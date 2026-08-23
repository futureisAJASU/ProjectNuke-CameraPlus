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

    /** Test-only override; production code must not write it. */
    @Volatile
    var overrideForTest: Boolean? = null

    /** True when heavy diagnostic IMAGE artifacts may be generated for this job. */
    fun imageArtifactsEnabled(job: JSONObject): Boolean =
        overrideForTest ?: (BuildConfig.DEBUG && job.optBoolean(JOB_KEY, false))
}
