package com.projectnuke.keplernightlab

import android.os.Build
import org.json.JSONObject
/**
 * Phase 7: separates REQUIRED production output from OPTIONAL debug artifacts.
 *
 * Audit finding: every normal YUV capture wrote ~5 FULL-RESOLUTION diagnostic
 * PNGs (reference copies, fused full-res triplicates, comparisons) plus quality
 * sheets on the background lane, dominating background processing time and
 * adding thermal pressure.  These images are diagnostics, not product truth:
 * gallery export, reprocess candidates, recovery, and HardwareE2E JSON evidence
 * never depend on them.
 *
 * Policy: heavy diagnostic IMAGE generation requires explicit debug/diagnostic
 * intent - a debug build AND the job stamped with [JOB_KEY] (set by the capture
 * pipeline when the user/developer requested diagnostics).  JSON metrics and
 * alignment/debug metadata are ALWAYS written: that is diagnostically necessary
 * HardwareE2E evidence and is never weakened by this policy.
 */
internal object DebugArtifactPolicy {
    const val JOB_KEY = "diagnosticIntent"
    const val STATUS_DISABLED = "DISABLED_BY_POLICY"

    /**
     * The ONLY scenario name that does NOT represent an explicit debug run.
     * The real debug entry point (CameraScreen.runCameraJob with a real
     * instrumentation scenario) arms [diagnosticIntentArmedForNewJobs]; normal
     * user captures keep the production scenario and never arm it.
     */
    const val PRODUCTION_DIAGNOSTIC_SCENARIO = "production_main_camera_screen"

    /** Test-only override; production code must not write it. */
    @Volatile
    var overrideForTest: Boolean? = null

    /**
     * Process-scoped intent armed by the REAL debug entry point for the CURRENT
     * capture.  Never assumed from a mere key presence: a job is durably
     * stamped with [JOB_KEY] at creation time only while this is armed, and
     * heavy images additionally require a debug build ([imageArtifactsEnabled]).
     */
    @Volatile
    var diagnosticIntentArmedForNewJobs: Boolean = false

    fun setDiagnosticIntentArmed(armed: Boolean) {
        diagnosticIntentArmedForNewJobs = armed
    }

    /**
     * Durable stamping of NEW capture jobs: called once when the job metadata
     * is created so the in-memory flag becomes durable truth before any
     * downstream debug-artifact decision reads the job.
     */
    fun stampIntentForNewJob(jobDirStamp: (String, Boolean) -> Unit) {
        if (diagnosticIntentArmedForNewJobs) {
            jobDirStamp(JOB_KEY, true)
        }
    }

    /** True when heavy diagnostic IMAGE artifacts may be generated for this job. */
    fun imageArtifactsEnabled(job: JSONObject): Boolean =
        overrideForTest ?: (BuildConfig.DEBUG && job.optBoolean(JOB_KEY, false))
}
