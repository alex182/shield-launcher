package dev.alex.minlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The pure part of the room ambilight ({@link AmbilightService}): where each
 * LED is in the room, which part of the picture it shows, the sampling, the
 * letterbox bars, the colour pipeline and WLED's UDP packets. No Android
 * here, so it runs in the JVM unit tests.
 *
 * <h3>The room</h3>
 * A box, in centimetres, seen from the seat facing the TV:
 * <ul>
 *   <li>x runs from the left wall (0) to the right wall ({@code width});</li>
 *   <li>y from the TV wall (0) to the back wall ({@code depth});</li>
 *   <li>z from the floor (0) to the ceiling ({@code height}).</li>
 * </ul>
 * The TV hangs on the TV wall: its screen's centre at ({@code x}, {@code
 * offset}, {@code z}), facing the room. A strip is a path of points on the
 * walls, ceiling, floor or the back of the TV, from its first LED (the data
 * input end) to its last; the LEDs are spread evenly along it.
 *
 * <h3>Which colour an LED shows</h3>
 * The LED is projected onto the TV's plane, and a ray from the screen's
 * centre towards it picks the point on the picture's border it shows: an LED
 * up and to the left of the TV shows the top-left corner, one on the right
 * wall the right edge, one on the ceiling straight above the TV the middle of
 * the top edge. That holds for LEDs anywhere in the room, so the light wraps
 * round the room as the picture would if it carried on past its edges. LEDs
 * nearly straight in front of or behind the screen (the middle of the back
 * wall) have no clear edge, so they fade towards the whole picture's average.
 */
final class Ambilight {

    private Ambilight() { }

    /** WLED's realtime UDP port ("Receive UDP realtime", on by default). */
    static final int WLED_PORT = 21324;
    /** WLED's DNRGB protocol: start index and RGB, at most this many LEDs a packet. */
    static final int DNRGB = 4;
    static final int DNRGB_MAX_LEDS = 489;
    /** WLED goes back to its own state this long after the last packet. */
    static final int WLED_TIMEOUT_S = 2;
    /** Off-axis angle under which an LED starts fading to the average colour. */
    static final double FULL_EDGE_DEG = 25;
    /** A pixel brighter than this (any channel, 0..255) is picture, not a bar. */
    static final int BAR_THRESHOLD = 24;
    /** Bars are never thicker than this share of the height (or width). */
    static final double MAX_BAR = 0.3;

    // ------------------------------------------------------------- the model

    static final class Point {
        final double x, y, z;
        Point(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    static final class Room {
        final double width, depth, height;
        Room(double width, double depth, double height) {
            this.width = width; this.depth = depth; this.height = height;
        }
    }

    /** {@code x}, {@code z}: the screen's centre; {@code offset}: out from the wall; the screen's own size. */
    static final class Tv {
        final double x, z, offset, width, height;
        Tv(double x, double z, double offset, double width, double height) {
            this.x = x; this.z = z; this.offset = offset; this.width = width; this.height = height;
        }
    }

    /** {@code start}: the first LED's index on its WLED controller; {@code points}: data end first. */
    static final class Strip {
        final String name, host;
        final int port, start, count;
        final List<Point> points;
        Strip(String name, String host, int port, int start, int count, List<Point> points) {
            this.name = name; this.host = host; this.port = port; this.start = start;
            this.count = count; this.points = points;
        }
    }

    static final class Layout {
        final Room room;
        final Tv tv;
        final List<Strip> strips;
        Layout(Room room, Tv tv, List<Strip> strips) { this.room = room; this.tv = tv; this.strips = strips; }
    }

    /**
     * The picture-to-light settings: {@code brightness} and {@code saturation}
     * in percent (100 = as is), {@code gamma} (1 = none), {@code smoothingMs}
     * (time constant of the fade between frames), {@code edgeDepth} (how far
     * into the picture each LED looks, percent of its width or height), and
     * {@code edgeInset} (how much of the outer edge it skips first, the same
     * units: for pictures with bright borders, menus or logos at the edge).
     */
    static final class Tuning {
        final int brightness, saturation, smoothingMs, edgeDepth, edgeInset;
        final double gamma;
        Tuning(int brightness, int saturation, double gamma, int smoothingMs, int edgeDepth, int edgeInset) {
            this.brightness = brightness; this.saturation = saturation; this.gamma = gamma;
            this.smoothingMs = smoothingMs; this.edgeDepth = edgeDepth; this.edgeInset = edgeInset;
        }
        static final Tuning DEFAULT = new Tuning(100, 120, 1.0, 150, 15, 0);
    }

    // -------------------------------------------------------------- geometry

    /** Each LED's place, spread evenly along the path, the first half a step in from its start. */
    static double[][] ledPositions(Strip s) {
        double[][] out = new double[Math.max(0, s.count)][];
        List<Point> p = s.points;
        if (out.length == 0 || p.isEmpty()) {
            for (int i = 0; i < out.length; i++) out[i] = new double[] {0, 0, 0};
            return out;
        }
        double[] seg = new double[p.size()];   // path length up to each point
        for (int i = 1; i < p.size(); i++) seg[i] = seg[i - 1] + dist(p.get(i - 1), p.get(i));
        double total = seg[seg.length - 1];
        int k = 1;
        for (int i = 0; i < out.length; i++) {
            double at = total * (i + 0.5) / out.length;
            while (k < seg.length - 1 && seg[k] < at) k++;
            if (seg.length == 1 || total == 0) {
                Point a = p.get(0);
                out[i] = new double[] {a.x, a.y, a.z};
                continue;
            }
            Point a = p.get(k - 1), b = p.get(k);
            double len = seg[k] - seg[k - 1];
            double t = len == 0 ? 0 : (at - seg[k - 1]) / len;
            out[i] = new double[] {a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t};
        }
        return out;
    }

    static double length(Strip s) {
        double sum = 0;
        for (int i = 1; i < s.points.size(); i++) sum += dist(s.points.get(i - 1), s.points.get(i));
        return sum;
    }

    private static double dist(Point a, Point b) {
        return Math.sqrt(sq(a.x - b.x) + sq(a.y - b.y) + sq(a.z - b.z));
    }

    private static double sq(double v) { return v * v; }

    /**
     * Where on the picture an LED at (x, y, z) looks: {sx, sy, weight}. sx and
     * sy are a point on the picture's border, 0..1 from its top-left corner;
     * weight is how much of the LED's colour comes from there (1) rather than
     * from the whole picture's average (0).
     */
    static double[] screenPoint(Tv tv, double x, double y, double z) {
        double dr = x - tv.x, du = z - tv.z, dn = y - tv.offset;
        double qx = dr / (tv.width / 2), qy = du / (tv.height / 2);
        double m = Math.max(Math.abs(qx), Math.abs(qy));
        if (m < 1e-9) return new double[] {0.5, 0.5, 0};
        double bx = qx / m, by = qy / m;
        // 90° for anything level with or behind the screen, 0° straight out in front.
        double offAxis = Math.toDegrees(Math.atan2(Math.hypot(dr, du), Math.max(0, dn)));
        double weight = Math.min(1, offAxis / FULL_EDGE_DEG);
        return new double[] {(1 + bx) / 2, (1 - by) / 2, weight};
    }

    /** For every strip, every LED's {sx, sy, weight}. */
    static List<double[][]> map(Layout l) {
        List<double[][]> out = new ArrayList<>();
        for (Strip s : l.strips) {
            double[][] pos = ledPositions(s), m = new double[pos.length][];
            for (int i = 0; i < pos.length; i++) m[i] = screenPoint(l.tv, pos[i][0], pos[i][1], pos[i][2]);
            out.add(m);
        }
        return out;
    }

    // -------------------------------------------------------------- sampling

    /**
     * The pixel box an LED averages, {x0, y0, x1, y1} (x1, y1 exclusive), in
     * a capture whose picture (bars cropped) is {@code crop}: a square-ish
     * patch by the border at the LED's point, {@code depth} (0..1) of the
     * picture's width and height deep, starting {@code inset} (0..1, same
     * units) in from the border; 0 touches it.
     */
    static int[] box(double sx, double sy, int[] crop, double depth, double inset) {
        int cw = crop[2] - crop[0], ch = crop[3] - crop[1];
        double bx = sx * 2 - 1, by = sy * 2 - 1, hs = depth;   // normalised: -1..1 across the picture
        double cx = bx * (1 - hs - 2 * inset), cy = by * (1 - hs - 2 * inset);
        double px = crop[0] + (cx + 1) / 2 * cw, py = crop[1] + (cy + 1) / 2 * ch;
        double hw = Math.max(0.5, hs / 2 * cw), hh = Math.max(0.5, hs / 2 * ch);
        int x0 = clamp((int) Math.floor(px - hw), crop[0], crop[2] - 1);
        int y0 = clamp((int) Math.floor(py - hh), crop[1], crop[3] - 1);
        int x1 = clamp((int) Math.ceil(px + hw), x0 + 1, crop[2]);
        int y1 = clamp((int) Math.ceil(py + hh), y0 + 1, crop[3]);
        return new int[] {x0, y0, x1, y1};
    }

    /**
     * Summed-area tables of an RGBA frame ({@code stride} bytes a row, 4 a
     * pixel), so any box's average costs four lookups however big it is.
     * Each table is (w + 1) × (h + 1), row 0 and column 0 zero.
     */
    static void integrate(byte[] rgba, int w, int h, int stride, int[] r, int[] g, int[] b) {
        int tw = w + 1;
        for (int x = 0; x <= w; x++) { r[x] = 0; g[x] = 0; b[x] = 0; }
        for (int y = 0; y < h; y++) {
            int rr = 0, gg = 0, bb = 0, row = y * stride, t = (y + 1) * tw, up = y * tw;
            r[t] = 0; g[t] = 0; b[t] = 0;
            for (int x = 0; x < w; x++) {
                int p = row + x * 4;
                rr += rgba[p] & 0xff; gg += rgba[p + 1] & 0xff; bb += rgba[p + 2] & 0xff;
                r[t + x + 1] = r[up + x + 1] + rr;
                g[t + x + 1] = g[up + x + 1] + gg;
                b[t + x + 1] = b[up + x + 1] + bb;
            }
        }
    }

    /** The box's average colour into {@code out} (0..255 floats), from {@link #integrate}'s tables. */
    static void average(int[] r, int[] g, int[] b, int w, int[] box, float[] out) {
        int tw = w + 1, a = box[1] * tw + box[0], bq = box[1] * tw + box[2];
        int c = box[3] * tw + box[0], d = box[3] * tw + box[2];
        float n = (box[2] - box[0]) * (box[3] - box[1]);
        out[0] = (r[d] - r[bq] - r[c] + r[a]) / n;
        out[1] = (g[d] - g[bq] - g[c] + g[a]) / n;
        out[2] = (b[d] - b[bq] - b[c] + b[a]) / n;
    }

    // ------------------------------------------------------------ black bars

    /**
     * The picture inside letterbox or pillarbox bars: {x0, y0, x1, y1}, the
     * same bar top and bottom (and left and right), so subtitles in one bar
     * don't shift it. Null when the whole frame is black (nothing to go by).
     */
    static int[] content(byte[] rgba, int w, int h, int stride) {
        int top = 0;
        while (top < h && darkRow(rgba, w, stride, top)) top++;
        if (top == h) return null;
        int bottom = 0;
        while (bottom < h && darkRow(rgba, w, stride, h - 1 - bottom)) bottom++;
        int left = 0;
        while (left < w && darkColumn(rgba, h, stride, left)) left++;
        int right = 0;
        while (right < w && darkColumn(rgba, h, stride, w - 1 - right)) right++;
        int bar = Math.min(Math.min(top, bottom), (int) (h * MAX_BAR));
        int side = Math.min(Math.min(left, right), (int) (w * MAX_BAR));
        return new int[] {side, bar, w - side, h - bar};
    }

    private static boolean darkRow(byte[] px, int w, int stride, int y) {
        int bright = 0;
        for (int x = 0, p = y * stride; x < w; x++, p += 4) if (bright(px, p) && ++bright > w / 50) return false;
        return true;
    }

    private static boolean darkColumn(byte[] px, int h, int stride, int x) {
        int bright = 0;
        for (int y = 0, p = x * 4; y < h; y++, p += stride) if (bright(px, p) && ++bright > h / 50) return false;
        return true;
    }

    private static boolean bright(byte[] px, int p) {
        return (px[p] & 0xff) > BAR_THRESHOLD || (px[p + 1] & 0xff) > BAR_THRESHOLD
                || (px[p + 2] & 0xff) > BAR_THRESHOLD;
    }

    /**
     * Follows the bars without flicker: a new crop is taken only once it has
     * held for {@code frames} frames in a row, and an all-black frame (a fade,
     * a dark scene) keeps the crop there was.
     */
    static final class Bars {
        private final int frames;
        private int[] current, candidate;
        private int seen;

        Bars(int w, int h, int frames) {
            this.frames = frames;
            current = new int[] {0, 0, w, h};
        }

        int[] update(int[] found) {
            if (found == null || java.util.Arrays.equals(found, current)) {
                candidate = null;
                return current;
            }
            if (candidate != null && java.util.Arrays.equals(found, candidate)) {
                if (++seen >= frames) { current = found; candidate = null; }
            } else {
                candidate = found;
                seen = 1;
            }
            return current;
        }
    }

    // ------------------------------------------------------------- auto edge

    /**
     * "How far into the picture" and "Skip the outer edge", tuned from the
     * picture itself, a frame at a time, from {@link #integrate}'s tables.
     *
     * <p>Skip: the picture's edge moves with the content, but a channel logo,
     * a game's HUD or a menu frame stays put. Each {@value #STEP}%-wide ring
     * in from the border (out to {@value #MAX_INSET}%) is split into
     * segments, and how much each changes from frame to frame is followed
     * over a few seconds. Rings much stiller than the picture inside them are
     * skipped. A still picture (paused, a menu) says nothing, so the skip is
     * kept as it was.
     *
     * <p>Depth: when the edge is dark but the middle isn't (a dark scene lit
     * in the centre), the patches reach deeper, up to {@value #MAX_DEPTH}%,
     * so the LEDs still get colour; a bright, colourful edge keeps them
     * shallow ({@value #MIN_DEPTH}%), so each LED follows its own part of
     * the picture. Both move slowly and in steps, so the room doesn't pump.
     */
    static final class AutoEdge {
        static final int STEP = 2, MAX_INSET = 20, MIN_DEPTH = 10, MAX_DEPTH = 40, DEPTH_STEP = 5;
        private static final int RINGS = MAX_INSET / STEP, SEGMENTS = 4;   // per side
        /** Motion (mean luma change a frame) below this in the reference band: a still picture. */
        private static final double STILL = 1.0;
        /** A ring moving less than this share of the reference band's motion is a static border. */
        private static final double STATIC_SHARE = 0.3;
        /** A new skip must hold this long before it's taken. */
        private static final long HOLD_MS = 1500;
        private static final double MOTION_TAU_MS = 3000, DEPTH_TAU_MS = 2000;

        private final float[][] last = new float[RINGS + 1][4 * SEGMENTS];
        private final double[] motion = new double[RINGS + 1];   // ring RINGS: the reference band, 20–30% in
        private boolean primed;
        private double depthF;
        private int depth, inset, pendingInset = -1;
        private long pendingMs;
        private final float[] px = new float[3];

        AutoEdge(int depth, int inset) {
            this.depth = clamp(depth, MIN_DEPTH, MAX_DEPTH);
            this.inset = clamp(inset, 0, MAX_INSET);
            depthF = this.depth;
        }

        int depth() { return depth; }
        int inset() { return inset; }

        /** One frame; true when the depth or the skip changed (the boxes need making again). */
        boolean update(int[] r, int[] g, int[] b, int w, int[] crop, long dtMs) {
            int cw = crop[2] - crop[0], ch = crop[3] - crop[1];
            if (cw < 20 || ch < 20 || dtMs <= 0) return false;
            double a = 1 - Math.exp(-dtMs / MOTION_TAU_MS);
            for (int k = 0; k <= RINGS; k++) {
                double from = k * STEP / 100.0, to = k < RINGS ? (k + 1) * STEP / 100.0 : 0.3;
                double diff = 0;
                for (int side = 0, i = 0; side < 4; side++) {
                    for (int sg = 0; sg < SEGMENTS; sg++, i++) {
                        float l = luma(r, g, b, w, segment(crop, side, sg, from, to));
                        diff += Math.abs(l - last[k][i]);
                        last[k][i] = l;
                    }
                }
                if (primed) motion[k] += a * (diff / (4 * SEGMENTS) - motion[k]);
            }
            primed = true;
            boolean changed = false;

            double ref = motion[RINGS];
            if (ref > STILL) {
                int k = 0;
                while (k < RINGS && motion[k] < STATIC_SHARE * ref) k++;
                int want = k * STEP;
                if (want == inset) {
                    pendingInset = -1;
                } else if (want != pendingInset) {
                    pendingInset = want;
                    pendingMs = 0;
                } else if ((pendingMs += dtMs) >= HOLD_MS) {
                    inset = want;
                    pendingInset = -1;
                    changed = true;
                }
            }

            // Edge band: from the skip, 15% deep, all round; centre: the middle 40%.
            double i0 = inset / 100.0;
            float edge = ringLuma(r, g, b, w, crop, i0, i0 + 0.15), mid = luma(r, g, b, w, rect(crop, 0.3));
            if (mid > 8) {
                double dark = Math.max(0, Math.min(1, 1 - edge / mid));
                double want = MIN_DEPTH + (MAX_DEPTH - MIN_DEPTH) * dark;
                depthF += (1 - Math.exp(-dtMs / DEPTH_TAU_MS)) * (want - depthF);
                int d = clamp((int) Math.round(depthF / DEPTH_STEP) * DEPTH_STEP, MIN_DEPTH, MAX_DEPTH);
                if (d != depth) { depth = d; changed = true; }
            }
            return changed;
        }

        /** Segment {@code sg} of a side (0 top, 1 bottom, 2 left, 3 right), between {@code from} and {@code to} in. */
        private static int[] segment(int[] c, int side, int sg, double from, double to) {
            int cw = c[2] - c[0], ch = c[3] - c[1];
            if (side < 2) {
                int x0 = c[0] + cw * sg / SEGMENTS, x1 = c[0] + cw * (sg + 1) / SEGMENTS;
                int a = (int) Math.floor(from * ch), z = Math.max(a + 1, (int) Math.round(to * ch));
                return side == 0 ? new int[] {x0, c[1] + a, x1, c[1] + z} : new int[] {x0, c[3] - z, x1, c[3] - a};
            }
            int y0 = c[1] + ch * sg / SEGMENTS, y1 = c[1] + ch * (sg + 1) / SEGMENTS;
            int a = (int) Math.floor(from * cw), z = Math.max(a + 1, (int) Math.round(to * cw));
            return side == 2 ? new int[] {c[0] + a, y0, c[0] + z, y1} : new int[] {c[2] - z, y0, c[2] - a, y1};
        }

        private static int[] rect(int[] c, double in) {
            int dx = (int) Math.round(in * (c[2] - c[0])), dy = (int) Math.round(in * (c[3] - c[1]));
            return new int[] {c[0] + dx, c[1] + dy, Math.max(c[0] + dx + 1, c[2] - dx), Math.max(c[1] + dy + 1, c[3] - dy)};
        }

        /** Mean luma of the ring between {@code from} and {@code to} in from the border. */
        private float ringLuma(int[] r, int[] g, int[] b, int w, int[] c, double from, double to) {
            int[] o = rect(c, from), i = rect(c, Math.min(to, 0.49));
            double ao = area(o), ai = area(i);
            if (ao <= ai) return luma(r, g, b, w, o);
            return (float) ((luma(r, g, b, w, o) * ao - luma(r, g, b, w, i) * ai) / (ao - ai));
        }

        private static double area(int[] box) { return (double) (box[2] - box[0]) * (box[3] - box[1]); }

        private float luma(int[] r, int[] g, int[] b, int w, int[] box) {
            average(r, g, b, w, box, px);
            return 0.299f * px[0] + 0.587f * px[1] + 0.114f * px[2];
        }
    }

    // ---------------------------------------------------------------- colour

    /**
     * The edge colour mixed with the average by {@code weight}, then
     * saturation, gamma and brightness; into {@code out}, 0..255.
     */
    static void colour(float[] edge, float[] avg, double weight, Tuning t, float[] out) {
        for (int i = 0; i < 3; i++) out[i] = (float) (edge[i] * weight + avg[i] * (1 - weight));
        float luma = 0.299f * out[0] + 0.587f * out[1] + 0.114f * out[2];
        float sat = t.saturation / 100f, bri = t.brightness / 100f;
        for (int i = 0; i < 3; i++) {
            float v = luma + (out[i] - luma) * sat;
            v = Math.max(0, Math.min(255, v));
            if (t.gamma != 1) v = (float) (255 * Math.pow(v / 255, t.gamma));
            out[i] = Math.max(0, Math.min(255, v * bri));
        }
    }

    /**
     * Eases each value towards its target: an exponential fade with time
     * constant {@code tauMs}, so it behaves the same at any frame rate.
     */
    static void smooth(float[] state, float[] target, long dtMs, int tauMs) {
        float k = tauMs <= 0 ? 1 : (float) (1 - Math.exp(-(double) dtMs / tauMs));
        for (int i = 0; i < state.length; i++) state[i] += (target[i] - state[i]) * k;
    }

    // -------------------------------------------------------- test patterns

    /**
     * The test pattern's colour for a picture point: hue by its angle round
     * the centre (right edge red, then yellow, green at the top-left, blue,
     * magenta), faded to grey by the LED's weight as the real thing would be.
     */
    static int testColour(double sx, double sy, double weight) {
        double angle = Math.toDegrees(Math.atan2(1 - 2 * sy, 2 * sx - 1));
        int rgb = hsv((angle + 360) % 360, 1, 1);
        int grey = 96;
        int r = (int) Math.round(((rgb >> 16) & 0xff) * weight + grey * (1 - weight));
        int g = (int) Math.round(((rgb >> 8) & 0xff) * weight + grey * (1 - weight));
        int b = (int) Math.round((rgb & 0xff) * weight + grey * (1 - weight));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Finding a strip's ends: its first LED green, its last red, and a white
     * dot running from first to last once every {@code periodMs}; the rest
     * off. Shows which end is which and whether the count is right.
     */
    static int chaseColour(int i, int count, long nowMs, long periodMs) {
        if (i == 0) return 0x00ff00;
        if (i == count - 1) return 0xff0000;
        double head = (nowMs % periodMs) / (double) periodMs * count;
        double behind = head - i;
        if (behind >= 0 && behind < 4) return grey((int) (255 * (1 - behind / 4)));
        return 0;
    }

    private static int grey(int v) { return (v << 16) | (v << 8) | v; }

    static int hsv(double h, double s, double v) {
        double c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return ((int) Math.round((r + m) * 255) << 16) | ((int) Math.round((g + m) * 255) << 8)
                | (int) Math.round((b + m) * 255);
    }

    // ----------------------------------------------------------------- WLED

    /**
     * WLED DNRGB packets for {@code rgb} (3 bytes a LED) starting at LED
     * {@code start}: [4, timeout s, start hi, start lo, r, g, b, …], split
     * every {@link #DNRGB_MAX_LEDS} LEDs.
     */
    static List<byte[]> dnrgb(int start, byte[] rgb, int timeoutS) {
        int leds = rgb.length / 3;
        if (leds == 0) return Collections.emptyList();
        List<byte[]> out = new ArrayList<>();
        for (int from = 0; from < leds; from += DNRGB_MAX_LEDS) {
            int n = Math.min(DNRGB_MAX_LEDS, leds - from), index = start + from;
            byte[] p = new byte[4 + n * 3];
            p[0] = DNRGB;
            p[1] = (byte) timeoutS;
            p[2] = (byte) (index >> 8);
            p[3] = (byte) index;
            System.arraycopy(rgb, from * 3, p, 4, n * 3);
            out.add(p);
        }
        return out;
    }

    /** "192.168.1.50" or "192.168.1.50:21324" → {host, port}. */
    static String[] hostPort(String s) {
        s = s.trim();
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            try {
                int port = Integer.parseInt(s.substring(colon + 1));
                return new String[] {s.substring(0, colon), String.valueOf(port)};
            } catch (NumberFormatException ignored) { }
        }
        return new String[] {s, String.valueOf(WLED_PORT)};
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
