package dev.alex.minlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-device settings shared by the home screen, the quick actions overlay
 * and the web config page. All three run in one process, so they see the same
 * SharedPreferences instance and its change listeners.
 */
final class Store {

    /** Pinned on first run, in this order, when installed. */
    private static final String[] DEFAULT_PINNED = {
            "org.jellyfin.androidtv",
            "com.aspiro.tidal",
    };

    /** Hidden on first run: system tools that are rarely opened directly. */
    private static final String[] DEFAULT_HIDDEN = {
            "com.android.documentsui",
            "com.android.gallery3d",
            "com.nvidia.inputviewer",
            "com.nvidia.ota",
            "com.nvidia.remotelocator",
    };

    static final String PREFS = "launcher";
    static final String KEY_PINNED = "pinned";     // comma-joined, ordered
    static final String KEY_HIDDEN = "hidden";     // string set
    static final String KEY_ACTIONS = "actions";   // JSON array, see Action
    static final String KEY_HOLD_MS = "hold_ms";
    static final String KEY_HA_URL = "ha_url";
    static final String KEY_HA_TOKEN = "ha_token";
    static final String KEY_SINGLE_PRESS = "netflix_single";   // package, "" = nothing
    static final String KEY_CAMERAS = "cameras";               // comma-joined entity ids
    static final String KEY_SLEEP_MINUTES = "sleep_minutes";   // sleep timer length
    static final String KEY_SLEEP_SHOWN = "sleep_shown";       // Sleep row at the bottom of quick actions
    static final String KEY_VISUALIZER_HOLD = "visualizer_hold";   // hold play/pause for the music visualizer
    static final String KEY_VISUALIZER = "visualizer";         // JSON, see visualizer()
    static final String KEY_VISUALIZER_STATUS = "visualizer_status";   // JSON: what it listens to, written by VisualizerOverlay
    static final String KEY_VISUALIZER_LIKED = "visualizer_liked";     // JSON array of preset names, liked on the TV or the page
    static final String KEY_VISUALIZER_SHOWING = "visualizer_showing"; // the preset on screen now, "" when closed
    static final String KEY_VISUALIZER_IMAGES = "visualizer_images";   // source images mixed in or not, toggled on the TV or the page
    static final String KEY_AMBILIGHT = "ambilight";           // JSON, see AmbilightConfig
    static final String KEY_MODE_LIGHTS = "mode_lights";       // JSON, see ModeLights: room lights for the visualizer and ambilight
    static final String KEY_MODE_LIGHTS_STATUS = "mode_lights_status";   // JSON {mode: {at, text, error}}, written by ModeLightsController
    static final String KEY_RADAR_SHOWN = "radar_shown";       // radar pop-up while it rains
    static final String KEY_RADAR_SOURCE = "radar_source";     // image URL or camera entity
    static final String KEY_RADAR_LIGHTNING_DISTANCE = "radar_lightning_distance";   // entity ids, "" = off
    static final String KEY_RADAR_LIGHTNING_STRIKE = "radar_lightning_strike";
    static final String KEY_RADAR_LIGHTNING_MILES = "radar_lightning_miles";
    static final String KEY_ALERTS_SHOWN = "alerts_shown";     // NWS alert banner
    static final String KEY_ALERTS_SEVERITY = "alerts_severity";   // minimum CAP severity
    static final String KEY_ALERTS_TONE_SECONDS = "alerts_tone_seconds";   // 0 = no tone
    static final String KEY_ALERTS_POINT = "alerts_point";     // "lat,lon", "" = zone.home
    // Written by AlertBanner, not by the user.
    static final String KEY_ALERTS_SEEN = "alerts_seen";       // JSON {alert id: end ms}: shown already
    static final String KEY_ALERTS_STATUS = "alerts_status";   // JSON: last check
    static final String KEY_CALENDAR_SHOWN = "calendar_shown"; // today's events on HOME
    static final String KEY_CALENDARS = "calendars";           // comma-joined entity ids, "" = every calendar
    static final String KEY_CALENDAR_STATUS = "calendar_status";   // JSON: last fetch, written by AgendaController
    static final String KEY_REMINDER_TIMES = "reminder_times"; // minutes before an event, "60, 30, 10"; "" = off
    static final String KEY_REMINDER_CHIME = "reminder_chime";
    static final String KEY_REMINDERS_SEEN = "reminders_seen"; // JSON {event key: start ms}: shown already
    static final String KEY_DIGEST_SHOWN = "digest_shown";     // evening summary of tomorrow's events
    static final String KEY_DIGEST_TIME = "digest_time";       // "HH:mm"
    static final String KEY_DIGEST_DAY = "digest_day";         // local midnight of the day it last came up
    // Backgrounds: inputs. A change to any of these re-picks the background.
    static final String KEY_BG_WEATHER = "bg_weather";         // entity ids
    static final String KEY_BG_SUN = "bg_sun";
    static final String KEY_BG_SEASON = "bg_season";
    static final String KEY_BG_HOLIDAYS = "bg_holidays";       // Backgrounds.DEFAULT_HOLIDAYS format
    static final String KEY_BG_OVERRIDE = "bg_override";       // JSON {focal, season, time, condition, holiday}
    // Readability and layout, in percent.
    static final String KEY_SCRIM_DAY = "scrim_day";           // gradient opacity at the left edge
    static final String KEY_SCRIM_NIGHT = "scrim_night";
    static final String KEY_SCRIM_END = "scrim_end";           // where it is fully transparent, % of width
    static final String KEY_CONTENT_WIDTH = "content_width";   // tiles and lists stay left of this, % of width
    // Written by BackgroundController, not by the user.
    static final String KEY_BG_STATUS = "bg_status";           // JSON: what is showing and why
    static final String KEY_BG_LAST = "bg_last";               // last shown path, put up first on start
    static final String KEY_BG_FOCAL_DAY = "bg_focal_day";     // "YYYY-MM-DD focal": today's pick, held all day

    static final String DEFAULT_WEATHER = "weather.lake_of_the_forest";   // the Tempest station
    static final String DEFAULT_SUN = "sun.sun";
    /** NWS Kansas City/Pleasant Hill radar, the closest to Lake of the Forest; an animated loop. */
    static final String DEFAULT_RADAR = "https://radar.weather.gov/ridge/standard/KEAX_loop.gif";
    /** The Tempest's last strike: how far, and when. */
    static final String DEFAULT_LIGHTNING_DISTANCE = "sensor.lake_of_the_forest_lightning_last_distance";
    static final String DEFAULT_LIGHTNING_STRIKE = "sensor.lake_of_the_forest_lightning_last_strike";
    static final int DEFAULT_LIGHTNING_MILES = 15;
    static final String DEFAULT_SEASON = "sensor.season";
    static final int DEFAULT_SCRIM_DAY = 75;
    static final int DEFAULT_SCRIM_NIGHT = 65;
    static final int DEFAULT_SCRIM_END = 65;
    static final int DEFAULT_CONTENT_WIDTH = 65;

    static final long DEFAULT_HOLD_MS = 3000;
    /** Where the visualizer's sound comes from. */
    static final List<String> VISUALIZER_SOURCES = Arrays.asList("auto", "app", "mix", "mic");
    /** Which presets it cycles through: every one, or only the liked ones. */
    static final List<String> VISUALIZER_PLAYLISTS = Arrays.asList("all", "liked");
    /** butterchurnviz.com's own choices; 0 frames = no limit. Canvas sizes past native are left out. */
    static final List<Integer> VISUALIZER_FRAME_LIMITS = Arrays.asList(15, 30, 45, 60, 0);
    static final List<Double> VISUALIZER_CANVAS_SIZES = Arrays.asList(0.25, 0.5, 0.67, 1.0);
    static final List<Integer> VISUALIZER_MESH_SIZES = Arrays.asList(18, 24, 36, 48, 72, 96);
    /** How the source image ({@link VisualizerImage}) goes into the picture: not at all, all the time, or on the beat. */
    static final List<String> VISUALIZER_IMAGE_MODES = Arrays.asList("steady", "beat");
    /** The whole image inside the picture, or the picture filled and the image cropped. */
    static final List<String> VISUALIZER_IMAGE_FITS = Arrays.asList("fit", "fill");
    /** Seconds between source images; 0 = the next one with each preset change. */
    static final List<Integer> VISUALIZER_IMAGE_CYCLES = Arrays.asList(0, 10, 30, 60, 300);
    static final int DEFAULT_SLEEP_MINUTES = 90;
    static final int MAX_SLEEP_MINUTES = 600;
    /** What Button Mapper did with the Netflix button before this took it over. */
    static final String DEFAULT_SINGLE_PRESS = "org.jellyfin.androidtv";

    /**
     * A quick action. {@code service} is Home Assistant's "domain.service"
     * (e.g. "scene.turn_on") and {@code data} its JSON body; an empty service
     * is a placeholder that does nothing yet. {@code icon} is an "mdi:" name,
     * as in Home Assistant, or blank.
     */
    static final class Action {
        final String label, icon, service, data;
        Action(String label, String icon, String service, String data) {
            this.label = label; this.icon = icon; this.service = service; this.data = data;
        }
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("label", label).put("icon", icon)
                    .put("service", service).put("data", data);
        }
        static Action fromJson(JSONObject o) {
            return new Action(o.optString("label"), o.optString("icon"),
                    o.optString("service"), o.optString("data"));
        }
    }

    private final SharedPreferences prefs;

    Store(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_PINNED)) {
            prefs.edit()
                    .putString(KEY_PINNED, TextUtils.join(",", DEFAULT_PINNED))
                    .putStringSet(KEY_HIDDEN, new HashSet<>(Arrays.asList(DEFAULT_HIDDEN)))
                    .apply();
        }
    }

    SharedPreferences prefs() { return prefs; }

    List<String> pinned() {
        List<String> out = new ArrayList<>();
        for (String p : prefs.getString(KEY_PINNED, "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    void setPinned(List<String> pkgs) {
        prefs.edit().putString(KEY_PINNED, TextUtils.join(",", pkgs)).apply();
    }

    /** A copy: the set getStringSet returns must not be modified in place. */
    Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
    }

    void setHidden(Set<String> pkgs) {
        prefs.edit().putStringSet(KEY_HIDDEN, pkgs).apply();
    }

    List<Action> actions() {
        List<Action> out = new ArrayList<>();
        String raw = prefs.getString(KEY_ACTIONS, null);
        if (raw == null) {
            for (int i = 1; i <= 4; i++) out.add(new Action("Action " + i, "", "", ""));
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) out.add(Action.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) { }
        return out;
    }

    void setActions(List<Action> actions) throws JSONException {
        JSONArray a = new JSONArray();
        for (Action x : actions) a.put(x.toJson());
        prefs.edit().putString(KEY_ACTIONS, a.toString()).apply();
    }

    long holdMs() { return prefs.getLong(KEY_HOLD_MS, DEFAULT_HOLD_MS); }
    String haUrl() { return prefs.getString(KEY_HA_URL, ""); }
    String haToken() { return prefs.getString(KEY_HA_TOKEN, ""); }

    int sleepMinutes() { return prefs.getInt(KEY_SLEEP_MINUTES, DEFAULT_SLEEP_MINUTES); }
    boolean sleepShown() { return prefs.getBoolean(KEY_SLEEP_SHOWN, true); }

    void setSleep(int minutes, boolean shown) {
        prefs.edit().putInt(KEY_SLEEP_MINUTES, Math.max(1, Math.min(MAX_SLEEP_MINUTES, minutes)))
                .putBoolean(KEY_SLEEP_SHOWN, shown).apply();
    }

    String singlePress() { return prefs.getString(KEY_SINGLE_PRESS, DEFAULT_SINGLE_PRESS); }
    void setSinglePress(String pkg) { prefs.edit().putString(KEY_SINGLE_PRESS, pkg).apply(); }

    boolean visualizerHold() { return prefs.getBoolean(KEY_VISUALIZER_HOLD, true); }

    /**
     * The visualizer's settings, every field filled in. Modelled on
     * butterchurnviz.com's, with its defaults, except the canvas: 0.67 of
     * native (720p) keeps the Tegra's frame rate up.
     * <ul>
     *   <li>{@code source}: "auto" (whichever app is playing), "app" (only
     *       {@code app}, a package), "mix" (session 0) or "mic";</li>
     *   <li>{@code preset}: a preset name to show, "" for a random one;</li>
     *   <li>{@code playlist}: "all" presets, or only the "liked" ones
     *       ({@link #visualizerLiked()});</li>
     *   <li>{@code cycle}, {@code cycleSeconds}, {@code randomize}: change preset
     *       on a timer, to a random one or the next;</li>
     *   <li>{@code autoBlend}, {@code userBlend}: seconds to blend into a preset
     *       picked by the timer, or by the remote or this page;</li>
     *   <li>{@code frameLimit}, {@code canvasSize}, {@code meshSize}: frames a
     *       second (0 = no limit), drawing size as a share of the screen's,
     *       and mesh rows (the columns are 4/3 of them);</li>
     *   <li>{@code imageMode}, {@code imageStrength}, {@code imageFit},
     *       {@code imageCycle}, {@code imageSoftness}: how the source images
     *       ({@link VisualizerImage}) are fed into the preset's feedback for
     *       it to melt (when {@link #visualizerImages()} is on): "steady" or on the "beat"; how strongly, 1 to
     *       100; "fit" or "fill"; seconds before the next one, 0 for with
     *       each preset; and how far its edges fade out, 0 (hard) to 100 (an
     *       oval);</li>
     *   <li>{@code imageKeep}: 0 to 100, how strongly the image is also laid
     *       over the finished picture, where the preset can't wash it out.</li>
     * </ul>
     */
    JSONObject visualizer() {
        JSONObject in;
        try { in = new JSONObject(prefs.getString(KEY_VISUALIZER, "{}")); }
        catch (JSONException e) { in = new JSONObject(); }
        try {
            String source = in.optString("source", "auto");
            String playlist = in.optString("playlist", "all");
            int frames = in.optInt("frameLimit", 30);
            double canvas = in.optDouble("canvasSize", 0.67);
            int mesh = in.optInt("meshSize", 36);
            String imageMode = in.optString("imageMode", "steady");
            String imageFit = in.optString("imageFit", "fit");
            int imageCycle = in.optInt("imageCycle", 0);
            return new JSONObject()
                    .put("source", VISUALIZER_SOURCES.contains(source) ? source : "auto")
                    .put("app", in.optString("app", ""))
                    .put("preset", in.optString("preset", ""))
                    .put("playlist", VISUALIZER_PLAYLISTS.contains(playlist) ? playlist : "all")
                    .put("cycle", in.optBoolean("cycle", true))
                    .put("cycleSeconds", clamp(in.optInt("cycleSeconds", 25), 1, 3600))
                    .put("randomize", in.optBoolean("randomize", true))
                    .put("autoBlend", Math.max(0, Math.min(60, in.optDouble("autoBlend", 2.7))))
                    .put("userBlend", Math.max(0, Math.min(60, in.optDouble("userBlend", 5.7))))
                    .put("frameLimit", VISUALIZER_FRAME_LIMITS.contains(frames) ? frames : 30)
                    .put("canvasSize", VISUALIZER_CANVAS_SIZES.contains(canvas) ? canvas : 0.67)
                    .put("meshSize", VISUALIZER_MESH_SIZES.contains(mesh) ? mesh : 36)
                    .put("imageMode", VISUALIZER_IMAGE_MODES.contains(imageMode) ? imageMode : "steady")
                    .put("imageStrength", clamp(in.optInt("imageStrength", 30), 1, 100))
                    .put("imageFit", VISUALIZER_IMAGE_FITS.contains(imageFit) ? imageFit : "fit")
                    .put("imageCycle", VISUALIZER_IMAGE_CYCLES.contains(imageCycle) ? imageCycle : 0)
                    .put("imageSoftness", clamp(in.optInt("imageSoftness", 35), 0, 100))
                    .put("imageKeep", clamp(in.optInt("imageKeep", 20), 0, 100));
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only put() of finite values above
        }
    }

    /** Anything left out or out of range falls back to its default when read. */
    void setVisualizer(boolean hold, JSONObject settings) {
        prefs.edit().putBoolean(KEY_VISUALIZER_HOLD, hold)
                .putString(KEY_VISUALIZER, settings.toString()).apply();
    }

    String visualizerStatus() { return prefs.getString(KEY_VISUALIZER_STATUS, "{}"); }
    void setVisualizerStatus(String json) { prefs.edit().putString(KEY_VISUALIZER_STATUS, json).apply(); }

    /**
     * Liked preset names, oldest first. Kept apart from {@link #visualizer()},
     * so saving the config page doesn't undo a like made on the TV meanwhile.
     */
    JSONArray visualizerLiked() {
        try { return new JSONArray(prefs.getString(KEY_VISUALIZER_LIKED, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    /** Likes or unlikes a preset; the TV and the page can both call this at once. */
    synchronized void setVisualizerLiked(String preset, boolean liked) {
        JSONArray old = visualizerLiked(), out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            if (!old.optString(i).equals(preset)) out.put(old.optString(i));
        }
        if (liked) out.put(preset);
        prefs.edit().putString(KEY_VISUALIZER_LIKED, out.toString()).apply();
    }

    /**
     * Whether the source images are mixed in. Kept apart from
     * {@link #visualizer()}, like the likes, so the page's Save can't undo a
     * toggle made on the TV. On by default (the built-in images are there
     * from the start), except where an earlier version saved the mode "off".
     */
    boolean visualizerImages() {
        if (prefs.contains(KEY_VISUALIZER_IMAGES)) return prefs.getBoolean(KEY_VISUALIZER_IMAGES, true);
        try { return !"off".equals(new JSONObject(prefs.getString(KEY_VISUALIZER, "{}")).optString("imageMode")); }
        catch (JSONException e) { return true; }
    }
    void setVisualizerImages(boolean on) { prefs.edit().putBoolean(KEY_VISUALIZER_IMAGES, on).apply(); }

    String visualizerShowing() { return prefs.getString(KEY_VISUALIZER_SHOWING, ""); }
    void setVisualizerShowing(String preset) { prefs.edit().putString(KEY_VISUALIZER_SHOWING, preset).apply(); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** The ambilight's room, TV, strips and tuning, every field filled in; see {@link AmbilightConfig}. */
    JSONObject ambilight() {
        try { return AmbilightConfig.normalize(new JSONObject(prefs.getString(KEY_AMBILIGHT, "{}"))); }
        catch (JSONException e) { return AmbilightConfig.normalize(null); }
    }

    void setAmbilight(JSONObject settings) {
        prefs.edit().putString(KEY_AMBILIGHT, AmbilightConfig.normalize(settings).toString()).apply();
    }

    /** Lights switched as the visualizer or the ambilight starts and ends; see {@link ModeLights}. */
    JSONObject modeLights() {
        try { return ModeLights.normalize(new JSONObject(prefs.getString(KEY_MODE_LIGHTS, "{}"))); }
        catch (JSONException e) { return ModeLights.normalize(null); }
    }

    void setModeLights(JSONObject settings) {
        prefs.edit().putString(KEY_MODE_LIGHTS, ModeLights.normalize(settings).toString()).apply();
    }

    JSONObject modeLightsStatus() {
        try { return new JSONObject(prefs.getString(KEY_MODE_LIGHTS_STATUS, "{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }

    synchronized void setModeLightsStatus(String mode, JSONObject status) throws JSONException {
        prefs.edit().putString(KEY_MODE_LIGHTS_STATUS, modeLightsStatus().put(mode, status).toString()).apply();
    }

    boolean radarShown() { return prefs.getBoolean(KEY_RADAR_SHOWN, true); }
    String radarSource() { return prefs.getString(KEY_RADAR_SOURCE, DEFAULT_RADAR); }

    String radarLightningDistance() {
        return prefs.getString(KEY_RADAR_LIGHTNING_DISTANCE, DEFAULT_LIGHTNING_DISTANCE);
    }
    String radarLightningStrike() { return prefs.getString(KEY_RADAR_LIGHTNING_STRIKE, DEFAULT_LIGHTNING_STRIKE); }
    int radarLightningMiles() { return prefs.getInt(KEY_RADAR_LIGHTNING_MILES, DEFAULT_LIGHTNING_MILES); }

    boolean alertsShown() { return prefs.getBoolean(KEY_ALERTS_SHOWN, true); }
    String alertsSeverity() { return prefs.getString(KEY_ALERTS_SEVERITY, Alerts.DEFAULT_SEVERITY); }
    int alertsToneSeconds() { return prefs.getInt(KEY_ALERTS_TONE_SECONDS, Alerts.DEFAULT_TONE_SECONDS); }
    String alertsPoint() { return prefs.getString(KEY_ALERTS_POINT, ""); }
    String alertsSeen() { return prefs.getString(KEY_ALERTS_SEEN, "{}"); }
    String alertsStatus() { return prefs.getString(KEY_ALERTS_STATUS, "{}"); }
    void setAlertsSeen(String json) { prefs.edit().putString(KEY_ALERTS_SEEN, json).apply(); }
    void setAlertsStatus(String json) { prefs.edit().putString(KEY_ALERTS_STATUS, json).apply(); }

    void setAlerts(boolean shown, String severity, int toneSeconds, String point) {
        prefs.edit().putBoolean(KEY_ALERTS_SHOWN, shown)
                .putString(KEY_ALERTS_SEVERITY, Alerts.SEVERITIES.contains(severity) ? severity : Alerts.DEFAULT_SEVERITY)
                .putInt(KEY_ALERTS_TONE_SECONDS, Math.max(0, Math.min(Alerts.MAX_TONE_SECONDS, toneSeconds)))
                .putString(KEY_ALERTS_POINT, point).apply();
    }

    /** Blank entities switch the lightning check off. */
    void setRadarLightning(String distance, String strike, int miles) {
        prefs.edit().putString(KEY_RADAR_LIGHTNING_DISTANCE, distance)
                .putString(KEY_RADAR_LIGHTNING_STRIKE, strike)
                .putInt(KEY_RADAR_LIGHTNING_MILES, Math.max(1, Math.min(100, miles))).apply();
    }

    /** A blank source means the default radar. */
    void setRadar(boolean shown, String source) {
        prefs.edit().putBoolean(KEY_RADAR_SHOWN, shown)
                .putString(KEY_RADAR_SOURCE, source.isEmpty() ? DEFAULT_RADAR : source).apply();
    }

    /** Cameras to show, in order; empty means every camera Home Assistant has. */
    List<String> cameras() {
        List<String> out = new ArrayList<>();
        for (String p : prefs.getString(KEY_CAMERAS, "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    void setCameras(List<String> ids) {
        prefs.edit().putString(KEY_CAMERAS, TextUtils.join(",", ids)).apply();
    }

    boolean calendarShown() { return prefs.getBoolean(KEY_CALENDAR_SHOWN, true); }

    /** Calendars whose events show on HOME; empty means every calendar Home Assistant has. */
    List<String> calendars() {
        List<String> out = new ArrayList<>();
        for (String p : prefs.getString(KEY_CALENDARS, "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    void setCalendars(boolean shown, List<String> ids) {
        prefs.edit().putBoolean(KEY_CALENDAR_SHOWN, shown)
                .putString(KEY_CALENDARS, TextUtils.join(",", ids)).apply();
    }

    static final String DEFAULT_REMINDER_TIMES = "10";

    /** Reminder leads in minutes, smallest first; empty = no reminders. */
    List<Integer> reminderTimes() {
        return Agenda.parseLeads(prefs.getString(KEY_REMINDER_TIMES, DEFAULT_REMINDER_TIMES));
    }
    boolean reminderChime() { return prefs.getBoolean(KEY_REMINDER_CHIME, true); }
    String remindersSeen() { return prefs.getString(KEY_REMINDERS_SEEN, "{}"); }
    void setRemindersSeen(String json) { prefs.edit().putString(KEY_REMINDERS_SEEN, json).apply(); }

    /** Stored tidied up ("60, 30, 10"), whatever was typed. */
    void setReminders(String times, boolean chime) {
        prefs.edit().putString(KEY_REMINDER_TIMES, Agenda.formatLeads(Agenda.parseLeads(times)))
                .putBoolean(KEY_REMINDER_CHIME, chime).apply();
    }

    static final String DEFAULT_DIGEST_TIME = "20:30";

    boolean digestShown() { return prefs.getBoolean(KEY_DIGEST_SHOWN, true); }
    String digestTime() { return prefs.getString(KEY_DIGEST_TIME, DEFAULT_DIGEST_TIME); }
    long digestDay() { return prefs.getLong(KEY_DIGEST_DAY, 0); }
    void setDigestDay(long dayStart) { prefs.edit().putLong(KEY_DIGEST_DAY, dayStart).apply(); }

    /** A time that isn't "HH:mm" keeps the one saved. */
    void setDigest(boolean shown, String time) {
        SharedPreferences.Editor e = prefs.edit().putBoolean(KEY_DIGEST_SHOWN, shown);
        int m = Agenda.parseClock(time);
        if (m >= 0) e.putString(KEY_DIGEST_TIME, String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60));
        e.apply();
    }

    String calendarStatus() { return prefs.getString(KEY_CALENDAR_STATUS, "{}"); }
    void setCalendarStatus(String json) { prefs.edit().putString(KEY_CALENDAR_STATUS, json).apply(); }

    String bgWeather() { return prefs.getString(KEY_BG_WEATHER, DEFAULT_WEATHER); }
    String bgSun() { return prefs.getString(KEY_BG_SUN, DEFAULT_SUN); }
    String bgSeason() { return prefs.getString(KEY_BG_SEASON, DEFAULT_SEASON); }
    String bgHolidays() { return prefs.getString(KEY_BG_HOLIDAYS, Backgrounds.DEFAULT_HOLIDAYS); }

    String bgOverrideJson() { return prefs.getString(KEY_BG_OVERRIDE, "{}"); }

    Backgrounds.Forced bgOverride() {
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_BG_OVERRIDE, "{}"));
            return new Backgrounds.Forced(o.optString("focal"), o.optString("season"),
                    o.optString("time"), o.optString("condition"), o.optString("holiday"));
        } catch (JSONException e) {
            return Backgrounds.Forced.NONE;
        }
    }

    int scrimDay() { return prefs.getInt(KEY_SCRIM_DAY, DEFAULT_SCRIM_DAY); }
    int scrimNight() { return prefs.getInt(KEY_SCRIM_NIGHT, DEFAULT_SCRIM_NIGHT); }
    int scrimEnd() { return prefs.getInt(KEY_SCRIM_END, DEFAULT_SCRIM_END); }
    int contentWidth() { return prefs.getInt(KEY_CONTENT_WIDTH, DEFAULT_CONTENT_WIDTH); }

    String bgStatus() { return prefs.getString(KEY_BG_STATUS, "{}"); }
    String bgLast() { return prefs.getString(KEY_BG_LAST, ""); }
    String bgFocalDay() { return prefs.getString(KEY_BG_FOCAL_DAY, ""); }

    void setBgStatus(String json) { prefs.edit().putString(KEY_BG_STATUS, json).apply(); }
    void setBgLast(String path) { prefs.edit().putString(KEY_BG_LAST, path).apply(); }
    void setBgFocalDay(String v) { prefs.edit().putString(KEY_BG_FOCAL_DAY, v).apply(); }

    /** Background settings from the config page; each is optional. */
    void setBackground(String weather, String sun, String season, String holidays, String overrideJson) {
        SharedPreferences.Editor e = prefs.edit();
        if (weather != null) e.putString(KEY_BG_WEATHER, weather);
        if (sun != null) e.putString(KEY_BG_SUN, sun);
        if (season != null) e.putString(KEY_BG_SEASON, season);
        if (holidays != null) e.putString(KEY_BG_HOLIDAYS, holidays);
        if (overrideJson != null) e.putString(KEY_BG_OVERRIDE, overrideJson);
        e.apply();
    }

    void setPercent(String key, int value) {
        prefs.edit().putInt(key, Math.max(0, Math.min(100, value))).apply();
    }

    void setHold(long ms) { prefs.edit().putLong(KEY_HOLD_MS, ms).apply(); }
    void setHaUrl(String url) { prefs.edit().putString(KEY_HA_URL, url).apply(); }
    void setHaToken(String token) { prefs.edit().putString(KEY_HA_TOKEN, token).apply(); }
}
