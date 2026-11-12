package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class ModeLightsTest {

    @Test
    public void normalizeFillsEveryModeAndKeepsOnlyLightsAndSwitches() throws JSONException {
        JSONObject n = ModeLights.normalize(new JSONObject().put("visualizer", new JSONObject()
                .put("lights", new JSONArray().put("light.lamp").put("sensor.x").put("switch.fan").put("light.lamp"))
                .put("enter", "on").put("leave", "bogus")));
        JSONObject v = n.getJSONObject("visualizer");
        assertEquals(Arrays.asList("light.lamp", "switch.fan"), ModeLights.lights(v));
        assertEquals("on", v.getString("enter"));
        assertEquals("restore", v.getString("leave"));
        JSONObject a = n.getJSONObject("ambilight");
        assertTrue(ModeLights.lights(a).isEmpty());
        assertEquals("off", a.getString("enter"));
        assertEquals(n.toString(), ModeLights.normalize(null).put("visualizer", v).toString());
    }

    @Test
    public void sceneIdsAreOnePerLight() {
        assertEquals("shield_launcher_light_tv_lamp", ModeLights.sceneId("light.tv_lamp"));
    }

    @Test
    public void sharedLightsAreKeptByTheFirstModeAndGivenBackByTheLast() {
        ModeLights.Tracker t = new ModeLights.Tracker();
        List<String> viz = Arrays.asList("light.a", "light.b"), amb = Arrays.asList("light.b", "light.c");

        assertEquals(viz, t.enter(ModeLights.VISUALIZER, viz));
        // b is switched already: its state from before the visualizer is the one to keep.
        assertEquals(Collections.singletonList("light.c"), t.enter(ModeLights.AMBILIGHT, amb));

        assertEquals(Collections.singletonList("light.a"), t.leave(ModeLights.VISUALIZER));
        assertFalse(t.isActive(ModeLights.VISUALIZER));
        assertTrue(t.isActive(ModeLights.AMBILIGHT));
        assertEquals(amb, t.leave(ModeLights.AMBILIGHT));
    }

    @Test
    public void repeatedStartsAndEndsDoNothing() {
        ModeLights.Tracker t = new ModeLights.Tracker();
        List<String> l = Collections.singletonList("light.a");
        assertEquals(l, t.enter(ModeLights.AMBILIGHT, l));
        assertTrue(t.enter(ModeLights.AMBILIGHT, l).isEmpty());
        assertEquals(l, t.leave(ModeLights.AMBILIGHT));
        assertTrue(t.leave(ModeLights.AMBILIGHT).isEmpty());
        assertEquals(l, t.enter(ModeLights.AMBILIGHT, l));
    }

    @Test
    public void playbackWatchesJellyfinByDefaultAndDimDefaultsAreFilledIn() throws JSONException {
        JSONObject n = ModeLights.normalize(null);
        assertEquals(Collections.singletonList(ModeLights.DEFAULT_APP), ModeLights.apps(n.getJSONObject("playback")));
        assertFalse(n.getJSONObject("visualizer").has("apps"));
        assertEquals(20, n.getJSONObject("ambilight").getInt("level"));
        assertEquals(2, n.getJSONObject("ambilight").getInt("transition"));
        JSONObject odd = ModeLights.normalize(new JSONObject().put("playback", new JSONObject()
                .put("apps", new JSONArray()).put("level", 500).put("transition", -3).put("enter", "dim")));
        JSONObject p = odd.getJSONObject("playback");
        assertTrue(ModeLights.apps(p).isEmpty());   // unticking every app is kept
        assertEquals(100, p.getInt("level"));
        assertEquals(0, p.getInt("transition"));
        assertEquals("dim", p.getString("enter"));
    }

    @Test
    public void callsSplitLightsFromSwitchesAndFade() throws JSONException {
        List<String> both = Arrays.asList("light.a", "light.b", "switch.fan");
        assertEquals("[light.turn_on {\"entity_id\":[\"light.a\",\"light.b\"],\"transition\":2,\"brightness_pct\":15},"
                        + " switch.turn_off {\"entity_id\":\"switch.fan\"}]",
                sorted(ModeLights.calls("dim", both, 15, 2)));
        assertEquals("[light.turn_off {\"entity_id\":\"light.a\"}]",
                sorted(ModeLights.calls("off", Collections.singletonList("light.a"), 20, 0)));
        assertEquals("[switch.turn_on {\"entity_id\":\"switch.fan\"}]",
                sorted(ModeLights.calls("on", Collections.singletonList("switch.fan"), 20, 3)));
        assertEquals("[scene.turn_on {\"entity_id\":[\"scene.shield_launcher_light_a\",\"scene.shield_launcher_switch_fan\"],\"transition\":3}]",
                sorted(ModeLights.calls("restore", Arrays.asList("light.a", "switch.fan"), 20, 3)));
        assertTrue(ModeLights.calls("none", both, 20, 2).isEmpty());
        assertTrue(ModeLights.calls("off", Collections.emptyList(), 20, 2).isEmpty());
    }

    /** JSON key order isn't fixed: compare each call's data as a sorted-key object. */
    private static String sorted(List<ModeLights.Call> calls) throws JSONException {
        StringBuilder b = new StringBuilder("[");
        for (ModeLights.Call c : calls) {
            if (b.length() > 1) b.append(", ");
            JSONObject o = new JSONObject(c.data);
            StringBuilder d = new StringBuilder("{");
            for (String k : new String[] {"entity_id", "transition", "brightness_pct"}) {
                if (!o.has(k)) continue;
                if (d.length() > 1) d.append(',');
                d.append('"').append(k).append("\":").append(o.get(k) instanceof String
                        ? "\"" + o.get(k) + "\"" : o.get(k).toString());
            }
            b.append(c.service).append(' ').append(d).append('}');
        }
        return b.append(']').toString();
    }
}
