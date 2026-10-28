package dev.alex.minlauncher;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays children out left to right and wraps to a new row at its own width,
 * so pinned banners never run past the content area into the background's
 * focal element. Children use {@link MarginLayoutParams}.
 */
final class TileFlow extends ViewGroup {

    /** Fits in a MeasureSpec's 30-bit size, for when the parent sets no width. */
    private static final int UNBOUNDED = (1 << 29) - 1;

    private int widestRow;

    TileFlow(Context c) { super(c); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        boolean unbounded = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED;
        int width = unbounded ? UNBOUNDED : MeasureSpec.getSize(widthSpec);
        int height = place(width, false);
        setMeasuredDimension(unbounded ? widestRow : width, resolveSize(height, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        place(r - l, true);
    }

    /** Measures (and with {@code layout}, positions) children; returns the total height. */
    private int place(int width, boolean layout) {
        int left = getPaddingLeft(), right = width - getPaddingRight();
        List<View> kids = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() != GONE) kids.add(c);
        }
        int[][] boxes = new int[kids.size()][];
        for (int i = 0; i < kids.size(); i++) {
            View c = kids.get(i);
            MarginLayoutParams lp = (MarginLayoutParams) c.getLayoutParams();
            if (!layout) {
                c.measure(MeasureSpec.makeMeasureSpec(Math.max(0, right - left - lp.leftMargin - lp.rightMargin),
                                MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
            boxes[i] = new int[] { c.getMeasuredWidth(), c.getMeasuredHeight(),
                    lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin };
        }
        int[][] at = arrange(boxes, left, getPaddingTop(), right);
        if (layout) {
            for (int i = 0; i < kids.size(); i++) {
                kids.get(i).layout(at[i][0], at[i][1], at[i][0] + boxes[i][0], at[i][1] + boxes[i][1]);
            }
        }
        widestRow = at[kids.size()][0] + getPaddingRight();
        return at[kids.size()][1] + getPaddingBottom();
    }

    /**
     * Places boxes ({width, height, leftMargin, topMargin, rightMargin,
     * bottomMargin} each) left to right between {@code left} and {@code right},
     * wrapping when the next one would cross {@code right}. Returns each box's
     * {x, y}, then {right edge of the widest row, bottom}. Plain arithmetic, so
     * the wrapping is unit-tested on the JVM.
     */
    static int[][] arrange(int[][] boxes, int left, int top, int right) {
        int[][] out = new int[boxes.length + 1][];
        int x = left, y = top, rowHeight = 0, widest = left;
        for (int i = 0; i < boxes.length; i++) {
            int[] b = boxes[i];
            // A box's trailing margin may hang past the edge; the box itself may not.
            if (x > left && x + b[2] + b[0] > right) {
                x = left;
                y += rowHeight;
                rowHeight = 0;
            }
            out[i] = new int[] { x + b[2], y + b[3] };
            x += b[2] + b[0] + b[4];
            widest = Math.max(widest, x);
            rowHeight = Math.max(rowHeight, b[3] + b[1] + b[5]);
        }
        out[boxes.length] = new int[] { widest, y + rowHeight };
        return out;
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams
                ? new MarginLayoutParams((MarginLayoutParams) p) : new MarginLayoutParams(p);
    }
}
