package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import dev.alex.minlauncher.Alerts.Alert;

public class AlertsTest {

    private static Alert alert(String id, String severity) {
        return new Alert(id, "Event " + id, severity, "", "", "Alert", -1);
    }

    @Test public void severityMinimum() {
        assertTrue(Alerts.meets("Extreme", "Severe"));
        assertTrue(Alerts.meets("Severe", "Severe"));
        assertFalse(Alerts.meets("Moderate", "Severe"));
        assertTrue(Alerts.meets("Minor", "Minor"));
        assertFalse("unknown severity only passes Minor", Alerts.meets("Unknown", "Minor"));
        assertFalse("unknown minimum means Severe", Alerts.meets("Moderate", "bogus"));
        assertTrue(Alerts.meets("Severe", "bogus"));
    }

    @Test public void onlyRealAlertsCount() {
        assertTrue(Alerts.counts("Actual", "Alert", "Severe", "Severe"));
        assertTrue(Alerts.counts("Actual", "Update", "Severe", "Severe"));
        assertFalse(Alerts.counts("Actual", "Cancel", "Severe", "Severe"));
        assertFalse(Alerts.counts("Test", "Alert", "Extreme", "Severe"));
        assertFalse(Alerts.counts("Exercise", "Alert", "Extreme", "Severe"));
        assertFalse(Alerts.counts("Actual", "Alert", "Moderate", "Severe"));
    }

    @Test public void nextIsMostSevereUnseen() {
        Alert severe = alert("a", "Severe"), extreme = alert("b", "Extreme"), other = alert("c", "Severe");
        assertEquals("b", Alerts.next(Arrays.asList(severe, extreme, other), Collections.emptySet()).id);
        assertEquals("a", Alerts.next(Arrays.asList(severe, extreme, other), new HashSet<>(Arrays.asList("b"))).id);
        assertNull(Alerts.next(Arrays.asList(severe, extreme), new HashSet<>(Arrays.asList("a", "b"))));
        assertNull(Alerts.next(Collections.emptyList(), Collections.emptySet()));
    }

    @Test public void updatesComeWithoutTone() {
        assertTrue(new Alert("u", "", "Severe", "", "", "Update", -1).isUpdate());
        assertFalse(alert("a", "Severe").isUpdate());
    }

    @Test public void pruneDropsEnded() {
        Map<String, Long> seen = new HashMap<>();
        seen.put("old", 1000L);
        seen.put("live", 5000L);
        Alerts.prune(seen, 2000);
        assertEquals(Collections.singleton("live"), seen.keySet());
    }

    @Test public void crawlIsOneLine() {
        assertEquals("* WHAT...Tornado. * WHERE...Here.   TAKE COVER NOW!",
                Alerts.crawlText("* WHAT...Tornado.\n\n* WHERE...Here.\n", "TAKE COVER\nNOW!"));
        assertEquals("Only this.", Alerts.crawlText("Only this.", null));
        assertEquals("Just do it.", Alerts.crawlText("  ", "Just do it."));
    }

    @Test public void attentionSignalIsBothTones() {
        int rate = 44_100;
        short[] pcm = Alerts.attentionSignal(rate, 2);
        assertEquals(2 * rate, pcm.length);
        assertEquals("fades in from silence", 0, pcm[0]);
        assertEquals("fades out to silence", 0, pcm[pcm.length - 1]);
        // Correlate with each tone: both present, a neighbouring frequency not.
        assertTrue(power(pcm, rate, Alerts.TONE_LOW_HZ) > 100 * power(pcm, rate, 700));
        assertTrue(power(pcm, rate, Alerts.TONE_HIGH_HZ) > 100 * power(pcm, rate, 700));
        int peak = 0;
        for (short s : pcm) peak = Math.max(peak, Math.abs(s));
        assertTrue("loud but not clipped", peak > 20_000 && peak <= Short.MAX_VALUE);
    }

    private static double power(short[] pcm, int rate, double hz) {
        double re = 0, im = 0;
        for (int i = 0; i < pcm.length; i++) {
            double a = 2 * Math.PI * hz * i / rate;
            re += pcm[i] * Math.cos(a);
            im += pcm[i] * Math.sin(a);
        }
        return re * re + im * im;
    }
}
