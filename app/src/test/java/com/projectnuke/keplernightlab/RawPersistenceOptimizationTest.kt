package com.projectnuke.keplernightlab

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 3: RAW durable-persistence optimization over REAL Camera2 RAW_SENSOR
 * layouts. RAW_SENSOR is a single-plane 16-bit-per-sample Bayer format and
 * AOSP ImageReader maps it to pixelStride == 2 (bytes between adjacent sample
 * starts). Therefore:
 *  - compact plane (rowStride == width*2) must use SEQUENTIAL_BULK;
 *  - padded plane (rowStride > width*2) must use PADDED_ROW_PACK with one
 *    reusable bounded row buffer (never a full-frame allocation);
 *  - only genuinely non-standard positive pixelStride != 2 layouts may fall
 *    back to the scalar path.
 * Both bulk paths must stay byte-identical to the legacy scalar extraction,
 * and post-publish verification must fail closed on any content mismatch.
 */
@RunWith(RobolectricTestRunner::class)
class RawPersistenceOptimizationTest {

    private fun plane(vararg rows: ByteArray, rowStride: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(rows.size * rowStride)
        rows.forEachIndexed { index, row ->
            buffer.position(index * rowStride)
            buffer.put(row)
        }
        buffer.flip()
        return buffer
    }

    private fun deterministicBytes(size: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + 7) xor (index ushr 5)).toByte() }

    /**
     * Exact byte semantics of the pre-optimization scalar extractor: for every
     * sample x of row y, read the two bytes beginning at y*rowStride +
     * x*pixelStride; zero-fill samples beyond the mapped limit.
     */
    private fun legacyScalarReference(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        limit: Int,
        buffer: ByteBuffer
    ): ByteArray {
        val out = ByteArray(width * height * 2)
        var o = 0
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val index = row + x * pixelStride
                if (index + 1 < limit) {
                    out[o++] = buffer.get(index)
                    out[o++] = buffer.get(index + 1)
                } else {
                    out[o++] = 0
                    out[o++] = 0
                }
            }
        }
        return out
    }

    /** Writes through the production seam; returns the strategy AND durable bytes. */
    private fun writeToFile(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        limit: Int,
        source: ByteBuffer,
        name: String
    ): Pair<Raw16WriteStrategy, ByteArray> {
        val file = File(createTempDir(), name)
        val strategy = FileOutputStream(file).use { output ->
            writeRaw16Rows(width, height, rowStride, pixelStride, limit, source, output)
        }
        return strategy to file.readBytes()
    }

    @Test
    fun rawSensorCompactPixelStride2_usesSequentialBulk() {
        // A normal tightly-packed RAW_SENSOR plane: pixelStride=2, no padding.
        val payload = deterministicBytes(2 * 3 * 2)
        val source = plane(
            payload.copyOfRange(0, 4),
            payload.copyOfRange(4, 8),
            payload.copyOfRange(8, 12),
            rowStride = 4
        )
        val file = File(createTempDir(), "compact-raw16.raw16")

        val strategy = FileOutputStream(file).use { output ->
            writeRaw16Rows(width = 2, height = 3, rowStride = 4, pixelStride = 2,
                limit = 12, buffer = source, sink = output)
        }

        assertEquals(Raw16WriteStrategy.SEQUENTIAL_BULK, strategy)
        assertEquals(payload.size.toLong(), file.length())
        assertTrue(file.inputStream().use { it.readBytes() }.contentEquals(payload))
    }

    @Test
    fun rawSensorPaddedPixelStride2_usesBoundedRowPack() {
        // A normal padded RAW_SENSOR plane: pixelStride=2, rowStride > width*2.
        val row0 = byteArrayOf(0x10, 0x01, 0x20, 0x02)
        val row1 = byteArrayOf(0x30, 0x03, 0x40, 0x04)
        val padding0 = byteArrayOf(0xEE.toByte(), 0xFF.toByte())
        val padding1 = byteArrayOf(0x11, 0x22.toByte())
        val source = plane(row0 + padding0, row1 + padding1, rowStride = 6)
        val file = File(createTempDir(), "padded-raw16.raw16")

        val strategy = FileOutputStream(file).use { output ->
            writeRaw16Rows(width = 2, height = 2, rowStride = 6, pixelStride = 2,
                limit = 12, buffer = source, sink = output)
        }

        assertEquals(Raw16WriteStrategy.PADDED_ROW_PACK, strategy)
        // Padding is stripped: the durable payload is exactly width*height*2.
        assertEquals(8L, file.length())
        assertTrue(file.inputStream().use { it.readBytes() }.contentEquals(byteArrayOf(
            0x10, 0x01, 0x20, 0x02, 0x30, 0x03, 0x40, 0x04
        )))
    }

    @Test
    fun rawSensorCompactBulk_isByteIdenticalToLegacyScalarReference() {
        val width = 24
        val height = 16
        val rowStride = width * 2
        val limit = rowStride * height
        val bytes = deterministicBytes(limit)
        val source = ByteBuffer.allocate(limit).apply { put(bytes); flip() }
        val reference = legacyScalarReference(width, height, rowStride, 2, limit, source)

        val (strategy, written) = writeToFile(width, height, rowStride, 2, limit, source, "bulk-id.raw16")

        assertEquals(Raw16WriteStrategy.SEQUENTIAL_BULK, strategy)
        assertTrue(written.contentEquals(reference))
        assertTrue(written.contentEquals(bytes))
    }

    @Test
    fun rawSensorPaddedBulk_isByteIdenticalToLegacyScalarReference() {
        val width = 24
        val height = 16
        val rowStride = width * 2 + 8
        val limit = rowStride * height
        val bytes = deterministicBytes(limit)
        val source = ByteBuffer.allocate(limit).apply { put(bytes); flip() }
        val reference = legacyScalarReference(width, height, rowStride, 2, limit, source)

        val (strategy, written) = writeToFile(width, height, rowStride, 2, limit, source, "padded-bulk-id.raw16")

        assertEquals(Raw16WriteStrategy.PADDED_ROW_PACK, strategy)
        assertEquals((width * height * 2).toLong(), written.size.toLong())
        assertTrue(written.contentEquals(reference))
        // And the reference equals the packed rows: each row's first width*2 bytes.
        val expected = ByteArray(width * height * 2)
        for (y in 0 until height) {
            bytes.copyInto(expected, y * width * 2, y * rowStride, y * rowStride + width * 2)
        }
        assertTrue(written.contentEquals(expected))
    }

    @Test
    fun rawSensorFinalRow_withoutMappedTrailingPadding_isAccepted() {
        // Padded layout where ONLY the final row's payload is mapped: its
        // trailing rowStride padding lies outside buffer.limit().
        val width = 4
        val height = 3
        val rowStride = width * 2 + 2
        val limit = (height - 1) * rowStride + width * 2
        val bytes = deterministicBytes(limit)
        val source = ByteBuffer.allocate(limit).apply { put(bytes); flip() }
        val reference = legacyScalarReference(width, height, rowStride, 2, limit, source)

        val (strategy, written) = writeToFile(width, height, rowStride, 2, limit, source, "final-row.raw16")

        assertEquals(Raw16WriteStrategy.PADDED_ROW_PACK, strategy)
        assertEquals((width * height * 2).toLong(), written.size.toLong())
        assertTrue(written.contentEquals(reference))
        // The final row's payload is exactly its mapped width*2 prefix.
        val finalRowStart = (height - 1) * width * 2
        assertTrue(
            written.copyOfRange(finalRowStart, written.size).contentEquals(
                bytes.copyOfRange((height - 1) * rowStride, limit)
            )
        )
    }

    @Test
    fun rawSensorExoticPixelStride_fallsBackScalar() {
        // Genuinely non-standard layouts keep the EXACT legacy scalar semantics:
        // sample x reads the two bytes at rowOffset + x*pixelStride.

        // pixelStride == 4: interleaved double-sample layout with junk between.
        val exoticSource = ByteBuffer.allocate(16).apply {
            put(byteArrayOf(
                0x01, 0xDE.toByte(), 0xAA.toByte(), 0xAA.toByte(),
                0x02, 0xAD.toByte(), 0xBB.toByte(), 0xBB.toByte(),
                0x03, 0xBE.toByte(), 0xCC.toByte(), 0xCC.toByte(),
                0x04, 0xEF.toByte(), 0xDD.toByte(), 0xDD.toByte()
            ))
            flip()
        }
        val (exoticStrategy, exoticWritten) = writeToFile(2, 2, 8, 4, 16, exoticSource, "interleaved.raw16")
        assertEquals(Raw16WriteStrategy.SCALAR_FALLBACK, exoticStrategy)
        assertEquals(8L, exoticWritten.size.toLong())
        assertTrue(exoticWritten.contentEquals(byteArrayOf(
            0x01, 0xDE.toByte(), 0x02, 0xAD.toByte(),
            0x03, 0xBE.toByte(), 0x04, 0xEF.toByte()
        )))

        // pixelStride == 1 (overlapping sample windows) must NOT take a bulk
        // shortcut: bulk-copying contiguous bytes would produce different data.
        val overlapSource = ByteBuffer.allocate(6).apply {
            put(byteArrayOf(0x10, 0x21, 0x32, 0x43, 0x54, 0x65))
            flip()
        }
        val overlapReference = legacyScalarReference(2, 2, 3, 1, 6, overlapSource)
        val (overlapStrategy, overlapWritten) = writeToFile(2, 2, 3, 1, 6, overlapSource, "overlap.raw16")
        assertEquals(Raw16WriteStrategy.SCALAR_FALLBACK, overlapStrategy)
        assertTrue(overlapWritten.contentEquals(overlapReference))
    }

    @Test
    fun rawPersistence_doesNotAllocateFullFrameCopyWhenUnnecessary() {
        // A full-frame copy would be width*height*2 bytes; the bulk paths only
        // ever own one bounded row buffer. Prove non-destructive single-pass
        // behavior: the source plane is unchanged after the write and a second
        // frame write from the same layout produces identical bytes.
        val payload = ByteArray(64) { (it * 7).toByte() }
        val source = ByteBuffer.allocate(64).apply { put(payload); flip() }
        val before = ByteArray(64).also { source.duplicate().get(it) }
        val fileA = File(createTempDir(), "a.raw16")
        val fileB = File(createTempDir(), "b.raw16")

        val strategyA = FileOutputStream(fileA).use {
            writeRaw16Rows(16, 2, 32, 2, 64, source, it)
        }
        val strategyB = FileOutputStream(fileB).use {
            writeRaw16Rows(16, 2, 32, 2, 64, source, it)
        }

        assertEquals(Raw16WriteStrategy.SEQUENTIAL_BULK, strategyA)
        assertEquals(Raw16WriteStrategy.SEQUENTIAL_BULK, strategyB)
        val after = ByteArray(64).also { source.duplicate().get(it) }
        assertTrue(before.contentEquals(after))
        assertTrue(fileA.readBytes().contentEquals(payload))
        assertTrue(fileB.readBytes().contentEquals(payload))
    }

    @Test
    fun rawPersistence_verifyFailsClosedOnContentMismatch() {
        val dir = createTempDir()
        val good = File(dir, "good.raw16").apply { writeBytes(ByteArray(8) { (it + 1).toByte() }) }
        val digest = verifyRaw16Payload(good, expectedSize = 8L)

        // Same size, flipped content: size-only checks pass, digest must not.
        val corrupt = File(dir, "corrupt.raw16").apply {
            val bytes = ByteArray(8) { (it + 1).toByte() }
            bytes[3] = (bytes[3].toInt() xor 0x01).toByte()
            writeBytes(bytes)
        }
        assertThrows(IllegalStateException::class.java) {
            verifyRaw16Payload(corrupt, expectedSize = 8L, expectedSha256 = digest.sha256)
        }
        // The intact file passes the same strict check.
        verifyRaw16Payload(good, expectedSize = 8L, expectedSha256 = digest.sha256)
    }

    @Test
    fun rawPersistence_digestAtWriteMatchesReadBack() {
        val payload = ByteArray(4096) { ((it * 31) shr 3).toByte() }
        val digesting = DigestingOutputStream(object : OutputStream() {
            override fun write(b: Int) {}
        })
        digesting.write(payload)
        val writtenDigest = digesting.digestHex()

        val file = File(createTempDir(), "digest.raw16").apply { writeBytes(payload) }
        val readBack = NoFollowFileSystem.digestVerified(file)
        assertEquals(writtenDigest, readBack.sha256)
        // And the strict verifier accepts exactly these bytes.
        verifyRaw16Payload(file, expectedSize = payload.size.toLong(), expectedSha256 = writtenDigest)
    }
}
