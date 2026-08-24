package com.projectnuke.keplernightlab

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject
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
 * Phase-A corrective audit, Phase 5: packed-YUV V1 durability and structural
 * verification hardening (format stays OUT of the production default path).
 *
 *  - The final header digest patch is explicitly synced BEFORE atomic
 *    publication: a published file always carries durably-synced metadata.
 *  - Structural verification rejects header-valid truncated payloads, payload
 *    decomposition mismatches, and stride/dimension violations.
 *  - Digest verification lives ONLY in the full durable verifier; corruption
 *    of the payload is invisible to structural checks but fatal to the full
 *    path.
 */
@RunWith(RobolectricTestRunner::class)
class PackedYuvFrameStoreAuditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun frame(): BufferedYuvFrame {
        val width = 8
        val height = 6
        return BufferedYuvFrame(
            index = 1,
            timestampNs = 4321L,
            width = width,
            height = height,
            y = ByteArray(10 * height) { it.toByte() },
            u = ByteArray(5 * height / 2) { (it * 3).toByte() },
            v = ByteArray(5 * height / 2) { (it * 7).toByte() },
            yRowStride = 10,
            yPixelStride = 1,
            uRowStride = 5,
            uPixelStride = 1,
            vRowStride = 5,
            vPixelStride = 1
        )
    }

    private fun packFile(name: String = "frame_01.yuvpack"): File {
        val out = File(tmp.newFolder(), name)
        PackedYuvFrameStore.pack(frame(), rotationDegrees = 90, outFile = out)
        return out
    }

    /** Rebuilds the container with a mutated header JSON (same payload bytes). */
    private fun rewriteWithMutatedHeader(original: File, mutate: (JSONObject) -> Unit): File {
        val data = original.readBytes()
        val headerLength =
            (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8) or
                ((data[10].toInt() and 0xFF) shl 16) or ((data[11].toInt() and 0xFF) shl 24)
        val headerJson = JSONObject(String(data, 12, headerLength, Charsets.UTF_8))
        mutate(headerJson)
        val newHeader = headerJson.toString().toByteArray(Charsets.UTF_8)
        val out = File(original.parentFile, "mutated_${original.name}")
        RandomAccessFile(out, "rw").use { raf ->
            raf.write(data.copyOfRange(0, 8))
            raf.writeInt(newHeader.size)
            raf.write(newHeader)
            raf.write(data.copyOfRange(12 + headerLength, data.size))
        }
        return out
    }

    // ------------------------------------------------------------------
    // Durability ordering
    // ------------------------------------------------------------------

    @Test
    fun packedYuv_headerPatchIsSyncedBeforePublish() {
        val dir = tmp.newFolder()
        val out = File(dir, "synced.yuvpack")
        val boundaries = mutableListOf<String>()
        PackedYuvFrameStore.packDurabilityObserver = { boundary, _ -> boundaries.add(boundary) }
        try {
            PackedYuvFrameStore.pack(frame(), rotationDegrees = 90, outFile = out)
        } finally {
            PackedYuvFrameStore.packDurabilityObserver = null
        }
        // Exact required sequence: payload fsync -> final header patch synced ->
        // atomic publication. Publication is never observed before durability.
        assertEquals(listOf("payloadSynced", "headerSynced", "published"), boundaries)
        // The published file carries the REAL digest in its header (patch landed).
        val header = PackedYuvFrameStore.readHeader(out)
        assertTrue(header.payloadDigest.none { it == '0' } || header.payloadDigest != "0".repeat(64))
        PackedYuvFrameStore.verifyFull(out)
    }

    @Test
    fun packedYuv_atomicPublishOnlyAfterFinalDurability() {
        val dir = tmp.newFolder()
        val out = File(dir, "atomic.yuvpack")
        var publishedAt = -1
        var headerSyncedAt = -1
        var step = 0
        PackedYuvFrameStore.packDurabilityObserver = { boundary, file ->
            if (boundary == "headerSynced") {
                headerSyncedAt = step++
                // At header-sync time only the temp candidate exists; nothing is
                // published yet.
                assertFalse(file.isFile)
            } else if (boundary == "published") {
                publishedAt = step++
                assertTrue(file.isFile)
            }
        }
        try {
            PackedYuvFrameStore.pack(frame(), rotationDegrees = 180, outFile = out)
        } finally {
            PackedYuvFrameStore.packDurabilityObserver = null
        }
        assertTrue(headerSyncedAt >= 0)
        assertTrue(publishedAt > headerSyncedAt)
        // Atomic semantics: no leftover temp candidates next to the output.
        assertEquals(listOf("atomic.yuvpack"), dir.listFiles()!!.map { it.name })
    }

    // ------------------------------------------------------------------
    // Structural rejection
    // ------------------------------------------------------------------

    @Test
    fun packedYuv_completeHeader_truncatedPayloadRejected() {
        val original = packFile()
        assertTrue(PackedYuvFrameStore.verifyStructure(original))
        val truncated = File(original.parentFile, "truncated.yuvpack")
        val data = original.readBytes()
        truncated.writeBytes(data.copyOfRange(0, data.size - 10))

        // Complete valid header + truncated payload must FAIL structurally.
        assertFalse(PackedYuvFrameStore.verifyStructure(truncated))
        assertThrows(IllegalArgumentException::class.java) { PackedYuvFrameStore.readHeader(truncated) }
        assertThrows(IllegalArgumentException::class.java) { PackedYuvFrameStore.unpack(truncated) }
        assertThrows(IllegalArgumentException::class.java) { PackedYuvFrameStore.verifyFull(truncated) }
    }

    @Test
    fun packedYuv_payloadLengthMismatchRejected() {
        val original = packFile()
        // Decomposition mismatch: declared total no longer equals y+u+v while
        // padding the file so naive length checks would still pass.
        val padded = rewriteWithMutatedHeader(original) { header ->
            header.put("payloadLength", header.getLong("payloadLength") + 8L)
        }
        assertFalse(PackedYuvFrameStore.verifyStructure(padded))
        assertThrows(IllegalArgumentException::class.java) { PackedYuvFrameStore.readHeader(padded) }
        assertThrows(IllegalArgumentException::class.java) { PackedYuvFrameStore.unpack(padded) }
    }

    @Test
    fun packedYuv_invalidStrideRejected() {
        val original = packFile()
        val invalid = rewriteWithMutatedHeader(original) { header ->
            header.put("yRowStride", 4)
        }
        assertFalse(PackedYuvFrameStore.verifyStructure(invalid))
        assertThrows(IllegalArgumentException::class.java) { PackedYuvFrameStore.unpack(invalid) }
    }

    @Test
    fun packedYuv_invalidDimensionsRejected() {
        val original = packFile()
        val zeroWidth = rewriteWithMutatedHeader(original) { it.put("width", 0) }
        assertFalse(PackedYuvFrameStore.verifyStructure(zeroWidth))
        val hugeHeight = rewriteWithMutatedHeader(original) { it.put("height", 1 shl 20) }
        assertFalse(PackedYuvFrameStore.verifyStructure(hugeHeight))
        val badPixelStride = rewriteWithMutatedHeader(original) { it.put("uPixelStride", 0) }
        assertFalse(PackedYuvFrameStore.verifyStructure(badPixelStride))
    }

    // ------------------------------------------------------------------
    // Digest verification separation
    // ------------------------------------------------------------------

    @Test
    fun packedYuv_fullDigestVerificationDetectsCorruption() {
        val original = packFile()
        val corrupted = File(original.parentFile, "corrupted.yuvpack")
        // Flip one byte strictly inside the PAYLOAD (never the header): flip in
        // the last plane region so structure stays identical.
        val data = original.readBytes()
        val headerLength =
            (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8) or
                ((data[10].toInt() and 0xFF) shl 16) or ((data[11].toInt() and 0xFF) shl 24)
        val payloadStart = 12 + headerLength
        val target = data.size - 1 - ((data.size - payloadStart) / 4)
        data[target] = (data[target].toInt() xor 0x5A).toByte()
        corrupted.writeBytes(data)

        // Structure intact: same lengths, same header. Only the CONTENT changed.
        assertTrue(PackedYuvFrameStore.verifyStructure(corrupted))
        // The separately-named FULL durable verifier streams the payload and
        // rejects the digest mismatch.
        assertThrows(java.lang.IllegalArgumentException::class.java) {
            PackedYuvFrameStore.verifyFull(corrupted)
        }
        assertThrows(java.lang.IllegalArgumentException::class.java) {
            PackedYuvFrameStore.unpack(corrupted)
        }
    }
}
