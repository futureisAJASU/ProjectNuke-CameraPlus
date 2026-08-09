package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.io.OutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal fun noFollowIdentityMatches(
    expectedFileKey: Any?,
    actualFileKey: Any?,
    expectedSize: Long,
    actualSize: Long,
    expectedModifiedMillis: Long,
    actualModifiedMillis: Long
): Boolean = if (expectedFileKey != null && actualFileKey != null) {
    expectedFileKey == actualFileKey
} else {
    // This is only a stat-equality helper retained for callers that merely
    // report a change.  It is NOT a stable-identity proof when file keys are
    // absent; correctness-sensitive callers use StableFileIdentity below.
    expectedSize == actualSize && expectedModifiedMillis == actualModifiedMillis
}

/**
 * Best-effort descriptor identity probe.  It is deliberately given the exact
 * stream that supplies authoritative content; opening a second FileInputStream
 * here would introduce a replacement window.
 */
private fun descriptorIdentity(input: InputStream): String? = runCatching {
    (input as? FileInputStream)?.let { stream ->
        val os = Class.forName("android.system.Os")
        val stat = os.getMethod("fstat", java.io.FileDescriptor::class.java)
        .invoke(null, stream.fd)
        val dev = stat.javaClass.getField("st_dev").getLong(stat)
        val ino = stat.javaClass.getField("st_ino").getLong(stat)
        "$dev:$ino"
    }
}.getOrNull()

internal sealed interface NoFollowInspection<out T> {
    data object Absent : NoFollowInspection<Nothing>
    data class Present<T>(val value: T) : NoFollowInspection<T>
    data class InspectionFailed(val exception: Exception) : NoFollowInspection<Nothing>
}

internal object NoFollowFileSystem {
    data class StreamDigest(val size: Long, val sha256: String, val prefix: ByteArray)
    /**
     * Evidence produced by one authoritative no-follow stream read.  A null
     * fileKey/descriptor means this represents stable *content* (sha256), not
     * a proof that a provider kept the same underlying file object.
     */
    data class StableFileIdentity(
        val isRegularFile: Boolean,
        val size: Long,
        val modifiedMillis: Long,
        val fileKey: Any?,
        val descriptorIdentity: String?,
        val sha256: String
    )

    private data class VerifiedRead(
        val digest: StreamDigest,
        val identity: StableFileIdentity
    )
    private object DiscardOutput : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    /** Shared streaming engine for every verified read/copy operation. */
    private fun streamVerified(file: File, output: OutputStream): VerifiedRead {
        val path = file.toPath()
        val before = when (val inspection = inspect(path)) {
            NoFollowInspection.Absent -> throw java.io.FileNotFoundException(file.absolutePath)
            is NoFollowInspection.InspectionFailed -> throw inspection.exception
            is NoFollowInspection.Present -> inspection.value
        }
        require(before.isRegularFile && !before.isSymbolicLink()) { "Unsafe file: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(16)
        var prefixCount = 0
        var size = 0L
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val openedDescriptor = descriptorIdentity(input)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (prefixCount < prefix.size) {
                    val copied = minOf(read, prefix.size - prefixCount)
                    buffer.copyInto(prefix, prefixCount, 0, copied)
                    prefixCount += copied
                }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
                size += read
            }
            val digestHex = digest.digest().joinToString("") { "%02x".format(it) }
            val after = when (val inspection = inspect(path)) {
                is NoFollowInspection.Present -> inspection.value
                else -> null
            }
            require(after != null && after.isRegularFile && !after.isSymbolicLink()) {
                "File identity changed during read: ${file.absolutePath}"
            }
            val sameKey = before.fileKey() != null && after.fileKey() != null &&
                before.fileKey() == after.fileKey()
            val sameStat = before.size() == after.size() &&
                before.lastModifiedTime().toMillis() == after.lastModifiedTime().toMillis()
            // Without a provider file key we can prove only stable content.  A
            // second no-follow stream hashes the current pathname; equal bytes
            // make the content fence explicit rather than pretending stat data
            // is object identity.
            val sameContent = if (sameKey) true else {
                digestAtPath(path) == digestHex
            }
            require((sameKey || sameContent) && sameStat) {
                "File identity changed during read: ${file.absolutePath}"
            }
            val streamDigest = StreamDigest(size, digestHex, prefix.copyOf(prefixCount))
            return VerifiedRead(
                digest = streamDigest,
                identity = StableFileIdentity(
                    isRegularFile = true,
                    size = size,
                    modifiedMillis = after.lastModifiedTime().toMillis(),
                    fileKey = after.fileKey(),
                    descriptorIdentity = openedDescriptor,
                    sha256 = digestHex
                )
            )
        }
        error("Input stream closed before verified read completed")
    }

    private fun digestAtPath(path: Path): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Streams a stable regular file without following links; never loads a RAW/DNG into memory. */
    fun copyVerified(file: File, output: OutputStream): StreamDigest = streamVerified(file, output).digest

    fun digestVerified(file: File): StreamDigest = copyVerified(file, DiscardOutput)
    fun stableIdentity(file: File): StableFileIdentity = streamVerified(file, DiscardOutput).identity
    fun readBytesVerified(file: File): ByteArray = ByteArrayOutputStream().use { output ->
        streamVerified(file, output)
        output.toByteArray()
    }

    fun readTextVerified(file: File): String = readBytesVerified(file).toString(Charsets.UTF_8)

    /**
     * Reads lines with [File.readLines] semantics (LF/CRLF terminators stripped
     * and the single trailing empty line removed) while verifying stable file
     * identity, so it is a drop-in verified replacement for [File.readLines].
     */
    fun readLinesVerified(file: File): List<String> {
        val lines = readTextVerified(file)
            .split('\n')
            .map { if (it.endsWith('\r')) it.dropLast(1) else it }
        return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    fun decodeBitmapVerified(file: File, options: BitmapFactory.Options? = null): Bitmap? {
        val bytes = readBytesVerified(file)
        return if (options == null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    @Deprecated("Use inspect() and handle InspectionFailed explicitly")
    fun attributes(path: Path): BasicFileAttributes? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    fun inspect(path: Path): NoFollowInspection<BasicFileAttributes> = try {
        NoFollowInspection.Present(
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        NoFollowInspection.Absent
    } catch (error: Exception) {
        NoFollowInspection.InspectionFailed(error)
    }

    /**
     * Legacy stat-only revalidation.  It is fail-closed on providers without a
     * file key because size/mtime cannot prove identity.  New correctness
     * callers must retain [StableFileIdentity] from [stableIdentity].
     */
    fun revalidate(path: Path, expected: BasicFileAttributes): Boolean = when (val current = inspect(path)) {
        NoFollowInspection.Absent -> false
        is NoFollowInspection.InspectionFailed -> false
        is NoFollowInspection.Present -> {
            val actual = current.value
            !actual.isSymbolicLink() &&
                actual.isDirectory == expected.isDirectory &&
                actual.isRegularFile == expected.isRegularFile &&
                actual.isOther == expected.isOther &&
                expected.fileKey() != null && actual.fileKey() != null &&
                expected.fileKey() == actual.fileKey()
        }
    }

    /**
     * Revalidates a token emitted by the authoritative stream read.  Providers
     * with file keys get object identity; providers without them get a fresh
     * no-follow digest comparison, i.e. stable content rather than a false
     * object-identity claim.
     */
    fun revalidate(path: Path, expected: StableFileIdentity): Boolean {
        val current = try {
            stableIdentity(path.toFile())
        } catch (_: Exception) {
            return false
        }
        if (!current.isRegularFile || current.size != expected.size) return false
        if (expected.fileKey != null && current.fileKey != null) {
            return expected.fileKey == current.fileKey
        }
        return current.sha256 == expected.sha256
    }

    // Directory-stream providers on some Android/Windows filesystems do not expose file keys.
    // Traversal still refuses symlinks and type changes; authoritative replacement/read paths
    // use revalidate(), which remains fail-closed when identity is unavailable.
    private fun revalidateTraversal(path: Path, expected: BasicFileAttributes): Boolean = when (val current = inspect(path)) {
        NoFollowInspection.Absent -> false
        is NoFollowInspection.InspectionFailed -> false
        is NoFollowInspection.Present -> {
            val actual = current.value
            !actual.isSymbolicLink() &&
                actual.isDirectory == expected.isDirectory &&
                actual.isRegularFile == expected.isRegularFile &&
                actual.isOther == expected.isOther &&
                (expected.fileKey() == null || actual.fileKey() == expected.fileKey())
        }
    }

    fun resolveDirectChildResult(
        root: File,
        name: String,
        requireFile: Boolean = false
    ): NoFollowInspection<File> {
        val rootAttributes = when (val inspection = inspect(root.toPath())) {
            NoFollowInspection.Absent -> return NoFollowInspection.Absent
            is NoFollowInspection.InspectionFailed -> return inspection
            is NoFollowInspection.Present -> inspection.value
        }
        if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink()) {
            return NoFollowInspection.InspectionFailed(IllegalStateException("Expected a real directory"))
        }
        if (name.isBlank() || name != name.trim() ||
            name == "." || name == ".." || name.contains('/') || name.contains('\\')
        ) return NoFollowInspection.InspectionFailed(
            IllegalArgumentException("Unsafe direct-child path")
        )
        val path = root.toPath().resolve(name).normalize()
        if (path.parent != root.toPath()) {
            return NoFollowInspection.InspectionFailed(IllegalArgumentException("Path escapes root"))
        }
        val result = when (val result = inspect(path)) {
            NoFollowInspection.Absent -> NoFollowInspection.Absent
            is NoFollowInspection.InspectionFailed -> result
            is NoFollowInspection.Present -> {
                val attrs = result.value
                if (attrs.isSymbolicLink() || (requireFile && !attrs.isRegularFile)) {
                    NoFollowInspection.InspectionFailed(IllegalStateException("Unsafe filesystem entry"))
                } else {
                    NoFollowInspection.Present(path.toFile())
                }
            }
        }
        return result
    }

    fun isRealDirectory(path: Path): Boolean = when (val result = inspect(path)) {
        is NoFollowInspection.Present -> result.value.isDirectory && !result.value.isSymbolicLink()
        else -> false
    }

    fun isRealFile(path: Path): Boolean = when (val result = inspect(path)) {
        is NoFollowInspection.Present -> result.value.isRegularFile && !result.value.isSymbolicLink()
        else -> false
    }

    fun resolveDirectChild(root: File, name: String, requireFile: Boolean = false): File? {
        return when (val result = resolveDirectChildResult(root, name, requireFile)) {
            NoFollowInspection.Absent -> null
            is NoFollowInspection.Present -> result.value
            is NoFollowInspection.InspectionFailed -> null
        }
    }

    @Deprecated("Use listResult() or requireDirectChildren()")
    fun list(root: File): List<File> {
        return when (val result = listResult(root)) {
            is NoFollowInspection.Present -> result.value
            NoFollowInspection.Absent -> emptyList()
            is NoFollowInspection.InspectionFailed -> emptyList()
        }
    }

    fun listResult(root: File): NoFollowInspection<List<File>> {
        val rootAttrs = when (val result = inspect(root.toPath())) {
            NoFollowInspection.Absent -> return NoFollowInspection.Absent
            is NoFollowInspection.InspectionFailed -> return result
            is NoFollowInspection.Present -> result.value
        }
        if (!rootAttrs.isDirectory || rootAttrs.isSymbolicLink()) {
            return NoFollowInspection.InspectionFailed(
                IllegalStateException("Expected a real directory: ${root.absolutePath}")
            )
        }
        val result = ArrayList<File>()
        fun visit(dir: Path, expected: BasicFileAttributes) {
            if (!revalidateTraversal(dir, expected)) {
                throw java.io.IOException("Directory changed during no-follow traversal: $dir")
            }
            val stream: DirectoryStream<Path> = try {
                Files.newDirectoryStream(dir)
            } catch (error: Exception) {
                throw error
            }
            if (!revalidateTraversal(dir, expected)) {
                stream.close()
                throw java.io.IOException("Directory changed after open: $dir")
            }
            stream.use { entries ->
                for (entry in entries) {
                    val attrs = when (val inspection = inspect(entry)) {
                        NoFollowInspection.Absent -> continue
                        is NoFollowInspection.InspectionFailed -> throw inspection.exception
                        is NoFollowInspection.Present -> inspection.value
                    }
                    if (attrs.isSymbolicLink()) continue
                    result += entry.toFile()
                    if (attrs.isDirectory) visit(entry, attrs)
                }
            }
        }
        return try {
            visit(root.toPath(), rootAttrs)
            NoFollowInspection.Present(result)
        } catch (error: Exception) {
            NoFollowInspection.InspectionFailed(error)
        }
    }

    @Deprecated("Use directChildrenResult() or requireDirectChildren()")
    fun directChildren(root: File): List<File> {
        return when (val result = directChildrenResult(root)) {
            is NoFollowInspection.Present -> result.value
            NoFollowInspection.Absent -> emptyList()
            is NoFollowInspection.InspectionFailed -> emptyList()
        }
    }

    fun directChildrenResult(root: File): NoFollowInspection<List<File>> {
        val rootAttrs = when (val inspection = inspect(root.toPath())) {
            NoFollowInspection.Absent -> return NoFollowInspection.Absent
            is NoFollowInspection.InspectionFailed -> return inspection
            is NoFollowInspection.Present -> inspection.value
        }
        if (!rootAttrs.isDirectory || rootAttrs.isSymbolicLink()) {
            return NoFollowInspection.InspectionFailed(
                IllegalStateException("Expected a real directory: ${root.absolutePath}")
            )
        }
        val result = ArrayList<File>()
        return try {
            val stream = Files.newDirectoryStream(root.toPath())
            if (!revalidateTraversal(root.toPath(), rootAttrs)) {
                stream.close()
                return NoFollowInspection.InspectionFailed(
                    java.io.IOException("Directory changed after open: ${root.absolutePath}")
                )
            }
            stream.use { entries ->
                for (entry in entries) {
                    when (val inspection = inspect(entry)) {
                        NoFollowInspection.Absent -> continue
                        is NoFollowInspection.InspectionFailed -> throw inspection.exception
                        is NoFollowInspection.Present -> if (!inspection.value.isSymbolicLink()) {
                            result += entry.toFile()
                        }
                    }
                }
            }
            NoFollowInspection.Present(result)
        } catch (error: Exception) {
            NoFollowInspection.InspectionFailed(error)
        }
    }

    fun requireDirectChildren(root: File): List<File> = when (val result = directChildrenResult(root)) {
        NoFollowInspection.Absent -> emptyList()
        is NoFollowInspection.Present -> result.value
        is NoFollowInspection.InspectionFailed -> throw result.exception
    }

    fun requireSize(root: File): Long = when (val result = sizeResult(root)) {
        NoFollowInspection.Absent -> 0L
        is NoFollowInspection.Present -> result.value
        is NoFollowInspection.InspectionFailed -> throw result.exception
    }

    fun requireDirectChildFile(root: File, name: String): File = when (
        val result = resolveDirectChildResult(root, name, requireFile = true)
    ) {
        NoFollowInspection.Absent -> error("Required file is absent: $name")
        is NoFollowInspection.Present -> result.value
        is NoFollowInspection.InspectionFailed -> throw result.exception
    }

    fun optionalDirectChildFile(root: File, name: String): File? = when (
        val result = resolveDirectChildResult(root, name, requireFile = true)
    ) {
        NoFollowInspection.Absent -> null
        is NoFollowInspection.Present -> result.value
        is NoFollowInspection.InspectionFailed -> throw result.exception
    }

    @Deprecated("Use sizeResult() or requireSize()")
    fun size(root: File): Long {
        return when (val result = sizeResult(root)) {
            is NoFollowInspection.Present -> result.value
            NoFollowInspection.Absent -> 0L
            is NoFollowInspection.InspectionFailed -> 0L
        }
    }

    fun sizeResult(root: File): NoFollowInspection<Long> {
        when (val inspection = inspect(root.toPath())) {
            NoFollowInspection.Absent -> return NoFollowInspection.Absent
            is NoFollowInspection.InspectionFailed -> return inspection
            is NoFollowInspection.Present -> {
                val attrs = inspection.value
                if (attrs.isSymbolicLink()) {
                    return NoFollowInspection.InspectionFailed(
                        IllegalStateException("Refusing to inspect a symbolic link: ${root.absolutePath}")
                    )
                }
                if (attrs.isRegularFile) return NoFollowInspection.Present(attrs.size())
                if (!attrs.isDirectory) {
                    return NoFollowInspection.InspectionFailed(
                        IllegalStateException("Expected a regular file or directory: ${root.absolutePath}")
                    )
                }
            }
        }
        val entries = when (val listed = listResult(root)) {
            NoFollowInspection.Absent -> return NoFollowInspection.Absent
            is NoFollowInspection.InspectionFailed -> return listed
            is NoFollowInspection.Present -> listed.value
        }
        var total = 0L
        for (file in entries) {
            val attrs = when (val inspection = inspect(file.toPath())) {
                NoFollowInspection.Absent -> continue
                is NoFollowInspection.InspectionFailed -> return inspection
                is NoFollowInspection.Present -> inspection.value
            }
            if (attrs.isRegularFile && !attrs.isSymbolicLink()) {
                total = if (Long.MAX_VALUE - total < attrs.size()) Long.MAX_VALUE else total + attrs.size()
            }
        }
        return NoFollowInspection.Present(total)
    }

    fun deleteTree(root: File): Pair<CleanupStatus, List<String>> {
        val rootPath = root.toPath()
        val rootAttrs = when (val inspection = inspect(rootPath)) {
            NoFollowInspection.Absent -> return CleanupStatus.COMPLETE to emptyList()
            is NoFollowInspection.InspectionFailed -> {
                return CleanupStatus.FAILED to listOf(root.absolutePath)
            }
            is NoFollowInspection.Present -> inspection.value
        }
        if (rootAttrs.isSymbolicLink()) return CleanupStatus.FAILED to listOf(root.absolutePath)
        val failures = ArrayList<String>()
        fun remove(path: Path) {
            val attrs = (inspect(path) as? NoFollowInspection.Present)?.value
            if (attrs == null || attrs.isSymbolicLink()) {
                failures += path.toString()
                return
            }
            if (attrs.isDirectory) {
                val stream = try { Files.newDirectoryStream(path) } catch (_: Exception) {
                    failures += path.toString(); return
                }
                if (!revalidateTraversal(path, attrs)) {
                    stream.close()
                    failures += path.toString()
                    return
                }
                stream.use { entries ->
                    for (entry in entries) {
                        if (!revalidateTraversal(path, attrs)) {
                            failures += path.toString()
                            return
                        }
                        remove(entry)
                    }
                }
            }
            if (!revalidateTraversal(path, attrs) ||
                !runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
            ) failures += path.toString()
        }
        remove(rootPath)
        val status = when {
            failures.isEmpty() -> CleanupStatus.COMPLETE
            inspect(rootPath) is NoFollowInspection.Absent -> CleanupStatus.PARTIAL
            else -> CleanupStatus.FAILED
        }
        return status to failures
    }
}

internal fun isDngTiffHeader(prefix: ByteArray): Boolean = prefix.size >= 4 && (
    prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(0x49, 0x49, 0x2a, 0)) ||
        prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4d, 0x4d, 0, 0x2a))
    )
