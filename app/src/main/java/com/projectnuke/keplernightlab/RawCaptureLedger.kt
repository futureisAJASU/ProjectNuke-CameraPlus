package com.projectnuke.keplernightlab

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/**
 * Immutable snapshot of RAW capture progress, published by the owner after every
 * counter-mutating event. Cross-thread readers (progress, job status, timeout
 * logic) must read counters ONLY through this snapshot, never the live fields.
 */
internal data class RawCaptureProgressSnapshot(
    val requestedFrames: Int,
    val attemptedFrames: Int,
    val savedFrames: Int,
    val receivedImages: Int,
    val completedResults: Int,
    val failedCaptures: Int,
    val droppedUnmatchedImages: Int
)

/**
 * A ready (image, result) pair taken from the owner's unmatched maps together
 * with the frame identity allocated by the owner.
 */
internal data class RawReadyFrame<IMAGE, RESULT>(
    val frameIndex: Int,
    val timestampNs: Long,
    val image: IMAGE,
    val result: RESULT
)

internal enum class RawImageReleaseReason {
    DUPLICATE_TIMESTAMP, CAPACITY_EVICTION, IDENTITY_EXHAUSTED, TERMINAL_CLEANUP
}

internal data class RawImageReleaseOutcome(
    val reason: RawImageReleaseReason,
    val attempted: Boolean,
    val succeeded: Boolean,
    val failure: Throwable? = null
)

/**
 * The RAW capture owner's authoritative mutable state: unmatched timestamp maps,
 * progress counters, frame identity, manifest, and sidecar accounting.
 *
 * The ledger is NOT thread-safe by design: it is mutated exclusively by owner
 * events on the capture handler thread. The internal lock is a strictly
 * resource-local guard so that terminal cleanup ([releaseAllImages]) can close
 * held images concurrently with a running event without leaking them.
 *
 * The save worker never touches this state directly; it returns immutable
 * [RawSaveCompletion] objects which the owner adopts or rejects.
 */
internal class RawCaptureLedger<IMAGE, RESULT>(
    val requestedFrames: Int,
    private val closeImage: (IMAGE) -> Unit
) {
    private val lock = Any()
    private val imagesByTimestamp = mutableMapOf<Long, IMAGE>()
    private val imageArrivalMillis = mutableMapOf<Long, Long>()
    private val resultsByTimestamp = mutableMapOf<Long, RESULT>()
    /** A rejected save submission keeps this exact identity for a later retry. */
    private val submissionPendingByTimestamp = mutableMapOf<Long, RawReadyFrame<IMAGE, RESULT>>()
    private val identity = CaptureFrameIdentityOwner(requestedFrames)
    private val rawFrameSaveTimesMs = mutableListOf<Long>()
    private val frameObjects = JSONArray()
    private val imageReleaseOutcomes = mutableListOf<RawImageReleaseOutcome>()

    var attemptedFrames = 0
        private set
    var savedFrames = 0
        private set
    var receivedImages = 0
        private set
    var completedResults = 0
        private set
    var failedCaptures = 0
        private set
    var droppedUnmatchedImages = 0
        private set
    var rawFirstImageDelayMs: Long? = null

    fun snapshot(): RawCaptureProgressSnapshot = RawCaptureProgressSnapshot(
        requestedFrames = requestedFrames,
        attemptedFrames = attemptedFrames,
        savedFrames = savedFrames,
        receivedImages = receivedImages,
        completedResults = completedResults,
        failedCaptures = failedCaptures,
        droppedUnmatchedImages = droppedUnmatchedImages
    )

    fun recordImage(timestampNs: Long, image: IMAGE, arrivalMillis: Long) {
        val replaced = synchronized(lock) {
            val previous = imagesByTimestamp.remove(timestampNs)
            imageArrivalMillis.remove(timestampNs)
            imagesByTimestamp[timestampNs] = image
            imageArrivalMillis[timestampNs] = arrivalMillis
            previous
        }
        replaced?.let { releaseImage(it, RawImageReleaseReason.DUPLICATE_TIMESTAMP) }
        receivedImages++
    }

    fun recordResult(timestampNs: Long, result: RESULT) {
        synchronized(lock) { resultsByTimestamp[timestampNs] = result }
        completedResults++
    }

    fun recordCaptureFailure() {
        failedCaptures++
    }

    fun setAttemptedFrames(value: Int) {
        attemptedFrames = value
    }

    /** Drops the oldest unmatched image when the held-image count reaches capacity. */
    fun evictEmergencyUnmatchedImages(readerCapacity: Int) {
        val evicted = synchronized(lock) {
            if (imagesByTimestamp.size < readerCapacity) return
            imagesByTimestamp.keys
                .filter { it !in resultsByTimestamp }
                .minByOrNull { imageArrivalMillis[it] ?: Long.MIN_VALUE }
                ?.let { timestamp ->
                    val image = imagesByTimestamp.remove(timestamp)
                    imageArrivalMillis.remove(timestamp)
                    droppedUnmatchedImages++
                    image
                }
        }
        evicted?.let { releaseImage(it, RawImageReleaseReason.CAPACITY_EVICTION) }
    }

    /**
     * Transactionally transfers one ready pair at a time. A rejected submission must
     * call [restoreRejectedSubmission] with this same object, preserving identity.
     */
    fun takeNextReadyFrame(): RawReadyFrame<IMAGE, RESULT>? {
        var exhausted: IMAGE? = null
        val ready: RawReadyFrame<IMAGE, RESULT>? = synchronized(lock) {
            submissionPendingByTimestamp.keys.minOrNull()?.let { timestamp ->
                return@synchronized submissionPendingByTimestamp.remove(timestamp)
            }
            val timestamp = imagesByTimestamp.keys
                .asSequence()
                .filter { resultsByTimestamp.containsKey(it) }
                .minOrNull()
                ?: return@synchronized null
            val image = imagesByTimestamp.remove(timestamp) ?: return@synchronized null
            imageArrivalMillis.remove(timestamp)
            val result = resultsByTimestamp.remove(timestamp)
            if (result == null) {
                imagesByTimestamp[timestamp] = image
                null
            } else {
                val index = identity.nextIdentity()
                if (index == null) {
                    exhausted = image
                    null
                } else {
                    RawReadyFrame(index, timestamp, image, result)
                }
            }
        }
        exhausted?.let { releaseImage(it, RawImageReleaseReason.IDENTITY_EXHAUSTED) }
        return ready
    }

    fun restoreRejectedSubmission(frame: RawReadyFrame<IMAGE, RESULT>) {
        synchronized(lock) {
            check(submissionPendingByTimestamp.put(frame.timestampNs, frame) == null) {
                "duplicate RAW submission-pending timestamp ${frame.timestampNs}"
            }
        }
    }

    private fun releaseImage(image: IMAGE, reason: RawImageReleaseReason) {
        val outcome = try {
            closeImage(image)
            RawImageReleaseOutcome(reason, attempted = true, succeeded = true)
        } catch (t: Throwable) {
            if (t is CancellationException || t is Error) throw t
            RawImageReleaseOutcome(reason, attempted = true, succeeded = false, failure = t)
        }
        synchronized(lock) { imageReleaseOutcomes += outcome }
    }

    fun imageReleaseOutcomes(): List<RawImageReleaseOutcome> = synchronized(lock) {
        imageReleaseOutcomes.toList()
    }

    /** Adopts a successfully saved frame: counter + manifest + save-time accounting. */
    fun adoptSuccess(completion: RawSaveCompletion.Success) {
        savedFrames++
        synchronized(lock) {
            rawFrameSaveTimesMs += completion.saveDurationMs
            frameObjects.put(completion.frame.toJson())
        }
    }

    /** Adopts a failed frame's manifest entry (no saved-frame accounting). */
    fun adoptFailure(completion: RawSaveCompletion.Failed) {
        synchronized(lock) { frameObjects.put(completion.frame.toJson()) }
    }

    /** JSON serialization is owner-only; workers carry [RawFrameManifestData]. */
    private fun RawFrameManifestData.toJson(): JSONObject = JSONObject()
        .put("index", frameIndex)
        .put("frameIndex", frameIndex)
        .put("raw16File", raw16Filename ?: JSONObject.NULL)
        .put("dngFile", dngFilename ?: JSONObject.NULL)
        .put("dngSidecarStatus", dngSidecar.status.name)
        .put("dngSidecarError", dngSidecar.failureDescription ?: JSONObject.NULL)
        .put("timestampNs", timestampNs)
        .put("cameraId", cameraId)
        .put("zoomRatio", zoomRatio)
        .put("selectedRoute", selectedRoute)
        .put("actualRoute", actualRoute ?: JSONObject.NULL)
        .put("requestedPhysicalCameraId", requestedPhysicalCameraId ?: JSONObject.NULL)
        .put("activePhysicalId", activePhysicalId ?: JSONObject.NULL)
        .put("finalRequestZoom", finalRequestZoom)
        .put("cropApplied", cropApplied)
        .put("cropActiveArraySource", cropActiveArraySource)
        .put("cropRegion", cropRegion ?: JSONObject.NULL)
        .put("exposureTimeNs", exposureTimeNs ?: JSONObject.NULL)
        .put("sensitivityIso", sensitivityIso ?: JSONObject.NULL)
        .put("frameDurationNs", frameDurationNs ?: JSONObject.NULL)
        .put("rawWidth", rawWidth ?: JSONObject.NULL)
        .put("rawHeight", rawHeight ?: JSONObject.NULL)
        .put("rowStride", rowStride ?: JSONObject.NULL)
        .put("pixelStride", pixelStride ?: JSONObject.NULL)
        .put("dynamicBlackLevel", dynamicBlackLevel?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("dynamicWhiteLevel", dynamicWhiteLevel ?: JSONObject.NULL)
        .put("colorCorrectionGains", colorCorrectionGains ?: JSONObject.NULL)
        .put("colorCorrectionTransform", colorCorrectionTransform ?: JSONObject.NULL)
        .put("failureReason", failureDescription ?: JSONObject.NULL)

    fun rawSaveTotalMs(): Long = synchronized(lock) { rawFrameSaveTimesMs.sum() }

    fun rawAverageSaveMs(): Double? = synchronized(lock) {
        rawFrameSaveTimesMs.takeIf { it.isNotEmpty() }?.average()
    }

    fun frameObjectsSnapshot(): JSONArray = synchronized(lock) { JSONArray(frameObjects.toString()) }

    /** Closes every held image and clears the maps. Called only from terminal cleanup. */
    fun releaseAllImages() {
        val held = synchronized(lock) {
            val values = buildList {
                addAll(imagesByTimestamp.values)
                addAll(submissionPendingByTimestamp.values.map { it.image })
            }
            imagesByTimestamp.clear()
            imageArrivalMillis.clear()
            resultsByTimestamp.clear()
            submissionPendingByTimestamp.clear()
            values
        }
        held.forEach { releaseImage(it, RawImageReleaseReason.TERMINAL_CLEANUP) }
    }
}
