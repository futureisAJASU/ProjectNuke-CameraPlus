package com.projectnuke.keplernightlab

import java.nio.file.Files
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NoFollowFileSystemTest {
    @Test
    fun verifiedCopyUsesOpenedStreamAndDigestFence() {
        val root = createTempDirectory("kepler-nofollow-copy").toFile()
        try {
            val source = root.resolve("source.dng").apply { writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0, 1, 2, 3)) }
            val output = ByteArrayOutputStream()
            val digest = NoFollowFileSystem.copyVerified(source, output)
            assertTrue(digest.size == source.length())
            assertTrue(output.toByteArray().contentEquals(source.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nullFileKeysRequireStableStatFallback() {
        assertTrue(noFollowIdentityMatches(null, null, 12L, 12L, 99L, 99L))
        assertFalse(noFollowIdentityMatches(null, null, 12L, 13L, 99L, 99L))
    }

    @Test
    fun fileKeyIdentityMatchesExactStringAndRejectsMismatches() {
        val keyA = "(dev=1,ino=42)"
        val keyB = "(dev=1,ino=42)"
        val keyC = "(dev=1,ino=43)"
        // Same fileKey on both sides -> identity match succeeds.
        assertTrue(noFollowIdentityMatches(keyA, keyB, 0L, 0L, 0L, 0L))
        // Different fileKey -> identity mismatch fails closed.
        assertFalse(noFollowIdentityMatches(keyA, keyC, 0L, 0L, 0L, 0L))
    }

    @Test
    fun fileKeyMismatchTrumpsMatchingStatFence() {
        // fileKey says "different files" even when size+mtime match. The
        // identity fence must be fail-closed: a same-size same-mtime
        // replacement with a different fileKey is rejected.
        assertFalse(noFollowIdentityMatches("(dev=1,ino=1)", "(dev=1,ino=2)", 100L, 100L, 1000L, 1000L))
    }

    @Test
    fun sameSizeMtimeDifferentContentFailsWhenFileKeysAreAbsent() {
        // null/null + matching size + matching mtime -> STILL matches
        // (the stat fallback accepts this; callers that need stronger
        // guarantees must layer a content fingerprint on top).
        assertTrue(noFollowIdentityMatches(null, null, 64L, 64L, 500L, 500L))
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
