package dev.alex.minlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the remote's Netflix button, over whatever app is in front:
 * <ul>
 *   <li>single press: opens the configured app (Jellyfin by default);</li>
 *   <li>double press: the Home Assistant camera grid ({@link CameraOverlay});</li>
 *   <li>hold (3 s by default): the quick actions panel, which calls Home
 *       Assistant.</li>
 * </ul>
 * Any press while an overlay is up just closes it (the small radar goes full
 * screen instead). Holding play/pause opens or closes the music visualizer
 * ({@link VisualizerOverlay}); a short press is held back until release, then
 * sent on as a media key. The service also hosts the
 * web config page, the rain radar pop-up ({@link RadarOverlay})
 * the NWS alert banner ({@link AlertBanner}), calendar reminders
 * ({@link EventReminders}) and starts the room ambilight
 * ({@link AmbilightService}) when the screen comes on, since it is the one component that is always
 * running. Back closes the banner or a reminder and steps the radar down (full screen, corner, closed), when nothing else of ours is up.
 *
 * An accessibility service is the only way a non-system app sees keys meant
 * for other apps, and its TYPE_ACCESSIBILITY_OVERLAY windows need no
 * SYSTEM_ALERT_WINDOW grant. Enable it over adb (see README).
 *
 * Every key-filtering service sees every key, so Button Mapper / Buttons
 * remapper must have no Netflix-button mapping of their own, or a double
 * press also fires theirs twice.
 */
public class QuickMenuService extends AccessibilityService {

    /** 2019 SHIELD remote: the Netflix key is scancode 393 → BUTTON_12. */
    private static final int NETFLIX_KEY = KeyEvent.KEYCODE_BUTTON_12;
    /** A second press within this of the first release makes a double press. */
    private static final long DOUBLE_PRESS_MS = 350;
    private static final long MINUTE_MS = 60_000;
    /** Between going HOME (so players stop) and sleeping. */
    private static final long SLEEP_AFTER_HOME_MS = 1500;
    /** Play/pause held this long toggles the visualizer. */
    private static final long VISUALIZER_HOLD_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable holdReached = () -> { holdFired = true; showMenu(); };
    private final Runnable singlePress = this::onSinglePress;
    private final Runnable visualizerHoldReached = () -> { visualizerHoldFired = true; this.visualizer.toggle(); };
    private final ExecutorService calls = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private View panel;
    private CameraOverlay cameras;
    private VisualizerOverlay visualizer;
    private LightPanel lights;
    private RadarOverlay radar;
    private AlertBanner alerts;
    private EventReminders reminders;
    private Store store;
    private WebConfigServer web;
    private TvWatcher watcher;

    // Sleep timer: elapsedRealtime when it fires, 0 = off.
    private long sleepAt;
    private TextView sleepRow;
    private TextView header;        // device name, plus the time left while the timer runs
    private String deviceName = "";
    private final Runnable sleepNow = this::sleepNow;
    private final Runnable sleepWarning = () -> toast(getString(R.string.sleep_soon), true);
    private final Runnable sleepRowTick = new Runnable() {
        @Override public void run() {
            updateSleepRow();
            if (sleepAt != 0 && sleepRow != null) handler.postDelayed(this, MINUTE_MS);
        }
    };
    /** Off some other way: the timer has nothing left to do. On: the ambilight, if it starts with the TV. */
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) autoStartAmbilight();
            else cancelSleep();
            if (watcher != null) watcher.poke();
        }
    };
    private TextView ambilightRow;
    /** After boot, give HOME a moment before the capture starts. */
    private static final long AMBILIGHT_BOOT_DELAY_MS = 5000;

    // Netflix-button gesture state.
    private boolean holdFired;       // this press already opened quick actions
    private boolean singlePending;   // released once; waiting to see if a second press follows
    private boolean secondPress;     // this press is the second of a double press
    private boolean swallowRelease;  // this press only closed an overlay
    private boolean swallowBack;     // this Back only closed the radar
    // Play/pause: down until up, whatever the setting does meanwhile, so the app never sees half a press.
    private boolean playPauseDown;
    private boolean visualizerHoldFired;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        store = new Store(this);
        cameras = new CameraOverlay(this, wm, store);
        visualizer = new VisualizerOverlay(this, wm, store);
        lights = new LightPanel(this, wm, store);
        radar = new RadarOverlay(this, wm, store);
        radar.start();
        alerts = new AlertBanner(this, wm, store);
        alerts.start();
        reminders = new EventReminders(this, wm, store);
        reminders.start();
        web = new WebConfigServer(this, store, () -> handler.post(radar::force),
                () -> handler.post(alerts::test), () -> handler.post(reminders::test),
                () -> handler.post(reminders::testDigest), (playlist, preset) -> handler.post(() -> visualizer.show(playlist, preset)));
        web.setScreenshots(live -> live ? Screenshot.jpeg(this) : Screenshot.png(this));
        web.start();
        watcher = new TvWatcher(this, store, visualizer::isShowing);
        watcher.start();
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenOff, screen);
        handler.postDelayed(this::autoStartAmbilight, AMBILIGHT_BOOT_DELAY_MS);
    }

    private void autoStartAmbilight() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        JSONObject a = store.ambilight();
        if (a.optBoolean("autoStart") && pm.isInteractive() && a.optJSONArray("strips").length() > 0
                && !AmbilightService.isOn()) {
            AmbilightService.start(this);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return onBack(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                && (playPauseDown || store.visualizerHold())) {
            return onPlayPause(event);
        }
        if (event.getKeyCode() != NETFLIX_KEY) {
            // The full-screen radar has no focus (so playback carries on under
            // it); the app underneath must not act on keys meant for the radar.
            return radar.isFull() && !passesRadar(event.getKeyCode());
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) return true;
            if (panel != null || cameras.isShowing() || visualizer.isShowing() || alerts.isShowing()
                    || reminders.isShowing() || radar.isShowing()) {
                if (panel != null || cameras.isShowing() || visualizer.isShowing()) {
                    hideMenu();
                    cameras.hide();
                    visualizer.hide();
                } else if (reminders.isShowing()) {
                    reminders.dismiss();
                } else if (alerts.isShowing()) {
                    alerts.dismiss();
                } else if (radar.isFull()) {
                    radar.dismiss();
                } else {
                    radar.setFull(true);
                }
                swallowRelease = true;
                return true;
            }
            holdFired = false;
            if (singlePending) {
                handler.removeCallbacks(singlePress);
                singlePending = false;
                secondPress = true;
            }
            handler.postDelayed(holdReached, store.holdMs());
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(holdReached);
            if (swallowRelease) {
                swallowRelease = false;
            } else if (holdFired) {
                secondPress = false;
            } else if (secondPress) {
                secondPress = false;
                cameras.show();
            } else {
                singlePending = true;
                handler.postDelayed(singlePress, DOUBLE_PRESS_MS);
            }
        }
        return true;   // the system's own Netflix-button handling never sees it
    }

    /**
     * The reminder, the alert banner and the radar can't take focus (the app
     * underneath keeps the remote), so their Back is caught here, in that
     * order. Our other overlays handle their own Back.
     */
    private boolean onBack(KeyEvent event) {
        if (swallowBack) {
            // Its repeats and release too, so the app never sees half a press.
            if (event.getAction() == KeyEvent.ACTION_UP) swallowBack = false;
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || panel != null || cameras.isShowing()
                || lights.isShowing() || visualizer.isShowing()) {
            return false;
        }
        if (reminders.isShowing()) {
            reminders.dismiss();
            swallowBack = true;
            return true;
        }
        if (alerts.isShowing()) {
            alerts.dismiss();
            swallowBack = true;
            return true;
        }
        if (radar.isShowing()) {
            if (radar.isFull()) radar.setFull(false);
            else radar.dismiss();
            swallowBack = true;
            return true;
        }
        return false;
    }

    /**
     * Held: the visualizer, on or off. Released sooner: an ordinary play/pause,
     * sent on through the media session. That reaches every player with one
     * (all the music and video apps here), but not one that only reads keys
     * in its own window; the config page can switch the hold off.
     */
    private boolean onPlayPause(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                playPauseDown = true;
                visualizerHoldFired = false;
                handler.postDelayed(visualizerHoldReached, VISUALIZER_HOLD_MS);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(visualizerHoldReached);
            playPauseDown = false;
            if (!visualizerHoldFired) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
        return true;
    }

    private void sendMediaKey(int key) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    /** Volume and playback still reach the app while the radar fills the screen. */
    private static boolean passesRadar(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_HOME:
                return true;
            default:
                return false;
        }
    }

    private void onSinglePress() {
        singlePending = false;
        String pkg = store.singlePress();
        if (!pkg.isEmpty() && !Apps.launch(this, pkg)) {
            toast(getString(R.string.cannot_launch, pkg));
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (watcher != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            watcher.windowChanged(event.getPackageName(), event.getClassName());
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(holdReached);
        handler.removeCallbacks(singlePress);
        handler.removeCallbacks(visualizerHoldReached);
        cancelSleep();
        try { unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { }
        hideMenu();
        if (cameras != null) cameras.shutdown();
        if (visualizer != null) visualizer.shutdown();
        if (lights != null) lights.shutdown();
        if (radar != null) radar.shutdown();
        if (alerts != null) alerts.shutdown();
        if (reminders != null) reminders.shutdown();
        if (web != null) web.stop();
        if (watcher != null) watcher.stop();
        calls.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ panel

    @SuppressWarnings("deprecation")
    private void showMenu() {
        if (panel != null || wm == null) return;

        LinearLayout box = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) hideMenu();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xF0101010);
        box.setPadding(dp(40), dp(48), dp(40), dp(40));

        TextView title = new TextView(this);
        title.setText(R.string.quick_actions);
        title.setTextSize(28);
        title.setTextColor(getResources().getColor(R.color.fg));
        box.addView(title);

        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        deviceName = name == null ? "" : name.toUpperCase();
        TextView sub = new TextView(this);
        header = sub;
        sub.setText(deviceName);
        sub.setTextSize(14);
        sub.setTextColor(getResources().getColor(R.color.dim));
        sub.setPadding(0, dp(2), 0, dp(24));
        box.addView(sub);

        // The actions scroll in the leftover height, so the title stays put and
        // Sleep sits at the very bottom of the pane however many there are.
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Store.Action action : store.actions()) list.addView(makeRow(action));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(32));
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (store.ambilight().optJSONArray("strips").length() > 0) {
            ambilightRow = makeRow("", v -> toggleAmbilight());
            box.addView(ambilightRow);
            updateAmbilightRow(AmbilightService.isOn());
        }
        if (store.sleepShown()) {
            sleepRow = makeRow(Icons.withLabel(this, "mdi:power-sleep", ""), v -> toggleSleep());
            box.addView(sleepRow);
            updateSleepRow();
            if (sleepAt != 0) handler.postDelayed(sleepRowTick, MINUTE_MS);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(420), ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.dimAmount = 0.5f;

        panel = box;
        wm.addView(panel, lp);
        // As in MainActivity, focus only sticks once the views are laid out.
        box.post(() -> {
            View first = list.getChildCount() > 0 ? list.getChildAt(0)
                    : ambilightRow != null ? ambilightRow : sleepRow;
            if (first != null) first.requestFocus();
        });
    }

    private void hideMenu() {
        if (lights != null) lights.hide();
        handler.removeCallbacks(sleepRowTick);
        sleepRow = null;
        ambilightRow = null;
        header = null;
        if (panel == null) return;
        wm.removeView(panel);
        panel = null;
    }

    private TextView makeRow(Store.Action action) {
        TextView t = makeRow(Icons.withLabel(this, action.icon, action.label), v -> runAction(action));
        // Long press on an action whose entities are all lights: brightness and colour.
        List<String> ids = entityIds(action);
        if (Lights.allLights(ids)) {
            t.setOnLongClickListener(v -> { lights.show(action.label, ids); return true; });
        }
        return t;
    }

    @SuppressWarnings("deprecation")
    private TextView makeRow(CharSequence text, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(20);
        t.setTextColor(getResources().getColorStateList(R.color.row_text));
        t.setBackgroundResource(R.drawable.row_bg);
        t.setFocusable(true);
        t.setSingleLine(true);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        t.setOnClickListener(onClick);
        return t;
    }

    /** The panel stays up, so several actions can run in a row; Back or the Netflix button closes it. */
    private void runAction(Store.Action action) {
        if (action.service.isEmpty()) {
            toast(getString(R.string.action_placeholder, action.label));
            return;
        }
        String url = store.haUrl(), token = store.haToken();
        calls.execute(() -> {
            String msg;
            try {
                HomeAssistant.call(url, token, action);
                msg = action.label;
            } catch (IOException e) {
                msg = getString(R.string.action_failed, action.label, e.getMessage());
            }
            String shown = msg;
            handler.post(() -> toast(shown));
        });
    }

    // -------------------------------------------------------------- ambilight

    /** The row shows what it was switched to; the capture itself starts a moment later. */
    private void toggleAmbilight() {
        boolean on = !AmbilightService.isOn();
        if (on) AmbilightService.start(this);
        else AmbilightService.stop(this);
        updateAmbilightRow(on);
    }

    private void updateAmbilightRow(boolean on) {
        if (ambilightRow == null) return;
        ambilightRow.setText(Icons.withLabel(this, "mdi:television-ambient-light",
                getString(on ? R.string.ambilight_on : R.string.ambilight_off)));
    }

    // ------------------------------------------------------------------ sleep

    private void toggleSleep() {
        if (sleepAt != 0) {
            cancelSleep();
            toast(getString(R.string.sleep_cancelled));
        } else {
            int minutes = store.sleepMinutes();
            sleepAt = SystemClock.elapsedRealtime() + minutes * MINUTE_MS;
            handler.postDelayed(sleepNow, minutes * MINUTE_MS);
            if (minutes > 1) handler.postDelayed(sleepWarning, (minutes - 1) * MINUTE_MS);
            handler.removeCallbacks(sleepRowTick);
            handler.postDelayed(sleepRowTick, MINUTE_MS);
            toast(getString(R.string.sleep_set, minutes));
        }
        updateSleepRow();
    }

    private void cancelSleep() {
        sleepAt = 0;
        handler.removeCallbacks(sleepNow);
        handler.removeCallbacks(sleepWarning);
        handler.removeCallbacks(sleepRowTick);
        updateSleepRow();
    }

    /** The Sleep row, and the header's "· SLEEP IN 42 MIN" while the timer runs. */
    private void updateSleepRow() {
        long minutesLeft = sleepAt == 0 ? 0
                : Math.max(1, (sleepAt - SystemClock.elapsedRealtime() + MINUTE_MS - 1) / MINUTE_MS);
        if (header != null) {
            header.setText(sleepAt == 0 ? deviceName : deviceName
                    + (deviceName.isEmpty() ? "" : " · ")
                    + getString(R.string.sleep_header, minutesLeft).toUpperCase());
        }
        if (sleepRow == null) return;
        String label = sleepAt == 0 ? getString(R.string.sleep_start, store.sleepMinutes())
                : getString(R.string.sleep_running, minutesLeft);
        sleepRow.setText(Icons.withLabel(this, "mdi:power-sleep", label));
    }

    /**
     * Stops playback, then sleeps the SHIELD. Sleeping also turns the TV off
     * over HDMI-CEC, as long as the SHIELD's "turn off TV" CEC setting is on.
     */
    private void sleepNow() {
        sleepAt = 0;
        hideMenu();
        cameras.hide();
        visualizer.hide();
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP);
        // Players that ignore media keys still stop when they leave the screen.
        performGlobalAction(GLOBAL_ACTION_HOME);
        // On API 28+ this puts the device to sleep, not just behind a keyguard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), SLEEP_AFTER_HOME_MS);
        }
    }

    /** The action's entity_id: one ID or a list; empty if it has none or the data is not JSON. */
    private static List<String> entityIds(Store.Action action) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject data = new JSONObject(action.data.trim().isEmpty() ? "{}" : action.data);
            Object id = data.opt("entity_id");
            if (id instanceof String) {
                out.add((String) id);
            } else if (id instanceof JSONArray) {
                JSONArray a = (JSONArray) id;
                for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    private void toast(String msg) {
        toast(msg, false);
    }

    private void toast(String msg, boolean longer) {
        Toast.makeText(this, msg, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
