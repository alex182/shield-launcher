package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class LightsTest {

    @Test
    public void onlyAllLightActionsGetThePanel() {
        assertTrue(Lights.allLights(Arrays.asList("light.kitchen", "light.livingroom_group")));
        assertFalse(Lights.allLights(Arrays.asList("light.kitchen", "switch.fan")));
        assertFalse(Lights.allLights(Collections.singletonList("automation.movie")));
        assertFalse(Lights.allLights(Collections.emptyList()));
    }

    @Test
    public void colorModesDecideTheControls() {
        Lights.Support onoff = Lights.support(Collections.singletonList("onoff"));
        assertFalse(onoff.brightness || onoff.temp || onoff.color);

        Lights.Support dim = Lights.support(Collections.singletonList("brightness"));
        assertTrue(dim.brightness);
        assertFalse(dim.temp || dim.color);

        Lights.Support tunable = Lights.support(Collections.singletonList("color_temp"));
        assertTrue(tunable.brightness && tunable.temp);
        assertFalse(tunable.color);

        // A group of a white bulb and a colour bulb offers both.
        Lights.Support group = Lights.support(Arrays.asList("color_temp", "xy"));
        assertTrue(group.brightness && group.temp && group.color);
    }

    @Test
    public void unknownModesOfferEverything() {
        Lights.Support s = Lights.support(Collections.emptyList());
        assertTrue(s.brightness && s.temp && s.color);
    }

    @Test
    public void brightnessAsPercent() {
        assertEquals(0, Lights.percent(false, 255));
        assertEquals(100, Lights.percent(true, 255));
        assertEquals(50, Lights.percent(true, 128));
        assertEquals(1, Lights.percent(true, 1));   // on is never shown as off
    }

    @Test
    public void stepsStayInRange() {
        assertEquals(0, Lights.step(3, -5));
        assertEquals(100, Lights.step(98, 5));
        assertEquals(55, Lights.step(50, 5));
    }
}
