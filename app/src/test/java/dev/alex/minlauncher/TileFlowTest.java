package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pinned banners at the SHIELD's real geometry: 1920 px wide at density 2,
 * so the numbers below are MainActivity's dp values doubled.
 */
public class TileFlowTest {

    private static final int SCREEN = 1920;
    private static final int GUTTER = 128;            // content paddingLeft, 64dp
    private static final int FLOW_PAD = 16;           // TileFlow padding, 8dp
    private static final int TILE_W = 464, TILE_H = 268;   // 224x126dp image + 4dp padding
    private static final int MARGIN = 32;             // right and bottom margin, 16dp
    private static final float FOCUS_SCALE = 1.06f;

    /** Absolute x where content must end, as MainActivity computes it. */
    private static int boundary(int percent) {
        int rightPad = Math.max(GUTTER, SCREEN - SCREEN * percent / 100);
        return SCREEN - rightPad;
    }

    private static int[][] layout(int tiles, int percent) {
        int[][] boxes = new int[tiles][];
        for (int i = 0; i < tiles; i++) boxes[i] = new int[] { TILE_W, TILE_H, 0, 0, MARGIN, MARGIN };
        // TileFlow spans the content area; its own padding is inside that.
        return TileFlow.arrange(boxes, GUTTER + FLOW_PAD, 0, boundary(percent) - FLOW_PAD);
    }

    @Test public void moreTilesThanFitWrapInsideTheBoundary() {
        int tiles = 7;
        int[][] at = layout(tiles, 65);
        int edge = boundary(65);
        assertEquals(1248, edge);   // 65% of 1920
        int rows = 1;
        for (int i = 0; i < tiles; i++) {
            int right = at[i][0] + TILE_W;
            // Even scaled up for focus (from its centre), a tile stays left of the boundary.
            int grown = Math.round(TILE_W * (FOCUS_SCALE - 1) / 2);
            assertTrue("tile " + i + " ends at " + right, right + grown <= edge);
            if (i > 0 && at[i][1] > at[i - 1][1]) rows++;
        }
        assertEquals(4, rows);   // two per row at 65%
        assertEquals(at[0][0], at[2][0]);   // each row starts at the left again
    }

    @Test public void widerBoundaryFitsMorePerRow() {
        int[][] at = layout(7, 100);
        assertEquals(at[0][1], at[2][1]);   // three on the first row
        assertTrue(at[3][1] > at[0][1]);
    }

    @Test public void oneTileAlwaysPlacedEvenIfTooWide() {
        int[][] at = TileFlow.arrange(new int[][] { { 500, 100, 0, 0, 0, 0 } }, 0, 0, 300);
        assertEquals(0, at[0][0]);
        assertEquals(100, at[1][1]);
    }
}
