package com.projectnuke.keplernightlab

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NoFollowFileSystemTest {
    @Test
    fun nullFileKeysUseStableDescriptorFence() {
        assertTrue(noFollowIdentityMatches(null, null, 12L, 12L, 99L, 99L))
        assertFalse(noFollowIdentityMatches(null, null, 12L, 13L, 99L, 99L))
    }
    @Test
    fun childSymlinkIsNotListedOrCounted() {
        val root = createTempDirectory("kepler-nofollow").toFile()
        try {
            val outside = createTempDirectory("kepler-outside").toFile()
            try {
                val target = outside.resolve("payload.bin").apply { writeText("outside") }
                val link = root.resolve("payload.bin").toPath()
                assumeTrue(runCatching { Files.createSymbolicLink(link, target.toPath()) }.isSuccess)
                assertTrue(NoFollowFileSystem.list(root).none { it.name == "payload.bin" })
                assertTrue(NoFollowFileSystem.size(root) == 0L)
                assertFalse(NoFollowFileSystem.resolveDirectChild(root, "payload.bin", requireFile = true) != null)
            } finally {
                outside.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symlinkRootIsRejected() {
        val parent = createTempDirectory("kepler-nofollow-root").toFile()
        val outside = createTempDirectory("kepler-nofollow-target").toFile()
        try {
            val link = parent.resolve("job").toPath()
            assumeTrue(runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess)
            assertFalse(NoFollowFileSystem.isRealDirectory(link))
            assertTrue(NoFollowFileSystem.list(link.toFile()).isEmpty())
        } finally {
            parent.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun inspectedDirectoryReplacementIsRejectedBeforeMutation() {
        val parent = createTempDirectory("kepler-nofollow-race").toFile()
        val outside = createTempDirectory("kepler-nofollow-race-target").toFile()
        try {
            val child = parent.resolve("job").apply { mkdirs() }
            val inspected = (NoFollowFileSystem.inspect(child.toPath()) as? NoFollowInspection.Present)
                ?.value ?: return
            child.delete()
            val link = parent.resolve("job").toPath()
            assumeTrue(runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess)
            assertFalse(NoFollowFileSystem.revalidate(link, inspected))
        } finally {
            parent.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun jobJsonSymlinkIsInspectionFailure() {
        val parent = createTempDirectory("kepler-nofollow-manifest").toFile()
        val outside = createTempDirectory("kepler-nofollow-manifest-target").toFile()
        try {
            parent.resolve("job").mkdirs()
            val target = outside.resolve("job.json").apply { writeText("{}") }
            val link = parent.resolve("job").resolve("job.json").toPath()
            assumeTrue(runCatching { Files.createSymbolicLink(link, target.toPath()) }.isSuccess)
            assertTrue(
                NoFollowFileSystem.resolveDirectChildResult(parent.resolve("job"), "job.json", true)
                    is NoFollowInspection.InspectionFailed
            )
        } finally {
            parent.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
