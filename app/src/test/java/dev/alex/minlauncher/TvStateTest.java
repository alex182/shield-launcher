package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class TvStateTest {

    private static final String JF = "org.jellyfin.androidtv", TIDAL = "com.aspiro.tidal";

    private static TvState st(boolean screen, boolean viz, String app, boolean home, String playing) {
        return new TvState(screen, viz, false, app, app, home, playing);
    }

    @Test
    public void theStateIsTheMostTellingOfWhatsGoingOn() {
        assertEquals("off", st(false, true, JF, false, JF).state());
        assertEquals("visualizer", st(true, true, TIDAL, false, TIDAL).state());
        assertEquals("playing", st(true, false, JF, false, JF).state());
        assertEquals("playing", st(true, false, "dev.alex.minlauncher", true, TIDAL).state());
        assertEquals("home", st(true, false, "dev.alex.minlauncher", true, "").state());
        assertEquals("app", st(true, false, JF, false, "").state());
    }

    @Test
    public void attributesNameTheAppAndWhatPlays() throws JSONException {
        JSONObject a = new TvState(true, false, true, JF, "Jellyfin", false, "?").attributes("Office");
        assertEquals("Office", a.getString("friendly_name"));
        assertEquals(JF, a.getString("app"));
        assertEquals("Jellyfin", a.getString("app_name"));
        assertTrue(a.getBoolean("playing"));
        assertEquals("", a.getString("playing_app"));
        assertTrue(a.getBoolean("ambilight"));
        assertEquals("on", a.getString("screen"));
        JSONObject home = st(true, false, "dev.alex.minlauncher", true, "").attributes("Office");
        assertEquals("", home.getString("app"));
        assertEquals("Home", home.getString("app_name"));
    }

    @Test
    public void onlyTheWatchedAppsCount() {
        List<String> apps = Collections.singletonList(JF);
        AudioSessions.Playing jf = new AudioSessions.Playing(9, 100, JF);
        AudioSessions.Playing tidal = new AudioSessions.Playing(10, 101, TIDAL);
        AudioSessions.Playing unnamed = new AudioSessions.Playing(11, 102, "");
        assertTrue(TvState.watched(Arrays.asList(tidal, jf), apps, TIDAL));
        assertFalse(TvState.watched(Collections.singletonList(tidal), apps, JF));
        assertTrue(TvState.watched(Collections.singletonList(unnamed), apps, JF));
        assertFalse(TvState.watched(Collections.singletonList(unnamed), apps, TIDAL));
        assertFalse(TvState.watched(Collections.emptyList(), apps, JF));
        assertEquals(TIDAL, TvState.playingApp(Arrays.asList(unnamed, tidal)));
        assertEquals("?", TvState.playingApp(Collections.singletonList(unnamed)));
        assertEquals("", TvState.playingApp(Collections.emptyList()));
    }

    @Test
    public void sensorNamesComeFromTheDeviceName() {
        assertEquals("sensor.office_shield", TvState.defaultEntity("Office SHIELD"));
        assertEquals("sensor.shield", TvState.defaultEntity(null));
        assertEquals("sensor.living_room_tv", TvState.defaultEntity("  Living-room TV!"));
        assertTrue(TvState.validEntity("sensor.office_shield"));
        assertFalse(TvState.validEntity("light.office"));
        assertFalse(TvState.validEntity("sensor.Office"));
    }
}
