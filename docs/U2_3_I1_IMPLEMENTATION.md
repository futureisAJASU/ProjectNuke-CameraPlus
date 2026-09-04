# U2.3-I1 IMPLEMENTATION — SAFE VERSION/VOLUME-GENERATION FAST PATH (DEFAULT OFF)

**Baseline:** 29c21343e89953bc109eb94f9af93a34158e6778
**Reference Device (pilot):** SM-S921N, Android 17 / API 37
**Date:** 2026-09-04
**Status:** SOURCE + PILOT PASS — READY FOR 46×3 CLOSURE. Production default OFF.

Authoritative predicate: `docs/U2_3_MEDIASTORE_REVERIFICATION_DESIGN.md` §20C.
Evidence: C2/C3/C3.1 reports. This document describes the implementation, not the proof.

---

## 1. FEATURE GATE (`U23FastPathGate`, `U23FastPath.kt`)

- Production/default state = **OFF**: `overrideForTest = false`, and `isEnabled()`
  additionally requires `BuildConfig.DEBUG`, so a release build can never honor the
  override even if the flag were flipped.
- Test/debug-only override: `U23FastPathGate.overrideForTest = true` (in-memory only,
  never persisted, no Settings/UI/preference).
- When OFF: recovery executes the identical legacy path — no extra provider reads, no
  evidence issuance, full verifier every cold start. Host test
  `offParity_noU23ReadsOrCounters` proves OFF never touches U23 reads (exploding fake)
  and leaves counters at zero with `FULL` mode. No recovery restructuring around the
  flag: two guarded blocks inside `ContextMediaStoreExportRecoveryAccess.inspect` plus
  candidate persistence in `recoverMediaStoreExportJournal`.

## 2. EVIDENCE SCHEMA (`U23VerificationEvidence`, schema 1, algorithm 1)

Additive nested `verificationEvidence` block on `MediaStoreExportJournal`
(plus `verificationEvidencePresent` for malformed-vs-absent diagnostics):

```
schemaVersion, algorithmVersion, exactVolumeName, mediaStoreVersion,
volumeGeneration, rowId, uri, size, mimeType, displayName, width, height,
appVersionCode, bootCount (BOOT_COUNT), fullVerifiedAt
```

- Old journals parse unchanged (absent key / explicit null → null evidence).
- Missing / malformed / unknown schema / algorithm mismatch → FULL VERIFY.
- Nothing inferred from `exportVerified`, journal `VERIFIED`, or
  `terminalMetadataPersisted`. Row generation never stored for authority.
- Strict `fromJson` (blank/negative/zero rejected per field).

## 3. BOOT BOUNDARY

`Settings.Global.BOOT_COUNT` (no dangerous permission): stable across process
death/restart, changes across reboot. Unavailable (`-1`) or mismatch → FULL VERIFY
(`BOOT_BOUNDARY`). No random per-process token invented. First cold start after reboot
always full-verifies by construction (predicate leg 8).

## 4. VOLUME RESOLUTION

`MediaStore.getVolumeName(uri)` (platform API, never path parsing). Persisted and
compared as part of row identity; mismatch → `IDENTITY_MISMATCH` → FULL VERIFY.

## 5. CHEAP SNAPSHOT + READ SEMANTICS (`U23MediaReads` / `AndroidU23MediaReads`)

Exact-row query (`_ID, IS_PENDING, SIZE, MIME_TYPE, DISPLAY_NAME, WIDTH, HEIGHT`) plus
per-volume `getVersion`/`getGeneration`. All reads are `VALUE / ROW_ABSENT /
QUERY_FAILED / UNAVAILABLE`; exceptions/nulls never become "unchanged". Host tests
inject fakes through the `U23MediaReads` seam (production `Context…Access` takes an
optional override, defaulting to the Android implementation).

## 6. PREDICATE (`evaluateU23Predicate`, pure, §20C.1 legs 1–11)

Gate → evidence present/valid → schema → algorithm → app version → boot token →
row exists → pending==0 → URI/_ID/volume identity → bracketed version equality (before
== persisted == after) → bracketed volume-generation equality → SIZE → MIME →
exact display-name + extension truth → WIDTH/HEIGHT. Any failure → `Miss(reason)` →
existing FULL verifier (never a new failure mode; row absence preserves
`PUBLIC_RESULT_REMOVED`).

## 7. ORDERING / RACE HARDENING

`versionBefore/genBefore → row snapshot → versionAfter/genAfter`, all three compared to
persisted values AND to each other. Drift mid-inspection → FULL VERIFY. Row generation
is never read to repair/override anything.

## 8. VERIFIED SEMANTICS (`U23VerificationMode`: FULL / STABLE_MEDIASTORE_EVIDENCE)

`MediaStoreExportInspection` and `MediaStoreExportRecoveryResult` carry the mode
(default FULL; all pre-existing constructions unchanged). Fast-path hits return
`PUBLIC_VERIFIED` + STABLE mode: identical external classification (terminal-stable
suppression, debt, removal semantics preserved), distinguishable telemetry.
`U23Counters` (in-memory only): cheapInspections, fastPathHits, fullVerifierRuns,
fallbacks + per-reason counts.

## 9. STABLE ISSUANCE (`decideStableEvidence` + caller persistence)

Evidence issues ONLY when: gate ON + true FULL `Verified` + version/gen identical
before/after verification + all reads VALUE + final row agrees with the verified
result (size/MIME/name/dims, pending==0) + app/boot readable. Drift → current result
preserved for THIS run, NO evidence issued (next start falls back to FULL VERIFY). No
unbounded retry. Persistence reuses the atomic journal write and SKIPS identical
rewrites (zero-write fast path).

## 10. ZERO-WRITE + FALLBACK + REMOVAL

- Fast-path hit on stored evidence: fully read-only (no journal/metadata rewrite).
  Host `af_evidenceRoundTrip_isStable` + pilot counters prove it.
- Every fallback reason runs the EXISTING full verifier; no alternate corruption
  outcomes invented from cheap signals.
- Exact row absence keeps `PUBLIC_RESULT_REMOVED` / commit-missing semantics exactly.

## 11. HOST MATRIX (34 tests, `U23FastPathPredicateTest`)

A Hit; B OFF parity; C–X all fallback legs; Y/Z bracket drift; AA issuance;
AB/AC unstable-window no-issuance; AD verifier-failure/gate-off no-issuance;
AE legacy-booleans no-trust; AF round-trip stability; old-journal parse parity;
ON-hit integration (PUBLIC_VERIFIED + STABLE mode + counters, zero full-verifier runs).

## 12. DEVICE PILOT (SM-S921N, 3 JPEG + 3 HEIF, override ON in test process only)

Measured (logcat `U23PILOT`, three force-separated process-cold invocations):

- A (seed): 6/6 RECOVERED, 6 FULL verifier runs, evidence issued (5 first pass + 1 on a
  bounded retry after a background volume movement voided one window — fail-closed per
  §9), seed full-verify aggregate 391 ms.
- B (force-stop, process-cold, unchanged): 6 cheap inspections, 6 fast-path hits,
  0 full-verifier runs, 0 fallbacks, 0 journal/metadata byte changes (SHA-compared),
  identical RECOVERED results, fast aggregate 143 ms (directional only).
- C (unrelated owned-row mutation): 6/6 `VOLUME_GENERATION_MISMATCH` fallbacks, full
  verifier fallback for all, all remain valid (coarse false-positive demonstrated).
- D (same-size signature kill on exact JPEG row, readback-SHA proven): volume-mismatch
  rejection, full verifier ran, journal → `PUBLIC_COMMITTED` (`SIGNATURE_INVALID`
  semantics preserved).
- E (exact HEIF-row deletion): `RECOVERED` with `PUBLIC_RESULT_REMOVED`, no false
  fast-path success.
- Timing directional only (46×3 remains the performance authority).
- All pilot rows/jobs/controls removed (asserted absent); user media untouched;
  `stayon false`; gate reset OFF.

## 13. FILES CHANGED

- `app/src/main/…/U23FastPath.kt` (new): gate, evidence, reads seam, predicate,
  issuance, counters.
- `app/src/main/…/MediaStoreExportJournal.kt`: additive evidence fields + JSON +
  `withVerificationEvidence`.
- `app/src/main/…/MediaStoreExportRecovery.kt`: mode fields, fast-path + bracket in
  `inspect`, issuance wiring (`liveJournal`), mode propagation.
- `app/src/test/…/U23FastPathPredicateTest.kt` (new): 34-test matrix.
- `app/src/androidTest/…/U23FastPathPilotTest.kt` (new): scenarios A–E.
- Docs: this file; design-doc cleanup (stale contradictions labeled
  HISTORICAL/SUPERSEDED/REJECTED/WITHDRAWN; §20C authoritative).

**Production default: OFF. No Camera/RAW/signing/R4 changes. No row-generation allow path.**
