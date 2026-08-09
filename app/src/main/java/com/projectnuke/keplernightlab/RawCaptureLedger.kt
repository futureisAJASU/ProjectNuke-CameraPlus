package com.projectnuke.keplernightlab

import org.json.JSONArray

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
    private val identity = CaptureFrameIdentityOwner(requestedFrames)
    private val rawFrameSaveTimesMs = mutableListOf<Long>()
    private val frameObjects = JSONArray()

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
        synchronized(lock) {
            imagesByTimestamp.remove(timestampNs)?.let { closeImage(it) }
            imagesByTimestamp[timestampNs] = image
            imageArrivalMillis[timestampNs] = arrivalMillis
        }
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
        synchronized(lock) {
            if (imagesByTimestamp.size < readerCapacity) return
            imagesByTimestamp.keys
                .filter { it !in resultsByTimestamp }
                .minByOrNull { imageArrivalMillis[it] ?: Long.MIN_VALUE }
                ?.let { timestamp ->
                    imagesByTimestamp.remove(timestamp)?.let { closeImage(it) }
                    imageArrivalMillis.remove(timestamp)
                    droppedUnmatchedImages++
                }
        }
    }

    /** Closes every held image whose capture result has not arrived. */
    fun closeUnmatchedImages() {
        synchronized(lock) {
            val unmatched = imagesByTimestamp.filter { it.key !in resultsByTimestamp }.keys.toList()
            for (timestamp in unmatched) {
                imagesByTimestamp.remove(timestamp)?.let { closeImage(it) }
                imageArrivalMillis.remove(timestamp)
                droppedUnmatchedImages++
            }
        }
    }

    /**
     * Removes and pairs ready (image, result) frames in ascending timestamp order,
     * allocating each a frame identity. Pairs whose result has not yet arrived
     * stay in the unmatched maps.
     */
    fun takeReadyFrames(): List<RawReadyFrame<IMAGE, RESULT>> {
        val taken: List<RawReadyFrame<IMAGE, RESULT>> = synchronized(lock) {
            val pairs = mutableListOf<RawReadyFrame<IMAGE, RESULT>>()
            for (timestamp in imagesByTimestamp.keys
                .filter { resultsByTimestamp.containsKey(it) }
                .sorted()) {
                val image = imagesByTimestamp.remove(timestamp) ?: continue
                imageArrivalMillis.remove(timestamp)
                val result = resultsByTimestamp.remove(timestamp)
                if (result == null) {
                    imagesByTimestamp[timestamp] = image
                    imageArrivalMillis[timestamp] = System.currentTimeMillis()
                } else {
                    pairs.add(RawReadyFrame(frameIndex = 0, timestampNs = timestamp, image = image, result = result))
                }
            }
            pairs
        }
        val frames = mutableListOf<RawReadyFrame<IMAGE, RESULT>>()
        for (pair in taken) {
            val index = identity.nextIdentity()
            if (index == null) {
                closeImage(pair.image)
                continue
            }
            frames.add(pair.copy(frameIndex = index))
        }
        return frames
    }

    /** Restores a pair whose save submission was rejected (queue saturated). */
    fun restorePair(timestampNs: Long, image: IMAGE, result: RESULT) {
        synchronized(lock) {
            imagesByTimestamp[timestampNs] = image
            imageArrivalMillis[timestampNs] = System.currentTimeMillis()
            resultsByTimestamp[timestampNs] = result
        }
    }

    /** Adopts a successfully saved frame: counter + manifest + save-time accounting. */
    fun adoptSuccess(completion: RawSaveCompletion.Success) {
        savedFrames++
        synchronized(lock) {
            rawFrameSaveTimesMs += completion.saveDurationMs
            completion.frameEntry?.let { frameObjects.put(it) }
        }
    }

    /** Adopts a failed frame's manifest entry (no saved-frame accounting). */
    fun adoptFailure(completion: RawSaveCompletion.Failed) {
        synchronized(lock) { completion.frameEntry?.let { frameObjects.put(it) } }
    }

    fun rawSaveTotalMs(): Long = synchronized(lock) { rawFrameSaveTimesMs.sum() }

    fun rawAverageSaveMs(): Double? = synchronized(lock) {
        rawFrameSaveTimesMs.takeIf { it.isNotEmpty() }?.average()
    }

    fun frameObjectsSnapshot(): JSONArray = synchronized(lock) { JSONArray(frameObjects.toString()) }

    /** Closes every held image and clears the maps. Called only from terminal cleanup. */
    fun releaseAllImages() {
        synchronized(lock) {
            imagesByTimestamp.values.forEach { closeImage(it) }
            imagesByTimestamp.clear()
            imageArrivalMillis.clear()
            resultsByTimestamp.clear()
        }
    }
}
