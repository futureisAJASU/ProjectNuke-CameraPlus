package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * U2.3 - SM-S921N MEDIASTORE CHANGE-SIGNAL + VERIFIER CHARACTERIZATION
 */
@RunWith(AndroidJUnit4::class)
class U23MediaStoreCharacterizationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val TAG = "U23Char"

    @Test
    fun characterizeMediaStoreSignals() {
        log("START device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        
        val rows = mutableListOf<Row>()
        
        try {
            repeat(5) { i -> rows.add(createTestRow("u23-jpeg-$i-${UUID.randomUUID()}.jpg")) }
            log("COHORT: ${rows.size} JPEG rows")
            
            // A: Unchanged control
            val beforeGen = rows[0].snapshot.generationModified
            val beforeVer = verifyGalleryExportResult(context, rows[0].uri.toString(), GalleryExportExpectation(OutputFormat.JPEG, 64, 64))
            Thread.sleep(100)
            val after = querySnapshot(rows[0].uri)
            val afterVer = verifyGalleryExportResult(context, rows[0].uri.toString(), GalleryExportExpectation(OutputFormat.JPEG, 64, 64))
            log("UNCHANGED-A: gen=$beforeGen->${after.generationModified} size=${rows[0].snapshot.size}->${after.size} verified=${beforeVer is GalleryExportVerification.Verified && afterVer is GalleryExportVerification.Verified}")
            
            // B: Metadata update
            val beforeB = querySnapshot(rows[1].uri)
            assertEquals(1, context.contentResolver.update(rows[1].uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "renamed_${rows[1].displayName}") }, null, null))
            val afterB = querySnapshot(rows[1].uri)
            val afterVerB = verifyGalleryExportResult(context, rows[1].uri.toString(), GalleryExportExpectation(OutputFormat.JPEG, 64, 64))
            log("METADATA-B: gen=${beforeB.generationModified}->${afterB.generationModified} name=${beforeB.displayName}->${afterB.displayName} verified=${afterVerB is GalleryExportVerification.Verified}")
            
            // C: Pending transition
            val beforeC = querySnapshot(rows[2].uri)
            assertEquals(1, context.contentResolver.update(rows[2].uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null))
            val pendingC = querySnapshot(rows[2].uri)
            assertEquals(1, context.contentResolver.update(rows[2].uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val afterC = querySnapshot(rows[2].uri)
            log("PENDING-C: IS_PENDING=${beforeC.isPending}->${pendingC.isPending}->${afterC.isPending} gen=${beforeC.generationModified}->${pendingC.generationModified}->${afterC.generationModified}")
            
            // D: Same-size replacement
            val beforeD = querySnapshot(rows[3].uri)
            val beforeBytes = context.contentResolver.openInputStream(rows[3].uri)?.readBytes()!!
            val beforeVerD = verifyGalleryExportResult(context, rows[3].uri.toString(), GalleryExportExpectation(OutputFormat.JPEG, 64, 64))
            val modified = beforeBytes.mapIndexed { i, b -> if (i % 10 == 0) (b.toInt() xor 0xFF).toByte() else b }.toByteArray()
            context.contentResolver.openOutputStream(rows[3].uri, "rwt")?.use { it.write(modified) }
            val afterD = querySnapshot(rows[3].uri)
            val afterVerD = verifyGalleryExportResult(context, rows[3].uri.toString(), GalleryExportExpectation(OutputFormat.JPEG, 64, 64))
            log("SAME-SIZE-D: size=${beforeD.size}->${afterD.size} gen=${beforeD.generationModified}->${afterD.generationModified} beforeVer=${beforeVerD is GalleryExportVerification.Verified} afterVer=${afterVerD is GalleryExportVerification.PermanentFailure}")
            
            // E: Different-size replacement
            val beforeE = querySnapshot(rows[4].uri)
            val smallBytes = deterministicBitmap(32, 32).let { 
                val s = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.JPEG, 90, s); it.recycle(); s.toByteArray() 
            }
            context.contentResolver.openOutputStream(rows[4].uri, "rwt")?.use { it.write(smallBytes) }
            val afterE = querySnapshot(rows[4].uri)
            val afterVerE = verifyGalleryExportResult(context, rows[4].uri.toString(), GalleryExportExpectation(OutputFormat.JPEG, 32, 32))
            log("DIFF-SIZE-E: size=${beforeE.size}->${afterE.size} gen=${beforeE.generationModified}->${afterE.generationModified} verified=${afterVerE is GalleryExportVerification.Verified}")
            
            // F: Deletion
            val beforeVerF = verifyGalleryExportResult(context, rows[4].uri.toString(), GalleryExportExpectation(OutputFormat.JPEG, 32, 32))
            assertEquals(1, context.contentResolver.delete(rows[4].uri, null, null))
            val exists = context.contentResolver.query(rows[4].uri, arrayOf(MediaStore.MediaColumns._ID), null, null)?.use { it.count } ?: 0
            log("DELETION-F: beforeVer=${beforeVerF is GalleryExportVerification.Verified} existsAfterDelete=${exists == 0}")
            
            // Timing
            val jpegTimes = (1..10).map {
                val start = System.nanoTime()
                verifyGalleryExportResult(context, rows[0].uri.toString(), GalleryExportExpectation(null, null, null))
                System.nanoTime() - start
            }.sorted()
            log("TIMING-JPEG: median=${jpegTimes[5]/1e6}ms min=${jpegTimes.first()/1e6}ms max=${jpegTimes.last()/1e6}ms")
            
        } finally {
            rows.forEach { try { context.contentResolver.delete(it.uri, null, null) } catch(e: Exception) {} }
            log("CLEANUP: completed")
        }
    }
    
    private fun log(msg: String) = Log.d(TAG, msg)
    
    private fun createTestRow(displayName: String): Row {
        val bitmap = deterministicBitmap(64, 64)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/KeplerU23Char")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Insert failed")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bitmapToJpegBytes(bitmap)) }
        assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        bitmap.recycle()
        return Row(uri, querySnapshot(uri))
    }
    
    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val s = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, s)
        return s.toByteArray()
    }
    
    private fun querySnapshot(uri: Uri): Snapshot {
        val base = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
        val proj = if (Build.VERSION.SDK_INT >= 30) base + MediaStore.MediaColumns.GENERATION_MODIFIED else base
        val cursor = context.contentResolver.query(uri, proj, null, null, null) ?: error("Null cursor")
        return try {
            if (!cursor.moveToFirst()) error("Empty cursor")
            Snapshot(cursor.getLong(0), cursor.getInt(1), cursor.getLong(2), cursor.getLong(3),
                cursor.getString(4), cursor.getString(5), cursor.getInt(6), cursor.getInt(7),
                if (Build.VERSION.SDK_INT >= 30) cursor.getLong(8) else null)
        } finally { cursor.close() }
    }
    
    private fun deterministicBitmap(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        for (y in 0 until h) for (x in 0 until w) it.setPixel(x, y, android.graphics.Color.argb(255, (x*4)%256, (y*4)%256, ((x+y)*2)%256))
    }
    
    data class Row(val uri: Uri, val snapshot: Snapshot) { val displayName get() = snapshot.displayName }
    data class Snapshot(val id: Long, val isPending: Int, val size: Long, val dateModified: Long,
        val mimeType: String?, val displayName: String?, val width: Int, val height: Int, val generationModified: Long?)
}
