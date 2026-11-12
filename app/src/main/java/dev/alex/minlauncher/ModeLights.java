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
 * or any other Home Assistant light or switch) turned off or on while the
 * visualizer or the ambilight runs, and put back afterwards. No Android
 * types, so it runs in unit tests.
 *
 * Stored ({@link Store#KEY_MODE_LIGHTS}) as
 * <pre>
 * {visualizer: {lights: [entity ids], enter: "off"|"on"|"none", leave: "restore"|"on"|"off"|"none"},
 *  ambilight: {…}}
 * </pre>
 */
final class ModeLights {

    private ModeLights() { }

    static final String VISUALIZER = "visualizer", AMBILIGHT = "ambilight";
    static final List<String> MODES = Arrays.asList(VISUALIZER, AMBILIGHT);
    /** What happens to the lights as a mode starts. */
    static final List<String> ENTER = Arrays.asList("off", "on", "none");
    /** And as it ends; "restore" brings back each light's state from just before. */
    static final List<String> LEAVE = Arrays.asList("restore", "on", "off", "none");

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
                out.put(mode, new JSONObject()
                        .put("lights", new JSONArray(ids))
                        .put("enter", ENTER.contains(enter) ? enter : "off")
                        .put("leave", LEAVE.contains(leave) ? leave : "restore"));
            }
            return out;
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only put() of strings and arrays above
        }
    }

    static List<String> lights(JSONObject mode) {
        List<String> out = new ArrayList<>();
        JSONArray a = mode == null ? null : mode.optJSONArray("lights");
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
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
