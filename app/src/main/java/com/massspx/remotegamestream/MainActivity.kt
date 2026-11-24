package com.massspx.remotegamestream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val requestCapture = 4102
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var clientChannel: SecureChannel? = null
    @Volatile private var discoveredHost: String? = null
    private lateinit var projectionManager: MediaProjectionManager
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
        root.addView(text("ဖုန်းအချင်းချင်း ဂိမ်းမျက်နှာပြင်မျှဝေခြင်း", 22f))
        root.addView(text("Host ဖုန်းမှာ Hotspot ဖွင့်ပါ။ Client ဖုန်းကို အဲဒီ Hotspot နဲ့ Wi-Fi ချိတ်ပါ။ ပြီးမှ ဒီ App ကို ဖွင့်ပါ။", 15f))
        root.addView(button("ဒီဖုန်းက ဂိမ်းကို ထုတ်လွှင့်မယ် (Host)") {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), requestCapture)
        })
        root.addView(button("တခြားဖုန်းရဲ့ ဂိမ်းကို ကြည့်မယ် (Client)") { showClientSetup() })
        root.addView(button("အဝေးထိန်း Touch ခွင့်ပြုချက်ဖွင့်ရန်") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(text("Host ဖုန်းမှာ Screen Share ခွင့်ပြုချက်နဲ့ Accessibility ခွင့်ပြုချက်ကို ကိုယ်တိုင်ဖွင့်ပေးရပါမယ်။", 13f))
        setContentView(root)
    }

    private fun showClientSetup() {
        discoveredHost = null
        val root = column()
        root.addView(text("Host ဖုန်းကို အလိုအလျောက်ရှာနေသည်", 21f))
        val found = text("Wi-Fi ချိတ်ဆက်မှုကို စစ်ဆေးနေသည်…", 15f)
        status = found
        root.addView(found)
        val pairing = EditText(this).apply {
            hint = "Host ဖုန်းပေါ်က Pairing Code ၁၂ လုံး"
            setSingleLine(true)
            isSingleLine = true
        }
        root.addView(pairing)
        root.addView(text("IP လိပ်စာကို ကိုယ်တိုင်မထည့်ရပါ။ Host ကိုတွေ့ပြီးမှ လုံခြုံရေး Code ထည့်ပါ။", 13f))
        val connect = button("လုံခြုံစွာ ချိတ်ဆက်မယ်") {
            val host = discoveredHost
            val code = pairing.text.toString().trim().uppercase(Locale.US)
            if (host == null) {
                Toast.makeText(this, "Host ကို အရင်ရှာပါ", Toast.LENGTH_SHORT).show()
            } else if (code.length != 12) {
                Toast.makeText(this, "Host ဖုန်းပေါ်က Code ၁၂ လုံး ထည့်ပါ", Toast.LENGTH_SHORT).show()
            } else {
                showClientScreen(host, code)
            }
        }
        connect.isEnabled = false
        root.addView(connect)
        root.addView(button("Host ကို ပြန်ရှာမယ်") {
            found.text = "Host ကို ပြန်ရှာနေသည်…"
            connect.isEnabled = false
            discoveredHost = null
            discoverHost { host ->
                discoveredHost = host
                found.text = if (host != null) "Host ဖုန်းကို တွေ့ပါပြီ။ လုံခြုံရေး Code ထည့်ပါ။" else "Host မတွေ့ပါ။ Hotspot နဲ့ Wi-Fi ကို စစ်ပြီး ပြန်ရှာပါ။"
                connect.isEnabled = host != null
            }
        })
        root.addView(button("နောက်သို့") { showHome() })
        setContentView(root)

        discoverHost { host ->
            discoveredHost = host
            if (!isFinishing) {
                found.text = if (host != null) "Host ဖုန်းကို တွေ့ပါပြီ။ လုံခြုံရေး Code ထည့်ပါ။" else "Host မတွေ့ပါ။ ဖုန်းနှစ်လုံး Wi-Fi တစ်ခုတည်းမှာ ရှိမရှိ စစ်ပါ။"
                connect.isEnabled = host != null
            }
        }
    }

    private fun discoverHost(onResult: (String?) -> Unit) {
        executor.execute {
            val host = findHostOnLocalWifi()
            runOnUiThread { onResult(host) }
        }
    }

    private fun findHostOnLocalWifi(): String? {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = try { wifi.dhcpInfo } catch (_: Exception) { null }

        // First try UDP discovery so merely searching does not open a TCP
        // connection that would be mistaken for a failed secure handshake.
        try {
            val socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 1800
            }
            val request = "RGS_DISCOVER_V1".toByteArray(Charsets.UTF_8)
            val targets = linkedSetOf<InetAddress>()
            targets.add(InetAddress.getByName("255.255.255.255"))
            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                val broadcast = (dhcp.ipAddress and dhcp.netmask.inv()) or dhcp.netmask
                targets.add(InetAddress.getByName(intToIp(broadcast)))
            }
            for (target in targets) {
                try { socket.send(DatagramPacket(request, request.size, target, 5001)) } catch (_: Exception) {}
            }
            val buffer = ByteArray(128)
            val response = DatagramPacket(buffer, buffer.size)
            val deadline = System.currentTimeMillis() + 1800
            while (System.currentTimeMillis() < deadline) {
                try {
                    response.length = buffer.size
                    socket.receive(response)
                    val message = String(response.data, response.offset, response.length, Charsets.UTF_8)
                    if (message == "RGS_HOST_V1|5000") {
                        val address = response.address.hostAddress
                        socket.close()
                        if (!address.isNullOrBlank()) return address
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
            socket.close()
        } catch (_: Exception) {}

        // On Android phone hotspots, the DHCP gateway is normally the host phone.
        // Use it only as a fallback after broadcast discovery.
        if (dhcp != null && dhcp.gateway != 0) return intToIp(dhcp.gateway)
        return null
    }

    private fun intToIp(value: Int): String =
        listOf(value and 255, value shr 8 and 255, value shr 16 and 255, value shr 24 and 255).joinToString(".")

    private fun showClientScreen(host: String, pairingCode: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff101010.toInt())
        }
        status = text("Host ဖုန်းနဲ့ လုံခြုံစွာ ချိတ်ဆက်နေသည်…", 14f)
        root.addView(status)
        streamImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xff000000.toInt())
        }
        root.addView(streamImage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("ချိတ်ဆက်မှု ဖြတ်မယ်") {
            clientChannel = null
            try { clientSocket?.close() } catch (_: Exception) {}
            showHome()
        })
        setContentView(root)

        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, 5000), 10_000)
                socket.tcpNoDelay = true
                clientSocket = socket
                val channel = SecureChannel.connect(socket, pairingCode)
                clientChannel = channel
                runOnUiThread { status.text = "ချိတ်ဆက်ပြီး — ဂိမ်းမျက်နှာပြင်ကို လက်နဲ့ထိန်းနိုင်ပါပြီ" }
                while (!socket.isClosed) {
                    val bytes = channel.receive()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    runOnUiThread {
                        if (!isFinishing && ::streamImage.isInitialized) streamImage.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                clientChannel = null
                runOnUiThread {
                    if (::status.isInitialized) status.text =
                        if (e is SecurityException) "လုံခြုံရေး Code မမှန်ပါ။ Host ပေါ်က Code ကို စစ်ပါ။"
                        else "ချိတ်ဆက်မှု မအောင်မြင်ပါ။ Hotspot နဲ့ Host App ကို စစ်ပါ။"
                }
            }
        }

        streamImage.setOnTouchListener { _, event ->
            val channel = clientChannel
            if (channel == null) false else when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val point = normalizedPoint(streamImage, event.x, event.y)
                    if (point == null) false else {
                        touchStartX = point.first
                        touchStartY = point.second
                        touchStartTime = event.eventTime
                        true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val point = normalizedPoint(streamImage, event.x, event.y)
                    if (point != null) {
                        val duration = (event.eventTime - touchStartTime).coerceIn(80L, 1000L)
                        val command = "G,$touchStartX,$touchStartY,${point.first},${point.second},$duration"
                        try { channel.send(command.toByteArray(Charsets.UTF_8)) }
                        catch (_: Exception) { clientChannel = null }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun normalizedPoint(view: ImageView, x: Float, y: Float): Pair<Float, Float>? {
        val drawable = view.drawable ?: return null
        val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        view.imageMatrix.mapRect(rect)
        if (rect.width() <= 0f || rect.height() <= 0f || !rect.contains(x, y)) return null
        return ((x - rect.left) / rect.width()).coerceIn(0f, 1f) to
            ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
    }

    @Deprecated("Deprecated by Android, retained for compatibility with older versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val pairingCode = SecureChannel.generatePairingCode()
            val service = Intent(this, HostStreamService::class.java).apply {
                putExtra(HostStreamService.EXTRA_RESULT_CODE, resultCode)
                putExtra(HostStreamService.EXTRA_RESULT_DATA, data)
                putExtra(HostStreamService.EXTRA_PAIRING_CODE, pairingCode)
            }
            startForegroundService(service)
            val root = column()
            root.addView(text("Host အဖြစ် အသင့်ဖြစ်နေပါပြီ", 22f))
            root.addView(text("၁။ ဒီဖုန်းမှာ Hotspot ဖွင့်ထားပါ။\n၂။ တခြားဖုန်းကို ဒီ Hotspot နဲ့ ချိတ်ပါ။\n၃။ တခြားဖုန်းမှာ App ဖွင့်ပြီး Client ကိုရွေးပါ။", 15f))
            root.addView(text("လုံခြုံရေး Code", 16f))
            root.addView(text(pairingCode, 26f))
            root.addView(text("ဒီ Code ကို ကိုယ်ပိုင် Client ဖုန်းမှာသာ ထည့်ပါ။ မသိသူကို မမျှဝေပါနှင့်။", 13f))
            root.addView(button("အဝေးထိန်း Touch ခွင့်ပြုချက်ဖွင့်ရန်") {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            root.addView(button("ထုတ်လွှင့်မှု ရပ်မယ်") {
                stopService(Intent(this, HostStreamService::class.java))
                showHome()
            })
            setContentView(root)
        }
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
        clientChannel = null
        try { clientSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
