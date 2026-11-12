package dev.alex.minlauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class AmbilightTest {

    private static final double EPS = 1e-6;
    /** A 65" TV centred on a 450 cm wide TV wall, 110 cm up, 5 cm out. */
    private static final Ambilight.Tv TV = new Ambilight.Tv(225, 110, 5, 144, 81);

    private static Ambilight.Strip strip(int count, Ambilight.Point... points) {
        return new Ambilight.Strip("s", "10.0.0.2", Ambilight.WLED_PORT, 0, count, Arrays.asList(points));
    }

    private static Ambilight.Point p(double x, double y, double z) { return new Ambilight.Point(x, y, z); }

    // ------------------------------------------------------------- geometry

    @Test
    public void ledsSpreadEvenlyHalfAStepIn() {
        double[][] leds = Ambilight.ledPositions(strip(10, p(0, 0, 0), p(100, 0, 0)));
        assertEquals(5, leds[0][0], EPS);
        assertEquals(15, leds[1][0], EPS);
        assertEquals(95, leds[9][0], EPS);
    }

    @Test
    public void ledsFollowTheCorner() {
        // Along the floor of the TV wall, then up the right wall: 100 + 100 cm, 4 LEDs.
        double[][] leds = Ambilight.ledPositions(strip(4, p(350, 0, 0), p(450, 0, 0), p(450, 100, 0)));
        assertArrayEquals(new double[] {375, 0, 0}, leds[0], EPS);
        assertArrayEquals(new double[] {425, 0, 0}, leds[1], EPS);
        assertArrayEquals(new double[] {450, 25, 0}, leds[2], EPS);
        assertArrayEquals(new double[] {450, 75, 0}, leds[3], EPS);
        assertEquals(200, Ambilight.length(strip(4, p(350, 0, 0), p(450, 0, 0), p(450, 100, 0))), EPS);
    }

    @Test
    public void aStripWithOnePointHasAllItsLedsThere() {
        double[][] leds = Ambilight.ledPositions(strip(3, p(10, 20, 30)));
        for (double[] l : leds) assertArrayEquals(new double[] {10, 20, 30}, l, EPS);
    }

    @Test
    public void ledsBesideTheTvShowThatEdge() {
        double[] right = Ambilight.screenPoint(TV, 450, 200, 110);    // right wall, at screen height
        assertArrayEquals(new double[] {1, 0.5, 1}, right, EPS);
        double[] left = Ambilight.screenPoint(TV, 0, 100, 110);
        assertArrayEquals(new double[] {0, 0.5, 1}, left, EPS);
        double[] above = Ambilight.screenPoint(TV, 225, 0, 245);      // TV wall, at the ceiling
        assertArrayEquals(new double[] {0.5, 0, 1}, above, EPS);
        double[] below = Ambilight.screenPoint(TV, 225, 50, 0);       // floor, in front
        assertEquals(0.5, below[0], EPS);
        assertEquals(1, below[1], EPS);
    }

    @Test
    public void ledsOnTheDiagonalShowTheCorner() {
        // Up and left exactly along the screen's diagonal, on the TV's back.
        double[] corner = Ambilight.screenPoint(TV, 225 - 72, 5, 110 + 40.5);
        assertEquals(0, corner[0], EPS);
        assertEquals(0, corner[1], EPS);
        assertEquals(1, corner[2], EPS);
    }

    @Test
    public void theBackWallsMiddleFadesToTheAverage() {
        double[] middle = Ambilight.screenPoint(TV, 225, 400, 110);
        assertEquals(0, middle[2], EPS);
        // Far to the side of the back wall: an edge again, the left one on the left.
        double[] side = Ambilight.screenPoint(TV, 0, 400, 110);
        assertEquals(0, side[0], EPS);
        assertTrue(side[2] > 0.99);
        // Partway: some of each.
        double[] partway = Ambilight.screenPoint(TV, 225 + 80, 400, 110);
        assertTrue(partway[2] > 0.3 && partway[2] < 0.9);
    }

    // ------------------------------------------------------------- sampling

    @Test
    public void boxesTouchTheBorderAndStayInside() {
        int[] crop = {0, 10, 160, 80};   // letterboxed: 70 rows of picture
        int[] right = Ambilight.box(1, 0.5, crop, 0.15, 0);
        assertEquals(160, right[2]);
        assertEquals(136, right[0]);   // 15% of the width deep
        int[] top = Ambilight.box(0.5, 0, crop, 0.15, 0);
        assertEquals(10, top[1]);
        int[] corner = Ambilight.box(0, 1, crop, 0.15, 0);
        assertEquals(0, corner[0]);
        assertEquals(80, corner[3]);
        for (int[] b : new int[][] {right, top, corner}) {
            assertTrue(b[0] < b[2] && b[1] < b[3]);
            assertTrue(b[0] >= crop[0] && b[2] <= crop[2] && b[1] >= crop[1] && b[3] <= crop[3]);
        }
    }

    @Test
    public void insetBoxesSkipTheOuterEdgeAndKeepTheirDepth() {
        int[] crop = {0, 10, 160, 80};
        int[] right = Ambilight.box(1, 0.5, crop, 0.15, 0.10);
        assertEquals(144, right[2]);   // 10% of the width (16 px) skipped
        assertEquals(120, right[0]);   // then the same 15% (24 px) deep
        int[] top = Ambilight.box(0.5, 0, crop, 0.15, 0.10);
        assertEquals(17, top[1]);      // 10% of 70 rows in from the bar
        int[] left = Ambilight.box(0, 0.5, crop, 0.15, 0.10);
        assertEquals(16, left[0]);
        assertEquals(40, left[2], 1);  // 40.000…04 rounds up: a pixel either way
        for (int[] b : new int[][] {right, top, left}) {
            assertTrue(b[0] >= crop[0] && b[2] <= crop[2] && b[1] >= crop[1] && b[3] <= crop[3]);
        }
    }

    /** A w×h RGBA frame, rows padded to {@code stride}, painted by {@code paint}. */
    private interface Paint { int at(int x, int y); }

    private static byte[] frame(int w, int h, int stride, Paint paint) {
        byte[] f = new byte[stride * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = paint.at(x, y), p = y * stride + x * 4;
                f[p] = (byte) (c >> 16); f[p + 1] = (byte) (c >> 8); f[p + 2] = (byte) c; f[p + 3] = (byte) 0xff;
            }
        }
        return f;
    }

    @Test
    public void boxAveragesFromTheTables() {
        int w = 8, h = 4, stride = w * 4 + 16;
        // Left half red, right half blue.
        byte[] f = frame(w, h, stride, (x, y) -> x < 4 ? 0xff0000 : 0x0000ff);
        int[] r = new int[(w + 1) * (h + 1)], g = new int[r.length], b = new int[r.length];
        Ambilight.integrate(f, w, h, stride, r, g, b);
        float[] out = new float[3];
        Ambilight.average(r, g, b, w, new int[] {0, 0, 4, 4}, out);
        assertArrayEquals(new float[] {255, 0, 0}, out, 0.01f);
        Ambilight.average(r, g, b, w, new int[] {2, 1, 6, 3}, out);
        assertArrayEquals(new float[] {127.5f, 0, 127.5f}, out, 0.01f);
    }

    // ----------------------------------------------------------- black bars

    @Test
    public void letterboxBarsAreFound() {
        int w = 160, h = 90, stride = w * 4;
        byte[] f = frame(w, h, stride, (x, y) -> y < 11 || y >= 79 ? 0x050505 : 0x808080);
        assertArrayEquals(new int[] {0, 11, 160, 79}, Ambilight.content(f, w, h, stride));
    }

    @Test
    public void subtitlesInTheBottomBarDontMoveIt() {
        int w = 160, h = 90, stride = w * 4;
        byte[] f = frame(w, h, stride, (x, y) -> {
            if (y >= 11 && y < 79) return 0x808080;
            if (y >= 82 && y < 86 && x >= 40 && x < 120) return 0xffffff;   // white subtitle text
            return 0;
        });
        // The top bar (11) and the bottom one cut short by the text (4): the smaller of the two.
        assertArrayEquals(new int[] {0, 4, 160, 86}, Ambilight.content(f, w, h, stride));
    }

    @Test
    public void pillarboxBarsAreFound() {
        int w = 160, h = 90, stride = w * 4;
        byte[] f = frame(w, h, stride, (x, y) -> x < 20 || x >= 140 ? 0 : 0x3060a0);
        assertArrayEquals(new int[] {20, 0, 140, 90}, Ambilight.content(f, w, h, stride));
    }

    @Test
    public void anAllBlackFrameGivesNothing() {
        int w = 16, h = 9, stride = w * 4;
        assertNull(Ambilight.content(frame(w, h, stride, (x, y) -> 0x101010), w, h, stride));
    }

    @Test
    public void barsMustHoldBeforeTheCropFollows() {
        Ambilight.Bars bars = new Ambilight.Bars(160, 90, 3);
        int[] full = {0, 0, 160, 90}, boxed = {0, 11, 160, 79};
        assertArrayEquals(full, bars.update(boxed));
        assertArrayEquals(full, bars.update(boxed));
        assertArrayEquals(boxed, bars.update(boxed));
        // A black frame keeps it; a one-frame blip doesn't move it.
        assertArrayEquals(boxed, bars.update(null));
        assertArrayEquals(boxed, bars.update(full));
        assertArrayEquals(boxed, bars.update(boxed));
    }

    // --------------------------------------------------------------- colour

    @Test
    public void colourMixesEdgeAndAverageByWeight() {
        Ambilight.Tuning plain = new Ambilight.Tuning(100, 100, 1, 0, 15, 0);
        float[] out = new float[3];
        Ambilight.colour(new float[] {200, 0, 0}, new float[] {0, 0, 200}, 0.25, plain, out);
        assertArrayEquals(new float[] {50, 0, 150}, out, 0.01f);
    }

    @Test
    public void saturationAndBrightness() {
        float[] out = new float[3];
        Ambilight.colour(new float[] {100, 100, 100}, new float[3], 1,
                new Ambilight.Tuning(50, 300, 1, 0, 15, 0), out);
        assertArrayEquals(new float[] {50, 50, 50}, out, 0.01f);   // grey has nothing to saturate
        Ambilight.colour(new float[] {200, 100, 100}, new float[3], 1,
                new Ambilight.Tuning(100, 0, 1, 0, 15, 0), out);
        assertEquals(out[0], out[1], 0.01f);                       // no saturation: grey
    }

    @Test
    public void smoothingIsFrameRateIndependent() {
        float[] a = {0}, b = {0};
        Ambilight.smooth(a, new float[] {100}, 100, 100);
        for (int i = 0; i < 10; i++) Ambilight.smooth(b, new float[] {100}, 10, 100);
        assertEquals(a[0], b[0], 0.01f);
        assertEquals(63.2f, a[0], 0.1f);
        float[] c = {0};
        Ambilight.smooth(c, new float[] {100}, 16, 0);
        assertEquals(100, c[0], 0.01f);
    }

    @Test
    public void testPatternGoesRoundTheBorder() {
        assertEquals(0xff0000, Ambilight.testColour(1, 0.5, 1));    // right: red
        assertEquals(0x0000ff, Ambilight.testColour(0.067, 1, 1) & 0x0000ff); // bottom-left: blue
        assertEquals(0x606060, Ambilight.testColour(0.5, 0.5, 0));  // no edge: grey
    }

    @Test
    public void chaseMarksBothEnds() {
        assertEquals(0x00ff00, Ambilight.chaseColour(0, 50, 0, 4000));
        assertEquals(0xff0000, Ambilight.chaseColour(49, 50, 0, 4000));
        assertEquals(0xffffff, Ambilight.chaseColour(25, 50, 2000, 4000));
        assertEquals(0, Ambilight.chaseColour(10, 50, 2000, 4000));
    }

    // ----------------------------------------------------------------- WLED

    @Test
    public void dnrgbSplitsLongStrips() {
        byte[] rgb = new byte[500 * 3];
        rgb[489 * 3] = 7;   // the first LED of the second packet
        List<byte[]> packets = Ambilight.dnrgb(300, rgb, 2);
        assertEquals(2, packets.size());
        byte[] a = packets.get(0), b = packets.get(1);
        assertEquals(4 + 489 * 3, a.length);
        assertArrayEquals(new byte[] {4, 2, 1, 44}, Arrays.copyOf(a, 4));       // start 300
        assertEquals(4 + 11 * 3, b.length);
        assertArrayEquals(new byte[] {4, 2, 3, 21}, Arrays.copyOf(b, 4));       // start 789
        assertEquals(7, b[4]);
    }

    @Test
    public void hostsTakeAnOptionalPort() {
        assertArrayEquals(new String[] {"192.168.1.50", "21324"}, Ambilight.hostPort("192.168.1.50"));
        assertArrayEquals(new String[] {"192.168.1.50", "4048"}, Ambilight.hostPort(" 192.168.1.50:4048 "));
        assertArrayEquals(new String[] {"wled-tv.lan", "21324"}, Ambilight.hostPort("wled-tv.lan"));
    }
}
