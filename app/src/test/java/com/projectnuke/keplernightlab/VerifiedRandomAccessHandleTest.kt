package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class VerifiedRandomAccessHandleTest {
    @Test
    fun openedHandleVerifiesTheBytesItsDescriptorSupplies() {
        val dir = Files.createTempDirectory("verified-raw-handle").toFile()
        try {
            val file = dir.resolve("frame.raw16").apply { writeBytes(ByteArray(32) { it.toByte() }) }
            val handle = VerifiedRandomAccessHandle.open(file, 32L)
            try {
                handle.randomAccess.seek(0L)
                assertNotNull(handle.randomAccess.read())
                handle.verifyPathStillMatches()
            } finally {
                assertNull(handle.close())
                assertNull(handle.close())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun wrongExpectedSizeIsRejectedBeforeProcessing() {
        val dir = Files.createTempDirectory("verified-raw-size").toFile()
        try {
            val file = dir.resolve("frame.raw16").apply { writeBytes(ByteArray(16)) }
            VerifiedRandomAccessHandle.open(file, 32L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun successfulReadSurfacesDescriptorCloseFailure() {
        val dir = Files.createTempDirectory("verified-raw-close-failure").toFile()
        try {
            val file = dir.resolve("frame.raw16").apply { writeBytes(ByteArray(32)) }
            val closeFailure = IllegalStateException("descriptor close failed")
            val handle = VerifiedRandomAccessHandle.openForTesting(file, 32L, closeFailure)
            try {
                handle.use { input -> assertNotNull(input.read()) }
                fail("close failure was not observable")
            } catch (failure: Throwable) {
                assertSame(closeFailure, failure)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationRemainsPrimaryWhenDescriptorCloseAlsoFails() {
        val dir = Files.createTempDirectory("verified-raw-cancel-close-failure").toFile()
        try {
            val file = dir.resolve("frame.raw16").apply { writeBytes(ByteArray(32)) }
            val closeFailure = IllegalStateException("descriptor close failed")
            val cancellation = CancellationException("cancelled")
            val handle = VerifiedRandomAccessHandle.openForTesting(file, 32L, closeFailure)
            try {
                handle.use { throw cancellation }
                fail("cancellation was not propagated")
            } catch (failure: Throwable) {
                assertSame(cancellation, failure)
                assertTrue(cancellation.suppressed.any { it === closeFailure })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verificationFailureRemainsPrimaryWhenDescriptorCloseAlsoFails() {
        val dir = Files.createTempDirectory("verified-raw-verify-close-failure").toFile()
        try {
            val file = dir.resolve("frame.raw16").apply { writeBytes(ByteArray(32)) }
            val closeFailure = IllegalStateException("descriptor close failed")
            val handle = VerifiedRandomAccessHandle.openForTesting(file, 32L, closeFailure)
            val verificationFailure = IllegalStateException("stable-input verification failed")
            try {
                handle.use {
                    file.writeBytes(ByteArray(32) { 1 })
                    try {
                        handle.verifyPathStillMatches()
                    } catch (failure: IllegalStateException) {
                        throw verificationFailure
                    }
                }
                fail("verification failure was not propagated")
            } catch (failure: Throwable) {
                assertSame(verificationFailure, failure)
                assertTrue(verificationFailure.suppressed.any { it === closeFailure })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalDescriptorCloseFailureIsNotReducedToCleanupDebt() {
        val dir = Files.createTempDirectory("verified-raw-fatal-close").toFile()
        try {
            val file = dir.resolve("frame.raw16").apply { writeBytes(ByteArray(32)) }
            val fatal = AssertionError("fatal descriptor close")
            val handle = VerifiedRandomAccessHandle.openForTesting(file, 32L, fatal)
            try {
                handle.use { assertNotNull(it.read()) }
                fail("fatal close failure was not propagated")
            } catch (failure: AssertionError) {
                assertSame(fatal, failure)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalCloseSupersedesOrdinaryPrimaryButRetainsItSuppressed() {
        val dir = Files.createTempDirectory("verified-raw-fatal-close-primary").toFile()
        try {
            val file = dir.resolve("frame.raw16").apply { writeBytes(ByteArray(32)) }
            val fatal = AssertionError("fatal descriptor close")
            val primary = IllegalStateException("ordinary processing failure")
            val handle = VerifiedRandomAccessHandle.openForTesting(file, 32L, fatal)
            try {
                handle.use { throw primary }
                fail("fatal close failure was not propagated")
            } catch (failure: AssertionError) {
                assertSame(fatal, failure)
                assertTrue(fatal.suppressed.any { it === primary })
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
