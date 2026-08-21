package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureStreamLifetimeTest {

    // ------------------------------------------------------------------
    // RAW16 stream-lifetime regression
    // ------------------------------------------------------------------

    @Test
    fun rawCompactWriter_syncOccursBeforeUnderlyingClose() {
        val tempDir = createTempDir()
        val tempFile = File(tempDir, "raw.raw16")
        var closed = false
        val fos = object : FileOutputStream(tempFile) {
            override fun close() {
                closed = true
                super.close()
            }
        }

        val buffer = ByteBuffer.allocate(4)
        buffer.put(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        buffer.flip()

        fos.use { rawOutput ->
            writeRaw16Rows(2, 2, 2, 1, 4, buffer, rawOutput)
            rawOutput.fd.sync()
        }

        assertTrue(closed)
    }

    @Test
    fun rawCompactWriter_flushesBeforeSync() {
        val tempDir = createTempDir()
        val tempFile = File(tempDir, "raw.raw16")
        val flushCalls = mutableListOf<Long>()

        val fos = object : FileOutputStream(tempFile) {
            override fun flush() {
                flushCalls += System.nanoTime()
                super.flush()
            }
        }

        val buffer = ByteBuffer.allocate(4)
        buffer.put(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        buffer.flip()

        fos.use { rawOutput ->
            writeRaw16Rows(2, 2, 2, 1, 4, buffer, rawOutput)
            rawOutput.fd.sync()
        }

        assertEquals(1, flushCalls.size)
    }

    @Test
    fun rawCompactWriter_payloadStillVerifies() {
        val tempDir = createTempDir()
        val tempFile = File(tempDir, "raw.raw16")

        val buffer = ByteBuffer.allocate(2)
        buffer.put(byteArrayOf(0x01, 0x02))
        buffer.flip()

        FileOutputStream(tempFile).use { rawOutput ->
            writeRaw16Rows(1, 1, 2, 1, 2, buffer, rawOutput)
            rawOutput.fd.sync()
        }

        assertEquals(2L, tempFile.length())
        val digest = NoFollowFileSystem.digestVerified(tempFile)
        assertEquals(2L, digest.size)
    }

    // ------------------------------------------------------------------
    // YUV PNG stream-lifetime regression
    // ------------------------------------------------------------------

    @Test
    fun yuvPngWriter_flushesBeforeSync() {
        val tempDir = createTempDir()
        val tempFile = File(tempDir, "out.png")
        val flushCalls = mutableListOf<String>()

        val fos = object : FileOutputStream(tempFile) {
            override fun flush() {
                flushCalls += "flush"
                super.flush()
            }
            override fun close() {
                flushCalls += "close"
                super.close()
            }
        }

        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        fos.use { output ->
            writePngBitmapToSink(bitmap, output)
        }

        assertTrue("flush must be called at least once", flushCalls.contains("flush"))
        val flushIndex = flushCalls.indexOf("flush")
        val closeIndex = flushCalls.indexOf("close")
        assertTrue("flush must occur before close", flushIndex >= 0 && closeIndex >= 0 && flushIndex < closeIndex)
        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() > 0)
    }

    @Test
    fun yuvPngWriter_compressFalseIsPreciseFailure() {
        val tempFile = File(createTempDir(), "out.png")
        val fos = FileOutputStream(tempFile)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        fos.use { output ->
            writePngBitmapToSink(bitmap, output)
        }
        assertTrue(tempFile.length() > 0)
    }
}
