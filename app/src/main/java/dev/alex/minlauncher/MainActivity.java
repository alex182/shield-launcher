package dev.alex.minlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import dev.alex.minlauncher.Apps.App;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately minimal Android TV home screen: a clock, pinned apps as
 * banners, then everything else. No rows of recommendations, no sponsored
 * content.
 *
 * Pins and hidden apps are edited from the remote (long-press any app) or the
 * web config page, and stored per device, so one APK serves every SHIELD.
 */
public class MainActivity extends Activity {

    private LinearLayout rows;
    private ScrollView scroller;
    private TextView clock, date;
    private BroadcastReceiver timeTick;
    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rebuild = this::rebuildKeepingFocus;
    /** Pins and hides can change from the web page while this is showing. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged =
            (p, key) -> {
                if (!Store.KEY_PINNED.equals(key) && !Store.KEY_HIDDEN.equals(key)) return;
                handler.removeCallbacks(rebuild);
                handler.post(rebuild);   // one rebuild per batch of web edits
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        rows = findViewById(R.id.rows);
        scroller = findViewById(R.id.scroller);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);

        store = new Store(this);

        timeTick = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { updateClock(); }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        // ACTION_TIME_TICK only arrives while registered at runtime; it cannot
        // be declared in the manifest.
        registerReceiver(timeTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        buildList(null);   // rebuild so installs/uninstalls show up immediately
        store.prefs().registerOnSharedPreferenceChangeListener(prefsChanged);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(timeTick); } catch (IllegalArgumentException ignored) { }
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsChanged);
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                .format(now).toUpperCase(Locale.getDefault()));
    }

    private void rebuildKeepingFocus() {
        View f = getCurrentFocus();
        buildList(f != null && f.getTag() instanceof String ? (String) f.getTag() : null);
    }

    /** Rebuilds the screen, then focuses {@code focusPkg} if given, else the first item. */
    private void buildList(String focusPkg) {
        rows.removeAllViews();
        Map<String, App> all = Apps.collect(this);

        List<App> pins = new ArrayList<>();
        for (String p : store.pinned()) {
            App a = all.remove(p);
            if (a != null) pins.add(a);
        }
        Set<String> hiddenPkgs = store.hidden();
        List<App> hiddenApps = new ArrayList<>();
        List<App> rest = new ArrayList<>();
        for (App a : all.values()) (hiddenPkgs.contains(a.pkg) ? hiddenApps : rest).add(a);

        if (!pins.isEmpty()) {
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setClipChildren(false);
            for (App a : pins) strip.addView(makeBanner(a));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            // Room for the focused banner to scale up without being clipped.
            hs.setPadding(dp(8), dp(10), dp(8), dp(4));
            hs.addView(strip);
            rows.addView(hs);
            addDivider();
        }

        for (App a : Apps.sorted(rest)) {
            View row = makeRow(a.label, v -> launch(a.pkg));
            row.setTag(a.pkg);
            row.setOnLongClickListener(v -> { showAppMenu(a); return true; });
            rows.addView(row);
        }

        addDivider();
        if (!hiddenApps.isEmpty()) {
            rows.addView(makeRow(getString(R.string.hidden_apps, hiddenApps.size()),
                    v -> showHiddenMenu(Apps.sorted(hiddenApps))));
        }
        rows.addView(makeRow(getString(R.string.settings),
                v -> startActivity(new Intent(Settings.ACTION_SETTINGS))));

        // requestFocus() is a no-op on views that have not been laid out yet,
        // so post it. Without this the framework picks its own default and
        // focus lands on the last row instead of the first.
        rows.post(() -> {
            View target = focusPkg == null ? null : rows.findViewWithTag(focusPkg);
            if (target == null) {
                scroller.scrollTo(0, 0);
                target = rows.getChildCount() == 0 ? null : rows.getChildAt(0);
            }
            if (target != null) target.requestFocus();
        });
    }

    private void addDivider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(14);
        lp.rightMargin = dp(420);
        v.setLayoutParams(lp);
        v.setBackgroundColor(0xFF1E1E1E);
        rows.addView(v);
    }

    @SuppressWarnings("deprecation")
    private TextView makeRow(String text, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(20);
        t.setTextColor(getResources().getColorStateList(R.color.row_text));
        t.setBackgroundResource(R.drawable.row_bg);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setSingleLine(true);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        t.setOnClickListener(onClick);
        return t;
    }

    /**
     * A pinned app as its 16:9 TV banner. Apps without one (sideloaded phone
     * apps) get their icon on a dark tile with the label underneath.
     */
    @SuppressWarnings("deprecation")
    private View makeBanner(App a) {
        PackageManager pm = getPackageManager();
        Drawable banner = a.info.loadBanner(pm);

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(R.drawable.banner_bg);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setTag(a.pkg);
        tile.setOnClickListener(v -> launch(a.pkg));
        tile.setOnLongClickListener(v -> { showAppMenu(a); return true; });
        tile.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.06f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();
        });

        ImageView img = new ImageView(this);
        img.setLayoutParams(new LinearLayout.LayoutParams(dp(224), dp(126)));
        if (banner != null) {
            img.setImageDrawable(banner);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            img.setImageDrawable(a.info.loadIcon(pm));
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setBackgroundColor(0xFF1E1E1E);
            img.setPadding(dp(32), dp(20), dp(32), dp(20));
        }
        tile.addView(img);

        if (banner == null) {
            TextView label = new TextView(this);
            label.setText(a.label);
            label.setTextSize(14);
            label.setTextColor(getResources().getColor(R.color.fg));
            label.setSingleLine(true);
            label.setPadding(dp(4), dp(6), dp(4), dp(2));
            tile.addView(label);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(16);
        tile.setLayoutParams(lp);
        return tile;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void launch(String pkg) {
        if (!Apps.launch(this, pkg)) {
            Toast.makeText(this, "Cannot launch " + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ menus

    /** Long-press menu: pin/unpin, reorder pins, hide. */
    private void showAppMenu(App a) {
        List<String> pins = store.pinned();
        int at = pins.indexOf(a.pkg);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (at < 0) {
            labels.add(getString(R.string.pin));
            actions.add(() -> { pins.add(a.pkg); store.setPinned(pins); });
        } else {
            if (at > 0) {
                labels.add(getString(R.string.move_left));
                actions.add(() -> { Collections.swap(pins, at, at - 1); store.setPinned(pins); });
            }
            if (at < pins.size() - 1) {
                labels.add(getString(R.string.move_right));
                actions.add(() -> { Collections.swap(pins, at, at + 1); store.setPinned(pins); });
            }
            labels.add(getString(R.string.unpin));
            actions.add(() -> { pins.remove(a.pkg); store.setPinned(pins); });
        }
        labels.add(getString(R.string.hide));
        actions.add(() -> {
            pins.remove(a.pkg);
            store.setPinned(pins);
            Set<String> h = store.hidden();
            h.add(a.pkg);
            store.setHidden(h);
        });

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    actions.get(which).run();
                    buildList(a.pkg);
                })
                .show();
    }

    /** Lists hidden apps; picking one unhides it. */
    private void showHiddenMenu(List<App> hiddenApps) {
        String[] labels = new String[hiddenApps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = hiddenApps.get(i).label;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.unhide_title)
                .setItems(labels, (d, which) -> {
                    String pkg = hiddenApps.get(which).pkg;
                    Set<String> h = store.hidden();
                    h.remove(pkg);
                    store.setHidden(h);
                    buildList(pkg);
                })
                .show();
    }

    // ------------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // The remote's menu button is the only spare key worth binding.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** This activity is HOME; there is nowhere to go back to. */
    @Override
    public void onBackPressed() { }
}
