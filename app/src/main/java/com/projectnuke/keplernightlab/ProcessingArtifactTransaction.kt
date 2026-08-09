package com.projectnuke.keplernightlab

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

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
        val cleanupFailure = try {
            if (temp.exists() && !temp.delete()) {
                IllegalStateException("Could not delete artifact temp ${temp.absolutePath}")
            } else {
                null
            }
        } catch (cleanup: Throwable) {
            cleanup
        }
        if (cleanupFailure != null) {
            failure.addSuppressed(cleanupFailure)
            state = ProcessingArtifactState.CLEANUP_FAILED
        } else if (state != ProcessingArtifactState.COMMITTED_FINAL) {
            state = ProcessingArtifactState.ROLLED_BACK
        }
        throw failure
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
            check(NoFollowFileSystem.readTextVerified(committed).isNotEmpty()) { "Text artifact is empty" }
        }
    )
