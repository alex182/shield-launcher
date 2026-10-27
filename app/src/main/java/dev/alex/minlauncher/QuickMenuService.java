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

/**
 * Holding the remote's Netflix button for {@link #HOLD_MS} opens a quick
 * actions panel over whatever app is in front.
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
    private static final long HOLD_MS = 3000;

    /** Placeholders until these call Home Assistant: {label, action id}. */
    private static final String[][] ACTIONS = {
            {"Action 1", "action_1"},
            {"Action 2", "action_2"},
            {"Action 3", "action_3"},
            {"Action 4", "action_4"},
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openMenu = this::showMenu;
    private WindowManager wm;
    private View panel;

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != NETFLIX_KEY) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (panel != null) {
                hideMenu();
            } else {
                handler.postDelayed(openMenu, HOLD_MS);
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

        for (String[] action : ACTIONS) box.addView(makeRow(action[0], action[1]));

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
    private TextView makeRow(String label, String actionId) {
        TextView t = new TextView(this);
        t.setText(label);
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
        t.setOnClickListener(v -> runAction(actionId, label));
        return t;
    }

    /** Placeholder: will become a Home Assistant service call. */
    private void runAction(String actionId, String label) {
        hideMenu();
        Toast.makeText(this, getString(R.string.action_placeholder, label),
                Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
