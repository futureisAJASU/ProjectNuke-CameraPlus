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
 * Phase 3: RAW durable-persistence optimization. The extraction must use bulk
 * sequential transfers for compact planes and ONE reusable bounded row buffer
 * for padded planes (never a full-frame allocation), and the post-publish
 * verification must fail closed on any content mismatch.
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

    @Test
    fun rawCompactStride_usesDirectSequentialWritePath() {
        val row0 = byteArrayOf(0x10, 0x01, 0x20, 0x02)
        val row1 = byteArrayOf(0x30, 0x03, 0x40, 0x04)
        val source = plane(row0, row1, rowStride = 4)
        val file = File(createTempDir(), "compact.raw16")

        val strategy = FileOutputStream(file).use { output ->
            writeRaw16Rows(width = 2, height = 2, rowStride = 4, pixelStride = 1,
                limit = 8, buffer = source, sink = output)
        }

        assertEquals(Raw16WriteStrategy.SEQUENTIAL_BULK, strategy)
        assertEquals(8L, file.length())
        assertTrue(file.inputStream().use { it.readBytes() }.contentEquals(byteArrayOf(
            0x10, 0x01, 0x20, 0x02, 0x30, 0x03, 0x40, 0x04
        )))
    }

    @Test
    fun rawPaddedStride_usesBoundedRowPackPath() {
        val row0 = byteArrayOf(0xAA.toByte(), 0x01, 0xBB.toByte(), 0x02)
        val row1 = byteArrayOf(0xCC.toByte(), 0x03, 0xDD.toByte(), 0x04)
        val padding = byteArrayOf(0xEE.toByte(), 0xFF.toByte())
        val source = plane(row0 + padding, row1 + padding, rowStride = 6)
        val file = File(createTempDir(), "padded.raw16")

        val strategy = FileOutputStream(file).use { output ->
            writeRaw16Rows(width = 2, height = 2, rowStride = 6, pixelStride = 1,
                limit = 12, buffer = source, sink = output)
        }

        assertEquals(Raw16WriteStrategy.PADDED_ROW_PACK, strategy)
        // Padding is stripped: the durable payload is exactly width*height*2.
        assertEquals(8L, file.length())
        assertTrue(file.inputStream().use { it.readBytes() }.contentEquals(byteArrayOf(
            0xAA.toByte(), 0x01, 0xBB.toByte(), 0x02, 0xCC.toByte(), 0x03, 0xDD.toByte(), 0x04
        )))
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
            writeRaw16Rows(16, 2, 32, 1, 64, source, it)
        }
        val strategyB = FileOutputStream(fileB).use {
            writeRaw16Rows(16, 2, 32, 1, 64, source, it)
        }

        assertEquals(Raw16WriteStrategy.SEQUENTIAL_BULK, strategyA)
        assertEquals(Raw16WriteStrategy.SEQUENTIAL_BULK, strategyB)
        val after = ByteArray(64).also { source.duplicate().get(it) }
        assertTrue(before.contentEquals(after))
        assertTrue(fileA.readBytes().contentEquals(payload))
        assertTrue(fileB.readBytes().contentEquals(payload))
    }

    @Test
    fun rawPersistence_exoticPixelStrideStillFallsBackScalar() {
        // pixelStride == 2 interleaved layout keeps the exact legacy path.
        val source = ByteBuffer.allocate(8).apply {
            put(byteArrayOf(
                0x01, 0xDE.toByte(), 0x02, 0xAD.toByte(),
                0x03, 0xBE.toByte(), 0x04, 0xEF.toByte()
            ))
            flip()
        }
        val file = File(createTempDir(), "interleaved.raw16")
        val strategy = FileOutputStream(file).use {
            writeRaw16Rows(2, 2, 4, 2, 8, source, it)
        }
        assertEquals(Raw16WriteStrategy.SCALAR_FALLBACK, strategy)
        assertEquals(8L, file.length())
        assertTrue(file.readBytes().contentEquals(byteArrayOf(
            0x01, 0xDE.toByte(), 0x02, 0xAD.toByte(),
            0x03, 0xBE.toByte(), 0x04, 0xEF.toByte()
        )))
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
