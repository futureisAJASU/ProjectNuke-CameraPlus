package com.projectnuke.keplernightlab

import android.graphics.Color
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Deterministic tests for the verified payload reads of the capture pipeline
 * ([readRaw16], [loadRawRgbaBitmap]). Both now stream through
 * [NoFollowFileSystem.copyVerified], so every payload read is fenced with a
 * no-follow identity check: a file replaced mid-read is rejected instead of
 * silently parsed. These tests pin the parsing semantics that the verified
 * streaming rewrite must preserve.
 */
@RunWith(RobolectricTestRunner::class)
class RawStablePayloadReadsTest {

    @Test
    fun readRaw16DecodesLittleEndianPairs() {
        val root = createTempDirectory("kepler-stable-payload").toFile()
        try {
            val file = root.resolve("frame.raw16").apply {
                writeBytes(byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0x00, 0x34, 0x12))
            }
            val values = readRaw16(file, 3)
            assertEquals(3, values.size)
            assertEquals(0x0201, values[0].toInt() and 0xFFFF)
            assertEquals(0x00FF, values[1].toInt() and 0xFFFF)
            assertEquals(0x1234, values[2].toInt() and 0xFFFF)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readRaw16CarriesOddTrailingByteIntoNextWriteChunk() {
        val root = createTempDirectory("kepler-stable-payload").toFile()
        try {
            // First copyVerified write chunk is 8192 bytes: 4096 whole pairs
            // fill values 0..4095. The odd tail [0xAB, 0xCD] arrives in the
            // second chunk: 0xAB is carried as the low byte and 0xCD completes
            // values[4096] = 0xCDAB.
            val bytes = ByteArray(8194)
            for (i in 0 until 4096) {
                bytes[2 * i] = 0x01
                bytes[2 * i + 1] = 0x02
            }
            bytes[8192] = 0xAB.toByte()
            bytes[8193] = 0xCD.toByte()
            val file = root.resolve("frame.raw16").apply { writeBytes(bytes) }
            val values = readRaw16(file, 4097)
            assertEquals(0x0201, values[0].toInt() and 0xFFFF)
            assertEquals(0x0201, values[4095].toInt() and 0xFFFF)
            assertEquals(0xCDAB, values[4096].toInt() and 0xFFFF)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readRaw16MissingFileThrows() {
        val root = createTempDirectory("kepler-stable-payload").toFile()
        try {
            assertThrows(FileNotFoundException::class.java) {
                readRaw16(root.resolve("missing.raw16"), 1)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loadRawRgbaBitmapDecodesArgbRows() {
        val root = createTempDirectory("kepler-stable-payload").toFile()
        try {
            val file = root.resolve("native.rgba")
            file.writeBytes(
                byteArrayOf(
                    0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),
                    0x00, 0xFF.toByte(), 0x00, 0x80.toByte(),
                    0x00, 0x00, 0xFF.toByte(), 0x40.toByte(),
                    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00
                )
            )
            val bitmap = loadRawRgbaBitmap(file, 2, 2)
            assertEquals(Color.argb(255, 255, 0, 0), bitmap.getPixel(0, 0))
            assertEquals(Color.argb(128, 0, 255, 0), bitmap.getPixel(1, 0))
            assertEquals(Color.argb(64, 0, 0, 255), bitmap.getPixel(0, 1))
            assertEquals(Color.argb(0, 255, 255, 255), bitmap.getPixel(1, 1))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loadRawRgbaBitmapTruncatedFileThrows() {
        val root = createTempDirectory("kepler-stable-payload").toFile()
        try {
            val file = root.resolve("native.rgba").apply { writeBytes(ByteArray(6)) }
            assertThrows(IllegalArgumentException::class.java) {
                loadRawRgbaBitmap(file, 2, 2)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
