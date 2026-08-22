package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NativeRgbaBitmapSink contract under arbitrary OutputStream fragmentation.
 * NoFollowFileSystem.copyVerified streams in ~DEFAULT_BUFFER_SIZE chunks, so
 * one write() call can be smaller than a single RGBA row; the sink must keep
 * partial input across write() calls and prove completion via [NativeRgbaBitmapSink.finish].
 */
@RunWith(RobolectricTestRunner::class)
class NativeRgbaBitmapSinkTest {

    private class KnownPixels(val width: Int, val height: Int, private val argb: List<Int>) {
        fun rgbaBytes(): ByteArray = argb.flatMap { pixel ->
            listOf(
                ((pixel shr 16) and 0xFF).toByte(),
                ((pixel shr 8) and 0xFF).toByte(),
                (pixel and 0xFF).toByte(),
                ((pixel ushr 24) and 0xFF).toByte()
            )
        }.toByteArray()

        fun expectedPixel(index: Int): Int = argb[index]

        fun newSink(): NativeRgbaBitmapSink =
            NativeRgbaBitmapSink(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), width, height)
    }

    private val small = KnownPixels(
        width = 3,
        height = 2,
        argb = listOf(
            Color.argb(255, 255, 0, 0),
            Color.argb(128, 0, 255, 0),
            Color.argb(64, 0, 0, 255),
            Color.argb(0, 255, 255, 255),
            Color.argb(200, 17, 34, 51),
            Color.argb(255, 250, 240, 230)
        )
    )

    // One row is 4096*4 = 16384 bytes > DEFAULT_BUFFER_SIZE (8192), which is
    // exactly the SM-S921N physical shape (4080*4 = 16320 vs ~8KB writes).
    private val wideWidth = 4096
    private val wideHeight = 4

    private fun wideArgb(): List<Int> {
        val pixels = mutableListOf<Int>()
        for (y in 0 until wideHeight) {
            for (x in 0 until wideWidth) {
                pixels.add(Color.argb((x + y) % 256, x % 256, y % 256, (x * 7 + y * 13) % 256))
            }
        }
        return pixels
    }

    private fun assertBitmapMatches(sink: NativeRgbaBitmapSink, known: KnownPixels) {
        val bitmap = sink.bitmapFieldForTest()
        for (index in 0 until known.width * known.height) {
            val x = index % known.width
            val y = index / known.width
            assertEquals("pixel($x,$y)", known.expectedPixel(index), bitmap.getPixel(x, y))
        }
    }

    private fun NativeRgbaBitmapSink.bitmapFieldForTest(): Bitmap {
        val field = NativeRgbaBitmapSink::class.java.getDeclaredField("bitmap")
        field.isAccessible = true
        return field.get(this) as Bitmap
    }

    @Test
    fun nativeRgbaSink_acceptsOneByteWrites() {
        val known = small
        val sink = known.newSink()
        try {
            known.rgbaBytes().forEach { byte -> sink.write(byte.toInt()) }
            sink.finish()
            assertBitmapMatches(sink, known)
        } finally {
            sink.bitmapFieldForTest().recycle()
        }
    }

    @Test
    fun nativeRgbaSink_acceptsChunksSmallerThanOneRow() {
        val known = small
        val sink = known.newSink()
        try {
            val bytes = known.rgbaBytes()
            var offset = 0
            val chunkSizes = listOf(5, 1, 7, 3, 2, 6)
            var chunkIndex = 0
            while (offset < bytes.size) {
                val size = minOf(chunkSizes[chunkIndex % chunkSizes.size], bytes.size - offset)
                sink.write(bytes, offset, size)
                offset += size
                chunkIndex++
            }
            sink.finish()
            assertBitmapMatches(sink, known)
        } finally {
            sink.bitmapFieldForTest().recycle()
        }
    }

    @Test
    fun nativeRgbaSink_accepts8192ByteCopyVerifiedChunking() {
        val known = KnownPixels(wideWidth, wideHeight, wideArgb())
        val dir = createTempDirectory("kepler-rgba-sink").toFile()
        val sink = known.newSink()
        try {
            val file = File(dir, "native.rgba")
            FileOutputStream(file).use { it.write(known.rgbaBytes()) }
            NoFollowFileSystem.copyVerified(file, sink)
            sink.finish()
            val bitmap = sink.bitmapFieldForTest()
            assertEquals(wideWidth, bitmap.width)
            assertEquals(wideHeight, bitmap.height)
            // Rows straddle 8KB write boundaries; probe every row plus boundary columns.
            for (y in 0 until wideHeight) {
                listOf(0, 1, wideWidth / 2 - 1, wideWidth / 2, wideWidth - 1).forEach { x ->
                    val expected = known.expectedPixel(y * wideWidth + x)
                    assertEquals("pixel($x,$y)", expected, bitmap.getPixel(x, y))
                }
            }
        } finally {
            sink.bitmapFieldForTest().recycle()
            dir.deleteRecursively()
        }
    }

    @Test
    fun nativeRgbaSink_acceptsRandomFragmentation() {
        val known = KnownPixels(wideWidth, wideHeight, wideArgb())
        val sink = known.newSink()
        try {
            val bytes = known.rgbaBytes()
            val random = Random(0x5EED1234L)
            var offset = 0
            while (offset < bytes.size) {
                val size = minOf(1 + random.nextInt(9000), bytes.size - offset)
                sink.write(bytes, offset, size)
                offset += size
            }
            sink.finish()
            val bitmap = sink.bitmapFieldForTest()
            for (y in 0 until wideHeight step 2) {
                for (x in 0 until wideWidth step 97) {
                    assertEquals(
                        "pixel($x,$y)",
                        known.expectedPixel(y * wideWidth + x),
                        bitmap.getPixel(x, y)
                    )
                }
            }
        } finally {
            sink.bitmapFieldForTest().recycle()
        }
    }

    @Test
    fun nativeRgbaSink_preservesExactPixelChannels() {
        val known = small
        val sink = known.newSink()
        try {
            sink.write(known.rgbaBytes())
            sink.finish()
            assertBitmapMatches(sink, known)
        } finally {
            sink.bitmapFieldForTest().recycle()
        }
    }

    @Test
    fun nativeRgbaSink_rejectsTruncatedInputAtFinish() {
        val known = small
        val sink = known.newSink()
        try {
            val bytes = known.rgbaBytes()
            // Deliver everything except the final row's last four bytes.
            sink.write(bytes, 0, bytes.size - 4)
            val failure = assertThrows(IllegalStateException::class.java) { sink.finish() }
            assertTrue(failure.message!!.contains("Native RGBA output incomplete"))
        } finally {
            sink.bitmapFieldForTest().recycle()
        }
    }

    @Test
    fun nativeRgbaSink_rejectsTrailingBytes() {
        val known = small
        val sink = known.newSink()
        try {
            val bytes = known.rgbaBytes()
            sink.write(bytes)
            val failure = assertThrows(IllegalStateException::class.java) { sink.write(0x00) }
            assertTrue(failure.message!!.contains("Unexpected trailing bytes"))
        } finally {
            sink.bitmapFieldForTest().recycle()
        }
    }

    @Test
    fun loadRawRgbaBitmap_readsValidRgbaFile() {
        val dir = createTempDirectory("kepler-rgba-load").toFile()
        try {
            val file = File(dir, "native.rgba")
            file.writeBytes(small.rgbaBytes())
            val bitmap = loadRawRgbaBitmap(file, small.width, small.height)
            try {
                for (index in 0 until small.width * small.height) {
                    val x = index % small.width
                    val y = index / small.width
                    assertEquals(small.expectedPixel(index), bitmap.getPixel(x, y))
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadRawRgbaBitmap_readsWideRowsAcrossDefaultBufferChunks() {
        val known = KnownPixels(wideWidth, wideHeight, wideArgb())
        val dir = createTempDirectory("kepler-rgba-load-wide").toFile()
        try {
            val file = File(dir, "native_wide.rgba")
            file.writeBytes(known.rgbaBytes())
            val bitmap = loadRawRgbaBitmap(file, wideWidth, wideHeight)
            try {
                for (y in 0 until wideHeight) {
                    for (x in 0 until wideWidth step 211) {
                        assertEquals(
                            known.expectedPixel(y * wideWidth + x),
                            bitmap.getPixel(x, y)
                        )
                    }
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
