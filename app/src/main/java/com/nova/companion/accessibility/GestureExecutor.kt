package com.nova.companion.accessibility

import android.accessibilityservice.GestureDescription
import android.graphics.Path

object GestureExecutor {

    fun createTap(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
    }

    fun createLongPress(x: Float, y: Float, durationMs: Long = 1000): GestureDescription {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
    }

    fun createSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300
    ): GestureDescription {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
    }

    fun createDoubleTap(x: Float, y: Float): List<GestureDescription> {
        return listOf(createTap(x, y), createTap(x, y))
    }
}
