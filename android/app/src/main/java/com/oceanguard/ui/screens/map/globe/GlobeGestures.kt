package com.oceanguard.ai.ui.screens.map.globe

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker

/** What the globe does with touches; all lengths are in px and are converted by the receiver. */
internal class GlobeGestureHandler(
    val onTouch: () -> Unit,
    val onDrag: (dx: Float, dy: Float) -> Unit,
    val onPinch: (scale: Float) -> Unit,
    val onRelease: (vx: Float, vy: Float) -> Unit,
    val onDoubleTap: () -> Unit,
)

/**
 * One finger drags the globe and keeps it turning when released, two fingers pinch to zoom, a
 * double tap zooms in. Taps that land on a marker never reach this: the marker takes them.
 */
internal fun Modifier.globeGestures(handler: GlobeGestureHandler): Modifier = this
    .pointerInput(handler) { detectTapGestures(onDoubleTap = { handler.onDoubleTap() }) }
    .pointerInput(handler) { detectDragAndPinch(handler) }

private suspend fun PointerInputScope.detectDragAndPinch(handler: GlobeGestureHandler) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        handler.onTouch()
        val tracker = VelocityTracker()
        var multiTouch = false
        var moved = false
        do {
            val event = awaitPointerEvent()
            multiTouch = multiTouch || event.changes.size > 1
            moved = applyMotion(event, handler, tracker) || moved
        } while (event.changes.any { it.pressed })
        // A tap is not a fling: flinging at zero speed would take the camera and cancel the flight a
        // tapped marker has just started.
        if (!multiTouch && moved) {
            val v = tracker.calculateVelocity()
            handler.onRelease(v.x, v.y)
        }
    }
}

/** Applies one pointer event; true when it panned or pinched the globe. */
private fun applyMotion(event: PointerEvent, handler: GlobeGestureHandler, tracker: VelocityTracker): Boolean {
    val pan = event.calculatePan()
    val zoom = event.calculateZoom()
    val centroid = event.calculateCentroid(useCurrent = true)
    if (centroid != Offset.Unspecified && event.changes.none { it.changedToUp() }) {
        tracker.addPosition(event.changes.first().uptimeMillis, centroid)
    }
    if (zoom != 1f) handler.onPinch(zoom)
    if (pan != Offset.Zero) handler.onDrag(pan.x, pan.y)
    event.changes.forEach { if (it.positionChanged()) it.consume() }
    return zoom != 1f || pan != Offset.Zero
}
