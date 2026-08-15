package com.projectnuke.keplernightlab

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * Owns the exact RandomAccessFile used for RAW processing. The opened handle
 * is sampled, rather than opening a second descriptor for identity evidence;
 * when the provider cannot expose a comparable file key, the streamed digest
 * is the fail-closed content fence.
 */
internal class VerifiedRandomAccessHandle private constructor(
    val file: File,
    val randomAccess: RandomAccessFile,
    val expectedSize: Long,
    private val openedDigest: String,
    private val expectedIdentity: NoFollowFileSystem.StableFileIdentity,
    private val closeAction: () -> Unit
) {
    private var closed = false

    internal fun verifyPathStillMatches() {
        val current = NoFollowFileSystem.stableIdentity(file)
        check(current.size == expectedSize) { "RAW input size changed: ${file.name}" }
        check(current.sha256 == openedDigest) {
            "RAW input content changed after opening: ${file.name}"
        }
        val expectedKey = expectedIdentity.fileKey
        val currentKey = current.fileKey
        if (expectedKey != null && currentKey != null) {
            check(expectedKey == currentKey) { "RAW input object changed: ${file.name}" }
        }
    }

    internal fun close(): Throwable? {
        if (closed) return null
        closed = true
        return try {
            closeAction()
            null
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            failure
        }
    }

    /**
     * Runs one operation against the verified descriptor and settles that descriptor without
     * allowing a secondary close failure to replace the operation's primary failure.
     */
    internal fun <T> use(block: (RandomAccessFile) -> T): T {
        var primaryFailure: Throwable? = null
        try {
            return block(randomAccess)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val closeFailure = try {
                close()
            } catch (closeFatal: Error) {
                when {
                    primaryFailure is Error -> {
                        if (primaryFailure !== closeFatal) primaryFailure.addSuppressed(closeFatal)
                        throw primaryFailure
                    }
                    primaryFailure != null -> {
                        closeFatal.addSuppressed(primaryFailure)
                        throw closeFatal
                    }
                    else -> throw closeFatal
                }
            }
            if (closeFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure)
                } else {
                    throw closeFailure
                }
            }
        }
    }

    companion object {
        internal fun open(file: File, expectedSize: Long): VerifiedRandomAccessHandle {
            val before = NoFollowFileSystem.stableIdentity(file)
            check(before.size == expectedSize) { "RAW input has invalid size: ${file.name}" }
            check(!Files.isSymbolicLink(file.toPath())) { "RAW input must not be a symlink" }
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "RAW input must be a regular file"
            }
            val randomAccess = RandomAccessFile(file, "r")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var remaining = expectedSize
                while (remaining > 0L) {
                    val count = randomAccess.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0) { "RAW input ended while opening: ${file.name}" }
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
                randomAccess.seek(0L)
                val openedDigest = digest.digest().joinToString("") { "%02x".format(it) }
                check(before.sha256 == openedDigest) {
                    "RAW input was replaced while opening: ${file.name}"
                }
                return VerifiedRandomAccessHandle(
                    file,
                    randomAccess,
                    expectedSize,
                    openedDigest,
                    before,
                    closeAction = { randomAccess.close() }
                )
            } catch (failure: Throwable) {
                try {
                    randomAccess.close()
                } catch (closeFailure: Throwable) {
                    when {
                        failure is Error -> {
                            if (failure !== closeFailure) failure.addSuppressed(closeFailure)
                        }
                        closeFailure is Error -> {
                            closeFailure.addSuppressed(failure)
                            throw closeFailure
                        }
                        else -> failure.addSuppressed(closeFailure)
                    }
                }
                throw failure
            }
        }

        internal fun openForTesting(
            file: File,
            expectedSize: Long,
            closeFailure: Throwable
        ): VerifiedRandomAccessHandle {
            val before = NoFollowFileSystem.stableIdentity(file)
            check(before.size == expectedSize)
            val randomAccess = RandomAccessFile(file, "r")
            return VerifiedRandomAccessHandle(
                file,
                randomAccess,
                expectedSize,
                before.sha256,
                before,
                closeAction = {
                    try {
                        randomAccess.close()
                    } finally {
                        throw closeFailure
                    }
                }
            )
        }
    }
}
