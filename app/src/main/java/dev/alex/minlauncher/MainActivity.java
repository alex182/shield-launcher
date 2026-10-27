package dev.alex.minlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A deliberately minimal Android TV home screen: a clock, pinned apps, then
 * everything else. No rows of recommendations, no sponsored content, no
 * network access of any kind.
 */
public class MainActivity extends Activity {

    /** Pinned to the top, in this order, when installed. */
    private static final String[] FAVORITES = {
            "org.jellyfin.androidtv",
            "com.aspiro.tidal",
    };

    private LinearLayout rows;
    private ScrollView scroller;
    private TextView clock, date;
    private BroadcastReceiver timeTick;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        rows = findViewById(R.id.rows);
        scroller = findViewById(R.id.scroller);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);

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
        buildList();   // rebuild so installs/uninstalls show up immediately
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(timeTick); } catch (IllegalArgumentException ignored) { }
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                .format(now).toUpperCase(Locale.getDefault()));
    }

    // ---------------------------------------------------------------- app list

    private static final class App {
        final String label, pkg;
        App(String label, String pkg) { this.label = label; this.pkg = pkg; }
    }

    /**
     * Collects launchable apps. Queries LEANBACK_LAUNCHER *and* LAUNCHER,
     * because sideloaded phone apps (Button Mapper, for one) only ever declare
     * the plain LAUNCHER category and would otherwise be invisible here.
     */
    private List<App> collectApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        String self = getPackageName();
        String settingsPkg = settingsPackage();

        for (String category : new String[] {
                Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || pkg.equals(settingsPkg)) continue;
                if (byPkg.containsKey(pkg)) continue;
                byPkg.put(pkg, new App(ri.loadLabel(pm).toString(), pkg));
            }
        }

        List<App> ordered = new ArrayList<>();
        for (String fav : FAVORITES) {
            App a = byPkg.remove(fav);
            if (a != null) ordered.add(a);
        }
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
        ordered.addAll(rest);
        return ordered;
    }

    /** Package that ACTION_SETTINGS opens, so the list can avoid duplicating it. */
    private String settingsPackage() {
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Settings.ACTION_SETTINGS), 0);
        return (ri == null || ri.activityInfo == null) ? null : ri.activityInfo.packageName;
    }

    private void buildList() {
        rows.removeAllViews();
        List<App> apps = collectApps();
        int favCount = 0;
        for (String fav : FAVORITES) {
            for (App a : apps) if (a.pkg.equals(fav)) { favCount++; break; }
        }

        for (int i = 0; i < apps.size(); i++) {
            if (i == favCount && favCount > 0) addDivider();
            App a = apps.get(i);
            rows.addView(makeRow(a.label, i < favCount, v -> launch(a.pkg)));
        }

        addDivider();
        rows.addView(makeRow(getString(R.string.settings), false,
                v -> startActivity(new Intent(Settings.ACTION_SETTINGS))));

        // requestFocus() is a no-op on views that have not been laid out yet,
        // so post it. Without this the framework picks its own default and
        // focus lands on the last row instead of the first.
        rows.post(() -> {
            if (rows.getChildCount() == 0) return;
            scroller.scrollTo(0, 0);
            rows.getChildAt(0).requestFocus();
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
    private TextView makeRow(String text, boolean primary, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(primary ? 30 : 20);
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, "Cannot launch " + pkg, Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
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
