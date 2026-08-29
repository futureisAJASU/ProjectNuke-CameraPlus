package com.projectnuke.keplernightlab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Bounded capture-latency timing ledger (Phase 5).
 *
 * Records the authoritative capture-stage milestone instants for ONE capture
 * operation plus per-frame persistence instants.  Design constraints:
 *
 *  - NEVER blocks Camera2 callbacks: every recorder is an atomic CAS/put; there
 *    are no locks, no I/O, and no unbounded event histories.  Per-frame storage
 *    is a fixed-size array indexed by frame identity; frames beyond
 *    [requestedFrames] are ignored.
 *  - Deterministic: the time source is injectable so tests drive exact values.
 *  - Persisted as ONE small bounded JSON object (capture_timing.json) and as
 *    the "captureTiming" key of job.json for HardwareE2E summaries.
 */
internal class CaptureTimingLedger(
    val requestedFrames: Int,
    private val timeSource: () -> Long = System::nanoTime
) {
    /** Fixed-size per-frame instants in nanoseconds on the monotonic clock (0 = unset). */
    internal class FrameTimes(requested: Int) {
        val resultAt = AtomicLongArray(requested)
        val imageAt = AtomicLongArray(requested)
        val persistenceSubmittedAt = AtomicLongArray(requested)
        val workerStartedAt = AtomicLongArray(requested)
        val conversionCompletedAt = AtomicLongArray(requested)
        val encodeFinishedAt = AtomicLongArray(requested)
        val writeFinishedAt = AtomicLongArray(requested)
        val fsyncFinishedAt = AtomicLongArray(requested)
        val verifiedAt = AtomicLongArray(requested)
        val committedAt = AtomicLongArray(requested)
    }

    private val time = FrameTimes(requestedFrames.coerceAtLeast(1))

    @Volatile private var imageCount = 0
    @Volatile private var resultCount = 0

    private val requestSubmittedAt = AtomicLong(0L)
    private val firstImageReceivedAt = AtomicLong(0L)
    private val lastImageReceivedAt = AtomicLong(0L)
    private val firstCaptureResultAt = AtomicLong(0L)
    private val lastCaptureResultAt = AtomicLong(0L)
    private val cameraAcquisitionCompleteAt = AtomicLong(0L)
    private val persistenceDrainCompleteAt = AtomicLong(0L)
    private val processingHandoffPublishedAt = AtomicLong(0L)
    private val captureResourcesSettledAt = AtomicLong(0L)
    private val captureStageCompleteAt = AtomicLong(0L)

    // RAW aggregate persistence evidence. Accumulated from REAL measured spans
    // around the raw16 write and its fsync on the save worker; never inferred.
    private val rawBytesPersisted = AtomicLong(0L)
    private val rawPersistenceWriteMs = AtomicLong(0L)
    private val rawPersistenceSyncMs = AtomicLong(0L)

    private fun now(): Long = timeSource()

    // ------------------------------------------------------------------
    // Recorders — atomic, non-blocking, idempotent for milestones
    // ------------------------------------------------------------------

    fun recordCaptureRequestSubmitted() {
        requestSubmittedAt.compareAndSet(0L, now())
    }

    fun recordImageReceived(frameIndex: Int = -1) {
        val at = now()
        if (firstImageReceivedAt.compareAndSet(0L, at)) {
            lastImageReceivedAt.set(at)
        } else {
            while (true) {
                val current = lastImageReceivedAt.get()
                if (current >= at || lastImageReceivedAt.compareAndSet(current, at)) break
            }
        }
        imageCount++
        putFrameInstant(time.imageAt, frameIndex, at)
        maybeRecordAcquisitionComplete(at)
    }

    fun recordResultReceived(frameIndex: Int = -1) {
        val at = now()
        if (firstCaptureResultAt.compareAndSet(0L, at)) {
            lastCaptureResultAt.set(at)
        } else {
            while (true) {
                val current = lastCaptureResultAt.get()
                if (current >= at || lastCaptureResultAt.compareAndSet(current, at)) break
            }
        }
        resultCount++
        putFrameInstant(time.resultAt, frameIndex, at)
        maybeRecordAcquisitionComplete(at)
    }

    private fun maybeRecordAcquisitionComplete(at: Long) {
        if (cameraAcquisitionCompleteAt.get() != 0L) return
        if (requestedFrames > 0 &&
            cameraAcquisitionPairCount(imageCount, resultCount) >= requestedFrames
        ) {
            cameraAcquisitionCompleteAt.compareAndSet(0L, at)
        }
    }

    fun recordPersistenceSubmitted(frameIndex: Int) { putFrameInstant(time.persistenceSubmittedAt, frameIndex, now()) }

    /** Persistence worker actually began executing this frame's task. */
    fun recordWorkerStarted(frameIndex: Int) { putFrameInstant(time.workerStartedAt, frameIndex, now()) }

    /**
     * YUV -> color conversion finished (buffered path only; the direct Image
     * path converts inside the same encoder call and leaves this unset).
     */
    fun recordConversionCompleted(frameIndex: Int) { putFrameInstant(time.conversionCompletedAt, frameIndex, now()) }

    /**
     * Candidate production finished: bounded around the real encode() call
     * (conversion where not separately recorded + PNG compression + candidate
     * file write including its fsync).  Never inferred from sub-step durations.
     */
    fun recordEncodeFinished(frameIndex: Int) { putFrameInstant(time.encodeFinishedAt, frameIndex, now()) }

    /** Final artifact visible: candidate->final atomic replace returned. */
    fun recordWriteFinished(frameIndex: Int) { putFrameInstant(time.writeFinishedAt, frameIndex, now()) }

    /** The real FileDescriptor.sync() of the PNG sink returned (buffered path). */
    fun recordFsyncFinished(frameIndex: Int) { putFrameInstant(time.fsyncFinishedAt, frameIndex, now()) }
    fun recordVerified(frameIndex: Int) { putFrameInstant(time.verifiedAt, frameIndex, now()) }
    fun recordCommitted(frameIndex: Int) { putFrameInstant(time.committedAt, frameIndex, now()) }

    fun recordPersistenceDrainComplete() { persistenceDrainCompleteAt.compareAndSet(0L, now()) }
    fun recordProcessingHandoffPublished() { processingHandoffPublishedAt.compareAndSet(0L, now()) }
    fun recordCaptureResourcesSettled() { captureResourcesSettledAt.compareAndSet(0L, now()) }
    fun recordCaptureStageComplete() { captureStageCompleteAt.compareAndSet(0L, now()) }

    /**
     * RAW per-frame write evidence: compact bytes actually persisted plus the
     * REAL measured span of the row-extraction/write segment (fsync excluded).
     * Accumulates the aggregate raw* metrics; per-frame segments remain
     * derivable from workerStartedAt/writeFinishedAt.
     */
    fun recordRawFrameWriteStats(frameIndex: Int, bytesWritten: Long, writeDurationMs: Long) {
        if (frameIndex < 0 || frameIndex >= requestedFrames.coerceAtLeast(1)) return
        if (bytesWritten > 0) rawBytesPersisted.addAndGet(bytesWritten)
        if (writeDurationMs > 0) rawPersistenceWriteMs.addAndGet(writeDurationMs)
    }

    /** Accumulates one REAL measured fd.sync()/force() span (RAW persistence). */
    fun recordRawFrameSyncStats(syncDurationMs: Long) {
        if (syncDurationMs > 0) rawPersistenceSyncMs.addAndGet(syncDurationMs)
    }

    private fun putFrameInstant(array: AtomicLongArray, frameIndex: Int, at: Long) {
        if (frameIndex < 0 || frameIndex >= array.length()) return
        array.compareAndSet(frameIndex, 0L, at)
    }

    // ------------------------------------------------------------------
    // Derived metrics (milliseconds; 0 when either endpoint is unset)
    // ------------------------------------------------------------------

    fun snapshot(): CaptureTimingSnapshot {
        val requestSubmitted = requestSubmittedAt.get()
        val acquisitionComplete = cameraAcquisitionCompleteAt.get()
        val drainComplete = persistenceDrainCompleteAt.get()
        val handoffPublished = processingHandoffPublishedAt.get()
        val resourcesSettled = captureResourcesSettledAt.get()
        val stageComplete = captureStageCompleteAt.get()
        val lastCommitted = lastFrameCommittedAt()
        return CaptureTimingSnapshot(
            requestedFrames = requestedFrames,
            captureRequestSubmittedAt = requestSubmitted,
            firstImageReceivedAt = firstImageReceivedAt.get(),
            firstCaptureResultAt = firstCaptureResultAt.get(),
            lastImageReceivedAt = lastImageReceivedAt.get(),
            lastCaptureResultAt = lastCaptureResultAt.get(),
            cameraAcquisitionCompleteAt = acquisitionComplete,
            persistenceDrainCompleteAt = drainComplete,
            processingHandoffPublishedAt = handoffPublished,
            captureResourcesSettledAt = resourcesSettled,
            captureStageCompleteAt = stageComplete,
            cameraAcquisitionMs = durationMs(requestSubmitted, acquisitionComplete),
            postAcquisitionPersistenceMs = durationMs(acquisitionComplete, drainComplete),
            persistenceDrainMs = durationMs(acquisitionComplete, drainComplete),
            handoffPublicationMs = durationMs(drainComplete, handoffPublished),
            rawMetadataSettlementMs = durationMs(lastCommitted, handoffPublished),
            captureSettlementMs = durationMs(handoffPublished, resourcesSettled),
            postAcquisitionToShutterMs = durationMs(acquisitionComplete, stageComplete),
            handoffSettlementMs = durationMs(drainComplete, stageComplete),
            captureStageTotalMs = durationMs(requestSubmitted, stageComplete),
            rawBytesPersisted = rawBytesPersisted.get(),
            rawPersistenceWriteMs = rawPersistenceWriteMs.get(),
            rawPersistenceSyncMs = rawPersistenceSyncMs.get(),
            lastFrameCommittedAt = lastCommitted,
            postAcquisitionVerifyOverlapMs = postAcquisitionVerifyOverlapMs(),
            postAcquisitionRawWriteOverlapMs = postAcquisitionRawWriteOverlapMs(),
            postAcquisitionMetadataOverlapMs = postAcquisitionMetadataOverlapMs(),
            postAcquisitionHandoffOverlapMs = postAcquisitionHandoffOverlapMs()
        )
    }

    /** Latest nonzero per-frame committed instant (0 when no frame committed). */
    private fun lastFrameCommittedAt(): Long {
        var latest = 0L
        val frames = time.committedAt
        for (index in 0 until frames.length()) {
            val at = frames.get(index)
            if (at > latest) latest = at
        }
        return latest
    }

    fun postAcquisitionVerifyOverlapMs(): Long {
        val acquisitionComplete = cameraAcquisitionCompleteAt.get()
        val drainComplete = persistenceDrainCompleteAt.get()
        if (acquisitionComplete <= 0L || drainComplete <= 0L) return 0L
        var totalOverlapNanos = 0L
        val frames = time
        val count = requestedFrames.coerceAtLeast(1)
        for (index in 0 until count) {
            val writeFinished = frames.writeFinishedAt.get(index)
            val verifiedAt = frames.verifiedAt.get(index)
            if (writeFinished <= 0L || verifiedAt <= 0L) continue
            val overlapStart = maxOf(writeFinished, acquisitionComplete)
            val overlapEnd = minOf(verifiedAt, drainComplete)
            totalOverlapNanos += (overlapEnd - overlapStart).coerceAtLeast(0L)
        }
        return totalOverlapNanos / 1_000_000L
    }

    fun postAcquisitionRawWriteOverlapMs(): Long {
        val acquisitionComplete = cameraAcquisitionCompleteAt.get()
        val stageComplete = captureStageCompleteAt.get()
        if (acquisitionComplete <= 0L || stageComplete <= 0L) return 0L
        var totalOverlapNanos = 0L
        val frames = time
        val count = requestedFrames.coerceAtLeast(1)
        for (index in 0 until count) {
            val workerStarted = frames.workerStartedAt.get(index)
            val writeFinished = frames.writeFinishedAt.get(index)
            if (workerStarted <= 0L || writeFinished <= 0L) continue
            val overlapStart = maxOf(workerStarted, acquisitionComplete)
            val overlapEnd = minOf(writeFinished, stageComplete)
            totalOverlapNanos += (overlapEnd - overlapStart).coerceAtLeast(0L)
        }
        return totalOverlapNanos / 1_000_000L
    }

    fun postAcquisitionMetadataOverlapMs(): Long {
        val acquisitionComplete = cameraAcquisitionCompleteAt.get()
        val stageComplete = captureStageCompleteAt.get()
        if (acquisitionComplete <= 0L || stageComplete <= 0L) return 0L
        var totalOverlapNanos = 0L
        val frames = time
        val count = requestedFrames.coerceAtLeast(1)
        for (index in 0 until count) {
            val writeFinished = frames.writeFinishedAt.get(index)
            val committedAt = frames.committedAt.get(index)
            if (writeFinished <= 0L || committedAt <= 0L) continue
            val overlapStart = maxOf(writeFinished, acquisitionComplete)
            val overlapEnd = minOf(committedAt, stageComplete)
            totalOverlapNanos += (overlapEnd - overlapStart).coerceAtLeast(0L)
        }
        return totalOverlapNanos / 1_000_000L
    }

    fun postAcquisitionHandoffOverlapMs(): Long {
        val acquisitionComplete = cameraAcquisitionCompleteAt.get()
        val drainComplete = persistenceDrainCompleteAt.get()
        val handoffPublished = processingHandoffPublishedAt.get()
        val stageComplete = captureStageCompleteAt.get()
        if (acquisitionComplete <= 0L || drainComplete <= 0L || handoffPublished <= 0L || stageComplete <= 0L) return 0L
        val overlapStart = maxOf(drainComplete, acquisitionComplete)
        val overlapEnd = minOf(handoffPublished, stageComplete)
        return ((overlapEnd - overlapStart).coerceAtLeast(0L)) / 1_000_000L
    }

    private fun durationMs(fromNanos: Long, toNanos: Long): Long =
        if (fromNanos <= 0L || toNanos <= 0L || toNanos < fromNanos) 0L else (toNanos - fromNanos) / 1_000_000L

    /** Bounded JSON projection for job.json / capture_timing.json. */
    fun toJson(): JSONObject {
        val snap = snapshot()
        return JSONObject()
            .put("requestedFrames", snap.requestedFrames)
            .put("captureRequestSubmittedAt", snap.captureRequestSubmittedAt)
            .put("firstImageReceivedAt", snap.firstImageReceivedAt)
            .put("firstCaptureResultAt", snap.firstCaptureResultAt)
            .put("lastImageReceivedAt", snap.lastImageReceivedAt)
            .put("lastCaptureResultAt", snap.lastCaptureResultAt)
            .put("cameraAcquisitionCompleteAt", snap.cameraAcquisitionCompleteAt)
            .put("persistenceDrainCompleteAt", snap.persistenceDrainCompleteAt)
            .put("processingHandoffPublishedAt", snap.processingHandoffPublishedAt)
            .put("captureResourcesSettledAt", snap.captureResourcesSettledAt)
            .put("captureStageCompleteAt", snap.captureStageCompleteAt)
            .put("cameraAcquisitionMs", snap.cameraAcquisitionMs)
            .put("postAcquisitionPersistenceMs", snap.postAcquisitionPersistenceMs)
            .put("persistenceDrainMs", snap.persistenceDrainMs)
            .put("handoffPublicationMs", snap.handoffPublicationMs)
            .put("rawMetadataSettlementMs", snap.rawMetadataSettlementMs)
            .put("captureSettlementMs", snap.captureSettlementMs)
            .put("postAcquisitionToShutterMs", snap.postAcquisitionToShutterMs)
            .put("handoffSettlementMs", snap.handoffSettlementMs)
            .put("captureStageTotalMs", snap.captureStageTotalMs)
            .put("rawBytesPersisted", snap.rawBytesPersisted)
            .put("rawPersistenceWriteMs", snap.rawPersistenceWriteMs)
            .put("rawPersistenceSyncMs", snap.rawPersistenceSyncMs)
            .put("postAcquisitionVerifyOverlapMs", snap.postAcquisitionVerifyOverlapMs)
            .put("postAcquisitionRawWriteOverlapMs", snap.postAcquisitionRawWriteOverlapMs)
            .put("postAcquisitionMetadataOverlapMs", snap.postAcquisitionMetadataOverlapMs)
            .put("postAcquisitionHandoffOverlapMs", snap.postAcquisitionHandoffOverlapMs)
            .put("frames", framesToJson())
    }

    private fun framesToJson(): JSONArray {
        val array = JSONArray()
        for (index in 0 until requestedFrames.coerceAtLeast(1)) {
            array.put(
                JSONObject()
                    .put("frameIndex", index)
                    .put("resultAt", time.resultAt.get(index))
                    .put("imageAt", time.imageAt.get(index))
                        .put("persistenceSubmittedAt", time.persistenceSubmittedAt.get(index))
                    .put("workerStartedAt", time.workerStartedAt.get(index))
                    .put("conversionCompletedAt", time.conversionCompletedAt.get(index))
                    .put("encodeFinishedAt", time.encodeFinishedAt.get(index))
                    .put("writeFinishedAt", time.writeFinishedAt.get(index))
                    .put("fsyncFinishedAt", time.fsyncFinishedAt.get(index))
                    .put("verifiedAt", time.verifiedAt.get(index))
                    .put("committedAt", time.committedAt.get(index))
            )
        }
        return array
    }

    companion object {
        const val FILE_NAME = "capture_timing.json"

        /**
         * Persists the bounded ledger next to the job metadata (standalone
         * bounded file + "captureTiming" merge into job.json).  Best-effort:
         * diagnostic writes must never fail a capture pipeline.
         */
        fun persist(jobDir: File, ledger: CaptureTimingLedger): Boolean {
            val json = ledger.toJson()
            var ok = try {
                val temp = File(jobDir, ".$FILE_NAME.${System.nanoTime()}.tmp")
                temp.writeText(json.toString())
                KeplerJobMetadata.atomicReplace(temp, File(jobDir, FILE_NAME))
                true
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                false
            }
            try {
                KeplerJobMetadata.update(jobDir) { current ->
                    current.put("captureTiming", json)
                }
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                ok = false
            }
            return ok
        }
    }
}

/** Immutable derived view; durations are milliseconds (0 = endpoint unset). */
internal data class CaptureTimingSnapshot(
    val requestedFrames: Int,
    val captureRequestSubmittedAt: Long,
    val firstImageReceivedAt: Long,
    val firstCaptureResultAt: Long,
    val lastImageReceivedAt: Long,
    val lastCaptureResultAt: Long,
    val cameraAcquisitionCompleteAt: Long,
    val persistenceDrainCompleteAt: Long,
    val processingHandoffPublishedAt: Long,
    val captureResourcesSettledAt: Long,
    val captureStageCompleteAt: Long,
    val cameraAcquisitionMs: Long,
    /** cameraAcquisitionCompleteAt -> persistenceDrainCompleteAt (report-canonical name). */
    val postAcquisitionPersistenceMs: Long = 0L,
    /** Same segment as [postAcquisitionPersistenceMs]; legacy field name. */
    val persistenceDrainMs: Long,
    /** persistenceDrainCompleteAt -> processingHandoffPublishedAt. */
    val handoffPublicationMs: Long = 0L,
    /** Last per-frame commit -> processingHandoffPublishedAt (RAW terminal JSON work). */
    val rawMetadataSettlementMs: Long = 0L,
    /** processingHandoffPublishedAt -> captureResourcesSettledAt (owner exit). */
    val captureSettlementMs: Long = 0L,
    /**
     * THE user-observed 100%-to-shutter interval:
     * cameraAcquisitionCompleteAt -> captureStageCompleteAt.
     */
    val postAcquisitionToShutterMs: Long = 0L,
    val handoffSettlementMs: Long,
    val captureStageTotalMs: Long,
    /** RAW aggregate evidence; 0 for YUV jobs. */
    val rawBytesPersisted: Long = 0L,
    val rawPersistenceWriteMs: Long = 0L,
    val rawPersistenceSyncMs: Long = 0L,
    val lastFrameCommittedAt: Long = 0L,
    /** Overlap of per-frame verify spans with [cameraAcquisitionCompleteAt, persistenceDrainCompleteAt]. */
    val postAcquisitionVerifyOverlapMs: Long = 0L,
    /** Overlap of per-frame write spans with [cameraAcquisitionCompleteAt, captureStageCompleteAt]. */
    val postAcquisitionRawWriteOverlapMs: Long = 0L,
    /** Overlap of per-frame metadata commit spans with [cameraAcquisitionCompleteAt, captureStageCompleteAt]. */
    val postAcquisitionMetadataOverlapMs: Long = 0L,
    /** Overlap of handoff span [persistenceDrainCompleteAt, processingHandoffPublishedAt] with [cameraAcquisitionCompleteAt, captureStageCompleteAt]. */
    val postAcquisitionHandoffOverlapMs: Long = 0L
)

/**
 * Real production persistence-timing hooks for the YUV owner (Phase 3).
 * Implementations MUST be non-blocking (atomic puts only): they run on the
 * owner dispatcher and on persistence worker threads, never on Camera2
 * callbacks.  Each method marks one REAL operation boundary — no inferred or
 * synthetic durations.
 */
internal interface YuvCaptureTimingHooks {
    /** The owner attempted to submit this frame's persistence task. */
    fun onPersistenceSubmitted(frameIndex: Int) {}

    /** A persistence worker thread began executing this frame's task. */
    fun onWorkerStarted(frameIndex: Int) {}

    /**
     * The bounded encode span completed: YUV/color conversion (+ PNG
     * compression + candidate write incl. its fsync where not separately
     * recorded).  Recorded immediately after the real encode call returned.
     */
    fun onEncodeFinished(frameIndex: Int) {}

    /** Candidate->final atomic replace returned (final artifact visible). */
    fun onWriteFinished(frameIndex: Int) {}

    /** Final-file verification succeeded for this frame's artifact. */
    fun onVerified(frameIndex: Int) {}
}
