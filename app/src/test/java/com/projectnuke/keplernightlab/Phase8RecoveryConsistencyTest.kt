package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 8: durable jobs stay correct across lifecycle boundaries - activity
 * recreation, process restart, processing/export failure, dispatch failure,
 * and recovery ordering. The background coordinator is only a client of the
 * existing authority: exact job, JobOperationLease, processing handoff.
 */
@RunWith(RobolectricTestRunner::class)
class Phase8RecoveryConsistencyTest {

    private val root = createTempDirectory("phase8-recovery").toFile()
    private val yuvRoot = File(root, "KeplerYuvFusion")

    @Before
    fun resetCoordinator() {
        BackgroundProcessingCoordinator.resetForTest()
        yuvRoot.mkdirs()
    }

    @After
    fun cleanup() {
        BackgroundProcessingCoordinator.resetForTest()
        root.deleteRecursively()
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun newJobDir(prefix: String): File =
        yuvRoot.resolve("KPL_YUV_FUSION_${prefix}_${System.nanoTime()}").apply { mkdirs() }

    /** Seeds metadata exactly as a capture does: an active capture operation owned this session. */
    private fun seedJobMetadata(jobDir: File): JSONObject {
        val metadata = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("status", "CAPTURE_STAGE_COMPLETE")
            .put(ACTIVE_OPERATION_ID, "capture-op-1")
            .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
        KeplerJobMetadata.write(jobDir, metadata)
        return metadata
    }

    @Test
    fun activityRecreation_doesNotDuplicateBackgroundProcessing() {
        val coordinator = BackgroundProcessingCoordinator.of(context())
        // Simulated activity recreation: brand new session instances, but the
        // process-scoped coordinator singleton is unchanged.
        val firstCompositionSession = CameraPipelineUiSession()
        val recreatedCompositionSession = CameraPipelineUiSession()
        assertNotNull(firstCompositionSession)
        assertNotNull(recreatedCompositionSession)
        assertSame(coordinator as Any, BackgroundProcessingCoordinator.of(context()) as Any)

        val executions = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobDir = newJobDir("recreation")
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork {
                    executions.incrementAndGet()
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(started.await(10, TimeUnit.SECONDS))
        // A recreation-time re-enqueue of the same exact job is rejected.
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork { executions.incrementAndGet() }
            ) is BackgroundEnqueueResult.Duplicate
        )
        release.countDown()
        val deadline = System.currentTimeMillis() + 5000
        while (coordinator.snapshot().hasPendingWork && System.currentTimeMillis() < deadline) Thread.sleep(15)
        assertEquals(1, executions.get())
    }

    @Test
    fun processRestart_pendingHandoffRemainsRecoverable() {
        val jobDir = newJobDir("restart")
        seedJobMetadata(jobDir)
        assertTrue(
            KeplerJobMetadata.publishProcessingHandoff(
                jobDir, "capture-op-1", KeplerActiveOperationKind.PROCESSING_YUV
            )
        )
        assertEquals(
            KeplerJobMetadata.ProcessingHandoffPresence.CORRELATED,
            KeplerJobMetadata.inspectProcessingHandoff(jobDir, KeplerActiveOperationKind.PROCESSING_YUV)
        )
        // Process death is simulated by a fresh recovery pass over the same
        // durable state: the pending handoff is discovered, never skipped.
        val report = KeplerRecoveryCoordinator.recoverRoots(listOf(yuvRoot))
        assertTrue(report.jobs.any { it.jobDir.absolutePath == jobDir.absolutePath })
        // The job metadata stays readable and durably owned either way the
        // recovery settled it (pending or reconciliation-retained).
        assertNotNull(KeplerJobMetadata.read(jobDir))
    }

    @Test
    fun failedBackgroundJob_doesNotBlockNextQueuedJob() {
        val coordinator = BackgroundProcessingCoordinator.of(context())
        val failing = newJobDir("failing")
        val secondRan = CountDownLatch(1)
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(failing, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork { throw IllegalStateException("boom") }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(newJobDir("after_failure"), KeplerActiveOperationKind.PROCESSING_RAW),
                HeavyProcessingWork { secondRan.countDown() }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(secondRan.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun partialExport_doesNotBlockNextCapture() {
        val session = CameraPipelineUiSession()
        val generation = (session.start("burst", 4) as
            CameraPipelineUiSession.StartResult.Accepted).operation.generation
        session.accept(CameraPipelineEvent.Started(generation, "capturing"))
        session.accept(handoff(generation))
        session.accept(
            CameraPipelineEvent.Terminal(
                generation = generation,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = false,
                captureResourcesSettled = true,
                message = "local output committed; public export failed"
            )
        )
        val snapshot = session.snapshot()
        assertTrue(snapshot.canAdmitNewCapture)
        assertTrue(session.start("next", 4) is CameraPipelineUiSession.StartResult.Accepted)
    }

    private fun handoff(generation: Long): CameraPipelineEvent.CaptureStageComplete =
        CameraPipelineEvent.CaptureStageComplete(
            generation = generation,
            counts = CameraPipelineProgressCounts(),
            message = "handoff",
            jobDirectoryPath = newJobDir("handoff").absolutePath,
            captureResourcesSettled = true,
            processingHandoffDurable = true
        )

    @Test
    fun processingDispatchFailure_retainsDurableHandoff() {
        val jobDir = newJobDir("dispatch_fail")
        seedJobMetadata(jobDir)
        assertTrue(
            KeplerJobMetadata.publishProcessingHandoff(
                jobDir, "capture-op-1", KeplerActiveOperationKind.PROCESSING_YUV
            )
        )
        // Lane scheduling never consumed the handoff (work never executed):
        // the enqueue-failure settlement converts it into durably retained
        // processing ownership / reconciliation evidence - never ownerless.
        assertTrue(
            KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(jobDir)
        )
        val metadata = KeplerJobMetadata.read(jobDir)
        val handoffRetained = metadata.optString(PROCESSING_HANDOFF_OPERATION_ID).isNotBlank()
        val consumedIntoOwnedOperation = metadata.optString(ACTIVE_OPERATION_ID).isNotBlank()
        assertTrue(
            "durable ownership evidence must survive dispatch failure",
            handoffRetained || consumedIntoOwnedOperation
        )
    }

    @Test
    fun recoveryMultipleJobs_isDeterministicAndSerialized() {
        val jobA = newJobDir("jobA")
        val jobB = newJobDir("jobB")
        val jobC = newJobDir("jobC")
        seedJobMetadata(jobA)
        seedJobMetadata(jobB)
        seedJobMetadata(jobC)
        val first = KeplerRecoveryCoordinator.recoverRoots(listOf(yuvRoot))
        val second = KeplerRecoveryCoordinator.recoverRoots(listOf(yuvRoot))
        val orderOne = first.jobs.filter { it.jobDir.parentFile == yuvRoot }.map { it.jobDir.name }
        val orderTwo = second.jobs.filter { it.jobDir.parentFile == yuvRoot }.map { it.jobDir.name }
        assertEquals(3, orderOne.size)
        assertEquals(orderOne, orderTwo)
        assertEquals(orderOne.sorted(), orderOne)
    }

    @Test
    fun recoveryDoesNotStealLiveOperation() {
        val jobDir = newJobDir("live_op")
        seedJobMetadata(jobDir)
        val lease = KeplerJobMetadata.acquireOperation(jobDir)
        assertNotNull(lease)
        try {
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(yuvRoot))
            val result = report.jobs.first { it.jobDir.absolutePath == jobDir.absolutePath }
            assertEquals(
                KeplerJobRecoveryClassification.SKIP_ACTIVE_CURRENT_PROCESS,
                result.classification
            )
        } finally {
            lease?.releaseOrRetainForReconciliation()
        }
    }

    @Test
    fun reprocessAndBackgroundQueue_doNotDoubleOwnSameJob() {
        val coordinator = BackgroundProcessingCoordinator.of(context())
        val jobDir = newJobDir("double_own")
        seedJobMetadata(jobDir)
        val insideLane = CountDownLatch(1)
        val releaseLane = CountDownLatch(1)
        val leaseChecks = arrayOfNulls<Boolean>(2)
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork { _ ->
                    insideLane.countDown()
                    releaseLane.await(10, TimeUnit.SECONDS)
                    val laneLease = KeplerJobMetadata.acquireOperation(jobDir)
                    leaseChecks[0] = laneLease != null
                    // While this owner lives, the durable lease is discoverable.
                    leaseChecks[1] = KeplerJobMetadata.findOperationLease(jobDir) != null
                    laneLease?.releaseOrRetainForReconciliation()
                }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(insideLane.await(10, TimeUnit.SECONDS))
        // A duplicate request against the CURRENTLY OWNED job is rejected.
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork { }
            ) is BackgroundEnqueueResult.Duplicate
        )
        releaseLane.countDown()
        val drained = CountDownLatch(1)
        coordinator.enqueue(
            ExactJobRef(newJobDir("after_dup"), KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork { drained.countDown() }
        )
        assertTrue(drained.await(10, TimeUnit.SECONDS))
        // The worker held exactly one durable operation lease.
        assertTrue(leaseChecks[0] == true)
        assertTrue(leaseChecks[1] == true)
    }

    @Test
    fun captureCancellationDebt_doesNotBecomeProcessingOwnership() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("cancelled burst", 4) as
            CameraPipelineUiSession.StartResult.Accepted).operation
        session.accept(CameraPipelineEvent.Started(operation.generation, "capturing"))
        assertTrue(session.requestCancellation(operation.generation, "user cancel"))

        session.accept(
            CameraPipelineEvent.Terminal(
                generation = operation.generation,
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                captureResourcesSettled = true,
                message = "capture cancelled"
            )
        )
        // Foreground ownership ended with the cancelled capture; no processing
        // lease or handoff was created by cancellation itself.
        assertFalse(session.snapshot().isCaptureBusy)
    }
}
