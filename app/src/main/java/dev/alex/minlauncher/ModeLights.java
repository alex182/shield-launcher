package dev.alex.minlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure logic behind {@link ModeLightsController}: the room's lights (Zigbee
 * or any other Home Assistant light or switch) turned off, on or dimmed
 * while the visualizer or the ambilight runs, or while chosen apps play, and
 * put back afterwards. No Android types, so it runs in unit tests.
 *
 * Stored ({@link Store#KEY_MODE_LIGHTS}) as
 * <pre>
 * {visualizer: {lights: [entity ids], enter: "off"|"on"|"dim"|"none", level: 1–100 (% for "dim"),
 *               transition: seconds, leave: "restore"|"on"|"off"|"none"},
 *  ambilight: {…},
 *  playback: {…, apps: [package names]}}
 * </pre>
 */
final class ModeLights {

    private ModeLights() { }

    static final String VISUALIZER = "visualizer", AMBILIGHT = "ambilight", PLAYBACK = "playback";
    static final List<String> MODES = Arrays.asList(VISUALIZER, AMBILIGHT, PLAYBACK);
    /** What happens to the lights as a mode starts; "dim" sets lights to {@code level}% and turns switches off. */
    static final List<String> ENTER = Arrays.asList("off", "on", "dim", "none");
    /** And as it ends; "restore" brings back each light's state from just before. */
    static final List<String> LEAVE = Arrays.asList("restore", "on", "off", "none");
    static final int DEFAULT_LEVEL = 20, DEFAULT_TRANSITION = 2, MAX_TRANSITION = 30;
    /** The video apps watched by default. */
    static final String DEFAULT_APP = "org.jellyfin.androidtv";

    /** Every mode filled in; only light and switch entities are kept, once each. */
    static JSONObject normalize(JSONObject in) {
        if (in == null) in = new JSONObject();
        try {
            JSONObject out = new JSONObject();
            for (String mode : MODES) {
                JSONObject m = in.optJSONObject(mode);
                if (m == null) m = new JSONObject();
                Set<String> ids = new LinkedHashSet<>();
                JSONArray a = m.optJSONArray("lights");
                for (int i = 0; a != null && i < a.length(); i++) {
                    String id = a.optString(i, "").trim();
                    if (id.startsWith("light.") || id.startsWith("switch.")) ids.add(id);
                }
                String enter = m.optString("enter", "off"), leave = m.optString("leave", "restore");
                JSONObject o = new JSONObject()
                        .put("lights", new JSONArray(ids))
                        .put("enter", ENTER.contains(enter) ? enter : "off")
                        .put("level", Math.max(1, Math.min(100, m.optInt("level", DEFAULT_LEVEL))))
                        .put("transition", Math.max(0, Math.min(MAX_TRANSITION, m.optInt("transition", DEFAULT_TRANSITION))))
                        .put("leave", LEAVE.contains(leave) ? leave : "restore");
                if (mode.equals(PLAYBACK)) {
                    Set<String> apps = new LinkedHashSet<>();
                    JSONArray ap = m.optJSONArray("apps");
                    if (ap == null) apps.add(DEFAULT_APP);
                    for (int i = 0; ap != null && i < ap.length(); i++) {
                        String pkg = ap.optString(i, "").trim();
                        if (!pkg.isEmpty()) apps.add(pkg);
                    }
                    o.put("apps", new JSONArray(apps));
                }
                out.put(mode, o);
            }
            return out;
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only put() of strings and arrays above
        }
    }

    static List<String> lights(JSONObject mode) { return strings(mode, "lights"); }

    static List<String> apps(JSONObject mode) { return strings(mode, "apps"); }

    private static List<String> strings(JSONObject o, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = o == null ? null : o.optJSONArray(key);
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** One Home Assistant service call: "domain.service" and its JSON data. */
    static final class Call {
        final String service, data;
        Call(String service, JSONObject data) { this.service = service; this.data = data.toString(); }
        @Override public String toString() { return service + " " + data; }
    }

    /**
     * The calls that switch these entities: "off", "on", "dim" (to
     * {@code level}%), or "restore" (each one's scene, {@link #sceneId}).
     * Lights fade over {@code transition} seconds; switches can't fade or
     * dim, so they get their own calls (and "dim" turns them off).
     */
    static List<Call> calls(String action, List<String> entities, int level, int transition) {
        List<Call> out = new ArrayList<>();
        List<String> lights = new ArrayList<>(), switches = new ArrayList<>();
        for (String id : entities) (id.startsWith("light.") ? lights : switches).add(id);
        try {
            if (action.equals("restore")) {
                List<String> scenes = new ArrayList<>();
                for (String id : entities) scenes.add("scene." + sceneId(id));
                if (!scenes.isEmpty()) out.add(new Call("scene.turn_on", fade(ids(scenes), transition)));
                return out;
            }
            boolean on = action.equals("on"), dim = action.equals("dim");
            if (!on && !dim && !action.equals("off")) return out;
            if (!lights.isEmpty()) {
                JSONObject d = fade(ids(lights), transition);
                if (dim) d.put("brightness_pct", level);
                out.add(new Call(on || dim ? "light.turn_on" : "light.turn_off", d));
            }
            if (!switches.isEmpty()) out.add(new Call(on ? "switch.turn_on" : "switch.turn_off", ids(switches)));
            return out;
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only put() of strings and ints
        }
    }

    private static JSONObject ids(List<String> ids) throws JSONException {
        return new JSONObject().put("entity_id", ids.size() == 1 ? ids.get(0) : new JSONArray(ids));
    }

    private static JSONObject fade(JSONObject data, int transition) throws JSONException {
        return transition > 0 ? data.put("transition", transition) : data;
    }

    /**
     * The scene a light's state is kept in while a mode holds it, one per
     * light, so a light can be given back on its own: "light.tv_lamp" is
     * kept as scene.shield_launcher_light_tv_lamp.
     */
    static String sceneId(String entityId) {
        return "shield_launcher_" + entityId.replace('.', '_');
    }

    /**
     * Which mode holds which light. The visualizer and the ambilight can run
     * at once and share lights: a light's state is kept only when the first
     * mode takes it (later ones would keep it already switched), and given
     * back only when the last one lets it go.
     */
    static final class Tracker {
        private final Map<String, Set<String>> holders = new HashMap<>();   // light → modes
        private final Map<String, List<String>> held = new HashMap<>();      // mode → lights

        boolean isActive(String mode) { return held.containsKey(mode); }

        /** The mode takes these lights; returns the ones no other mode held, whose state to keep. */
        List<String> enter(String mode, List<String> lights) {
            if (held.containsKey(mode)) return new ArrayList<>();
            held.put(mode, new ArrayList<>(lights));
            List<String> fresh = new ArrayList<>();
            for (String id : lights) {
                Set<String> h = holders.get(id);
                if (h == null) holders.put(id, h = new HashSet<>());
                if (h.isEmpty()) fresh.add(id);
                h.add(mode);
            }
            return fresh;
        }

        /** The mode lets its lights go; returns the ones no other mode still holds, to give back. */
        List<String> leave(String mode) {
            List<String> lights = held.remove(mode), released = new ArrayList<>();
            if (lights == null) return released;
            for (String id : lights) {
                Set<String> h = holders.get(id);
                if (h == null) continue;
                h.remove(mode);
                if (h.isEmpty()) { holders.remove(id); released.add(id); }
            }
            return released;
        }
    }
}
