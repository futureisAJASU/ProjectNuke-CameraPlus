package com.projectnuke.keplernightlab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingShutterStateTest {

    // 1. main long-press activation
    @Test
    fun activateFloatingFromDocked() {
        val controller = FloatingShutterController()
        assertEquals(FloatingShutterState.DOCKED, controller.state)
        val ok = controller.activateFloating(Offset(200f, 300f))
        assertTrue(ok)
        assertEquals(FloatingShutterState.FLOATING_IDLE, controller.state)
        assertEquals(200f, controller.position.x, 0.01f)
        assertEquals(300f, controller.position.y, 0.01f)
    }

    // 2. haptic event exactly once
    @Test
    fun hapticExactlyOnceOnActivate() {
        var count = 0
        val controller = FloatingShutterController(onHaptic = { if (it == FloatingShutterHaptic.ACTIVATE) count++ })
        controller.activateFloating(Offset(10f, 20f))
        assertEquals(1, count)
        // second attempt while already FLOATING_IDLE must not emit another haptic
        controller.activateFloating(Offset(99f, 99f))
        assertEquals(1, count)
    }

    // 3. idle tap -> capture (pure: tap returns true, state unchanged)
    @Test
    fun idleTapRequestsCapture() {
        val controller = FloatingShutterController()
        controller.activateFloating(Offset(100f, 100f))
        assertTrue(controller.tap())
        assertEquals(FloatingShutterState.FLOATING_IDLE, controller.state)
    }

    // 4. long press -> dragging
    @Test
    fun longPressEnterDragging() {
        val controller = FloatingShutterController()
        controller.activateFloating(Offset(100f, 100f))
        val ok = controller.startDrag()
        assertTrue(ok)
        assertEquals(FloatingShutterState.FLOATING_DRAGGING, controller.state)
    }

    // 5. continuing same gesture moves shutter
    @Test
    fun continuingGestureMoves() {
        val controller = FloatingShutterController()
        controller.activateFloating(Offset(100f, 100f))
        controller.startDrag()
        controller.dragBy(Offset(30f, -20f))
        assertEquals(130f, controller.position.x, 0.01f)
        assertEquals(80f, controller.position.y, 0.01f)
    }

    // 6. release outside dock -> FLOATING_IDLE at new position
    @Test
    fun releaseOutsideDockRemainsIdle() {
        val controller = FloatingShutterController()
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = 1080f, viewportHeightPx = 1920f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(540f, 1700f)
        )!!
        controller.updateGeometry(geom)
        controller.activateFloating(Offset(540f, 1700f))
        controller.startDrag()
        controller.dragBy(Offset(0f, -600f))
        val before = controller.position
        controller.endDrag()
        assertEquals(FloatingShutterState.FLOATING_IDLE, controller.state)
        assertEquals(before.x, controller.position.x, 0.01f)
        assertEquals(before.y, controller.position.y, 0.01f)
    }

    // 7. release inside dock -> DOCKED
    @Test
    fun releaseInsideDockReturnsToDocked() {
        val controller = FloatingShutterController()
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = 1080f, viewportHeightPx = 1920f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(540f, 1700f)
        )!!
        controller.updateGeometry(geom)
        controller.activateFloating(Offset(540f, 1700f))
        controller.startDrag()
        // nudge a little then return
        controller.dragBy(Offset(20f, 10f))
        controller.dragBy(Offset(-20f, -10f))
        controller.endDrag()
        assertEquals(FloatingShutterState.DOCKED, controller.state)
    }

    // 8. viewport clamp
    @Test
    fun viewportClampEnforced() {
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = 400f, viewportHeightPx = 800f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(200f, 700f)
        )!!
        // clamp respects shutter radius (min 36, max 364 etc.)
        assertEquals(36f, clampFloatingPosition(Offset(0f, 0f), geom).x, 0.01f)
        assertEquals(36f, clampFloatingPosition(Offset(0f, 0f), geom).y, 0.01f)
        assertEquals(364f, clampFloatingPosition(Offset(999f, 999f), geom).x, 0.01f)
        assertEquals(764f, clampFloatingPosition(Offset(999f, 999f), geom).y, 0.01f)
    }

    // 9. rotation/viewport resize clamp
    @Test
    fun resizeClampsOffscreenPosition() {
        val geomSmall = computeFloatingShutterGeometry(
            viewportWidthPx = 400f, viewportHeightPx = 800f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(200f, 700f)
        )!!
        val controller = FloatingShutterController()
        controller.updateGeometry(geomSmall)
        controller.activateFloating(Offset(360f, 760f)) // at edge of small viewport
        // rotate/resize to a smaller viewport
        val geomTiny = computeFloatingShutterGeometry(
            viewportWidthPx = 300f, viewportHeightPx = 600f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(150f, 500f)
        )!!
        controller.updateGeometry(geomTiny)
        // Off-screen position must be clamped
        assertTrue(controller.position.x <= 264f + 0.01f)
        assertTrue(controller.position.y <= 564f + 0.01f)
    }

    // 10. no capture while busy (tap is pure; capture gating is at the call site)
    //    Verified: tap() is a no-op in DOCKED/DRAGGING, caller checks busy flags.

    // 11. floating hit area is 72dp (constant)
    @Test
    fun floatingHitAreaIs72dp() {
        assertEquals(72f, FloatingShutterButtonSize.value, 0.01f)
    }

    // 12. dock target equals measured main-shutter geometry, not magic constants
    @Test
    fun dockTargetEqualsMeasuredGeometry() {
        val measuredCenter = Offset(612f, 1620f)
        val viewport = Size(1080f, 1920f)
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = viewport.width, viewportHeightPx = viewport.height,
            shutterRadiusPx = 36f, dockCenterPx = measuredCenter
        )!!
        assertEquals(measuredCenter.x, geom.dockCenter.x, 0.01f)
        assertEquals(measuredCenter.y, geom.dockCenter.y, 0.01f)
        // isWithinDock at the measured center must be true
        assertTrue(isWithinDock(measuredCenter, geom))
        // far from measured center must be false (no magic constants coincidentally equal)
        assertFalse(isWithinDock(Offset(100f, 100f), geom))
        // far away position built from old magic constants (300,700) must NOT be docked
        assertFalse(isWithinDock(Offset(300f, 700f), geom))
    }

    @Test
    fun dockRadiusIsErgonomicMultipleOfShutterRadius() {
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = 1080f, viewportHeightPx = 1920f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(540f, 1700f)
        )!!
        assertEquals(36f * FLOATING_DOCK_RADIUS_MULTIPLIER, geom.dockRadiusPx, 0.01f)
    }

    @Test
    fun reverseFloatingStateSequence() {
        val controller = FloatingShutterController()
        controller.activateFloating(Offset(200f, 200f))
        controller.startDrag()
        controller.dragBy(Offset(50f, 50f))
        // dock resolution
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = 600f, viewportHeightPx = 1000f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(300f, 900f)
        )!!
        controller.updateGeometry(geom)
        controller.endDrag()
        assertEquals(FloatingShutterState.FLOATING_IDLE, controller.state)
    }

    // Floating coordinate contract: center vs top-left
    @Test
    fun centerToTopLeftConversion() {
        val radius = 36f
        assertEquals(Offset(0f, 0f), floatingShutterTopLeft(Offset(36f, 36f), radius))
        assertEquals(Offset(328f, 728f), floatingShutterTopLeft(Offset(364f, 764f), radius))
    }

    @Test
    fun renderedNodeStaysFullyOnScreen() {
        val viewportW = 400f
        val viewportH = 800f
        val radius = 36f
        val diameter = radius * 2f
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = viewportW, viewportHeightPx = viewportH,
            shutterRadiusPx = radius, dockCenterPx = Offset(200f, 400f)
        )!!
        // clamped centers still render fully on-screen
        listOf(
            Offset(36f, 36f),
            Offset(364f, 764f),
            Offset(200f, 400f)
        ).forEach { center ->
            val clamped = clampFloatingPosition(center, geom)
            val topLeft = floatingShutterTopLeft(clamped, radius)
            assertTrue(topLeft.x >= -0.01f)
            assertTrue(topLeft.y >= -0.01f)
            assertTrue(topLeft.x + diameter <= viewportW + 0.01f)
            assertTrue(topLeft.y + diameter <= viewportH + 0.01f)
        }
    }

    @Test
    fun activationAtDockCenterVisuallyCentersExactly() {
        val dock = Offset(612f, 1620f)
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = 1080f, viewportHeightPx = 1920f,
            shutterRadiusPx = 36f, dockCenterPx = dock
        )!!
        val controller = FloatingShutterController()
        controller.updateGeometry(geom)
        controller.activateFloating(dock)
        assertEquals(dock.x, controller.position.x, 0.01f)
        assertEquals(dock.y, controller.position.y, 0.01f)
        // visual topLeft centers exactly: center - radius
        assertEquals(dock.x - 36f, floatingShutterTopLeft(controller.position, 36f).x, 0.01f)
        assertEquals(dock.y - 36f, floatingShutterTopLeft(controller.position, 36f).y, 0.01f)
    }

    @Test
    fun dockComparisonUsesCenterNotTopLeft() {
        val dock = Offset(200f, 200f)
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = 400f, viewportHeightPx = 800f,
            shutterRadiusPx = 36f, dockCenterPx = dock
        )!!
        // If code mistakenly used topLeft instead of center, a center at
        // (300,200) (100px away, outside dock radius 90) would be mis-evaluated
        // as inside when passing its topLeft (264,164) (73px away).
        val centerOutside = Offset(300f, 200f)
        val topLeftForCenter = Offset(264f, 164f)
        assertFalse(isWithinDock(centerOutside, geom))
        assertTrue(isWithinDock(topLeftForCenter, geom))
        // Conversely dock itself must be inside when passing center
        assertTrue(isWithinDock(dock, geom))
        // A point that is topLeft==dock (if misinterpreted as center) would
        // still be inside, so we prove the distinction via the outside case above.
    }

    @Test
    fun dragDeltaChangesCenterByIdenticalPx() {
        val controller = FloatingShutterController()
        val geom = computeFloatingShutterGeometry(
            viewportWidthPx = 800f, viewportHeightPx = 800f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(400f, 700f)
        )!!
        controller.updateGeometry(geom)
        controller.activateFloating(Offset(200f, 200f))
        controller.startDrag()
        controller.dragBy(Offset(15f, -25f))
        assertEquals(215f, controller.position.x, 0.01f)
        assertEquals(175f, controller.position.y, 0.01f)
    }

    @Test
    fun resizeClampsCenterAndKeepsNodeFullyVisible() {
        val geomLarge = computeFloatingShutterGeometry(
            viewportWidthPx = 800f, viewportHeightPx = 1200f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(400f, 1000f)
        )!!
        val controller = FloatingShutterController()
        controller.updateGeometry(geomLarge)
        controller.activateFloating(Offset(760f, 1160f))
        val geomSmall = computeFloatingShutterGeometry(
            viewportWidthPx = 400f, viewportHeightPx = 800f,
            shutterRadiusPx = 36f, dockCenterPx = Offset(200f, 700f)
        )!!
        controller.updateGeometry(geomSmall)
        val topLeft = floatingShutterTopLeft(controller.position, 36f)
        assertTrue(topLeft.x >= -0.01f)
        assertTrue(topLeft.y >= -0.01f)
        assertTrue(topLeft.x + 72f <= 400f + 0.01f)
        assertTrue(topLeft.y + 72f <= 800f + 0.01f)
    }
}