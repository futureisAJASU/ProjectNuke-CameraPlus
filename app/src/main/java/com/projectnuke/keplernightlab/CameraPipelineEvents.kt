package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicBoolean

/** UI-facing authority for a camera capture/processing operation. Display text is diagnostic only. */
sealed interface CameraPipelineEvent {
    val generation: Long
    val message: String?
    val counts: CameraPipelineProgressCounts

    data class Started(
        override val generation: Long,
        override val message: String? = null,
        override val counts: CameraPipelineProgressCounts = CameraPipelineProgressCounts()
    ) : CameraPipelineEvent

    data class CaptureProgress(
        override val generation: Long,
        override val counts: CameraPipelineProgressCounts,
        override val message: String? = null
    ) : CameraPipelineEvent

    data class CaptureStageComplete(
        override val generation: Long,
        override val counts: CameraPipelineProgressCounts,
        override val message: String? = null,
        /**
         * Authoritative durable-handoff evidence (Phase 5 boundary). A
         * CaptureStageComplete without complete evidence is treated as a
         * legacy in-pipeline stage marker: foreground capture ownership is
         * NOT released and the shutter stays gated until terminal.
         */
        val jobDirectoryPath: String? = null,
        val captureResourcesSettled: Boolean = false,
        val processingHandoffDurable: Boolean = false
    ) : CameraPipelineEvent {
        /** True only when the event proves the exact durable handoff boundary. */
        val handoffEvidenceComplete: Boolean
            get() = jobDirectoryPath != null && captureResourcesSettled && processingHandoffDurable
    }

    data class ProcessingStage(
        override val generation: Long,
        val stage: CaptureStage,
        override val counts: CameraPipelineProgressCounts,
        override val message: String? = null
    ) : CameraPipelineEvent

    data class ExportStage(
        override val generation: Long,
        val stage: CaptureStage = CaptureStage.EXPORTING,
        override val counts: CameraPipelineProgressCounts,
        override val message: String? = null
    ) : CameraPipelineEvent

    data class Terminal(
        override val generation: Long,
        val kind: Kind,
        val requiredOutputCommitted: Boolean = false,
        val publicExportCommitted: Boolean = false,
        val verified: Boolean = false,
        val captureResourcesSettled: Boolean = true,
        override val counts: CameraPipelineProgressCounts = CameraPipelineProgressCounts(),
        override val message: String? = null,
        val jobDirectoryPath: String? = null
    ) : CameraPipelineEvent {
        enum class Kind { COMPLETE, COMPLETE_PARTIAL, FAILED, CANCELLED }
    }
}

data class CameraPipelineProgressCounts(
    val requestedFrames: Int = 0,
    val savedFrames: Int = 0,
    val receivedImages: Int = 0,
    val completedResults: Int = 0
)

internal typealias CameraPipelineEventSink = (CameraPipelineEvent) -> Unit

internal class CameraPipelineTerminalPublisher(
    private val sink: CameraPipelineEventSink
) {
    private val published = AtomicBoolean(false)

    fun publish(
        kind: CameraPipelineEvent.Terminal.Kind,
        requiredOutputCommitted: Boolean = false,
        publicExportCommitted: Boolean = false,
        verified: Boolean = false,
        captureResourcesSettled: Boolean = true,
        counts: CameraPipelineProgressCounts = CameraPipelineProgressCounts(),
        message: String? = null,
        jobDirectoryPath: String? = null
    ): Boolean {
        if (!published.compareAndSet(false, true)) return false
        sink(
            CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = kind,
                requiredOutputCommitted = requiredOutputCommitted,
                publicExportCommitted = publicExportCommitted,
                verified = verified,
                captureResourcesSettled = captureResourcesSettled,
                counts = counts,
                message = message,
                jobDirectoryPath = jobDirectoryPath
            )
        )
        return true
    }

    /** True once this publisher emitted its single terminal (or claimed it). */
    fun isPublished(): Boolean = published.get()
}

internal fun CameraPipelineEvent.withGeneration(generation: Long): CameraPipelineEvent = when (this) {
    is CameraPipelineEvent.Started -> copy(generation = generation)
    is CameraPipelineEvent.CaptureProgress -> copy(generation = generation)
    is CameraPipelineEvent.CaptureStageComplete -> copy(generation = generation)
    is CameraPipelineEvent.ProcessingStage -> copy(generation = generation)
    is CameraPipelineEvent.ExportStage -> copy(generation = generation)
    is CameraPipelineEvent.Terminal -> copy(generation = generation)
}

internal fun CameraPipelineProgressCounts.toCaptureProgress(
    previous: CaptureProgressState,
    stage: CaptureStage,
    message: String?
): CaptureProgressState {
    val progress = when (stage) {
        CaptureStage.IDLE -> 0f
        CaptureStage.PREPARING -> 0.05f
        CaptureStage.CAPTURING -> if (requestedFrames > 0) savedFrames.toFloat() / requestedFrames else previous.progressPercent
        CaptureStage.PROCESSING -> 0.65f
        CaptureStage.DEMOSAICING -> 0.75f
        CaptureStage.EXPORTING -> 0.85f
        CaptureStage.VERIFYING -> 0.92f
        CaptureStage.CLEANING -> 0.97f
        CaptureStage.COMPLETE,
        CaptureStage.FAILED,
        CaptureStage.CANCELLED,
        CaptureStage.TIMEOUT -> 1f
    }
    return previous.copy(
        stage = stage,
        message = message ?: previous.message,
        requestedFrames = requestedFrames,
        savedFrames = savedFrames,
        receivedImages = receivedImages,
        completedResults = completedResults,
        progressPercent = progress.coerceIn(0f, 1f)
    )
}

/** Compatibility-only adapter for legacy String callbacks. It never owns UI lifecycle. */
internal fun legacyCameraPipelineEvent(
    generation: Long,
    status: String,
    fallback: CaptureProgressState
): CameraPipelineEvent {
    val parsed = parseCaptureProgress(status, fallback)
    val counts = CameraPipelineProgressCounts(
        requestedFrames = parsed.requestedFrames,
        savedFrames = parsed.savedFrames,
        receivedImages = parsed.receivedImages,
        completedResults = parsed.completedResults
    )
    val normalized = status.trimStart()
    return when {
        normalized.startsWith("PIPELINE_COMPLETE_PARTIAL", ignoreCase = true) ->
            CameraPipelineEvent.Terminal(
                generation = generation,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = true,
                counts = counts,
                message = status
            )
        normalized.startsWith("PIPELINE_COMPLETE", ignoreCase = true) ||
            normalized.startsWith("EXPORT_COMPLETE", ignoreCase = true) ->
            CameraPipelineEvent.Terminal(
                generation = generation,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = true,
                counts = counts,
                message = status
            )
        normalized.startsWith("PIPELINE_CANCELLED", ignoreCase = true) ->
            CameraPipelineEvent.Terminal(
                generation = generation,
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                captureResourcesSettled = true,
                counts = counts,
                message = status
            )
        normalized.startsWith("PIPELINE_FAILED", ignoreCase = true) ||
            normalized.startsWith("CAPTURE_FAILED", ignoreCase = true) ||
            normalized.startsWith("PROCESS_FAILED", ignoreCase = true) ||
            normalized.startsWith("EXPORT_FAILED", ignoreCase = true) ||
            normalized.startsWith("CAPTURE_TIMEOUT", ignoreCase = true) ||
            normalized.startsWith("PROCESS_TIMEOUT", ignoreCase = true) ||
            normalized.startsWith("EXPORT_TIMEOUT", ignoreCase = true) ->
            CameraPipelineEvent.Terminal(
                generation = generation,
                kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                captureResourcesSettled = true,
                counts = counts,
                message = status
            )
        isCaptureStageCompleteButPipelineStillRunning(status) ->
            CameraPipelineEvent.CaptureStageComplete(generation, counts, status)
        parsed.stage == CaptureStage.EXPORTING ||
            parsed.stage == CaptureStage.VERIFYING ||
            parsed.stage == CaptureStage.CLEANING ->
            CameraPipelineEvent.ExportStage(generation, parsed.stage, counts, status)
        parsed.stage == CaptureStage.PROCESSING || parsed.stage == CaptureStage.DEMOSAICING ->
            CameraPipelineEvent.ProcessingStage(generation, parsed.stage, counts, status)
        else -> CameraPipelineEvent.CaptureProgress(generation, counts, status)
    }
}

/** Compatibility-only status assertion retained for legacy parser tests and diagnostics. */
internal fun legacyShouldIgnoreCancelledPipelineStatus(
    cancelled: Boolean,
    timedOutGeneration: Int,
    localGeneration: Int,
    pipelineGeneration: Int,
    status: String
): Boolean {
    if (!cancelled) return false
    val committedMarker = status.trimStart().startsWith("PIPELINE_COMPLETE", ignoreCase = true)
    return !(timedOutGeneration == localGeneration &&
        localGeneration == pipelineGeneration &&
        committedMarker)
}
