package android.media

import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM test fake for the real Camera2 [Image].
 *
 * Robolectric provides no shadow for [Image], and `Image`'s constructor is
 * package-private, so the fake is declared inside the `android.media` package (test
 * sources only) where that constructor is accessible.  [close] is intercepted so
 * tests can assert exactly-once release; all other members are inert.
 */
class FakeImage(
    private val timestamp: Long = 4321L,
    private val closeThrows: Boolean = false
) : Image() {
    val closeCount = AtomicInteger(0)

    override fun getFormat(): Int = 1
    override fun getHeight(): Int = 1
    override fun getWidth(): Int = 1
    override fun getTimestamp(): Long = timestamp
    override fun getPlanes(): Array<Image.Plane> = emptyArray()
    override fun close() {
        closeCount.incrementAndGet()
        if (closeThrows) error("image close failed")
    }
}
