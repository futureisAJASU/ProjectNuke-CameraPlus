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
 * U2.3-I3 VALIDATED-TARGET DEFAULT-PATH PRODUCTION CANARY (SM-S921N, API 37).
 *
 * The test/debug override is NEVER set here (UNSET). The gate is ON solely via the
 * production rollout policy [U23RolloutPolicy] on the validated target. If this test
 * runs on a non-canary device, the policy is OFF and the seed assertion fails fast.
 *
 * Uses ONE new exact test-owned 6-job cohort (3 JPEG + 3 native HEIF) via the production
 * exporter. Invocations (host force-stops + proves process absent between each):
 *   1. i3Seed6        — (A) fresh cohort: 6 FULL, valid evidence issued for all 6.
 *   2. i3Stabilize    — (B0) quiet stable evidence cut (re-issue if drifted); separate process.
 *   3. i3ColdHit      — (B1) true process-cold FIRST and ONLY recovery: 6 hits, zero-write.
 *   4. i3GenMismatch  — (C) unrelated test-owned mutation: 6 FULL volume-gen fallback.
 *   5. i3JpegSigKill  — (D) same-size JPEG signature kill: reject, FULL, SIGNATURE_INVALID.
 *   6. i3HeifFtypKill — (E) same-size HEIF ftyp kill: same required result.
 *   7. i3ExactDelete  — (F) exact deletion: PUBLIC_RESULT_REMOVED.
 *   8. i3FinalSweep   — exact cleanup of this cohort + auxiliary rows.
 */
@RunWith(AndroidJUnit4::class)
class U23I3ProductionCanaryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val TAG = "U23I3CANARY"

    private fun i3Root(): File {
        val pictures = requireNotNull(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES))
        return File(pictures, "U23I3Canary/KeplerYuvFusion")
    }

    private fun manifestFile(): File = File(context.filesDir, "u23i3-manifest.json")

    /** Test-only per-invocation handoff consumed by the host orchestrator. */
    private fun resultFile(): File = File(context.filesDir, "u23i3-last-run.json")

    private data class I3Job(val jobDir: File, val uri: Uri, val format: OutputFormat)

    private fun requirePolicyGate() {
        U23FastPathGate.testOverride = U23TestOverride.UNSET
        U23Counters.reset()
        U23Timings.reset()
        assertTrue(
            "I3 pilot requires production policy ON on validated target (no override); " +
                "env=${U23RolloutPolicy.currentEnvironment()}",
            U23FastPathGate.isEnabled()
        )
    }

    private fun baseResult(runId: String, mode: String): JSONObject = JSONObject()
        .put("runId", runId)
        .put("mode", mode)
        .put("policyEnabled", U23FastPathGate.isEnabled())
        .put("testOverride", U23FastPathGate.testOverride.name)

    // ------------------------------------------------------------- invocation 1 (A)

    @Test
    fun i3Seed6() {
        requirePolicyGate()
        log("I3-SEED6 device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} gate=${U23FastPathGate.isEnabled()}")
        val root = i3Root()
        assertTrue(root.mkdirs() || root.isDirectory)
        val jobs = mutableListOf<I3Job>()
        try {
            repeat(6) { index ->
                val format = if (index % 2 == 0) OutputFormat.JPEG else OutputFormat.HEIF
                jobs.add(createI3Job(root, index, format))
            }
            assertEquals(3, jobs.count { it.format == OutputFormat.JPEG })
            assertEquals(3, jobs.count { it.format == OutputFormat.HEIF })

            // Scenario A: fresh cohort, no evidence -> every job cheap-inspected, missed
            // (NO_EVIDENCE), full-verified on the FIRST pass. Deterministic for fresh evidence.
            val t0 = SystemClock.elapsedRealtime()
            var report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val firstCounters = U23Counters.snapshot()
            assertEquals(6, report.jobs.size)
            assertEquals(6, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            assertEquals(6, firstCounters["cheapInspections"])
            assertEquals(0, firstCounters["fastPathHits"])
            assertEquals(6, firstCounters["fullVerifierRuns"])
            // All 6 fresh misses are NO_EVIDENCE (deterministic: stored evidence is Absent,
            // so the predicate short-circuits). The total may carry one systematic extra
            // evaluation from the issuance path, so require >= 6, not exactly 6.
            assertEquals(6, firstCounters["fallback:NO_EVIDENCE"])
            assertTrue((firstCounters["fallbacks"] ?: 0) >= 6)
            // Bounded retries to 6/6 valid evidence (already-evidenced jobs hit on later passes).
            var passes = 1
            while (jobs.any { evidenced(it) == null } && passes < 8) {
                passes++
                report = KeplerRecoveryCoordinator.recoverRoots(
                    listOf(root), ContextMediaStoreExportRecoveryAccess(context)
                )
            }
            val seedMs = SystemClock.elapsedRealtime() - t0
            assertEquals(6, report.jobs.size)
            assertEquals(6, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            val missing = jobs.filter { evidenced(it) == null }.map { it.jobDir.name }
            assertTrue("6/6 evidence required (missing=$missing)", missing.isEmpty())
            val counters = U23Counters.snapshot()
            log("I3-SEED6-DONE jobs=6 passes=$passes seedMs=$seedMs first=$firstCounters final=$counters")
            persistManifest(jobs, JSONObject().put("seedMs", seedMs).put("seedPasses", passes))
            writeResult(baseResult("i3Seed6", "SEED")
                .put("totalMs", seedMs)
                .put("seedPasses", passes)
                .put("recovered", 6)
                .put("jobsTotal", 6)
                .put("jpeg", 3)
                .put("heif", 3)
                .put("firstPassCounters", JSONObject(firstCounters))
                .put("counters", JSONObject(counters)))
        } catch (e: Throwable) {
            jobs.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
            jobs.forEach { runCatching { it.jobDir.deleteRecursively() } }
            runCatching { manifestFile().delete() }
            throw e
        }
    }

    // ------------------------------------------------------------- invocation 2 (B0)

    @Test
    fun i3Stabilize() {
        requirePolicyGate()
        val root = i3Root()
        val jobs = loadManifestJobs()
        assertEquals(6, jobs.size)
        // Stabilization ONLY: prepare a quiet stable evidence cut for the true-cold hit.
        // Re-issues evidence if the volume generation drifted. Requires all 6 currently
        // valid/evidenced. This is a separate exited process; the cold hit below runs fresh.
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
        val totalMs = SystemClock.elapsedRealtime() - t0
        assertEquals(6, report.jobs.size)
        assertEquals(6, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
        val missing = jobs.filter { evidenced(it) == null }.map { it.jobDir.name }
        assertTrue("stabilize requires 6/6 currently valid evidence (missing=$missing)", missing.isEmpty())
        val counters = U23Counters.snapshot()
        log("i3Stabilize-DONE jobs=6 passes=$passes totalMs=$totalMs counters=$counters")
        writeResult(baseResult("i3Stabilize", "STABILIZE")
            .put("totalMs", totalMs)
            .put("passes", passes)
            .put("recovered", 6)
            .put("jobsTotal", 6)
            .put("counters", JSONObject(counters)))
    }

    // ------------------------------------------------------------- invocation 3 (B1)

    @Test
    fun i3ColdHit() {
        requirePolicyGate()
        val root = i3Root()
        val jobs = loadManifestJobs()
        assertEquals(6, jobs.size)
        // Allowed before timing: manifest load, fingerprints, policy/UNSET asserts above.
        // NO stabilize/warmup/preflight/refresh recovery before timing. EXACTLY ONE
        // recoverRoots below; its wall time is the authoritative cold-hit timing.
        val beforeBytes = jobs.associate { it.jobDir.name to dirFingerprints(it.jobDir) }
        var recoveriesExecuted = 0
        val t0 = SystemClock.elapsedRealtime()
        recoveriesExecuted++
        val report = KeplerRecoveryCoordinator.recoverRoots(
            listOf(root), ContextMediaStoreExportRecoveryAccess(context)
        )
        val totalMs = SystemClock.elapsedRealtime() - t0
        val counters = U23Counters.snapshot()
        val timings = U23Timings.snapshot()
        log("i3ColdHit report=${report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED }}/6 counters=$counters totalMs=$totalMs recoveries=$recoveriesExecuted")
        try {
            // Scenario B: the FIRST and ONLY recovery in this fresh process must hit 6/6.
            // If background MediaStore activity moved the generation, this attempt did NOT
            // pass; the failure handoff below lets the host retain it and retry bounded.
            assertEquals(6, report.jobs.size)
            assertEquals(6, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            assertEquals(6, counters["cheapInspections"])
            assertEquals(6, counters["fastPathHits"])
            assertEquals(0, counters["fullVerifierRuns"])
            assertEquals(0, counters["fallbacks"])
            var zeroWriteVerified = true
            jobs.forEach { job ->
                if (beforeBytes[job.jobDir.name] != dirFingerprints(job.jobDir)) zeroWriteVerified = false
                assertEquals("zero-write i3ColdHit: ${job.jobDir.name}", beforeBytes[job.jobDir.name], dirFingerprints(job.jobDir))
            }
            writeResult(baseResult("i3ColdHit", "HIT")
                .put("totalMs", totalMs)
                .put("recoveriesExecuted", recoveriesExecuted)
                .put("passed", true)
                .put("recovered", 6)
                .put("jobsTotal", 6)
                .put("counters", JSONObject(counters))
                .put("timingsMs", JSONObject(timingsMs(timings)))
                .put("zeroWriteVerified", zeroWriteVerified))
            log("i3ColdHit-DONE totalMs=$totalMs")
        } catch (e: Throwable) {
            // Retain the drifted/failed attempt so the host can record it and retry bounded.
            // Written even though this instrumentation invocation reports failure.
            runCatching {
                writeResult(baseResult("i3ColdHit", "HIT")
                    .put("totalMs", totalMs)
                    .put("recoveriesExecuted", recoveriesExecuted)
                    .put("passed", false)
                    .put("failureReason", e.message ?: e.javaClass.simpleName)
                    .put("recovered", report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
                    .put("jobsTotal", report.jobs.size)
                    .put("counters", JSONObject(counters))
                    .put("timingsMs", JSONObject(timingsMs(timings))))
            }
            throw e
        }
    }

    // ------------------------------------------------------------- invocation 4 (C)

    @Test
    fun i3GenMismatch() {
        requirePolicyGate()
        val root = i3Root()
        val jobs = loadManifestJobs()
        assertEquals(6, jobs.size)
        val unrelated = createUnrelatedRow()
        try {
            // Unrelated TEST-OWNED mutation (byte flip through the provider) moves the volume
            // generation, so the whole intact cohort must coarsely fall back to FULL.
            val otherBytes = readBytes(unrelated)
            val mutated = otherBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
            writeBytes(unrelated, mutated)
            assertTrue(readBytes(unrelated).contentEquals(mutated))
            val t0 = SystemClock.elapsedRealtime()
            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val totalMs = SystemClock.elapsedRealtime() - t0
            val counters = U23Counters.snapshot()
            log("i3GenMismatch report=${report.jobs.map { it.classification }} counters=$counters")
            assertEquals(6, report.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            assertEquals(6, counters["cheapInspections"])
            assertEquals(0, counters["fastPathHits"])
            assertEquals(6, counters["fullVerifierRuns"])
            assertEquals(6, counters["fallback:VOLUME_GENERATION_MISMATCH"])
            writeResult(baseResult("i3GenMismatch", "GEN_MISMATCH")
                .put("totalMs", totalMs)
                .put("recovered", 6)
                .put("jobsTotal", 6)
                .put("counters", JSONObject(counters)))
        } finally {
            context.contentResolver.delete(unrelated, null, null)
            assertTrue("unrelated row must be absent", awaitAbsent(unrelated))
        }
        log("i3GenMismatch-DONE")
    }

    // ------------------------------------------------------------- invocation 5 (D)

    @Test
    fun i3JpegSigKill() {
        requirePolicyGate()
        val root = i3Root()
        val jobs = loadManifestJobs()
        assertEquals(6, jobs.size)
        val target = jobs.first { it.format == OutputFormat.JPEG }
        val origBytes = readBytes(target.uri)
        // Same-size JPEG signature kill: destroy the SOI marker, keep byte length identical.
        val killed = origBytes.copyOf().also { it[0] = 0x00; it[1] = 0x00; it[2] = 0x00 }
        writeBytes(target.uri, killed)
        val readback = readBytes(target.uri)
        try {
            assertEquals(origBytes.size, readback.size)
            assertTrue("D: readback SHA must prove mutation", sha256(origBytes) != sha256(readback))
            assertTrue(readback.contentEquals(killed))
            val t0 = SystemClock.elapsedRealtime()
            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val totalMs = SystemClock.elapsedRealtime() - t0
            val counters = U23Counters.snapshot()
            log("i3JpegSigKill report=${report.jobs.map { it.classification }} counters=$counters")
            // The corrupting write moves the volume generation, so the cohort falls back;
            // the FULL verifier must type the corrupted payload as SIGNATURE_INVALID.
            assertTrue((counters["fullVerifierRuns"] ?: 0) >= 1)
            val others = report.jobs.filter { it.jobDir.name != target.jobDir.name }
            assertEquals(5, others.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            val direct = recoverMediaStoreExportJournals(
                target.jobDir, ContextMediaStoreExportRecoveryAccess(context)
            )
            val main = direct.single { it.attemptId == journalAttemptId(target) }
            log("i3JpegSigKill diagnostic=${main.verificationDiagnosticReason} classification=${main.classification}")
            assertEquals(GalleryExportVerificationReason.SIGNATURE_INVALID, main.verificationDiagnosticReason)
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED, main.classification)
            writeResult(baseResult("i3JpegSigKill", "SIG_KILL")
                .put("totalMs", totalMs)
                .put("target", target.jobDir.name)
                .put("targetDiagnostic", requireNotNull(main.verificationDiagnosticReason).name)
                .put("counters", JSONObject(counters)))
        } finally {
            // Restore byte-identical content so later scenarios start from an intact cohort.
            writeBytes(target.uri, origBytes)
            assertTrue("D: restore must be byte-identical", readBytes(target.uri).contentEquals(origBytes))
        }
        log("i3JpegSigKill-DONE")
    }

    // ------------------------------------------------------------- invocation 6 (E)

    @Test
    fun i3HeifFtypKill() {
        requirePolicyGate()
        val root = i3Root()
        val jobs = loadManifestJobs()
        assertEquals(6, jobs.size)
        val target = jobs.first { it.format == OutputFormat.HEIF }
        val origBytes = readBytes(target.uri)
        // Same-size native HEIF ftyp kill: zero the ftyp box type + major brand + minor
        // version (bytes 4..11), keep byte length identical.
        val killed = origBytes.copyOf().also { for (i in 4..11) it[i] = 0x00 }
        writeBytes(target.uri, killed)
        val readback = readBytes(target.uri)
        try {
            assertEquals(origBytes.size, readback.size)
            assertTrue("E: readback SHA must prove mutation", sha256(origBytes) != sha256(readback))
            assertTrue(readback.contentEquals(killed))
            val t0 = SystemClock.elapsedRealtime()
            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root), ContextMediaStoreExportRecoveryAccess(context)
            )
            val totalMs = SystemClock.elapsedRealtime() - t0
            val counters = U23Counters.snapshot()
            log("i3HeifFtypKill report=${report.jobs.map { it.classification }} counters=$counters")
            assertTrue((counters["fullVerifierRuns"] ?: 0) >= 1)
            val others = report.jobs.filter { it.jobDir.name != target.jobDir.name }
            assertEquals(5, others.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            val direct = recoverMediaStoreExportJournals(
                target.jobDir, ContextMediaStoreExportRecoveryAccess(context)
            )
            val main = direct.single { it.attemptId == journalAttemptId(target) }
            log("i3HeifFtypKill diagnostic=${main.verificationDiagnosticReason} classification=${main.classification}")
            assertEquals(GalleryExportVerificationReason.SIGNATURE_INVALID, main.verificationDiagnosticReason)
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED, main.classification)
            writeResult(baseResult("i3HeifFtypKill", "FTYP_KILL")
                .put("totalMs", totalMs)
                .put("target", target.jobDir.name)
                .put("targetDiagnostic", requireNotNull(main.verificationDiagnosticReason).name)
                .put("counters", JSONObject(counters)))
        } finally {
            writeBytes(target.uri, origBytes)
            assertTrue("E: restore must be byte-identical", readBytes(target.uri).contentEquals(origBytes))
        }
        log("i3HeifFtypKill-DONE")
    }

    // ------------------------------------------------------------- invocation 7 (F)

    @Test
    fun i3ExactDelete() {
        requirePolicyGate()
        val root = i3Root()
        val jobs = loadManifestJobs()
        assertEquals(6, jobs.size)
        // Pre-stabilize (no mutations): bring all evidence current so the delete is the only
        // staleness in the scenario recovery below.
        val pre = KeplerRecoveryCoordinator.recoverRoots(
            listOf(root), ContextMediaStoreExportRecoveryAccess(context)
        )
        assertEquals(6, pre.jobs.size)
        U23Counters.reset()
        val target = jobs.first()
        assertEquals(1, context.contentResolver.delete(target.uri, null, null))
        assertTrue("deleted row must be absent", awaitAbsent(target.uri))
        val t0 = SystemClock.elapsedRealtime()
        val report = KeplerRecoveryCoordinator.recoverRoots(
            listOf(root), ContextMediaStoreExportRecoveryAccess(context)
        )
        val totalMs = SystemClock.elapsedRealtime() - t0
        val counters = U23Counters.snapshot()
        val targetReport = report.jobs.single { it.jobDir.name == target.jobDir.name }
        log("i3ExactDelete target classification=${targetReport.classification} actions=${targetReport.actions} counters=$counters")
        // Exact deletion -> PUBLIC_RESULT_REMOVED on the target; the intact remainder recovers.
        assertTrue(
            targetReport.actions.contains(MediaStoreExportRecoveryClassification.PUBLIC_RESULT_REMOVED.name)
        )
        val others = report.jobs.filter { it.jobDir.name != target.jobDir.name }
        assertEquals(5, others.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
        writeResult(baseResult("i3ExactDelete", "DELETE")
            .put("totalMs", totalMs)
            .put("target", target.jobDir.name)
            .put("targetActions", targetReport.actions.toString())
            .put("counters", JSONObject(counters)))
        log("i3ExactDelete-DONE")
    }

    // ------------------------------------------------------------- invocation 8: final sweep

    @Test
    fun i3FinalSweep() {
        U23FastPathGate.testOverride = U23TestOverride.UNSET
        val root = i3Root()
        val jobs = loadManifestJobs()
        try {
            // Exact cleanup: every manifest row asserted ABSENT (QUERY_FAILED is not absent),
            // every job dir removed.
            jobs.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
            var urisAbsent = 0
            jobs.forEach { job ->
                val absent = awaitAbsent(job.uri)
                Log.d(TAG, "CLEANUP uri=${job.uri} absent=$absent")
                assertTrue("I3 cohort row must be absent: ${job.uri}", absent)
                if (absent) urisAbsent++
                runCatching { job.jobDir.deleteRecursively() }
                assertTrue("I3 cohort job dir must be removed: ${job.jobDir}", !job.jobDir.exists())
            }
            runCatching { root.deleteRecursively() }
            assertTrue("I3 cohort root must be absent: $root", !root.exists())
            runCatching { manifestFile().delete() }
            assertTrue("I3 manifest must be absent: ${manifestFile()}", !manifestFile().exists())
            runCatching { resultFile().delete() }
            assertTrue("I3 per-run result file must be absent: ${resultFile()}", !resultFile().exists())
            // No pending/stray test rows remain (user media untouched: only manifest URIs deleted).
            val leftover = leftoverI3ImageRows()
            assertTrue("I3 no pending test rows allowed (leftover=$leftover)", leftover == 0)
            writeResult(baseResult("i3FinalSweep", "CLEANUP")
                .put("cleanupVerified", true)
                .put("urisAbsent", urisAbsent)
                .put("urisTotal", jobs.size)
                .put("jobDirsRemoved", jobs.size)
                .put("rootAbsent", !root.exists())
                .put("manifestAbsent", !manifestFile().exists())
                .put("leftoverTestRows", leftover))
            Log.d(TAG, "I3-CLEANUP-DONE urisAbsent=$urisAbsent leftover=$leftover")
        } catch (e: Throwable) {
            throw e
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun createI3Job(root: File, index: Int, format: OutputFormat): I3Job {
        val jobDir = File(root, "KPL_YUV_FUSION_I3_${index}_${UUID.randomUUID()}")
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
                displayNameBase = "u23i3-$index-${UUID.randomUUID()}",
                requestedFormat = format, relativeAlbumPath = "Pictures/KeplerU23I3",
                quality = 92, cancellation = NoOpKeplerPipelineCancellation, jobDir = jobDir
            )
            assertTrue("I3 export($format) must succeed: $export", export.success)
            assertEquals(GalleryExportCommitState.VERIFIED, export.publicCommitState)
            assertEquals(format, export.formatUsed)
            updateExportMetadata(jobDir, export, verified = true, finalOutputFormat = finalFormat)
            KeplerJobMetadata.update(jobDir) {
                it.put("status", "COMPLETE").put("processStatus", "PIPELINE_COMPLETE")
            }
            KeplerJobMetadata.findOperationLease(jobDir)?.let { KeplerJobMetadata.releaseOperation(it) }
            return I3Job(jobDir, Uri.parse(requireNotNull(export.uriString)), export.formatUsed)
        } finally {
            bitmap.recycle()
        }
    }

    private fun createUnrelatedRow(): Uri {
        val bitmap = deterministicBitmap(32, 32, 99)
        try {
            val export = exportNightFusionBitmapToGallery(
                context = context, bitmap = bitmap,
                displayNameBase = "u23i3-unrelated-${UUID.randomUUID()}",
                requestedFormat = OutputFormat.JPEG, relativeAlbumPath = "Pictures/KeplerU23I3",
                quality = 92, cancellation = NoOpKeplerPipelineCancellation, jobDir = null
            )
            assertTrue("unrelated export must succeed", export.success)
            return Uri.parse(requireNotNull(export.uriString))
        } finally {
            bitmap.recycle()
        }
    }

    private fun evidenced(job: I3Job): U23VerificationEvidence? =
        MediaStoreExportJournal.list(job.jobDir)
            .single { it.role == MediaStoreExportRole.MAIN_IMAGE }
            .verificationEvidence

    private fun journalAttemptId(job: I3Job): String =
        MediaStoreExportJournal.list(job.jobDir)
            .single { it.role == MediaStoreExportRole.MAIN_IMAGE }.exportAttemptId

    private fun persistManifest(jobs: List<I3Job>, seed: JSONObject) {
        val json = JSONObject()
            .put("jobs", JSONArray(jobs.map {
                JSONObject().put("dir", it.jobDir.absolutePath).put("uri", it.uri.toString()).put("format", it.format.name)
            }))
            .put("seed", seed)
        manifestFile().writeText(json.toString())
        log("I3-MANIFEST jobs=${jobs.size}")
    }

    private fun loadManifestJobs(): List<I3Job> {
        val json = JSONObject(manifestFile().readText())
        val arr = json.getJSONArray("jobs")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            I3Job(File(o.getString("dir")), Uri.parse(o.getString("uri")), OutputFormat.valueOf(o.getString("format")))
        }
    }

    /** Writes the per-invocation handoff consumed by the host orchestrator (app-private file). */
    private fun writeResult(record: JSONObject) {
        resultFile().writeText(record.toString())
        log("I3-RESULT runId=${record.optString("runId")}")
    }

    /**
     * Counts MediaStore image rows whose display name matches this test's `u23i3-` prefix.
     * Returns -1 on any query failure: QUERY_FAILED is NOT a clean/absent result.
     */
    private fun leftoverI3ImageRows(): Int {
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("u23i3-%"),
                null
            )
        } catch (_: Exception) {
            return -1
        }
        return cursor?.use { if (it.moveToFirst()) it.count else 0 } ?: -1
    }

    private fun dirFingerprints(jobDir: File): List<Pair<String, String>> =
        jobDir.listFiles()?.sortedBy { it.name }?.map { it.name to sha256(it.readBytes()) } ?: emptyList()

    private fun timingsMs(t: Map<String, Long>): Map<String, Double> = buildMap {
        val rowN = (t["rowQueries"] ?: 0L).coerceAtLeast(1L)
        val verN = (t["versionQueries"] ?: 0L).coerceAtLeast(1L)
        val genN = (t["generationQueries"] ?: 0L).coerceAtLeast(1L)
        val predN = (t["predicates"] ?: 0L).coerceAtLeast(1L)
        put("rowQueryAvgMs", (t["rowQueryNanos"] ?: 0L) / 1e6 / rowN)
        put("versionAvgMs", (t["versionQueryNanos"] ?: 0L) / 1e6 / verN)
        put("generationAvgMs", (t["generationQueryNanos"] ?: 0L) / 1e6 / genN)
        put("predicateAvgMs", (t["predicateNanos"] ?: 0L) / 1e6 / predN)
        put("rowTotalMs", (t["rowQueryNanos"] ?: 0L) / 1e6)
        put("versionTotalMs", (t["versionQueryNanos"] ?: 0L) / 1e6)
        put("generationTotalMs", (t["generationQueryNanos"] ?: 0L) / 1e6)
        put("predicateTotalMs", (t["predicateNanos"] ?: 0L) / 1e6)
    }

    private fun readBytes(uri: Uri) =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("null stream $uri")

    private fun writeBytes(uri: Uri, payload: ByteArray) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rwt") ?: error("null PFD $uri")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { fos -> fos.write(payload); fos.flush(); fos.fd.sync() }
        }
    }

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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun log(msg: String) = Log.d(TAG, msg)

    private fun deterministicBitmap(w: Int, h: Int, seed: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            for (y in 0 until h) for (x in 0 until w) {
                it.setPixel(x, y, android.graphics.Color.argb(255, (x * 4 + seed) % 256, (y * 4 + seed * 3) % 256, ((x + y) * 2 + seed * 5) % 256))
            }
        }
}
