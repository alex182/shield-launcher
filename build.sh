#!/usr/bin/env bash
# Build the launcher in the dev container and optionally install it. Unit
# tests run first; a failing test stops the build.
#   ./build.sh            build only
#   ./build.sh install    build, install on every SHIELD, return each to HOME
# DEVICES (space-separated) or DEVICE overrides the targets.
set -euo pipefail
cd "$(dirname "$0")"
DEVICES="${DEVICES:-${DEVICE:-192.168.1.209:5555 192.168.1.197:5555}}"
IMAGE=shield-launcher-dev:latest
APK=app/build/outputs/apk/release/app-release.apk

docker image inspect "$IMAGE" >/dev/null 2>&1 || \
  docker build --build-arg UID="$(id -u)" --build-arg GID="$(id -g)" -t "$IMAGE" .devcontainer

docker run --rm \
  -v "$PWD":/workspaces/shield-launcher \
  -v shield-launcher-gradle:/home/dev/.gradle \
  -w /workspaces/shield-launcher \
  "$IMAGE" gradle :app:testReleaseUnitTest :app:assembleRelease --no-daemon --console=plain

echo "APK: $APK ($(stat -c%s "$APK") bytes)"

if [ "${1:-}" = "install" ]; then
    failed=0
    for d in $DEVICES; do
        echo "== $d"
        adb connect "$d" >/dev/null 2>&1 || true
        if adb -s "$d" install -r "$APK" </dev/null; then
            # Quick actions service: append to, never replace, the enabled
            # list, or Button Mapper's own service is switched off.
            svc=dev.alex.minlauncher/dev.alex.minlauncher.QuickMenuService
            cur=$(adb -s "$d" shell settings get secure enabled_accessibility_services </dev/null | tr -d '\r')
            case "$cur" in
                *QuickMenuService*) ;;
                null|"") adb -s "$d" shell settings put secure enabled_accessibility_services "$svc" </dev/null ;;
                *) adb -s "$d" shell settings put secure enabled_accessibility_services "$cur:$svc" </dev/null ;;
            esac
            adb -s "$d" shell settings put secure accessibility_enabled 1 </dev/null
            # Doze otherwise firewalls the config page while the SHIELD sleeps.
            adb -s "$d" shell dumpsys deviceidle whitelist +dev.alex.minlauncher </dev/null >/dev/null
            adb -s "$d" shell input keyevent KEYCODE_HOME </dev/null
        else
            failed=1
        fi
    done
    [ "$failed" = 0 ] && echo "installed on all devices and returned to HOME" || { echo "install failed on at least one device" >&2; exit 1; }
fi
