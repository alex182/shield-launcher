package dev.alex.minlauncher;

import android.content.Context;
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
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the home screen background, and the weather line under the date, in
 * step with Home Assistant. While HOME
 * is showing it polls the weather, sun and season entities every five
 * minutes (the client is REST-only), picks an image with {@link Backgrounds}
 * and crossfades to it, but only when the picked path changes.
 *
 * The images and their manifest.json come from an NFS share through
 * {@link BackgroundFiles}, which keeps recent ones on the device, so the
 * last one shows straight away on start and stays up when Home Assistant or
 * the NAS is unreachable. The manifest is re-read on every check, so images
 * added to the share are picked up without a reinstall. Reads and decoding
 * stay on one worker thread; the views are touched on the main one.
 */
final class BackgroundController {

    private static final String TAG = "Backgrounds";
    private static final long POLL_MS = 5 * 60_000;
    private static final int FADE_MS = 1500;

    private final Context context;
    private final Store store;
    private final ImageView image;
    private final Scrim scrim;
    private final TextView weatherLine, forecastLine;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refresh;

    private final BackgroundFiles files;
    /** What there is to pick from, re-read on every check; on the worker. Null until then. */
    private Backgrounds.Manifest manifest;

    /** Path on screen: null before the first image, "" for the plain default. Set on the main thread. */
    private volatile String shown;
    private boolean running;
    private Backgrounds.Day today = Backgrounds.Day.today();

    BackgroundController(Context context, Store store, ImageView image, Scrim scrim, TextView weatherLine,
            TextView forecastLine) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.image = image;
        this.scrim = scrim;
        this.weatherLine = weatherLine;
        this.forecastLine = forecastLine;
        files = new BackgroundFiles(this.context, store);
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
        Backgrounds.Manifest m = files.manifest(true);
        if (m != null) manifest = m;
        if (manifest == null) {
            status(null, files.error != null ? files.error
                    : "No manifest.json in " + files.where() + ": publish the backgrounds there");
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
            String forecast = weather == null ? null : forecast(url, token, store.bgWeather());
            handler.post(() -> showWeather(line, forecast));
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
        status(choice, join(missingEntities(in), files.error));
        show(choice.path == null ? "" : choice.path, true);
    }

    /** Puts up the last picked image, before Home Assistant has answered (or if it never does). */
    private void showLast() {
        String last = store.bgLast();
        if (shown != null || last.isEmpty()) return;
        if (manifest == null) manifest = files.manifest(false);
        if (manifest == null || !manifest.files.contains(last)) return;
        Log.i(TAG, "showing last pick " + last);
        show(last, false);
    }

    /**
     * Decodes {@code key} ("" = the plain default) and crossfades to it, unless
     * it is already up. {@code network} false uses only what is on the device.
     */
    private void show(String key, boolean network) {
        if (key.equals(shown)) return;
        Bitmap bmp = null;
        if (!key.isEmpty()) {
            try {
                bmp = decode(files.image(key, network));
            } catch (IOException e) {
                Log.w(TAG, "cannot open " + key + ": " + e.getMessage());
                if (network) status(null, "Cannot load " + key + ": " + e.getMessage());
                return;
            }
            if (bmp == null) {
                status(null, "Cannot decode " + key);
                return;
            }
        }
        Bitmap b = bmp;
        handler.post(() -> crossfade(key, b));
    }

    /** Today's (or, in the evening, tomorrow's) forecast line; null if there is none. */
    private static String forecast(String url, String token, String entityId) {
        try {
            JSONArray days = HomeAssistant.dailyForecast(url, token, entityId);
            boolean tomorrow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    >= Backgrounds.FORECAST_TOMORROW_HOUR;
            JSONObject d = days.optJSONObject(tomorrow ? 1 : 0);
            if (d == null) return null;
            return Backgrounds.forecastText(tomorrow, d.optDouble("temperature", Double.NaN),
                    d.optDouble("templow", Double.NaN), d.optDouble("precipitation_probability", Double.NaN),
                    d.optString("condition", null));
        } catch (IOException e) {
            // Not every weather integration has a daily forecast; the line just stays hidden.
            Log.w(TAG, "no forecast: " + e.getMessage());
            return null;
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

    private static String join(String a, String b) {
        return a == null ? b : b == null ? a : a + "; " + b;
    }

    private static String orUnset(String entityId, String what) {
        return entityId.isEmpty() ? "(no " + what + " entity set)" : entityId;
    }

    /** Decoded at the display's size or a little over: 4K WebPs are 33 MB each at full size. */
    @SuppressWarnings("deprecation")
    private Bitmap decode(BackgroundFiles.Source source) {
        Point size = new Point();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRealSize(size);
        int target = Math.max(1, Math.max(size.x, size.y));
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = source.open()) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in = source.open()) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (IOException | OutOfMemoryError e) {
            Log.w(TAG, "cannot decode: " + e);
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

    private void showWeather(String line, String forecast) {
        weatherLine.setText(line);
        weatherLine.setVisibility(line == null ? View.GONE : View.VISIBLE);
        forecastLine.setText(forecast);
        forecastLine.setVisibility(forecast == null ? View.GONE : View.VISIBLE);
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
