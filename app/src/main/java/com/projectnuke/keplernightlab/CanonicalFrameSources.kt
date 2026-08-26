package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Phase 10 — ONE canonical, job-aware source authority for every consumer
 * (gallery frame summary, storage accounting, cleanup predicates, source
 * availability, detectReprocessCapability, reprocess backup protection,
 * reprocess frame counting).
 *
 * Authority rules:
 *  - RAW: raw16File is the canonical RAW source; DNG is a sidecar/alternate
 *    consulted only when no raw16 reference exists.
 *  - YUV PNG strategy: frame.file PNG is the canonical source.
 *  - YUV PACKED_YUV_V1: the durable .yuvpack file (packedSourceFilename) is
 *    the canonical immutable source; the converted frame_NN_color.png is a
 *    DERIVED, recomputable fusion input — never canonical authority.
 *
 * The durable metadata key [YuvPersistenceStrategy.JOB_KEY] decides packed vs
 * PNG strategy; filenames alone never upgrade a job to packed authority.
 */
internal enum class CanonicalFrameFormat {
    RAW16,
    DNG,
    PNG,
    PACKED_YUV_V1,
    UNKNOWN
}

internal data class CanonicalFrameSource(
    val frameIndex: Int,
    val sourceFormat: CanonicalFrameFormat,
    val sourceFile: File?,
    /** Converted/recomputable fusion input for packed jobs; null otherwise. */
    val optionalFusionInputFile: File?
)

internal object CanonicalFrameSources {

    fun packedStrategySelected(job: JSONObject?): Boolean =
        job != null && PackedYuvBackgroundConverter.isSelected(job)

    private val packedDerivedPngRegex = Regex("""frame_\d+_color\.png""")

    /** True when this filename is a converted (derived) fusion input of a packed job. */
    fun isDerivedFusionInputFileName(name: String, job: JSONObject?): Boolean {
        if (!packedStrategySelected(job)) return false
        return name.lowercase(Locale.US).matches(packedDerivedPngRegex)
    }

    /**
     * Job-aware canonical source filename predicate. .yuvpack is canonical only
     * under the packed strategy metadata stamp; generic helpers stay consistent.
     */
    fun isCanonicalSourceFileName(name: String, job: JSONObject?): Boolean {
        val lower = name.lowercase(Locale.US)
        if (!lower.startsWith("frame_")) return false
        if (isDerivedFusionInputFileName(name, job)) return false
        if (packedStrategySelected(job)) {
            return lower.endsWith(PackedYuvFrameStore.FILE_EXTENSION)
        }
        return lower.endsWith(".png") || lower.endsWith(".raw16") || lower.endsWith(".dng") ||
            lower.endsWith(".yuv") || lower.endsWith(".nv21") || lower.endsWith(".yuv420")
    }

    /** Canonical source name for one frames-manifest entry, by authority order. */
    fun canonicalFileName(frame: JSONObject, kind: ReprocessJobKind, job: JSONObject?): String {
        if (kind == ReprocessJobKind.RAW_FUSION) {
            return sequenceOf("raw16File", "dngFile", "file")
                .map { frame.optString(it) }
                .firstOrNull { it.isNotBlank() && it != "null" }
                .orEmpty()
        }
        if (packedStrategySelected(job)) {
            return sequenceOf("packedSourceFilename", "file", "yuvFile", "nv21File")
                .map { frame.optString(it) }
                .firstOrNull { it.isNotBlank() && it != "null" }
                .orEmpty()
        }
        return sequenceOf("file", "yuvFile", "nv21File")
            .map { frame.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()
    }

    /** Derived fusion input name for one manifest entry when a packed source is canonical. */
    fun optionalFusionInputName(frame: JSONObject, kind: ReprocessJobKind, job: JSONObject?): String? {
        if (kind != ReprocessJobKind.YUV_FUSION || !packedStrategySelected(job)) return null
        val packedName = canonicalFileName(frame, kind, job)
        if (!packedName.endsWith(PackedYuvFrameStore.FILE_EXTENSION)) return null
        val frameIndex = sequenceOf(
            frame.optInt("frameIndex", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            frame.optInt("index", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            packedFrameIndex(packedName)
        ).firstOrNull { it != null } ?: return null
        return yuvFrameFileName(frameIndex, YuvPersistenceStrategy.PNG)
    }

    private fun packedFrameIndex(fileName: String): Int? =
        Regex("""frame_(\d+)_color""").find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Resolve every declared frame's canonical source against actual disk truth.
     * Unsafe relative references are skipped (never resolved, never counted);
     * frames whose canonical file is absent resolve with sourceFile=null so
     * callers count real availability only.
     */
    fun resolve(jobDir: File, job: JSONObject?, kind: ReprocessJobKind): List<CanonicalFrameSource> {
        val frames = job?.optJSONArray("frames") ?: return emptyList()
        return buildList {
            repeat(frames.length()) { position ->
                val frame = frames.optJSONObject(position) ?: return@repeat
                val index = frame.optInt("index", position)
                val canonicalName = canonicalFileName(frame, kind, job)
                val fusionName = optionalFusionInputName(frame, kind, job)
                fun safeExisting(name: String?): File? =
                    name?.takeIf { it.isNotBlank() && it != "null" && isSafeRelativeFilename(it) }
                        ?.let { NoFollowFileSystem.optionalDirectChildFile(jobDir, it) }
                        ?.takeIf { it.isFile }
                val canonicalFile = safeExisting(canonicalName)
                    ?.takeIf { isCanonicalSourceFileName(it.name, job) }
                val fusionFile = safeExisting(fusionName)
                val format = when {
                    canonicalFile == null -> CanonicalFrameFormat.UNKNOWN
                    canonicalFile.name.endsWith(PackedYuvFrameStore.FILE_EXTENSION) -> CanonicalFrameFormat.PACKED_YUV_V1
                    canonicalFile.name.endsWith(".raw16") -> CanonicalFrameFormat.RAW16
                    canonicalFile.name.endsWith(".dng") -> CanonicalFrameFormat.DNG
                    else -> CanonicalFrameFormat.PNG
                }
                add(
                    CanonicalFrameSource(
                        frameIndex = index,
                        sourceFormat = format,
                        sourceFile = canonicalFile,
                        optionalFusionInputFile = fusionFile
                    )
                )
            }
        }
    }

    /** Actual on-disk canonical source count used by capability and availability truth. */
    fun countAvailable(jobDir: File, job: JSONObject, kind: ReprocessJobKind): Int =
        resolve(jobDir, job, kind).count { it.sourceFile != null }
}
