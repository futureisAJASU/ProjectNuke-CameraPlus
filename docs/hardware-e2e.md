# KeplerNightLab real-device E2E baseline

This harness validates the production `MainCameraScreen` route only:

`MainCameraScreen` -> `CameraPipelineUiOrchestrator` -> Camera2 capture -> current RAW/YUV processing and export.

`LegacyCaptureTools` and `DebugScreen` captures remain capability diagnostics. They are not production-path E2E evidence.

## Harness and report

Hardware instrumentation is opt-in. Without `kepler.hardwareE2E=true`, the capture tests skip cleanly. The recorder writes compact JSON reports to the debug app-private directory:

`files/hardware-e2e/<runId>.json`

and publishes the newest report as `files/hardware-e2e/latest.json`. Reports retain at most 12 completed run files. Reports contain the runtime session, device/build identity, scenario, ordered event/checkpoint history, counts, terminal flags, newest correlated job directory, high-level `job.json` state, file names, timing/memory fields, and final classification. Image/RAW/PNG payloads are never copied into the report.

Stable checkpoints include `APP_STARTED`, `PREVIEW_READY`, `PIPELINE_ACCEPTED`, `CAPTURE_STARTED`, `CAPTURE_STAGE_COMPLETE`, `PROCESSING_STARTED`, `EXPORT_STARTED`, `PUBLIC_OUTPUT_COMMITTED`, `TERMINAL_COMPLETE`, `TERMINAL_FAILED`, `TERMINAL_CANCELLED`, and `OWNER_SETTLED`; the existing production event stream remains authoritative.

## Windows collection

From the repository root in PowerShell:

```powershell
.\tools\run-kepler-hardware-e2e.ps1 -CleanLogcat
```

When more than one device is connected, pass the intended serial explicitly:

```powershell
.\tools\run-kepler-hardware-e2e.ps1 -Serial R5CT... -CleanLogcat
```

The script checks `adb`, records device identity, grants CAMERA where possible, runs the opt-in instrumentation class, collects filtered logcat and basic dumpsys output, and retrieves the report with `run-as`. It does not clear the app cache, delete photos, or require root. Artifacts are placed under `artifacts/hardware-e2e/<timestamp>/`.

## Phase A - first qualification

1. Install the latest debug APK on the physical device.
2. Grant camera permission and launch the app.
3. Verify the production preview starts.
4. Record the camera capability report.
5. Select main 1x and 12M.
6. Run the fixed four-frame YUV Night Fusion burst.
7. Inspect the terminal state, Cache / Jobs, MediaStore/gallery output, and `hardware-e2e-report.json`.
8. If the capability report advertises the route, run the four-frame 12M RAW burst.
9. Inspect RAW frame accounting, per-frame files/DNG sidecars, final export state, and the report bundle.

## Phase B - repeated baseline

Run several successive captures and verify that the preview returns, busy state clears, a subsequent shutter press is accepted, no stale active operation remains, no job is permanently stuck, export remains internally consistent, and Gallery/Cache remains usable.

The following are deliberately the next hardware qualification batch, not implemented lifecycle automation in this batch:

- cancellation during capture;
- cancellation after capture/during processing;
- app background/foreground and activity recreation;
- process kill after capture and during processing;
- restart recovery;
- reproducible MediaStore/provider interruption;
- gallery reprocess and repeated reprocess;
- Super Resolution;
- repeated long-run/thermal sequence.

The recorder/checkpoints are intended to make those later external ADB scenarios diagnosable, for example waiting for `CAPTURE_STAGE_COMPLETE`, force-stopping, relaunching, and inspecting the same report/job evidence.

No storage, lease, recovery, Camera2 ownership, or reconciliation authority semantics are changed by this foundation.
