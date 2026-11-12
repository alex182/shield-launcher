package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class BackupTest {

    private static Map<String, Object> prefs() {
        Map<String, Object> p = new HashMap<>();
        p.put(Store.KEY_PINNED, "org.jellyfin.androidtv,com.aspiro.tidal");
        p.put(Store.KEY_HIDDEN, new HashSet<>(Arrays.asList("com.nvidia.ota", "com.android.gallery3d")));
        p.put(Store.KEY_HOLD_MS, 2500L);
        p.put(Store.KEY_SLEEP_MINUTES, 60);
        p.put(Store.KEY_SLEEP_SHOWN, false);
        p.put(Store.KEY_AMBILIGHT, "{\"strips\":[]}");
        p.put(Store.KEY_HA_URL, "http://ha.lan:8123");
        // Never in a backup: the token, and what the TV writes for itself.
        p.put(Store.KEY_HA_TOKEN, "secret");
        p.put(Store.KEY_BG_STATUS, "{}");
        p.put(Store.KEY_ALERTS_SEEN, "{}");
        p.put(Store.KEY_DIGEST_DAY, 123L);
        p.put(Backup.KEY_UNDO, "{}");
        return p;
    }

    private static JSONObject roundTrip(Map<String, Object> p) throws JSONException {
        // Through text, as the file is.
        return new JSONObject(Backup.create(p, "Office", 1000).toString());
    }

    @Test
    public void backupHoldsTheSettingsButNotTheTokenOrStatus() throws JSONException {
        JSONObject b = roundTrip(prefs());
        assertEquals(Backup.APP, b.getString("app"));
        assertEquals("Office", b.getString("device"));
        JSONObject s = b.getJSONObject("settings");
        assertEquals("http://ha.lan:8123", s.getString(Store.KEY_HA_URL));
        assertEquals(2, s.getJSONArray(Store.KEY_HIDDEN).length());
        for (String k : new String[] {Store.KEY_HA_TOKEN, Store.KEY_BG_STATUS, Store.KEY_ALERTS_SEEN,
                Store.KEY_DIGEST_DAY, Backup.KEY_UNDO}) {
            assertFalse(k, s.has(k));
        }
    }

    @Test
    public void restoreGivesBackTheSameValuesAndTypes() throws JSONException {
        Map<String, Object> c = Backup.read(roundTrip(prefs()), Backup.groupIds());
        assertEquals("org.jellyfin.androidtv,com.aspiro.tidal", c.get(Store.KEY_PINNED));
        assertEquals(new HashSet<>(Arrays.asList("com.nvidia.ota", "com.android.gallery3d")), c.get(Store.KEY_HIDDEN));
        assertEquals(2500L, c.get(Store.KEY_HOLD_MS));        // a long, though JSON wrote a small number
        assertEquals(60, c.get(Store.KEY_SLEEP_MINUTES));     // an int
        assertEquals(false, c.get(Store.KEY_SLEEP_SHOWN));
        assertEquals("{\"strips\":[]}", c.get(Store.KEY_AMBILIGHT));
        assertFalse(c.containsKey(Store.KEY_HA_TOKEN));
    }

    @Test
    public void settingsAtTheirDefaultThereAreResetHere() throws JSONException {
        Map<String, Object> c = Backup.read(roundTrip(prefs()), Backup.groupIds());
        // Not set on the backed-up SHIELD: back to the default here.
        assertSame(Backup.REMOVE, c.get(Store.KEY_ACTIONS));
        assertSame(Backup.REMOVE, c.get(Store.KEY_RADAR_SOURCE));
    }

    @Test
    public void onlyThePickedPartsAreRestored() throws JSONException {
        Map<String, Object> c = Backup.read(roundTrip(prefs()), Collections.singletonList("ambilight"));
        assertEquals(Collections.singleton(Store.KEY_AMBILIGHT), c.keySet());
    }

    @Test
    public void wrongFilesAndValuesAreRefusedWhole() throws JSONException {
        expectRefused(new JSONObject().put("app", "something.else").put("format", 1)
                .put("settings", new JSONObject()), Backup.groupIds());
        expectRefused(new JSONObject().put("app", Backup.APP).put("format", 99)
                .put("settings", new JSONObject()), Backup.groupIds());
        JSONObject b = roundTrip(prefs());
        expectRefused(b, Collections.singletonList("nonsense"));
        b.getJSONObject("settings").put(Store.KEY_SLEEP_MINUTES, "sixty");
        expectRefused(b, Backup.groupIds());
        b.getJSONObject("settings").put(Store.KEY_SLEEP_MINUTES, 1.5);
        expectRefused(b, Backup.groupIds());
    }

    @Test
    public void everyKeyIsInOneGroupOnly() {
        Set<String> seen = new HashSet<>();
        for (Backup.Group g : Backup.GROUPS) {
            for (String k : g.keys.keySet()) assertTrue(k, seen.add(k));
        }
        assertFalse(seen.contains(Store.KEY_HA_TOKEN));
    }

    private static void expectRefused(JSONObject b, java.util.List<String> groups) {
        try {
            Backup.read(b, groups);
            fail("accepted " + b);
        } catch (JSONException expected) { }
    }
}
