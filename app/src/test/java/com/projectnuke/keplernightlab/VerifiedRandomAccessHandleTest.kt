package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
