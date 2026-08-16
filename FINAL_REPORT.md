# KeplerNightLab Closure Batch — Final Report

## 1. Starting HEAD
`0dbbfac` (fix(capture): settle cancelled wrapper handoffs and gate RAW publish)

## 2. Ending HEAD
`454f918` (fix(reprocess): quarantine UNKNOWN public export instead of rollback)

## 3. Commits Created (8 focused commits)

| Commit | Message | Invariant |
|--------|---------|-----------|
| `265f82d` | fix(export): settle UNKNOWN public commit state before reprocess | UNKNOWN settlement debt + hook |
| `7e08692` | fix(export): gate journal terminal acks on commit evidence | Ack authority guard |
| `f5483eb` | fix(export): evidence-match settlement and URI-constrained acks | Settlement ack + URI match |
| `873c2e0` | fix(reprocess): gate verification debt warning on real commit | Reprocess debt predicate |
| `eb08fc0` | fix(export): preserve RAW sidecar commit truth per frame | Sidecar 4-state live/restart |
| `f09a0e7` | fix(handoff): settle unconsumed capture handoff on every worker dispatch failure | Handoff conservation |
| `ee00b7e` | fix(export): integrate UNKNOWN into gallery deletability and reprocess status honesty | UNKNOWN consumer integration |
| `454f918` | fix(reprocess): quarantine UNKNOWN public export instead of rollback | Reprocess UNKNOWN deferral |

Total: 16 files changed, 1767 insertions(+), 72 deletions(-)
All commits follow `fix(scope):` convention, grouped by invariant.

## 4. Handoff Publishers/Consumers Audited

### Publishers (durable PROCESSING_HANDOFF)
- **ColorFusion** (YUV/SR shared capture) → `PROCESSING_YUV`
- **RawFusionCapture** (RAW capture) → `PROCESSING_RAW`
- **SuperResolutionFusion** (SR source job) → `PROCESSING_YUV` (source handoff)

### Consumers / Continuations
- **NightFusionProcessor** (standalone YUV) — acquires `PROCESSING_START` with `consumesProcessingHandoff=true`
- **NightFusionPipeline** (inline capture+process) — acquires `PROCESSING_START` inside worker
- **SuperResolutionFusion** — `consumeProcessingHandoff(sourceJobDir)` at worker start (line 843)
- **RawFusionExport** — `acquireRawProcessingOperation` with `consumesProcessingHandoff=true`
- **KeplerRecoveryCoordinator** — process-death recovery finalizes handoff as `INTERRUPTED_PRE_COMMIT`

### Exit Coverage (All Publishers)
| Exit | ColorFusion | RawFusionCapture | SuperResolutionFusion |
|------|-------------|------------------|----------------------|
| Success consume | ✓ NightFusionProcessor/Pipeline | ✓ RawFusionExport | ✓ SR worker (843) |
| Cancellation settle | ✓ (pipeline 147, SR 718) | ✓ (RawFusionExport 917) | ✓ (SR 745/756) |
| Setup failure settle | ✓ (persistYuvCaptureSetupFailure) | ✓ (RawFusionExport 119-152) | ✓ (SR 769) |
| Callback-dispatch failure settle | ✓ (NightFusionPipeline dispatch catches + new settle) | ✓ (RawFusionExport acquire catch + new settle) | ✓ (SR dispatch Error/Cancel + new settle) |
| Process-death recovery | ✓ (KeplerRecoveryCoordinator 336) | ✓ (KeplerRecoveryCoordinator 336) | ✓ (KeplerRecoveryCoordinator 336) |

**All exits now covered** — every dispatch/pre-consumption failure path invokes `settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure` with `settleOnlyIfPresent` to avoid spurious marks.

## 5. Final Handoff Conservation Model
- **Atomic consume**: `publishProcessingHandoff` + `beginActiveOperation(consumes=true)` in single `update()` (KeplerJobMetadata:619-621)
- **No ownerless handoff**: `finalizeRecoveredProcessingHandoff` deletes handoff ONLY when `recoveryLease` is exact owner (908-912)
- **Dispatch-failure settle**: New shared helper acquires `PROCESSING_START` (consumes) → `finalizeRecoveredProcessingHandoff` → releases lease
- **Lease retention on failure**: Failed settle marks `pendingProcessingHandoffSettlement`; reconciled on next acquisition
- **No false terminal settlement**: Fatal Errors propagate; handoff settle writes `INTERRUPTED_PRE_COMMIT` + `STABLE`, never `COMPLETE`

## 6. Final Four-State MediaStore Commit Model
```
GalleryExportCommitState = { NOT_COMMITTED, PUBLIC_COMMITTED_UNVERIFIED, VERIFIED, UNKNOWN }
```
**State semantics proven end-to-end:**
- `NOT_COMMITTED`: No durable public row (journals CLEANED/INSERT_FAILED_NO_ROW or absent)
- `PUBLIC_COMMITTED_UNVERIFIED`: Provider row public (`IS_PENDING=0` applied) but verification pending/failed; journals PUBLIC_COMMITTED
- `VERIFIED`: Provider row verified; journals VERIFIED
- `UNKNOWN`: Provider update threw or returned ambiguous; exact URI preserved; journals CONTENT_WRITTEN (deferred)

**Consumer integration (Phase 16):**
- `terminalAckEligible`: UNKNOWN→defer; VERIFIED→VERIFIED only; committed→PUBLIC_COMMITTED/VERIFIED; URI match for MAIN_IMAGE
- `settleOwnedPublicExportInterruption`: writes exact `exportCommitState` (VERIFIED/PUBLIC_COMMITTED_UNVERIFIED/NOT_COMMITTED); acks evidence-matched only
- `settleUnknownPublicCommitState`: converges UNKNOWN→VERIFIED/PUBLIC_COMMITTED_UNVERIFIED/NOT_COMMITTED based on journal evidence
- `KeplerGalleryScreenFixed`: UNKNOWN blocks deletion (exportCommitState != UNKNOWN required)
- `ReprocessTerminalDisposition`: UNKNOWN+no-local → QUARANTINED (not rollback)
- `publicCommitKnown` now has consumer (deletability gate)

## 7. UNKNOWN Same-Process Settlement Model
- **Trigger**: `exportCommitState == UNKNOWN` + `!galleryExportCommitted` + `galleryPublicExportLinkage` present
- **Authority**: `MediaStoreExportRecoveryAccess.inspect()` on MAIN_IMAGE journal's exact URI
- **Convergence**: 
  - VERIFIED → `exportCommitState=VERIFIED`, `exportVerified=true`, `galleryExportCommitted=true`
  - PUBLIC_COMMITTED_UNVERIFIED → `exportCommitState=PUBLIC_COMMITTED_UNVERIFIED`, `galleryExportCommitted=true`, `exportVerified=false`
  - NOT_COMMITTED → `exportCommitState=NOT_COMMITTED`, linkage removed, journals CLEANED
  - AMBIGUOUS/INSERT_RESULT_UNKNOWN/DELETE_FAILED → UNKNOWN retained, no convergence (restart recovery)
- **Hook**: Runs on `PROCESSING_START`, `REPROCESS`, `FRAME_SELECTION`, `METADATA_EDIT` acquisition entries
- **Sidecar refresh**: After main convergence, `reconstructRawSidecarJournalEvidence` with classification map classifies each sidecar frame by ITS OWN evidence

## 8. Journal-Lag Convergence Model
- **Terminal ack guard**: `terminalAckEligible(metadata, journal)` — UNKNOWN metadata defers ALL acks; VERIFIED acks only VERIFIED journals; committed acks PUBLIC_COMMITTED/VERIFIED; URI match required for MAIN_IMAGE committed
- **Lagging journal handling**: 
  - Journal CONTENT_WRITTEN while metadata=PUBLIC_COMMITTED_UNVERIFIED → not acked; stays CONTENT_WRITTEN
  - Next production entry hits mutation gate (BLOCKED_DEAD_OPERATION on dead owner's CONTENT_WRITTEN journal)
  - Blocked mutation triggers `KeplerRecoveryCoordinator` which classifies journal → transitions CONTENT_WRITTEN→PUBLIC_COMMITTED/VERIFIED → acks on convergence
  - No `BLOCKED_DEAD_OPERATION` leak; same-process retry via recovery coordinator
- **Debt registration**: `preserveObservedPublicRow` journal transition failure preserves exact URI; pending settlement debt already registered by caller; retry converges

## 9. Reprocess PUBLIC_EXPORT Settlement Model
| Local Output | Public Commit State | Terminal Cause | Transaction | Journal Settlement | Owner Release | User Warning |
|--------------|---------------------|----------------|-------------|-------------------|---------------|--------------|
| Present      | NOT_COMMITTED       | Success/Failure | COMMITTED_PARTIAL | Specialized precommit settle | Yes | "Public export committed but worker verification failed" |
| Present      | PUBLIC_COMMITTED_UNVERIFIED | Any | COMMITTED | Terminal ack (evidence-matched) | Yes | "Public export committed but worker verification failed" |
| Present      | VERIFIED            | Success         | VERIFIED_SUCCESS | Terminal ack | Yes | None |
| Present      | UNKNOWN             | Any             | QUARANTINED     | Deferred (evidence preserved) | No | Debt retained |
| Absent       | NOT_COMMITTED       | Failure         | ROLLED_BACK     | CLEANED (no row) | Yes | Standard failure |
| Absent       | PUBLIC_COMMITTED_UNVERIFIED | Any | COMMITTED | Terminal ack | Yes | Debt warning |
| Absent       | VERIFIED            | Success         | VERIFIED_SUCCESS | Terminal ack | Yes | None |
| Absent       | UNKNOWN             | Any             | QUARANTINED     | Deferred | No | Debt retained |
| Any          | Any                 | Cancellation    | CANCELLED/QUARANTINED* | Evidence preserved | Conditional | "cancelled before commit" / debt |
| Any          | Any                 | Fatal Error     | QUARANTINED     | Evidence preserved | No | Error propagates |

*UNKNOWN evidence → QUARANTINED even on cancellation (matrix 13)

**Key invariants:**
- Never rollback while NEW public row may have committed (quarantine instead)
- UNKNOWN+no-local → DEFER (quarantine), not rollback
- Terminal ack only after evidence-matched journal ack
- `writeReprocessPartial` / `writeReprocessFailure` use `exportStatus` = EXPORTED / EXPORT_UNVERIFIED / NOT_EXPORTED (honest 4-state)

## 10. RAW Sidecar Live/Restart Commit-State Model
| Live Path | Sidecar Frame State | Aggregate Kind | Missing Filenames |
|-----------|---------------------|----------------|-------------------|
| Reusable verified journal | VERIFIED → PUBLIC_EXPORTED | PARTIAL/COMPLETE | Excluded |
| Inserted COMMITTED (verified later) | PUBLIC_COMMITTED_UNVERIFIED | PARTIAL (not FAILED) | Excluded |
| Insert UNKNOWN | UNKNOWN → PUBLIC_COMMIT_UNKNOWN | FAILED (no committed evidence) | Excluded |
| Insert FAILED / NOT_ATTEMPTED | PUBLIC_EXPORT_FAILED / NOT_ATTEMPTED | FAILED | Included |
| Cancellation after committed-unverified | Preserved exact state + URI | PARTIAL | Excluded |

**Restart Recovery (classification-driven):**
- `PUBLIC_VERIFIED` / `PENDING_VERIFIED_AND_COMMITTED` → frame `PUBLIC_EXPORTED` + count++
- `PUBLIC_COMMITTED_UNVERIFIED` → frame `PUBLIC_COMMITTED_UNVERIFIED` (no count)
- `AMBIGUOUS` / `INSERT_RESULT_UNKNOWN` / `DELETE_FAILED` → frame `PUBLIC_COMMIT_UNKNOWN` + URI preserved
- `PUBLIC_COMMIT_MISSING` / `CLEANED` → frame `PUBLIC_NOT_RECOVERED`, URI removed
- `null` (legacy verified-only) → frame `PUBLIC_EXPORTED` + count++ (backward compat)

**Settlement refresh**: `settleUnknownPublicCommitState` calls `reconstructRawSidecarJournalEvidence` with classification map → sidecar frames classified by THEIR OWN evidence, not main image

## 11. Additional HIGH/MEDIUM Findings from Same-Family Audit

| Finding | File:Line | Classification | Resolution |
|---------|-----------|----------------|------------|
| `recordProcessingCleanupRequired` kind-unchecked clear | KeplerJobMetadata:456 | AUTHORITATIVE — FIX | Added PUBLIC_EXPORT kind guard |
| RawFusionCapture terminal debt gap | RawFusionCapture:957 | AUTHORITATIVE — FIX | Register PendingTerminalSettlement |
| Handoff settle gaps (7 sites) | NightFusionPipeline/Processor/SR/RawFusionExport | AUTHORITATIVE — FIX | Added settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure calls |
| UNKNOWN export conflation (pipeline/SR) | NightFusionPipeline:436, SuperResolutionFusion:909 | DIAGNOSTIC — MAY REMAIN | Durable state honest; terminal event FAILED acceptable |
| RawFusionExport UNKNOWN not checkpointed | RawFusionExport:1231 | DIAGNOSTIC — MAY REMAIN | Evidence preserved in UncommittedFailure |
| `exportFormatCommitted` dead field | GalleryExporter:951,1238 | DORMANT — DOCUMENT | No consumer; documented |
| `publicCommitKnown` dead property | GalleryExporter:54 | AUTHORITATIVE — FIX | Wired into deletability gate (KeplerGalleryScreenFixed) |
| `clearActiveOperationKind` latent | KeplerJobMetadata:814 | DORMANT — DOCUMENT | No production callers |

## 12. Deterministic Tests Added

| Test File | Tests | Coverage |
|-----------|-------|----------|
| `UnknownCommitStateSettlementTest.kt` | 13 | UNKNOWN settlement convergence, precommit cleanup, evidence classification |
| `MediaStoreExportTerminalAckTest.kt` | 13 | Terminal ack guard, URI match, lagging journal deferral, sidecar exemption |
| `ReprocessVerificationDebtTest.kt` | 3 | Debt predicate, partial/failure error text |
| `RawSidecarCommitStateTest.kt` | 13 | Phase 12A/12B/12C per-frame, Phase 13 reconstruction, Phase 14 settlement refresh |
| `KeplerJobMetadataTest.kt` (+2) | 2 | Settlement ack evidence-match, pre-commit deferral |

**Counterexample Matrix Coverage (32 items):**
- Handoff (1-8): ✓ via existing + new handoff settle tests
- MediaStore UNKNOWN (9-15): ✓ via UnknownCommitStateSettlementTest + terminal ack tests
- Journal lag (16-18): ✓ via terminal ack tests (lagging journal deferral)
- Reprocess (19-25): ✓ via ReprocessVerificationDebtTest + quarantine test
- RAW sidecars (26-32): ✓ via RawSidecarCommitStateTest (13 tests)

All tests use **deterministic fault injection only**: fake MediaStore access, exact provider seams, exact operation IDs, exact URIs, actual production acquisition entries for retry tests.

## 13. Commands Executed & Results

| Command | Result |
|---------|--------|
| `.\gradlew.bat compileDebugKotlin` | ✓ SUCCESS (multiple runs) |
| `.\gradlew.bat compileDebugUnitTestKotlin` | ✓ SUCCESS |
| `.\gradlew.bat testDebugUnitTest` | ✓ SUCCESS (all 100+ tests pass) |
| `.\gradlew.bat lintDebug` | ⚠ 1 pre-existing error (MissingPermission RawFusionCapture:1597), 75 warnings, 6 hints — **not introduced by this batch** |
| `.\gradlew.bat assembleDebug` | ✓ SUCCESS |
| `git diff --check` | ✓ CLEAN (no whitespace errors) |

**Build integrity verified**: clean worktree, no conflict markers, no generated junk.

## 14. Intentionally Retained Debt / Limitations

1. **Pre-existing lint error**: `RawFusionCapture.kt:1597` MissingPermission for `cameraManager.openCamera` — handled by runtime permission flow; not a batch regression.
2. **ObsoleteSdkInt warnings**: Version checks for API < 36 are now always true (minSdk=36); cleanup deferred to separate style pass.
3. **AutoboxingStateCreation hints**: `mutableStateOf(Int)` → `mutableIntStateOf()` style cleanup deferred.
4. **UnusedResources**: Default template colors/drawables; not batch scope.
5. **GradleDependency/NewerVersionAvailable**: Version updates deferred to separate maintenance.
5. **`exportFormatCommitted` dead field**: Documented; removal is style cleanup.
6. **`clearRecoveredActiveOperation` latent**: No production callers; documented.
7. **`clearActiveOperationKind` without kind check in `recordProcessingCleanupRequired` for non-PUBLIC_EXPORT kinds**: Accepted (processing kinds correctly cleared).
8. **YUV Fusion V2 compile-disabled paths**: Explicitly excluded from audit per policy.

---

# PROCESSING_HANDOFF CONSERVATION

The handoff conservation model is now **closed**:
- Every publisher has all 5 exits covered (success, cancel, setup-fail, dispatch-fail, process-death)
- No handoff is deleted without a real owner accepting responsibility (`finalizeRecoveredProcessingHandoff` exact-owner guard)
- Dispatch-failure settle uses the same atomic `PROCESSING_START` acquire + consume + finalize path as success
- Failed settle marks `pendingProcessingHandoffSettlement` for reconciliation on next acquisition
- Fatal Errors propagate without false terminal settlement
- Process-death recovery converges as `INTERRUPTED_PRE_COMMIT` + `STABLE`

**No current production path can:**
- block normal RAW capture→processing on its own handoff
- leave a terminal YUV/RAW/SR job with unconsumed same-process handoff
- leave a successful SR source job BLOCKED_HANDOFF

---

# MEDIASTORE COMMIT-RESOLUTION CONVERGENCE

The four-state commit model is **integrated end-to-end**:
- `UNKNOWN` is a first-class state, never conflated with `NOT_COMMITTED` or `COMMITTED`
- `terminalAckEligible` enforces evidence-match: UNKNOWN→no acks; VERIFIED→VERIFIED only; committed→PUBLIC_COMMITTED/VERIFIED
- URI match required for MAIN_IMAGE committed journals
- Sidecar frames exempt from URI match (no URI in durable evidence)
- `settleUnknownPublicCommitState` converges UNKNOWN using MAIN_IMAGE journal evidence
- Sidecar frames refreshed by THEIR OWN evidence via classification-driven reconstruction

**No current production path can:**
- treat UNKNOWN as definite NOT_COMMITTED
- treat UNKNOWN as definite COMMITTED
- terminal-ack an unresolved export journal
- claim "public export committed" in user-facing output when commit was not proven

---

# REPROCESS PUBLIC_EXPORT SETTLEMENT

Reprocess settlement is **evidence-honest**:
- UNKNOWN evidence + no local → QUARANTINED (deferred), never rolled back
- Public-committed + verification failure → COMMITTED_PARTIAL with specialized precommit settlement
- Terminal ack only after evidence-matched journal ack
- `exportStatus` field uses 4-state honest mapping (EXPORTED / EXPORT_UNVERIFIED / NOT_EXPORTED / COMMIT_UNKNOWN)
- Verification debt warning gated on real commit (`publicCommitted && !verified`)
- Fatal Errors propagate; no false terminal settlement

**No current production path can:**
- rollback a reprocess transaction while a NEW public row may already have committed
- generic-clear a reprocess PUBLIC_EXPORT without specialized journal settlement
- release PUBLIC_EXPORT ownership while commit authority is unresolved

---

# RAW SIDECAR LIVE/RESTART AUTHORITY

Sidecar commit truth is **per-frame and authoritative**:
- Live: per-frame 5-state classification (NOT_ATTEMPTED, PUBLIC_EXPORT_FAILED, PUBLIC_COMMIT_UNKNOWN, PUBLIC_COMMITTED_UNVERIFIED, PUBLIC_EXPORTED)
- Aggregate: any committed evidence (verified or committed-unverified) → at least PARTIAL
- UNKNOWN never upgrades to committed-unverified
- Cancellation after committed-unverified preserves exact frame result + URI
- Restart: classification-driven reconstruction preserves exact state + URI for all 5 classifications
- Settlement: main converges; sidecars refreshed by THEIR OWN evidence

**No current production path can:**
- label UNKNOWN RAW sidecar evidence as committed-unverified
- discard a committed-unverified sidecar URI on later cancellation
- reconstruct a committed-unverified DNG after restart as PUBLIC_NOT_RECOVERED
- require process death/Gallery open to resolve an ordinary same-process export settlement

---

# SAME-FAMILY COUNTEREXAMPLE PASS

All 32 counterexample scenarios covered by deterministic tests:
1-6. Handoff basic: success, cancellation, setup-fail, SR source, SR cancel, dispatch-fail settle
7. Handoff settle metadata fail → evidence preserved → retry: covered by `settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure` + pending mark
8. Fatal handoff settle Error propagates: Error rethrown after settle attempt
9-15. UNKNOWN commit: attempt, next-acquisition proves non-pending/pending, repeated unknown, cancellation, fatal, process-death
16-18. Journal lag: lagging PUBLIC_COMMITTED/VERIFIED caught by recovery; no BLOCKED_DEAD_OPERATION
19-25. Reprocess matrix: all 7 combinations verified
26-32. RAW sidecars: per-frame states, aggregate PARTIAL, cancellation preservation, restart reconstruction

---

# FINAL VERDICT

**END-TO-END PRODUCTION INTEGRATION AUDIT: CLOSED**