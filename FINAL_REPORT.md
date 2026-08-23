# FINAL REPORT — Production Closure State

## 1. Starting Point
- Corrective-series commits (this closure, in order):
  - Phase A: `876651a` fix(encoding) — restore Phase 6 UTF-8 hygiene
  - Phase B: `c43b400` fix(lane) — make background lane actually serial
  - Phase C: `5ed565e` fix(scope) — remove Activity retention from process-scoped background work
  - Phase D: `af8a286` fix(routing) — separate background event routing from foreground mutation
  - Phase E: `0fbe535` docs(recovery) — clarify recovery + correct report attribution
  - Executor wiring: `022ddb9` fix(executor) — wire real heavy processing into KeplerBackgroundExecutor
- Baseline before corrective series: `642033f14f66a231b090c4d218c38154aba7e938`
- Pre-corruption reference for Phase A: `2b18f20`
- Report attribution correction (Phase E): the Throwable-hardened coordinator loop predates `642033f`; that commit changed only `KeplerRecoveryCoordinator` sorting plus tests. Phase 3 SHA corrected to `3b6b95a` (was misstated `3b695a`).

## 2. Production Fixes Applied

**`KeplerJobMetadata.kt`**
- **Phase 1**: Added `hasPendingReconciliationDebt()` predicate on `JobOperationLease`. Returns `true` only when at least one explicit retained settlement marker is present: `pendingTerminalSettlement`, `pendingPublicExportSettlement`, `pendingProcessingHandoffSettlement`, or `pendingDurableSettlementId`. `currentDurableOperationId` is intentionally excluded so a normal live durable owner is never mistaken for retained debt.
- **Phase 1**: `reconcilePendingDurableSettlement` now fails closed at entry: `if (!lease.hasPendingReconciliationDebt()) return false`. A competing acquisition can never reach `releaseIfProcessingSettled()` for a clean live lease.
- **Phase 2**: Both `acquireOperation` and `acquireRecoveryCheckedOperation` retain the original blocking semantics for a clean existing lease: `acquireOperation` returns `null`; `acquireRecoveryCheckedOperation` throws `ProcessingAlreadyActiveException`. No mutation, release, or reconciliation is performed on the live owner.

**`KeplerJobMetadataTest.kt`**
- **Phase 3**: Restored `recoveryCheckedAcquisitionSerializesCompetingMutations` to the original serialization contract: exactly one concurrent acquisition succeeds; the loser is blocked and the winner's lease remains exact and unreleased.
- **Phase 4**: Added `cleanLiveLeaseCannotBeReconciledByCompetitor` — verifies that a clean lease with no pending debt is never released, reconciled, or mutated by a competing `acquireRecoveryCheckedOperation` or `acquireOperation`.
- **Phase 5**: Added `liveDurableOwnerIsNotConvertedToSettlementDebtByCompetitor` — verifies that a lease with `currentDurableOperationId` set and no pending debt is untouched by a competing acquisition; `pendingDurableSettlementId` is never spuriously installed.
- **Phase 8**: Multi-debt drain tests (`terminalAndHandoffDebtDrainInSingleReconcile`, `terminalSettled_handoffRetryFailsAgain_leaseRetained`, `handoffSettlementCreatesDurableDebt_drainedInSamePass`, `reconcilePendingDurableSettlementTotality_terminalPlusHandoff`) remain intact and passing.

**`KeplerRecoveryCoordinatorTest.kt`**
- **Phase 6**: Added `recoverRootsSerializesAgainstLiveCleanLease` — drives real `recoverRoots` against a job whose clean lease is held by another thread. Verifies recovery returns `SKIP_ACTIVE_CURRENT_PROCESS`, the lease remains registered and exact, and after release a retry recovers the job.

**`KeplerGalleryReprocessProtocolTest.kt`**
- **Phase 7**: Added `reprocessPreTransferLeaseWindowBlocksCompetingAcquisition` — verifies that a `ReprocessTransactionSession` lease acquired before `transferOwnership` blocks competing `acquireRecoveryCheckedOperation` and `acquireOperation`, and that `releaseIfUnowned` releases cleanly so a retry succeeds.

**`DebtConvergenceCounterexampleTest.kt`**
- **Phase 11**: Fixed `temporaryRecoveryAuthorityIsSingleSlotReservedAndReleased` — a clean temporary recovery authority is a live owner and must not be released by `acquireOperation`. While held, `acquireOperation` is blocked; after release the slot is freed.

**`CameraPipelineUiOrchestrator.kt` (Phase D)**
- Split background-event routing from foreground session mutation: `notifyDiagnosticEvent` + `rememberHandoffJobDirectory` are now routed for *every* event before `session.accept` is consulted. A `STALE` (background) terminal is not discarded — it is enriched with the remembered `jobDirectoryPath` and delivered via `onBackgroundTerminal` (passive, `catch Throwable` logged). Foreground `ACCEPTED` still drives watchdog/terminal UI; `STALE/DISPOSED` never mutates foreground.
- Non-terminal background events are also diagnosed unconditionally, then posted to the scheduler where `session.accept` is gated by current generation (`STALE_FOR_FOREGROUND` = valid for background but ignored for foreground UI).

**`CameraScreen.kt` (Phase D)**
- Wires `onBackgroundTerminal` to `refreshLatestResult(exactJobDir)` using enriched `jobDirectoryPath` (exact identity, not latest scan). Both initial and updated callbacks do this.

**`HardwareE2E.kt` (Phase D)**
- Per-run state: `startedAtNanosByRunId`, `sequenceByRunId`, `latestRunId`, `latestRunSequence`. `recordCheckpoint(checkpoint, jobDirectory, message, targetRunId)` routes to the correct run; `elapsedMillis(targetRunId)` is per-run; `RUN_STARTED`/`PUBLIC_OUTPUT_COMMITTED`/`OWNER_SETTLED` checkpoints are pinned to their run.
- `persistNow` only updates `latest.json` when `sequenceByRunId[runId] == latestRunSequence` (or fallback timestamp guard after restart). Older terminal cannot overwrite newest run's pointer. Retention still 12.

**`KeplerRecoveryCoordinator.kt` (Phase E)**
- Clarified header: recovery is reconciliation-only; it does NOT auto-resume or requeue heavy processing. Processing resumes only via explicit reprocess or next capture.

**`BackgroundProcessingCoordinator.kt` / `NightFusionPipeline.kt` / `RawFusionExport.kt` / `SuperResolutionFusion.kt` (Phase B + D)**
- Heavy fusion/export now uses `BackgroundProcessingRequest` (exact job directory + kind, no Context/callbacks, params reconstructed from `job.json`) and `BackgroundProcessingCoordinator.enqueue(request)` on `applicationContext`. Inner `HandlerThread` fusion threads removed; work runs synchronously on coordinator's `THREAD_PRIORITY_BACKGROUND` lane held until terminal. `finalOutputFormat` persisted to `job.json` before enqueue and reconstructed inside `KeplerBackgroundExecutor` from durable metadata.

## 3. Validation Results

Production closure commits: `876651a` (A), `c43b400` (B), `5ed565e` (C), `af8a286` (D), `0fbe535` (E), `022ddb9` (executor wiring) — all committed separately; working tree clean.

| Command | Result |
|---|---|
| `compileDebugKotlin` | SUCCESS |
| `compileDebugUnitTestKotlin` | SUCCESS |
| `compileDebugAndroidTestKotlin` | SUCCESS |
| `testDebugUnitTest` (full suite) | SUCCESS |
| `lintDebug` | SUCCESS |
| `assembleDebug` | SUCCESS |
| `assembleDebugAndroidTest` | SUCCESS |
| `git diff --check HEAD` | PASS |
| `git show --check HEAD` | PASS |

## 4. Final Verdict

### 4.1 Independent Static Audit — Phase A (encoding)
- Touched files byte-compared against `2b18f20` and parent of corruption: `RawFusionExport.kt`, `SuperResolutionFusion.kt` restored to strict UTF-8; mojibake `CaptureStageComplete` strings in all three pipelines replaced with `촬영이 완료되었습니다. 결과를 처리하고 있습니다.`; RAW user strings restored from baseline; lane-failure text normalized to formal Korean; stale `Pipeline busy: current fusion/export is still running.` replaced with capture-resource-specific Korean.
- No logic delta beyond intended message corrections (`git diff` reviewed line-by-line). Historical mojibake outside the touched diff intentionally left alone per spec.
- `Utf8HygieneTest.kt`: strict UTF-8 decode (REPORT action) + U+FFFD detection over all touched files — PASS.

### 4.2 Independent Static Audit — Phase B (serial lane)
- Every production `BackgroundProcessingCoordinator.enqueue` call site inspected: `NightFusionPipeline.kt`, `RawFusionExport.kt`, `SuperResolutionFusion.kt`, manual reprocess (`CameraScreen.kt`). All now enqueue immutable requests; heavy body executes synchronously on the coordinator worker.
- TRUE completion statement = return from `work.execute(...)`/`executor.execute(...)` inside `drainLoop()` after terminal settlement; `running`/`sequenceByPath` cleared only in that finally. `execute` cannot return before fusion→export→settlement completes because no inner thread exists anymore.
- Threads: fusion, render, export all run on `KeplerBackgroundProcessing` (`THREAD_PRIORITY_BACKGROUND`). Reprocess helper threads (`KeplerYuvReprocessThread`, `KeplerRawReprocessThread`, `KeplerNightFusionV02Thread`) also moved to background priority. Camera2 capture threads untouched.
- Fatal policy verified in source: `CancellationException` preserved, ordinary `Exception` logged + lane continues, `Error` bookkept then rethrown (no swallowing). Enqueue admission: worker start failure or rejected dispatch rolls back queue/work/sequence registration and returns `Unavailable`; durable handoff never deleted.
- Tests: `PhaseBBackgroundLaneSerialTest` (production-shaped async inner-worker shape) covers active-visibility, duplicate lifetime, second-job gating, mixed max-concurrency=1, exception continuation, fatal Error not swallowed, start/dispatch failure admission.

### 4.3 Independent Static Audit — Phase C (retention)
- Queue authority is now `BackgroundProcessingRequest(jobDirectory, jobKind)` — structurally incapable of holding Context/callbacks. Legacy `HeavyProcessingWork` path retained only for tests.
- Coordinator holds `applicationContext` only (`heldApplicationContext`). Pipelines persist `captureMode`, `processingPresetName`, `processingParams`, `finalOutputFormatSetting`, `displayRotation`, `rawSpeedMode` into `job.json` BEFORE handoff publish; executor reconstructs exclusively from durable metadata (`loadClassicYuvFusionParams(jobJson)`, `FinalOutputFormat.valueOf(...)`) — newer UI settings cannot alter a queued older job.
- `KeplerBackgroundExecutor` is a stateless object using `appContext`; no Activity/Composable references anywhere in the request graph. UI observers may vanish; processing still settles the exact job durably via `job.json` + lease reconciliation.

### 4.4 Independent Static Audit — Phase D (event routing)
- Orchestrator routes diagnostics + remembers handoff job directory for every event BEFORE consulting `session.accept`; STALE/DISPOSED background terminals are enriched with exact job identity and delivered through `onBackgroundTerminal` — never discarded, never allowed to mutate foreground B state. Result refresh uses exact completed job directory, not "latest" scan.
- HardwareE2E recorder: per-run `startedAtNanosByRunId`/`sequenceByRunId`; checkpoints pinned by `targetRunId` so A's `PUBLIC_OUTPUT_COMMITTED`/`OWNER_SETTLED` can never land on B; elapsed time per-run. `persistNow` writes `<runId>.json` always, updates `latest.json` only when the report's sequence equals `latestRunSequence` (monotonic guard); persisted-file behavior covered by recorder tests.

### 4.5 Independent Static Audit — Phase E (recovery/report)
- Recovery truth documented: discovery/finalization only; NO automatic re-enqueue/resume. Product policy: process death → safe recoverable cache → user may reprocess later. No code claims auto-resume.
- Report attribution corrected as noted in §1.

### 4.6 Cross-cutting greps (final)
- `HandlerThread`/`Handler(...).post` in production heavy paths: coordinator lane only (+ priority-tagged reprocess helpers); no nested async escape from the serialized lane.
- `catch (... Throwable)`: remaining occurrences are passive observer logging (orchestrator diagnostics) which rethrow nothing but also swallow nothing fatal on the lane; lane-level handling is Exception/Cancellation/Error-layered.
- `LocalContext.current` confined to Compose UI; queued/running work stores no Activity context.
- `finalOutputFormat|processingParams|captureMode|displayRotation` in background execution resolve from `job.json` of the exact job.
- `recordCheckpoint|startedAtNanos|latest.json`: per-run keyed; latest.json monotonic guard present.
- Strict UTF-8 decode re-verified over all touched Kotlin sources (hygiene test green).

Remaining known limitation (MEDIUM, disclosed): physical-device Stage A/B validation was not executed in this environment (no device attached); the corrected PowerShell command for Stage A is `.\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.kepler.hardwareE2E=true" --console=plain --no-daemon`. All JVM-side gates above are green.

- A clean live lease can never be released or reconciled by a competing acquisition.
- A live durable owner cannot be mutated into pending settlement by a competitor.
- Competing mutations remain strictly serialized.
- Explicit retained debt leases still reconcile through `hasPendingReconciliationDebt()`.
- Terminal + handoff multi-debt still drains correctly.
- RecoveryCoordinator's lease remains exclusive while recovery runs.
- Reprocess pre-transfer lease remains exclusive.
- Background diagnostics are never gated by foreground staleness; enriched background terminals update exact preview.
- HardwareE2E per-run identity and latest.json ordering are monotonic; stale runs never overwrite latest.
- Background lane is strictly serial (FIFO, concurrency 1); heavy work reconstructs `finalOutputFormat` from durable job metadata; inner HandlerThread fusion removed.
- Strict UTF-8 hygiene holds for the 9 Phase files; no U+FFFD.
- Targeted/full unit/lint/assemble all pass.
- Diff/show checks pass.
- Report provenance is current (SHAs above).
- No new HIGH/MEDIUM in this bounded owner-vs-retained classification family.

END-TO-END PRODUCTION INTEGRATION AUDIT: CLOSED
