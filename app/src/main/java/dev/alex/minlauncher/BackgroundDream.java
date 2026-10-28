package dev.alex.minlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.dreams.DreamService;
import android.view.View;
import android.widget.TextView;

import java.util.Random;

/**
 * Screensaver: the home screen's background full screen, with the clock, date
 * and weather in the image's quiet left side. The image is picked and
 * refreshed by its own {@link BackgroundController}, exactly as HOME does, so
 * it shows the same focal element, season, time, weather and holiday, and
 * crossfades when the weather or the sun changes.
 *
 * The picture is dimmed, and the text drifts a little within the dark left
 * edge every minute, so nothing stays still long enough to mark the panel.
 * Any key wakes it.
 * Chosen under Settings → Device Preferences → Screen saver, or over adb (see
 * README).
 */
public class BackgroundDream extends DreamService {

    /**
     * How far the text may wander, as fractions of the screen: a little sideways,
     * so it stays in the scrim's darkest part at the left edge, and more up and down.
     */
    private static final float DRIFT_X = 0.03f, DRIFT_Y = 0.30f;
    private static final long DRIFT_MS = 4000;

    private final Random random = new Random();
    private BackgroundController background;
    private TextView clock, date;
    private View text;
    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            MainActivity.showTime(clock, date);
            background.onTimeTick();
            drift();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(R.layout.dream);
        text = findViewById(R.id.dream_text);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        background = new BackgroundController(this, new Store(this),
                findViewById(R.id.background), findViewById(R.id.scrim), findViewById(R.id.weather),
                findViewById(R.id.forecast));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        MainActivity.showTime(clock, date);
        // ACTION_TIME_TICK only arrives while registered at runtime.
        registerReceiver(timeTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        background.start();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        try { unregisterReceiver(timeTick); } catch (IllegalArgumentException ignored) { }
        background.stop();
    }

    @Override
    public void onDetachedFromWindow() {
        background.shutdown();
        super.onDetachedFromWindow();
    }

    /** Glides the text to a random spot within the left part of the screen. */
    private void drift() {
        View root = text.getRootView();
        text.animate()
                .translationX(random.nextFloat() * root.getWidth() * DRIFT_X)
                .translationY(random.nextFloat() * root.getHeight() * DRIFT_Y)
                .setDuration(DRIFT_MS)
                .start();
    }
}
