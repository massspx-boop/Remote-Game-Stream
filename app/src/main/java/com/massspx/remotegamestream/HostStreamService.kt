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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class HostStreamService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val CHANNEL_ID = "remote_stream_channel"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var server: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    @Volatile private var client: Socket? = null
    private var pairingCode: String? = null
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(55, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.get()) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        pairingCode = intent?.getStringExtra(EXTRA_PAIRING_CODE)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null || pairingCode?.length != 12) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
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
            // Discovery is an optional convenience; a UDP bind failure must not stop TCP streaming.
            try { startDiscoveryListener() } catch (_: Exception) {}

            var failedHandshakes = 0
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
                    if (!isPrivateLanClient(connected)) throw SecurityException("Non-private network client rejected")
                    val channel = SecureChannel.accept(connected, pairingCode ?: throw SecurityException("Missing pairing code"))
                    failedHandshakes = 0
                    Thread({ readRemoteInput(channel, connected, clientRunning) }, "remote-input").start()

                    while (running.get() && clientRunning.get() && !connected.isClosed) {
                        val image = reader?.acquireLatestImage()
                        if (image != null) {
                            try {
                                val jpeg = imageToJpeg(image)
                                if (jpeg != null && clientRunning.get()) channel.send(jpeg)
                            } finally {
                                image.close()
                            }
                        }
                        Thread.sleep(75)
                    }
                } catch (e: Exception) {
                    if (e is SecurityException || e is java.net.SocketTimeoutException) {
                        failedHandshakes++
                        if (failedHandshakes >= 5) {
                            try { Thread.sleep(30_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                            failedHandshakes = 0
                        } else {
                            try { Thread.sleep(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        }
                    }
                } finally {
                    clientRunning.set(false)
                    try { connected.close() } catch (_: Exception) {}
                    client = null
                }
            }
        } catch (_: Exception) {
        } finally {
            running.set(false)
            try { discoverySocket?.close() } catch (_: Exception) {}
            discoverySocket = null
            stopCapture()
            try { server?.close() } catch (_: Exception) {}
            server = null
        }
    }

    private fun startDiscoveryListener() {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
            bind(InetSocketAddress(5001))
        }
        discoverySocket = socket
        Thread({
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = try { wifi.createMulticastLock("remote-game-discovery").apply { setReferenceCounted(false); acquire() } } catch (_: Exception) { null }
            val buffer = ByteArray(128)
            try {
                while (running.get() && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val request = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        if (request == "RGS_DISCOVER_V1" && isPrivateLanAddress(packet.address)) {
                            val reply = "RGS_HOST_V1|5000".toByteArray(Charsets.UTF_8)
                            socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            } finally {
                try { lock?.release() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }
        }, "host-discovery").start()
    }

    private fun isPrivateLanAddress(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress

    private fun isPrivateLanClient(socket: Socket): Boolean {
        val address = (socket.remoteSocketAddress as? InetSocketAddress)?.address ?: return false
        return isPrivateLanAddress(address)
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
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
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
        val cropped = if (paddedWidth == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val bytes = java.io.ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 55, bytes)
        if (cropped !== padded) cropped.recycle()
        padded.recycle()
        return bytes.toByteArray()
    }

    private fun readRemoteInput(channel: SecureChannel, socket: Socket, clientRunning: AtomicBoolean) {
        try {
            while (running.get() && clientRunning.get() && !socket.isClosed) {
                val bytes = channel.receive()
                if (bytes.size > 256) throw SecurityException("Input command too large")
                val parts = bytes.toString(Charsets.UTF_8).split(",")
                if (parts.size != 6 || parts[0] != "G") continue
                val values = parts.drop(1).map { it.toFloatOrNull() ?: Float.NaN }
                if (values.any { !it.isFinite() } ||
                    values[0] !in 0f..1f || values[1] !in 0f..1f ||
                    values[2] !in 0f..1f || values[3] !in 0f..1f) continue
                val duration = parts[5].toLongOrNull() ?: continue
                if (duration !in 80L..1000L) continue
                RemoteInputAccessibilityService.performNormalizedGesture(values[0], values[1], values[2], values[3], duration)
            }
        } catch (_: Exception) {
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
            val channel = NotificationChannel(CHANNEL_ID, "ဂိမ်းမျက်နှာပြင် ထုတ်လွှင့်မှု", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Remote Game Stream")
                .setContentText("ဂိမ်းမျက်နှာပြင်ကို လုံခြုံစွာ ထုတ်လွှင့်နေသည်")
                .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Remote Game Stream")
                .setContentText("ဂိမ်းမျက်နှာပြင် ထုတ်လွှင့်နေသည်")
                .setSmallIcon(android.R.drawable.presence_video_online).build()
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { client?.close() } catch (_: Exception) {}
        try { discoverySocket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        stopCapture()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        pairingCode = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
