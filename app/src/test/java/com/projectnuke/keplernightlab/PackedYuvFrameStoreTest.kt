package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 6: packed-YUV durable source representation.  The foreground
 * bottleneck (pure-Kotlin YUV->RGB conversion + 12MP PNG compression) is
 * replaced by a lossless sequential plane write with fsync and structural
 * size/digest verification.  These tests prove exact byte fidelity, stride
 * handling, metadata preservation, corruption/truncation detection, format
 * versioning, and bounded structural verification.
 */
@RunWith(RobolectricTestRunner::class)
class PackedYuvFrameStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun frame(
        width: Int = 8,
        height: Int = 6,
        yRowStride: Int = width,
        uRowStride: Int = width / 2,
        vRowStride: Int = width / 2,
        index: Int = 2,
        timestampNs: Long = 1234L
    ): BufferedYuvFrame {
        val ySize = yRowStride * height
        val uvSize = uRowStride * height / 2
        return BufferedYuvFrame(
            index = index,
            timestampNs = timestampNs,
            width = width,
            height = height,
            y = ByteArray(ySize) { it.toByte() },
            u = ByteArray(uvSize) { (it * 3).toByte() },
            v = ByteArray(uvSize) { (it * 7).toByte() },
            yRowStride = yRowStride,
            yPixelStride = 1,
            uRowStride = uRowStride,
            uPixelStride = 1,
            vRowStride = vRowStride,
            vPixelStride = 1
        )
    }

    @Test
    fun packThenUnpack_roundTripsPlanesByteForByte() {
        val dir = tmp.newFolder()
        val original = frame(yRowStride = 10, uRowStride = 5, vRowStride = 5)
        val out = java.io.File(dir, "frame_02_color.yuvpack")

        PackedYuvFrameStore.pack(original, rotationDegrees = 270, outFile = out)

        assertTrue(out.isFile && out.length() > 0L)
        val restored = PackedYuvFrameStore.unpack(out)
        assertTrue(original.y.contentEquals(restored.y))
        assertTrue(original.u.contentEquals(restored.u))
        assertTrue(original.v.contentEquals(restored.v))
        assertEquals(original.width, restored.width)
        assertEquals(original.height, restored.height)
        assertEquals(original.index, restored.frameIndex)
        assertEquals(original.timestampNs, restored.timestampNs)
        assertEquals(270, restored.rotationDegrees)
        assertEquals(original.yRowStride, restored.header.yRowStride)
        assertEquals(original.uPixelStride, restored.header.uPixelStride)
    }

    @Test
    fun packedFile_isSelfDescribingAndVersioned() {
        val dir = tmp.newFolder()
        val out = java.io.File(dir, "frame_00.yuvpack")
        PackedYuvFrameStore.pack(frame(index = 0), rotationDegrees = 0, outFile = out)

        val header = PackedYuvFrameStore.readHeader(out)
        assertEquals(8, header.width)
        assertEquals(6, header.height)
        assertEquals(8L * 6L + (4L * 3L) * 2L, header.payloadLength)

        val bytes = out.readBytes()
        assertEquals("KPYN", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(PackedYuvFrameStore.VERSION, bytes[4].toInt())

        // A future version must be rejected fail-closed.
        bytes[4] = (PackedYuvFrameStore.VERSION + 1).toByte()
        val mutated = java.io.File(dir, "future.yuvpack")
        mutated.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { PackedYuvFrameStore.readHeader(mutated) }
    }

    /** Any failure mode (digest/length/EOF) must be fail-closed; type is not contractual. */
    private fun expectUnpackFailure(file: java.io.File) {
        try {
            PackedYuvFrameStore.unpack(file)
        } catch (_: Exception) {
            // expected: digest mismatch, truncation, EOF, or malformed header
            return
        }
        throw AssertionError("expected unpack failure for ${file.name}")
    }

    @Test
    fun payloadCorruption_isDetectedByDigest() {
        val dir = tmp.newFolder()
        val out = java.io.File(dir, "frame_01.yuvpack")
        PackedYuvFrameStore.pack(frame(index = 1), rotationDegrees = 90, outFile = out)

        val bytes = out.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        out.writeBytes(bytes)

        expectUnpackFailure(out)
    }

    @Test
    fun truncation_isDetectedByStructuralLengthCheck() {
        val dir = tmp.newFolder()
        val out = java.io.File(dir, "frame_03.yuvpack")
        PackedYuvFrameStore.pack(frame(index = 3), rotationDegrees = 0, outFile = out)

        // Simulate process death mid-write: keep the full header but truncate
        // most of the payload.
        val bytes = out.readBytes()
        out.writeBytes(bytes.copyOfRange(0, bytes.size / 2))
        expectUnpackFailure(out)

        // Structural-only check also fails closed for the truncated file...
        assertFalse(PackedYuvFrameStore.verifyStructure(out))

        // ...and succeeds for a complete one.
        val complete = java.io.File(dir, "ok.yuvpack")
        PackedYuvFrameStore.pack(frame(), rotationDegrees = 0, outFile = complete)
        assertTrue(PackedYuvFrameStore.verifyStructure(complete))
    }

    @Test
    fun nonPackedFile_isRejected() {
        val dir = tmp.newFolder()
        val png = java.io.File(dir, "frame_00_color.png")
        png.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10))
        assertThrows(Exception::class.java) { PackedYuvFrameStore.unpack(png) }
        assertFalse(PackedYuvFrameStore.verifyStructure(png))
    }

    @Test
    fun atomicPublication_leavesNoTempFileBehind() {
        val dir = tmp.newFolder()
        val out = java.io.File(dir, "frame_00.yuvpack")
        PackedYuvFrameStore.pack(frame(), rotationDegrees = 0, outFile = out)
        assertTrue(out.isFile)
        val siblings = dir.listFiles { f -> f.name != out.name } ?: emptyArray()
        assertTrue(siblings.isEmpty())
    }
}
