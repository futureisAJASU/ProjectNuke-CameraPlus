# U2.3-I3 VALIDATED-TARGET PRODUCTION CANARY (R1 CORRECTED)

**Repository:** futureisAJASU/ProjectNuke-CameraPlus
**Source HEAD:** 59c4e7132cee3bb367e779510836d75c67e8437e
**Reference Device:** SM-S921N (adb serial R3CX40A15GB)
**Android:** 17 / API 37, user 0, manufacturer samsung
**Platform incremental:** S921NKSUHZZHL (exact `ro.build.version.incremental`)
**Package:** com.projectnuke.keplernightlab (versionCode 1, versionName 1.0, buildType debug)
**Gate:** Production default OFF; canary ON only inside the exact validated scope below.

---

## 1. Exact production rollout scope (literal incremental pin)

`U23RolloutPolicy.isProductionEnabled(environment)` returns true ONLY for:

- manufacturer == samsung (case-insensitive)
- model == SM-S921N (exact)
- sdk == 37 (exact)
- platformIncremental == S921NKSUHZZHL (exact equality on `Build.VERSION.INCREMENTAL`,
  which is `ro.build.version.incremental`)

`Build.DISPLAY` is NOT rollout authority (diagnostic only). Unknown or blank incremental
is OFF. A platform update changes the incremental, so rollout goes OFF until revalidated.
Everything else returns false; there is no fall-through to ON. OFF means zero U2.3 cheap
reads and the existing FULL verifier every cold start (proven by unit tests).

The existing reboot (boot count) and app version boundaries remain fail-closed inside the
predicate. Row GENERATION_MODIFIED is never consulted by the policy or the predicate.

---

## 2. Source HEAD and APK provenance

The host orchestrator required a clean worktree, recorded HEAD, built both APKs from that
HEAD, computed SHA-256, and installed those exact artifacts. The evidence binds them:

- gitHead: 59c4e7132cee3bb367e779510836d75c67e8437e
- appApk: app-debug.apk
  sha256: 0753cd165fc5bb39394d535abcc805698f5b6d70d2bb6681fae8d98bac9a58a1
- testApk: app-debug-androidTest.apk
  sha256: 598013e7b61b5458325b205dbd2605d7c7b4e9538bcada4154392633cba54e61
- appVersionCode 1, appVersionName 1.0, buildType debug

No pre-installed APK was relied upon. Package verification runs AFTER installation for
normal execution.

---

## 3. Default policy and unsupported-device fallback

- Default (non-canary, or canary build after an OTA): U2.3 OFF. Recovery is byte-identical
  to baseline: no U2.3 provider reads, no evidence issuance, FULL verifier every cold start.
- The safety predicate is unchanged and still fail-closes every leg independently; the
  policy only selects whether the cheap path is attempted.
- Test override semantics: DEBUG-only tri-state (`UNSET`/`FORCE_OFF`/`FORCE_ON`). Tests can
  force OFF or ON. Release builds ignore the override entirely; the production decision
  comes ONLY from `U23RolloutPolicy`. No SharedPreferences, no Settings UI, no persisted
  toggle. The override is an in-memory volatile, never persisted. Debug UNSET and release
  use the same pure policy.

---

## 4. First-activation behavior

Missing, stale, or version/boot-mismatched evidence fails closed to the FULL verifier;
stable evidence is issued only under the already accepted stable bracket
(version+generation stable around a verified FULL result with agreeing row metadata).
No authority is created or migrated from old exportVerified / VERIFIED booleans. Only
later unchanged cold starts may fast-path. Proven by Scenario A (fresh -> 6 FULL, evidence
issued) followed by the true-cold hit (Scenario B1).

---

## 5. Physical pilot evidence (SM-S921N / API 37, no override)

New exact 6-job cohort (3 JPEG + 3 native HEIF). The override stayed `UNSET` in every
invocation (`policyEnabled=true` throughout); no override is responsible for any hit.
Each invocation was independently true process-cold (explicit pidof/ps result types,
`am force-stop --user 0`, absence proven from successful empty queries, instrumentation
exit status recorded).

### Corrected Scenario B (AUTHORITATIVE)

Stabilization is a SEPARATE exited instrumentation process (`i3Stabilize`), followed by a
host force-stop with proven absence, followed by a FRESH `i3ColdHit` process that performs
EXACTLY ONE recovery. That first and only recovery hit 6/6 (`recoveriesExecuted=1`).

- Stabilize: 6/6 currently valid evidence prepared (separate process, exited cleanly).
- Cold boundary: stabilize exit 0 -> `force-stop --user 0` (exit 0) -> pidof/ps proven
  absent -> fresh cold-hit invocation (exit 0).
- Cold hit (attempt 1): 6 cheap, 6 hits, 0 full, 0 fallbacks, 6/6 RECOVERED, zero-write,
  **122 ms** (FIRST recovery wall time in the fresh process).
- No drifted attempts (`attempts: []`); bounded retry (max 3) was not needed.

### Superseded timing (NOT authoritative)

A prior run reported 83 ms for Scenario B. That 83 ms was an IN-PROCESS SECOND recovery
(stabilization ran inside the same instrumentation invocation before the measured
recovery). It is labeled here as:

    SUPERSEDED - IN-PROCESS SECOND RECOVERY (83 ms)

It is retained in git history but MUST NOT be cited as the cold-hit timing. The
authoritative cold-hit timing is the 122 ms first-recovery result above.

### All scenarios

| Scenario | Run | Result |
|----------|-----|--------|
| A fresh/stale | i3Seed6 | 6 FULL (NO_EVIDENCE x6), 6/6 RECOVERED, 6/6 evidence in 1 pass, 501 ms |
| B0 stabilize | i3Stabilize | 6/6 currently valid evidence prepared, separate exited process |
| B1 true-cold hit | i3ColdHit | 6 cheap, 6 hits, 0 full, 0 fallbacks, zero-write, **122 ms** (first recovery) |
| C unrelated mutation | i3GenMismatch | 6 FULL (VOLUME_GENERATION_MISMATCH x6), 6/6 RECOVERED |
| D JPEG sig kill | i3JpegSigKill | fast-path reject, FULL, SIGNATURE_INVALID |
| E HEIF ftyp kill | i3HeifFtypKill | fast-path reject, FULL, SIGNATURE_INVALID |
| F exact deletion | i3ExactDelete | PUBLIC_RESULT_REMOVED on target, other 5 RECOVERED |
| Sweep | i3FinalSweep | 6/6 URIs absent, dirs/root/manifest/result absent, 0 leftover rows |

Scenario B1 stage timings (authoritative first recovery): row query 14.547 ms total
(2.425 ms/row); getVersion 9.839 ms (0.820 ms/row); getGeneration 7.194 ms (0.600 ms/row);
predicate 0.417 ms (0.069 ms/row).

Machine-generated evidence: `docs/evidence/U2_3_I3_canary_evidence.json`
(schema `u23-i3-canary-evidence/v1`, 8 runs + `attempts[]`). The tool aborts invalid runs
and does not rewrite the final evidence; drifted cold-hit attempts (none in this run)
are retained in `attempts[]`, never overwritten.

---

## 6. Zero-write proof

Scenario B1 fingerprinted every exact job directory (SHA-256 per file) before its single
recovery and compared after: all 6 byte-identical (`zeroWriteVerified=true`). Stable-hit
writes: reconstruction 0, journal 0, terminal metadata 0, evidence refresh 0 (the hit
path issues nothing). R4 zero-write behavior is preserved.

---

## 7. Cleanup

Final sweep authoritatively proved: all 6 MediaStore URIs ABSENT (in-app query;
QUERY_FAILED is not ABSENT), all 6 job dirs removed, cohort root absent, manifest absent,
on-device per-run result file absent (host-deleted and re-verified), 0 leftover `u23i3-`
rows. User media untouched (only manifest URIs deleted). Host restored
`svc power stayon false` and asserted the screen OFF (deterministic KEYCODE_SLEEP with
wakefulness polling, no blind power toggle).

---

## Classification

**U2.3-I3 PRODUCTION CANARY PASS - VALIDATED TARGET TRUE PROCESS-COLD DEFAULT-PATH ACTIVATION PROVEN**

Basis: exact incremental policy passes; override UNSET throughout; stabilization is a
separate exited process; target force-stopped with proven absence; fresh cold-hit process
performs exactly one recovery hitting 6/6 with 0 full, 0 fallback, zero-write;
`recoveriesExecuted=1`; all fallback scenarios correct; APK/source provenance bound;
cleanup authoritative.

Production enablement stays restricted to the exact canary scope above. Do NOT broaden
beyond Samsung SM-S921N / API 37 / exact incremental S921NKSUHZZHL in this phase.

---

## Files

- `docs/U2_3_I3_PRODUCTION_CANARY.md` - this file
- `docs/evidence/U2_3_I3_canary_evidence.json` - machine-generated corrected canary evidence
- `tools/u23_i3_canary_host.py` - bounded deterministic canary orchestrator (stabilize/cold-hit retry, attempts retention)
- `tools/u23_i21_ab_host.py` - I2.1 orchestrator with host-hygiene hardening
- `app/src/main/java/com/projectnuke/keplernightlab/U23RolloutPolicy.kt` - pure exact-incremental canary policy
- `app/src/main/java/com/projectnuke/keplernightlab/U23FastPath.kt` - tri-state gate (predicate untouched)
- `app/src/androidTest/java/com/projectnuke/keplernightlab/U23I3ProductionCanaryTest.kt` - default-path pilot (separate stabilize, one-recovery hit)
- `app/src/test/java/com/projectnuke/keplernightlab/U23RolloutPolicyTest.kt` - exact-incremental non-canary proof
