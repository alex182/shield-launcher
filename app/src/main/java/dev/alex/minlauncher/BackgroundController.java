package dev.alex.minlauncher;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the home screen background, and the weather line under the date, in
 * step with Home Assistant. While HOME
 * is showing it polls the weather, sun and season entities every five
 * minutes (the client is REST-only), picks an image with {@link Backgrounds}
 * and crossfades to it, but only when the picked path changes.
 *
 * The images ship in the APK under {@link #ASSETS}, listed by its
 * manifest.json (the build copies them from output/backgrounds/), so the
 * last one shows straight away on start and stays up when Home Assistant is
 * unreachable. Reads and decoding stay on one worker thread; the views are
 * touched on the main one.
 */
final class BackgroundController {

    static final String ASSETS = "backgrounds/";
    private static final String TAG = "Backgrounds";
    private static final long POLL_MS = 5 * 60_000;
    private static final int FADE_MS = 1500;

    private final Context context;
    private final Store store;
    private final ImageView image;
    private final Scrim scrim;
    private final TextView weatherLine;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refresh;

    /** What this APK carries; read once, on the worker. Null until then. */
    private Backgrounds.Manifest manifest;

    /** Path on screen: null before the first image, "" for the plain default. Set on the main thread. */
    private volatile String shown;
    private boolean running;
    private Backgrounds.Day today = Backgrounds.Day.today();

    BackgroundController(Context context, Store store, ImageView image, Scrim scrim, TextView weatherLine) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.image = image;
        this.scrim = scrim;
        this.weatherLine = weatherLine;
    }

    /** HOME is showing: pick now, then every {@link #POLL_MS}. */
    void start() {
        running = true;
        today = Backgrounds.Day.today();
        applyScrim();
        if (shown == null) worker.execute(this::showLast);
        refresh();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
    }

    void shutdown() {
        stop();
        worker.shutdownNow();
    }

    /** Called every minute; re-picks when the date (and so maybe the holiday) changes. */
    void onTimeTick() {
        Backgrounds.Day d = Backgrounds.Day.today();
        if (d.equals(today)) return;
        today = d;
        refresh();
    }

    /** Re-reads Home Assistant and re-picks; also the response to a settings change. */
    void refresh() {
        handler.removeCallbacks(poll);
        if (running) handler.postDelayed(poll, POLL_MS);
        worker.execute(this::update);
    }

    /** Gradient strength follows the image: daytime images are brighter. */
    void applyScrim() {
        boolean night = Backgrounds.isNight(shown);
        scrim.set(night ? store.scrimNight() : store.scrimDay(), store.scrimEnd());
    }

    // ------------------------------------------------------------- worker

    private void update() {
        if (!loadManifest()) {
            status(null, "This APK has no backgrounds: build it after scripts/generate_backgrounds.py");
            return;
        }
        String url = store.haUrl(), token = store.haToken();
        Backgrounds.Day day = Backgrounds.Day.today();
        Backgrounds.Inputs in;
        try {
            JSONObject weather = store.bgWeather().isEmpty() ? null
                    : HomeAssistant.entity(url, token, store.bgWeather());
            String condition = weather == null ? null : weather.optString("state", null);
            in = new Backgrounds.Inputs(day, condition,
                    state(url, token, store.bgSun()), state(url, token, store.bgSeason()));
            // The live weather, whatever image a holiday or the preview put up.
            JSONObject attrs = weather == null ? null : weather.optJSONObject("attributes");
            String line = Backgrounds.weatherText(condition,
                    attrs == null ? Double.NaN : attrs.optDouble("temperature", Double.NaN),
                    attrs == null ? null : attrs.optString("temperature_unit", null));
            handler.post(() -> showWeather(line));
        } catch (IOException e) {
            // Keep whatever is showing; showLast() already put up the last pick.
            Log.w(TAG, "cannot reach Home Assistant: " + e.getMessage());
            status(null, "Home Assistant: " + e.getMessage());
            return;
        }

        Backgrounds.HolidayRules rules = Backgrounds.parseHolidays(store.bgHolidays());
        Backgrounds.Choice choice = Backgrounds.resolve(manifest, in, store.bgOverride(),
                rules.holidays, focalFor(day));
        Log.i(TAG, "picked " + choice);
        status(choice, missingEntities(in));
        show(choice.path == null ? "" : choice.path);
    }

    /** Puts up the last picked image, before Home Assistant has answered (or if it never does). */
    private void showLast() {
        String last = store.bgLast();
        if (shown != null || last.isEmpty() || !loadManifest() || !manifest.files.contains(last)) return;
        Log.i(TAG, "showing last pick " + last);
        show(last);
    }

    /** Decodes {@code key} ("" = the plain default) and crossfades to it, unless it is already up. */
    private void show(String key) {
        if (key.equals(shown)) return;
        Bitmap bmp = null;
        if (!key.isEmpty()) {
            bmp = decode(ASSETS + key);
            if (bmp == null) {
                status(null, "Cannot decode " + key);
                return;
            }
        }
        Bitmap b = bmp;
        handler.post(() -> crossfade(key, b));
    }

    /** Reads the bundled manifest.json once. False if the APK carries none. */
    private boolean loadManifest() {
        if (manifest != null) return true;
        try (InputStream in = context.getAssets().open(ASSETS + "manifest.json")) {
            JSONObject o = new JSONObject(HomeAssistant.readAll(in));
            List<String> focals = new ArrayList<>();
            JSONArray fa = o.optJSONArray("focal_elements");
            for (int i = 0; fa != null && i < fa.length(); i++) focals.add(fa.getString(i));
            Set<String> files = new HashSet<>();
            JSONArray fs = o.optJSONArray("files");
            for (int i = 0; fs != null && i < fs.length(); i++) files.add(fs.getString(i));
            manifest = new Backgrounds.Manifest(focals, files);
            Log.i(TAG, "manifest: " + files.size() + " files, focal elements " + focals);
            return true;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "no usable manifest.json in the APK: " + e.getMessage());
            return false;
        }
    }

    /** An entity's state; null if it doesn't exist or no entity is set. */
    private static String state(String url, String token, String entityId) throws IOException {
        return entityId.isEmpty() ? null : HomeAssistant.entityState(url, token, entityId);
    }

    /** Today's focal element, held all day whatever else changes. */
    private String focalFor(Backgrounds.Day day) {
        String saved = store.bgFocalDay();
        int space = saved.indexOf(' ');
        if (space > 0 && saved.substring(0, space).equals(day.toString())
                && manifest.focals.contains(saved.substring(space + 1))) {
            return saved.substring(space + 1);
        }
        String f = Backgrounds.focalFor(day, manifest.focals);
        if (f != null) store.setBgFocalDay(day + " " + f);
        return f;
    }

    private String missingEntities(Backgrounds.Inputs in) {
        List<String> missing = new ArrayList<>();
        if (in.weather == null) missing.add(orUnset(store.bgWeather(), "weather"));
        if (in.sun == null) missing.add(orUnset(store.bgSun(), "sun"));
        if (in.season == null) missing.add(orUnset(store.bgSeason(), "season"));
        if (missing.isEmpty()) return null;
        return "Not in Home Assistant: " + TextUtils.join(", ", missing);
    }

    private static String orUnset(String entityId, String what) {
        return entityId.isEmpty() ? "(no " + what + " entity set)" : entityId;
    }

    /** Decoded at the display's size or a little over: 4K WebPs are 33 MB each at full size. */
    @SuppressWarnings("deprecation")
    private Bitmap decode(String asset) {
        Point size = new Point();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRealSize(size);
        int target = Math.max(1, Math.max(size.x, size.y));
        AssetManager assets = context.getAssets();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = assets.open(asset)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in = assets.open(asset)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (IOException | OutOfMemoryError e) {
            Log.w(TAG, "cannot decode " + asset + ": " + e);
            return null;
        }
    }

    /** What the config page shows under "Now showing". */
    private void status(Backgrounds.Choice c, String error) {
        try {
            JSONObject o = new JSONObject().put("at", System.currentTimeMillis());
            if (c != null) o.put("path", c.path == null ? "" : c.path).put("reason", c.reason);
            if (error != null) o.put("error", error);
            if (manifest != null) o.put("focals", new JSONArray(manifest.focals));
            store.setBgStatus(o.toString());
        } catch (JSONException ignored) { }
    }

    // ---------------------------------------------------------------- main

    private void showWeather(String line) {
        weatherLine.setText(line);
        weatherLine.setVisibility(line == null ? View.GONE : View.VISIBLE);
    }

    private void crossfade(String key, Bitmap bmp) {
        if (key.equals(shown)) return;
        shown = key;
        if (!key.isEmpty()) store.setBgLast(key);
        Drawable next = bmp == null ? new ColorDrawable(Color.TRANSPARENT)
                : new BitmapDrawable(context.getResources(), bmp);
        Drawable prev = image.getDrawable();
        if (prev == null) prev = new ColorDrawable(Color.TRANSPARENT);
        TransitionDrawable t = new TransitionDrawable(new Drawable[] { prev, next });
        t.setCrossFadeEnabled(true);
        image.setImageDrawable(t);
        t.startTransition(FADE_MS);
        // Then let go of the old image so its bitmap can be collected.
        handler.postDelayed(() -> { if (image.getDrawable() == t) image.setImageDrawable(next); },
                FADE_MS + 100);
        applyScrim();
    }
}
