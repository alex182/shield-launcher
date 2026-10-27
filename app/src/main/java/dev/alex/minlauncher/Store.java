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

    void setHold(long ms) { prefs.edit().putLong(KEY_HOLD_MS, ms).apply(); }
    void setHaUrl(String url) { prefs.edit().putString(KEY_HA_URL, url).apply(); }
    void setHaToken(String token) { prefs.edit().putString(KEY_HA_TOKEN, token).apply(); }
}
