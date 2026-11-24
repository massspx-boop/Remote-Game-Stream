package com.massspx.remotegamestream

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RemoteInputAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: RemoteInputAccessibilityService? = null

        fun performNormalizedGesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
            val service = active ?: return
            val metrics = service.resources.displayMetrics
            val path = Path().apply {
                moveTo(x1.coerceIn(0f, 1f) * metrics.widthPixels,
                    y1.coerceIn(0f, 1f) * metrics.heightPixels)
                lineTo(x2.coerceIn(0f, 1f) * metrics.widthPixels,
                    y2.coerceIn(0f, 1f) * metrics.heightPixels)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceIn(80, 1000))
            android.os.Handler(service.mainLooper).post {
                service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }
    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }
}
