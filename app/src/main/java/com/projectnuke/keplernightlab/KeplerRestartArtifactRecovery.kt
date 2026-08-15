package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File

internal enum class KeplerMetadataTempClassification { NONE, STALE_CLEANED, PROMOTED_SINGLE_VALID, AMBIGUOUS }

internal var metadataTempInspectionFailureForTest: Throwable? = null
internal var metadataTempDeleteFailureForTest: Throwable? = null
internal var metadataTempCandidateReadFailureForTest: Throwable? = null
internal var metadataTempPromotionFailureForTest: Throwable? = null
internal var cacheCleanupDeleteFailureForTest: Throwable? = null

internal data class KeplerMetadataTempRecovery(
    val classification: KeplerMetadataTempClassification,
    val actions: List<String> = emptyList(),
    val failures: List<String> = emptyList()
)

internal fun reconcileJobMetadataWriteTemps(jobDir: File): KeplerMetadataTempRecovery {
    val children = try {
        metadataTempInspectionFailureForTest?.let { failure ->
            metadataTempInspectionFailureForTest = null
            throw failure
        }
        NoFollowFileSystem.requireDirectChildren(jobDir)
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        return KeplerMetadataTempRecovery(KeplerMetadataTempClassification.AMBIGUOUS, failures = listOf("Metadata temp inspection failed: ${failure.message}"))
    }
    val candidates = children.filter { file ->
        NoFollowFileSystem.isRealFile(file.toPath()) && file.name.matches(Regex("\\.job\\.json\\.\\d+\\.tmp"))
    }
    if (candidates.isEmpty()) return KeplerMetadataTempRecovery(KeplerMetadataTempClassification.NONE)
    if (NoFollowFileSystem.resolveDirectChild(jobDir, JOB_JSON_FILE_NAME, requireFile = true) != null) {
        val failures = candidates.mapNotNull { candidate ->
            try {
                metadataTempDeleteFailureForTest?.let { failure ->
                    metadataTempDeleteFailureForTest = null
                    throw failure
                }
                val deleted = candidate.delete()
                if (deleted || !candidate.exists()) null else "Could not delete stale metadata temp ${candidate.name}."
            } catch (failure: Error) {
                throw failure
            } catch (failure: Exception) {
                "Could not delete stale metadata temp ${candidate.name}: ${failure.message}"
            }
        }
        return KeplerMetadataTempRecovery(
            if (failures.isEmpty()) KeplerMetadataTempClassification.STALE_CLEANED else KeplerMetadataTempClassification.AMBIGUOUS,
            actions = candidates.filterNot { it.exists() }.map { "DELETED_${it.name}" },
            failures = failures
        )
    }
    val valid = candidates.mapNotNull { candidate ->
        try {
            metadataTempCandidateReadFailureForTest?.let { failure ->
                metadataTempCandidateReadFailureForTest = null
                throw failure
            }
            val json = JSONObject(NoFollowFileSystem.readTextVerified(candidate))
            requireValidMetadataReplacement(jobDir, json)
            candidate
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }
    if (valid.size != 1 || valid.size != candidates.size) {
        return KeplerMetadataTempRecovery(KeplerMetadataTempClassification.AMBIGUOUS, failures = listOf("Metadata replacement is ambiguous; candidates were preserved."))
    }
    return try {
        metadataTempPromotionFailureForTest?.let { failure ->
            metadataTempPromotionFailureForTest = null
            throw failure
        }
        KeplerJobMetadata.atomicReplace(valid.single(), File(jobDir, JOB_JSON_FILE_NAME))
        KeplerMetadataTempRecovery(KeplerMetadataTempClassification.PROMOTED_SINGLE_VALID, actions = listOf("PROMOTED_${valid.single().name}"))
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        KeplerMetadataTempRecovery(KeplerMetadataTempClassification.AMBIGUOUS, failures = listOf("Valid metadata replacement could not be promoted: ${failure.message}"))
    }
}

private fun requireValidMetadataReplacement(jobDir: File, job: JSONObject) {
    require(job.optInt("schemaVersion", 1) == 1) { "Unsupported metadata schema" }
    val jobType = job.optString("jobType").trim()
    require(jobType in setOf(
        "RAW", "RAW_NIGHT_FUSION", "YUV_NIGHT_FUSION", "YUV_SINGLE_FRAME",
        "SUPER_RESOLUTION", "SUPER_RESOLUTION_FUSION"
    )) { "Unknown job type" }
    require(job.has("status") || job.has("currentPipelineStage") || job.has("frames")) {
        "Metadata has no critical job evidence"
    }
    job.optJSONArray("frames")?.let { frames ->
        for (index in 0 until frames.length()) require(frames.optJSONObject(index) != null) {
            "Invalid frame metadata at index $index"
        }
    }
    job.optString("jobDirAbsolutePath").takeIf { it.isNotBlank() }?.let { path ->
        require(File(path).canonicalFile == jobDir.canonicalFile) { "Metadata job identity mismatch" }
    }
    val dirName = jobDir.name
    if (dirName.startsWith("KPL_RAW_FUSION_")) {
        require(jobType == "RAW" || jobType == "RAW_NIGHT_FUSION") { "RAW directory has incompatible job type" }
    } else if (dirName.startsWith("KPL_YUV_FUSION_")) {
        require(jobType == "YUV_NIGHT_FUSION" || jobType == "YUV_SINGLE_FRAME") { "YUV directory has incompatible job type" }
    } else if (dirName.startsWith("KPL_SUPER_RES_")) {
        require(jobType == "SUPER_RESOLUTION" || jobType == "SUPER_RESOLUTION_FUSION") { "Super-resolution directory has incompatible job type" }
    }
}

internal data class KeplerCaptureTempRecovery(val deleted: List<String> = emptyList(), val failures: List<String> = emptyList())

internal data class KeplerCacheCleanupResult(
    val deleted: List<String> = emptyList(),
    val failures: List<String> = emptyList()
)

/** Exact direct-child cleanup for manifest-owned capture transaction temps after old-process loss. */
internal fun recoverCaptureOwnedTemps(jobDir: File, job: JSONObject, oldActiveOperation: Boolean): KeplerCaptureTempRecovery {
    if (!oldActiveOperation) return KeplerCaptureTempRecovery()
    val ownedFinals = buildSet {
        job.optJSONArray("frames")?.let { frames ->
            for (index in 0 until frames.length()) {
                val frame = frames.optJSONObject(index) ?: continue
                listOf("file", "rawFile", "raw16File", "dngFile").forEach { key ->
                    frame.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let(::add)
                }
            }
        }
    }
    if (ownedFinals.isEmpty()) return KeplerCaptureTempRecovery()
    val deleted = mutableListOf<String>()
    val failures = mutableListOf<String>()
    NoFollowFileSystem.requireDirectChildren(jobDir).filter { file ->
        NoFollowFileSystem.isRealFile(file.toPath()) && file.name.endsWith(".tmp") && ownedFinals.any { file.name.startsWith(".$it.") }
    }.forEach { file ->
        try { if (file.delete() || !file.exists()) deleted += file.name else failures += "Could not delete owned capture temp ${file.name}." }
        catch (failure: Exception) { failures += "Could not delete owned capture temp ${file.name}: ${failure.message}" }
    }
    return KeplerCaptureTempRecovery(deleted, failures)
}

internal fun cleanStaleKeplerExportCacheFiles(cacheDir: File): List<String> {
    return cleanStaleKeplerExportCacheFilesDetailed(cacheDir).deleted
}

internal fun cleanStaleKeplerExportCacheFilesDetailed(cacheDir: File): KeplerCacheCleanupResult {
    if (!NoFollowFileSystem.isRealDirectory(cacheDir.toPath())) return KeplerCacheCleanupResult()
    val deleted = mutableListOf<String>()
    val failures = mutableListOf<String>()
    NoFollowFileSystem.requireDirectChildren(cacheDir).filter { file ->
        NoFollowFileSystem.isRealFile(file.toPath()) && file.name.startsWith("kepler_export_") && file.name.endsWith(".heic")
    }.forEach { file ->
        try {
            cacheCleanupDeleteFailureForTest?.let { failure ->
                cacheCleanupDeleteFailureForTest = null
                throw failure
            }
            if (file.delete() || !file.exists()) deleted += file.name
            else failures += "Could not delete cache export ${file.name}."
        } catch (failure: Error) {
            throw failure
        } catch (failure: Exception) {
            failures += "Could not delete cache export ${file.name}: ${failure.message}"
        }
    }
    return KeplerCacheCleanupResult(deleted, failures)
}
