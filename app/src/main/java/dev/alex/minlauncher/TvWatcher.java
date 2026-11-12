package dev.alex.minlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Keeps an eye on the TV every {@value #TICK_MS} ms, from the quick actions
 * service: the screen, the app in front (from its accessibility events) and
 * what is playing (audio_flinger, {@link AudioSessions}). From that it
 *
 * <ul>
 *   <li>runs the "while watching" room lights ({@link ModeLights#PLAYBACK}):
 *       on while a chosen app plays, off {@value #MISSES_TO_STOP} ticks after
 *       it stops (a pause), or at once when the screen goes off;</li>
 *   <li>reports what the TV is doing ({@link TvState}) to a Home Assistant
 *       sensor, when that is turned on: on each change, and every
 *       {@value #HEARTBEAT_MS} ms besides, since states set over the REST
 *       API are gone after Home Assistant restarts.</li>
 * </ul>
 *
 * The dump is only read while the screen is on and something needs it.
 */
final class TvWatcher {

    private static final String TAG = "TvWatcher";
    private static final long TICK_MS = 2000;
    private static final int MISSES_TO_STOP = 2;
    private static final long HEARTBEAT_MS = 5 * 60_000;

    private final Context context;
    private final Store store;
    private final BooleanSupplier visualizerShowing;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, String> names = new HashMap<>();

    /** The app in front, from accessibility events on the main thread. */
    private volatile String front;
    // Worker thread only.
    private boolean watching;
    private int misses;
    private TvState reported;
    private long reportedAt;
    private String reportedTo = "";

    TvWatcher(Context context, Store store, BooleanSupplier visualizerShowing) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.visualizerShowing = visualizerShowing;
        front = this.context.getPackageName();   // started with the launcher, at home
    }

    void start() { exec.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS); }

    void stop() { exec.shutdownNow(); }

    /** Something changed (the screen): look now rather than at the next tick. */
    void poke() {
        try { exec.execute(this::tick); } catch (RuntimeException ignored) { }   // stopped
    }

    /**
     * A window came up. Only activities count (not dialogs, the keyboard or
     * overlays); of this launcher's own, only the home screen does.
     */
    void windowChanged(CharSequence pkg, CharSequence cls) {
        if (pkg == null || cls == null) return;
        String p = pkg.toString(), c = cls.toString();
        if (p.equals(context.getPackageName())) {
            if (c.equals(MainActivity.class.getName())) front = p;
            return;
        }
        if (p.equals("com.android.systemui")) return;
        try {
            context.getPackageManager().getActivityInfo(new ComponentName(p, c), 0);
            front = p;
        } catch (PackageManager.NameNotFoundException notAnActivity) { }
    }

    private void tick() {
        try {
            boolean screenOn = isInteractive();
            JSONObject play = store.modeLights().optJSONObject(ModeLights.PLAYBACK);
            JSONObject report = store.haReport();
            boolean wantLights = !ModeLights.lights(play).isEmpty(), wantReport = report.optBoolean("on");
            List<AudioSessions.Playing> playing = screenOn && (wantLights || wantReport)
                    ? AudioSessions.read(context) : Collections.emptyList();
            String app = front;

            boolean now = screenOn && TvState.watched(playing, ModeLights.apps(play), app);
            if (now) { watching = true; misses = 0; }
            else if (!screenOn) watching = false;
            else if (watching && ++misses >= MISSES_TO_STOP) watching = false;
            ModeLightsController.get(context).set(ModeLights.PLAYBACK, watching);

            if (wantReport) {
                TvState st = new TvState(screenOn, visualizerShowing.getAsBoolean(), AmbilightService.isOn(),
                        app, name(app), app.equals(context.getPackageName()), TvState.playingApp(playing));
                report(st, entity(report));
            } else {
                reported = null;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "tick", e);   // never let one bad tick end the schedule
        }
    }

    private void report(TvState st, String entity) {
        long t = System.currentTimeMillis();
        if (st.equals(reported) && entity.equals(reportedTo) && t - reportedAt < HEARTBEAT_MS) return;
        String error = "";
        try {
            HomeAssistant.setState(store.haUrl(), store.haToken(), entity,
                    new JSONObject().put("state", st.state()).put("attributes", st.attributes(deviceName())));
            reported = st;
            reportedTo = entity;
            reportedAt = t;
        } catch (IOException | JSONException e) {
            error = e.getMessage();
            Log.w(TAG, "report: " + error);
            reported = null;   // try again next tick
        }
        // Only on a change, so a failing Home Assistant doesn't rewrite it every tick.
        String text = entity + " = " + st.state();
        JSONObject old = store.haReportStatus();
        if (!text.equals(old.optString("text")) || !error.equals(old.optString("error"))) {
            try {
                store.setHaReportStatus(new JSONObject().put("at", t).put("text", text).put("error", error));
            } catch (JSONException ignored) { }
        }
    }

    private String entity(JSONObject report) {
        String e = report.optString("entity");
        return e.isEmpty() ? TvState.defaultEntity(deviceName()) : e;
    }

    private String deviceName() {
        String n = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        return n == null ? "SHIELD" : n;
    }

    private String name(String pkg) {
        String n = names.get(pkg);
        if (n != null) return n;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            n = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            n = pkg;
        }
        names.put(pkg, n);
        return n;
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH ? pm.isInteractive() : pm.isScreenOn();
    }
}
