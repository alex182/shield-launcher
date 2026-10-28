package dev.alex.minlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small radar picture-in-picture in the bottom-left corner, over whatever
 * app is in front, while Home Assistant's weather says rain or lightning, or
 * the weather station has seen lightning close by
 * ({@link Backgrounds#lightningNear}). Over the home screen it sits
 * bottom-right instead, clear of the app banners.
 *
 * While the screen is on it checks those entities every {@link #CHECK_MS};
 * the home screen's own poll only runs while HOME shows.
 * The small window is neither focusable nor touchable, so the app underneath
 * keeps the remote; {@link QuickMenuService} routes Back (close) and the
 * Netflix button (full screen) here. Full screen never takes focus either,
 * since players pause when they lose it; the service holds back the app's
 * navigation keys instead. There Back shrinks it again and Netflix closes it. Once
 * closed it stays away until the weather clears and turns wet again.
 *
 * The source is an image URL (an animated GIF plays, as the NWS loops do) or
 * a Home Assistant camera entity, re-fetched every {@link #IMAGE_REFRESH_MS}.
 */
final class RadarOverlay {

    private static final String TAG = "Radar";
    private static final long CHECK_MS = 2 * 60_000;
    /** NWS radar loops update about every five minutes. */
    private static final long IMAGE_REFRESH_MS = 5 * 60_000;
    /** Of the screen width; the NWS loops are 600×550. */
    private static final float WIDTH_FRACTION = 0.24f;
    private static final float ASPECT = 550f / 600f;

    private final Context context;
    private final WindowManager wm;
    private final Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable check = this::check;
    private final Runnable reload = this::reload;

    /** Set by MainActivity, which shares the process; read on the main thread. */
    private static boolean homeShowing;
    private static RadarOverlay current;

    private FrameLayout root;
    private ImageView image;
    private TextView hint;
    private boolean full;
    /** Shown from the config page: stays up whatever the weather, until closed. */
    private boolean forced;
    /** Closed during this spell of rain; cleared when the weather clears. */
    private boolean dismissed;
    private int generation;         // bumps on every show/hide so stale fetches drop

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                check();
            } else {
                handler.removeCallbacks(check);
                hide();
            }
        }
    };
    /** A change on the config page shows or hides it straight away. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = (p, key) -> {
        if (key != null && (key.startsWith("radar_") || Store.KEY_BG_WEATHER.equals(key))) {
            hide();
            dismissed = false;
            check();
        }
    };

    RadarOverlay(Context context, WindowManager wm, Store store) {
        this.context = context;
        this.wm = wm;
        this.store = store;
    }

    void start() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(screen, f);
        store.prefs().registerOnSharedPreferenceChangeListener(prefsChanged);
        current = this;
        if (((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isInteractive()) check();
    }

    void shutdown() {
        if (current == this) current = null;
        handler.removeCallbacks(check);
        try { context.unregisterReceiver(screen); } catch (IllegalArgumentException ignored) { }
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsChanged);
        hide();
        worker.shutdownNow();
    }

    boolean isShowing() { return root != null; }

    /** The home screen came up or went away: the corner radar moves out of the banners' way. Main thread. */
    static void setHomeShowing(boolean on) {
        if (homeShowing == on) return;
        homeShowing = on;
        RadarOverlay r = current;
        if (r != null && r.root != null && !r.full) r.wm.updateViewLayout(r.root, r.params());
    }

    boolean isFull() { return full; }

    /** Between the corner and the whole screen. */
    void setFull(boolean on) {
        if (root == null || on == full) return;
        full = on;
        layout();
        wm.updateViewLayout(root, params());
    }

    /** The config page's "Show radar now", to see it without waiting for rain. Main thread. */
    void force() {
        Log.i(TAG, "shown from the config page");
        dismissed = false;
        forced = true;
        show();
    }

    /** Closed from the remote: stays closed until the rain stops. */
    void dismiss() {
        dismissed = true;
        hide();
    }

    // --------------------------------------------------------------- weather

    private void check() {
        handler.removeCallbacks(check);
        handler.postDelayed(check, CHECK_MS);
        String weather = store.bgWeather();
        String distance = store.radarLightningDistance(), strike = store.radarLightningStrike();
        if (!store.radarShown() || (weather.isEmpty() && (distance.isEmpty() || strike.isEmpty()))) {
            if (!forced) hide();
            return;
        }
        String url = store.haUrl(), token = store.haToken();
        int miles = store.radarLightningMiles();
        worker.execute(() -> {
            String why;
            try {
                why = reason(url, token, weather, distance, strike, miles);
            } catch (IOException e) {
                Log.w(TAG, "cannot reach Home Assistant: " + e.getMessage());
                return;   // leave it as it is until Home Assistant answers
            }
            handler.post(() -> onWeather(why));
        });
    }

    /** Why the radar should be up ("weather is rainy", "lightning 4.2 mi away"), or null. Worker thread. */
    private static String reason(String url, String token, String weather, String distance,
            String strike, int maxMiles) throws IOException {
        if (!weather.isEmpty()) {
            String state = HomeAssistant.entityState(url, token, weather);
            if (Backgrounds.rainOrLightning(state)) return "weather is " + state;
        }
        if (distance.isEmpty() || strike.isEmpty()) return null;
        JSONObject d = HomeAssistant.entity(url, token, distance);
        String at = HomeAssistant.entityState(url, token, strike);
        if (d == null || at == null) return null;
        double dist = parseDouble(d.optString("state"));
        JSONObject attrs = d.optJSONObject("attributes");
        String unit = attrs == null ? null : attrs.optString("unit_of_measurement", null);
        if (!Backgrounds.lightningNear(dist, unit, HomeAssistant.parseTime(at), System.currentTimeMillis(), maxMiles)) {
            return null;
        }
        return String.format(Locale.ROOT, "lightning %.1f %s away", dist, unit == null ? "" : unit);
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private void onWeather(String why) {
        if (why == null) {
            dismissed = false;
            if (!forced) hide();
        } else if (!dismissed && root == null) {
            Log.i(TAG, why + ": showing radar");
            show();
        }
    }

    // ---------------------------------------------------------------- window

    private void show() {
        if (root != null || wm == null) return;
        full = false;
        root = new FrameLayout(context);
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xFF101010);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top left: the NWS loops keep their colour key at the top right.
        hint = new TextView(context);
        hint.setTextColor(context.getResources().getColor(R.color.fg));
        hint.setBackgroundColor(0xB0000000);
        root.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        layout();
        wm.addView(root, params());
        generation++;
        reload();
    }

    /** The views for the current size; the window follows from {@link #params()}. */
    private void layout() {
        if (full) {
            root.setBackgroundColor(0xFF000000);
            root.setPadding(0, 0, 0, 0);
        } else {
            root.setBackgroundResource(R.drawable.banner_bg);
            root.setPadding(dp(3), dp(3), dp(3), dp(3));
        }
        hint.setText(full ? R.string.radar_hint_full : R.string.radar_hint);
        hint.setTextSize(full ? 16 : 11);
        int h = dp(full ? 12 : 6);
        hint.setPadding(h, dp(full ? 6 : 2), h, dp(full ? 8 : 3));
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp;
        if (full) {
            lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
        } else {
            int width = Math.round(context.getResources().getDisplayMetrics().widthPixels * WIDTH_FRACTION);
            lp = new WindowManager.LayoutParams(width, Math.round(width * ASPECT),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | (homeShowing ? Gravity.END : Gravity.START);
            lp.x = dp(32);
            lp.y = dp(32);
        }
        return lp;
    }

    private void hide() {
        handler.removeCallbacks(reload);
        if (root == null) return;
        generation++;
        stopAnimation();
        wm.removeView(root);
        root = null;
        image = null;
        hint = null;
        full = false;
        forced = false;
    }

    /** Fetches the radar picture again; the old one stays up if this fails. */
    private void reload() {
        if (root == null) return;
        handler.removeCallbacks(reload);
        handler.postDelayed(reload, IMAGE_REFRESH_MS);
        int gen = generation;
        String source = store.radarSource();
        String url = store.haUrl(), token = store.haToken();
        worker.execute(() -> {
            Drawable d;
            try {
                byte[] bytes = source.startsWith("camera.")
                        ? HomeAssistant.cameraSnapshot(url, token, source) : HomeAssistant.fetchUrl(source);
                d = decode(bytes);
            } catch (IOException | OutOfMemoryError e) {
                Log.w(TAG, "cannot load " + source + ": " + e);
                return;
            }
            if (d == null) return;
            handler.post(() -> {
                if (gen != generation || image == null) return;
                stopAnimation();
                image.setImageDrawable(d);
                if (d instanceof Animatable) ((Animatable) d).start();
            });
        });
    }

    private void stopAnimation() {
        if (image != null && image.getDrawable() instanceof Animatable) {
            ((Animatable) image.getDrawable()).stop();
        }
    }

    /** Animated GIFs play on API 28+; older devices get the first frame. */
    private Drawable decode(byte[] bytes) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
        }
        return new BitmapDrawable(context.getResources(),
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
