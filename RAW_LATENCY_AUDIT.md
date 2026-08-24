# RAW Foreground Latency Root-Cause Audit (Stage-B Mega-Batch, Phase 2)

Scope: the interval between RAW camera acquisition reaching 100%
(`cameraAcquisitionCompleteAt`) and shutter admission
(`captureStageCompleteAt`), for a 4-frame 4080x3060 RAW16 burst
(~25 MB/frame, ~100 MB total source payload) on SM-S921N / Android 16.

All timings referenced below are produced by the Phase-1 timing ledger
wiring (`CaptureTimingLedger`, persisted as `capture_timing.json` and the
`captureTiming` key of `job.json`). No timing is derived from UI text.

## Code path (Camera2 callback -> shutter admission)

| # | Step | Site |
|---|------|------|
| 1 | `onImageAvailable` fires on `KeplerRawFusionCaptureThread` | RawFusionCapture.kt (`setOnImageAvailableListener`, handler-bound) |
| 2 | Image adopted by serialized owner event; arrival instant recorded | `postReceiveImage` -> `ledger.recordImage` + `captureTimingLedger.recordImageReceived(ordinal)` |
| 3 | Result adopted likewise | `postResultReceived` -> `recordResultReceived(ordinal)` |
| 4 | Pairing + save dispatch (owner thread) | `dispatchReadyFrames` -> `takeNextReadyFrame` -> `submitSaveRequest` (`persistenceSubmittedAt`) |
| 5 | Save worker executes frame persistence | `saveRawFrameToCompletion` on `BoundedCaptureWorker("KeplerRawFusionSave", capacity=2)` (`workerStartedAt`) |
| 6 | raw16 extraction+write (per-pixel scalar copy today) | `writeCompactRaw16`/`writeRaw16Rows` (`fsyncFinishedAt`, `Raw16WriteStats.writeDurationMs`) |
| 7 | fsync of temp payload | `rawOutput.fd.sync()` inside `Kepler_RAW_Sync` trace section (`syncDurationMs`) |
| 8 | Temp digest verification (2 full reads: stream + identity fence) | `verifyRaw16Payload(temp)` -> `NoFollowFileSystem.digestVerified` |
| 9 | Atomic publish | `KeplerJobMetadata.atomicReplace(temp, final)` (`writeFinishedAt`) |
| 10 | Final digest verification AGAIN (2 full reads) | `verifyRaw16Payload(finalFile, expected)` (`verifiedAt`) |
| 11 | DNG sidecar (only when requested; NOT_REQUESTED skips fully) | `DngCreator...writeImage` block guarded by `shouldSaveDngSidecars` |
| 12 | Frame adoption = durable commit boundary | owner event `adoptSuccess` -> `recordCommitted(frameIndex)` |
| 13 | Last frame triggers drain-complete | `finishSuccess` -> `recordPersistenceDrainComplete()` + ledger persist |
| 14 | Terminal metadata write (full manifest job.json) | `submitTerminal` -> `KeplerJobMetadata.write(jobDir, terminalJob)` |
| 15 | Processing handoff publish | `publishProcessingHandoff` -> `recordProcessingHandoffPublished()` |
| 16 | Owner settle + stage complete | `clearActiveOperation` -> `recordCaptureResourcesSettled()` -> `recordCaptureStageComplete()` |

Shutter admission itself is released by `CameraPipelineEvent.CaptureStageComplete`
with full handoff evidence (`RawFusionExport.kt`: handoff made durable, then
event published, then background enqueue). It does NOT wait for any
background processing.

## Static audit table

Bytes assume 4080x3060 RAW16 (~25 MB/frame; x4 frames ~100 MB).

| Operation | Thread | Bytes touched | Large allocation | Durability requirement | Can move after handoff? | Can optimize safely? |
|---|---|---|---|---|---|---|
| Image adoption/pairing | KeplerRawFusionCaptureThread | 0 (metadata only) | none | none | n/a | already minimal |
| raw16 row extraction + write | KeplerRawFusionSave worker | read 25 MB + write 25 MB | one `ByteArray(width*2)` row buffer (reused) | yes - payload before fsync | no (source Image lifetime bounded) | YES: scalar `buffer.get(index)` per byte; compact-stride fast path can bulk-copy |
| fd.sync() of raw16 payload | KeplerRawFusionSave | flush 25 MB | none | REQUIRED fsync | no | keep (single sync per frame is already minimal) |
| Temp digest verify | KeplerRawFusionSave | READ 25 MB x2 (stream + identity fence) | 8 KB read buffers | redundant pre-rename check | n/a | YES: consolidate into single post-publish verification |
| Atomic rename | KeplerRawFusionSave | metadata only | none | REQUIRED atomic publish | no | keep |
| Final digest verify | KeplerRawFusionSave | READ 25 MB x2 (stream + identity fence) | 8 KB read buffers | REQUIRED content truth for manifest commit | no | PARTIAL: fence re-read can be merged with streamed digest pass |
| DNG sidecar | KeplerRawFusionSave | 0 when NOT_REQUESTED (SM-S921N production default) | none | skip is correct | n/a | already skipped |
| Terminal manifest write | owner thread | ~100 KB JSON | none | REQUIRED before handoff | no | already single consolidated write |
| Handoff publish + owner clear | owner thread | small JSON | none | REQUIRED | no | already consolidated |
| Preview/debug/fusion before handoff | - | NONE (BALANCED skips debug PNGs) | none | n/a | n/a | nothing to remove |
| Acquisition blocked by persistence? | - | NO - capture continues while saves run; backpressure via bounded queue(capacity=2)+emergency eviction | | | | |

## Findings

1. **Acquisition truth vs persistence are already decoupled**: Camera2 keeps
   delivering frames while the save worker persists; the UI percentage is fed
   only by image/result pair evidence.
2. **The dominant software cost after 100% is read-back verification**:
   each frame is fully read back FOUR times today (temp stream digest +
   temp identity fence + final stream digest + final identity fence)
   ~= 100 MB of reads per 25 MB frame, all BEFORE the last commit that
   gates handoff. This is the primary Phase-3 target.
3. **Secondary target**: the extraction loop uses scalar byte reads from a
   direct ByteBuffer (`buffer.get(index)` per byte, 50M calls/frame). A
   compact-stride bulk path (rowStride == width*2, pixelStride == 1) can
   eliminate the per-pixel loop entirely.
4. **HAL pacing evidence** is now recorded per capture
   (`rawStreamTiming` / `rawMinFrameDurationNs` / `rawStallDurationNs`
   in job.json, exposed via HardwareE2E `rawMetadata`). Compare observed
   `cameraAcquisitionMs` against `requestedFrames * minFrameDurationNs`
   to answer "is the HAL slow, or is our software slow?"
5. **No unnecessary foreground work found outside persistence**: DNG work is
   fully skipped when NOT_REQUESTED; no preview/render/debug generation
   happens before CaptureStageComplete; metadata writes are consolidated
   at first-frame/last-frame/terminal boundaries only.

## Phase-3 optimization contract (derived from this audit)

Preserve: 4/4 durable source frames, strict manifest truth, process-death
recoverability, bounded memory, no live Image retention, crash-safe atomic
publication (temp write -> payload fsync -> atomic rename -> verified final
-> manifest commit -> handoff).

Targets (in priority order):
- A. Single post-publish verification of the FINAL file (size + streaming
  SHA-256) replacing the four-pass pattern; the manifest still commits only
  verified bytes.
- B. Compact-stride fast path writing a direct duplicate of the plane buffer
  sequentially (no intermediate arrays).
- C. Padded-stride row packing through ONE reusable bounded direct buffer
  (no per-row/per-frame allocations), preserving exact compact payload.

## Phase-3 result (implemented)

1. **Verification consolidation (target A)**: the pre-rename temp read-back
   (2 full payload reads) is REMOVED. The single remaining post-publish
   `verifyRaw16Payload(final, size, sha256)` now additionally enforces
   SHA-256 equality against the digest streamed at write time — previously
   only SIZE was asserted. Net: 4 full reads/frame -> 2 (the strict
   `NoFollowFileSystem.digestVerified` stream+fence is retained for the
   final artifact), while content truth is STRICTLY STRONGER than before.
2. **Bulk extraction (targets B/C)**: `writeRaw16Rows` now transfers one
   complete row per JNI crossing via `ByteBuffer.duplicate()` bulk gets:
   - `SEQUENTIAL_BULK` when rowStride == width*2 (contiguous plane);
   - `PADDED_ROW_PACK` when pixelStride==1 with padded stride (one reusable
     `ByteArray(width*2)` row buffer; padding stripped; zero-fill fallback
     preserved for short planes);
   - `SCALAR_FALLBACK` only for exotic pixelStride != 1 layouts.
   The scalar per-byte loop (~50M JNI crossings per 25 MB frame) is gone
   from all production layouts. No full-frame allocation exists anywhere on
   the path.
3. **Digest-at-write**: every payload byte flows through a
   `DigestingOutputStream` (SHA-256) placed between row emission and the
   durable sink, so the write-time digest covers EXACTLY the bytes handed to
   the kernel, independent of extraction strategy.
4. **Durability invariants unchanged**: temp write -> fd.sync() ->
   atomicReplace -> final verify (fail-closed) -> owner adoption
   (`recordCommitted`) -> drain -> terminal manifest -> handoff publish ->
   resources settled -> CaptureStageComplete. fsync count is unchanged
   (exactly one payload sync per frame). Failure/cancellation still fails
   the frame closed (never adopted) and closes the Image exactly once.
5. **Removed foreground work summary** (answers "what consumed the time"):
   - 50 MB/frame of redundant read-back I/O (two full reads);
   - ~50M scalar JNI byte reads/frame;
   - nothing else was removed: DNG stays fully skipped when NOT_REQUESTED,
     no preview/debug/fusion work existed before handoff.

Expected physical effect on SM-S921N: `postAcquisitionToShutterMs`
(= captureStageCompleteAt - cameraAcquisitionCompleteAt) should drop by
roughly the eliminated read-back span (~100 MB reads/burst) plus the
extraction CPU win, bounded below by the mandatory 2 final-read passes
(~50 MB) + 4 fsyncs + metadata writes.
