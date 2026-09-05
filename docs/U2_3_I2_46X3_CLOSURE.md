# U2.3-I2 FINAL 46×3 CLOSURE — TRUE PROCESS-COLD FAST-PATH CLOSURE PROVEN

**Repository:** futureisAJASU/ProjectNuke-CameraPlus
**Baseline:** 9d0ec1292e75c39f2eeb5350df6e6782626c3bd7
**Reference Device:** SM-S921N (Samsung Galaxy S24), adb serial R3CX40A15GB
**Software:** Android 17 / API 37, user 0 (owner)
**Package:** com.projectnuke.keplernightlab
**Date:** 2026-09-05
**Gate:** Production default OFF; test/debug override enabled per fresh instrumentation process only.
**Screen:** OFF-capable; no Camera/Gallery/UiAutomator.

---

## 1. COHORT (seed, 46 jobs)

- 23 JPEG + 23 native HEIF (via `exportNightFusionBitmapToGallery`, `requestedFormat`)
- 128×128 production-faithful rows, real MediaStore URIs, `VERIFIED` journal state
- Seed recovery bounded retries until all 46 carry durable U2.3 evidence
- Zero export debt; exact ownership recorded; terminal metadata persisted

```
SEED46: jobs=46 passes=2 seedMs=4935
counters: cheapInspections=92 fastPathHits=28 fullVerifierRuns=64 fallbacks=65
          fallback:NO_EVIDENCE=47 fallback:UNSTABLE_FULL_VERIFY_SNAPSHOT=1
          fallback:VOLUME_GENERATION_MISMATCH=17
```

(One background volume drift voided one issuance window; landed on pass 2 — fail-closed per §9.)

---

## 2. TRUE PROCESS-COLD UNCHANGED ×3 (host force-stop + am force-stop --user 0 between runs)

| Run | cheapInspections | fastPathHits | fullVerifierRuns | fallbacks | totalMs | rowTotalMs | versionTotalMs | generationTotalMs |
|-----|------------------|--------------|------------------|-----------|---------|------------|----------------|-------------------|
| 1   | 46               | 46           | 0                | 0         | 717     | 117.5      | 71.7           | 61.1              |
| 2   | 46               | 46           | 0                | 0         | 699     | 117.0      | 68.8           | 57.3              |
| 3   | 46               | 46           | 0                | 0         | 747     | 142.3      | 74.1           | 63.6              |

All three runs: **46/46 fast-path hits, 0 full-verifier executions, 0 fallbacks**.
Zero-write verified: every job directory byte-identical before/after (SHA-256).
External semantics identical: 46× `PUBLIC_VERIFIED` + `STABLE_MEDIASTORE_EVIDENCE`.

---

## 3. FALLBACK COHORT (bounded, test-owned only)

### A. Unrelated TEST-OWNED row mutation
- 1 unrelated row modified → whole-cohort `VOLUME_GENERATION_MISMATCH`
- 46 fallbacks, 46 full verifier runs, all `RECOVERED`

### B/C. Exact same-size corruption (JPEG SOI kill + HEIF ftyp→XXXX)
- Proven SHA change, same length, volume mismatch fallback
- Full verifier executed, diagnostic `SIGNATURE_INVALID` on both
- Journal state `PUBLIC_COMMITTED`, classification `PUBLIC_COMMITTED_UNVERIFIED`

### D. Exact-row deletion (HEIF)
- `PUBLIC_RESULT_REMOVED` preserved; no false fast-path success

### E. Missing/malformed evidence (isolated root)
- 1 absent evidence + 1 malformed JSON block → 2 full verifier fallbacks
- `NO_EVIDENCE` + `MALFORMED_EVIDENCE` correctly counted

### F/G. Boot-count + version mismatch via test seam
- Fake `bootCount = -999` → `BOOT_BOUNDARY` fallback
- Fake `version = bogus` → `MEDIASTORE_VERSION_MISMATCH` fallback
- Each case isolated to its own job; no evidence leakage between cases

### OFF-proof
- Gate OFF, overrideForTest = false → 0 cheap inspections, 0 fast-path hits
- 45 intact rows execute the FULL verifier, preserving `PUBLIC_VERIFIED` + `FULL` mode
- two deliberately corrupted rows remain `PUBLIC_COMMITTED_UNVERIFIED` / `SIGNATURE_INVALID`
- the deleted row remains `PUBLIC_RESULT_REMOVED`
- no U2.3 cheap reads or fast-path hits occur
- Exploding fake reads never touched (parity with baseline)

---

## 4. CLEANUP PROOF

- All 46 exact cohort URIs asserted absent (provider authoritative ABSENT)
- All 46 job directories removed (asserted absent)
- All throwaway mutation/fallback roots removed
- Manifest and state files deleted
- No pending rows, no test controls remain
- User media untouched; Secure Folder media untouched
- `adb shell svc power stayon false` restored; display OFF

---

## 5. PERFORMANCE VS R4 BASELINE

| Metric | R4 Median | I2 Measured | Δ |
|--------|-----------|-------------|---|
| Recovery (median) | 4093.030 ms | 717 / 699 / 747 ms (cold runs) | **~83% reduction** |
| MediaStore verification | 3469.124 ms | 0 ms (cold runs, fast-path) | **100% avoided** |
| Cheap query (row) | — | 2.5–3.1 ms avg (×46) | — |
| getVersion | — | 0.75–0.80 ms avg (×46) | — |
| getGeneration | — | 0.62–0.69 ms avg (×46) | — |
| Predicate eval | — | 0.04 ms avg (×46) | — |

**Correctness gates met:** zero-write unchanged path, zero full-verifier on unchanged,
all fallbacks typed/preserved, no new failure modes, default OFF.

---

## 6. FINAL CLASSIFICATION

### U2.3-I2 CLOSED — TRUE PROCESS-COLD 46-JOB FAST-PATH CLOSURE PROVEN

All I1.1 corrective + I2 closure criteria satisfied:

- ✅ Evidence persistence failure is fail-safe (ordinary Exception caught; Error/CancellationException propagate)
- ✅ Corruption diagnostic integration proven (SIGNATURE_INVALID on same-size JPEG/HEIF kills)
- ✅ Host gates pass (compile x3, unit suite x2, assemble, lint)
- ✅ Same 46-job cohort across seed + 3 cold runs + fallbacks + final sweep
- ✅ True process-cold ×3 (host force-stop + am force-stop --user 0 + pidof proof)
- ✅ Correctness preserved (zero-write, typed diagnostics, all semantics unchanged)
- ✅ Zero-write unchanged path proven (SHA-256 dir fingerprints identical)
- ✅ Required fallbacks proven (A–G + OFF-proof)
- ✅ Cleanup exact (all test URIs/jobs/state absent, user media untouched)
- ✅ Production/default remains OFF

**Production behavior TODAY remains full verify every cold start.** The fast path is
architected, tested, and proven; activation is a separate rollout decision.

---

## 7. FILES CHANGED (I1 + I1.1 + I2)

- `app/src/main/.../U23FastPath.kt` (new: gate, evidence, reads seam, predicate, issuance, counters, timings)
- `app/src/main/.../MediaStoreExportJournal.kt` (additive evidence fields, `withVerificationEvidence`)
- `app/src/main/.../MediaStoreExportRecovery.kt` (modes, fast-path bracket, fail-safe issuance wiring)
- `app/src/test/.../U23FastPathPredicateTest.kt` (new: 34-test matrix incl. failure-injection)
- `app/src/androidTest/.../U23FastPathPilotTest.kt` (new: 3x3 + diagnostic pilot)
- `app/src/androidTest/.../U23Closure46Test.kt` (new: seed + 3 cold + fallbacks + final sweep)
- `docs/U2_3_MEDIASTORE_REVERIFICATION_DESIGN.md` (reconciled: stale claims labeled HISTORICAL/SUPERSEDED; §20C authoritative)
- `docs/U2_3_I1_IMPLEMENTATION.md` (updated: I1.1 corrective §12.1)
- `docs/U2_3_I2_46X3_CLOSURE.md` (this file)

**Production default: OFF. No Camera/RAW/signing/R4 changes. No row-generation allow path.**