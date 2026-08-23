package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Packed-YUV durable source representation v1 (Phase 6).
 *
 * Physical S24 evidence (Phase 5 shape): 12MP PNG persistence dominates the
 * foreground capture stage - pure-Kotlin YUV->RGB conversion plus 12MP PNG
 * compression take seconds per frame on the single persistence worker, so a
 * 4-frame burst could persist only 1 frame before the acquisition deadline.
 *
 * The packed format removes conversion AND compression from the foreground
 * critical path entirely: the buffered YUV planes are copied out sequentially,
 * fsynced, and structurally verified (exact length + SHA-256 digest).  The
 * result is lossless, self-describing, process-death recoverable, and cheap to
 * verify (no image decode anywhere on the capture path).
 *
 * Format layout (all integers little-endian):
 *   [0..3]   magic "KPYN"
 *   [4..7]   format version = 1
 *   [8..11]  header length H (bytes, for forward compatibility)
 *   header (JSON, H bytes):
 *     width, height, yRowStride, yPixelStride, uRowStride, uPixelStride,
 *     vRowStride, vPixelStride, rotationDegrees, frameIndex, timestampNs,
 *     payloadLength, payloadDigest (SHA-256 hex)
 *   payload: y plane bytes, u plane bytes, v plane bytes (exactly the captured
 *     arrays - original strides preserved, no repacking, no color change)
 *
 * Old PNG cache jobs remain reprocessable unchanged: nothing in the existing
 * PNG pipeline is modified.  New packed-YUV jobs are explicitly versioned by
 * the magic + version header and by job metadata ("frameEncoding").
 */
internal object PackedYuvFrameStore {

    const val MAGIC = "KPYN"
    const val VERSION = 1
    const val FILE_EXTENSION = ".yuvpack"

    private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)
    private const val HEADER_LENGTH_OFFSET = 8
    private const val FIXED_PREFIX_BYTES = 12
    private val COPY_BUFFER_SIZE = 256 * 1024

    internal data class Header(
        val width: Int,
        val height: Int,
        val yRowStride: Int,
        val yPixelStride: Int,
        val uRowStride: Int,
        val uPixelStride: Int,
        val vRowStride: Int,
        val vPixelStride: Int,
        val rotationDegrees: Int,
        val frameIndex: Int,
        val timestampNs: Long,
        val payloadLength: Long,
        val yBytes: Long,
        val uBytes: Long,
        val vBytes: Long,
        val payloadDigest: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("width", width)
            .put("height", height)
            .put("yRowStride", yRowStride)
            .put("yPixelStride", yPixelStride)
            .put("uRowStride", uRowStride)
            .put("uPixelStride", uPixelStride)
            .put("vRowStride", vRowStride)
            .put("vPixelStride", vPixelStride)
            .put("rotationDegrees", rotationDegrees)
            .put("frameIndex", frameIndex)
            .put("timestampNs", timestampNs)
            .put("payloadLength", payloadLength)
            .put("yBytes", yBytes)
            .put("uBytes", uBytes)
            .put("vBytes", vBytes)
            .put("payloadDigest", payloadDigest)

        companion object {
            fun fromJson(json: JSONObject): Header = Header(
                width = json.getInt("width"),
                height = json.getInt("height"),
                yRowStride = json.getInt("yRowStride"),
                yPixelStride = json.getInt("yPixelStride"),
                uRowStride = json.getInt("uRowStride"),
                uPixelStride = json.getInt("uPixelStride"),
                vRowStride = json.getInt("vRowStride"),
                vPixelStride = json.getInt("vPixelStride"),
                rotationDegrees = json.getInt("rotationDegrees"),
                frameIndex = json.getInt("frameIndex"),
                timestampNs = json.getLong("timestampNs"),
                payloadLength = json.getLong("payloadLength"),
                yBytes = json.getLong("yBytes"),
                uBytes = json.getLong("uBytes"),
                vBytes = json.getLong("vBytes"),
                payloadDigest = json.getString("payloadDigest")
            )
        }
    }

    /** Result of reading back a packed frame. Payload bytes equal the stored planes exactly. */
    internal data class PackedFrame(
        val header: Header,
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray
    ) {
        val width: Int get() = header.width
        val height: Int get() = header.height
        val rotationDegrees: Int get() = header.rotationDegrees
        val frameIndex: Int get() = header.frameIndex
        val timestampNs: Long get() = header.timestampNs
    }

    private class DigestingOutput(private val sink: FileOutputStream) {
        val digest = MessageDigest.getInstance("SHA-256")

        /** Prefix/header bytes: written verbatim, NEVER included in the payload digest. */
        fun writeMeta(bytes: ByteArray) {
            sink.write(bytes)
        }

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            sink.write(bytes, offset, length)
            digest.update(bytes, offset, length)
        }
    }

    /**
     * Packs the buffered YUV planes into [outFile]: sequential durable write
     * (flush + fsync) with streaming SHA-256 over the payload.  Bounded memory:
     * fixed 256KB copy buffer; no full-frame RGB allocation ever happens.
     */
    fun pack(frame: BufferedYuvFrame, rotationDegrees: Int, outFile: File) {
        require(frame.y.isNotEmpty() || frame.u.isNotEmpty() || frame.v.isNotEmpty()) {
            "packed YUV frame has empty planes"
        }
        val temp = File(outFile.parentFile, ".${outFile.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { rawOut ->
                val out = DigestingOutput(rawOut)
                val yBytes = frame.y.size.toLong()
                val uBytes = frame.u.size.toLong()
                val vBytes = frame.v.size.toLong()
                val payloadLength = yBytes + uBytes + vBytes
                // The digest placeholder occupies exactly DIGEST_HEX_LENGTH bytes
                // so the in-place patch below cannot move any offsets.
                val headerJson = Header(
                    width = frame.width,
                    height = frame.height,
                    yRowStride = frame.yRowStride,
                    yPixelStride = frame.yPixelStride,
                    uRowStride = frame.uRowStride,
                    uPixelStride = frame.uPixelStride,
                    vRowStride = frame.vRowStride,
                    vPixelStride = frame.vPixelStride,
                    rotationDegrees = rotationDegrees,
                    frameIndex = frame.index,
                    timestampNs = frame.timestampNs,
                    payloadLength = payloadLength,
                    yBytes = yBytes,
                    uBytes = uBytes,
                    vBytes = vBytes,
                    payloadDigest = PLACEHOLDER_DIGEST
                ).toJson().toString().toByteArray(Charsets.UTF_8)

                writeFixedPrefix(out, headerJson.size)
                out.writeMeta(headerJson)
                writePlaneWithDigest(out, frame.y)
                writePlaneWithDigest(out, frame.u)
                writePlaneWithDigest(out, frame.v)
                rawOut.flush()
                rawOut.fd.sync()

                val digestHex = out.digest.digest().toHex()
                check(digestHex.length == DIGEST_HEX_LENGTH)
                patchHeaderDigest(temp, digestHex)
            }
            KeplerJobMetadata.atomicReplace(temp, outFile)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun writeFixedPrefix(out: DigestingOutput, headerLength: Int) {
        val prefix = ByteArray(FIXED_PREFIX_BYTES)
        MAGIC_BYTES.copyInto(prefix)
        prefix[4] = VERSION.toByte()
        prefix[5] = 0; prefix[6] = 0; prefix[7] = 0
        prefix[8] = (headerLength and 0xFF).toByte()
        prefix[9] = ((headerLength shr 8) and 0xFF).toByte()
        prefix[10] = ((headerLength shr 16) and 0xFF).toByte()
        prefix[11] = ((headerLength shr 24) and 0xFF).toByte()
        out.writeMeta(prefix)
    }

    private fun writePlaneWithDigest(out: DigestingOutput, plane: ByteArray) {
        var offset = 0
        while (offset < plane.size) {
            val chunk = minOf(COPY_BUFFER_SIZE, plane.size - offset)
            out.write(plane, offset, chunk)
            offset += chunk
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** The digest field occupies the LAST 64 bytes of the quoted header value ("00..0"} tail). */
    private fun patchHeaderDigest(file: File, digestHex: String) {
        RandomAccessFile(file, "rw").use { raf ->
            val headerLength = readHeaderLength(raf)
            val digestStart = FIXED_PREFIX_BYTES.toLong() + headerLength - DIGEST_HEX_LENGTH - 2L
            raf.seek(digestStart)
            raf.write(digestHex.toByteArray(Charsets.US_ASCII))
        }
    }

    private const val DIGEST_HEX_LENGTH = 64
    private val PLACEHOLDER_DIGEST = "0".repeat(DIGEST_HEX_LENGTH)

    private fun readHeaderLength(raf: RandomAccessFile): Int {
        val prefix = ByteArray(FIXED_PREFIX_BYTES)
        raf.seek(0)
        raf.readFully(prefix)
        return (prefix[8].toInt() and 0xFF) or
            ((prefix[9].toInt() and 0xFF) shl 8) or
            ((prefix[10].toInt() and 0xFF) shl 16) or
            ((prefix[11].toInt() and 0xFF) shl 24)
    }

    /** Reads ONLY the fixed prefix + header JSON; never touches the payload. */
    fun readHeader(file: File): Header {
        RandomAccessFile(file, "r").use { raf ->
            val prefix = ByteArray(FIXED_PREFIX_BYTES)
            raf.readFully(prefix)
            validatePrefix(prefix)
            val headerLength = readHeaderLength(raf)
            require(headerLength > DIGEST_HEX_LENGTH && headerLength < 64 * 1024) {
                "packed YUV header length invalid: $headerLength"
            }
            val headerBytes = ByteArray(headerLength)
            raf.readFully(headerBytes)
            return Header.fromJson(JSONObject(String(headerBytes, Charsets.UTF_8)))
        }
    }

    private fun validatePrefix(prefix: ByteArray) {
        require(prefix.copyOfRange(0, 4).contentEquals(MAGIC_BYTES)) {
            "not a packed YUV frame (bad magic)"
        }
        val version = prefix[4].toInt() and 0xFF
        require(version == VERSION) { "unsupported packed YUV version $version" }
    }

    /**
     * Reads and fully verifies a packed frame: magic/version, exact total
     * length, exact payload length, and SHA-256 digest.  Any mismatch throws -
     * callers treat an unreadable packed frame like a missing source frame.
     */
    fun unpack(file: File): PackedFrame {
        RandomAccessFile(file, "r").use { raf ->
            val prefix = ByteArray(FIXED_PREFIX_BYTES)
            raf.readFully(prefix)
            validatePrefix(prefix)
            val headerLength = readHeaderLength(raf)
            require(headerLength > DIGEST_HEX_LENGTH && headerLength < 64 * 1024) {
                "packed YUV header length invalid: $headerLength"
            }
            val headerBytes = ByteArray(headerLength)
            raf.readFully(headerBytes)
            val header = Header.fromJson(JSONObject(String(headerBytes, Charsets.UTF_8)))

            val expectedTotal = FIXED_PREFIX_BYTES.toLong() + headerLength + header.payloadLength
            require(raf.length() == expectedTotal) {
                "packed YUV truncated: expected=$expectedTotal actual=${raf.length()}"
            }

            // Stream the payload once: verify the digest while buffering planes.
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            raf.seek(FIXED_PREFIX_BYTES.toLong() + headerLength)
            val y = readPlane(raf, header.yBytes, buffer, digest)
            val u = readPlane(raf, header.uBytes, buffer, digest)
            val v = readPlane(raf, header.vBytes, buffer, digest)
            val actualDigest = digest.digest().toHex()
            require(actualDigest == header.payloadDigest) {
                "packed YUV digest mismatch"
            }
            return PackedFrame(header, y, u, v)
        }
    }

    /** Streams exactly [planeBytes] into a fresh array while updating [digest]. */
    private fun readPlane(
        raf: RandomAccessFile,
        planeBytes: Long,
        buffer: ByteArray,
        digest: java.security.MessageDigest
    ): ByteArray {
        require(planeBytes >= 0 && planeBytes <= Int.MAX_VALUE) { "plane length unsupported: $planeBytes" }
        val bytes = ByteArray(planeBytes.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val chunk = minOf(buffer.size, bytes.size - offset)
            raf.readFully(buffer, 0, chunk)
            digest.update(buffer, 0, chunk)
            System.arraycopy(buffer, 0, bytes, offset, chunk)
            offset += chunk
        }
        return bytes
    }

    /** Structural-only verification used on fast paths (no payload load). */
    fun verifyStructure(file: File): Boolean = try {
        readHeader(file)
        true
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        false
    }
}
