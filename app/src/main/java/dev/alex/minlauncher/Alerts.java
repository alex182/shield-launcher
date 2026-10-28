package dev.alex.minlauncher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure part of the NWS alert banner ({@link AlertBanner}): which alerts
 * count, which one to show next, and the EAS attention tone. No Android here,
 * so it runs in the JVM unit tests.
 */
final class Alerts {

    /** CAP severities, most severe first, as api.weather.gov writes them. */
    static final List<String> SEVERITIES = Arrays.asList("Extreme", "Severe", "Moderate", "Minor");
    /** Warnings such as tornado, severe thunderstorm and flash flood; not advisories. */
    static final String DEFAULT_SEVERITY = "Severe";

    /** The EAS attention signal is these two sine waves together (47 CFR Part 11). */
    static final double TONE_LOW_HZ = 853, TONE_HIGH_HZ = 960;
    static final int DEFAULT_TONE_SECONDS = 20;
    static final int MAX_TONE_SECONDS = 25;

    private Alerts() { }

    /** One active alert, from a feature's properties. {@code endsMs} is -1 when the NWS gives none. */
    static final class Alert {
        final String id, event, severity, headline, text, messageType;
        final long endsMs;
        Alert(String id, String event, String severity, String headline, String text, String messageType,
                long endsMs) {
            this.id = id; this.event = event; this.severity = severity; this.headline = headline;
            this.text = text; this.messageType = messageType; this.endsMs = endsMs;
        }
        /** An update to an alert already out (extended, re-worded): banner, but no tone. */
        boolean isUpdate() { return "Update".equals(messageType); }
        @Override public String toString() { return event + " (" + severity + ", " + messageType + ") " + id; }
    }

    /** Unknown severities rank after Minor. */
    static int rank(String severity) {
        int i = SEVERITIES.indexOf(severity);
        return i < 0 ? SEVERITIES.size() : i;
    }

    /** At least as severe as {@code minimum}; an unknown minimum means the default. */
    static boolean meets(String severity, String minimum) {
        int min = SEVERITIES.contains(minimum) ? rank(minimum) : rank(DEFAULT_SEVERITY);
        return rank(severity) <= min;
    }

    /** Whether the NWS feature is a real alert worth a banner (not a test, exercise or cancellation). */
    static boolean counts(String status, String messageType, String severity, String minimum) {
        return "Actual".equals(status) && !"Cancel".equals(messageType) && meets(severity, minimum);
    }

    /** The most severe alert not already shown; null if there is none. Ties keep the NWS order. */
    static Alert next(List<Alert> active, Set<String> seen) {
        List<Alert> sorted = new ArrayList<>(active);
        Collections.sort(sorted, (a, b) -> Integer.compare(rank(a.severity), rank(b.severity)));
        for (Alert a : sorted) if (!seen.contains(a.id)) return a;
        return null;
    }

    /** Drops alerts that ended before {@code nowMs}, so the seen list doesn't grow forever. */
    static void prune(Map<String, Long> seen, long nowMs) {
        for (Iterator<Long> it = seen.values().iterator(); it.hasNext(); ) {
            if (it.next() < nowMs) it.remove();
        }
    }

    /** The crawl: the NWS description then its instructions, on one line. */
    static String crawlText(String description, String instruction) {
        String d = oneLine(description), i = oneLine(instruction);
        return d.isEmpty() ? i : i.isEmpty() ? d : d + "   " + i;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * The attention signal as 16-bit mono PCM: both tones at equal level,
     * with a 20 ms fade at each end so it starts and stops without a click.
     */
    static short[] attentionSignal(int sampleRate, double seconds) {
        int n = (int) Math.round(sampleRate * seconds);
        int fade = Math.min(n / 2, sampleRate / 50);
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / sampleRate;
            double v = 0.4 * Math.sin(2 * Math.PI * TONE_LOW_HZ * t) + 0.4 * Math.sin(2 * Math.PI * TONE_HIGH_HZ * t);
            double env = Math.min(1, Math.min(i, n - 1 - i) / (double) Math.max(1, fade));
            pcm[i] = (short) Math.round(v * env * Short.MAX_VALUE);
        }
        return pcm;
    }
}
