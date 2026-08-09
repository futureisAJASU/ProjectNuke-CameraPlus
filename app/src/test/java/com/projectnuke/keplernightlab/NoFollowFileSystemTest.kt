package com.projectnuke.keplernightlab

import java.nio.file.Files
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun statHelperDoesNotClaimStableIdentityWhenFileKeysAreAbsent() {
        // This legacy helper is only stat equality. Correctness-sensitive
        // callers use StableFileIdentity, which carries a content digest.
        assertTrue(noFollowIdentityMatches(null, null, 64L, 64L, 500L, 500L))
    }

    @Test
    fun stableIdentityRejectsSameSizeSameMtimeReplacementWithoutFileKey() {
        val root = createTempDirectory("kepler-nofollow-token").toFile()
        try {
            val file = root.resolve("payload.raw16").apply { writeText("AAAA") }
            val baseline = NoFollowFileSystem.stableIdentity(file)
            file.writeText("BBBB")
            val nullKeyToken = baseline.copy(fileKey = null, descriptorIdentity = null)

            assertFalse(NoFollowFileSystem.revalidate(file.toPath(), nullKeyToken))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun swapBackPathFenceRejectsDifferentBytesEvenWhenKeysMatch() {
        assertFalse(
            noFollowReadFenceAccepts(
                beforeFileKey = "A",
                afterFileKey = "A",
                beforeSize = 4L,
                afterSize = 4L,
                beforeModifiedMillis = 10L,
                afterModifiedMillis = 10L,
                openedSha256 = "content-from-B",
                finalPathSha256 = "content-from-A"
            )
        )
    }

    @Test
    fun stableReadFenceAcceptsMatchingContentWhenObjectKeyUnavailable() {
        assertTrue(
            noFollowReadFenceAccepts(
                beforeFileKey = null,
                afterFileKey = null,
                beforeSize = 4L,
                afterSize = 4L,
                beforeModifiedMillis = 10L,
                afterModifiedMillis = 10L,
                openedSha256 = "same-content",
                finalPathSha256 = "same-content"
            )
        )
    }

    @Test
    fun stableIdentityStrengthDoesNotDowngradeObjectToken() {
        val root = createTempDirectory("kepler-nofollow-strength").toFile()
        try {
            val file = root.resolve("payload").apply { writeText("same") }
            val expected = NoFollowFileSystem.stableIdentity(file).copy(
                fileKey = "A",
                descriptorIdentity = "descriptor-A",
                strength = NoFollowFileSystem.StableIdentityStrength.OBJECT_IDENTITY
            )
            assertFalse(NoFollowFileSystem.revalidate(file.toPath(), expected))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun providerNeutralStreamNeverMintsObjectIdentityFromUncorrelatedProbe() {
        val root = createTempDirectory("kepler-nofollow-content-strength").toFile()
        try {
            val token = NoFollowFileSystem.stableIdentity(root.resolve("payload").apply { writeText("bytes") })
            assertEquals(NoFollowFileSystem.StableIdentityStrength.CONTENT_IDENTITY, token.strength)
        } finally {
            root.deleteRecursively()
        }
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
    fun readTextVerifiedReadsUtf8AndRejectsMissingTarget() {
        val root = createTempDirectory("kepler-nofollow-text").toFile()
        try {
            val file = root.resolve("job.json").apply { writeText("{\"a\":1}\n안녕") }
            assertEquals("{\"a\":1}\n안녕", NoFollowFileSystem.readTextVerified(file))
            assertThrows(java.io.FileNotFoundException::class.java) {
                NoFollowFileSystem.readTextVerified(root.resolve("missing.json"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readLinesVerifiedMatchesFileReadLinesSemantics() {
        val root = createTempDirectory("kepler-nofollow-lines").toFile()
        try {
            val trailing = root.resolve("marker").apply {
                writeText("transactionId=tx1\nbackupRoot=.reprocess_backup_tx1\ncreatedAt=42\n")
            }
            val parsed = NoFollowFileSystem.readLinesVerified(trailing)
            assertEquals(3, parsed.size)
            assertEquals("transactionId=tx1", parsed[0])
            assertEquals("createdAt=42", parsed[2])
            val crlf = root.resolve("crlf").apply { writeText("a\r\nb\r\n") }
            assertEquals(listOf("a", "b"), NoFollowFileSystem.readLinesVerified(crlf))
            val legacy = root.resolve("legacy").apply { writeText("quarantined\n") }
            assertEquals(listOf("quarantined"), NoFollowFileSystem.readLinesVerified(legacy))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readTextVerifiedRejectsDirectoryTarget() {
        val root = createTempDirectory("kepler-nofollow-text-dir").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                NoFollowFileSystem.readTextVerified(root)
            }
        } finally {
            root.deleteRecursively()
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
