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
| `app/src/main/java/dev/alex/minlauncher/` | `MainActivity` (home), `QuickMenuService` (overlay + web server host), `LightPanel` + `Lights` (long-press light controls), `WebConfigServer`, `Store`, `Apps`, `HomeAssistant`, `Icons`, `CameraOverlay`, `RadarOverlay`, `VisualizerOverlay` + `VisualizerImage`; backgrounds: `Backgrounds` (pure picking logic), `BackgroundController`, `Scrim`, `BackgroundDream` (screensaver); `Agenda` (pure) + `AgendaController` (today's events line) + `EventReminders` (pop-up before events); `Tone` (alert and chime playback); `TileFlow` (wrapping banner row); ambilight: `Ambilight` (pure geometry, sampling and packets), `AmbilightConfig` (settings), `AmbilightService` (capture and streaming), `AmbilightActivity` (asks for the capture) |
| `app/src/test/` | JVM unit tests (`Backgrounds`, `Agenda`, `Alerts`, `Lights`, `TileFlow` wrapping, `Ambilight`); JUnit is test-only |
| `app/src/main/assets/` | `config.html` (web page), `mdi/` (icon font), `visualizer/` (butterchurn page and presets) |
| `app/src/main/res/` | theme, colours, focus selector, layout |
| `tools/update-mdi.sh` | refresh the bundled icon font |
| `tools/update-butterchurn.sh` | refresh the bundled visualizer library and presets |
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

### Weather alerts

When the National Weather Service issues an alert for home, `AlertBanner`
puts a banner across the top of the screen over any app: the event
("TORNADO WARNING"), its headline, and the alert's text and instructions
crawling along the bottom. A new alert plays the EAS attention signal once
(853 Hz and 960 Hz together, 20 s by default, the tone TV stations play;
the EAS rules allow 8 to 25 s), and
updates to an alert already out (extended, re-worded) come without it. The
tone ducks whatever is playing rather than pausing it, and plays as media
so it goes wherever the show's sound goes (an alarm would play on the
built-in output and only sometimes on Bluetooth as well).

- **Source:** `api.weather.gov/alerts/active?point=` at Home Assistant's
  `zone.home`, or a `lat,lon` set on the config page; checked every minute
  while the screen is on. Only `Actual` alerts count (no tests or
  exercises), and cancellations take the banner down.
- **Which:** "Severe" and up by default (warnings: tornado, severe
  thunderstorm, flash flood); the config page can lower it to Moderate
  (watches, many advisories) or Minor (everything).
- **Closing:** Back or the Netflix button, ahead of the radar. Each alert
  shows once; the ids shown are kept until the alert ends, so a restart
  or reinstall doesn't sound them again.
- **Testing:** "Send a test alert" on the config page shows a banner marked
  TEST and plays the tone.

The attention signal is for real emergencies when broadcast (47 CFR 11.45);
this only plays it on the TV in the room, for a real NWS alert or a test
someone asked for.

## Music visualizer

Hold play/pause (1 s) over any app for Milkdrop visuals full screen
(`VisualizerOverlay`): [butterchurn](https://github.com/jberg/butterchurn)
in a WebView, with its main pack of 100 presets, both MIT and bundled in
`assets/visualizer/` (`tools/update-butterchurn.sh [version] [presets]`
refreshes them). Hold play/pause again, Back or the Netflix button closes it.
The config page's **Show on the TV now** opens it too.

| Key | Does |
| --- | --- |
| Left / right | previous / next preset |
| Up | like this preset, or unlike it |
| Down | a random preset |
| Select | hold this preset (stop cycling); again to cycle |
| Play/pause, volume, media keys | still go to the player |

- **Settings** (config page, Music visualizer) copy butterchurnviz.com's,
  with its defaults: select a preset, cycle presets every 25 s, randomize
  next preset, auto / user-solicited blend (2.7 s / 5.7 s), frame limit
  (30), canvas size and mesh size (48 × 36). Canvas size is the exception:
  0.67× native (720p) by default, and no 2×/4×, for the Tegra's sake. They
  apply live: the TV page re-reads them every second.
- **Liked presets**: up on the remote, or Like on the config page next to
  the preset on screen, saves a like straight away (`visualizer_liked`, kept
  apart from the settings so the page's Save can't undo a like made on the
  TV). *Cycle through: only liked presets* keeps the timer and the arrows to
  them; the page's **Play** buttons open the visualizer on the liked list (from
  one preset, or anywhere) for that showing only (`POST /api/visualizer/show`
  with `playlist`, `preset`; likes go through `POST /api/visualizer/like`).
- **Source images**: pictures the presets melt and mix. The TV page paints
  one into butterchurn's feedback frame (`renderer.targetFrameBuffer`) just
  before each `render()`, so the preset's own warp, zoom and decay carry it
  off like anything it drew. **Mix images in** turns them on or off
  straight away, on the config page (`POST /api/visualizer/images` with
  `on`) or by holding Select on the TV (a short press still holds the
  preset); it's its own pref (`visualizer_images`), apart from the settings,
  so the page's Save can't undo a toggle made on the TV. *Steady* paints a little every frame (the image
  keeps re-forming), *On the beat* splashes it in on bass hits; strength
  1–100, *Fit* or *Fill*, and *Soft edges* 0–100 (fades the image out
  towards its borders, up to a soft oval, so it doesn't sit in a box). With
  several, they take turns in upload order, the next one with each preset
  change or every 10 s to 5 min; the new one simply starts being painted in
  and the preset melts the old one away. PNG transparency is kept, so a logo
  adds only its opaque parts. The config page scales uploads to 1280 px and
  sends them straight away (`POST /api/visualizer/image`, raw bytes, up to
  6 MB each, 50 images; `GET /api/visualizer/image/<id>`;
  `POST /api/visualizer/image/delete` with `id`, `/clear` for all). They're
  files in the app's storage (`VisualizerImage`, `files/visualizer-images/`),
  not prefs, so the settings backup leaves them out. This reaches into
  butterchurn internals: re-check it after `tools/update-butterchurn.sh`.
  A **built-in set** of 12 ships in the APK (`assets/visualizer/defaults/`,
  about 6 MB) and is copied into a SHIELD's images on first run, after any
  already there, so mixing is on (*Steady*) out of the box; remove any like
  an upload, and **Restore built-in images** (`POST
  /api/visualizer/image/defaults`) brings back the removed ones
  (`files/visualizer-defaults.json` remembers which id each became). They're
  made by `scripts/generate_visualizer_images.py`: it draws the prompts in
  `scripts/visualizer_images/config.json` with the local ComfyUI (as for the
  backgrounds) as glowing subjects on black, then keys the black out to
  transparency, so only the subject melts in. Raw and keyed images and a
  contact sheet go to `output/visualizer/` (not committed);
  `--install-defaults` replaces the generated part of the built-in set
  (`NN_name.png`), `--upload http://<shield>:8080` sends them to one SHIELD
  instead. `--add-default FILE…` adds your own pictures to the built-in set
  (scaled to 1280 px, PNG with transparency or else JPEG; `--key-black` keys
  out a black background); delete a file there to drop it. A SHIELD copies
  in built-ins new in an update once, and never revives ones removed on it.
- **Sound** ("Listen to"): *whatever is playing* (default), *one app*,
  *the whole output mix* or *a microphone*. The first two use
  `android.media.audiofx.Visualizer` on the app's own audio session, found in
  `dumpsys media.audio_flinger`'s track tables (`AudioSessions`) and checked
  again every 2 s, so it follows a new song, player or app. The page pulls the
  latest 1024-sample waveform each frame through a JS bridge and hands it to
  butterchurn in place of WebAudio.
  - The output mix (session 0) would need no lookup, but it doesn't work on
    the SHIELD: media goes out through a bus device (`AUDIO_DEVICE_OUT_BUS`),
    and session 0's capture lands on another, idle output and hears
    silence. That's why the visuals didn't follow the music at first.
  - The microphone is `AudioRecord` on a USB mic; the remote's mic only opens
    for voice search.
  - The config page shows what it's listening to and which apps are playing
    now (`GET /api/visualizer`), from the status the overlay writes
    (`visualizer_status`).
  - `./build.sh install` grants both permissions it needs, `DUMP` (the
    lookup) and `RECORD_AUDIO` (the capture); without either, the presets run
    to silence and say which is missing. Audio sent to a receiver as encoded
    passthrough (Dolby, DTS) is never captured, so it stays flat then too.
- **Play/pause is held back:** the service can't tell a press from a hold
  until the button comes up, so it swallows the press and, if it was short,
  sends play/pause on with `AudioManager.dispatchMediaKeyEvent`. That
  reaches players through their media session (Tidal, Jellyfin, YouTube and
  the like); a player that only reads keys in its own window would stop
  pausing. Untick "Hold play/pause for the visualizer" and play/pause goes
  straight through again.
- **Window:** keeps the screen on and closes when the screen turns off.
  `adb logcat -s Visualizer chromium` for problems; it logs each session it
  attaches to.

## Ambilight

WLED strips round the TV's room take their colour from the picture
(`AmbilightService`). Each LED shows the part of the picture it's nearest, as
if the picture carried on past its edges into the room: one up and left of
the TV shows the top-left corner, one on the right wall the right edge, one
on the ceiling above the TV the middle of the top edge. LEDs near the middle
of the back wall, roughly in line with the screen, have no edge to go by, so
they fade to the whole picture's average.

- **Layout** (config page, Ambilight): the room as a box seen from the seat
  facing the TV (width, depth, height), the TV on the TV wall (size, centre,
  how far it stands out), and each strip as a path of points on the walls,
  ceiling, floor or the back of the TV. The path runs from the strip's data
  input end. Points are drawn on an unfolded room (ceiling above the TV wall,
  floor below, the four walls in a row) and checked in a 3D view; the TV adds
  the corner point when a path crosses to a neighbouring surface. **Run it
  round the TV** lays a strip round the back of the TV from a chosen corner.
  Stored in centimetres (`ambilight` in SharedPreferences, see
  `AmbilightConfig`); the page shows inches or centimetres.
- **Strips:** each has a WLED address (`ip` or `ip:port`), its first LED's
  index on that controller and its LED count. Several strips can share one
  controller at different offsets. The LEDs are spread evenly along the path.
- **Streaming:** WLED's realtime UDP, DNRGB on port 21324 (WLED's Sync
  settings → "Receive UDP realtime", on by default), up to 489 LEDs a packet,
  at 30 updates a second by default. Every packet carries a 2 s timeout, so
  when the stream stops (Stop, screen off, a crash) WLED goes back to what it
  was doing by itself. WLED that was off turns on for it, at its last
  brightness, and off again after.
- **Picture:** the screen is mirrored into a 160×90 capture
  (`MediaProjection`). Each LED averages a patch by the border at its point
  (15% deep by default) through summed-area tables, so the cost doesn't
  depend on patch size. *Skip the outer edge* (`edgeInset`, 0–30%, 0 by
  default) starts the patch that far in, past bright borders, menus or
  logos at the very edge. Letterbox and pillarbox bars are skipped once they've
  held for 0.7 s, the same top and bottom so subtitles in a bar don't move
  it. Brightness, saturation (120% by default), gamma and smoothing (an
  exponential fade, 150 ms) are settings. Gamma is 1 (none) by default, but
  WS2812 strips are linear and WLED leaves realtime data uncorrected, so
  pictures look pale; 2.2 matches the screen.
- **Protected video is black.** Netflix, Disney+, Prime and most paid
  streaming draw on secure surfaces that every screen capture sees as black,
  so the strips go dark for them; the page says so when the picture has
  been black for a few seconds. Jellyfin, YouTube, games, the home screen and
  the visualizer come through. Lighting DRM video would need an HDMI capture
  device, not the SHIELD.
- **On and off:** **Ambilight** in quick actions (above Sleep, once there's a
  strip), Start/Stop on the config page, or **Start with the TV**, which
  starts it whenever the screen comes on. It pauses while the screen is off.
- **The capture token:** only an activity can ask for one
  (`AmbilightActivity`, invisible), and Android 10+ wants it held by a
  foreground service of type `mediaProjection` (`AmbilightService`).
  `./build.sh install` runs `appops set dev.alex.minlauncher PROJECT_MEDIA
  allow`, so the system grants it without its "Start now?" dialog; without
  that, the TV asks each time it starts.
- **Testing:** **Test pattern** lights every strip with the picture's border
  in rainbow colours (right edge red, top yellow-green, left cyan, bottom
  blue to magenta), the same colours the page draws, so the room should match
  the drawing. **Find its ends** lights one strip's first LED green and last
  red with a white dot running from first to last, to check which end is the
  input and that the count is right. Both use the page's unsaved layout and
  run for a minute, then the ambilight carries on as before. **Colours: live
  from the TV** shows what each LED is being sent.
- **Debugging:** the page's status line (frames a second, bars skipped,
  per-strip send errors); `adb logcat -s Ambilight`.

## Web config page

`http://<shield-ip>:8080` (Office `192.168.1.209`, Livingroom `192.168.1.197`)
edits pins, hidden apps, quick actions, hold time, the visualizer, the ambilight and the Home Assistant URL
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
other app's service, such as Button Mapper) adds the app to the doze allowlist, and lets it capture the screen for the
ambilight (`PROJECT_MEDIA`) without a dialog.

### Backup

The page's **Backup** section downloads the settings as a JSON file
(`GET /api/backup`, `Backup.java`): pins, hidden apps, quick actions, the
visualizer and its likes, the ambilight's layout, cameras, calendar,
weather, background and the Home Assistant URL. It doesn't hold the Home
Assistant token (the page never reads it back, so type it again on a fresh
install), or what the TV writes for itself (statuses, alerts and reminders
already shown).

**Restore from a file…** shows where and when the backup was made and puts
back the parts ticked (`POST /api/restore`). Each part replaces this
SHIELD's, and settings the backup doesn't have go back to their defaults. A
backup from the other SHIELD starts with the ambilight unticked, since its
layout belongs to that room. The whole file is checked before anything is
written, and the settings it replaced are kept for one **Undo the last
restore**.

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

## Calendar

Today's events from Home Assistant's calendars (`AgendaController`), as one
line right under the date on HOME and the screensaver, styled like the forecast:
`Bin day · 16:00 Therapy · +1 more`. All-day events come first, then by start
time; the first two are named and the rest counted. One under way reads
`Therapy until 17:00`. Finished events drop off on the minute. After the
day's last event it shows tomorrow's instead, `Tomorrow · 09:00 Dentist`, and
it is gone when there's nothing today or tomorrow.

- **Which calendars:** every `calendar.*` entity (`GET /api/calendars`)
  except the Workday integration's (any id containing `workday`; it marks
  every working day), or the ones ticked in the config page's Calendar
  section. The same event in two calendars shows once.
- **Reading:** `GET /api/calendars/<entity>?start=…&end=…` for today and
  tomorrow, every 5 minutes while HOME or the screensaver is showing, at
  midnight, and right after a settings change. When Home Assistant is
  unreachable the last events stay up.
- **Debugging:** the Calendar section shows the last read (events, calendars,
  errors); `adb logcat -s Agenda`.

### Reminders

`EventReminders` (in the quick actions service, so over any app) pops a card
up in the top-right corner at each set time before a timed event in the same
calendars starts (10 minutes by default; a list such as `60, 30, 10` gives
one at each): "STARTS IN 10 MIN", the event and its time, and a soft
two-note chime (generated, E5 then C5) that ducks what's playing, like the
alert tone (media usage, so it reaches Bluetooth speakers too). It goes by
itself after a minute; Back or the Netflix button closes it sooner. Events
starting together share one card. All-day events get none.

- **Settings:** the config page's Calendar section: the reminder times, added and
  removed one by one (none turns reminders off), the chime, and "Send a test reminder", which puts a
  made-up event up on the TV.
- **Timing:** only while the screen is on. It looks every minute and re-reads
  Home Assistant every 5. Each reminder comes once (kept in `reminders_seen`
  until the event starts), so a restart doesn't chime again. A TV turned on
  late gets only the latest reminder due: on 20 minutes before, with
  `60, 30, 10`, it chimes once at 10, not three times.
- **Evening summary:** at 20:30 (a setting, or off) a card in the same
  corner lists tomorrow's events, "TOMORROW · FRIDAY, 30 OCTOBER" then one
  row each (time, or All day), up to six, with the chime if it's on. It
  stays two minutes. Once a day: a TV that is off at 20:30 gets it when
  turned on before midnight. Nothing tomorrow, no card. The config page's
  "Show tomorrow's events now" puts it up straight away, empty or not.
- **Debugging:** `adb logcat -s Reminders`.

## Screensaver

`BackgroundDream` is an Android screensaver ("Flint Hills"): the home screen's
background full screen, with the clock, date, forecast, current weather and
today's events in the image's quiet left side. It picks and refreshes the image the same way
HOME does (same focal element, season, time, weather and holiday, crossfading
when they change). The picture is dimmed with 45% black (`dream_dim` in
`colors.xml`; a SHIELD can't dim the TV itself), and the text drifts a little
every minute, mostly up and down, so it stays in the dark left edge and
nothing stays still on the panel. Any key wakes it.

Pick it on the SHIELD under Settings → Device Preferences → Screen saver, or
over adb:

```bash
adb -s <shield> shell settings put secure screensaver_components dev.alex.minlauncher/.BackgroundDream
adb -s <shield> shell am start -n com.android.systemui/.Somnambulator   # try it now
```

Both SHIELDs used Google's Backdrop
(`com.google.android.backdrop/.Backdrop`) before; put that back to undo.
`./build.sh install` doesn't change the screensaver setting. Developer
options → **Stay awake** (`stay_on_while_plugged_in`) stops any screensaver
from starting, because the device is never idle; it is off on both SHIELDs
since 2026-10-28, so they also sleep after their sleep timeout again.

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
