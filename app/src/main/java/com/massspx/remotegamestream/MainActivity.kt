package com.massspx.remotegamestream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val requestCapture = 4102
    private lateinit var projectionManager: MediaProjectionManager
    private val executor = Executors.newSingleThreadExecutor()
    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    private lateinit var streamImage: ImageView
    private lateinit var status: TextView
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        showHome()
    }

    private fun showHome() {
        val root = column()
        root.addView(text("REMOTE GAME STREAM", 24f))
        root.addView(text("Host ဖုန်းမှာ Game ကိုဖွင့်ပြီး Screen Share စတင်ပါ။ Client ဖုန်းက Wi-Fi တစ်ခုတည်းနဲ့ ချိတ်ပါ။", 15f))
        root.addView(button("HOST MODE — ဒီဖုန်းက Game ကိုထုတ်လွှင့်မယ်") {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), requestCapture)
        })
        root.addView(button("CLIENT MODE — တခြားဖုန်းကို ချိတ်မယ်") { showClientSetup() })
        root.addView(button("Remote Touch ခွင့်ပြုချက်ဖွင့်ရန်") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(text("မှတ်ချက် — Host ဖုန်းမှာ Accessibility Service ကို ကိုယ်တိုင်ဖွင့်ပေးရပါမယ်။", 13f))
        setContentView(root)
    }

    private fun showClientSetup() {
        val root = column()
        root.addView(text("CLIENT MODE", 22f))
        val ip = EditText(this).apply {
            hint = "Host IP ဥပမာ 192.168.1.5"
            setSingleLine(true)
        }
        root.addView(ip)
        root.addView(button("ချိတ်ဆက်မယ်") {
            val host = ip.text.toString().trim()
            if (host.isBlank()) {
                Toast.makeText(this, "Host IP ထည့်ပါ", Toast.LENGTH_SHORT).show()
            } else {
                showClientScreen(host)
            }
        })
        root.addView(button("နောက်သို့") { showHome() })
        setContentView(root)
    }

    private fun showClientScreen(host: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff101010.toInt())
        }
        status = text("ချိတ်ဆက်နေသည် — $host:5000", 14f)
        root.addView(status)
        streamImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xff000000.toInt())
        }
        root.addView(streamImage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("ချိတ်ဆက်မှုဖြတ်မယ်") {
            try { clientSocket?.close() } catch (_: Exception) {}
            showHome()
        })
        setContentView(root)

        executor.execute {
            try {
                val socket = Socket(host, 5000)
                clientSocket = socket
                socket.tcpNoDelay = true
                val writer = PrintWriter(BufferedOutputStream(socket.getOutputStream()), true)
                clientWriter = writer
                runOnUiThread { status.text = "ချိတ်ဆက်ပြီး — $host" }
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                while (!socket.isClosed) {
                    val size = input.readInt()
                    if (size <= 0 || size > 8 * 1024 * 1024) throw IllegalStateException("Frame size မမှန်ပါ")
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    runOnUiThread { streamImage.setImageBitmap(bitmap) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (::status.isInitialized) status.text = "ချိတ်ဆက်မှု ပြတ်သွားသည်: ${e.message ?: "Network error"}"
                }
            }
        }

        streamImage.setOnTouchListener { view, event ->
            val writer = clientWriter
            if (writer == null) {
                false
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val point = normalizedPoint(view, event.x, event.y)
                        if (point == null) false else {
                            touchStartX = point.first
                            touchStartY = point.second
                            touchStartTime = event.eventTime
                            true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val point = normalizedPoint(view, event.x, event.y)
                        if (point != null) {
                            val duration = (event.eventTime - touchStartTime).coerceIn(80L, 1000L)
                            synchronized(writer) {
                                writer.println("G,$touchStartX,$touchStartY,${point.first},${point.second},$duration")
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
    }

    private fun normalizedPoint(view: ImageView, x: Float, y: Float): Pair<Float, Float>? {
        val drawable = view.drawable ?: return null
        val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        view.imageMatrix.mapRect(rect)
        if (rect.width() <= 0f || rect.height() <= 0f || !rect.contains(x, y)) return null
        val nx = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ny = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
        return nx to ny
    }
    @Deprecated("Deprecated by Android, retained for compatibility with older versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, HostStreamService::class.java).apply {
                putExtra(HostStreamService.EXTRA_RESULT_CODE, resultCode)
                putExtra(HostStreamService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(service)
            val ip = wifiIpAddress()
            Toast.makeText(this, "Host စတင်နေသည်။ Client မှာ $ip:5000 ထည့်ပါ", Toast.LENGTH_LONG).show()
            val root = column()
            root.addView(text("HOST MODE စတင်နေသည်", 22f))
            root.addView(text("Host IP: $ip\nPort: 5000\nClient ဖုန်းနှင့် Wi-Fi တစ်ခုတည်းကို ချိတ်ထားပါ။", 17f))
            root.addView(button("Accessibility Settings ဖွင့်ရန်") {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            root.addView(button("Screen Stream ရပ်မယ်") {
                stopService(Intent(this, HostStreamService::class.java))
                showHome()
            })
            root.addView(button("Home သို့ပြန်မယ်") { showHome() })
            setContentView(root)
        }
    }

    private fun wifiIpAddress(): String {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wifi.connectionInfo.ipAddress
            if (ip == 0) "Wi-Fi IP မရ" else
                listOf(ip and 255, ip shr 8 and 255, ip shr 16 and 255, ip shr 24 and 255).joinToString(".")
        } catch (_: Exception) { "Wi-Fi IP မရ" }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 28, 22, 22)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, 12, 0, 12)
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        setOnClickListener { action() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { clientSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
