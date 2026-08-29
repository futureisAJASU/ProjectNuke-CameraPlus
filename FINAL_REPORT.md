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

---

# CLOSURE ADDENDUM — Background Terminal Delivery + True Durable Handoff

Baseline HEAD: `f8d603f21a0b82b1df616c78a3baa2d3c3884442`
Final HEAD (report commit): see `git log -1`; correction commit: `9a368c9` (§A9 contents).

## A1. Root cause of the physical 180 s timeout

`KeplerBackgroundExecutor` performed real YUV/RAW/SR work, but its only status sink was
`Log.i("KeplerBackgroundExecutor", ...)`. No process-safe producer published
`ProcessingStage` / `ExportStage` / `Terminal`, so every background job after
`CAPTURE_STAGE_COMPLETE` was invisible to `CameraPipelineUiOrchestrator`,
`HardwareE2ERunRecorder`, and result UI. HardwareE2E saw no terminal within 180 s →
INCOMPLETE with only `CAPTURE_STAGE_COMPLETE`. This was NOT a Camera2 capture hang.

Two further latent defects found during the audit and fixed in this closure:
- **Handoff gate deadlock**: real captured jobs carry a `processingHandoffKind` marker
  published by the capture owner. The executor acquired its lease WITHOUT
  `consumesProcessingHandoff = true`, so `inspectRecoveryMutationGate` returned
  `BLOCKED_HANDOFF` for EVERY real captured job (synthetic test jobs without the marker
  masked this).
- **SR misrouting**: SR capture handoff enqueues `jobKind = PROCESSING_YUV`; dispatch sent
  those jobs to classic YUV fusion instead of super resolution.

## A2. Background event architecture

New `BackgroundPipelineEventHub.kt`:
- `BackgroundPipelineEvent(exactJobDirectory, jobKind, event)` — immutable envelope keyed
  by the EXACT request job directory (Option B: `CameraPipelineEvent` unchanged; terminal
  events additionally carry `jobDirectoryPath` in-record).
- Process-scoped singleton hub; bounded (16) subscribers via explicit disposable
  registration tokens; dispose removes the subscriber (no Activity/Composable retention);
  delivery is strictly observational (`catch Throwable` per subscriber); publishing with
  zero subscribers is a no-op. Durable job metadata + `JobOperationLease` remain the only
  production authority.

## A3. Terminal truth and error policy (executor)

- Exactly one terminal per accepted request via `CameraPipelineTerminalPublisher`,
  published in `finally` AFTER `releaseOrRetainForReconciliation()` (lease settlement
  boundary). Kind derived by pure `backgroundTerminalKind(required, publicExport,
  verified)` from structured production results — no log-string parsing.
  verified+public → `COMPLETE`; local-or-public commit without verification →
  `COMPLETE_PARTIAL` with exact flags; otherwise `FAILED`.
- Ordinary `Exception`: best-effort durable FAILED truth written to `job.json`
  (`processStatus=PIPELINE_FAILED`, `pipelineFailed=true`, failure source/type/message;
  skipped when a current-attempt output claim exists so committed exports are never
  contradicted), then exact `FAILED` terminal after settlement.
- `CancellationException`: `CANCELLED` terminal, precedence preserved (rethrow).
- Fatal `Error`: never downgraded; propagates while the lease retains reconciliation debt.
- Non-terminal `ProcessingStage(PROCESSING/DEMOSAICING)` → `ExportStage(EXPORTING)` →
  Terminal published when semantically reached; observer failures cannot affect them.

## A4. Exact job identity routing

- `HardwareE2ERunRecorder.runIdByJobDirectory`: bound at evidenced
  `CaptureStageComplete` (`handoffEvidenceComplete` + `jobDirectoryPath`) to the resolved
  run; retained for recorder lifetime so late terminals still resolve their own run.
- Routing priority: (1) exact job-directory mapping, (2) foreground generation mapping,
  (3) current run only for truly unbound foreground events. Generation 0 never falls back
  to current.
- New `recordBackgroundEvent(BackgroundPipelineEvent)`: routes ONLY through the exact-job
  mapping; an unbound envelope is dropped rather than attributed to the current run.
- Finalization: routed background terminal triggers `finalizeAfterTerminal(exactRun)` →
  EXACT correlation on the pinned job → strict classification with
  `allowPartialCompletion=false` / `requiresExport=true` preserved.

## A5. Durable handoff ordering (Phase 4)

All three pipelines (`NightFusionPipeline.kt`, `RawFusionExport.kt`,
`SuperResolutionFusion.kt`) now order the handoff boundary:
1. frames/artifacts persisted + manifest durable (capture owner, before `onComplete`);
2. ALL post-handoff processing parameters persisted (`captureMode`, `processingPresetName`,
   `processingParams`, `finalOutputFormatSetting`, plus `displayRotation`,
   `displayRotationAtCapture`, `rawSpeedMode` for RAW, plus `backgroundWorkerKind=
   SUPER_RESOLUTION` marker for SR);
3. processing handoff already durably published by the capture owner;
4. ONLY NOW emit evidenced `CaptureStageComplete` (shutter unlock follows from evidence);
5. enqueue the immutable exact-job request.

Metadata persistence failure (Phase 4A): no evidenced event, no
`processingHandoffDurable=true`, no enqueue; `settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure`
keeps the job reprocessable; formal Korean status + FAILED terminal published. Applies to
YUV, RAW, SR paths.

## A6. UI connection without retention (Phase 2)

`CameraScreen.kt` subscribes once per screen via `DisposableEffect(hardwareE2ERecorder)`;
dispose removes the subscription. Per background envelope: (1) passive diagnostics —
recorder ALWAYS receives it; (2) UI — terminal events refresh latest-result data for the
exact completed job only; preview pop suppressed while a foreground capture owns the
viewfinder; newer foreground capture status/progress/session never mutated; nothing routed
through `session.accept()` as an existence test.

## A7. Tests added

`Phase5BackgroundTerminalTest` (real lane + executor + metadata + lease + hub):
yuv/raw/sr accepted-request terminal sequences, ordinary-failure FAILED truth, partial-
export flags, verified-flag kind matrix, terminal-after-settlement ordering proof
(delivery-time lease/metadata snapshot), observer-failure isolation, disposed-subscriber
non-blocking, disposed-subscriber non-retention.

`Phase6HardwareE2EOverlapTest` (real recorder integration): captureA binds jobA→runA;
runB-current background terminal/stage/export routing to runA; exact-job finalization
while B current; runB isolation; latest.json monotonicity; generation-0 never falls back
when exact job known; unbound envelope dropped; strict single YUV/RUN full-checkpoint
reports (`RUN_STARTED…TERMINAL_COMPLETE, PUBLIC_OUTPUT_COMMITTED, OWNER_SETTLED`) with
PASS/EXACT under strict flags.

## A8. Static audit findings (Phase 7)

- Every evidenced `CaptureStageComplete(processingHandoffDurable = true)` site (3 total)
  is preceded by a successful required-metadata write with fail-closed early return.
- `PIPELINE_COMPLETE*` strings exist only as status text/durable metadata; no terminal
  kind derives from any log string.
- Routing greps confirm exact-job priority precedes generation precedes current; the
  remaining `current?.runId` uses are foreground checkpoints/API only.
- Queue/executor/hub contain no `Activity` / `LocalContext.current` / UI callback
  captures (only contract KDoc mentions).
- Strict UTF-8 hygiene re-verified over all touched sources (`Utf8HygieneTest` green;
  a PowerShell round-trip encoding incident during development was fully reverted and
  redone via tooling before commit).

## A9. Correction commit `9a368c9` (code + tests)

`BackgroundPipelineEventHub.kt` (new), `KeplerBackgroundExecutor.kt` (event surface,
terminal truth, error policy, handoff consumption, SR routing), `CameraPipelineEvents.kt`
(`isPublished()`), `HardwareE2E.kt` (exact-job map + `recordBackgroundEvent`),
`CameraScreen.kt` (hub subscription), `NightFusionPipeline.kt` / `RawFusionExport.kt` /
`SuperResolutionFusion.kt` (durable handoff ordering + metadata failure policy),
`Phase5BackgroundTerminalTest.kt`, `Phase6HardwareE2EOverlapTest.kt`.

## A10. Validation results (addendum)

| Command | Result |
|---|---|
| `compileDebugKotlin` | SUCCESS |
| `compileDebugUnitTestKotlin` | SUCCESS |
| `compileDebugAndroidTestKotlin` | SUCCESS |
| `testDebugUnitTest` (full suite, 1260 tests) | SUCCESS |
| New groups ×2 (Phase5 + Phase6) | SUCCESS ×2 |
| `assembleDebug` | SUCCESS |
| `assembleDebugAndroidTest` | SUCCESS |
| `lintDebug` | SUCCESS |
| `git diff --check` / `git show --check` | PASS |

Remaining known limitation (MEDIUM, disclosed): physical-device instrumentation rerun
(`adb shell am instrument -w -r -e timeout_msec 240000 -e kepler.hardwareE2E true
com.projectnuke.keplernightlab.test/androidx.test.runner.AndroidJUnitRunner`) must show
YUV/RAW progressing beyond `CAPTURE_STAGE_COMPLETE` to
`PROCESSING_STARTED → EXPORT_STARTED → TERMINAL_COMPLETE` (or concrete
`TERMINAL_FAILED`); a 180-second INCOMPLETE with only CAPTURE_STAGE_COMPLETE remains a
regression signal.

ADDENDUM VERDICT: BACKGROUND TERMINAL DELIVERY + TRUE DURABLE HANDOFF — CLOSED (JVM gates)


---

# STAGE-A INDEPENDENT AUDIT CLOSURE — Corrective Series (7 Phases)

## S0. Scope and Baseline
- Baseline HEAD: 0cce8ad2d8634c622e247bb0c33d18f92b0c413c (packed-YUV v1 + debug-intent gating already landed).
- Bounded correctness/instrumentation follow-up. The previously-corrected Stage-A fixes (full-acquisition YUV draining, canonical HEIF naming, native-RGBA RAW export, typed Camera2 acquisition progress, serialized background processing) were NOT reopened.
- Packed-YUV remains OUT of the production default path after this series.

## S1. Commits (each phase committed separately)
| Phase | Commit | Subject |
|---|---|---|
| 1 | 077d723 | fix(yuv): deadline drains ALL accepted persistence before any terminal |
| 2 | bd52095 | fix(background): SR dual identity, main-scope UI delivery, fatal settlement precedence |
| 3 | 614efef | feat(timing): wire real production call sites into capture timing ledger |
| 4 | a9558ba | feat(e2e): expose capture/background timing evidence in physical report |
| 5 | 4138e50 | fix(packedyuv): sync final header before publish; reject structurally invalid files |
| 6 | 70d495d | fix(yuv): quality metrics unconditional; heavy debug images stay gated |

(Phases 1-6 above; Phase 7 is the static-audit closure documented in S4.)

## S2. Phase Summaries

### Phase 1 — Durable handoff invariant for ALL accepted persistence work
- onDeadlineReached() now asks FIRST: does accepted persistence work remain (buffered/reserved/pendingCompletions/queued/inFlight > 0)? If yes -> enter/continue DRAINING regardless of FULL vs PARTIAL acquisition; nothing is published.
- Terminal classification runs only after every accepted task settles: strict SUCCESS predicate (persisted==requested, manifest complete, buffered/reserved/pending/queued/inFlight all zero), genuine PARTIAL_SUCCESS after full settlement, concrete FAILED on drain failure, actual TIMED_OUT when the bounded drain deadline expires with work outstanding.
- emergencySettleDeadline() FAILS CLOSED: any observable accepted work (live atomic pending ledger + executor counters + buffered/reserved + draining flag) settles TIMED_OUT - never SUCCESS/PARTIAL.
- Shutdown-drained worker tasks never posted completions and leaked pending-ledger entries forever (stalled drain settlement). Both buffered and direct persistence tasks now claim pending-ledger ownership via a single-winner CAS: non-throwing posts hand release to the owner envelope; never-attempted posts release at settlement exactly once.
- Tests: YuvCaptureOwnerAcceptedWorkTest (8 required cases) + updated YuvCaptureOwnerTest / ProductionYuvCaptureBridgeTest.

### Phase 2 — Background ownership leftovers
- 2A SR dual identity: BackgroundPipelineEvent(requestJobDirectory, resultJobDirectory); terminal carries resultJobDirectoryPath. Routing/diagnostics use the REQUEST identity (source capture job); HardwareE2E finalization reads the RESULT identity (SR output dir); UI refresh uses result identity with request fallback. Relationship persisted durably into BOTH job.json files (superResolutionSourceJobDirectory / superResolutionResultJobDirectory) immediately after output-dir creation and re-asserted on every full SR metadata write - always BEFORE terminal publication. No latest-job lookup anywhere.
- 2B Main-thread UI delivery: new BackgroundTerminalUiDispatcher - diagnostics immediate/thread-safe on the worker lane; ALL Compose mutation dispatched onto the camera-owned scheduler; CURRENT foreground truth re-queried from pipelineSession.snapshot() INSIDE the dispatched block. Terminal A during capture B refreshes data but never pops preview; idle foreground may show it.
- 2C Fatal settlement precedence: shared finalizeLaneAfterExecution - Error -> bookkeeping then RETHROW (in-flight preserved as suppressed); CancellationException -> preserved; Exception -> explicit reconciliation debt with boundary UNREACHED so settleTerminal publishes captureResourcesSettled=false instead of an ownership-settled claim unsupported by reality.
- Tests: BackgroundOwnershipPhase2Test + updated Phase5/Phase6 suites.

### Phase 3 — Timing ledger made real
- Every advertised milestone now has a REAL production call site: persistenceQueued (owner submit), workerStarted (task entry), encodeFinished (bounded around actual conversion+PNG+candidate-write+fsync span), writeFinished (atomic replace returned), verified (final verification succeeded), committed (accounting commit). Buffered frames additionally record conversionCompleted and the true fsyncFinished boundary (writePngBitmapToSink invokes an onSynced callback at the real fd.sync() instant). New YuvCaptureTimingHooks seam keeps every recorder a non-blocking atomic put; Camera2 callbacks are never blocked.
- processingHandoffPublishedAt recorded ONLY after publishProcessingHandoff returns true (YUV and RAW).
- RAW captureStageCompleteAt moved from finishSuccess (internal completion) to the evidenced durable boundary inside terminal settlement: metadata persisted + handoff published + capture owner cleared. RAW also records processingHandoffPublishedAt/captureResourcesSettledAt there.
- Single-frame YUV uses the same session/hook path (frame-count independent). SR is background-only: no capture-stage ledger applies; its durations live in backgroundStageTimings.
- Tests: CaptureTimingLedgerProductionTest - causal chain requestSubmitted <= firstCameraEvidence <= acquisitionComplete <= persistenceDrainComplete <= processingHandoffPublished <= captureResourcesSettled <= captureStageComplete (same-timestamp equality allowed), handoff-failure leaves published/settled/stage unset, real per-frame chain end-to-end through a production-shaped session.

### Phase 4 — Physical report exposes the new evidence
- HardwareE2EJobSummary carries nested captureTiming (HardwareE2ECaptureTiming: cameraAcquisitionMs, persistenceDrainMs, handoffSettlementMs, captureStageTotalMs, aggregate encode/fsync/verify, maxFrameEncodeMs, per-frame segments) and backgroundStageTimings.
- Flattened run-report fields promoted at finalization: cameraAcquisitionMs, persistenceDrainMs, handoffSettlementMs, captureStageTotalMs, backgroundProcessingMs, backgroundExportMs.
- Pure snapshot semantics: fromJson() performs no filesystem queries; every new field has a non-default round-trip test (HardwareE2ETimingExposureTest).

### Phase 5 — Packed-YUV V1 hardened (still NOT default)
- Final header digest patch is explicitly fsynced BEFORE atomic publication (durability sequence payloadSynced -> headerSynced -> published, test-observable).
- Structural verification rejects header-valid truncated payloads, payload decomposition mismatches (payloadLength == y+u+v), stride/plane invariant violations, insane dimensions, and exact-length padding.
- Contract documented: structural check does NOT stream payload digest; digest lives exclusively in the separately-named full verifier verifyFull() (= unpack), required before authoritative artifact use. unpack() fails closed on malformed lengths/strides/truncation BEFORE allocating plane buffers.

### Phase 6 — Debug artifact / quality metric separation
- Fixed: the image-artifacts-disabled early return skipped writeFusionQualityDiagnostics entirely, so production captures had NO quality metrics. New writeBoundedQualityEvidence computes bounded previews + full quality metrics JSON unconditionally through the same production entry point; only heavy full-resolution images/compare/crop sheets remain gated. Five heavy PNGs NOT restored.
- diagnosticIntent audit: no production writer existed. Real debug entry point (CameraScreen.runCameraJob with an instrumentation scenario) arms process-scoped intent; ColorFusion durably stamps diagnosticIntent=true into job.json at CREATION. Normal user captures keep production_main_camera_screen and are never stamped.
- Tests: YuvQualityDiagnosticsSeparationTest enters through the production entry point.

## S3. Validation Results (this series)
All phases committed separately; working tree clean; focused groups run x2 per phase; full unit suite green after every phase.

| Command | Result |
|---|---|
| compileDebugKotlin | SUCCESS |
| compileDebugUnitTestKotlin | SUCCESS |
| compileDebugAndroidTestKotlin | SUCCESS |
| testDebugUnitTest (full suite) | SUCCESS |
| assembleDebug | SUCCESS |
| assembleDebugAndroidTest | SUCCESS |
| lintDebug | SUCCESS |
| git diff --check | PASS |

## S4. Final Static Audit (10-point proof)
1. No full OR partial YUV handoff with accepted persistence outstanding: deadline classification checks acceptedPersistenceWorkRemains FIRST (YuvCaptureOwner.kt onDeadlineReached); drain completion re-checks before any classification; emergency path fail-closed on live counters.
2. SR request/result identities explicit: typed fields on BackgroundPipelineEvent + CameraPipelineEvent.Terminal.resultJobDirectoryPath; executor publishes dual identities; E2E finalizes by result; relationship durably linked both directions pre-publication.
3. No heavy worker callback mutates Compose state directly: hub subscriber delegates to BackgroundTerminalUiDispatcher; mutation only inside the scheduler-dispatched block; foreground truth re-read at delivery time.
4. Fatal Error not swallowed during lease settlement: finalizeLaneAfterExecution rethrows Error/Cancellation after bookkeeping; ordinary release failures mark the boundary unreached (no false settled claim).
5. Every advertised timing milestone has a real production call site: see Phase 3 map (ColorFusion / YuvCaptureOwner / RawFusionCapture).
6. Milestone names match authority boundaries: handoffPublished only after durable publication; captureResourcesSettled/captureStageComplete only after owner clear; committed only after accounting commit; fsyncFinished at the real fd.sync().
7. HardwareE2E exposes the new timing evidence: summary captureTiming/backgroundStageTimings + flattened run fields, round-trip tested non-default.
8. Packed-YUV rejects header-valid truncated payloads: exact total length check inside readHeader/unpack (validateStructure).
9. Packed-YUV final metadata durable before atomic publication: header patch fsync precedes replace; ordering test-enforced.
10. YUV quality metrics survive heavy-image gating: disabled path calls writeBoundedQualityEvidence through the same production entry point; metrics asserted present, heavy files asserted absent.

## S5. Physical Promotion Gate (PENDING)
- Code series complete. Packed-YUV production default remains OFF until physical evidence justifies the switch.
- Next step (manual, hardware): install BOTH APKs (./gradlew :app:installDebug :app:installDebugAndroidTest --console=plain --no-daemon), run the strict physical Stage A, use the newly exposed timing fields (cameraAcquisitionMs / persistenceDrainMs / aggregate+max frame encode/fsync/verify / backgroundProcessingMs / backgroundExportMs) to prove the current foreground bottleneck and record a before/after baseline. Only that baseline may justify enabling packed-YUV as the default.

---

# STAGE-B PERFORMANCE + RAPID SEQUENTIAL CAPTURE MEGA-BATCH

## B0. Physical verification status (MANDATORY READING)

PHYSICALLY VERIFIED BEFORE THIS BATCH (Stage-A baseline, Samsung Galaxy S24 / SM-S921N / Android 16,
strict HardwareE2E):
- optInYuv12MpMainCameraProductionBurst ........ PASS
- appLaunchesAndDiagnosticReportCanBeCreatedAndRead PASS
- optInRaw12MpMainCameraProductionBurstWhenSupported PASS
- OK (3 tests), Time: 170.554 s

THIS BATCH IS **CODE VERIFIED ONLY**. No new physical performance claim and no
Stage-B closure is made. Physical Stage-B + performance validation remains
pending until the human runs the commands in section B12.

## B1. Baseline and final HEAD

- Baseline commit: c036018 (post Stage-A closure; clean tree).
- Final HEAD of this batch: see `git log --oneline` P1..P14 series below.

## B2. RAW latency architecture before -> after

BEFORE (per ~25 MB RAW16 frame, 4-frame burst):
1. scalar per-byte extraction (2 JNI reads/pixel ~= 50M crossings/frame);
2. BufferedOutputStream write of packed rows;
3. fd.sync();
4. verify temp: FULL READ x2 (stream digest + identity fence) - size only;
5. atomic rename;
6. verify final: FULL READ x2 - size only;
   => 4 full payload reads (~100 MB) per frame before manifest commit.

AFTER:
1. one bulk row transfer per JNI crossing (SEQUENTIAL_BULK for compact
    pixelStride==2 planes, PADDED_ROW_PACK through ONE reusable bounded row
    buffer when rowStride > width*2 at pixelStride==2,
    SCALAR_FALLBACK only for genuinely exotic pixelStride != 2 layouts -
    Android Camera2 RAW_SENSOR is a 16-bit single-plane Bayer format that
    AOSP ImageReader maps to pixelStride == 2);
2. every payload byte streams through DigestingOutputStream (write-time SHA-256);
3. fd.sync() (unchanged count);
4. atomic rename (unchanged);
5. SINGLE strict post-publish verification of the FINAL file: size AND
   SHA-256 equality vs the write-time digest (fail-closed).
   => 2 full payload reads per frame; content truth STRICTLY STRONGER
   (digest equality vs prior size-only).
Durable evidence: job.json now carries rawPersistenceWriteStrategy
(SEQUENTIAL_BULK | PADDED_ROW_PACK | SCALAR_FALLBACK, plus per-frame
raw16WriteStrategy in the frames manifest), surfaced in HardwareE2E reports as
finalJob.rawMetadata.rawPersistenceWriteStrategy, so the physical report PROVES
which path the device used instead of inferring activation from elapsed time.

Removed foreground work: 50 MB/frame redundant read-back I/O; ~50M scalar JNI
byte reads/frame. Nothing else was removable - DNG is fully skipped when
NOT_REQUESTED, no preview/debug/fusion work exists before CaptureStageComplete,
metadata writes were already consolidated at first/last/terminal boundaries.

## B3. Timing authority (Phase 1)

CaptureTimingLedger now records, with REAL production call sites:
acquisition: image/result ARRIVAL ORDINAL instants (owner-serialized);
persistence (RAW): persistenceSubmittedAt, workerStartedAt, fsyncFinishedAt
(real fd.sync span), writeFinishedAt (atomic rename returned), verifiedAt
(final strict verify passed), committedAt (owner adoption); aggregates
rawBytesPersisted / rawPersistenceWriteMs / rawPersistenceSyncMs.
Derived causal segments exposed to HardwareE2E JSON:
cameraAcquisitionMs, postAcquisitionPersistenceMs (= persistenceDrainMs),
handoffPublicationMs, rawMetadataSettlementMs (lastCommit -> handoffPublished),
captureSettlementMs (handoffPublished -> resourcesSettled),
postAcquisitionToShutterMs (100% -> shutter admission), captureStageTotalMs.
Diagnostic-only Android Trace sections: Kepler_RAW_Acquisition,
Kepler_RAW_Persist, Kepler_RAW_Sync, Kepler_CaptureHandoff.
HAL pacing evidence: StreamConfigurationMap min-frame/stall durations for the
exact RAW stream are stamped into job.json (rawStreamTiming +
rawMinFrameDurationNs/rawStallDurationNs, surfaced via E2E rawMetadata) so a
physical report can answer "HAL slow or software slow?" by comparing observed
cameraAcquisitionMs against requestedFrames * minFrameDurationNs.

## B4. UI semantics

Two-state capture surface (pure model, unit-tested):
CAPTURING = acquisition fraction 0..100% (unchanged formula);
SETTLING_CAPTURE = acquisition visually holds 100%, independent truthful
persistence line "촬영 데이터 저장 중 · N/M" driven ONLY by persistedFrames;
RELEASED = durable handoff proven (shutter re-enabled).
The busy shutter indicator says "저장 중" during settling (not "촬영 중").
Monotonic bar guarantees 100% never drops during settlement.
Background queue surface (non-blocking): active kind + "처리 대기 N건" +
bounded completion flash from exact background terminals; shutter gating is
untouched by any background state.

## B5. Durability invariants preserved (Phase 3/7)

temp write -> payload fd.sync -> atomic rename -> fail-closed strict verify ->
owner adoption (committedAt) -> drain -> terminal manifest -> handoff publish ->
resources settled -> CaptureStageComplete. Exactly one payload sync per frame;
fsync failure or digest mismatch throws before adoption so a frame can never be
committed unproven; Image close paths unchanged (covered by existing
RawSaveCompletionTest ownership tests).

## B6. Contention policy (Phase 6)

ForegroundCaptureActivitySignal: process-scoped AtomicInteger window driven by
the three real persistence task bodies (RAW save task, YUV BufferedEncodeTask,
YUV direct-path task). The serialized heavy lane calls at most ONE Thread.yield
at real stage-transition boundaries while the signal is active. No sleeps, no
locks, no cancellation, FIFO preserved. Heavy lane priority remains
THREAD_PRIORITY_BACKGROUND (verified assertion already in executor).

## B7. Packed-YUV A/B state (Phase 7)

YuvPersistenceStrategy {PNG, PACKED_YUV_V1}; production default PNG. Selection
is resolved ONCE at capture creation from a debug settings key and stamped as
DURABLE job.json metadata (yuvPersistenceStrategy); foreground naming and the
buffered encoder honor it; direct-mode captures keep PNG (documented scope).
Background lane converts PACKED_YUV_V1 sources (verifyFull -> BT.601 ->
rotation -> atomic PNG publish) into the EXACT inputs fusion already consumes
and rewrites manifest entries (packedSourceFilename retained). Conversion is
idempotent across process death; digest failures fail closed. Legacy jobs
without the key reprocess unchanged. NOT promoted: promotion requires the
physical foreground-latency A/B below.

## B8. Rapid sequential architecture + backpressure

Foreground lane max 1; heavy lane max 1 FIFO; queue content = immutable exact
job refs only (no pixels, no Activity callbacks). Deterministic overlap proof
seam: KeplerBackgroundExecutor.heavyLaneGateForTest (null in production).
Bounded backlog: MAX_QUEUED_HEAVY_JOBS=3 durable refs beyond the running job;
overflow rejects NEW handoffs cleanly (QueueFull) without deleting/reordering;
capacity recovers exactly on drain. UI blocks a new capture BEFORE acquisition
with formal Korean guidance when capacity is exhausted; pipeline-level QueueFull
keeps retain-for-recovery truth.

## B9. Performance acceptance model (Phase 13)

Primary foreground metrics: cameraAcquisitionMs, postAcquisitionToShutterMs =
captureStageCompleteAt - cameraAcquisitionCompleteAt (THE user-observed
100%-to-shutter interval), captureStageTotalMs; RAW adds rawPersistenceWriteMs /
rawPersistenceSyncMs / rawPersistenceDrainMs(=persistenceDrainMs) /
rawMetadataSettlementMs; YUV adds yuvPersistenceDrainMs(=persistenceDrainMs).
Background: processingMs/exportMs (existing backgroundStageTimings) +
unpackConvertMs for packed jobs. Stage-B: timeFromAHandoffToBCaptureStart,
maximumBackgroundHeavyConcurrency, queuedJobsPeak. An optimization counts only
if it improves postAcquisitionToShutterMs or captureStageTotalMs without
regressing correctness, quality, memory safety, thermal behavior, or recovery.
Pure-software post-acquisition delays > 1 s are explicit optimization targets.
CI never fails on host-vs-device latency thresholds; thresholds live only in
the Samsung Galaxy S24 / SM-S921N validation report.

## B10. Phase 8 audit result

backgroundStageTimings + debug-artifact gating (diagnosticIntent) already land
in Stage-A; full-resolution diagnostic PNGs remain gated off normal captures and
quality JSON stays available. Static pass found NO additional demonstrably
redundant background work safe to remove without image-quality risk; duplicate-
decode candidates are recorded as future seams behind benchmark evidence.

## B11. Final static audit checklist (Phase 14)

1 acquisition 100% = Camera2 pair truth ..................... CaptureProgress.kt
2 settlement never mislabeled as sensor capture ............. CaptureSettlementUiTest
3 RAW cannot hand off pre-durability ........................ RawFusionCapture submitTerminal
4 YUV cannot hand off with outstanding work ................. YuvCaptureOwner drain gate
5 admission = durable release, not processing ............... CameraPipelineUiSession.canAdmitNewCapture
6 heavy concurrency exactly one ............................. BackgroundProcessingCoordinator drainLoop + RapidSequentialOverlapTest
7 B coexists with A without shared foreground ownership ..... CaptureSettlementUiTest/RapidSequentialOverlapTest
8 old terminal cannot mutate new generation ................. LifecycleOverlapRegressionTest
9 queue holds refs only ..................................... ExactJobRef contract
10 backlog explicitly bounded ............................... MAX_QUEUED_HEAVY_JOBS
11 queue-full deletes nothing ............................... BackpressureTest
12 RAW optimization keeps fsync/atomic publication .......... RawPersistenceOptimizationTest
13 packed-YUV non-default ................................... YuvPersistenceStrategy.PNG default
14 PNG + packed both reprocessable .......................... PackedYuvStrategyTest.mixedHistoricalPngJob
15 quality metrics independent of debug PNGs ................ Stage-A gating (unchanged)
16 timing fields causal, real sites ......................... RawCaptureTimingLedgerTest
17 Stage-A strictness unchanged ............................. androidTest Stage-A file untouched
18 Stage-B overlap deterministic (gate seam, no sleeps) ..... HardwareE2EStageBInstrumentationTest
19 no latest-job inference for routing ...................... dual identity envelope/dispatcher tests
20 Error/Cancellation precedence intact ..................... all new try/finally wrappers reviewed; worker identity revert commit

## B12. PHYSICAL NEXT STEPS (human only, PowerShell)

.\gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain --no-daemon

# Stage-A regression (must stay green)
adb shell am instrument -w -r `
  -e timeout_msec 300000 `
  -e kepler.hardwareE2E true `
  com.projectnuke.keplernightlab.test/androidx.test.runner.AndroidJUnitRunner

# Stage-B rapid sequential overlap
adb shell am instrument -w -r `
  -e timeout_msec 600000 `
  -e kepler.hardwareE2E.stageB true `
  com.projectnuke.keplernightlab.test/androidx.test.runner.AndroidJUnitRunner

# Packed-YUV physical A/B (opt-in; writes/restores the DEBUG strategy key itself,
# runs BOTH the packed capture and the paired PNG reference capture, restores PNG)
adb shell am instrument -w -r `
  -e timeout_msec 300000 `
  -e kepler.hardwareE2E.packedYuv true `
  com.projectnuke.keplernightlab.test/androidx.test.runner.AndroidJUnitRunner

Report must capture per pipeline: cameraAcquisitionMs, postAcquisitionToShutterMs,
persistenceDrainMs, captureStageTotalMs; RAW additionally rawWriteMs(rawPersistenceWriteMs),
rawSyncMs(rawPersistenceSyncMs), rawMetadataSettlementMs, rawBytesPersisted;
background processingMs/exportMs; Stage-B A-handoff/B-start ordering, peak queue depth,
max heavy concurrency == 1 evidence.

PACKED-YUV PROMOTION RULE: promote only after physical A/B shows meaningful
foreground latency benefit on Samsung Galaxy S24 / SM-S921N with zero correctness/thermal/memory/
background regressions. RAW OPTIMIZATION PROMOTION RULE: prefer changes that cut
cameraAcquisitionCompleteAt -> captureStageCompleteAt.

---

# PRE-PHYSICAL MEGA-BATCH CLOSURE — RAW_SENSOR Fast Path + Exact Stage-B Harness + Packed-YUV A/B

## P0. Commit accounting correction

The mega-batch bundle contains **14 commits after `c036018`** (5110f60, b32830c,
dcfd868, 7b801b1, ae83ba6, fc1a0df, 543fe56, fa8cd1f, 83c58c3, ff52930,
50261d3, 3c095fb, 8818f1f, 7306e7a), not 16. Baseline of THIS bounded
pre-physical patch: `7306e7a8e8cec082e2aa2b7f2935498e7ad9ecc8`.

## P1. RAW_SENSOR bulk fast path corrected (Phase 1)

The mega-batch gated the raw16 bulk path on `pixelStride == 1`, but Android
Camera2 RAW_SENSOR is a single-plane 16-bit-per-sample Bayer format that AOSP
ImageReader maps to **pixelStride == 2** - real Samsung Galaxy S24 / SM-S921N frames would have run
the ~50M-scalar-crossing fallback forever. Corrected classification in
`writeRaw16Rows`:

- pixelStride == 2 && rowStride == width*2 -> SEQUENTIAL_BULK (one bulk
  ByteBuffer transfer of exactly width*2 bytes per row);
- pixelStride == 2 && rowStride > width*2 -> PADDED_ROW_PACK (exactly width*2
  bytes packed per row; trailing padding ignored; only the mapped payload of
  the row being read is required: rowOffset + width*2 <= buffer.limit);
- genuinely exotic positive pixelStride != 2 layouts -> SCALAR_FALLBACK with
  EXACT legacy byte semantics (two bytes read at rowOffset + x*pixelStride; no
  byte swap, no endianness reinterpretation).

Durable payloads remain byte-identical to the legacy scalar implementation
(unit-proven against an exact legacy reference for compact + padded layouts).
RAW durability ordering, digest-at-write, fsync, atomic publication, and
manifest semantics are untouched.

## P2/P3/P4. Stage-B harness made identity-exact (Phases 2-4)

- PINNING: each sequential run id is pinned at ITS OWN evidenced
  CaptureStageComplete (`HardwareE2EStageBRunPinning.selectNewHandoffRun`,
  earliest-started new evidenced run vs excluded ids) BEFORE the next capture
  starts; terminals are awaited by EXACT pinned run id (`read(runId)`), never
  by a mutable newest-first "latest matching pipeline" scan. Same-pipeline
  pairs can no longer misidentify reportA = B.
- CONFIGURATION ORDER (mixed pairs): YUV->RAW / RAW->YUV previously clicked B
  FIRST and saved settings afterwards, so CameraScreen captured A's Compose
  pipelineMode at the click. Now: after A's durable handoff is pinned,
  `configureSettings(pipelineB)` persists + recreates + re-verifies the store,
  THEN shutter B is clicked, and `reportB.scenario.selectedPipelineMode ==
  pipelineB` is asserted before the lane is released.
- OVERLAP EVIDENCE: while A is held at `heavyLaneGateForTest`, the coordinator
  snapshot must show active job = A exactly, queued contains B,
  queuedCount >= 1, and A != B exact directories; runId/jobDir pairs, click/
  handoff/terminal timestamps, and coordinator queued peak are emitted as
  STAGE_B_EVIDENCE in the instrumentation log. No sleeps, no production delay;
  the gate stays null in normal production.

## P5. Packed-YUV physical A/B actually runnable (Phase 5)

Two latent production breaks found and fixed deterministically (both would
have failed any real PACKED_YUV_V1 capture):
- writeColorJobJson is a FULL-replacement metadata write and silently dropped
  the creation-time `yuvPersistenceStrategy` stamp before the background lane
  read it; the capture writer now re-stamps it on every rewrite (previous-job
  carry-forward included). Unit-proven: strategyStamp_survivesFullMetadataRewrites.
- The background converter read packed sources from manifest key "filename",
  while production capture manifests use "file" (all fusion readers use
  "file"); conversion now reads both shapes and repoints both plus retains
  packedSourceFilename. Unit-proven:
  packedStrategy_productionManifestUsesFileKey_andIsConverted.

New opt-in androidTest `HardwareE2EPackedYuvInstrumentationTest`
(`-e kepler.hardwareE2E.packedYuv true`) writes the DEBUG strategy key before
Activity recreation, runs the SAME strict 12MP 4-frame YUV production capture,
asserts durable strategy metadata, .yuvpack source manifest, FULL structural +
SHA-256 verification of every packed source, strict PASS conversion/fusion/
export, and ALWAYS restores PNG in finally. A paired PNG reference capture
under the same flag yields directly comparable HardwareE2E reports.
PACKED_YUV_V1 remains NON-default; normal Stage-A does not depend on it.

## P6. UI backpressure aligned with coordinator policy

evaluateBackpressure now BLOCKs exactly when queuedCount >= MAX_QUEUED_HEAVY_JOBS
(= BackgroundProcessingCoordinator.enqueue QueueFull boundary) and no longer
subtracts the running job from the queued cap. Admitting a capture while
queuedCount == maxQueued - 1 leaves exactly one slot for the handoff about to
be enqueued: the lane represents 1 active + up to 3 queued on both sides.
uiAdmission_matchesCoordinatorAdmission_atEveryDepth proves UI admission ==
coordinator admission at every queue depth.

## P7. Physical verification status (READ BEFORE CLAIMING ANYTHING)

| Scope | Status |
|---|---|
| Stage-A production correctness (pre-mega-batch) | PHYSICALLY VERIFIED before the mega-batch (Samsung Galaxy S24 / SM-S921N, OK 3 tests / 170.554 s) |
| Mega-batch Stage-A regression | PENDING - rerun command 1 below |
| Stage-B rapid sequential overlap | CODE VERIFIED ONLY - PENDING physical (command 2) |
| Packed-YUV physical A/B | CODE VERIFIED ONLY - PENDING physical (command 3) |
| RAW bulk optimization activation | NOT physically verified. Do NOT claim it until the Samsung Galaxy S24 / SM-S921N report shows finalJob.rawMetadata.rawPersistenceWriteStrategy == SEQUENTIAL_BULK or PADDED_ROW_PACK (the Stage-A RAW test now fails closed on SCALAR_FALLBACK). |

## P8. EXACT PHYSICAL COMMANDS (PowerShell)

.\gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain --no-daemon

# 1) Strict Stage-A regression (must stay green)
adb shell am instrument -w -r `
  -e timeout_msec 300000 `
  -e kepler.hardwareE2E true `
  com.projectnuke.keplernightlab.test/androidx.test.runner.AndroidJUnitRunner

# 2) Stage-B rapid sequential suite (pinned identities + overlap evidence)
adb shell am instrument -w -r `
  -e timeout_msec 600000 `
  -e kepler.hardwareE2E.stageB true `
  com.projectnuke.keplernightlab.test/androidx.test.runner.AndroidJUnitRunner

# 3) Packed-YUV physical A/B (packed capture + paired PNG reference)
adb shell am instrument -w -r `
  -e timeout_msec 300000 `
  -e kepler.hardwareE2E.packedYuv true `
  com.projectnuke.keplernightlab.test/androidx.test.runner.AndroidJUnitRunner

Decision gates AFTER those results: (a) whether RAW postAcquisitionToShutterMs
improved and whether remaining delay is HAL acquisition (compare
cameraAcquisitionMs vs requestedFrames * rawMinFrameDurationNs) or software
persistence; (b) whether PACKED_YUV_V1 should be promoted (compare its report
against the paired PNG reference on postAcquisitionToShutterMs /
persistenceDrainMs with zero correctness regressions).
