package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class AutoEdgeTest {

    private static final int W = 160, H = 90;
    private static final int[] CROP = {0, 0, W, H};

    /** A picture of 16 px blocks in random greys that change every frame. */
    interface Px { int at(int x, int y, int[][] blocks); }

    private static int run(Ambilight.AutoEdge a, int frames, Px px, boolean moving) {
        Random rnd = new Random(7);
        int[][] blocks = new int[W / 16 + 1][H / 16 + 1];
        int[] r = new int[(W + 1) * (H + 1)], g = new int[r.length], b = new int[r.length];
        byte[] rgba = new byte[W * H * 4];
        for (int f = 0; f < frames; f++) {
            if (moving || f == 0) for (int[] col : blocks) for (int j = 0; j < col.length; j++) col[j] = 40 + rnd.nextInt(200);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int v = px.at(x, y, blocks), p = (y * W + x) * 4;
                rgba[p] = rgba[p + 1] = rgba[p + 2] = (byte) v;
                rgba[p + 3] = (byte) 255;
            }
            Ambilight.integrate(rgba, W, H, W * 4, r, g, b);
            a.update(r, g, b, W, CROP, 33);
        }
        return f(a);
    }

    private static int f(Ambilight.AutoEdge a) { return a.inset(); }

    private static int block(int x, int y, int[][] blocks) { return blocks[x / 16][y / 16]; }

    @Test
    public void aStillFrameRoundAMovingPictureIsSkipped() {
        Ambilight.AutoEdge a = new Ambilight.AutoEdge(15, 0);
        // A white frame 6% deep that never changes, like a channel's border.
        int inset = run(a, 300, (x, y, bl) -> x < W * 0.06 || x >= W * 0.94 || y < H * 0.06 || y >= H * 0.94
                ? 250 : block(x, y, bl), true);
        assertTrue("inset " + inset, inset >= 4 && inset <= 8);
    }

    @Test
    public void aPictureMovingRightToItsEdgeIsntSkipped() {
        Ambilight.AutoEdge a = new Ambilight.AutoEdge(15, 0);
        assertEquals(0, run(a, 300, AutoEdgeTest::block, true));
    }

    @Test
    public void aStillPictureKeepsTheSkipItHad() {
        Ambilight.AutoEdge a = new Ambilight.AutoEdge(15, 6);
        assertEquals(6, run(a, 300, AutoEdgeTest::block, false));
    }

    @Test
    public void darkEdgesWithALitMiddleReachDeeperAndBrightEdgesStayShallow() {
        Ambilight.AutoEdge dark = new Ambilight.AutoEdge(15, 0);
        run(dark, 300, (x, y, bl) -> Math.abs(x - W / 2) < W * 0.25 && Math.abs(y - H / 2) < H * 0.25 ? 220 : 6, false);
        assertEquals(Ambilight.AutoEdge.MAX_DEPTH, dark.depth());

        Ambilight.AutoEdge bright = new Ambilight.AutoEdge(30, 0);
        run(bright, 300, (x, y, bl) -> 200, false);
        assertEquals(Ambilight.AutoEdge.MIN_DEPTH, bright.depth());
    }
}
