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
    static final String DEFAULT_SEASON = "sensor.season";
    static final int DEFAULT_SCRIM_DAY = 75;
    static final int DEFAULT_SCRIM_NIGHT = 65;
    static final int DEFAULT_SCRIM_END = 65;
    static final int DEFAULT_CONTENT_WIDTH = 65;

    static final long DEFAULT_HOLD_MS = 3000;
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

    String singlePress() { return prefs.getString(KEY_SINGLE_PRESS, DEFAULT_SINGLE_PRESS); }
    void setSinglePress(String pkg) { prefs.edit().putString(KEY_SINGLE_PRESS, pkg).apply(); }

    /** Cameras to show, in order; empty means every camera Home Assistant has. */
    List<String> cameras() {
        List<String> out = new ArrayList<>();
        for (String p : prefs.getString(KEY_CAMERAS, "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    void setCameras(List<String> ids) {
        prefs.edit().putString(KEY_CAMERAS, TextUtils.join(",", ids)).apply();
    }

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
