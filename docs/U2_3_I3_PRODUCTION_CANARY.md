# U2.3-I3 VALIDATED-TARGET PRODUCTION CANARY

**Repository:** futureisAJASU/ProjectNuke-CameraPlus
**Source HEAD:** 8fff8be9f5d8e4002ac0e27e9aa96578e6b20682
**Reference Device:** SM-S921N (adb serial R3CX40A15GB)
**Android:** 17 / API 37, user 0, manufacturer samsung
**Platform build:** incremental S921NKSUHZZHL (display CP2A.260605.016.S921NKSUHZZHL)
**Package:** com.projectnuke.keplernightlab (versionCode 1, versionName 1.0, buildType debug)
**Gate:** Production default OFF; canary ON only inside the exact validated scope below.

---

## 1. Exact production rollout scope

`U23RolloutPolicy.isProductionEnabled(environment)` returns true ONLY for:

- manufacturer == samsung (case-insensitive)
- model == SM-S921N (exact)
- sdk == 37 (exact)
- platformBuild ends with S921NKSUHZZHL (the validated `ro.build.version.incremental`;
  `Build.DISPLAY` on the validated build ends with it)

Everything else returns false. There is no fall-through to ON for unknown environments.
OFF means zero U2.3 cheap reads and the existing FULL verifier every cold start
(proven by `policyOff_zeroCheapReads_fullVerifierPath`).

Strict-build pin: a platform update changes the incremental, so rollout goes OFF until
revalidated. This is the safest initial policy. The existing reboot (boot count) and app
version boundaries remain fail-closed inside the predicate. Row GENERATION_MODIFIED is
never consulted by the policy or the predicate.

---

## 2. Source HEAD and APK provenance

The host orchestrator required a clean worktree, recorded HEAD, built both APKs from that
HEAD, computed SHA-256, and installed those exact artifacts. The evidence binds them:

- gitHead: 8fff8be9f5d8e4002ac0e27e9aa96578e6b20682
- appApk: app-debug.apk
  sha256: 2197e4fc6626c773e4c162d09c6407060a76ffd039c534ec8e77be8fa57bea4c
- testApk: app-debug-androidTest.apk
  sha256: 0f720056a0fbe5ca7416b24c6cd07b3ffd29685bf2a05e2b04c73ada702db877
- appVersionCode 1, appVersionName 1.0, buildType debug

No pre-installed APK was relied upon.

---

## 3. Default policy and unsupported-device fallback

- Default (non-canary, or canary build after an OTA): U2.3 OFF. Recovery is byte-identical
  to baseline: no U2.3 provider reads, no evidence issuance, FULL verifier every cold start.
- The safety predicate is unchanged and still fail-closes every leg independently; the
  policy only selects whether the cheap path is attempted.
- Test override semantics: DEBUG-only tri-state (`UNSET`/`FORCE_OFF`/`FORCE_ON`). Tests can
  force OFF or ON. Release builds ignore the override entirely; the production decision
  comes ONLY from `U23RolloutPolicy`. No SharedPreferences, no Settings UI, no persisted
  toggle. The override is an in-memory volatile, never persisted.

---

## 4. First-activation behavior

Missing, stale, or version/boot-mismatched evidence failes closed to the FULL verifier;
stable evidence is issued only under the already accepted stable bracket
(version+generation stable around a verified FULL result with agreeing row metadata).
No authority is created or migrated from old exportVerified / VERIFIED booleans. Only
later unchanged cold starts may fast-path. Proven by Scenario A (fresh -> 6 FULL, evidence
issued) followed by Scenario B (unchanged -> 6 hits).

---

## 5. Physical pilot evidence (SM-S921N / API 37, no override)

New exact 6-job cohort (3 JPEG + 3 native HEIF). The override stayed `UNSET` in every
invocation (`policyEnabled=true` throughout); no override is responsible for any hit.
Each invocation was independently true process-cold (explicit pidof/ps result types,
`am force-stop --user 0`, absence proven from successful empty queries, instrumentation
exit status recorded).

| Scenario | Run | Result |
|----------|-----|--------|
| A fresh/stale | i3Seed6 | 6 FULL (NO_EVIDENCE x6), 6/6 RECOVERED, 6/6 evidence issued in 1 pass, 482 ms |
| B true-cold hit | i3ColdHit | 6 cheap, 6 hits, 0 full, 0 fallbacks, 6/6 RECOVERED, zero-write, 83 ms |
| C unrelated mutation | i3GenMismatch | 6 FULL (VOLUME_GENERATION_MISMATCH x6), 6/6 RECOVERED, 762 ms |
| D JPEG sig kill | i3JpegSigKill | fast-path reject, FULL, SIGNATURE_INVALID, 709 ms |
| E HEIF ftyp kill | i3HeifFtypKill | fast-path reject, FULL, SIGNATURE_INVALID, 406 ms |
| F exact deletion | i3ExactDelete | PUBLIC_RESULT_REMOVED on target, other 5 RECOVERED, 497 ms |
| Sweep | i3FinalSweep | 6/6 URIs absent, dirs/root/manifest/result absent, 0 leftover rows |

Scenario B stage timings (the canary hit): row query 13.071 ms total (2.178 ms/row);
getVersion 8.096 ms (0.675 ms/row); getGeneration 7.420 ms (0.618 ms/row); predicate
0.181 ms (0.030 ms/row).

Machine-generated evidence: `docs/evidence/U2_3_I3_canary_evidence.json`
(schema `u23-i3-canary-evidence/v1`). The tool aborts invalid runs and does not rewrite
the final evidence; there is no rejected-attempt artifact.

---

## 6. Zero-write proof

Scenario B fingerprinted every exact job directory (SHA-256 per file) after stabilization
and compared after the hitting recovery: all 6 byte-identical (`zeroWriteVerified=true`).
Stable-hit writes: reconstruction 0, journal 0, terminal metadata 0, evidence refresh 0
(the hit path issues nothing). R4 zero-write behavior is preserved.

---

## 7. Cleanup

Final sweep authoritatively proved: all 6 MediaStore URIs ABSENT (in-app query;
QUERY_FAILED is not ABSENT), all 6 job dirs removed, cohort root absent, manifest absent,
on-device per-run result file absent (host-deleted and re-verified), 0 leftover `u23i3-`
rows. User media untouched (only manifest URIs deleted). Host restored
`svc power stayon false` and asserted the screen OFF (Dozing, deterministic KEYCODE_SLEEP
with wakefulness polling, no blind power toggle).

---

## Classification

**U2.3-I3 PRODUCTION CANARY PASS - VALIDATED TARGET DEFAULT-PATH ACTIVATION PROVEN**

Production enablement stays restricted to the exact canary scope above. Do NOT broaden
beyond Samsung SM-S921N / API 37 / platform incremental S921NKSUHZZHL in this phase.

---

## Files

- `docs/U2_3_I3_PRODUCTION_CANARY.md` - this file
- `docs/evidence/U2_3_I3_canary_evidence.json` - machine-generated canary evidence
- `tools/u23_i3_canary_host.py` - bounded deterministic canary orchestrator
- `tools/u23_i21_ab_host.py` - I2.1 orchestrator with host-hygiene hardening
- `app/src/main/java/com/projectnuke/keplernightlab/U23RolloutPolicy.kt` - pure canary policy
- `app/src/main/java/com/projectnuke/keplernightlab/U23FastPath.kt` - tri-state gate (predicate untouched)
- `app/src/androidTest/java/com/projectnuke/keplernightlab/U23I3ProductionCanaryTest.kt` - default-path pilot
- `app/src/test/java/com/projectnuke/keplernightlab/U23RolloutPolicyTest.kt` - non-canary proof
