package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class CameraPreviewControllerLifecycleTest {
    private class TrackingExecutor : AbstractExecutorService() {
        var shutdownCalls = 0
            private set
        private var stopped = false

        override fun execute(command: Runnable) {
            if (stopped) throw IllegalStateException("executor stopped")
            command.run()
        }

        override fun shutdown() {
            shutdownCalls++
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown()
            return Collections.emptyList<Runnable>().toMutableList()
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
    }

    private fun controller(executor: TrackingExecutor): CameraPreviewController =
        CameraPreviewController(
            context = null,
            cameraId = "0",
            physicalCameraId = null,
            actualLensSource = ActualLensSource.MAIN_1X,
            onAeCapabilitiesChangedProvider = { { _, _, _ -> } },
            onPreviewAvailabilityChangedProvider = { { _ -> } },
            mainDispatch = { it.run(); true },
            emergencyReleaseExecutor = executor
        )

    @Test
    fun temporaryStopKeepsEmergencyExecutorUsable() {
        val executor = TrackingExecutor()
        val controller = controller(executor)

        controller.stop()

        assertFalse(executor.isShutdown)
        assertEquals(0, executor.shutdownCalls)
    }

    @Test
    fun disposeShutsEmergencyExecutorExactlyOnceAndRejectsFutureCommands() {
        val executor = TrackingExecutor()
        val controller = controller(executor)

        controller.dispose()
        controller.dispose()
        controller.updateZoomRatio(3.0f)

        assertTrue(executor.isShutdown)
        assertEquals(1, executor.shutdownCalls)
        assertEquals(PreviewCommandApplyOutcome.DISPATCH_REJECTED, controller.commandSnapshot().lastOutcome)
    }

    @Test
    fun controllerFailuresPublishFailedAndSuccessfulGenerationPublishesAvailable() {
        val availability = mutableListOf<PreviewAvailability>()
        val executor = TrackingExecutor()
        val controller = CameraPreviewController(
            context = null,
            cameraId = "0",
            physicalCameraId = null,
            actualLensSource = ActualLensSource.MAIN_1X,
            onAeCapabilitiesChangedProvider = { { _, _, _ -> } },
            onPreviewAvailabilityChangedProvider = { { availability += it } },
            mainDispatch = { it.run(); true },
            emergencyReleaseExecutor = executor
        )

        val firstGeneration = controller.beginGenerationForTest()
        controller.previewThreadStartFailed(firstGeneration, IllegalStateException("thread"))
        assertTrue(controller.isFailed())
        assertEquals(PreviewAvailability.FAILED, availability.last())

        val secondGeneration = controller.beginGenerationForTest()
        assertTrue(controller.markOpenForTest(secondGeneration))
        assertFalse(controller.isFailed())
        assertEquals(PreviewAvailability.AVAILABLE, availability.last())
        controller.dispose()
    }

    @Test
    fun callbackExecutorAndNormalSessionFailuresPublishFailed() {
        listOf<(CameraPreviewController, Int) -> Unit>(
            { controller, generation ->
                controller.callbackDispatchFailed(generation, IllegalStateException("callback"))
            },
            { controller, generation ->
                controller.normalPreviewFailed(generation)
            }
        ).forEach { fail ->
            val availability = mutableListOf<PreviewAvailability>()
            val controller = CameraPreviewController(
                context = null,
                cameraId = "0",
                physicalCameraId = null,
                actualLensSource = ActualLensSource.MAIN_1X,
                onAeCapabilitiesChangedProvider = { { _, _, _ -> } },
                onPreviewAvailabilityChangedProvider = { { availability += it } },
                mainDispatch = { it.run(); true },
                emergencyReleaseExecutor = TrackingExecutor()
            )
            fail(controller, controller.beginGenerationForTest())
            assertEquals(PreviewAvailability.FAILED, availability.single())
            controller.dispose()
        }
    }

    @Test
    fun rejectedAvailableNotificationIsRecordedWhileControllerRemainsOpen() {
        val executor = TrackingExecutor()
        val availability = mutableListOf<PreviewAvailability>()
        val controller = CameraPreviewController(
            context = null,
            cameraId = "0",
            physicalCameraId = null,
            actualLensSource = ActualLensSource.MAIN_1X,
            onAeCapabilitiesChangedProvider = { { _, _, _ -> } },
            onPreviewAvailabilityChangedProvider = { { availability += it } },
            mainDispatch = { false },
            emergencyReleaseExecutor = executor
        )

        val generation = controller.beginGenerationForTest()
        assertTrue(controller.markOpenForTest(generation))
        assertEquals(PreviewGenerationOwner.State.OPEN, controller.generationSnapshotForTest().state)
        assertTrue(availability.isEmpty())
        assertTrue(controller.cleanupDiagnostics().availabilityDispatchFailure != null)
        controller.dispose()
    }

    @Test
    fun authoritativeCameraCallbackSettlementClosesOnceAndRetainsFailure() {
        listOf("disconnected", "error").forEach { callback ->
            val availability = mutableListOf<PreviewAvailability>()
            val controller = CameraPreviewController(
                context = null,
                cameraId = "0",
                physicalCameraId = null,
                actualLensSource = ActualLensSource.MAIN_1X,
                onAeCapabilitiesChangedProvider = { { _, _, _ -> } },
                onPreviewAvailabilityChangedProvider = { { availability += it } },
                mainDispatch = { it.run(); true },
                emergencyReleaseExecutor = TrackingExecutor()
            )
            val generation = controller.beginGenerationForTest()
            controller.stop()
            var closeCount = 0

            controller.settleAuthoritativeCameraCallback(
                localGeneration = generation,
                detach = { true },
                close = {
                    closeCount++
                    error("$callback close failed")
                },
                failure = IllegalStateException(callback)
            )

            assertEquals(1, closeCount)
            assertTrue(availability.isEmpty())
            val cleanup = controller.cleanupDiagnostics().lastCleanupSnapshot
            assertTrue(cleanup?.failures?.any {
                it.operation == PreviewResourceOperation.CAMERA_DEVICE_CLOSE
            } == true)
            controller.dispose()
        }
    }

    @Test
    fun adoptedSessionRequestFailureRetainsCloseFailureAndTransitionsToFallback() {
        val controller = controller(TrackingExecutor())
        val generation = controller.beginGenerationForTest()
        controller.stop()
        var closeCount = 0
        var fallbackCount = 0

        controller.settleAdoptedSessionRequestFailure(
            localGeneration = generation,
            detach = { true },
            close = {
                closeCount++
                error("session close failed")
            },
            onFailure = { fallbackCount++ }
        )

        assertEquals(1, closeCount)
        assertEquals(1, fallbackCount)
        assertTrue(
            controller.cleanupDiagnostics().lastCleanupSnapshot?.failures?.any {
                it.operation == PreviewResourceOperation.CAPTURE_SESSION_CLOSE
            } == true
        )
        controller.dispose()
    }

    @Test
    fun physicalFailureUsesOneNormalPreviewFallbackWhileGenerationIsActive() {
        val controller = controller(TrackingExecutor())
        val generation = controller.beginGenerationForTest()
        var fallbackCount = 0

        controller.transitionPhysicalToNormalPreview(generation) { fallbackCount++ }

        assertEquals(1, fallbackCount)
        controller.dispose()
    }
}
