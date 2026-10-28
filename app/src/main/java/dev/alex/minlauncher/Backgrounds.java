package dev.alex.minlauncher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chooses the home screen background from Home Assistant's weather, sun and
 * season, the date, and the images the manifest says exist. Paths are
 * relative to the manifest, as scripts/generate_backgrounds.py writes them:
 * <pre>
 *   &lt;focal&gt;/&lt;season&gt;/&lt;day|night&gt;/&lt;condition&gt;.webp
 *   holidays/&lt;focal&gt;/&lt;holiday&gt;/&lt;day|night&gt;.webp
 * </pre>
 * Plain Java with no Android classes, so it is unit-tested on the JVM. Dates
 * are done by hand because java.time needs API 26 and minSdk is 21.
 */
final class Backgrounds {

    static final String CLEAR = "clear";
    static final List<String> SEASONS = Arrays.asList("spring", "summer", "autumn", "winter");
    static final List<String> TIMES = Arrays.asList("day", "night");
    static final List<String> CONDITIONS = Arrays.asList("clear", "partlycloudy", "cloudy", "fog",
            "rainy", "pouring", "lightning-rainy", "snowy", "windy");
    /** Debug override value that turns holidays off, to preview weather during one. */
    static final String NO_HOLIDAY = "none";

    /**
     * One rule per line, "name: from..to" (or a single day), checked in order;
     * the first match wins. A day is MM-DD or an anchor, "thanksgiving" (US:
     * fourth Thursday of November) or "easter" (Western Easter Sunday), with
     * an optional ±days offset. A range whose end comes before its start
     * wraps over New Year.
     */
    static final String DEFAULT_HOLIDAYS = ""
            + "valentines: 02-14\n"
            + "st_patricks: 03-17\n"
            + "easter: easter\n"
            + "july_4th: 07-03..07-05\n"
            + "halloween: 10-24..10-31\n"
            + "thanksgiving: thanksgiving..thanksgiving+1\n"
            + "christmas: 12-18..12-26\n"
            + "new_years: 12-31..01-01\n";

    private Backgrounds() { }

    // ------------------------------------------------------------- weather

    /** Home Assistant weather state → image condition. Anything unknown is clear. */
    static String condition(String weatherState) {
        if (weatherState == null) return CLEAR;
        switch (weatherState) {
            case "partlycloudy": return "partlycloudy";
            case "cloudy": case "exceptional": return "cloudy";
            case "fog": return "fog";
            case "rainy": return "rainy";
            case "pouring": case "hail": return "pouring";
            case "lightning": case "lightning-rainy": return "lightning-rainy";
            case "snowy": case "snowy-rainy": return "snowy";
            case "windy": case "windy-variant": return "windy";
            default: return CLEAR;   // sunny, clear-night, unknown, unavailable
        }
    }

    /** sun.sun's state → "day" / "night", or null if it is neither. */
    static String timeOfDay(String sunState) {
        if ("above_horizon".equals(sunState)) return "day";
        if ("below_horizon".equals(sunState)) return "night";
        return null;
    }

    static boolean isNight(String path) {
        return path != null && (path.contains("/night/") || path.endsWith("/night.webp"));
    }

    // ---------------------------------------------------------------- dates

    /** A calendar date. {@code month} is 1–12. */
    static final class Day {
        final int year, month, day;

        Day(int year, int month, int day) { this.year = year; this.month = month; this.day = day; }

        static Day today() {
            Calendar c = Calendar.getInstance();
            return new Day(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        }

        /** Days since 1970-01-01 (Howard Hinnant's days_from_civil). */
        int number() {
            int y = month <= 2 ? year - 1 : year;
            int era = (y >= 0 ? y : y - 399) / 400;
            int yoe = y - era * 400;
            int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
            int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
            return era * 146097 + doe - 719468;
        }

        static Day ofNumber(int n) {
            n += 719468;
            int era = (n >= 0 ? n : n - 146096) / 146097;
            int doe = n - era * 146097;
            int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
            int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
            int mp = (5 * doy + 2) / 153;
            int d = doy - (153 * mp + 2) / 5 + 1;
            int m = mp < 10 ? mp + 3 : mp - 9;
            return new Day(yoe + era * 400 + (m <= 2 ? 1 : 0), m, d);
        }

        Day plus(int days) { return ofNumber(number() + days); }

        /** ISO day of week: 1 = Monday … 7 = Sunday. 1970-01-01 was a Thursday. */
        int dayOfWeek() { return ((number() + 3) % 7 + 7) % 7 + 1; }

        @Override public boolean equals(Object o) {
            return o instanceof Day && ((Day) o).number() == number();
        }
        @Override public int hashCode() { return number(); }
        @Override public String toString() {
            return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
        }
    }

    /** US Thanksgiving: the fourth Thursday of November. */
    static Day thanksgiving(int year) {
        Day first = new Day(year, 11, 1);
        int toThursday = (4 - first.dayOfWeek() + 7) % 7;
        return first.plus(toThursday + 21);
    }

    /** Western Easter Sunday (the anonymous Gregorian algorithm). */
    static Day easter(int year) {
        int a = year % 19, b = year / 100, c = year % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = (h + l - 7 * m + 114) % 31 + 1;
        return new Day(year, month, day);
    }

    // ------------------------------------------------------------- holidays

    /** One end of a holiday range: a fixed MM-DD, or an anchor plus an offset. */
    private static final class Edge {
        final int month, day;      // when fixed
        final String anchor;       // "thanksgiving" / "easter", else null
        final int offset;

        Edge(int month, int day) { this.month = month; this.day = day; anchor = null; offset = 0; }
        Edge(String anchor, int offset) { month = 0; day = 0; this.anchor = anchor; this.offset = offset; }

        Day in(int year) {
            if (anchor == null) return new Day(year, month, day);
            Day base = anchor.equals("thanksgiving") ? thanksgiving(year) : easter(year);
            return base.plus(offset);
        }

        static Edge parse(String s) {
            s = s.trim().toLowerCase(Locale.ROOT);
            if (s.matches("\\d{1,2}-\\d{1,2}")) {
                String[] p = s.split("-");
                int m = Integer.parseInt(p[0]), d = Integer.parseInt(p[1]);
                if (m < 1 || m > 12 || d < 1 || d > 31) throw new IllegalArgumentException("no such date " + s);
                return new Edge(m, d);
            }
            java.util.regex.Matcher a = java.util.regex.Pattern
                    .compile("(thanksgiving|easter)\\s*(?:([+-])\\s*(\\d+))?").matcher(s);
            if (!a.matches()) {
                throw new IllegalArgumentException("\"" + s + "\" is not MM-DD, thanksgiving or easter");
            }
            int off = a.group(2) == null ? 0 : Integer.parseInt(a.group(3));
            return new Edge(a.group(1), "-".equals(a.group(2)) ? -off : off);
        }
    }

    static final class Holiday {
        final String name, rule;
        private final Edge from, to;

        private Holiday(String name, String rule, Edge from, Edge to) {
            this.name = name; this.rule = rule; this.from = from; this.to = to;
        }

        boolean covers(Day d) {
            int start = from.in(d.year).number(), end = to.in(d.year).number(), n = d.number();
            return start <= end ? n >= start && n <= end : n >= start || n <= end;
        }
    }

    /** Parsed holiday rules plus a message per line that could not be read. */
    static final class HolidayRules {
        final List<Holiday> holidays = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    static HolidayRules parseHolidays(String text) {
        HolidayRules out = new HolidayRules();
        String[] lines = (text == null ? "" : text).split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            String name = colon < 0 ? "" : line.substring(0, colon).trim();
            if (!name.matches("[a-z0-9_]+")) {
                out.errors.add("Line " + (i + 1) + ": expected \"name: MM-DD..MM-DD\"");
                continue;
            }
            String rule = line.substring(colon + 1).trim();
            try {
                String[] ends = rule.split("\\.\\.", -1);
                if (ends.length > 2) throw new IllegalArgumentException("more than one \"..\"");
                Edge from = Edge.parse(ends[0]);
                Edge to = ends.length == 2 ? Edge.parse(ends[1]) : from;
                out.holidays.add(new Holiday(name, rule, from, to));
            } catch (IllegalArgumentException e) {
                out.errors.add("Line " + (i + 1) + " (" + name + "): " + e.getMessage());
            }
        }
        return out;
    }

    /** The first holiday covering {@code d}, or null. */
    static Holiday holidayOn(Day d, List<Holiday> holidays) {
        for (Holiday h : holidays) if (h.covers(d)) return h;
        return null;
    }

    // ---------------------------------------------------------------- focal

    /**
     * Today's focal element: the available ones in name order, rotated one per
     * day. A pure function of the date, so it holds all day whatever the
     * weather does, and a newly published focal joins the rotation as soon as
     * the manifest lists it. Null if there are none.
     */
    static String focalFor(Day d, List<String> focals) {
        if (focals == null || focals.isEmpty()) return null;
        List<String> sorted = new ArrayList<>(focals);
        Collections.sort(sorted);
        int n = sorted.size();
        return sorted.get(((d.number() % n) + n) % n);
    }

    // ------------------------------------------------------------ resolving

    /** What the manifest says exists. */
    static final class Manifest {
        final List<String> focals;
        final Set<String> files;
        Manifest(List<String> focals, Set<String> files) { this.focals = focals; this.files = files; }
    }

    /** Current Home Assistant states; null where an entity is missing. */
    static final class Inputs {
        final Day day;
        final String weather, sun, season;
        Inputs(Day day, String weather, String sun, String season) {
            this.day = day; this.weather = weather; this.sun = sun; this.season = season;
        }
    }

    /** Debug override: each non-blank field replaces the live value. */
    static final class Forced {
        final String focal, season, time, condition, holiday;
        Forced(String focal, String season, String time, String condition, String holiday) {
            this.focal = blankToNull(focal); this.season = blankToNull(season); this.time = blankToNull(time);
            this.condition = blankToNull(condition); this.holiday = blankToNull(holiday);
        }
        static final Forced NONE = new Forced(null, null, null, null, null);
        boolean any() { return focal != null || season != null || time != null || condition != null || holiday != null; }
    }

    /** The image to show (null = the launcher's plain default) and why. */
    static final class Choice {
        final String path, reason;
        Choice(String path, String reason) { this.path = path; this.reason = reason; }
        @Override public String toString() { return (path == null ? "default background" : path) + " (" + reason + ")"; }
    }

    static String weatherPath(String focal, String season, String time, String condition) {
        return focal + "/" + season + "/" + time + "/" + condition + ".webp";
    }

    static String holidayPath(String focal, String holiday, String time) {
        return "holidays/" + focal + "/" + holiday + "/" + time + ".webp";
    }

    /**
     * Holidays first (today's focal, then any other focal), then weather:
     * exact → same focal/season/time clear → another focal's season/time
     * clear → null (the default background). {@code focal} is today's pick.
     */
    static Choice resolve(Manifest m, Inputs in, Forced o, List<Holiday> holidays, String focal) {
        List<String> why = new ArrayList<>();
        if (o.any()) why.add("debug override");

        if (o.focal != null) focal = o.focal;
        if (focal == null) return new Choice(null, "manifest lists no focal elements");
        List<String> others = new ArrayList<>(m.focals);
        Collections.sort(others);
        others.remove(focal);

        String time = o.time != null ? o.time : timeOfDay(in.sun);
        if (time == null) {
            return new Choice(null, "sun is " + describe(in.sun) + ", not above/below_horizon");
        }

        String holiday = null;
        if (o.holiday != null) {
            if (!o.holiday.equals(NO_HOLIDAY)) holiday = o.holiday;
        } else {
            Holiday h = holidayOn(in.day, holidays);
            if (h != null) holiday = h.name;
        }
        if (holiday != null) {
            String p = holidayPath(focal, holiday, time);
            if (m.files.contains(p)) {
                why.add("holiday " + holiday + ", " + time + ", focal " + focal);
                return new Choice(p, join(why));
            }
            for (String f : others) {
                p = holidayPath(f, holiday, time);
                if (m.files.contains(p)) {
                    why.add("holiday " + holiday + ", " + time + "; none for " + focal + ", using " + f);
                    return new Choice(p, join(why));
                }
            }
            why.add("no " + holiday + " image, using weather");
        }

        String season = o.season != null ? o.season : in.season;
        if (season == null || !SEASONS.contains(season)) {
            why.add("season is " + describe(season) + ", not spring/summer/autumn/winter");
            return new Choice(null, join(why));
        }
        String condition = o.condition != null ? o.condition : condition(in.weather);
        String source = o.condition != null ? condition : "weather " + describe(in.weather) + " → " + condition;

        String p = weatherPath(focal, season, time, condition);
        if (m.files.contains(p)) {
            why.add(source + ", " + season + ", " + time + ", focal " + focal);
            return new Choice(p, join(why));
        }
        p = weatherPath(focal, season, time, CLEAR);
        if (m.files.contains(p)) {
            why.add(source + ", " + season + ", " + time + ", focal " + focal + "; no " + condition + " image, using clear");
            return new Choice(p, join(why));
        }
        for (String f : others) {
            p = weatherPath(f, season, time, CLEAR);
            if (m.files.contains(p)) {
                why.add(source + ", " + season + ", " + time + "; nothing for " + focal + ", using " + f + " clear");
                return new Choice(p, join(why));
            }
        }
        why.add("no image for " + season + "/" + time + "; default background");
        return new Choice(null, join(why));
    }

    private static String describe(String state) { return state == null ? "missing" : "\"" + state + "\""; }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String s : parts) b.append(b.length() == 0 ? "" : "; ").append(s);
        return b.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
