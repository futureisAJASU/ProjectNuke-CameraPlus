package com.projectnuke.keplernightlab

import java.io.File

internal enum class RawOutputState {
    TEMP,
    VERIFIED_FINAL,
    UNADOPTED_FINAL,
    DISPOSAL_REQUIRED,
    DISCARDED,
    CLEANUP_FAILED,
    NONE
}

internal data class RawOutputCleanupOutcome(
    val attempted: Boolean,
    val succeeded: Boolean,
    val failureDescription: String? = null
) {
    companion object {
        val NotNeeded = RawOutputCleanupOutcome(attempted = false, succeeded = true)
        val Clean = RawOutputCleanupOutcome(attempted = true, succeeded = true)
        fun failed(error: Throwable) = RawOutputCleanupOutcome(
            attempted = true,
            succeeded = false,
            failureDescription = "${error.javaClass.simpleName}: ${error.message}"
        )
    }
}

/** Exact worker-owned output paths; no completion may invent a temp filename. */
internal data class RawOutputOwnership(
    val tempFile: File?,
    val finalFile: File?,
    val state: RawOutputState,
    val verifiedBytes: Long?,
    val cleanup: RawOutputCleanupOutcome = RawOutputCleanupOutcome.NotNeeded,
    val dngTempFile: File? = null,
    val dngFinalFile: File? = null,
    val dngCleanup: RawOutputCleanupOutcome = RawOutputCleanupOutcome.NotNeeded
)

internal enum class RawCompletionPostOutcome { ACCEPTED, REJECTED_AND_DISPOSED, REJECTED_UNSETTLED }

/** Immutable metadata collected while the worker still owns the Image/result. */
internal data class RawFrameManifestData(
    val frameIndex: Int,
    val timestampNs: Long,
    val raw16Filename: String?,
    val dngFilename: String?,
    val dngSidecar: RawDngSidecarOutcome,
    val cameraId: String,
    val zoomRatio: Double,
    val selectedRoute: String,
    val actualRoute: String?,
    val requestedPhysicalCameraId: String?,
    val activePhysicalId: String?,
    val finalRequestZoom: Double,
    val cropApplied: Boolean,
    val cropActiveArraySource: String,
    val cropRegion: String?,
    val exposureTimeNs: Long? = null,
    val sensitivityIso: Int? = null,
    val frameDurationNs: Long? = null,
    val rawWidth: Int? = null,
    val rawHeight: Int? = null,
    val rowStride: Int? = null,
    val pixelStride: Int? = null,
    val dynamicBlackLevel: List<Float>? = null,
    val dynamicWhiteLevel: Int? = null,
    val colorCorrectionGains: String? = null,
    val colorCorrectionTransform: String? = null,
    val failureDescription: String? = null
)

private fun defaultRawFrameManifest(
    frameIndex: Int,
    timestampNs: Long,
    raw16Filename: String?,
    dngSidecar: RawDngSidecarOutcome
) = RawFrameManifestData(
    frameIndex = frameIndex,
    timestampNs = timestampNs,
    raw16Filename = raw16Filename,
    dngFilename = dngSidecar.sidecarFilename,
    dngSidecar = dngSidecar,
    cameraId = "",
    zoomRatio = 1.0,
    selectedRoute = "UNKNOWN",
    actualRoute = null,
    requestedPhysicalCameraId = null,
    activePhysicalId = null,
    finalRequestZoom = 1.0,
    cropApplied = false,
    cropActiveArraySource = "UNKNOWN",
    cropRegion = null
)

/**
 * Immutable completion emitted by the RAW save worker.
 *
 * The save worker must NEVER mutate authoritative capture state directly
 * (savedFrames, frameObjects, terminal status).  Instead, it returns a
 * structured [RawSaveCompletion] that the serialized owner adopts or
 * rejects.  This keeps the same ownership model as the YUV pipeline:
 *
 *   camera callback / timeout / cancellation
 *       -> immutable owner event
 *   single serialized capture-state owner
 *       -> authoritative mutable capture progress
 *   worker
 *       -> own only transferred resources/work items
 *       -> produce immutable completion objects
 *
 * A late completion (after terminal) carries its identity explicitly so the
 * owner can publish it in the discardedLateCompletions list instead of
 * silently adopting an orphan frame.
 */
internal sealed interface RawSaveCompletion {
    /** Frame index the completion refers to (required for late/orphan correlation). */
    val frameIndex: Int
    /** Timestamp (ns) the completion refers to. */
    val timestampNs: Long
    val imageReleaseFailure: Throwable?

    /**
     * Frame raw16 saved successfully; optional DNG sidecar may also be saved.
     *
     * @param raw16Filename final raw16 filename committed by the worker
     * @param dngSidecar outcome of the per-frame DNG sidecar (NOT_REQUESTED if disabled)
     * @param output exact worker-owned path state; the owner adopts or settles it
     * @param frame manifest DTO; JSON is built only by the serialized owner
     */
    data class Success(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val raw16Filename: String,
        val raw16Bytes: Long,
        val saveDurationMs: Long,
        val dngSidecar: RawDngSidecarOutcome,
        val output: RawOutputOwnership = RawOutputOwnership(
            tempFile = null,
            finalFile = null,
            state = RawOutputState.NONE,
            verifiedBytes = raw16Bytes
        ),
        override val imageReleaseFailure: Throwable? = null,
        val frame: RawFrameManifestData = defaultRawFrameManifest(
            frameIndex, timestampNs, raw16Filename, dngSidecar
        )
    ) : RawSaveCompletion

    /**
     * Frame raw16 save failed.
     *
     * @param failureType short failure category (e.g. "OutOfMemoryError", "encode threw")
     * @param failureMessage human-readable failure description
     * @param output exact output ownership at failure (never a fabricated path)
     */
    data class Failed(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val failureType: String,
        val failureMessage: String,
        val throwable: Throwable?,
        val output: RawOutputOwnership = RawOutputOwnership(null, null, RawOutputState.NONE, null),
        override val imageReleaseFailure: Throwable? = null,
        val frame: RawFrameManifestData = defaultRawFrameManifest(
            frameIndex, timestampNs, null, RawDngSidecarOutcome.notRequested(frameIndex)
        ).copy(failureDescription = failureMessage)
    ) : RawSaveCompletion

    /**
     * Frame save was abandoned mid-write because the owner already claimed a
     * terminal status; the worker deleted its own partial files. Nothing is
     * adopted; the completion exists so the owner can close the accounting loop.
     */
    data class Abandoned(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val output: RawOutputOwnership = RawOutputOwnership(null, null, RawOutputState.NONE, null),
        override val imageReleaseFailure: Throwable? = null
    ) : RawSaveCompletion
}

/**
 * Production worker task for a single RAW save.  The task owns its Image after
 * executor acceptance.  A completion remains worker-owned until the serialized
 * owner accepts the completion event; rejected/late events are returned to the
 * supplied disposer instead of silently retaining a final output.
 */
internal class RawSaveTask(
    private val produceCompletion: () -> RawSaveCompletion,
    private val unexpectedFailure: (Throwable) -> RawSaveCompletion,
    private val postCompletion: (RawSaveCompletion) -> RawCompletionPostOutcome,
    private val disposeCompletion: (RawSaveCompletion) -> CaptureTaskDisposalOutcome,
    private val disposeQueuedInput: () -> CaptureTaskDisposalOutcome
) : OutcomeDisposableCaptureTask {
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun run() {
        if (!started.compareAndSet(false, true)) return
        val completion = try {
            produceCompletion()
        } catch (t: Throwable) {
            // The task boundary is the last ownership point for fatal worker
            // failures.  Convert them into an immutable completion so the
            // serialized owner can reject/adopt and settle outputs normally.
            unexpectedFailure(t)
        }
        if (postCompletion(completion) == RawCompletionPostOutcome.REJECTED_UNSETTLED) {
            disposeCompletion(completion)
        }
    }

    override fun dispose() {
        disposeWithOutcome()
    }

    override fun disposeWithOutcome(): CaptureTaskDisposalOutcome {
        if (!started.compareAndSet(false, true)) return CaptureTaskDisposalOutcome.Clean
        return disposeQueuedInput()
    }
}
