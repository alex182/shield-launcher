# SHIELD Minimal Launcher

A minimal dark home screen for NVIDIA SHIELD Android TV (`mdarcy`, Android 11 /
API 30). Clock, pinned apps, then everything else alphabetically. No
recommendation rows, no sponsored content, no network access.

## Build

Everything builds inside a container; the host needs only Docker and `adb`.

```bash
./build.sh            # build the APK
./build.sh install    # build, install over adb, return the device to HOME
```

Override the target with `DEVICE=192.168.1.x:5555 ./build.sh install`.

In VS Code, "Reopen in Container" uses the same image via `.devcontainer/`.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/dev/alex/minlauncher/MainActivity.java` | the whole launcher |
| `app/src/main/res/` | theme, colours, focus selector, layout |
| `.devcontainer/` | JDK 17 + Android SDK 34 + Gradle 8.5 image |
| `keystore/debug.keystore` | pinned signing key (see below) |

## Pinned apps

Edit `FAVORITES` in `MainActivity.java`; anything not listed falls into the
alphabetical section below the divider.

## Things that will bite you

- **The signing key is pinned** to `keystore/debug.keystore` rather than the
  container's generated one. Rebuilding the image regenerates that key, which
  breaks `adb install -r` with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and forces
  an uninstall. Keep this file.
- **`QUERY_ALL_PACKAGES`** is required on API 30+. Without it
  `queryIntentActivities()` returns almost nothing and the app list is empty.
- **Both `LEANBACK_LAUNCHER` and `LAUNCHER` are queried.** Sideloaded phone
  apps (Button Mapper) only declare the latter and would otherwise be invisible.
- **The HOME intent-filter must stand alone**, without `LAUNCHER` categories
  mixed in, and `android:priority` is ignored for non-system apps.
- **The stock launcher must be disabled** to win HOME resolution; its filter has
  `priority=2` against a third-party app's `0`:
  ```bash
  adb shell pm disable-user --user 0 com.google.android.tvlauncher   # take over
  adb shell pm enable com.google.android.tvlauncher                  # hand back
  ```
  `com.android.tv.settings.system.FallbackHome` sits at `priority=-1000` as a
  safety net, so the device always has *a* home screen.
- **Focus must be requested after layout** (`rows.post(...)`); calling
  `requestFocus()` during view construction silently fails and the framework
  drops focus on the last row instead.
