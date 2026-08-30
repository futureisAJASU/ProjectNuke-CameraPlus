package com.projectnuke.keplernightlab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/**
 * Ergonomic dock threshold, expressed relative to the shutter radius so it
 * scales with the actual hit target instead of an arbitrary absolute distance.
 */
internal const val FLOATING_DOCK_RADIUS_MULTIPLIER = 2.5f

enum class FloatingShutterHaptic {
    /** Emitted once when the main shutter long-press reaches the activation threshold. */
    ACTIVATE,

    /** Optional / preferred when a floating long-press enters move mode. */
    BEGIN_DRAG
}

data class FloatingShutterBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
) {
    val isDegenerate: Boolean get() = maxX <= minX || maxY <= minY
}

/**
 * Geometry used to clamp/dock the floating shutter, all in LAYOUT-LOCAL PIXELS.
 * Coordinates are relative to the camera root Box top-left.
 */
data class FloatingShutterGeometry(
    val bounds: FloatingShutterBounds,
    val dockCenter: Offset,
    val dockRadiusPx: Float
)

fun computeFloatingShutterGeometry(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    shutterRadiusPx: Float,
    dockCenterPx: Offset
): FloatingShutterGeometry? {
    if (viewportWidthPx <= 0f || viewportHeightPx <= 0f || shutterRadiusPx <= 0f) return null
    val maxX = (viewportWidthPx - shutterRadiusPx).coerceAtLeast(shutterRadiusPx)
    val maxY = (viewportHeightPx - shutterRadiusPx).coerceAtLeast(shutterRadiusPx)
    return FloatingShutterGeometry(
        bounds = FloatingShutterBounds(
            minX = shutterRadiusPx,
            maxX = maxX,
            minY = shutterRadiusPx,
            maxY = maxY
        ),
        dockCenter = dockCenterPx,
        dockRadiusPx = shutterRadiusPx * FLOATING_DOCK_RADIUS_MULTIPLIER
    )
}

fun clampFloatingPosition(position: Offset, geometry: FloatingShutterGeometry?): Offset {
    val g = geometry ?: return position
    if (g.bounds.isDegenerate) return position
    return Offset(
        x = position.x.coerceIn(g.bounds.minX, g.bounds.maxX),
        y = position.y.coerceIn(g.bounds.minY, g.bounds.maxY)
    )
}

fun isWithinDock(position: Offset, geometry: FloatingShutterGeometry?): Boolean {
    val g = geometry ?: return false
    return hypot(position.x - g.dockCenter.x, position.y - g.dockCenter.y) <= g.dockRadiusPx
}

/**
 * Pure floating-shutter controller. State and position are Compose state so the
 * camera screen recomposes on transitions; all transition/geometry rules are
 * delegated to the pure functions above so they can be unit-tested directly.
 */
class FloatingShutterController(
    private val onHaptic: (FloatingShutterHaptic) -> Unit = {}
) {
    var state by mutableStateOf(FloatingShutterState.DOCKED)
        private set

    var position by mutableStateOf(Offset.Zero)
        private set

    var geometry by mutableStateOf<FloatingShutterGeometry?>(null)
        private set

    fun updateGeometry(value: FloatingShutterGeometry?) {
        geometry = value
        if (state != FloatingShutterState.DOCKED) {
            position = clampFloatingPosition(position, value)
        }
    }

    fun activateFloating(initialPosition: Offset): Boolean {
        if (state != FloatingShutterState.DOCKED) return false
        onHaptic(FloatingShutterHaptic.ACTIVATE)
        position = clampFloatingPosition(initialPosition, geometry)
        state = FloatingShutterState.FLOATING_IDLE
        return true
    }

    fun startDrag(): Boolean {
        if (state != FloatingShutterState.FLOATING_IDLE) return false
        onHaptic(FloatingShutterHaptic.BEGIN_DRAG)
        state = FloatingShutterState.FLOATING_DRAGGING
        return true
    }

    fun dragBy(delta: Offset) {
        if (state != FloatingShutterState.FLOATING_DRAGGING) return
        position = clampFloatingPosition(position + delta, geometry)
    }

    fun endDrag() {
        if (state != FloatingShutterState.FLOATING_DRAGGING) return
        if (isWithinDock(position, geometry)) {
            state = FloatingShutterState.DOCKED
            position = Offset.Zero
        } else {
            state = FloatingShutterState.FLOATING_IDLE
        }
    }

    fun tap(): Boolean = state == FloatingShutterState.FLOATING_IDLE
}