package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import dev.alex.minlauncher.Agenda.Event;

public class AgendaTest {

    /** Local time on 2026-10-29 (a Thursday), plus {@code dayOffset} days. */
    private static long at(int dayOffset, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, 29, hour, minute);
        c.add(Calendar.DAY_OF_MONTH, dayOffset);
        return c.getTimeInMillis();
    }

    private static final long TODAY = at(0, 0, 0), TOMORROW = at(1, 0, 0), AFTER = at(2, 0, 0);

    private static Event timed(String s, long start, long end) { return new Event(s, start, end, false); }
    private static Event allDay(String s, int fromDay, int days) {
        return new Event(s, at(fromDay, 0, 0), at(fromDay + days, 0, 0), true);
    }

    private static List<String> names(List<Event> es) {
        List<String> out = new ArrayList<>();
        for (Event e : es) out.add(e.summary);
        return out;
    }

    @Test public void parsesAllDayDates() {
        assertEquals(TODAY, Agenda.parseDate("2026-10-29"));
        assertEquals(-1, Agenda.parseDate("2026-13-01"));
        assertEquals(-1, Agenda.parseDate("soon"));
        assertEquals(-1, Agenda.parseDate(null));
    }

    @Test public void dayStartIsLocalMidnight() {
        assertEquals(TODAY, Agenda.dayStart(at(0, 15, 42), 0));
        assertEquals(TOMORROW, Agenda.dayStart(at(0, 23, 59), 1));
        assertEquals(AFTER, Agenda.dayStart(TODAY, 2));
    }

    @Test public void splitsByDayAllDayFirst() {
        List<Event> all = Arrays.asList(
                timed("Dentist", at(0, 14, 30), at(0, 15, 30)),
                timed("Breakfast", at(0, 8, 0), at(0, 9, 0)),
                allDay("Bin day", 0, 1),
                timed("Flight", at(1, 6, 0), at(1, 9, 0)),
                allDay("Halloween", 2, 1));
        long now = at(0, 7, 0);
        assertEquals(Arrays.asList("Bin day", "Breakfast", "Dentist"), names(Agenda.forDay(all, TODAY, TOMORROW, now)));
        assertEquals(Arrays.asList("Flight"), names(Agenda.forDay(all, TOMORROW, AFTER, now)));
    }

    @Test public void finishedEventsDropOff() {
        List<Event> all = Arrays.asList(
                timed("Breakfast", at(0, 8, 0), at(0, 9, 0)),
                timed("Lunch", at(0, 12, 0), at(0, 13, 0)),
                timed("Reminder", at(0, 10, 0), at(0, 10, 0)));
        assertEquals(Arrays.asList("Breakfast", "Reminder", "Lunch"),
                names(Agenda.forDay(all, TODAY, TOMORROW, at(0, 8, 30))));
        assertEquals(Arrays.asList("Lunch"), names(Agenda.forDay(all, TODAY, TOMORROW, at(0, 10, 1))));
    }

    @Test public void allDayEventEndsAtMidnight() {
        // Home Assistant's end date is exclusive: a one-day event isn't tomorrow's too.
        List<Event> all = Arrays.asList(allDay("Bin day", 0, 1), allDay("Trip", 0, 3));
        assertEquals(Arrays.asList("Bin day", "Trip"), names(Agenda.forDay(all, TODAY, TOMORROW, at(0, 9, 0))));
        assertEquals(Arrays.asList("Trip"), names(Agenda.forDay(all, TOMORROW, AFTER, at(0, 9, 0))));
    }

    @Test public void sameEventInTwoCalendarsShowsOnce() {
        List<Event> all = Arrays.asList(
                timed("Dentist", at(0, 14, 30), at(0, 15, 30)),
                timed("Dentist", at(0, 14, 30), at(0, 15, 30)));
        assertEquals(1, Agenda.forDay(all, TODAY, TOMORROW, at(0, 9, 0)).size());
    }

    @Test public void itemReads() {
        long now = at(0, 5, 0);
        assertEquals("Bin day", Agenda.item(allDay("Bin day", 0, 1), TODAY, TOMORROW, now));
        assertEquals("14:30 Dentist", Agenda.item(timed("Dentist", at(0, 14, 30), at(0, 15, 30)), TODAY, TOMORROW, now));
        Event overnight = timed("Night shift", at(-1, 22, 0), at(0, 6, 0));
        assertEquals("Night shift until 06:00", Agenda.item(overnight, TODAY, TOMORROW, now));
        Event long_ = timed("Conference", at(-1, 9, 0), at(1, 17, 0));
        assertEquals("Conference", Agenda.item(long_, TODAY, TOMORROW, now));
        assertTrue(Agenda.forDay(Arrays.asList(long_), TODAY, TOMORROW, now).contains(long_));
    }

    @Test public void lineNamesTwoThenCounts() {
        long now = at(0, 9, 0);
        Event bins = allDay("Bin day", 0, 1), therapy = timed("Therapy", at(0, 16, 0), at(0, 17, 0)),
                dinner = timed("Dinner", at(0, 18, 30), at(0, 20, 0)), call = timed("Call", at(0, 20, 0), at(0, 21, 0));
        assertEquals(null, Agenda.line(Arrays.asList(), TODAY, TOMORROW, now));
        assertEquals("16:00 Therapy", Agenda.line(Arrays.asList(therapy), TODAY, TOMORROW, now));
        assertEquals("Bin day · 16:00 Therapy", Agenda.line(Arrays.asList(bins, therapy), TODAY, TOMORROW, now));
        assertEquals("Bin day · 16:00 Therapy · +2 more",
                Agenda.line(Arrays.asList(bins, therapy, dinner, call), TODAY, TOMORROW, now));
        assertEquals("Therapy until 17:00 · 18:30 Dinner",
                Agenda.line(Arrays.asList(therapy, dinner), TODAY, TOMORROW, at(0, 16, 10)));
    }

    @Test public void parsesLeads() {
        assertEquals(Arrays.asList(10, 30, 60), Agenda.parseLeads("60, 30, 10"));
        assertEquals(Arrays.asList(5, 15), Agenda.parseLeads(" 15 5 15 0"));
        assertEquals(Arrays.asList(), Agenda.parseLeads(""));
        assertEquals(Arrays.asList(), Agenda.parseLeads("0"));
        assertEquals(Arrays.asList(1440), Agenda.parseLeads("1440 1441 9999999999"));
        assertEquals("60, 30, 10", Agenda.formatLeads(Agenda.parseLeads("10,60;30")));
    }

    @Test public void remindsAtEachLeadOnce() {
        List<Integer> leads = Agenda.parseLeads("60, 30, 10");
        Event therapy = timed("Therapy", at(0, 16, 0), at(0, 17, 0));
        List<Event> all = Arrays.asList(therapy, allDay("Bin day", 0, 1), therapy);
        java.util.Set<String> seen = new java.util.HashSet<>();
        assertTrue(Agenda.due(all, at(0, 14, 59), leads, seen).isEmpty());
        for (int[] hm : new int[][] { { 15, 0 }, { 15, 30 }, { 15, 50 } }) {
            long now = at(0, hm[0], hm[1]);
            assertEquals(Arrays.asList("Therapy"), names(Agenda.due(all, now, leads, seen)));
            seen.addAll(Agenda.keysThrough(therapy, Agenda.slot(therapy, now, leads), leads));
            assertTrue("once", Agenda.due(all, now + 60_000, leads, seen).isEmpty());
        }
        assertTrue("started", Agenda.due(all, at(0, 16, 0), leads, new java.util.HashSet<>()).isEmpty());
    }

    @Test public void lateTvGetsOnlyTheLatest() {
        List<Integer> leads = Agenda.parseLeads("60, 30, 10");
        Event therapy = timed("Therapy", at(0, 16, 0), at(0, 17, 0));
        long now = at(0, 15, 40);
        assertEquals(30, Agenda.slot(therapy, now, leads));
        java.util.Set<String> seen = new java.util.HashSet<>(Agenda.keysThrough(therapy, 30, leads));
        assertTrue(Agenda.due(Arrays.asList(therapy), at(0, 15, 45), leads, seen).isEmpty());
        assertEquals(1, Agenda.due(Arrays.asList(therapy), at(0, 15, 50), leads, seen).size());
    }

    @Test public void minutesRoundUp() {
        assertEquals(10, Agenda.minutesUntil(at(0, 16, 0), at(0, 15, 50)));
        assertEquals(10, Agenda.minutesUntil(at(0, 16, 0), at(0, 15, 50) + 30_000));
        assertEquals(1, Agenda.minutesUntil(at(0, 16, 0), at(0, 15, 59) + 30_000));
        assertEquals(0, Agenda.minutesUntil(at(0, 16, 0), at(0, 16, 1)));
    }

    @Test public void chimeFitsAndFades() {
        short[] pcm = Agenda.chime(44_100);
        assertTrue(pcm.length > 44_100 && pcm.length < 3 * 44_100);
        assertTrue("silent start", Math.abs(pcm[0]) < 100);
        int peak = 0, tail = 0;
        for (int i = 0; i < pcm.length; i++) {
            peak = Math.max(peak, Math.abs(pcm[i]));
            if (i > pcm.length - 4410) tail = Math.max(tail, Math.abs(pcm[i]));
        }
        assertTrue("audible", peak > 5000);
        assertTrue("faded out", tail < peak / 20);
    }

    @Test public void homeLineTurnsToTomorrowAfterTheLastEvent() {
        List<Event> all = Arrays.asList(
                timed("Therapy", at(0, 16, 0), at(0, 17, 0)),
                allDay("Bin day", 1, 1),
                timed("Dentist", at(1, 9, 0), at(1, 10, 0)),
                timed("Lunch", at(1, 12, 0), at(1, 13, 0)));
        assertEquals("16:00 Therapy", Agenda.homeLine(all, at(0, 9, 0)));
        assertEquals("Therapy until 17:00", Agenda.homeLine(all, at(0, 16, 30)));
        assertEquals("Tomorrow · Bin day · 09:00 Dentist · +1 more", Agenda.homeLine(all, at(0, 17, 0)));
        assertEquals(null, Agenda.homeLine(Arrays.asList(timed("Therapy", at(0, 16, 0), at(0, 17, 0))), at(0, 18, 0)));
    }

    @Test public void summaryTimes() {
        assertEquals("All day", Agenda.time(allDay("Bin day", 1, 1), TOMORROW, AFTER));
        assertEquals("09:00", Agenda.time(timed("Dentist", at(1, 9, 0), at(1, 10, 0)), TOMORROW, AFTER));
        assertEquals("until 06:00", Agenda.time(timed("Night shift", at(0, 22, 0), at(1, 6, 0)), TOMORROW, AFTER));
    }

    @Test public void parsesClock() {
        assertEquals(20 * 60 + 30, Agenda.parseClock("20:30"));
        assertEquals(8 * 60 + 5, Agenda.parseClock(" 8:05 "));
        assertEquals(-1, Agenda.parseClock("24:00"));
        assertEquals(-1, Agenda.parseClock("8:5"));
        assertEquals(-1, Agenda.parseClock(""));
        assertEquals(-1, Agenda.parseClock(null));
    }

    @Test public void digestOncePerEveningUntilMidnight() {
        int at = Agenda.parseClock("20:30");
        assertTrue(!Agenda.digestDue(at(0, 20, 29), at, 0));
        assertTrue(Agenda.digestDue(at(0, 20, 30), at, 0));
        assertTrue("TV on late", Agenda.digestDue(at(0, 23, 10), at, 0));
        assertTrue("shown today", !Agenda.digestDue(at(0, 21, 0), at, TODAY));
        assertTrue("shown yesterday", Agenda.digestDue(at(0, 21, 0), at, at(-1, 0, 0)));
        assertTrue("next morning", !Agenda.digestDue(at(1, 8, 0), at, TODAY));
        assertTrue("off", !Agenda.digestDue(at(0, 21, 0), -1, 0));
    }
}
