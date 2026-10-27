#!/usr/bin/env bash
# Build the launcher in the dev container and optionally install it.
#   ./build.sh            build only
#   ./build.sh install    build, install, and return the SHIELD to HOME
set -euo pipefail
cd "$(dirname "$0")"
DEVICE="${DEVICE:-192.168.1.209:5555}"
IMAGE=shield-launcher-dev:latest
APK=app/build/outputs/apk/release/app-release.apk

docker image inspect "$IMAGE" >/dev/null 2>&1 || \
  docker build --build-arg UID="$(id -u)" --build-arg GID="$(id -g)" -t "$IMAGE" .devcontainer

docker run --rm \
  -v "$PWD":/workspaces/shield-launcher \
  -v shield-launcher-gradle:/home/dev/.gradle \
  -w /workspaces/shield-launcher \
  "$IMAGE" gradle :app:assembleRelease --no-daemon --console=plain

echo "APK: $APK ($(stat -c%s "$APK") bytes)"

if [ "${1:-}" = "install" ]; then
    adb connect "$DEVICE" >/dev/null 2>&1 || true
    adb -s "$DEVICE" install -r "$APK" </dev/null
    adb -s "$DEVICE" shell input keyevent KEYCODE_HOME </dev/null
    echo "installed and returned to HOME"
fi
