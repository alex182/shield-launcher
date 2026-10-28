# SHIELD Minimal Launcher

A minimal dark home screen for NVIDIA SHIELD Android TV (`mdarcy`, Android 11 /
API 30). Clock, pinned apps as banners, then everything else alphabetically. No
recommendation rows, no sponsored content, no network access.

## Build

Everything builds inside a container; the host needs only Docker and `adb`.

```bash
./build.sh            # build the APK
./build.sh install    # build, install on every SHIELD, return each to HOME
```

`install` targets both SHIELDs (Office `192.168.1.209`, Livingroom
`192.168.1.197`). Override with `DEVICES="192.168.1.x:5555 ..."` or
`DEVICE=192.168.1.x:5555`. Gradle and the JDK only ever run inside the
container; the host never compiles anything.

In VS Code, "Reopen in Container" uses the same image via `.devcontainer/`.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/dev/alex/minlauncher/` | `MainActivity` (home), `QuickMenuService` (overlay + web server host), `WebConfigServer`, `Store`, `Apps`, `HomeAssistant`, `Icons` |
| `app/src/main/assets/` | `config.html` (web page), `mdi/` (icon font) |
| `app/src/main/res/` | theme, colours, focus selector, layout |
| `tools/update-mdi.sh` | refresh the bundled icon font |
| `.devcontainer/` | JDK 17 + Android SDK 34 + Gradle 8.5 image |
| `keystore/debug.keystore` | pinned signing key (see below) |

## Pinned and hidden apps

Edited on the TV: long-press an app (hold the select button) for Pin, Unpin,
Move left/right, or Hide. Hidden apps are listed under "Hidden apps" above
Settings; picking one shows it again. Both lists live in the launcher's
SharedPreferences, so each SHIELD keeps its own.

`DEFAULT_PINNED` and `DEFAULT_HIDDEN` in `Store.java` only seed a fresh
install (or one whose data was cleared).

## Netflix button

The launcher owns the remote's Netflix button (`KEYCODE_BUTTON_12`, scancode
393) over any app:

| Gesture | Does |
| --- | --- |
| Press | opens the app chosen on the config page (default Jellyfin) |
| Double-press | Home Assistant camera grid (`CameraOverlay`) |
| Hold (3 s default) | quick actions panel |

Any press while an overlay is up closes it; so does Back. Every key-filtering
accessibility service sees every key, so **Button Mapper / Buttons remapper
must not map the Netflix button**, or a double press also fires theirs.
Keys injected with `adb shell input` do not reach accessibility services, so
the gestures can only be tested with a real remote.

### Quick actions

Each action calls a Home Assistant service; a blank service is a placeholder.

### Cameras

A grid of camera snapshots from `/api/camera_proxy`, refreshed every 2 s (1 s
for a single camera, chosen with select). The config page picks which cameras
and their order; none picked means all. The overlay closes when the screen
turns off. The page itself never shows camera images, since it is open to the
LAN.

Actions can carry Home Assistant icons (`mdi:lightbulb`). They are drawn from
a bundled Material Design Icons font in `app/src/main/assets/mdi/` (Apache
2.0, see its `LICENSE`), so nothing is fetched at runtime;
`tools/update-mdi.sh [version]` refreshes the font and its name table.

## Web config page

`http://<shield-ip>:8080` (Office `192.168.1.209`, Livingroom `192.168.1.197`)
edits pins, hidden apps, quick actions, hold time and the Home Assistant URL
and token, live, per device. "From Home Assistant…" on an action lists your entities
(filterable: automations, scenes, scripts, lights, …), then the services
their domain offers (e.g. automation trigger / turn on / turn off / toggle),
and fills in service, entity, label and icon. The SHIELD proxies
`/api/states` and `/api/services` for it, so the browser never talks to Home
Assistant directly and the token stays on the device. It is open to anyone on the LAN by choice; the
token alone is write-only and never sent back. The server runs inside the
quick actions service (`WebConfigServer`), so that service must be enabled.

`./build.sh install` does the device setup: it appends the service to
`enabled_accessibility_services` (replacing the list would switch Button
Mapper off) and adds the app to the doze allowlist.

## Backgrounds

The Flint Hills background images are generated locally with ComfyUI. See
[scripts/backgrounds/README.md](scripts/backgrounds/README.md).

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
- **Doze firewalls the config page.** While a SHIELD sleeps, only
  allowlisted apps accept connections; `dumpsys deviceidle whitelist
  +dev.alex.minlauncher` (done by `build.sh install`) fixes it.
- **Rethink (Office SHIELD, currently off) swallows inbound connections.**
  Its VPN routes every app's replies into `tun1`, so the page times out from
  the LAN. If it is turned back on, exclude the launcher ("Minimal") from the
  VPN in Rethink.
- **Focus must be requested after layout** (`rows.post(...)`); calling
  `requestFocus()` during view construction silently fails and the framework
  drops focus on the last row instead.
