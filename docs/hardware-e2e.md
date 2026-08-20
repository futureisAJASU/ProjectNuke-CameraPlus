# KeplerNightLab real-device E2E baseline

This harness validates the production `MainCameraScreen` route only:

`MainCameraScreen` -> `CameraPipelineUiOrchestrator` -> Camera2 capture -> current RAW/YUV processing and export.

`LegacyCaptureTools` and `DebugScreen` captures remain capability diagnostics. They are not production-path E2E evidence.

## Harness and report

Hardware instrumentation is opt-in. Without `kepler.hardwareE2E=true`, the capture tests skip cleanly. The recorder writes compact JSON reports to the debug app-private directory:

`files/hardware-e2e/<runId>.json`

and publishes the newest report as `files/hardware-e2e/latest.json`. Reports retain at most 12 completed run files. Reports contain the runtime session, device/build identity, scenario, ordered event/checkpoint history, counts, terminal flags, diagnostic job-correlation result, high-level `job.json` state, file names, timing/memory fields, and final classification/reason. Image/RAW/PNG payloads are never copied into the report. Instrumentation prints `HARDWARE_E2E_RUN_ID=<id>`; that exact report file is the only report the host collector retrieves.

Run checkpoints are truthful diagnostic markers: `RUN_STARTED`, `PIPELINE_REQUEST_ACCEPTED`, `CAPTURE_STARTED`, `CAPTURE_PROGRESS`, `CAPTURE_STAGE_COMPLETE`, `PROCESSING_STARTED`, `EXPORT_STARTED`, `PUBLIC_OUTPUT_COMMITTED`, `TERMINAL_COMPLETE`, `TERMINAL_FAILED`, `TERMINAL_CANCELLED`, and `OWNER_SETTLED`. `RUN_STARTED` means a recorder run was created; `PIPELINE_REQUEST_ACCEPTED` means the production start request returned accepted. They are not authority and do not claim app-start or preview readiness. The existing production event stream remains authoritative.

## Windows collection

From the repository root in PowerShell:

```powershell
.\tools\run-kepler-hardware-e2e.ps1 -Install -CleanLogcat
```

`-Install` expects one unambiguous `app-debug.apk` and one debug `androidTest` APK under `app/build/outputs/apk`. Build them first with `assembleDebug` and `assembleDebugAndroidTest`, or omit `-Install` only when both packages are already installed. The script preflights `pm list instrumentation`, grants CAMERA as a required command, checks native command exit codes, and returns `PASS`, `FAIL`, or `HARNESS_ERROR` with a non-zero process code for required failures. It retrieves `files/hardware-e2e/<exact-run-id>.json` through `run-as`; it never falls back to `latest.json`.

When more than one device is connected, pass the intended serial explicitly:

```powershell
.\tools\run-kepler-hardware-e2e.ps1 -Serial R5CT... -CleanLogcat
```

The script checks `adb`, requires exactly one authorized device unless `-Serial` is supplied, records device identity, runs the opt-in instrumentation class, collects filtered logcat and basic dumpsys output, and retrieves the exact report with `run-as`. It does not clear the app cache, delete photos, or require root. Artifacts are placed under `artifacts/hardware-e2e/<timestamp>/`.

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
