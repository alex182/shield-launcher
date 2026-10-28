package dev.alex.minlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Left-to-right black gradient between the background image and the UI, so
 * the clock and lists stay readable on bright images. Darkest at the left
 * edge, clear from {@code endPercent} of the width onwards.
 */
public final class Scrim extends View {

    private final Paint paint = new Paint();
    private int alphaPercent = Store.DEFAULT_SCRIM_DAY;
    private int endPercent = Store.DEFAULT_SCRIM_END;

    public Scrim(Context c) { super(c); }
    public Scrim(Context c, AttributeSet attrs) { super(c, attrs); }

    void set(int alphaPercent, int endPercent) {
        if (alphaPercent == this.alphaPercent && endPercent == this.endPercent) return;
        this.alphaPercent = alphaPercent;
        this.endPercent = endPercent;
        updateShader();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        updateShader();
    }

    private void updateShader() {
        float end = Math.max(1, getWidth() * endPercent / 100f);
        int a = Math.round(255 * alphaPercent / 100f);
        // A mid stop eases the falloff, so there is no visible edge where it ends.
        paint.setShader(new LinearGradient(0, 0, end, 0,
                new int[] { a << 24, (a * 2 / 5) << 24, 0 },
                new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (alphaPercent <= 0) return;
        canvas.drawRect(0, 0, getWidth() * endPercent / 100f, getHeight(), paint);
    }
}
