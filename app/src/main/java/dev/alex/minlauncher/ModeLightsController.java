package dev.alex.minlauncher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns the room's lights off (or on) as the visualizer or the ambilight
 * starts, and back as it ends ({@link ModeLights} has the settings and the
 * bookkeeping). "Put back as they were" snapshots each light into a Home
 * Assistant scene (scene.create) first and turns that scene on afterwards,
 * so brightness and colour come back too, and a light that was off stays off.
 *
 * Both modes call {@link #set} as often as they like; only changes act. The
 * Home Assistant calls run in order on one worker thread.
 */
final class ModeLightsController {

    private static final String TAG = "ModeLights";
    private static ModeLightsController instance;

    private final Store store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Worker thread only.
    private final ModeLights.Tracker tracker = new ModeLights.Tracker();
    /** Each running mode's settings as it started, so a save meanwhile doesn't change how it ends. */
    private final Map<String, JSONObject> entered = new HashMap<>();

    private ModeLightsController(Context c) { store = new Store(c); }

    static synchronized ModeLightsController get(Context c) {
        if (instance == null) instance = new ModeLightsController(c.getApplicationContext());
        return instance;
    }

    /** The mode ({@link ModeLights#VISUALIZER}, {@link ModeLights#AMBILIGHT}) is running or not. */
    void set(String mode, boolean on) {
        worker.execute(() -> {
            if (on) enter(mode);
            else leave(mode);
        });
    }

    private void enter(String mode) {
        if (tracker.isActive(mode)) return;
        JSONObject cfg = store.modeLights().optJSONObject(mode);
        List<String> lights = ModeLights.lights(cfg);
        List<String> fresh = tracker.enter(mode, lights);
        entered.put(mode, cfg);
        if (lights.isEmpty()) return;
        try {
            // Kept before anything changes; with no snapshot, the lights are left alone.
            for (String id : fresh) {
                call("scene.create", new JSONObject().put("scene_id", ModeLights.sceneId(id))
                        .put("snapshot_entities", new JSONArray().put(id)));
            }
            String action = cfg.optString("enter");
            if (!action.equals("none")) {
                call("homeassistant.turn_" + action, new JSONObject().put("entity_id", new JSONArray(lights)));
            }
            status(mode, action.equals("none") ? "Started: lights left as they were"
                    : "Started: turned " + action + " " + count(lights.size()), "");
        } catch (IOException | JSONException e) {
            Log.w(TAG, mode + " start: " + e.getMessage());
            status(mode, "Started", e.getMessage());
        }
    }

    private void leave(String mode) {
        if (!tracker.isActive(mode)) return;
        List<String> released = tracker.leave(mode);
        JSONObject cfg = entered.remove(mode);
        String action = cfg == null ? "none" : cfg.optString("leave");
        if (released.isEmpty() || action.equals("none")) {
            if (!ModeLights.lights(cfg).isEmpty()) {
                status(mode, action.equals("none") ? "Ended: lights left as they were"
                        : "Ended: lights still held by the other mode", "");
            }
            return;
        }
        try {
            if (action.equals("restore")) {
                List<String> scenes = new ArrayList<>();
                for (String id : released) scenes.add("scene." + ModeLights.sceneId(id));
                call("scene.turn_on", new JSONObject().put("entity_id", new JSONArray(scenes)));
                status(mode, "Ended: put back " + count(released.size()), "");
            } else {
                call("homeassistant.turn_" + action, new JSONObject().put("entity_id", new JSONArray(released)));
                status(mode, "Ended: turned " + action + " " + count(released.size()), "");
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, mode + " end: " + e.getMessage());
            status(mode, "Ended", e.getMessage());
        }
    }

    private void call(String service, JSONObject data) throws IOException {
        HomeAssistant.call(store.haUrl(), store.haToken(), new Store.Action("", "", service, data.toString()));
    }

    private static String count(int n) { return n + (n == 1 ? " light" : " lights"); }

    private void status(String mode, String text, String error) {
        try {
            store.setModeLightsStatus(mode, new JSONObject().put("at", System.currentTimeMillis())
                    .put("text", text).put("error", error == null ? "" : error));
        } catch (JSONException ignored) { }
    }
}
