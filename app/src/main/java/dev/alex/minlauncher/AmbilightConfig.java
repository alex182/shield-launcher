package dev.alex.minlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The ambilight's settings as stored ({@link Store#KEY_AMBILIGHT}) and sent
 * to and from the config page, and their reading into {@link Ambilight}'s
 * model. Every field is filled in and kept in range, whatever was saved.
 * Lengths are centimetres; the page shows inches or centimetres.
 *
 * <pre>
 * {autoStart, fps, units: "in"|"cm",
 *  brightness, saturation, gamma, smoothingMs, edgeDepth, edgeInset, autoEdge, blackBars,
 *  room: {width, depth, height},
 *  tv: {x, z, offset, width, height},
 *  strips: [{name, host, start, count, points: [{x, y, z}, …]}, …]}
 * </pre>
 */
final class AmbilightConfig {

    private AmbilightConfig() { }

    static final int[] FPS = {15, 20, 25, 30, 45, 60};
    static final int DEFAULT_FPS = 30;
    /** WLED's own limit for one controller's LEDs is far below this. */
    static final int MAX_LEDS = 2000;

    static JSONObject normalize(JSONObject in) {
        if (in == null) in = new JSONObject();
        try {
            JSONObject roomIn = obj(in, "room"), tvIn = obj(in, "tv");
            double width = clamp(roomIn.optDouble("width", 450), 100, 3000);
            double depth = clamp(roomIn.optDouble("depth", 400), 100, 3000);
            double height = clamp(roomIn.optDouble("height", 245), 150, 1000);
            // A 65" 16:9 screen, centred on the wall, 110 cm up.
            double tvW = clamp(tvIn.optDouble("width", 144), 20, width);
            double tvH = clamp(tvIn.optDouble("height", 81), 10, height);
            JSONObject room = new JSONObject().put("width", width).put("depth", depth).put("height", height);
            JSONObject tv = new JSONObject()
                    .put("x", clamp(tvIn.optDouble("x", width / 2), 0, width))
                    .put("z", clamp(tvIn.optDouble("z", 110), 0, height))
                    .put("offset", clamp(tvIn.optDouble("offset", 5), 0, depth / 2))
                    .put("width", tvW).put("height", tvH);

            JSONArray strips = new JSONArray(), sIn = in.optJSONArray("strips");
            for (int i = 0; sIn != null && i < sIn.length(); i++) {
                JSONObject s = sIn.optJSONObject(i);
                if (s == null) continue;
                JSONArray pts = new JSONArray(), pIn = s.optJSONArray("points");
                for (int j = 0; pIn != null && j < pIn.length(); j++) {
                    JSONObject p = pIn.optJSONObject(j);
                    if (p == null) continue;
                    pts.put(new JSONObject()
                            .put("x", clamp(p.optDouble("x", 0), 0, width))
                            .put("y", clamp(p.optDouble("y", 0), 0, depth))
                            .put("z", clamp(p.optDouble("z", 0), 0, height)));
                }
                strips.put(new JSONObject()
                        .put("name", s.optString("name", "Strip " + (i + 1)))
                        .put("host", s.optString("host", "").trim())
                        .put("start", (int) clamp(s.optInt("start", 0), 0, 65535 - MAX_LEDS))
                        .put("count", (int) clamp(s.optInt("count", 60), 1, MAX_LEDS))
                        .put("points", pts));
            }

            int fps = in.optInt("fps", DEFAULT_FPS);
            boolean known = false;
            for (int f : FPS) known |= f == fps;
            String units = in.optString("units", "in");
            Ambilight.Tuning d = Ambilight.Tuning.DEFAULT;
            return new JSONObject()
                    .put("autoStart", in.optBoolean("autoStart", false))
                    .put("fps", known ? fps : DEFAULT_FPS)
                    .put("units", units.equals("cm") ? "cm" : "in")
                    .put("brightness", (int) clamp(in.optInt("brightness", d.brightness), 0, 100))
                    .put("saturation", (int) clamp(in.optInt("saturation", d.saturation), 0, 300))
                    .put("gamma", clamp(in.optDouble("gamma", d.gamma), 0.5, 3))
                    .put("smoothingMs", (int) clamp(in.optInt("smoothingMs", d.smoothingMs), 0, 2000))
                    .put("edgeDepth", (int) clamp(in.optInt("edgeDepth", d.edgeDepth), 2, 50))
                    .put("edgeInset", (int) clamp(in.optInt("edgeInset", d.edgeInset), 0, 30))
                    .put("blackBars", in.optBoolean("blackBars", true))
                    .put("autoEdge", in.optBoolean("autoEdge", false))
                    .put("room", room)
                    .put("tv", tv)
                    .put("strips", strips);
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only put() of finite values above
        }
    }

    /** Reads a normalized config ({@link #normalize}). */
    static Ambilight.Layout layout(JSONObject c) {
        JSONObject room = c.optJSONObject("room"), tv = c.optJSONObject("tv");
        List<Ambilight.Strip> strips = new ArrayList<>();
        JSONArray a = c.optJSONArray("strips");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            List<Ambilight.Point> pts = new ArrayList<>();
            JSONArray p = s.optJSONArray("points");
            for (int j = 0; p != null && j < p.length(); j++) {
                JSONObject q = p.optJSONObject(j);
                pts.add(new Ambilight.Point(q.optDouble("x"), q.optDouble("y"), q.optDouble("z")));
            }
            String[] hp = Ambilight.hostPort(s.optString("host"));
            strips.add(new Ambilight.Strip(s.optString("name"), hp[0], Integer.parseInt(hp[1]),
                    s.optInt("start"), s.optInt("count"), pts));
        }
        return new Ambilight.Layout(
                new Ambilight.Room(room.optDouble("width"), room.optDouble("depth"), room.optDouble("height")),
                new Ambilight.Tv(tv.optDouble("x"), tv.optDouble("z"), tv.optDouble("offset"),
                        tv.optDouble("width"), tv.optDouble("height")),
                strips);
    }

    static Ambilight.Tuning tuning(JSONObject c) {
        return new Ambilight.Tuning(c.optInt("brightness"), c.optInt("saturation"), c.optDouble("gamma"),
                c.optInt("smoothingMs"), c.optInt("edgeDepth"), c.optInt("edgeInset"));
    }

    /**
     * For the page's preview: every LED's place and the picture point it
     * shows, with its test-pattern colour, and each strip's length:
     * {strips: [{length, leds: [[x, y, z, sx, sy, weight, "#rrggbb"], …]}, …]}.
     */
    static JSONObject preview(JSONObject c) throws JSONException {
        Ambilight.Layout l = layout(c);
        JSONArray strips = new JSONArray();
        for (Ambilight.Strip s : l.strips) {
            double[][] pos = Ambilight.ledPositions(s);
            JSONArray leds = new JSONArray();
            for (double[] p : pos) {
                double[] m = Ambilight.screenPoint(l.tv, p[0], p[1], p[2]);
                leds.put(new JSONArray().put(round(p[0])).put(round(p[1])).put(round(p[2]))
                        .put(round3(m[0])).put(round3(m[1])).put(round3(m[2]))
                        .put(hex(Ambilight.testColour(m[0], m[1], m[2]))));
            }
            strips.put(new JSONObject().put("length", round(Ambilight.length(s))).put("leds", leds));
        }
        return new JSONObject().put("strips", strips);
    }

    static String hex(int rgb) { return String.format("#%06x", rgb & 0xffffff); }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }
    private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }

    private static JSONObject obj(JSONObject in, String key) {
        JSONObject o = in.optJSONObject(key);
        return o == null ? new JSONObject() : o;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
