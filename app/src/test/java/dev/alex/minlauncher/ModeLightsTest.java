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
}
