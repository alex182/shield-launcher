package dev.alex.minlauncher;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The pure part of the home screen's calendar ({@link AgendaController}):
 * which events belong to a day, in what order, and how their times read. No
 * Android here, so it runs in the JVM unit tests.
 */
final class Agenda {

    /** Events named on the line before "+N more"; it sits under the weather, so it stays short. */
    static final int MAX_SHOWN = 2;

    private Agenda() { }

    /** One event, in epoch ms. All-day events run from local midnight to the midnight after their last day. */
    static final class Event {
        final String summary;
        final long start, end;
        final boolean allDay;
        Event(String summary, long start, long end, boolean allDay) {
            this.summary = summary; this.start = start; this.end = Math.max(start, end); this.allDay = allDay;
        }
        @Override public String toString() { return summary + " " + new Date(start) + (allDay ? " (all day)" : ""); }
    }

    /** A "yyyy-MM-dd" date (Home Assistant's all-day start and end) as local midnight; -1 if it isn't one. */
    static long parseDate(String s) {
        if (s == null) return -1;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setLenient(false);
        try {
            return f.parse(s).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    /** Local midnight at the start of the day {@code days} after the one holding {@code ms}. */
    static long dayStart(long ms, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /**
     * The events that touch [{@code dayStart}, {@code dayEnd}) and haven't
     * ended by {@code now}: all-day ones first, then by start time. The same
     * event in two calendars (a shared family one, say) shows once.
     */
    static List<Event> forDay(List<Event> events, long dayStart, long dayEnd, long now) {
        List<Event> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Event e : events) {
            // A reminder with no length counts as lasting its first millisecond.
            long end = Math.max(e.end, e.start + 1);
            if (e.start >= dayEnd || end <= dayStart || end <= now) continue;
            if (seen.add(e.summary + "\n" + e.start + "\n" + e.end)) out.add(e);
        }
        Collections.sort(out, (a, b) -> {
            boolean wa = wholeDay(a, dayStart, dayEnd), wb = wholeDay(b, dayStart, dayEnd);
            if (wa != wb) return wa ? -1 : 1;
            return a.start != b.start ? Long.compare(a.start, b.start) : a.summary.compareTo(b.summary);
        });
        return out;
    }

    /**
     * The line under the weather, e.g. "Bin day · 16:00 Therapy · +1 more",
     * from {@link #forDay}'s list; null if there is nothing left today.
     */
    static String line(List<Event> day, long dayStart, long dayEnd, long now) {
        if (day.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        int shown = Math.min(MAX_SHOWN, day.size());
        for (int i = 0; i < shown; i++) {
            b.append(i == 0 ? "" : " · ").append(item(day.get(i), dayStart, dayEnd, now));
        }
        if (day.size() > shown) b.append(" · +").append(day.size() - shown).append(" more");
        return b.toString();
    }

    /**
     * The line under the date: today's events while any are left, then,
     * after the day's last one, tomorrow's ("Tomorrow · 09:00 Dentist").
     * {@code events} must cover today and tomorrow. Null if both are empty.
     */
    static String homeLine(List<Event> events, long now) {
        long today = dayStart(now, 0), tomorrow = dayStart(now, 1), after = dayStart(now, 2);
        String line = line(forDay(events, today, tomorrow, now), today, tomorrow, now);
        if (line != null) return line;
        line = line(forDay(events, tomorrow, after, now), tomorrow, after, now);
        return line == null ? null : "Tomorrow · " + line;
    }

    /** A day's list, as the evening summary shows it: "All day", "09:00", or "until 06:00" if it began before. */
    static String time(Event e, long dayStart, long dayEnd) {
        if (wholeDay(e, dayStart, dayEnd)) return "All day";
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return e.start < dayStart ? "until " + hm.format(new Date(e.end)) : hm.format(new Date(e.start));
    }

    /** "20:30" as minutes after midnight; -1 for anything else, blank included. */
    static int parseClock(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s*(\\d{1,2}):(\\d{2})\\s*")
                .matcher(s == null ? "" : s);
        if (!m.matches()) return -1;
        int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
        return h < 24 && min < 60 ? h * 60 + min : -1;
    }

    /**
     * Whether the evening summary should come up now: it is past
     * {@code atMinute} today and today's hasn't been shown (the TV turned on
     * at 22:00 still gets it, until midnight).
     */
    static boolean digestDue(long now, int atMinute, long shownForDay) {
        long today = dayStart(now, 0);
        return atMinute >= 0 && shownForDay != today && now >= today + atMinute * 60_000L;
    }

    /** "Bin day" (all day), "16:00 Therapy", or "Therapy until 17:00" once it has begun. */
    static String item(Event e, long dayStart, long dayEnd, long now) {
        if (wholeDay(e, dayStart, dayEnd)) return e.summary;
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        if (e.start <= now && e.end > e.start) return e.summary + " until " + hm.format(new Date(e.end));
        return hm.format(new Date(e.start)) + " " + e.summary;
    }

    // ------------------------------------------------------------ reminders

    /** Longest reminder lead, in minutes: a day. */
    static final int MAX_LEAD_MINUTES = 1440;

    /**
     * "60, 30, 10" (any separators) as reminder leads in minutes, smallest
     * first, without repeats; 0 and anything over a day are dropped, so ""
     * or "0" means no reminders.
     */
    static List<Integer> parseLeads(String s) {
        List<Integer> out = new ArrayList<>();
        for (String p : (s == null ? "" : s).split("[^0-9]+")) {
            if (p.isEmpty() || p.length() > 5) continue;
            int m = Integer.parseInt(p);
            if (m > 0 && m <= MAX_LEAD_MINUTES && !out.contains(m)) out.add(m);
        }
        Collections.sort(out);
        return out;
    }

    /** The leads back as text, largest first, as the config page shows them: "60, 30, 10". */
    static String formatLeads(List<Integer> leads) {
        StringBuilder b = new StringBuilder();
        for (int i = leads.size() - 1; i >= 0; i--) b.append(b.length() == 0 ? "" : ", ").append(leads.get(i));
        return b.toString();
    }

    /**
     * Which of the {@code leads} (smallest first) an event is inside at
     * {@code now}: the smallest one it is within; -1 if it is further off than
     * all of them, or has started.
     */
    static int slot(Event e, long now, List<Integer> leads) {
        if (e.start <= now) return -1;
        for (int m : leads) if (e.start - m * 60_000L <= now) return m;
        return -1;
    }

    /** One reminder of one event, as remembered once shown: "summary\nstart@lead". */
    static String key(Event e, int lead) {
        return e.summary + "\n" + e.start + "@" + lead;
    }

    /**
     * The keys to remember once an event is reminded of at {@code lead}: that
     * one and every longer one, so turning the TV on 5 minutes before brings
     * the 10-minute reminder alone, not the 60 and 30 that were missed too.
     */
    static List<String> keysThrough(Event e, int lead, List<Integer> leads) {
        List<String> out = new ArrayList<>();
        for (int m : leads) if (m >= lead) out.add(key(e, m));
        return out;
    }

    /**
     * Timed events whose current reminder ({@link #slot}) hasn't been shown,
     * soonest first. All-day events have no moment to remind of. The same
     * event in two calendars comes once.
     */
    static List<Event> due(List<Event> events, long now, List<Integer> leads, Set<String> seen) {
        List<Event> out = new ArrayList<>();
        Set<String> keys = new HashSet<>(seen);
        for (Event e : events) {
            if (e.allDay) continue;
            int lead = slot(e, now, leads);
            if (lead > 0 && keys.add(key(e, lead))) out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** Whole minutes until {@code start}, rounded up: 9.5 minutes away reads "10". */
    static int minutesUntil(long start, long now) {
        return (int) Math.max(0, (start - now + 59_999) / 60_000);
    }

    /**
     * A soft two-note chime (E5 then C5, a doorbell's "ding-dong") as 16-bit
     * mono PCM: each note a sine with a quieter octave above, a 5 ms attack
     * against clicks, and an exponential fade.
     */
    static short[] chime(int sampleRate) {
        double[][] notes = { { 659.25, 0.0 }, { 523.25, 0.45 } };   // Hz, start in seconds
        double noteSeconds = 1.4;
        int n = (int) Math.round(sampleRate * (notes[1][1] + noteSeconds));
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / sampleRate, v = 0;
            for (double[] note : notes) {
                double u = t - note[1];
                if (u < 0 || u > noteSeconds) continue;
                double env = Math.min(1, u / 0.005) * Math.exp(-u * 3.2);
                v += env * (Math.sin(2 * Math.PI * note[0] * u) + 0.25 * Math.sin(4 * Math.PI * note[0] * u));
            }
            pcm[i] = (short) Math.round(Math.max(-1, Math.min(1, v * 0.3)) * Short.MAX_VALUE);
        }
        return pcm;
    }

    private static boolean wholeDay(Event e, long dayStart, long dayEnd) {
        return e.allDay || e.start <= dayStart && e.end >= dayEnd;
    }
}
