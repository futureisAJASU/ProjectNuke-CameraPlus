package com.projectnuke.keplernightlab

/**
 * Structured per-frame DNG sidecar status for the RAW capture pipeline.
 *
 * Each frame's DNG sidecar moves through an explicit state machine:
 *
 *   NOT_REQUESTED -> LOCAL_SAVED  (success path)
 *   NOT_REQUESTED -> LOCAL_SAVE_FAILED  (raw16 ok, DNG failed locally)
 *   LOCAL_SAVED -> PUBLIC_EXPORT_PENDING -> PUBLIC_EXPORTED
 *                                \-> PUBLIC_EXPORT_FAILED
 *
 * State transitions are recorded in the manifest so non-prefix failures are
 * representable: a frame is identified by [frameIndex] (not by filename prefix),
 * so failure modes that don't match the conventional `frame_NN.dng` name still
 * have a structured representation.
 *
 * A [RawDngSidecarOutcome] bundles the status with the sidecar filename and
 * the optional failure description so the manifest entry can be built from a
 * single typed object instead of a tangle of string-only fields.
 */
enum class RawDngSidecarStatus {
    /** The DNG sidecar was not requested for this frame. */
    NOT_REQUESTED,
    /** The worker owns a requested DNG sidecar but has not completed its write. */
    LOCAL_SAVE_PENDING,
    /** The DNG sidecar was successfully saved locally. */
    LOCAL_SAVED,
    /** The DNG sidecar local save failed. */
    LOCAL_SAVE_FAILED,
    /** Public export has not yet been attempted for this frame. */
    PUBLIC_EXPORT_PENDING,
    /** The DNG sidecar was successfully exported to public storage. */
    PUBLIC_EXPORTED,
    /** The DNG sidecar public export failed. */
    PUBLIC_EXPORT_FAILED;

    companion object {
        /** Parse a status string from JSON manifest entries. Unknown strings map to [NOT_REQUESTED]. */
        fun parseOrDefault(raw: String?): RawDngSidecarStatus = when (raw) {
            null, "", "null" -> NOT_REQUESTED
            else -> entries.firstOrNull { it.name == raw } ?: NOT_REQUESTED
        }
    }
}

/**
 * Structured per-frame DNG sidecar outcome. Persisted in the manifest as the
 * source of truth for the frame's DNG sidecar lifecycle, replacing the previous
 * string-only `dngSidecarStatus` / `dngFile` / `dngSidecarError` field trio.
 */
data class RawDngSidecarOutcome(
    val frameIndex: Int,
    val status: RawDngSidecarStatus,
    val sidecarFilename: String?,
    val publicUri: String?,
    val failureDescription: String?
) {
    /** True when local DNG save succeeded (LOCAL_SAVED). */
    val isLocallySaved: Boolean get() = status == RawDngSidecarStatus.LOCAL_SAVED
    /** True when local DNG save failed but the required raw16 frame was preserved. */
    val isLocalFailureOnly: Boolean get() = status == RawDngSidecarStatus.LOCAL_SAVE_FAILED
    /** True when public export has succeeded. */
    val isPublicExported: Boolean get() = status == RawDngSidecarStatus.PUBLIC_EXPORTED

    companion object {
        fun notRequested(frameIndex: Int): RawDngSidecarOutcome =
            RawDngSidecarOutcome(frameIndex, RawDngSidecarStatus.NOT_REQUESTED, null, null, null)

        fun localSavePending(frameIndex: Int, filename: String): RawDngSidecarOutcome =
            RawDngSidecarOutcome(frameIndex, RawDngSidecarStatus.LOCAL_SAVE_PENDING, filename, null, null)

        fun localSaved(frameIndex: Int, filename: String): RawDngSidecarOutcome =
            RawDngSidecarOutcome(frameIndex, RawDngSidecarStatus.LOCAL_SAVED, filename, null, null)

        fun localSaveFailed(frameIndex: Int, failureDescription: String): RawDngSidecarOutcome =
            RawDngSidecarOutcome(frameIndex, RawDngSidecarStatus.LOCAL_SAVE_FAILED, null, null, failureDescription)

        fun publicExportPending(frameIndex: Int, localFilename: String): RawDngSidecarOutcome =
            RawDngSidecarOutcome(frameIndex, RawDngSidecarStatus.PUBLIC_EXPORT_PENDING, localFilename, null, null)

        fun publicExported(frameIndex: Int, localFilename: String, publicUri: String): RawDngSidecarOutcome =
            RawDngSidecarOutcome(frameIndex, RawDngSidecarStatus.PUBLIC_EXPORTED, localFilename, publicUri, null)

        fun publicExportFailed(frameIndex: Int, localFilename: String, failureDescription: String): RawDngSidecarOutcome =
            RawDngSidecarOutcome(
                frameIndex, RawDngSidecarStatus.PUBLIC_EXPORT_FAILED,
                localFilename, null, failureDescription
            )
    }
}
