package com.massspx.remotegamestream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class HostStreamService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "remote_stream_channel"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private val running = AtomicBoolean(false)
    private val outputLock = Any()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(55, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.get()) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, android.os.Handler(mainLooper))
            running.set(true)
            Thread({ serve() }, "stream-server").start()
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun serve() {
        try {
            server = ServerSocket(5000).apply { reuseAddress = true }
            startCapture()

            while (running.get()) {
                val connected = try {
                    server?.accept() ?: break
                } catch (e: Exception) {
                    if (running.get()) throw e
                    break
                }
                client = connected
                val clientRunning = AtomicBoolean(true)
                try {
                    connected.tcpNoDelay = true
                    val out = DataOutputStream(BufferedOutputStream(connected.getOutputStream()))
                    Thread({
                        readRemoteInput(connected, clientRunning)
                    }, "remote-input").start()

                    while (running.get() && clientRunning.get() && !connected.isClosed) {
                        val image = reader?.acquireLatestImage()
                        if (image != null) {
                            try {
                                val jpeg = imageToJpeg(image)
                                if (jpeg != null) {
                                    synchronized(outputLock) {
                                        if (clientRunning.get() && !connected.isClosed) {
                                            out.writeInt(jpeg.size)
                                            out.write(jpeg)
                                            out.flush()
                                        }
                                    }
                                }
                            } finally {
                                image.close()
                            }
                        }
                        Thread.sleep(75)
                    }
                } catch (_: Exception) {
                    // End this client session; keep the host server available for reconnect.
                } finally {
                    clientRunning.set(false)
                    try { connected.close() } catch (_: Exception) {}
                    client = null
                }
            }
        } catch (_: Exception) {
            // Server stopped or screen capture failed.
        } finally {
            running.set(false)
            stopCapture()
            try { server?.close() } catch (_: Exception) {}
            server = null
        }
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val sourceWidth = metrics.widthPixels.coerceAtLeast(1)
        val sourceHeight = metrics.heightPixels.coerceAtLeast(1)
        val scale = 640f / maxOf(sourceWidth, sourceHeight)
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "RemoteGameStream", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = if (paddedWidth == image.width) padded else
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val bytes = java.io.ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 55, bytes)
        if (cropped !== padded) cropped.recycle()
        padded.recycle()
        return bytes.toByteArray()
    }

    private fun readRemoteInput(socket: Socket, clientRunning: AtomicBoolean) {
        try {
            val input = socket.getInputStream().bufferedReader()
            while (running.get() && clientRunning.get() && !socket.isClosed) {
                val line = input.readLine() ?: break
                val parts = line.split(",")
                if (parts.size == 6 && parts[0] == "G") {
                    val values = parts.drop(1).map { it.toFloat() }
                    RemoteInputAccessibilityService.performNormalizedGesture(
                        values[0], values[1], values[2], values[3], values[4].toLong()
                    )
                }
            }
        } catch (_: Exception) {
        } finally {
            clientRunning.set(false)
        }
    }

    private fun stopCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Game screen streaming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Remote Game Stream")
                .setContentText("Host screen sharing is active on port 5000")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Remote Game Stream")
                .setContentText("Screen sharing active").setSmallIcon(android.R.drawable.presence_video_online).build()
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { client?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        stopCapture()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
