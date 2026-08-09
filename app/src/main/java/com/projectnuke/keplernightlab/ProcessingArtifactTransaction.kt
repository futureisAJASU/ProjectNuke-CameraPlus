package com.projectnuke.keplernightlab

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import org.json.JSONObject

internal enum class ProcessingArtifactState {
    PLANNED,
    TEMP_OWNED,
    TEMP_VERIFIED,
    COMMITTED_FINAL,
    FINAL_VERIFIED,
    ADOPTED,
    ROLLED_BACK,
    CLEANUP_FAILED
}

internal data class ProcessingArtifactResult(
    val finalFile: File,
    val state: ProcessingArtifactState,
    val cleanupFailure: Throwable? = null
)

/**
 * Raised when an artifact could not be committed or verified.  The paths are
 * retained so callers can report/settle a path which survived a failed
 * cleanup attempt instead of losing ownership at the exception boundary.
 */
internal class ProcessingArtifactException(
    val finalFile: File,
    val tempFile: File,
    val cleanupFailure: Throwable?,
    cause: Throwable
) : IllegalStateException("Processing artifact transaction failed for ${finalFile.absolutePath}", cause)

internal fun commitProcessingArtifact(
    finalFile: File,
    writeTemp: (File) -> Unit,
    verifyFinal: (File) -> Unit
): ProcessingArtifactResult {
    val parent = finalFile.parentFile ?: error("Artifact parent is missing")
    require(NoFollowFileSystem.isRealDirectory(parent.toPath())) { "Artifact parent must be a real directory" }
    require(!Files.isSymbolicLink(finalFile.toPath())) { "Artifact destination must not be a symbolic link" }
    val temp = File(parent, ".${finalFile.name}.${System.nanoTime()}.tmp")
    var state = ProcessingArtifactState.PLANNED
    try {
        check(!Files.exists(temp.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Artifact temp already exists" }
        state = ProcessingArtifactState.TEMP_OWNED
        writeTemp(temp)
        check(Files.isRegularFile(temp.toPath(), LinkOption.NOFOLLOW_LINKS) && temp.length() > 0L) {
            "Artifact temp verification failed"
        }
        state = ProcessingArtifactState.TEMP_VERIFIED
        KeplerJobMetadata.atomicReplace(temp, finalFile)
        state = ProcessingArtifactState.COMMITTED_FINAL
        verifyFinal(finalFile)
        state = ProcessingArtifactState.FINAL_VERIFIED
        return ProcessingArtifactResult(finalFile, ProcessingArtifactState.ADOPTED)
    } catch (failure: Throwable) {
        val tempCleanupFailure = try {
            if (temp.exists() && !temp.delete()) {
                IllegalStateException("Could not delete artifact temp ${temp.absolutePath}")
            } else {
                null
            }
        } catch (cleanup: Throwable) {
            cleanup
        }
        // Once atomic replacement completed, the final path belongs to this
        // attempt.  Verification failure must not strand an unadopted final.
        val finalCleanupFailure = if (state >= ProcessingArtifactState.COMMITTED_FINAL) {
            try {
                val finalPath = finalFile.toPath()
                if (Files.isSymbolicLink(finalPath)) {
                    IllegalStateException("Refusing to delete symlink artifact ${finalFile.absolutePath}")
                } else if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.deleteIfExists(finalPath)
                ) {
                    IllegalStateException("Could not delete unverified artifact ${finalFile.absolutePath}")
                } else {
                    null
                }
            } catch (cleanup: Throwable) {
                cleanup
            }
        } else {
            null
        }
        val cleanupFailure = listOfNotNull(tempCleanupFailure, finalCleanupFailure)
            .reduceOrNull { first, next -> first.apply { addSuppressed(next) } }
        if (cleanupFailure != null) {
            failure.addSuppressed(cleanupFailure)
            state = ProcessingArtifactState.CLEANUP_FAILED
        } else if (state != ProcessingArtifactState.COMMITTED_FINAL) {
            state = ProcessingArtifactState.ROLLED_BACK
        }
        if (failure is Error) {
            throw failure
        }
        throw ProcessingArtifactException(finalFile, temp, cleanupFailure, failure)
    }
}

internal fun writeVerifiedTextArtifact(finalFile: File, text: String): ProcessingArtifactResult =
    commitProcessingArtifact(
        finalFile = finalFile,
        writeTemp = { temp ->
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        },
        verifyFinal = { committed ->
            val verified = NoFollowFileSystem.readTextVerified(committed)
            check(verified.isNotEmpty()) { "Text artifact is empty" }
            check(verified.trim().let { it.startsWith("{") && it.endsWith("}") }) {
                "Text artifact is not a JSON object"
            }
            JSONObject(verified)
        }
    )

internal fun copyVerifiedArtifact(sourceFile: File, finalFile: File): ProcessingArtifactResult =
    commitProcessingArtifact(
        finalFile = finalFile,
        writeTemp = { temp ->
            FileOutputStream(temp).use { output ->
                NoFollowFileSystem.copyVerified(sourceFile, output)
                output.flush()
                output.fd.sync()
            }
        },
        verifyFinal = { committed ->
            check(NoFollowFileSystem.digestVerified(committed).size > 0L) {
                "Copied artifact is empty"
            }
        }
    )

internal fun verifyPngArtifact(file: File) {
    val prefix = NoFollowFileSystem.digestVerified(file).prefix
    check(prefix.size >= 8 && prefix.copyOf(8).contentEquals(
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    )) { "Invalid PNG artifact ${file.name}" }
}

internal fun verifyJpegArtifact(file: File) {
    val prefix = NoFollowFileSystem.digestVerified(file).prefix
    check(prefix.size >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xD8.toByte()) {
        "Invalid JPEG artifact ${file.name}"
    }
}
