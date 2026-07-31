package com.projectnuke.keplernightlab

import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal sealed interface NoFollowInspection<out T> {
    data object Absent : NoFollowInspection<Nothing>
    data class Present<T>(val value: T) : NoFollowInspection<T>
    data class InspectionFailed(val exception: Exception) : NoFollowInspection<Nothing>
}

internal object NoFollowFileSystem {
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

    fun revalidate(path: Path, expected: BasicFileAttributes): Boolean = when (val current = inspect(path)) {
        NoFollowInspection.Absent -> false
        is NoFollowInspection.InspectionFailed -> false
        is NoFollowInspection.Present -> {
            val actual = current.value
            !actual.isSymbolicLink() && actual.isDirectory == expected.isDirectory &&
                (expected.fileKey() == null || expected.fileKey() == actual.fileKey())
        }
    }

    fun resolveDirectChildResult(
        root: File,
        name: String,
        requireFile: Boolean = false
    ): NoFollowInspection<File> {
        if (!isRealDirectory(root.toPath()) || name.isBlank() || name != name.trim() ||
            name == "." || name == ".." || name.contains('/') || name.contains('\\')
        ) return NoFollowInspection.InspectionFailed(
            IllegalArgumentException("Unsafe direct-child path")
        )
        val path = root.toPath().resolve(name).normalize()
        if (path.parent != root.toPath()) {
            return NoFollowInspection.InspectionFailed(IllegalArgumentException("Path escapes root"))
        }
        return when (val result = inspect(path)) {
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
    }

    fun isRealDirectory(path: Path): Boolean = attributes(path)?.let {
        it.isDirectory && !it.isSymbolicLink()
    } == true

    fun isRealFile(path: Path): Boolean = attributes(path)?.let {
        it.isRegularFile && !it.isSymbolicLink()
    } == true

    fun resolveDirectChild(root: File, name: String, requireFile: Boolean = false): File? {
        if (!isRealDirectory(root.toPath()) || name.isBlank() || name != name.trim()) return null
        if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) return null
        val path = root.toPath().resolve(name).normalize()
        if (path.parent != root.toPath()) return null
        val attrs = attributes(path) ?: return null
        if (attrs.isSymbolicLink()) return null
        if (requireFile && !attrs.isRegularFile) return null
        return path.toFile()
    }

    fun list(root: File): List<File> {
        return when (val result = listResult(root)) {
            is NoFollowInspection.Present -> result.value
            else -> emptyList()
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
            if (!revalidate(dir, expected)) {
                throw java.io.IOException("Directory changed during no-follow traversal: $dir")
            }
            val stream: DirectoryStream<Path> = try {
                Files.newDirectoryStream(dir)
            } catch (error: Exception) {
                throw error
            }
            if (!revalidate(dir, expected)) {
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

    fun directChildren(root: File): List<File> {
        if (!isRealDirectory(root.toPath())) return emptyList()
        val result = ArrayList<File>()
        val stream = runCatching { Files.newDirectoryStream(root.toPath()) }.getOrNull() ?: return emptyList()
        stream.use { entries ->
            for (entry in entries) {
                val attrs = attributes(entry) ?: continue
                if (!attrs.isSymbolicLink()) result += entry.toFile()
            }
        }
        return result
    }

    fun size(root: File): Long {
        return when (val result = sizeResult(root)) {
            is NoFollowInspection.Present -> result.value
            else -> 0L
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
                if (!revalidate(path, attrs)) {
                    stream.close()
                    failures += path.toString()
                    return
                }
                stream.use { entries -> entries.forEach(::remove) }
            }
            if (!revalidate(path, attrs) ||
                !runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
            ) failures += path.toString()
        }
        remove(rootPath)
        val status = when {
            failures.isEmpty() -> CleanupStatus.COMPLETE
            !Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS) -> CleanupStatus.PARTIAL
            else -> CleanupStatus.FAILED
        }
        return status to failures
    }
}
