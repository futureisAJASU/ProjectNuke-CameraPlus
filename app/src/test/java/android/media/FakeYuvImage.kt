package android.media

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM test fake for [Image] used by production-bridge tests of the YUV capture pipeline.
 *
 * Unlike [FakeImage], this fake exposes three YUV_420_888 planes with minimal byte
 * buffers so that [com.projectnuke.keplernightlab.Camera2YuvImageAccess] and
 * [com.projectnuke.keplernightlab.Camera2DirectYuvImageAccess] — which require
 * `image.planes[0..2]` — can be exercised end-to-end without a real Camera2 device.
 *
 * [close] is intercepted so tests can assert exactly-once release; all other
 * members are inert.
 */
class FakeYuvImage(
    private val timestamp: Long = 4321L,
    private val width: Int = 1,
    private val height: Int = 1,
    private val closeThrows: Boolean = false
) : Image() {
    val closeCount = AtomicInteger(0)
    val closeThrowable = AtomicInteger(0)

    private val yBuffer: ByteBuffer = ByteBuffer.allocate(1)
    private val uBuffer: ByteBuffer = ByteBuffer.allocate(1)
    private val vBuffer: ByteBuffer = ByteBuffer.allocate(1)

    private val yPlane = FakePlane(yBuffer, rowStride = 1, pixelStride = 1)
    private val uPlane = FakePlane(uBuffer, rowStride = 1, pixelStride = 1)
    private val vPlane = FakePlane(vBuffer, rowStride = 1, pixelStride = 1)

    override fun getFormat(): Int = 35 // ImageFormat.YUV_420_888 (constant value 35)
    override fun getHeight(): Int = height
    override fun getWidth(): Int = width
    override fun getTimestamp(): Long = timestamp
    override fun getPlanes(): Array<Image.Plane> = arrayOf(yPlane, uPlane, vPlane)
    override fun close() {
        closeCount.incrementAndGet()
        if (closeThrows) {
            closeThrowable.incrementAndGet()
            error("image close failed")
        }
    }

    private class FakePlane(
        private val buffer: ByteBuffer,
        private val rowStride: Int,
        private val pixelStride: Int
    ) : Image.Plane() {
        override fun getBuffer(): ByteBuffer = buffer
        override fun getPixelStride(): Int = pixelStride
        override fun getRowStride(): Int = rowStride
    }
}
