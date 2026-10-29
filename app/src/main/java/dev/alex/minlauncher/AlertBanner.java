package dev.alex.minlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A banner across the top of the screen, over whatever app is in front, when
 * the National Weather Service issues an alert for home: the event, its
 * headline, and its text crawling along the bottom, as TV stations do. A new
 * alert (not an update to one already out) plays the EAS attention tone.
 *
 * While the screen is on it asks api.weather.gov for the active alerts at the
 * home point every {@link #CHECK_MS}; the point is Home Assistant's zone.home
 * unless the config page sets one. Each alert shows once: the ids shown are
 * kept, with their end times, so a restart doesn't sound them again.
 *
 * Like the corner radar the window takes neither focus nor touches, so the
 * app keeps playing; {@link QuickMenuService} closes it on Back or the
 * Netflix button. The tone ducks other audio rather than pausing it.
 */
final class AlertBanner {

    private static final String TAG = "Alerts";
    private static final long CHECK_MS = 60_000;
    /** An alert with no end time is forgotten after this. */
    private static final long DEFAULT_LIFE_MS = 6 * 3600_000L;
    /** The config page's test banner goes away by itself after this. */
    private static final long TEST_MS = 60_000;
    private static final int SAMPLE_RATE = 44_100;
    private static final String API = "https://api.weather.gov/alerts/active?point=";

    private final Context context;
    private final WindowManager wm;
    private final Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable check = this::check;
    private final Runnable expire = this::hide;

    private View root;
    private Alerts.Alert showing;
    private final Tone tone;
    /** "lat,lon" from zone.home, looked up once. Worker thread. */
    private String homePoint;

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
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = (p, key) -> {
        if (Store.KEY_ALERTS_SHOWN.equals(key) || Store.KEY_ALERTS_SEVERITY.equals(key)
                || Store.KEY_ALERTS_POINT.equals(key)) {
            check();
        }
    };

    AlertBanner(Context context, WindowManager wm, Store store) {
        this.context = context;
        this.wm = wm;
        this.store = store;
        this.tone = new Tone(context, handler);
    }

    void start() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(screen, f);
        store.prefs().registerOnSharedPreferenceChangeListener(prefsChanged);
        if (((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isInteractive()) check();
    }

    void shutdown() {
        handler.removeCallbacks(check);
        try { context.unregisterReceiver(screen); } catch (IllegalArgumentException ignored) { }
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsChanged);
        hide();
        worker.shutdownNow();
    }

    boolean isShowing() { return root != null; }

    /** Closed from the remote. It stays seen, so it doesn't come back. */
    void dismiss() { hide(); }

    /** The config page's test: a banner marked TEST, with the tone. Main thread. */
    void test() {
        Log.i(TAG, "test from the config page");
        show(new Alerts.Alert("test", context.getString(R.string.alert_test_event), "Extreme",
                context.getString(R.string.alert_test_headline), context.getString(R.string.alert_test_text),
                "Alert", System.currentTimeMillis() + TEST_MS), true);
    }

    // ----------------------------------------------------------------- NWS

    private void check() {
        handler.removeCallbacks(check);
        handler.postDelayed(check, CHECK_MS);
        if (!store.alertsShown()) {
            if (showing != null && !showing.id.equals("test")) hide();
            return;
        }
        String url = store.haUrl(), token = store.haToken();
        String point = store.alertsPoint(), minimum = store.alertsSeverity();
        worker.execute(() -> {
            List<Alerts.Alert> active;
            try {
                String at = point.isEmpty() ? homePoint(url, token) : point;
                if (at == null) {
                    status(-1, "No location: set one, or give zone.home a latitude and longitude");
                    return;
                }
                active = parse(new String(HomeAssistant.fetchUrl(API + at), StandardCharsets.UTF_8), minimum);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "cannot check alerts: " + e.getMessage());
                status(-1, "Cannot check: " + e.getMessage());
                return;
            }
            status(active.size(), null);
            handler.post(() -> onAlerts(active));
        });
    }

    private void onAlerts(List<Alerts.Alert> active) {
        // Gone from the NWS list: cancelled or expired.
        if (showing != null && !showing.id.equals("test")) {
            boolean still = false;
            for (Alerts.Alert a : active) still |= a.id.equals(showing.id);
            if (!still) hide();
        }
        Map<String, Long> seen = seen();
        Alerts.Alert next = Alerts.next(active, seen.keySet());
        if (next == null) return;
        // A banner already up yields only to something more severe.
        if (showing != null && Alerts.rank(next.severity) >= Alerts.rank(showing.severity)) return;
        seen.put(next.id, next.endsMs > 0 ? next.endsMs : System.currentTimeMillis() + DEFAULT_LIFE_MS);
        saveSeen(seen);
        Log.i(TAG, "showing " + next);
        show(next, !next.isUpdate());
    }

    /** The features that count, from /alerts/active. */
    private static List<Alerts.Alert> parse(String json, String minimum) throws JSONException {
        List<Alerts.Alert> out = new ArrayList<>();
        JSONArray features = new JSONObject(json).optJSONArray("features");
        for (int i = 0; features != null && i < features.length(); i++) {
            JSONObject p = features.getJSONObject(i).optJSONObject("properties");
            if (p == null) continue;
            String severity = p.optString("severity"), type = p.optString("messageType");
            if (!Alerts.counts(p.optString("status"), type, severity, minimum)) continue;
            long ends = HomeAssistant.parseTime(p.isNull("ends") ? p.optString("expires") : p.optString("ends"));
            out.add(new Alerts.Alert(p.optString("id"), p.optString("event"), severity,
                    p.optString("headline"), Alerts.crawlText(p.optString("description"),
                    p.isNull("instruction") ? null : p.optString("instruction")), type, ends));
        }
        return out;
    }

    /** zone.home's "lat,lon", to four places as api.weather.gov wants; null if it has none. */
    private String homePoint(String url, String token) throws IOException {
        if (homePoint != null) return homePoint;
        JSONObject zone = HomeAssistant.entity(url, token, "zone.home");
        JSONObject a = zone == null ? null : zone.optJSONObject("attributes");
        if (a == null || !a.has("latitude") || !a.has("longitude")) return null;
        homePoint = String.format(Locale.ROOT, "%.4f,%.4f", a.optDouble("latitude"), a.optDouble("longitude"));
        return homePoint;
    }

    private Map<String, Long> seen() {
        Map<String, Long> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(store.alertsSeen());
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.getLong(k));
            }
        } catch (JSONException ignored) { }
        Alerts.prune(out, System.currentTimeMillis());
        return out;
    }

    private void saveSeen(Map<String, Long> seen) {
        store.setAlertsSeen(new JSONObject(seen).toString());
    }

    /** What the config page shows: when it last checked, how many alerts, or why it couldn't. */
    private void status(int active, String error) {
        try {
            JSONObject o = new JSONObject().put("at", System.currentTimeMillis());
            if (active >= 0) o.put("active", active);
            if (error != null) o.put("error", error);
            store.setAlertsStatus(o.toString());
        } catch (JSONException ignored) { }
    }

    // ---------------------------------------------------------------- window

    private void show(Alerts.Alert alert, boolean withTone) {
        hide();
        showing = alert;
        boolean extreme = alert.severity.equals("Extreme");
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(alert.id.equals("test") ? 0xF0303848 : extreme ? 0xF0A00018 : 0xF0C04000);

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(28), dp(14), dp(28), dp(4));
        TextView event = text(alert.event.toUpperCase(Locale.ROOT), 26);
        event.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(event, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView hint = text(context.getString(R.string.alert_hint), 13);
        hint.setAlpha(0.8f);
        top.addView(hint);
        box.addView(top);

        if (!alert.headline.isEmpty()) {
            TextView headline = text(alert.headline, 16);
            headline.setPadding(dp(28), 0, dp(28), dp(10));
            box.addView(headline);
        }
        if (!alert.text.isEmpty()) {
            Crawl crawl = new Crawl(context, sp(20), dp(110), alert.text);
            crawl.setBackgroundColor(0x60000000);
            crawl.setPadding(0, dp(6), 0, dp(8));
            box.addView(crawl);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        root = box;
        wm.addView(root, lp);
        if (alert.endsMs > 0) {
            handler.postDelayed(expire, Math.max(0, alert.endsMs - System.currentTimeMillis()));
        }
        if (withTone) playTone();
    }

    private void hide() {
        handler.removeCallbacks(expire);
        tone.stop();
        showing = null;
        if (root == null) return;
        wm.removeView(root);
        root = null;
    }

    private TextView text(String s, int sizeSp) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(0xFFFFFFFF);
        t.setShadowLayer(4, 0, 0, 0xFF000000);
        return t;
    }

    // ------------------------------------------------------------------ tone

    /** The EAS attention signal, over whatever is playing, which only ducks. */
    private void playTone() {
        int seconds = store.alertsToneSeconds();
        if (seconds > 0) tone.play(Alerts.attentionSignal(SAMPLE_RATE, seconds), SAMPLE_RATE);
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private float sp(int v) {
        return v * context.getResources().getDisplayMetrics().scaledDensity;
    }

    /**
     * One line of text crawling right to left, round and round, as on a TV
     * station's alert. Drawn by hand: a marquee TextView only runs while its
     * window has focus, and this window never does.
     */
    private static final class Crawl extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String text;
        private final float textWidth, pxPerMs;
        private float x = Float.NaN;
        private long last;

        Crawl(Context c, float textPx, float pxPerSecond, String text) {
            super(c);
            this.text = text;
            paint.setTextSize(textPx);
            paint.setColor(0xFFFFFFFF);
            textWidth = paint.measureText(text);
            pxPerMs = pxPerSecond / 1000f;
        }

        @Override
        protected void onMeasure(int w, int h) {
            Paint.FontMetrics fm = paint.getFontMetrics();
            setMeasuredDimension(MeasureSpec.getSize(w),
                    (int) Math.ceil(fm.bottom - fm.top) + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            if (Float.isNaN(x)) x = getWidth();
            else x -= (now - last) * pxPerMs;
            if (x < -textWidth) x = getWidth();
            last = now;
            c.drawText(text, x, getPaddingTop() - paint.getFontMetrics().top, paint);
            postInvalidateOnAnimation();
        }
    }
}
