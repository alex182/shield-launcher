package dev.alex.minlauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What the TV is doing, as reported to Home Assistant ({@link TvWatcher}):
 * one state for automations to trigger on, and the details as attributes.
 * Pure, for unit tests.
 *
 * <ul>
 *   <li>"off": the screen is off;</li>
 *   <li>"visualizer": the music visualizer is showing;</li>
 *   <li>"playing": some app is playing sound (video or music);</li>
 *   <li>"home": the launcher's home screen, nothing playing;</li>
 *   <li>"app": another app in front, nothing playing.</li>
 * </ul>
 */
final class TvState {

    final boolean screenOn, visualizer, ambilight;
    /** The app in front (this launcher's package on the home screen), and its name. */
    final String app, appName;
    /** The app playing sound, "" if none; "?" if something plays that the dump doesn't name. */
    final String playing;
    final boolean home;

    TvState(boolean screenOn, boolean visualizer, boolean ambilight, String app, String appName,
            boolean home, String playing) {
        this.screenOn = screenOn;
        this.visualizer = visualizer;
        this.ambilight = ambilight;
        this.app = app == null ? "" : app;
        this.appName = appName == null ? "" : appName;
        this.home = home;
        this.playing = playing == null ? "" : playing;
    }

    String state() {
        if (!screenOn) return "off";
        if (visualizer) return "visualizer";
        if (!playing.isEmpty()) return "playing";
        return home ? "home" : "app";
    }

    JSONObject attributes(String friendlyName) throws JSONException {
        return new JSONObject()
                .put("friendly_name", friendlyName)
                .put("icon", screenOn ? "mdi:television-play" : "mdi:television-off")
                .put("app", home ? "" : app)
                .put("app_name", home ? "Home" : appName)
                .put("playing", !playing.isEmpty())
                .put("playing_app", playing.equals("?") ? "" : playing)
                .put("visualizer", visualizer)
                .put("ambilight", ambilight)
                .put("screen", screenOn ? "on" : "off");
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof TvState)) return false;
        TvState t = (TvState) o;
        return t.screenOn == screenOn && t.visualizer == visualizer && t.ambilight == ambilight
                && t.home == home && t.app.equals(app) && t.playing.equals(playing);
    }

    @Override public int hashCode() { return Objects.hash(screenOn, visualizer, ambilight, home, app, playing); }

    /**
     * The first app playing sound: its package, "?" for a stream the dump
     * doesn't name, "" for none.
     */
    static String playingApp(List<AudioSessions.Playing> playing) {
        for (AudioSessions.Playing p : playing) if (!p.pkg.isEmpty()) return p.pkg;
        return playing.isEmpty() ? "" : "?";
    }

    /**
     * Whether one of the watched apps is playing. A stream whose app the dump
     * doesn't name counts when a watched app is in front.
     */
    static boolean watched(List<AudioSessions.Playing> playing, Collection<String> apps, String front) {
        for (AudioSessions.Playing p : playing) {
            if (apps.contains(p.pkg)) return true;
            if (p.pkg.isEmpty() && apps.contains(front)) return true;
        }
        return false;
    }

    /** "Office SHIELD" → "sensor.office_shield": the default entity for a device name. */
    static String defaultEntity(String deviceName) {
        String slug = (deviceName == null ? "" : deviceName).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return "sensor." + (slug.isEmpty() ? "shield" : slug);
    }

    /** A sensor entity id Home Assistant accepts: "sensor." and lowercase letters, digits and _. */
    static boolean validEntity(String id) {
        return id != null && id.matches("sensor\\.[a-z0-9_]+");
    }
}
