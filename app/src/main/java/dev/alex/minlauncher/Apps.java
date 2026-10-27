package dev.alex.minlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launchable apps, shared by the home screen and the web config page. */
final class Apps {

    static final class App {
        final String label, pkg;
        final ActivityInfo info;
        App(String label, String pkg, ActivityInfo info) {
            this.label = label; this.pkg = pkg; this.info = info;
        }
    }

    private Apps() { }

    /**
     * Collects launchable apps by package. Queries LEANBACK_LAUNCHER *and*
     * LAUNCHER, because sideloaded phone apps (Button Mapper, for one) only
     * ever declare the plain LAUNCHER category and would otherwise be
     * invisible here. Leanback is queried first so its activity, which is the
     * one carrying the banner, wins.
     */
    static Map<String, App> collect(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        String self = c.getPackageName();
        String settingsPkg = settingsPackage(c);

        for (String category : new String[] {
                Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || pkg.equals(settingsPkg)) continue;
                if (byPkg.containsKey(pkg)) continue;
                byPkg.put(pkg, new App(ri.loadLabel(pm).toString(), pkg, ri.activityInfo));
            }
        }
        return byPkg;
    }

    static List<App> sorted(List<App> apps) {
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    /** Starts an app by package, preferring its TV entry point. False if it has none. */
    static boolean launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return true;
    }

    /** Package that ACTION_SETTINGS opens, so the list can avoid duplicating it. */
    private static String settingsPackage(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(
                new Intent(Settings.ACTION_SETTINGS), 0);
        return (ri == null || ri.activityInfo == null) ? null : ri.activityInfo.packageName;
    }
}
