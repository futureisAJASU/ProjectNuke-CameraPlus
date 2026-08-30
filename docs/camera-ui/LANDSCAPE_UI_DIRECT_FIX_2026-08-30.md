# Kepler Night Lab — direct landscape UI correction (2026-08-30)

Baseline reviewed: `9bc38f5` (`fix(camera): anchor focus marker to display point`).

## What the latest baseline already fixed

Static review confirms the previous corrective is present:

- authoritative display-rotation state via `DisplayManager.DisplayListener`
- preview geometry models TextureView implicit scaling and uses a uniform effective crop
- AF/AE uses the canonical preview inverse mapping instead of a rotation-only approximation
- focus marker is kept in display space
- level vector convention is physical-down end-to-end with valid rotation-matrix tests

The supplied physical screenshot also shows the old obvious landscape preview stretching is gone.

## Why UI remained reopened

The supplied Kepler screenshot still had structural landscape-layout problems rather than mere taste differences:

- mode labels (`인물 사진 / 야간 / 사진 / 동영상 / 더보기`) occupied preview space
- portrait-style top status/level chrome remained over the image
- zoom/action/mode groups did not follow a coherent camera-control gutter
- the reprocess/refresh icon was visually malformed

## Direct correction

Landscape now uses a Samsung-inspired layout grammar without Samsung assets:

- left black utility rail: settings / metering / resolution
- bounded central preview: no camera chrome is laid out inside this rectangle
- right black control rail:
  - vertical zoom selector to the left
  - refresh/reprocess button above the shutter
  - shutter centered
  - result thumbnail below
  - mode labels in a dedicated outer vertical strip
- only mode label content rotates ±90°; containers and circular controls stay screen-stable
- portrait UI remains on the existing bottom-panel path
- floating-shutter dock geometry continues to use the measured root-local shutter center

## Debug signing identity

`app/kepler-debug.jks` is intentionally versioned and used only by the `debug` build type.
This keeps debug APK certificate identity stable across Android Studio / agent / CI builds and avoids reinstall failures caused solely by different per-machine `$HOME/.android/debug.keystore` files.

SHA-256 of the currently selected local pin and checked-in fallback:
`AC:62:52:51:38:84:1D:73:88:23:7D:19:0B:37:D9:E1:19:D8:54:D9:70:53:65:5D:06:3D:28:F9:BA:55:2C:8F`

Release signing is intentionally untouched.

For a phone that already has a build signed by the current Windows user's default Android debug key, run `tools/pin_debug_keystore.ps1` before the first build to copy that exact existing `androiddebugkey` into the pinned project location. That avoids even the one-time migration uninstall. If the generated key is used instead, a currently installed build with a different old certificate can require one final uninstall.

## Validation status in this environment

- `git diff --check`: PASS
- keystore readable / alias present / fingerprint recorded: PASS
- Host Gradle compile, focused tests, signing guard, lint, and assemble are run from the current workspace; no physical-device validation is implied by those gates.
- hardware validation: pending on the user's SM-S921N

Required next physical check:

1. portrait -> landscape-left -> landscape-right -> portrait
2. verify preview remains undistorted
3. verify modes stay entirely in the right black rail
4. verify zoom/shutter/result/refresh positions match the intended stock-camera grammar
5. verify focus marker/AF alignment after the new bounded preview size
6. verify floating shutter docks to the moved landscape shutter center
