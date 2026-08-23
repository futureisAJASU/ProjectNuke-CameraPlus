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
        val persistenceQueuedAt = AtomicLongArray(requested)
        val encodeFinishedAt = AtomicLongArray(requested)
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

    fun recordPersistenceQueued(frameIndex: Int) { putFrameInstant(time.persistenceQueuedAt, frameIndex, now()) }
    fun recordEncodeFinished(frameIndex: Int) { putFrameInstant(time.encodeFinishedAt, frameIndex, now()) }
    fun recordFsyncFinished(frameIndex: Int) { putFrameInstant(time.fsyncFinishedAt, frameIndex, now()) }
    fun recordVerified(frameIndex: Int) { putFrameInstant(time.verifiedAt, frameIndex, now()) }
    fun recordCommitted(frameIndex: Int) { putFrameInstant(time.committedAt, frameIndex, now()) }

    fun recordPersistenceDrainComplete() { persistenceDrainCompleteAt.compareAndSet(0L, now()) }
    fun recordProcessingHandoffPublished() { processingHandoffPublishedAt.compareAndSet(0L, now()) }
    fun recordCaptureResourcesSettled() { captureResourcesSettledAt.compareAndSet(0L, now()) }
    fun recordCaptureStageComplete() { captureStageCompleteAt.compareAndSet(0L, now()) }

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
        val stageComplete = captureStageCompleteAt.get()
        return CaptureTimingSnapshot(
            requestedFrames = requestedFrames,
            captureRequestSubmittedAt = requestSubmitted,
            firstImageReceivedAt = firstImageReceivedAt.get(),
            firstCaptureResultAt = firstCaptureResultAt.get(),
            lastImageReceivedAt = lastImageReceivedAt.get(),
            lastCaptureResultAt = lastCaptureResultAt.get(),
            cameraAcquisitionCompleteAt = acquisitionComplete,
            persistenceDrainCompleteAt = drainComplete,
            processingHandoffPublishedAt = processingHandoffPublishedAt.get(),
            captureResourcesSettledAt = captureResourcesSettledAt.get(),
            captureStageCompleteAt = stageComplete,
            cameraAcquisitionMs = durationMs(requestSubmitted, acquisitionComplete),
            persistenceDrainMs = durationMs(acquisitionComplete, drainComplete),
            handoffSettlementMs = durationMs(drainComplete, stageComplete),
            captureStageTotalMs = durationMs(requestSubmitted, stageComplete)
        )
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
            .put("persistenceDrainMs", snap.persistenceDrainMs)
            .put("handoffSettlementMs", snap.handoffSettlementMs)
            .put("captureStageTotalMs", snap.captureStageTotalMs)
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
                    .put("persistenceQueuedAt", time.persistenceQueuedAt.get(index))
                    .put("encodeFinishedAt", time.encodeFinishedAt.get(index))
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
    val persistenceDrainMs: Long,
    val handoffSettlementMs: Long,
    val captureStageTotalMs: Long
)
