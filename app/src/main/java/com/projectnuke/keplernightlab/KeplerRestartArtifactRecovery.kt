package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File

internal enum class KeplerMetadataTempClassification { NONE, STALE_CLEANED, PROMOTED_SINGLE_VALID, AMBIGUOUS }

internal data class KeplerMetadataTempRecovery(
    val classification: KeplerMetadataTempClassification,
    val actions: List<String> = emptyList(),
    val failures: List<String> = emptyList()
)

internal fun reconcileJobMetadataWriteTemps(jobDir: File): KeplerMetadataTempRecovery {
    val children = runCatching { NoFollowFileSystem.requireDirectChildren(jobDir) }.getOrElse {
        return KeplerMetadataTempRecovery(KeplerMetadataTempClassification.AMBIGUOUS, failures = listOf("Metadata temp inspection failed: ${it.message}"))
    }
    val candidates = children.filter { file ->
        NoFollowFileSystem.isRealFile(file.toPath()) && file.name.matches(Regex("\\.job\\.json\\.\\d+\\.tmp"))
    }
    if (candidates.isEmpty()) return KeplerMetadataTempRecovery(KeplerMetadataTempClassification.NONE)
    if (NoFollowFileSystem.resolveDirectChild(jobDir, JOB_JSON_FILE_NAME, requireFile = true) != null) {
        val failures = candidates.mapNotNull { candidate ->
            runCatching { candidate.delete() }.fold(
                onSuccess = { deleted -> if (deleted || !candidate.exists()) null else "Could not delete stale metadata temp ${candidate.name}." },
                onFailure = { "Could not delete stale metadata temp ${candidate.name}: ${it.message}" }
            )
        }
        return KeplerMetadataTempRecovery(
            if (failures.isEmpty()) KeplerMetadataTempClassification.STALE_CLEANED else KeplerMetadataTempClassification.AMBIGUOUS,
            actions = candidates.filterNot { it.exists() }.map { "DELETED_${it.name}" },
            failures = failures
        )
    }
    val valid = candidates.mapNotNull { candidate ->
        runCatching {
            val json = JSONObject(NoFollowFileSystem.readTextVerified(candidate))
            require(json.optString("jobType").isNotBlank() || json.optString("status").isNotBlank())
            candidate
        }.getOrNull()
    }
    if (valid.size != 1 || valid.size != candidates.size) {
        return KeplerMetadataTempRecovery(KeplerMetadataTempClassification.AMBIGUOUS, failures = listOf("Metadata replacement is ambiguous; candidates were preserved."))
    }
    return runCatching {
        KeplerJobMetadata.atomicReplace(valid.single(), File(jobDir, JOB_JSON_FILE_NAME))
        KeplerMetadataTempRecovery(KeplerMetadataTempClassification.PROMOTED_SINGLE_VALID, actions = listOf("PROMOTED_${valid.single().name}"))
    }.getOrElse { KeplerMetadataTempRecovery(KeplerMetadataTempClassification.AMBIGUOUS, failures = listOf("Valid metadata replacement could not be promoted: ${it.message}")) }
}

internal data class KeplerCaptureTempRecovery(val deleted: List<String> = emptyList(), val failures: List<String> = emptyList())

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
    if (!NoFollowFileSystem.isRealDirectory(cacheDir.toPath())) return emptyList()
    return NoFollowFileSystem.requireDirectChildren(cacheDir).filter { file ->
        NoFollowFileSystem.isRealFile(file.toPath()) && file.name.startsWith("kepler_export_") && file.name.endsWith(".heic")
    }.mapNotNull { file -> if (file.delete() || !file.exists()) file.name else null }
}
