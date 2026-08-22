package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bounds-only artifact verifier contract. With `inJustDecodeBounds = true`,
 * BitmapFactory never allocates a Bitmap, so a null decode result is expected
 * and must never be treated as verification failure. The signature fence plus
 * positive parsed dimensions carry the verification instead.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessingArtifactVerifierTest {

    private fun newTempDir(): File = createTempDirectory("kepler-artifact-verifier").toFile()

    private fun writeCompressedPng(dir: File, name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val file = File(dir, name)
        FileOutputStream(file).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.flush()
            output.fd.sync()
        }
        bitmap.recycle()
        return file
    }

    private fun writeCompressedJpeg(dir: File, name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF996633.toInt())
        val file = File(dir, name)
        FileOutputStream(file).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.flush()
            output.fd.sync()
        }
        bitmap.recycle()
        return file
    }

    @Test
    fun verifyPngArtifact_acceptsValidBoundsOnlyDecode() {
        val dir = newTempDir()
        try {
            val png = writeCompressedPng(dir, "frame.png", 37, 23)
            verifyPngArtifact(png)
            verifyPngArtifact(png, expectedWidth = 37, expectedHeight = 23)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyPngArtifact_rejectsInvalidSignature() {
        val dir = newTempDir()
        try {
            val fake = File(dir, "frame.png").apply {
                writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
            }
            val failure = assertThrows(IllegalStateException::class.java) {
                verifyPngArtifact(fake)
            }
            assertTrue(failure.message!!.contains("Invalid PNG artifact"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyPngArtifact_rejectsInvalidDimensionsOrCorruptInput() {
        val dir = newTempDir()
        try {
            // Valid PNG signature followed by garbage: bounds parsing cannot
            // recover IHDR dimensions, so verification must fail. A device
            // BitmapFactory leaves outWidth/outHeight at 0 (dimension gate);
            // the Robolectric JVM decoder surfaces the same corruption as a
            // decode exception. Both outcomes prove rejection.
            val corrupt = File(dir, "corrupt.png")
            corrupt.writeBytes(
                PNG_ARTIFACT_SIGNATURE + byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70)
            )
            val failure = assertThrows(Exception::class.java) {
                verifyPngArtifact(corrupt)
            }
            assertTrue(
                failure.message?.contains("PNG dimensions are invalid") == true ||
                    failure !is IllegalStateException
            )

            // Empty payload with valid signature must fail the same way.
            val empty = File(dir, "empty.png").apply { writeBytes(PNG_ARTIFACT_SIGNATURE) }
            assertThrows(Exception::class.java) { verifyPngArtifact(empty) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyPngArtifact_rejectsExpectedWidthMismatch() {
        val dir = newTempDir()
        try {
            val png = writeCompressedPng(dir, "frame.png", 31, 17)
            val failure = assertThrows(IllegalStateException::class.java) {
                verifyPngArtifact(png, expectedWidth = 32)
            }
            assertTrue(failure.message == "PNG width mismatch")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyPngArtifact_rejectsExpectedHeightMismatch() {
        val dir = newTempDir()
        try {
            val png = writeCompressedPng(dir, "frame.png", 29, 19)
            val failure = assertThrows(IllegalStateException::class.java) {
                verifyPngArtifact(png, expectedHeight = 18)
            }
            assertTrue(failure.message == "PNG height mismatch")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyJpegArtifact_acceptsValidBoundsOnlyDecode() {
        val dir = newTempDir()
        try {
            val jpeg = writeCompressedJpeg(dir, "final.jpg", 41, 13)
            verifyJpegArtifact(jpeg)
            verifyJpegArtifact(jpeg, expectedWidth = 41, expectedHeight = 13)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyJpegArtifact_rejectsInvalidSignature() {
        val dir = newTempDir()
        try {
            val fake = File(dir, "final.jpg").apply {
                writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44))
            }
            val failure = assertThrows(IllegalStateException::class.java) {
                verifyJpegArtifact(fake)
            }
            assertTrue(failure.message!!.contains("Invalid JPEG artifact"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyJpegArtifact_rejectsDimensionMismatch() {
        val dir = newTempDir()
        try {
            val jpeg = writeCompressedJpeg(dir, "final.jpg", 53, 27)
            assertThrows(IllegalStateException::class.java) {
                verifyJpegArtifact(jpeg, expectedWidth = 54)
            }
            assertThrows(IllegalStateException::class.java) {
                verifyJpegArtifact(jpeg, expectedHeight = 26)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyJpegArtifact_rejectsCorruptInputAfterSignature() {
        val dir = newTempDir()
        try {
            val corrupt = File(dir, "corrupt.jpg")
            corrupt.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01, 0x02))
            val failure = assertThrows(Exception::class.java) {
                verifyJpegArtifact(corrupt)
            }
            assertTrue(
                failure.message?.contains("JPEG dimensions are invalid") == true ||
                    failure !is IllegalStateException
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun processingArtifactTransaction_validPngPassesTempVerifyDespiteNullBoundsDecode() {
        val dir = newTempDir()
        try {
            val finalFile = File(dir, "result.png")
            val result = commitProcessingArtifact(
                finalFile = finalFile,
                writeTemp = { temp ->
                    val bitmap = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(0xFF112233.toInt())
                    FileOutputStream(temp).use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        output.flush()
                        output.fd.sync()
                    }
                    bitmap.recycle()
                },
                verifyFinal = { committed -> verifyPngArtifact(committed) }
            )
            assertTrue(result.state == ProcessingArtifactState.ADOPTED)
            assertTrue(finalFile.isFile && finalFile.length() > 0L)
            assertTrue(result.settlements.none { it.failure != null })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun processingArtifactTransaction_corruptPngStillFailsAtTempVerify() {
        val dir = newTempDir()
        try {
            val finalFile = File(dir, "result.png")
            val failure = assertThrows(ProcessingArtifactException::class.java) {
                commitProcessingArtifact(
                    finalFile = finalFile,
                writeTemp = { temp ->
                    FileOutputStream(temp).use { output ->
                        output.write(PNG_ARTIFACT_SIGNATURE + byteArrayOf(0x0A, 0x0B, 0x0C))
                        output.flush()
                        output.fd.sync()
                    }
                },
                    verifyFinal = { committed -> verifyPngArtifact(committed) }
                )
            }
            assertTrue(failure.failurePoint == ProcessingArtifactFailurePoint.TEMP_VERIFY)
            assertTrue(!finalFile.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
