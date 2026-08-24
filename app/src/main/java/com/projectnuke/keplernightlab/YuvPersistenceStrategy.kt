package com.projectnuke.keplernightlab

/**
 * Phase 7 A/B seam: which durable source format a YUV capture persists.
 *
 * Production default remains [PNG]. [PACKED_YUV_V1] exists ONLY behind an
 * explicit debug/instrumentation selection and is stamped into job.json at
 * capture creation - after that stamp, the durable metadata key (never the
 * selector, never filenames) is the authority for every later decision.
 */
internal enum class YuvPersistenceStrategy {
    PNG,
    PACKED_YUV_V1;

    companion object {
        const val JOB_KEY = "yuvPersistenceStrategy"

        /** Tolerant parser for persisted metadata; unknown/blank means legacy PNG. */
        fun fromNameOrDefault(name: String?): YuvPersistenceStrategy =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: PNG
    }
}

/** Honest durable source filename for one YUV frame under [strategy]. */
internal fun yuvFrameFileName(frameIndex: Int, strategy: YuvPersistenceStrategy): String {
    val base = frameIndex.toString().padStart(2, '0')
    val extension = when (strategy) {
        YuvPersistenceStrategy.PNG -> "png"
        YuvPersistenceStrategy.PACKED_YUV_V1 -> "yuvpack"
    }
    return "frame_${base}_color.$extension"
}
