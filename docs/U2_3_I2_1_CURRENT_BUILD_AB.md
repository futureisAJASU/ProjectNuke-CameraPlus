# U2.3-I2.1 CURRENT-BUILD PAIRED A/B HOST-RUN EVIDENCE

**Repository:** futureisAJASU/ProjectNuke-CameraPlus
**Baseline:** ba25c3aa52364726737f04654f57751e8d50b683
**Reference Device:** SM-S921N
**Android:** 17 / API 37
**Package:** com.projectnuke.keplernightlab
**Gate:** Production default OFF; test/debug override enabled per fresh instrumentation process only.

---

## Purpose

This document records the bounded, structured host-run evidence for the paired A/B current-build cohort runs. Each invocation is a separate true process-cold run with full provenance.

---

## Evidence File Format (JSON)

A compact structured evidence file is persisted under `docs/evidence/U2_3_I2_1_host_evidence.json` containing an array of invocation records. Each record captures:

```json
{
  "runId": "string",
  "mode": "OFF|ON",
  "adbSerial": "string",
  "deviceModel": "string",
  "androidRelease": "string",
  "androidApi": 37,
  "androidUser": 0,
  "timestamp": 1725123456789,
  "preForceStopProcessQuery": "string",
  "forceStopResult": "string",
  "postForceStopProcessQuery": "string",
  "instrumentationCommand": "string",
  "instrumentationExitStatus": 0,
  "recoveryCounters": {},
  "timingsMs": {}
}
```

**No logcat dumps.** Only the fields above.

---

## Paired Run Sequence

To reduce time/thermal ordering bias:

```
OFF-1 → ON-1 → OFF-2 → ON-2 → OFF-3 → ON-3
```

Each invocation MUST be a separate process-cold run:

1. Previous instrumentation exits
2. `adb shell am force-stop --user <actual-user> com.projectnuke.keplernightlab`
3. Prove target process absent (pidof / ps)
4. Start fresh instrumentation invocation
5. Set test override for that invocation only
6. Recover exact same 46 jobs
7. Capture counters/timing
8. Exit

---

## Expected Results

### OFF (overrideForTest = false)
- 0 cheap inspections
- 0 fast-path hits
- 46 full verifier runs (for intact cohort)
- verificationMode = FULL
- Zero-write: every job directory byte-identical before/after

### ON (overrideForTest = true)
- 46 cheap inspections
- 46 fast-path hits
- 0 full verifier runs
- verificationMode = STABLE_MEDIASTORE_EVIDENCE
- Zero-write: every job directory byte-identical before/after

---

## Volume Generation Changes

If unrelated real MediaStore activity changes volume generation during a run:
- Record the rejected/fallback run honestly
- Do NOT delete or manipulate unrelated user media to force a quiet volume
- A repeat under a later quiet window is allowed, but retain the rejected attempt in the evidence

---

## Zero-Write Verification

For every paired run, fingerprint every exact job directory before and after using content hashes (SHA-256), not timestamps alone.

---

## Classification

**U2.3-I2.1 ACTIVATION READINESS PASS — PAIRED CURRENT-BUILD A/B PROVEN**

or an explicit REOPEN classification.

**DO NOT enable U2.3 by default in this phase.**

---

## Files

- `docs/U2_3_I2_1_CURRENT_BUILD_AB.md` — this file
- `docs/evidence/U2_3_I2_1_host_evidence.json` — structured host evidence (appended per run)