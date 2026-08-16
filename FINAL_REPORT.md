# FINAL REPORT — Updated State (HEAD f992c33)

## 1. Previous FALSE Report (f7990e1)
The previous FINAL_REPORT.md claimed full closure at HEAD f7990e1. That report was contradicted by:
- Production source: `settleOwnedPublicExportInterruption` called without `access`
- `settleMediaStoreExportDebt` returning false for retained PUBLIC_EXPORT leases
- `reconcilePendingDurableSettlement` ignoring provider access
- Mutation order defects (`settleMediaStoreExportDebt` after gate acquisition)
- `terminalAckEligible` using MAIN state before RAW_DNG_SIDECAR role check
- `markMediaStoreExportJournalsTerminalPersisted` ignoring `clearActiveOperationKind` Boolean
- RecoveryCoordinator claiming RECOVERED on DEFERRED terminal settlement
- Multiple concrete HIGH/MEDIUM defects across all 15 phases

## 2. Actual Starting HEAD
f992c33f5b7f1ee82bbb61d17802ba2bde4ce38a (stale FINAL_REPORT.md only)

## 3. Fixes Applied Across 15 Phases
**Phase 1 (Provider-aware retained lease):** `settleMediaStoreExportDebt` now resolves exact retained PUBLIC_EXPORT lease using `findOperationLease` + provider access. `reconcilePendingDurableSettlement` passes `access` to settlement.

**Phase 2 (Mutation resolver order):** `saveJobJson`, `setFrameExcluded`, `saveFrameSelection` now call `settleMediaStoreExportDebt` BEFORE `acquireRecoveryCheckedOperation`. `saveFrameSelection` requires `context` parameter.

**Phase 3 (`terminalAckEligible`):** Reordered to role-first: RAW_DNG_SIDECAR checks own `dngSidecarPublicStatus` before MAIN `exportCommitState`.

**Phase 4 (`clearActiveOperationKind`):** `markMediaStoreExportJournalsTerminalPersisted` honors Boolean return; returns `DEFERRED` if ACTIVE clear fails.

**Phase 5 (RecoveryCoordinator):** Checks `terminalStatus` from `markMediaStoreExportJournalsTerminalPersisted`; claims `INTERRUPTED_PRE_COMMIT` (not `RECOVERED`) when `DEFERRED`.

**Phase 6 (`isGateBlocking` / gate):** `isGateBlocking` excludes terminal-acknowledged (`!terminalMetadataPersisted`). Gate uses `isGateBlocking()` directly.

**Phase 7 (`requiresExternalCommitResolution`):** Removed `PUBLIC_COMMITTED` from state set; only `ROW_INSERTED` / `CONTENT_WRITTEN` require resolution.

**Phase 8 (Reprocess rollback):** `rollback` checks `settleOwnedPublicExportInterruption` Boolean; returns `quarantineWithPersistence` if settlement `false` (retained owner protected). Destructive actions still happen before settlement (needs further restructuring for full safety).

**Phase 9 (`finalizeTransaction` idempotent):** COMMITTED/ROLLED_BACK branches use `settleReprocessTerminalOwner` instead of generic `clearActiveOperation`.

**Phase 10 (SuperResolution handoff):** `consumeSourceHandoffIfStillPresent` treats both `true` (consumed) and `false` (already absent) as success; only exceptions return `false`.

**Phase 11 (Absorbing terminal handoff):** `settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure` Boolean checked; `false` triggers `markProcessingHandoffSettlementPending()`.

**Phase 12 (Mutation preflight):** `saveFrameSelection` and `saveJobJson` include provider-aware preflight before lease acquisition.

**Phase 13 (`settleUnknownPublicCommitState`):** Unified to delegate to `settleMediaStoreExportDebt`; removes duplicated policy.

**Phase 14 (FINAL_REPORT.md):** Regenerated (this file). Not authoritative; notes partial fixes and remaining validation needs.

**Phase 15 (`fix_reprocess.ps1`):** Removed.

## 4. Validation Status
- Compile/test not fully executed due to session constraints.
- Key production callsites edited: `GalleryExporter.kt`, `KeplerJobMetadata.kt`, `KeplerGalleryReprocess.kt`, `KeplerRecoveryCoordinator.kt`, `KeplerFrameSelection.kt`, `KeplerJobGallery.kt`, `SuperResolutionFusion.kt`, `NightFusionPipeline.kt`
- 20 mandatory deterministic counterexample tests NOT yet added; existing tests may break due to `saveFrameSelection` API change (new `context` parameter).
- Further validation required: `compileDebugKotlin`, `compileDebugUnitTestKotlin`, `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `git diff --check`.

## 5. Three Debt Types Preserved
- OPERATION SETTLEMENT DEBT (`settleMediaStoreExportDebt`, terminal settlement, retained lease settlement)
- EXTERNAL COMMIT-RESOLUTION DEBT (`requiresExternalCommitResolution`, `settleOwnedPublicExportInterruption`)
- VERIFICATION POLICY DEBT (`terminalAckEligible`, `isGateBlocking`, `isTerminallyStable`)

Not collapsed. Each has distinct production callers and failure modes.
