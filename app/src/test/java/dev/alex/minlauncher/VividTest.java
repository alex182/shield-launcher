package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VividTest {

    private static final int W = 20, H = 10;

    interface Px { int[] at(int x, int y); }

    /** {@link Ambilight#dominant} over the whole frame; null when it says grey. */
    private static float[] dominant(Px px) {
        byte[] rgba = new byte[W * H * 4];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int[] c = px.at(x, y);
            int p = (y * W + x) * 4;
            rgba[p] = (byte) c[0]; rgba[p + 1] = (byte) c[1]; rgba[p + 2] = (byte) c[2]; rgba[p + 3] = (byte) 255;
        }
        byte[] bin = new byte[W * H], chroma = new byte[W * H];
        Ambilight.vivid(rgba, W, H, W * 4, bin, chroma);
        float[] out = new float[3];
        boolean ok = Ambilight.dominant(rgba, W, W * 4, bin, chroma, new int[] {0, 0, W, H},
                new double[Ambilight.HUE_BINS * 4], out);
        return ok ? out : null;
    }

    @Test
    public void streaksOfSeveralColoursGiveOneOfThemNotTheirMud() {
        // Green streaks over a little more area than purple ones: the mean would be a grey-ish teal.
        float[] c = dominant((x, y) -> x % 5 < 3 ? new int[] {20, 200, 30} : new int[] {150, 20, 200});
        assertTrue(c[1] > 150 && c[0] < 40 && c[2] < 50);
    }

    @Test
    public void strongColourOutweighsALotOfFaintColour() {
        // Most of the patch faintly warm, a quarter strongly blue.
        float[] c = dominant((x, y) -> x < 5 ? new int[] {20, 40, 230} : new int[] {120, 105, 100});
        assertTrue(c[2] > c[0] && c[2] > c[1] && c[2] > 150);
    }

    @Test
    public void greyUsesTheMean() {
        assertEquals(null, dominant((x, y) -> new int[] {128 + x, 128 + x, 130 + x}));
        assertEquals(null, dominant((x, y) -> new int[] {0, 0, 0}));
    }

    @Test
    public void aSmallBrightThingInADarkPatchStaysDim() {
        float[] c = dominant((x, y) -> x < 2 ? new int[] {255, 0, 0} : new int[] {0, 0, 0});   // 10% red
        assertFalse(c == null);   // red, but
        assertTrue("dim, was " + c[0], c[0] < 100 && c[0] > 20);
    }
}
