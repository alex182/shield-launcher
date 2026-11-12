package dev.alex.minlauncher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backups of the settings, as a JSON file the config page downloads and can
 * restore, on this SHIELD or another:
 * <pre>
 * {app: "dev.alex.minlauncher", format: 1, device, createdAt,
 *  settings: {key: value, …}}
 * </pre>
 * Only what the user sets is in it, in {@link #GROUPS}; what the TV writes
 * for itself (statuses, alerts and reminders already shown, today's focal
 * element) is left out, and so is the Home Assistant token, which the page
 * never reads back. A restore takes only the groups picked, checks every
 * value's type, and resets a setting the backup doesn't have (it was at its
 * default there). The settings it replaces are kept for one undo.
 */
final class Backup {

    private Backup() { }

    static final String APP = "dev.alex.minlauncher";
    static final int FORMAT = 1;
    /** The settings before the last restore, as a backup; for undo. Never backed up itself. */
    static final String KEY_UNDO = "restore_undo";

    enum Type { STRING, BOOL, INT, LONG, SET }

    static final class Group {
        final String id, label;
        final Map<String, Type> keys = new LinkedHashMap<>();
        Group(String id, String label, Object... keyTypes) {
            this.id = id;
            this.label = label;
            for (int i = 0; i < keyTypes.length; i += 2) keys.put((String) keyTypes[i], (Type) keyTypes[i + 1]);
        }
    }

    static final List<Group> GROUPS = Arrays.asList(
            new Group("apps", "Pinned and hidden apps",
                    Store.KEY_PINNED, Type.STRING, Store.KEY_HIDDEN, Type.SET),
            new Group("remote", "Netflix button, quick actions and Sleep",
                    Store.KEY_SINGLE_PRESS, Type.STRING, Store.KEY_HOLD_MS, Type.LONG,
                    Store.KEY_ACTIONS, Type.STRING, Store.KEY_SLEEP_MINUTES, Type.INT,
                    Store.KEY_SLEEP_SHOWN, Type.BOOL),
            new Group("visualizer", "Music visualizer and liked presets",
                    Store.KEY_VISUALIZER_HOLD, Type.BOOL, Store.KEY_VISUALIZER, Type.STRING,
                    Store.KEY_VISUALIZER_LIKED, Type.STRING, Store.KEY_VISUALIZER_IMAGES, Type.BOOL),
            new Group("ambilight", "Ambilight (room, TV, strips and picture) and room lights",
                    Store.KEY_AMBILIGHT, Type.STRING, Store.KEY_MODE_LIGHTS, Type.STRING),
            new Group("calendar", "Cameras, calendar and reminders",
                    Store.KEY_CAMERAS, Type.STRING, Store.KEY_CALENDAR_SHOWN, Type.BOOL,
                    Store.KEY_CALENDARS, Type.STRING, Store.KEY_REMINDER_TIMES, Type.STRING,
                    Store.KEY_REMINDER_CHIME, Type.BOOL, Store.KEY_DIGEST_SHOWN, Type.BOOL,
                    Store.KEY_DIGEST_TIME, Type.STRING),
            new Group("weather", "Weather alerts and rain radar",
                    Store.KEY_ALERTS_SHOWN, Type.BOOL, Store.KEY_ALERTS_SEVERITY, Type.STRING,
                    Store.KEY_ALERTS_TONE_SECONDS, Type.INT, Store.KEY_ALERTS_POINT, Type.STRING,
                    Store.KEY_RADAR_SHOWN, Type.BOOL, Store.KEY_RADAR_SOURCE, Type.STRING,
                    Store.KEY_RADAR_LIGHTNING_DISTANCE, Type.STRING,
                    Store.KEY_RADAR_LIGHTNING_STRIKE, Type.STRING,
                    Store.KEY_RADAR_LIGHTNING_MILES, Type.INT),
            new Group("background", "Background and layout",
                    Store.KEY_BG_WEATHER, Type.STRING, Store.KEY_BG_SUN, Type.STRING,
                    Store.KEY_BG_SEASON, Type.STRING, Store.KEY_BG_HOLIDAYS, Type.STRING,
                    Store.KEY_BG_OVERRIDE, Type.STRING, Store.KEY_SCRIM_DAY, Type.INT,
                    Store.KEY_SCRIM_NIGHT, Type.INT, Store.KEY_SCRIM_END, Type.INT,
                    Store.KEY_CONTENT_WIDTH, Type.INT),
            new Group("homeassistant", "Home Assistant address (not the token)",
                    Store.KEY_HA_URL, Type.STRING));

    /** In a restore's result: the setting goes back to its default. */
    static final Object REMOVE = new Object();

    /** A backup of every group's settings that are set, from SharedPreferences.getAll(). */
    static JSONObject create(Map<String, ?> all, String device, long now) throws JSONException {
        JSONObject settings = new JSONObject();
        for (Group g : GROUPS) {
            for (Map.Entry<String, Type> k : g.keys.entrySet()) {
                Object v = all.get(k.getKey());
                if (v == null || !matches(v, k.getValue())) continue;
                settings.put(k.getKey(), v instanceof Set ? new JSONArray((Set<?>) v) : v);
            }
        }
        return new JSONObject().put("app", APP).put("format", FORMAT).put("device", device)
                .put("createdAt", now).put("settings", settings);
    }

    /**
     * What restoring these groups of a backup changes: each of their settings'
     * new value, or {@link #REMOVE} where the backup has none. Throws, before
     * anything is written, on a file that isn't a backup, an unknown group or
     * a value of the wrong type.
     */
    static Map<String, Object> read(JSONObject backup, Collection<String> groups) throws JSONException {
        if (!APP.equals(backup.optString("app"))) throw new JSONException("Not a launcher backup");
        if (backup.optInt("format", 0) < 1 || backup.optInt("format") > FORMAT) {
            throw new JSONException("Backup format " + backup.opt("format") + " is newer than this launcher");
        }
        JSONObject settings = backup.optJSONObject("settings");
        if (settings == null) throw new JSONException("The backup has no settings");
        Set<String> wanted = new HashSet<>(groups);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Group g : GROUPS) {
            if (!wanted.remove(g.id)) continue;
            for (Map.Entry<String, Type> k : g.keys.entrySet()) {
                String key = k.getKey();
                out.put(key, settings.has(key) ? value(key, settings.get(key), k.getValue()) : REMOVE);
            }
        }
        if (!wanted.isEmpty()) throw new JSONException("Unknown part: " + wanted.iterator().next());
        return out;
    }

    /** Writes {@link #read}'s changes, all at once. */
    static void apply(SharedPreferences prefs, Map<String, Object> changes) {
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, Object> c : changes.entrySet()) {
            Object v = c.getValue();
            String k = c.getKey();
            if (v == REMOVE) e.remove(k);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> set = (Set<String>) v;
                e.putStringSet(k, set);
            } else e.putString(k, (String) v);
        }
        e.commit();
    }

    static List<String> groupIds() {
        List<String> out = new ArrayList<>();
        for (Group g : GROUPS) out.add(g.id);
        return out;
    }

    /** For the page: [{id, label, keys: [...]}]. */
    static JSONArray groupsJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (Group g : GROUPS) {
            a.put(new JSONObject().put("id", g.id).put("label", g.label)
                    .put("keys", new JSONArray(g.keys.keySet())));
        }
        return a;
    }

    private static boolean matches(Object v, Type t) {
        switch (t) {
            case BOOL: return v instanceof Boolean;
            case INT: return v instanceof Integer;
            case LONG: return v instanceof Long;
            case SET: return v instanceof Set;
            default: return v instanceof String;
        }
    }

    private static Object value(String key, Object v, Type t) throws JSONException {
        switch (t) {
            case BOOL:
                if (v instanceof Boolean) return v;
                break;
            case INT:
            case LONG:
                if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    if (d == Math.rint(d)) {
                        if (t == Type.LONG) return ((Number) v).longValue();
                        if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return ((Number) v).intValue();
                    }
                }
                break;
            case SET:
                if (v instanceof JSONArray) {
                    JSONArray a = (JSONArray) v;
                    Set<String> s = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) {
                        if (!(a.get(i) instanceof String)) throw new JSONException(key + ": not a list of names");
                        s.add(a.getString(i));
                    }
                    return s;
                }
                break;
            default:
                if (v instanceof String) return v;
        }
        throw new JSONException(key + ": wrong kind of value");
    }
}
