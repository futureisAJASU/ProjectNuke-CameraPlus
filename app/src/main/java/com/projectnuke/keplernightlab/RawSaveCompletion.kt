package com.projectnuke.keplernightlab

import java.io.File

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
sealed interface RawSaveCompletion {
    /** Frame index the completion refers to (required for late/orphan correlation). */
    val frameIndex: Int
    /** Timestamp (ns) the completion refers to. */
    val timestampNs: Long

    /**
     * Frame raw16 saved successfully; optional DNG sidecar may also be saved.
     *
     * @param raw16Filename final raw16 filename committed by the worker
     * @param dngSidecar outcome of the per-frame DNG sidecar (NOT_REQUESTED if disabled)
     */
    data class Success(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val raw16Filename: String,
        val raw16Bytes: Long,
        val saveDurationMs: Long,
        val dngSidecar: RawDngSidecarOutcome
    ) : RawSaveCompletion

    /**
     * Frame raw16 save failed.
     *
     * @param failureType short failure category (e.g. "OutOfMemoryError", "encode threw")
     * @param failureMessage human-readable failure description
     */
    data class Failed(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val raw16TempFile: File?,
        val failureType: String,
        val failureMessage: String,
        val throwable: Throwable?
    ) : RawSaveCompletion
}
