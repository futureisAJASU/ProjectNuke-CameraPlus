package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U2.3-I1 BOUNDED DEVICE PILOT (SM-S921N, 3 JPEG + 3 native HEIF, test-owned only).
 *
 * Runs in THREE separate instrumentation invocations with force-stop between
 * (host-driven true process-cold):
 *  1. pilotSeed        — create cohort, recovery with override ON: 6 FULL verifies, evidence issued.
 *  2. pilotUnchanged   — recovery only: 6 cheap inspections, 6 fast-path hits, 0 verifier, 0 writes.
 *  3. pilotMutations   — unrelated-row fallback (C), same-size corruption (D),
 *                        deletion (E), then full cleanup.
 *
 * Production default gate stays OFF in committed code; the override is set in the test
 * process only (same process as recovery under instrumentation).
 */
@RunWith(AndroidJUnit4::class)
class U23FastPathPilotTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val TAG = "U23PILOT"

    private fun pilotRoot(): File {
        val pictures = requireNotNull(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES))
        return File(pictures, "U23Pilot/KeplerYuvFusion")
    }

    private fun stateFile(): File = File(context.filesDir, "u23pilot-state.json")

    // ------------------------------------------------------------ invocation 1

    @Test
    fun pilotSeed() {
        U23FastPathGate.overrideForTest = true
        U23Counters.reset()
        log("SEED device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} gate=${U23FastPathGate.isEnabled()}")
        val root = pilotRoot()
        assertTrue(root.mkdirs() || root.isDirectory)
        val jobs = mutableListOf<PilotJob>()
        try {
            repeat(6) { index ->
                val format = if (index % 2 == 0) OutputFormat.JPEG else OutputFormat.HEIF
                jobs.add(createPilotJob(root, index, format))
            }
            assertEquals(3, jobs.count { it.format == OutputFormat.JPEG })
            assertEquals(3, jobs.count { it.format == OutputFormat.HEIF })

            val t0 = SystemClock.elapsedRealtime()
            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val fullMs = SystemClock.elapsedRealtime() - t0
            assertEquals(6, report.jobs.size)
            assertEquals(6, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            var counters = U23Counters.snapshot()
            log("SEED report=${report.jobs.map { it.classification }} counters=$counters fullMs=$fullMs")
            assertEquals(0, counters["fastPathHits"])
            assertEquals(6, counters["fullVerifierRuns"])
            // Stable evidence issues only from quiet windows; background volume movement may
            // void one window (fail-closed, by design). Retry bounded passes until all 6 carry
            // evidence — already-evidenced jobs fast-path hit on later passes.
            var passes = 1
            while (jobs.any { evidenced(it) == null } && passes < 4) {
                passes++
                val retry = KeplerRecoveryCoordinator.recoverRoots(
                    listOf(root), ContextMediaStoreExportRecoveryAccess(context)
                )
                assertEquals(6, retry.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
                counters = U23Counters.snapshot()
                log("SEED-RETRY pass=$passes counters=$counters")
            }
            val missing = jobs.filter { evidenced(it) == null }.map { it.jobDir.name }
            assertTrue("seed must issue evidence for all 6 (missing=$missing) counters=$counters", missing.isEmpty())
            persistState(jobs, mapOf("seedFullMs" to fullMs))
            log("SEED-DONE jobs=6 fullVerifier=6 evidence=6 fullMs=$fullMs")
        } catch (e: Throwable) {
            // Never strand pilot rows on seed failure: remove everything created here.
            jobs.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
            jobs.forEach { runCatching { it.jobDir.deleteRecursively() } }
            runCatching { stateFile().delete() }
            throw e
        }
    }

    // ------------------------------------------------------------ invocation 2

    @Test
    fun pilotUnchanged() {
        U23FastPathGate.overrideForTest = true
        U23Counters.reset()
        val root = pilotRoot()
        val jobs = loadState()
        assertEquals(6, jobs.size)
        val beforeBytes = jobs.associate { it.jobDir.name to dirBytes(it.jobDir) }
        val t0 = SystemClock.elapsedRealtime()
        val report = KeplerRecoveryCoordinator.recoverRoots(
            listOf(root), ContextMediaStoreExportRecoveryAccess(context)
        )
        val fastMs = SystemClock.elapsedRealtime() - t0
        val counters = U23Counters.snapshot()
        log("UNCHANGED report=${report.jobs.map { it.classification }} counters=$counters fastMs=$fastMs")
        assertEquals(6, report.jobs.size)
        assertEquals(6, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
        assertEquals(6, counters["cheapInspections"])
        assertEquals(6, counters["fastPathHits"])
        assertEquals(0, counters["fullVerifierRuns"])
        assertEquals(0, counters["fallbacks"])
        jobs.forEach { job ->
            assertEquals(
                "zero-write: ${job.jobDir.name} must be byte-identical",
                beforeBytes[job.jobDir.name], dirBytes(job.jobDir)
            )
        }
        // Semantic results unchanged vs seed (all RECOVERED).
        persistState(jobs, mapOf("fastMs" to fastMs))
        log("UNCHANGED-DONE hits=6 verifier=0 writes=0 fastMs=$fastMs")
    }

    // ------------------------------------------------------------ invocation 3

    @Test
    fun pilotMutations() {
        U23FastPathGate.overrideForTest = true
        U23Counters.reset()
        val root = pilotRoot()
        val jobs = loadState()
        assertEquals(6, jobs.size)
        try {
            // C: unrelated TEST-OWNED row mutation -> coarse fallback for the whole cohort.
            U23Counters.reset()
            val unrelated = createUnrelatedRow()
            try {
                val otherBytes = readBytes(unrelated)
                val mutated = otherBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
                writeBytes(unrelated, mutated)
                assertTrue(readBytes(unrelated).contentEquals(mutated))
                val reportC = KeplerRecoveryCoordinator.recoverRoots(
                    listOf(root), ContextMediaStoreExportRecoveryAccess(context)
                )
                val countersC = U23Counters.snapshot()
                log("MUTATE-C report=${reportC.jobs.map { it.classification }} counters=$countersC")
                assertEquals(6, countersC["fullVerifierRuns"])
                assertEquals(6, countersC["fallback:VOLUME_GENERATION_MISMATCH"])
                assertEquals(6, reportC.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            } finally {
                context.contentResolver.delete(unrelated, null, null)
                assertTrue(awaitAbsent(unrelated))
            }

            // D: exact-target same-size signature kill (first JPEG job).
            U23Counters.reset()
            val targetJpeg = jobs.first { it.format == OutputFormat.JPEG }
            val origBytes = readBytes(targetJpeg.uri)
            val killed = origBytes.copyOf().also { it[0] = 0x00; it[1] = 0x00; it[2] = 0x00 }
            writeBytes(targetJpeg.uri, killed)
            val readback = readBytes(targetJpeg.uri)
            assertEquals(origBytes.size, readback.size)
            assertTrue("D: readback SHA must prove mutation", sha256(origBytes) != sha256(readback))
            assertTrue(readback.contentEquals(killed))
            KeplerRecoveryCoordinator.recoverRoots(listOf(root), ContextMediaStoreExportRecoveryAccess(context))
            val countersD = U23Counters.snapshot()
            val journalD = MediaStoreExportJournal.list(targetJpeg.jobDir)
                .single { it.role == MediaStoreExportRole.MAIN_IMAGE }
            log("MUTATE-D journalState=${journalD.state} counters=$countersD")
            assertEquals(MediaStoreExportState.PUBLIC_COMMITTED, journalD.state)
            assertTrue((countersD["fullVerifierRuns"] ?: 0) >= 1)

            // E: exact-target deletion (first HEIF job).
            U23Counters.reset()
            val targetHeif = jobs.first { it.format == OutputFormat.HEIF }
            assertEquals(1, context.contentResolver.delete(targetHeif.uri, null, null))
            assertTrue(awaitAbsent(targetHeif.uri))
            val reportE = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val jobE = reportE.jobs.single { it.jobDir.name == targetHeif.jobDir.name }
            log("MUTATE-E classification=${jobE.classification} actions=${jobE.actions}")
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, jobE.classification)
            assertTrue(jobE.actions.contains(MediaStoreExportRecoveryClassification.PUBLIC_RESULT_REMOVED.name))
            log("MUTATIONS-DONE")
        } finally {
            // Full hygiene: every pilot row + job dir + state, asserted absent/removed.
            jobs.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
            jobs.forEach { uri ->
                val absent = awaitAbsent(uri.uri)
                Log.d(TAG, "CLEANUP uri=${uri.uri} absent=$absent")
                assertTrue("pilot row must be absent: ${uri.uri}", absent)
            }
            jobs.forEach { runCatching { it.jobDir.deleteRecursively() } }
            jobs.forEach { assertTrue("pilot job dir must be removed: ${it.jobDir}", !it.jobDir.exists()) }
            runCatching { root.delete() }
            runCatching { stateFile().delete() }
            U23FastPathGate.overrideForTest = false
            Log.d(TAG, "CLEANUP-DONE")
        }
    }

    // ---------------------------------------------------------------- helpers

    private data class PilotJob(val jobDir: File, val uri: Uri, val format: OutputFormat)

    private fun evidenced(job: PilotJob): U23VerificationEvidence? =
        MediaStoreExportJournal.list(job.jobDir)
            .single { it.role == MediaStoreExportRole.MAIN_IMAGE }
            .verificationEvidence

    private fun createPilotJob(root: File, index: Int, format: OutputFormat): PilotJob {
        val jobDir = File(root, "KPL_YUV_FUSION_U23P1_${index}_${UUID.randomUUID()}")
        assertTrue(jobDir.mkdirs())
        val finalFormat = if (format == OutputFormat.JPEG) FinalOutputFormat.JPEG else FinalOutputFormat.HEIF
        KeplerJobMetadata.write(
            jobDir,
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "PROCESSING")
                .put("processStatus", "PROCESSING")
                .put("currentPipelineStage", "PROCESSING")
                .put("recoveryState", "STABLE")
                .put("createdAt", System.currentTimeMillis())
        )
        val bitmap = deterministicBitmap(64, 64, index)
        try {
            val export = exportNightFusionBitmapToGallery(
                context = context, bitmap = bitmap,
                displayNameBase = "u23pilot-$index-${UUID.randomUUID()}",
                requestedFormat = format, relativeAlbumPath = "Pictures/KeplerU23Pilot",
                quality = 92, cancellation = NoOpKeplerPipelineCancellation, jobDir = jobDir
            )
            assertTrue("pilot export($format) must succeed: $export", export.success)
            assertEquals(GalleryExportCommitState.VERIFIED, export.publicCommitState)
            assertEquals(format, export.formatUsed)
            updateExportMetadata(jobDir, export, verified = true, finalOutputFormat = finalFormat)
            KeplerJobMetadata.update(jobDir) {
                it.put("status", "COMPLETE").put("processStatus", "PIPELINE_COMPLETE")
            }
            KeplerJobMetadata.findOperationLease(jobDir)?.let { KeplerJobMetadata.releaseOperation(it) }
            val uri = Uri.parse(requireNotNull(export.uriString))
            return PilotJob(jobDir, uri, export.formatUsed)
        } finally {
            bitmap.recycle()
        }
    }

    private fun createUnrelatedRow(): Uri {
        val bitmap = deterministicBitmap(32, 32, 99)
        try {
            val export = exportNightFusionBitmapToGallery(
                context = context, bitmap = bitmap,
                displayNameBase = "u23pilot-unrelated-${UUID.randomUUID()}",
                requestedFormat = OutputFormat.JPEG, relativeAlbumPath = "Pictures/KeplerU23Pilot",
                quality = 92, cancellation = NoOpKeplerPipelineCancellation, jobDir = null
            )
            assertTrue("unrelated export must succeed", export.success)
            return Uri.parse(requireNotNull(export.uriString))
        } finally {
            bitmap.recycle()
        }
    }

    private fun persistState(jobs: List<PilotJob>, timing: Map<String, Long>) {
        val json = JSONObject()
            .put("jobs", JSONArray(jobs.map { JSONObject().put("dir", it.jobDir.absolutePath).put("uri", it.uri.toString()).put("format", it.format.name) }))
            .put("timing", JSONObject(timing.mapValues { it.value }))
        stateFile().writeText(json.toString())
        log("STATE path=${stateFile().absolutePath} jobs=${jobs.size} timing=$timing")
    }

    private fun loadState(): List<PilotJob> {
        val json = JSONObject(stateFile().readText())
        val arr = json.getJSONArray("jobs")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PilotJob(File(o.getString("dir")), Uri.parse(o.getString("uri")), OutputFormat.valueOf(o.getString("format")))
        }
    }

    // Content-based fingerprint: NEVER compare ByteArray with == (referential).
    private fun dirBytes(jobDir: File): List<Pair<String, String>> =
        jobDir.listFiles()?.sortedBy { it.name }?.map { it.name to sha256(it.readBytes()) } ?: emptyList()

    private fun readBytes(uri: Uri) =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("null stream $uri")

    private fun writeBytes(uri: Uri, payload: ByteArray) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rwt") ?: error("null PFD $uri")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { fos -> fos.write(payload); fos.flush(); fos.fd.sync() }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // Tri-state: 1 = present, 0 = authoritative absent, -1 = query failed.
    // Only authoritative absent converges; failures keep waiting until the bound.
    private fun awaitAbsent(uri: Uri, timeoutMs: Long = 8000L): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val state = try {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { if (it.moveToFirst()) 1 else 0 } ?: -1
            } catch (_: Exception) {
                -1
            }
            if (state == 0) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun log(msg: String) = Log.d(TAG, msg)

    private fun deterministicBitmap(w: Int, h: Int, seed: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            for (y in 0 until h) for (x in 0 until w) {
                it.setPixel(x, y, android.graphics.Color.argb(255, (x * 4 + seed) % 256, (y * 4 + seed * 3) % 256, ((x + y) * 2 + seed * 5) % 256))
            }
        }
}
