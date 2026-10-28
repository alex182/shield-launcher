package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import dev.alex.minlauncher.Backgrounds.Choice;
import dev.alex.minlauncher.Backgrounds.Day;
import dev.alex.minlauncher.Backgrounds.Holiday;
import dev.alex.minlauncher.Backgrounds.Inputs;
import dev.alex.minlauncher.Backgrounds.Manifest;
import dev.alex.minlauncher.Backgrounds.Forced;

public class BackgroundsTest {

    // ------------------------------------------------------------ conditions

    @Test public void mapsEveryWeatherState() {
        String[][] cases = {
                {"sunny", "clear"}, {"clear-night", "clear"},
                {"partlycloudy", "partlycloudy"},
                {"cloudy", "cloudy"}, {"exceptional", "cloudy"},
                {"fog", "fog"},
                {"rainy", "rainy"},
                {"pouring", "pouring"}, {"hail", "pouring"},
                {"lightning", "lightning-rainy"}, {"lightning-rainy", "lightning-rainy"},
                {"snowy", "snowy"}, {"snowy-rainy", "snowy"},
                {"windy", "windy"}, {"windy-variant", "windy"},
                {"unknown", "clear"}, {"unavailable", "clear"}, {"something-new", "clear"},
        };
        for (String[] c : cases) assertEquals(c[0], c[1], Backgrounds.condition(c[0]));
        assertEquals("clear", Backgrounds.condition(null));
    }

    @Test public void everyMappedConditionIsAnImageCondition() {
        for (String s : new String[] {"sunny", "partlycloudy", "exceptional", "fog", "rainy", "hail",
                "lightning", "snowy-rainy", "windy-variant"}) {
            assertTrue(s, Backgrounds.CONDITIONS.contains(Backgrounds.condition(s)));
        }
    }

    @Test public void sunStateToTimeOfDay() {
        assertEquals("day", Backgrounds.timeOfDay("above_horizon"));
        assertEquals("night", Backgrounds.timeOfDay("below_horizon"));
        assertNull(Backgrounds.timeOfDay("unavailable"));
        assertNull(Backgrounds.timeOfDay(null));
    }

    // ----------------------------------------------------------------- dates

    @Test public void dayArithmeticMatchesCalendar() {
        GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(1999, Calendar.JANUARY, 1);
        for (int i = 0; i < 365 * 45; i++) {
            Day d = new Day(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            int n = (int) (c.getTimeInMillis() / 86_400_000L);
            assertEquals(d.toString(), n, d.number());
            assertEquals(d.toString(), d.toString(), Day.ofNumber(n).toString());
            int iso = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;   // Calendar: Sunday = 1
            assertEquals(d.toString(), iso, d.dayOfWeek());
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    @Test public void thanksgivingIsTheFourthThursdayOfNovember() {
        assertEquals("2024-11-28", Backgrounds.thanksgiving(2024).toString());
        assertEquals("2025-11-27", Backgrounds.thanksgiving(2025).toString());
        assertEquals("2026-11-26", Backgrounds.thanksgiving(2026).toString());
        assertEquals("2027-11-25", Backgrounds.thanksgiving(2027).toString());
        assertEquals("2029-11-22", Backgrounds.thanksgiving(2029).toString());   // Nov 1 is a Thursday
        assertEquals("2030-11-28", Backgrounds.thanksgiving(2030).toString());   // Nov 1 is a Friday
        for (int y = 2000; y < 2100; y++) {
            Day t = Backgrounds.thanksgiving(y);
            assertEquals(4, t.dayOfWeek());
            assertTrue(t.day >= 22 && t.day <= 28);
        }
    }

    @Test public void easterSundays() {
        assertEquals("2024-03-31", Backgrounds.easter(2024).toString());
        assertEquals("2025-04-20", Backgrounds.easter(2025).toString());
        assertEquals("2026-04-05", Backgrounds.easter(2026).toString());
        assertEquals("2027-03-28", Backgrounds.easter(2027).toString());
        assertEquals("2038-04-25", Backgrounds.easter(2038).toString());   // latest possible
        for (int y = 2000; y < 2100; y++) assertEquals(7, Backgrounds.easter(y).dayOfWeek());
    }

    // -------------------------------------------------------------- holidays

    private static final List<Holiday> DEFAULTS = Backgrounds.parseHolidays(Backgrounds.DEFAULT_HOLIDAYS).holidays;

    private static String holiday(int y, int m, int d) {
        Holiday h = Backgrounds.holidayOn(new Day(y, m, d), DEFAULTS);
        return h == null ? null : h.name;
    }

    @Test public void defaultRulesParse() {
        assertTrue(Backgrounds.parseHolidays(Backgrounds.DEFAULT_HOLIDAYS).errors.isEmpty());
        assertEquals(8, DEFAULTS.size());
    }

    @Test public void newYearsWrapsTheYearBoundary() {
        assertNull(holiday(2026, 12, 30));
        assertEquals("new_years", holiday(2026, 12, 31));
        assertEquals("new_years", holiday(2027, 1, 1));
        assertNull(holiday(2027, 1, 2));
        // Leap years and the other side of the wrap.
        assertEquals("new_years", holiday(2028, 1, 1));
        assertEquals("new_years", holiday(2028, 12, 31));
    }

    @Test public void thanksgivingThursdayAndFriday() {
        assertNull(holiday(2026, 11, 25));
        assertEquals("thanksgiving", holiday(2026, 11, 26));
        assertEquals("thanksgiving", holiday(2026, 11, 27));
        assertNull(holiday(2026, 11, 28));
        assertEquals("thanksgiving", holiday(2027, 11, 25));
        assertEquals("thanksgiving", holiday(2027, 11, 26));
        assertNull(holiday(2027, 11, 24));
    }

    @Test public void fixedRanges() {
        assertNull(holiday(2026, 10, 23));
        assertEquals("halloween", holiday(2026, 10, 24));
        assertEquals("halloween", holiday(2026, 10, 31));
        assertNull(holiday(2026, 11, 1));
        assertNull(holiday(2026, 12, 17));
        assertEquals("christmas", holiday(2026, 12, 18));
        assertEquals("christmas", holiday(2026, 12, 26));
        assertNull(holiday(2026, 12, 27));
        assertNull(holiday(2026, 7, 2));
        assertEquals("july_4th", holiday(2026, 7, 3));
        assertEquals("july_4th", holiday(2026, 7, 5));
        assertNull(holiday(2026, 7, 6));
        assertEquals("valentines", holiday(2026, 2, 14));
        assertEquals("st_patricks", holiday(2026, 3, 17));
        assertEquals("easter", holiday(2026, 4, 5));
        assertNull(holiday(2026, 4, 6));
    }

    @Test public void customRulesWithOffsetsAndFirstMatchWins() {
        List<Holiday> h = Backgrounds.parseHolidays(""
                + "# comment\n"
                + "easter_weekend: easter-2..easter+1\n"
                + "spooky: 10-31\n"
                + "halloween: 10-01..10-31\n").holidays;
        assertEquals("easter_weekend", Backgrounds.holidayOn(new Day(2026, 4, 3), h).name);
        assertEquals("easter_weekend", Backgrounds.holidayOn(new Day(2026, 4, 6), h).name);
        assertNull(Backgrounds.holidayOn(new Day(2026, 4, 7), h));
        assertEquals("spooky", Backgrounds.holidayOn(new Day(2026, 10, 31), h).name);
        assertEquals("halloween", Backgrounds.holidayOn(new Day(2026, 10, 30), h).name);
    }

    @Test public void badRulesAreReportedNotGuessed() {
        Backgrounds.HolidayRules r = Backgrounds.parseHolidays("ok: 01-01\nno colon here\nx: 13-01\ny: someday\nz: 01-01..02-02..03-03\n");
        assertEquals(1, r.holidays.size());
        assertEquals(4, r.errors.size());
        assertTrue(r.errors.get(0).startsWith("Line 2"));
    }

    // ----------------------------------------------------------------- focal

    @Test public void singleFocalIsAlwaysUsed() {
        List<String> one = Arrays.asList("windmill");
        Day d = new Day(2026, 1, 1);
        for (int i = 0; i < 800; i++) assertEquals("windmill", Backgrounds.focalFor(d.plus(i), one));
    }

    @Test public void noFocalsMeansNone() {
        assertNull(Backgrounds.focalFor(new Day(2026, 1, 1), new ArrayList<>()));
    }

    @Test public void focalIsFixedPerDayAndRotatesDaily() {
        List<String> two = Arrays.asList("windmill", "farmhouse");
        Day d = new Day(2026, 10, 28);
        assertEquals(Backgrounds.focalFor(d, two), Backgrounds.focalFor(new Day(2026, 10, 28), two));
        assertFalse(Backgrounds.focalFor(d, two).equals(Backgrounds.focalFor(d.plus(1), two)));
        // The manifest's order doesn't matter.
        assertEquals(Backgrounds.focalFor(d, two),
                Backgrounds.focalFor(d, Arrays.asList("farmhouse", "windmill")));
    }

    @Test public void newlyAddedFocalJoinsTheRotation() {
        List<String> three = Arrays.asList("windmill", "farmhouse", "lone_tree");
        Set<String> seen = new HashSet<>();
        Day d = new Day(2026, 10, 28);
        for (int i = 0; i < 3; i++) seen.add(Backgrounds.focalFor(d.plus(i), three));
        assertEquals(new HashSet<>(three), seen);
    }

    // -------------------------------------------------------------- resolving

    private static Manifest manifest(String focals, String... files) {
        return new Manifest(Arrays.asList(focals.split(",")), new HashSet<>(Arrays.asList(files)));
    }

    private static Inputs in(Day day, String weather, String sun, String season) {
        return new Inputs(day, weather, sun, season);
    }

    private static final Day ORDINARY = new Day(2026, 9, 15);   // no default holiday
    private static final Manifest M = manifest("windmill,farmhouse",
            "windmill/autumn/day/clear.webp",
            "windmill/autumn/day/fog.webp",
            "windmill/autumn/night/clear.webp",
            "farmhouse/winter/day/clear.webp",
            "holidays/windmill/halloween/day.webp",
            "holidays/farmhouse/christmas/night.webp");

    private static Choice pick(Inputs in, Forced o, String focal) {
        return Backgrounds.resolve(M, in, o, DEFAULTS, focal);
    }

    @Test public void exactMatch() {
        Choice c = pick(in(ORDINARY, "fog", "above_horizon", "autumn"), Forced.NONE, "windmill");
        assertEquals("windmill/autumn/day/fog.webp", c.path);
    }

    @Test public void missingConditionFallsBackToClear() {
        Choice c = pick(in(ORDINARY, "pouring", "above_horizon", "autumn"), Forced.NONE, "windmill");
        assertEquals("windmill/autumn/day/clear.webp", c.path);
        assertTrue(c.reason, c.reason.contains("no pouring image"));
    }

    @Test public void missingFocalFallsBackToAnotherFocalsClear() {
        Choice c = pick(in(ORDINARY, "rainy", "above_horizon", "winter"), Forced.NONE, "windmill");
        assertEquals("farmhouse/winter/day/clear.webp", c.path);
    }

    @Test public void nothingForSeasonAndTimeIsTheDefault() {
        Choice c = pick(in(ORDINARY, "sunny", "below_horizon", "winter"), Forced.NONE, "windmill");
        assertNull(c.path);
    }

    @Test public void holidayBeatsWeather() {
        Choice c = pick(in(new Day(2026, 10, 30), "pouring", "above_horizon", "autumn"), Forced.NONE, "windmill");
        assertEquals("holidays/windmill/halloween/day.webp", c.path);
    }

    @Test public void holidayFromAnotherFocalBeforeWeather() {
        Choice c = pick(in(new Day(2026, 12, 24), "snowy", "below_horizon", "winter"), Forced.NONE, "windmill");
        assertEquals("holidays/farmhouse/christmas/night.webp", c.path);
    }

    @Test public void holidayWithoutImageUsesWeather() {
        // Halloween night has no image at all.
        Choice c = pick(in(new Day(2026, 10, 30), "sunny", "below_horizon", "autumn"), Forced.NONE, "windmill");
        assertEquals("windmill/autumn/night/clear.webp", c.path);
        assertTrue(c.reason, c.reason.contains("no halloween image"));
    }

    @Test public void holidaysDoNotNeedASeason() {
        Choice c = pick(in(new Day(2026, 10, 30), "sunny", "above_horizon", null), Forced.NONE, "windmill");
        assertEquals("holidays/windmill/halloween/day.webp", c.path);
    }

    @Test public void missingSeasonOrSunIsReportedNotGuessed() {
        Choice noSeason = pick(in(ORDINARY, "sunny", "above_horizon", null), Forced.NONE, "windmill");
        assertNull(noSeason.path);
        assertTrue(noSeason.reason, noSeason.reason.contains("season is missing"));
        Choice badSeason = pick(in(ORDINARY, "sunny", "above_horizon", "monsoon"), Forced.NONE, "windmill");
        assertNull(badSeason.path);
        Choice noSun = pick(in(ORDINARY, "sunny", null, "autumn"), Forced.NONE, "windmill");
        assertNull(noSun.path);
        assertTrue(noSun.reason, noSun.reason.contains("sun is missing"));
    }

    @Test public void unavailableWeatherIsClear() {
        Choice c = pick(in(ORDINARY, "unavailable", "above_horizon", "autumn"), Forced.NONE, "windmill");
        assertEquals("windmill/autumn/day/clear.webp", c.path);
    }

    @Test public void overridesReplaceLiveValues() {
        Inputs live = in(new Day(2026, 10, 30), "sunny", "below_horizon", "summer");
        Choice c = pick(live, new Forced(null, "autumn", "day", "fog", Backgrounds.NO_HOLIDAY), "windmill");
        assertEquals("windmill/autumn/day/fog.webp", c.path);
        assertTrue(c.reason.startsWith("debug override"));
        Choice h = pick(in(ORDINARY, "sunny", "below_horizon", "winter"),
                new Forced("farmhouse", null, null, null, "christmas"), "windmill");
        assertEquals("holidays/farmhouse/christmas/night.webp", h.path);
    }

    @Test public void noFocalInManifest() {
        Manifest empty = manifest("x");
        Choice c = Backgrounds.resolve(new Manifest(new ArrayList<>(), empty.files),
                in(ORDINARY, "sunny", "above_horizon", "autumn"), Forced.NONE, DEFAULTS, null);
        assertNull(c.path);
    }

    @Test public void nightPaths() {
        assertTrue(Backgrounds.isNight("windmill/autumn/night/fog.webp"));
        assertTrue(Backgrounds.isNight("holidays/windmill/christmas/night.webp"));
        assertFalse(Backgrounds.isNight("windmill/autumn/day/fog.webp"));
        assertFalse(Backgrounds.isNight(null));
    }
}
