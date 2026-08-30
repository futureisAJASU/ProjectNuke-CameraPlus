#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
source_keystore="${HOME}/.android/debug.keystore"
destination="${repo_root}/app/local-debug.jks"
if command -v keytool >/dev/null 2>&1; then
  keytool_bin="$(command -v keytool)"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/keytool" ]]; then
  keytool_bin="${JAVA_HOME}/bin/keytool"
else
  echo "keytool is required to inspect the debug certificate." >&2
  exit 1
fi

certificate_fingerprint() {
  local output
  if ! output="$(${keytool_bin} -list -v -keystore "$1" -storepass android -alias androiddebugkey 2>/dev/null)"; then
    echo "Could not inspect the Android debug certificate." >&2
    exit 1
  fi
  printf '%s\n' "$output" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1
}

if [[ -f "$destination" ]]; then
  fingerprint="$(certificate_fingerprint "$destination")"
  echo "Local debug pin already exists; preserving its identity."
  echo "Source category: developer-local pinned override"
  echo "Certificate SHA-256: ${fingerprint}"
  exit 0
fi

if [[ ! -f "$source_keystore" ]]; then
  echo "Default Android debug keystore not found: $source_keystore" >&2
  exit 1
fi

cp "$source_keystore" "$destination"
fingerprint="$(certificate_fingerprint "$destination")"
echo "Created the developer-local debug pin; future runs preserve it."
echo "Source category: developer-local pinned override"
echo "Certificate SHA-256: ${fingerprint}"
