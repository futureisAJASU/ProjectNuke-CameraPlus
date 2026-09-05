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
 * U2.3-I2 FINAL 46×3 CLOSURE (SM-S921N, 23 JPEG + 23 native HEIF, test-owned only).
 *
 * Production default stays OFF; the test/debug override is enabled per fresh process only.
 * Invocations (host force-stops + proves process absent between each):
 *  1. closureSeed46   — create cohort, seed recovery, bounded retries to 46/46 evidence.
 *  2-4. closureColdRun — SAME test; true-cold unchanged: 46/46/0 + zero-write + timings.
 *  5. closureFallbacks — A unrelated-row, B JPEG corruption, C HEIF corruption.
 *  6. closureFinalSweep — D deletion, E missing/malformed, F/G seams, OFF proof, cleanup.
 */
@RunWith(AndroidJUnit4::class)
class U23Closure46Test {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val TAG = "U23CLOSURE"

    private fun closureRoot(): File {
        val pictures = requireNotNull(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES))
        return File(pictures, "U23Closure46/KeplerYuvFusion")
    }

    private fun manifestFile(): File = File(context.filesDir, "u23closure-manifest.json")

    private data class CJob(val jobDir: File, val uri: Uri, val format: OutputFormat)

    // ------------------------------------------------------------- invocation 1

    @Test
    fun closureSeed46() {
        U23FastPathGate.overrideForTest = true
        U23Counters.reset()
        U23Timings.reset()
        log("SEED46 device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        val root = closureRoot()
        assertTrue(root.mkdirs() || root.isDirectory)
        val jobs = mutableListOf<CJob>()
        try {
            repeat(46) { index ->
                val format = if (index % 2 == 0) OutputFormat.JPEG else OutputFormat.HEIF
                jobs.add(createClosureJob(root, index, format))
            }
            assertEquals(23, jobs.count { it.format == OutputFormat.JPEG })
            assertEquals(23, jobs.count { it.format == OutputFormat.HEIF })

            val t0 = SystemClock.elapsedRealtime()
            var report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            var passes = 1
            while (jobs.any { evidenced(it) == null } && passes < 8) {
                passes++
                report = KeplerRecoveryCoordinator.recoverRoots(
                    listOf(root), ContextMediaStoreExportRecoveryAccess(context)
                )
            }
            val seedMs = SystemClock.elapsedRealtime() - t0
            assertEquals(46, report.jobs.size)
            assertEquals(46, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            val missing = jobs.filter { evidenced(it) == null }.map { it.jobDir.name }
            assertTrue("46/46 evidence required (missing=$missing)", missing.isEmpty())
            val counters = U23Counters.snapshot()
            log("SEED46-DONE jobs=46 passes=$passes seedMs=$seedMs counters=$counters")
            persistManifest(jobs, JSONObject().put("seedMs", seedMs).put("seedPasses", passes))
        } catch (e: Throwable) {
            jobs.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
            jobs.forEach { runCatching { it.jobDir.deleteRecursively() } }
            runCatching { manifestFile().delete() }
            throw e
        }
    }

    // ------------------------------------------------------- invocations 2, 3, 4

    @Test
    fun closureColdRun() {
        U23FastPathGate.overrideForTest = true
        U23Counters.reset()
        U23Timings.reset()
        val root = closureRoot()
        val jobs = loadManifestJobs()
        assertEquals(46, jobs.size)
        val beforeBytes = jobs.associate { it.jobDir.name to dirFingerprints(it.jobDir) }
        val t0 = SystemClock.elapsedRealtime()
        val report = KeplerRecoveryCoordinator.recoverRoots(
            listOf(root), ContextMediaStoreExportRecoveryAccess(context)
        )
        val totalMs = SystemClock.elapsedRealtime() - t0
        val counters = U23Counters.snapshot()
        val timings = U23Timings.snapshot()
        log("COLDRUN report=${report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED }}/46 counters=$counters totalMs=$totalMs timings=${timingsMs(timings)}")
        assertEquals(46, report.jobs.size)
        assertEquals(46, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
        assertEquals(46, counters["cheapInspections"])
        assertEquals(46, counters["fastPathHits"])
        assertEquals(0, counters["fullVerifierRuns"])
        assertEquals(0, counters["fallbacks"])
        jobs.forEach { job ->
            assertEquals("zero-write: ${job.jobDir.name}", beforeBytes[job.jobDir.name], dirFingerprints(job.jobDir))
        }
        appendRunRecord(JSONObject()
            .put("totalMs", totalMs).put("counters", JSONObject(counters.mapValues { it.value }))
            .put("timingsMs", JSONObject(timingsMs(timings).mapValues { it.value })))
        log("COLDRUN-DONE totalMs=$totalMs")
    }

    // ------------------------------------------------------------- invocation 5

    @Test
    fun closureFallbacks() {
        U23FastPathGate.overrideForTest = true
        val root = closureRoot()
        val jobs = loadManifestJobs()
        assertEquals(46, jobs.size)
        // A: unrelated TEST-OWNED row change -> whole-cohort volume mismatch fallback.
        U23Counters.reset()
        val unrelated = createUnrelatedRow()
        try {
            val otherBytes = readBytes(unrelated)
            val mutated = otherBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
            writeBytes(unrelated, mutated)
            assertTrue(readBytes(unrelated).contentEquals(mutated))
            val reportA = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val countersA = U23Counters.snapshot()
            log("FB-A counters=$countersA")
            assertEquals(46, countersA["fullVerifierRuns"])
            assertEquals(46, countersA["fallback:VOLUME_GENERATION_MISMATCH"])
            assertEquals(46, reportA.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
        } finally {
            context.contentResolver.delete(unrelated, null, null)
            assertTrue(awaitAbsent(unrelated))
        }
        // B/C: proven same-size signature kills on one JPEG + one HEIF cohort row.
        U23Counters.reset()
        val jpegTarget = jobs.first { it.format == OutputFormat.JPEG }
        val heifTarget = jobs.first { it.format == OutputFormat.HEIF }
        corruptDeterministic(jpegTarget.uri, OutputFormat.JPEG)
        corruptDeterministic(heifTarget.uri, OutputFormat.HEIF)
        val reportBC = KeplerRecoveryCoordinator.recoverRoots(
            listOf(root), ContextMediaStoreExportRecoveryAccess(context)
        )
        val countersBC = U23Counters.snapshot()
        log("FB-BC counters=$countersBC")
        assertTrue((countersBC["fullVerifierRuns"] ?: 0) >= 2)
        listOf(jpegTarget to OutputFormat.JPEG, heifTarget to OutputFormat.HEIF).forEach { (target, _) ->
            val attemptId = journalAttempt(target)
            val direct = recoverMediaStoreExportJournals(target.jobDir, ContextMediaStoreExportRecoveryAccess(context))
            val main = direct.single { it.attemptId == attemptId }
            assertEquals(GalleryExportVerificationReason.SIGNATURE_INVALID, main.verificationDiagnosticReason)
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED, main.classification)
            val journal = MediaStoreExportJournal.list(target.jobDir).single { it.role == MediaStoreExportRole.MAIN_IMAGE }
            assertEquals(MediaStoreExportState.PUBLIC_COMMITTED, journal.state)
        }
        log("FB-BC-DONE jpegSigInvalid heifSigInvalid")
    }

    // ------------------------------------------------------------- invocation 6

    @Test
    fun closureFinalSweep() {
        U23FastPathGate.overrideForTest = true
        val root = closureRoot()
        val jobs = loadManifestJobs()
        try {
            // D: exact row deletion -> PUBLIC_RESULT_REMOVED.
            val deleteTarget = jobs.filter { it.format == OutputFormat.HEIF }[1]
            assertEquals(1, context.contentResolver.delete(deleteTarget.uri, null, null))
            assertTrue(awaitAbsent(deleteTarget.uri))
            val reportD = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val jobD = reportD.jobs.single { it.jobDir.name == deleteTarget.jobDir.name }
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, jobD.classification)
            assertTrue(jobD.actions.contains(MediaStoreExportRecoveryClassification.PUBLIC_RESULT_REMOVED.name))
            log("FB-D-DONE removed=${deleteTarget.uri}")

            // E: missing + malformed evidence on throwaway jobs in an isolated root.
            val eRoot = File(
                requireNotNull(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)),
                "U23ClosureE/KeplerYuvFusion"
            )
            assertTrue(eRoot.mkdirs() || eRoot.isDirectory)
            val eJobs = listOf(createClosureJob(eRoot, 0, OutputFormat.JPEG), createClosureJob(eRoot, 1, OutputFormat.HEIF))
            try {
                // Malform the second job's evidence block (journal itself stays valid).
                val malJournal = MediaStoreExportJournal.list(eJobs[1].jobDir)
                    .single { it.role == MediaStoreExportRole.MAIN_IMAGE }
                val malFile = MediaStoreExportJournal.fileFor(eJobs[1].jobDir, malJournal.exportAttemptId)
                val raw = JSONObject(malFile.readText())
                raw.put("verificationEvidence", JSONObject().put("schemaVersion", 1).put("bogus", true))
                malFile.writeText(raw.toString())
                U23Counters.reset()
                val reportE = KeplerRecoveryCoordinator.recoverRoots(
                    listOf(eRoot), ContextMediaStoreExportRecoveryAccess(context)
                )
                val countersE = U23Counters.snapshot()
                log("FB-E counters=$countersE")
                assertEquals(2, reportE.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
                assertTrue((countersE["fullVerifierRuns"] ?: 0) >= 2)
                assertEquals(1, countersE["fallback:NO_EVIDENCE"])
                assertEquals(1, countersE["fallback:MALFORMED_EVIDENCE"])
            } finally {
                eJobs.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
                eJobs.forEach { job ->
                    val absent = awaitAbsent(job.uri)
                    assertTrue("E throwaway URI must be authoritatively absent: ${job.uri}", absent)
                    runCatching { job.jobDir.deleteRecursively() }
                    assertTrue("E throwaway job dir must be removed: ${job.jobDir}", !job.jobDir.exists())
                }
                runCatching { eRoot.deleteRecursively() }
                assertTrue("E throwaway root must be removed: $eRoot", !eRoot.exists())
            }

            // F/G: boot + version mismatch via the TEST seam (throwaway root, real rows).
            // Each case gets its OWN job: a fake-read run may legitimately issue evidence
            // derived from fake reads, so jobs are never shared across fake-read cases.
            val fgRoot = File(
                requireNotNull(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)),
                "U23ClosureFG/KeplerYuvFusion"
            )
            assertTrue(fgRoot.mkdirs() || fgRoot.isDirectory)
            val real = AndroidU23MediaReads(context)
            try {
                val fJob = createClosureJob(fgRoot, 0, OutputFormat.JPEG)
                KeplerRecoveryCoordinator.recoverRoots(listOf(fgRoot), ContextMediaStoreExportRecoveryAccess(context))
                assertTrue(evidencedFg(fJob) != null)
                val badBoot = object : U23MediaReads by real {
                    override fun bootCount(): U23Read<Int> = U23Read.Value(-999)
                }
                U23Counters.reset()
                KeplerRecoveryCoordinator.recoverRoots(listOf(fgRoot), ContextMediaStoreExportRecoveryAccess(context, badBoot))
                assertEquals(1, U23Counters.snapshot()["fallback:BOOT_BOUNDARY"])
                assertTrue((U23Counters.snapshot()["fullVerifierRuns"] ?: 0) >= 1)
                runCatching { context.contentResolver.delete(fJob.uri, null, null) }
                runCatching { fJob.jobDir.deleteRecursively() }

                val gJob = createClosureJob(fgRoot, 1, OutputFormat.JPEG)
                KeplerRecoveryCoordinator.recoverRoots(listOf(fgRoot), ContextMediaStoreExportRecoveryAccess(context))
                assertTrue(evidencedFg(gJob) != null)
                val badVersion = object : U23MediaReads by real {
                    override fun providerState(volume: String): U23ProviderState =
                        U23ProviderState(U23Read.Value("bogus-version"), U23Read.Value(-1L))
                }
                U23Counters.reset()
                KeplerRecoveryCoordinator.recoverRoots(listOf(fgRoot), ContextMediaStoreExportRecoveryAccess(context, badVersion))
                assertEquals(1, U23Counters.snapshot()["fallback:MEDIASTORE_VERSION_MISMATCH"])
                assertTrue((U23Counters.snapshot()["fullVerifierRuns"] ?: 0) >= 1)
                runCatching { context.contentResolver.delete(gJob.uri, null, null) }
                runCatching { gJob.jobDir.deleteRecursively() }
                // Authoritative cleanup proof for F/G throwaway rows
                listOf(fJob, gJob).forEach { job ->
                    val absent = awaitAbsent(job.uri)
                    assertTrue("F/G throwaway URI must be authoritatively absent: ${job.uri}", absent)
                    runCatching { job.jobDir.deleteRecursively() }
                    assertTrue("F/G throwaway job dir must be removed: ${job.jobDir}", !job.jobDir.exists())
                }
                runCatching { fgRoot.deleteRecursively() }
                assertTrue("F/G throwaway root must be removed: $fgRoot", !fgRoot.exists())
                log("FB-FG-DONE bootBoundary versionMismatch")
            } finally {
                runCatching { fgRoot.deleteRecursively() }
            }

            // Default-OFF final proof on an intact cohort job: no U23 reads, full verifier
            // runs, no evidence issuance from default startup.
            U23FastPathGate.overrideForTest = false
            U23Counters.reset()
            val intact = jobs.filter { it.format == OutputFormat.JPEG }[1]
            val intactBefore = dirFingerprints(intact.jobDir)
            val reportOff = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val countersOff = U23Counters.snapshot()
            log("OFFPROOF counters=$countersOff")
            assertEquals(0, countersOff["cheapInspections"])
            assertEquals(0, countersOff["fastPathHits"])
            assertTrue((countersOff["fullVerifierRuns"] ?: 0) > 0)
            assertEquals(intactBefore, dirFingerprints(intact.jobDir))
            assertTrue(reportOff.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED } >= 40)
            log("OFFPROOF-DONE gateOffFullVerify")
        } finally {
            // Exact cleanup: every manifest row asserted absent, every job dir removed.
            jobs.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
            jobs.forEach {
                val absent = awaitAbsent(it.uri)
                Log.d(TAG, "CLEANUP uri=${it.uri} absent=$absent")
                assertTrue("closure row must be absent: ${it.uri}", absent)
                runCatching { it.jobDir.deleteRecursively() }
                assertTrue("closure job dir must be removed: ${it.jobDir}", !it.jobDir.exists())
            }
            runCatching { root.deleteRecursively() }
            runCatching { manifestFile().delete() }
            U23FastPathGate.overrideForTest = false
            Log.d(TAG, "CLEANUP-DONE")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun createClosureJob(root: File, index: Int, format: OutputFormat): CJob {
        val jobDir = File(root, "KPL_YUV_FUSION_U23C46_${index}_${UUID.randomUUID()}")
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
        val bitmap = deterministicBitmap(128, 128, index)
        try {
            val export = exportNightFusionBitmapToGallery(
                context = context, bitmap = bitmap,
                displayNameBase = "u23c46-$index-${UUID.randomUUID()}",
                requestedFormat = format, relativeAlbumPath = "Pictures/KeplerU23Closure",
                quality = 92, cancellation = NoOpKeplerPipelineCancellation, jobDir = jobDir
            )
            assertTrue("closure export($format) must succeed: $export", export.success)
            assertEquals(GalleryExportCommitState.VERIFIED, export.publicCommitState)
            assertEquals(format, export.formatUsed)
            updateExportMetadata(jobDir, export, verified = true, finalOutputFormat = finalFormat)
            KeplerJobMetadata.update(jobDir) {
                it.put("status", "COMPLETE").put("processStatus", "PIPELINE_COMPLETE")
            }
            KeplerJobMetadata.findOperationLease(jobDir)?.let { KeplerJobMetadata.releaseOperation(it) }
            return CJob(jobDir, Uri.parse(requireNotNull(export.uriString)), export.formatUsed)
        } finally {
            bitmap.recycle()
        }
    }

    private fun evidenced(job: CJob): U23VerificationEvidence? =
        MediaStoreExportJournal.list(job.jobDir)
            .single { it.role == MediaStoreExportRole.MAIN_IMAGE }
            .verificationEvidence

    private fun evidencedFg(job: CJob): U23VerificationEvidence? = evidenced(job)

    private fun journalAttempt(job: CJob): String =
        MediaStoreExportJournal.list(job.jobDir).single { it.role == MediaStoreExportRole.MAIN_IMAGE }.exportAttemptId

    private fun persistManifest(jobs: List<CJob>, seed: JSONObject) {
        val json = JSONObject()
            .put("jobs", JSONArray(jobs.map {
                JSONObject().put("dir", it.jobDir.absolutePath).put("uri", it.uri.toString()).put("format", it.format.name)
            }))
            .put("seed", seed)
            .put("runs", JSONArray())
        manifestFile().writeText(json.toString())
        log("MANIFEST jobs=${jobs.size}")
    }

    private fun loadManifestJobs(): List<CJob> {
        val json = JSONObject(manifestFile().readText())
        val arr = json.getJSONArray("jobs")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CJob(File(o.getString("dir")), Uri.parse(o.getString("uri")), OutputFormat.valueOf(o.getString("format")))
        }
    }

    private fun appendRunRecord(record: JSONObject) {
        val json = JSONObject(manifestFile().readText())
        json.getJSONArray("runs").put(record)
        manifestFile().writeText(json.toString())
    }

    private fun dirFingerprints(jobDir: File): List<Pair<String, String>> =
        jobDir.listFiles()?.sortedBy { it.name }?.map { it.name to sha256(it.readBytes()) } ?: emptyList()

    private fun timingsMs(t: Map<String, Long>): Map<String, Double> = buildMap {
        val rowN = t["rowQueries"] ?: 1L
        val verN = t["versionQueries"] ?: 1L
        val genN = t["generationQueries"] ?: 1L
        val predN = t["predicates"] ?: 1L
        put("rowQueryAvgMs", (t["rowQueryNanos"] ?: 0L) / 1e6 / rowN)
        put("versionAvgMs", (t["versionQueryNanos"] ?: 0L) / 1e6 / verN)
        put("generationAvgMs", (t["generationQueryNanos"] ?: 0L) / 1e6 / genN)
        put("predicateAvgMs", (t["predicateNanos"] ?: 0L) / 1e6 / predN)
        put("rowTotalMs", (t["rowQueryNanos"] ?: 0L) / 1e6)
        put("versionTotalMs", (t["versionQueryNanos"] ?: 0L) / 1e6)
        put("generationTotalMs", (t["generationQueryNanos"] ?: 0L) / 1e6)
    }

    private fun createUnrelatedRow(): Uri {
        val bitmap = deterministicBitmap(32, 32, 99)
        try {
            val export = exportNightFusionBitmapToGallery(
                context = context, bitmap = bitmap,
                displayNameBase = "u23c46-unrelated-${UUID.randomUUID()}",
                requestedFormat = OutputFormat.JPEG, relativeAlbumPath = "Pictures/KeplerU23Closure",
                quality = 92, cancellation = NoOpKeplerPipelineCancellation, jobDir = null
            )
            assertTrue(export.success)
            return Uri.parse(requireNotNull(export.uriString))
        } finally {
            bitmap.recycle()
        }
    }

    private fun corruptDeterministic(uri: Uri, format: OutputFormat) {
        val orig = readBytes(uri)
        val killed = orig.copyOf()
        when (format) {
            OutputFormat.JPEG -> {
                assertTrue(orig[0] == 0xFF.toByte() && orig[1] == 0xD8.toByte())
                killed[0] = 0x00; killed[1] = 0x00; killed[2] = 0x00
            }
            OutputFormat.HEIF -> {
                assertEquals("ftyp", orig.copyOfRange(4, 8).toString(Charsets.US_ASCII))
                killed[4] = 'X'.code.toByte(); killed[5] = 'X'.code.toByte()
                killed[6] = 'X'.code.toByte(); killed[7] = 'X'.code.toByte()
            }
            else -> throw AssertionError("unexpected $format")
        }
        writeBytes(uri, killed)
        val readback = readBytes(uri)
        assertEquals(orig.size, readback.size)
        assertTrue("SHA must prove $format corruption", sha256(orig) != sha256(readback))
        assertTrue(readback.contentEquals(killed))
    }

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
