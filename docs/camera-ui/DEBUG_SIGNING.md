# Kepler Night Lab debug signing identity

Debug APKs use a stable debug certificate so `adb install -r` does not fail just because Android Studio, an agent, or CI built the APK on a different machine.

The debug signing order is:

1. `app/local-debug.jks` if it exists. This file is ignored by git and is intended for the user's current development PC certificate.
2. `app/kepler-debug.jks` as the checked-in fallback debug identity.

Both are **debug-only, non-secret** identities. Release builds do not use them, and any future production/release signing key must be managed separately.

## Preserve the certificate of an already-installed debug build

If the phone currently has a build signed by this development machine's normal `$HOME/.android/debug.keystore`, run `tools/pin_debug_keystore.ps1` on Windows, or `tools/pin_debug_keystore.sh` on Linux/macOS, before the first build with this patch. The pin command is idempotent: once `app/local-debug.jks` exists, it validates and preserves that identity instead of copying a newer machine-default key over it.

On first use, the script copies the current `androiddebugkey` into `app/local-debug.jks`, leaving the checked-in fallback keystore untouched. That keeps the existing app update-compatible while avoiding accidental commits of the developer-machine key. It prints only the selected source category and certificate SHA-256.

If neither `app/local-debug.jks` nor the old certificate matches the already-installed APK, one final uninstall may still be required. After the first matching install, subsequent debug builds remain stable.
