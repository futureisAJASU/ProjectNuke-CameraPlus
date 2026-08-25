package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.CancellationException

internal enum class RawOutputState {
    TEMP,
    VERIFIED_FINAL,
    UNADOPTED_FINAL,
    DISPOSAL_REQUIRED,
    DISCARDED,
    CLEANUP_FAILED,
    NONE
}

internal enum class RawOutputCleanupStatus {
    NOT_ATTEMPTED,
    ABSENT,
    DELETED,
    DELETE_RETURNED_FALSE,
    DELETE_THREW,
    QUARANTINED,
    QUARANTINE_FAILED,
    ADOPTED
}

/** Exact worker-owned output paths; no completion may invent a temp filename. */
internal data class RawOutputOwnership(
    val tempFile: File?,
    val finalFile: File?,
    val state: RawOutputState,
    val verifiedBytes: Long?,
    val dngTempFile: File? = null,
    val dngFinalFile: File? = null
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
    /** Which extraction strategy wrote this frame's raw16 payload (physical evidence). */
    val raw16WriteStrategy: String? = null,
    val dynamicBlackLevel: List<Float>? = null,
    val dynamicWhiteLevel: Int? = null,
    val colorCorrectionGains: String? = null,
    val colorCorrectionTransform: String? = null,
    val failureDescription: String? = null
)

internal fun defaultRawFrameManifest(
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

/** Settles the transferred Image before exposing the immutable completion. */
internal fun settleRawSaveImage(
    completion: RawSaveCompletion,
    closeImage: () -> Unit
): RawSaveCompletion {
    val releaseFailure = try {
        closeImage()
        null
    } catch (failure: Error) {
        // The task boundary rethrows this after the owner receives the exact
        // completion, so resource settlement cannot replace fatal identity.
        failure
    } catch (failure: Exception) {
        failure
    }
    return when (completion) {
        is RawSaveCompletion.Success -> completion.copy(imageReleaseFailure = releaseFailure)
        is RawSaveCompletion.Failed -> completion.copy(imageReleaseFailure = releaseFailure)
        is RawSaveCompletion.Abandoned -> completion.copy(imageReleaseFailure = releaseFailure)
    }
}

internal data class RawSuccessOutputSettlementPlan(
    val adopted: List<File>,
    val leftovers: List<File>
)

internal data class RawOutputSettlementResult(
    val records: List<RawOutputCleanupRecord>,
    val failure: Throwable?
)

internal fun settleRawOutputFiles(
    files: List<File>,
    deleteFile: (File) -> Boolean = { it.delete() }
): RawOutputSettlementResult {
    val records = mutableListOf<RawOutputCleanupRecord>()
    var firstFailure: Throwable? = null
    for (file in files.distinctBy { it.absolutePath }) {
        val kind = when {
            file.name.endsWith(".tmp") && file.name.contains(".dng.") -> RawOutputResourceKind.DNG_TEMP
            file.name.endsWith(".tmp") -> RawOutputResourceKind.RAW_TEMP
            file.extension.equals("dng", ignoreCase = true) -> RawOutputResourceKind.DNG_FINAL
            else -> RawOutputResourceKind.RAW_FINAL
        }
        val role = if (kind == RawOutputResourceKind.RAW_TEMP || kind == RawOutputResourceKind.DNG_TEMP) {
            RawOutputOwnershipRole.TEMPORARY
        } else {
            RawOutputOwnershipRole.UNADOPTED
        }
        if (!file.exists()) {
            records += RawOutputCleanupRecord(file.absolutePath, kind, role, RawOutputCleanupStatus.ABSENT)
            continue
        }
        try {
            if (!deleteFile(file)) {
                val failure = java.io.IOException("Could not delete RAW output ${file.absolutePath}")
                records += RawOutputCleanupRecord(file.absolutePath, kind, role, RawOutputCleanupStatus.DELETE_RETURNED_FALSE, failure)
                if (firstFailure == null) firstFailure = failure
            } else {
                records += RawOutputCleanupRecord(file.absolutePath, kind, role, RawOutputCleanupStatus.DELETED)
            }
        } catch (failure: Error) {
            throw failure
        } catch (failure: Exception) {
            records += RawOutputCleanupRecord(file.absolutePath, kind, role, RawOutputCleanupStatus.DELETE_THREW, failure)
            if (firstFailure == null) firstFailure = failure
        }
    }
    return RawOutputSettlementResult(records, firstFailure)
}

/** Shared production ownership split used before the owner settles a success. */
internal fun planRawSuccessOutputSettlement(
    jobDir: File,
    completion: RawSaveCompletion.Success
): RawSuccessOutputSettlementPlan {
    val adopted = buildList {
        add(File(jobDir, completion.raw16Filename))
        if (completion.dngSidecar.status == RawDngSidecarStatus.LOCAL_SAVED &&
            completion.frame.dngFilename != null
        ) {
            add(File(jobDir, completion.frame.dngFilename))
        }
    }
    val leftovers = listOfNotNull(
        completion.output.tempFile,
        completion.output.dngTempFile,
        completion.output.dngFinalFile
    ).filterNot { file -> adopted.any { it.absolutePath == file.absolutePath } }
    return RawSuccessOutputSettlementPlan(adopted, leftovers)
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
            // Publish the exact failure completion while the task still owns the
            // transferred input/output, then preserve fatal/cancellation identity.
            val failed = unexpectedFailure(t)
            var secondaryFailure: Throwable? = null
            try {
                if (postCompletion(failed) == RawCompletionPostOutcome.REJECTED_UNSETTLED) {
                    when (val disposal = disposeCompletion(failed)) {
                        is CaptureTaskDisposalOutcome.Failed -> {
                            secondaryFailure = disposal.failure
                        }
                        else -> Unit
                    }
                }
            } catch (secondary: Throwable) {
                secondaryFailure = secondary
            }
            secondaryFailure?.let { secondary ->
                when {
                    secondary is Error || secondary is CancellationException -> {
                        if (t is Error || t is CancellationException) {
                            if (t !== secondary) t.addSuppressed(secondary)
                        } else {
                            secondary.addSuppressed(t)
                        }
                    }
                    else -> t.addSuppressed(secondary)
                }
            }
            if (t is java.util.concurrent.CancellationException || t is Error) throw t
            if (secondaryFailure is CancellationException || secondaryFailure is Error) {
                throw secondaryFailure!!
            }
            return
        }
        if (postCompletion(completion) == RawCompletionPostOutcome.REJECTED_UNSETTLED) {
            when (val disposal = disposeCompletion(completion)) {
                is CaptureTaskDisposalOutcome.Failed -> {
                    if (disposal.failure is CancellationException || disposal.failure is Error) {
                        throw disposal.failure
                    }
                }
                else -> Unit
            }
        }
        val completionFailure = when (completion) {
            is RawSaveCompletion.Failed -> completion.throwable
            else -> completion.imageReleaseFailure
        }
        if (completionFailure is java.util.concurrent.CancellationException || completionFailure is Error) {
            throw completionFailure
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
