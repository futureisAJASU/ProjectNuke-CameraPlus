package com.projectnuke.keplernightlab

import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal object NoFollowFileSystem {
    fun attributes(path: Path): BasicFileAttributes? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

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
        if (!isRealDirectory(root.toPath())) return emptyList()
        val result = ArrayList<File>()
        fun visit(dir: Path) {
            val stream: DirectoryStream<Path> = try { Files.newDirectoryStream(dir) } catch (_: Exception) { return }
            stream.use { entries ->
                for (entry in entries) {
                    val attrs = attributes(entry) ?: continue
                    if (attrs.isSymbolicLink()) continue
                    result += entry.toFile()
                    if (attrs.isDirectory) visit(entry)
                }
            }
        }
        visit(root.toPath())
        return result
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
        if (isRealFile(root.toPath())) return attributes(root.toPath())?.size() ?: 0L
        if (!isRealDirectory(root.toPath())) return 0L
        var total = 0L
        for (file in list(root)) {
            val attrs = attributes(file.toPath()) ?: continue
            if (attrs.isRegularFile && !attrs.isSymbolicLink()) {
                total = if (Long.MAX_VALUE - total < attrs.size()) Long.MAX_VALUE else total + attrs.size()
            }
        }
        return total
    }

    fun deleteTree(root: File): Pair<CleanupStatus, List<String>> {
        val rootPath = root.toPath()
        val rootAttrs = attributes(rootPath) ?: return CleanupStatus.COMPLETE to emptyList()
        if (rootAttrs.isSymbolicLink()) return CleanupStatus.FAILED to listOf(root.absolutePath)
        val failures = ArrayList<String>()
        fun remove(path: Path) {
            val attrs = attributes(path)
            if (attrs == null || attrs.isSymbolicLink()) {
                failures += path.toString()
                return
            }
            if (attrs.isDirectory) {
                val stream = try { Files.newDirectoryStream(path) } catch (_: Exception) {
                    failures += path.toString(); return
                }
                stream.use { entries -> entries.forEach(::remove) }
            }
            if (!runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) failures += path.toString()
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
