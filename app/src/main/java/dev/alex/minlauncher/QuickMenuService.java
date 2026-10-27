package dev.alex.minlauncher;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holding the remote's Netflix button (3 s by default) opens a quick actions
 * panel over whatever app is in front. Actions call Home Assistant. The
 * service also hosts the web config page, since it is the one component that
 * is always running.
 *
 * An accessibility service is the only way a non-system app sees keys meant
 * for other apps, and its TYPE_ACCESSIBILITY_OVERLAY window needs no
 * SYSTEM_ALERT_WINDOW grant. Enable it over adb (see README).
 *
 * Short presses are deliberately left alone: Button Mapper / Buttons remapper
 * already open Jellyfin on them, and every filtering service sees each key, so
 * consuming it here would not stop them anyway.
 */
public class QuickMenuService extends AccessibilityService {

    /** 2019 SHIELD remote: the Netflix key is scancode 393 → BUTTON_12. */
    private static final int NETFLIX_KEY = KeyEvent.KEYCODE_BUTTON_12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openMenu = this::showMenu;
    private final ExecutorService calls = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private View panel;
    private Store store;
    private WebConfigServer web;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        store = new Store(this);
        web = new WebConfigServer(this, store);
        web.start();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != NETFLIX_KEY) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (panel != null) {
                hideMenu();
            } else {
                handler.postDelayed(openMenu, store.holdMs());
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(openMenu);
        }
        return false;   // let the remappers keep their short press
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(openMenu);
        hideMenu();
        if (web != null) web.stop();
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
        TextView sub = new TextView(this);
        sub.setText(name == null ? "" : name.toUpperCase());
        sub.setTextSize(14);
        sub.setTextColor(getResources().getColor(R.color.dim));
        sub.setPadding(0, dp(2), 0, dp(24));
        box.addView(sub);

        for (Store.Action action : store.actions()) box.addView(makeRow(action));

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
        box.post(() -> { if (box.getChildCount() > 2) box.getChildAt(2).requestFocus(); });
    }

    private void hideMenu() {
        if (panel == null) return;
        wm.removeView(panel);
        panel = null;
    }

    @SuppressWarnings("deprecation")
    private TextView makeRow(Store.Action action) {
        TextView t = new TextView(this);
        t.setText(action.label);
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
        t.setOnClickListener(v -> runAction(action));
        return t;
    }

    private void runAction(Store.Action action) {
        hideMenu();
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
