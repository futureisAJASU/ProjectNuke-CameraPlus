# ProjectNuke-CameraPlus

## KeplerNightLab Debug Notes

- Preview rotation is controlled in `CameraPreview.kt` by `PREVIEW_ROTATION_FIX_DEGREES`. For Galaxy S24 testing, try only `0f`, `90f`, `180f`, or `270f` while the sensor/display transform is being verified.
- If the shutter still appears as an old pill after this code is installed, suspect stale build/install state. Run `./gradlew clean`, uninstall the app from the device, then reinstall the debug APK.
