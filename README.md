# SHIELD Minimal Launcher

A minimal dark home screen for NVIDIA SHIELD Android TV (`mdarcy`, Android 11 /
API 30). Clock, pinned apps as banners, then everything else alphabetically,
over a weather/season/holiday background from Home Assistant. No
recommendation rows, no sponsored content.

## Build

Everything builds inside a container; the host needs only Docker and `adb`.

```bash
./build.sh            # run the unit tests, build the APK
./build.sh install    # same, then install on every SHIELD, return each to HOME
```

`install` targets both SHIELDs (Office `192.168.1.209`, Livingroom
`192.168.1.197`). Override with `DEVICES="192.168.1.x:5555 ..."` or
`DEVICE=192.168.1.x:5555`. Gradle and the JDK only ever run inside the
container; the host never compiles anything.

In VS Code, "Reopen in Container" uses the same image via `.devcontainer/`.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/dev/alex/minlauncher/` | `MainActivity` (home), `QuickMenuService` (overlay + web server host), `LightPanel` + `Lights` (long-press light controls), `WebConfigServer`, `Store`, `Apps`, `HomeAssistant`, `Icons`, `CameraOverlay`, `RadarOverlay`; backgrounds: `Backgrounds` (pure picking logic), `BackgroundController`, `Scrim`; `TileFlow` (wrapping banner row) |
| `app/src/test/` | JVM unit tests (`Backgrounds`, `TileFlow` wrapping); JUnit is test-only |
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

Any press while an overlay is up closes it; so does Back. The exception is
the corner rain radar, which a press makes full screen (see below). Every
key-filtering accessibility service sees every key, so **Button Mapper /
Buttons remapper must not map the Netflix button**, or a double press also
fires theirs, and its own mapping relaunching an app stops playback. Neither
SHIELD runs Button Mapper now (the Office one's was removed on 2026-10-28);
this matters if it is ever installed again.
Keys injected with `adb shell input` do not reach accessibility services, so
the gestures can only be tested with a real remote.

### Quick actions

Each action calls a Home Assistant service; a blank service is a placeholder.
The panel stays open after an action runs, so several can be run in a row;
Back or the Netflix button closes it.

The last row is **Sleep** (shown by default; the config page can hide it). It
starts a timer, 90 minutes unless changed on the config page; press it again
to cancel. When it runs out the launcher sends media pause and stop, goes
HOME, then sleeps the SHIELD with the accessibility lock-screen action, which
puts the device to sleep on Android 9+. The TV turns off with it over HDMI-CEC
(`hdmi_control_auto_device_off_enabled`, on on both SHIELDs). A toast warns a
minute before; the timer is dropped if the screen turns off some other way.

Long-press select on an action whose entities are all lights (light groups
are lights too) for its light panel (`LightPanel`): a brightness bar (left /
right in 5% steps, 0 turns them off) and white and colour swatches, offered
as the lights' `supported_color_modes` allow. Every change is a
`light.turn_on` on all of the action's lights; Back returns to quick actions.

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

### Rain radar

While the Background's weather entity (`weather.lake_of_the_forest`) is
rainy, pouring, hail or lightning, or the Tempest has logged a strike within
15 miles in the last 30 minutes (`sensor.lake_of_the_forest_lightning_last_distance`
and `_last_strike`; both the entities and the radius are settings),
`RadarOverlay` puts a small radar loop in the bottom-left corner over any app,
or bottom-right over the home screen, clear of the banners: the NWS Kansas City radar
(`KEAX_loop.gif`) unless the config page names another image URL or a
`camera.` entity. The service checks every 2 minutes while the screen is on
and reloads the image every 5. The small window takes no focus, so the app keeps
the remote. The Netflix button makes it full screen, still without focus
so playback carries on underneath; the launcher holds back the arrows and
select from the app meanwhile (volume and media keys still go through), and
Back shrinks it again and Netflix closes it. In the corner,
Back closes it (when no other overlay of ours is up). Once closed it stays
closed until the weather clears and turns wet again.
The config page can switch it off, and its **Show radar now** button puts it
up whatever the weather (it then stays until closed), for trying it out.

## Web config page

`http://<shield-ip>:8080` (Office `192.168.1.209`, Livingroom `192.168.1.197`)
edits pins, hidden apps, quick actions, hold time and the Home Assistant URL
and token, live, per device. "From Home Assistant…" on an action lists your entities
(filterable: automations, scenes, scripts, lights, …); tick one or more of
the same kind (say two lights, or several automations), then pick one of the
services their domain offers (e.g. automation trigger / turn on / turn off /
toggle). It fills in service, entities, label and icon; one press then acts on
every ticked entity in a single call. Reopening it on an action starts from
that action's entities, so more can be added. The SHIELD proxies
`/api/states` and `/api/services` for it, so the browser never talks to Home
Assistant directly and the token stays on the device. It is open to anyone on the LAN by choice; the
token alone is write-only and never sent back. The server runs inside the
quick actions service (`WebConfigServer`), so that service must be enabled.

`./build.sh install` does the device setup: it appends the service to
`enabled_accessibility_services` (replacing the list would switch off any
other app's service, such as Button Mapper) and adds the app to the doze allowlist.

## Backgrounds

The home screen shows one of the Flint Hills images, chosen from Home
Assistant's state. They're generated locally with ComfyUI (see
[scripts/backgrounds/README.md](scripts/backgrounds/README.md)) and **ship in
the APK**. The finished set in `output/backgrounds/` (WebPs and
`manifest.json`) is committed, so a fresh clone builds with them. The build
copies it into `assets/backgrounds/`. After regenerating, commit the changed
images; new images or focal elements reach the TVs with the next
`./build.sh install`. The full set makes the APK about 375 MB.

| Input | Default entity | Gives |
| --- | --- | --- |
| Weather | `weather.lake_of_the_forest` (Tempest) | condition: sunny/clear-night → clear, exceptional → cloudy, hail → pouring, lightning → lightning-rainy, snowy-rainy → snowy, windy-variant → windy, unknown/unavailable → clear |
| Sun | `sun.sun` | above/below_horizon → day/night |
| Season | `sensor.season` (Season integration) | spring/summer/autumn/winter |

- **Holidays win over weather**, shown in today's weather (a rainy Halloween)
  when that image exists, else the clear holiday image. The date ranges are
  editable on the config page. Thanksgiving is computed (fourth Thursday of
  November, plus Friday) and so is Easter; `12-31..01-01` wraps over New Year.
  If there's no image for the holiday, it falls back to weather.
- **Focal element:** one per calendar day, rotating in name order through
  `focal_elements` from the bundled manifest. It's held all day, and a new one
  joins the rotation with the APK that carries it.
- **Fallback chain:** exact image → same focal/season/time `clear` → another
  focal's season/time `clear` → plain black. A missing sun or season entity
  isn't guessed around; the config page says which one is missing.
- **Under the date:** the weather entity's condition and temperature, then
  its daily forecast (`weather.get_forecasts`): today's high, low and chance
  of rain (snow on snowy days), or tomorrow's from 18:00. Either line is hidden
  when Home Assistant has nothing for it.
- **Refresh:** every 5 minutes while HOME is showing (the client is REST-only,
  no websocket), at midnight, and right after a settings change. It crossfades
  over 1.5 s, only when the chosen path changes.
- **Startup and decoding:** the last pick shows straight away on start, and it
  stays up while Home Assistant is unreachable. Decoding happens off the main
  thread at display size, so 4K WebPs load at 1920×1080 on these SHIELDs.
- **Readability and layout:**
  - A left-to-right black gradient sits behind the UI: 75% at the left edge in
    the day, 65% at night, clear by 65% of the width.
  - Clock, date and list text get a soft shadow.
  - Apps and lists stay in the left 65% of the screen, and the banner row
    wraps there, so the image's focal element on the right stays clear.
  - All of these numbers are settings.
- **Debugging:**
  - The config page's Background section shows what's on screen and why.
  - Its Preview controls force focal/season/time/weather/holiday, or "None" to
    preview weather during a holiday. Set them back to Automatic afterwards.
  - Every pick is logged: `adb logcat -s Backgrounds`.

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
