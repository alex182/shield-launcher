package dev.alex.minlauncher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Keeps {@link QuickMenuService} (and with it the web config page) running.
 *
 * Android turns an accessibility service off in two ways and never turns it
 * back on: a force-stop of the package (TV Settings → Apps → Force stop)
 * drops it from {@code enabled_accessibility_services}, and a crash leaves it
 * listed but never rebound. Either
 * way the process itself comes back, since it is HOME, so the launcher puts
 * the service back from here. Both also leave it marked crashed, which only
 * taking it out of the list and putting it back clears.
 * Needs WRITE_SECURE_SETTINGS, granted over adb by build.sh install.
 *
 * It also writes uncaught exceptions to {@code crash.log} in the app's files
 * (served at /api/crashlog), since logcat rolls over within hours.
 */
final class ServiceGuard {

    private static final String TAG = "ServiceGuard";
    static final String SERVICE = "dev.alex.minlauncher/dev.alex.minlauncher.QuickMenuService";
    private static final long CHECK_MS = 60_000;
    /** After the process starts, how long Android gets to bind the service on its own. */
    private static final long GRACE_MS = 20_000;
    /** Between taking the service out of the list and putting it back. */
    private static final long RELIST_MS = 1_500;
    private static final long CRASH_LOG_MAX = 64 * 1024;

    private static Handler handler;
    private static long startedAt;
    private static Context app;
    private static volatile boolean connected;
    private static long lastFix;

    private ServiceGuard() { }

    /** Starts the periodic check and the crash log; safe to call any number of times. */
    static synchronized void start(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        handler = new Handler(Looper.getMainLooper());
        startedAt = SystemClock.elapsedRealtime();
        installCrashLog(app);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                check();
                handler.postDelayed(this, CHECK_MS);
            }
        }, GRACE_MS);
    }

    static void setConnected(boolean on) { connected = on; }

    /** Puts the service back if it is off. Runs on the main thread. */
    static void check() {
        if (app == null || connected) return;
        long now = SystemClock.elapsedRealtime();
        if (now - startedAt < GRACE_MS || now - lastFix < CHECK_MS) return;
        if (app.checkPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.os.Process.myPid(), android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "quick actions service is off and WRITE_SECURE_SETTINGS is not granted");
            return;
        }
        lastFix = now;
        String list = Settings.Secure.getString(app.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        // Either way Android has also marked it crashed, and a crashed service
        // is only rebound once it has left the list and come back. A force-stop
        // drops it from the list before the crash mark lands, so then it goes
        // in, out and in again.
        boolean listed = contains(list, SERVICE);
        Log.w(TAG, listed ? "quick actions service is listed but not running; re-listing it"
                : "quick actions service was switched off; switching it back on");
        long at = 0;
        if (!listed) { relist(true, at); at += RELIST_MS; }
        relist(false, at);
        relist(true, at + RELIST_MS);
    }

    /** After the delay, puts the service into (or takes it out of) the current list. */
    private static void relist(boolean in, long delayMs) {
        handler.postDelayed(() -> {
            try {
                String now = Settings.Secure.getString(app.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                put(in ? with(now, SERVICE) : without(now, SERVICE));
            } catch (SecurityException e) {
                Log.w(TAG, "cannot write accessibility settings", e);
            }
        }, delayMs);
    }

    private static void put(String list) {
        Settings.Secure.putString(app.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
        if (!list.isEmpty()) {
            Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        }
    }

    // ----------------------------------------------------- the service list

    /** Android writes the component short ("pkg/.Cls") or long ("pkg/pkg.Cls"). */
    private static boolean same(String entry, String service) {
        return normalise(entry).equals(normalise(service));
    }

    private static String normalise(String component) {
        String c = component.trim();
        int slash = c.indexOf('/');
        if (slash > 0 && slash + 1 < c.length() && c.charAt(slash + 1) == '.') {
            c = c.substring(0, slash + 1) + c.substring(0, slash) + c.substring(slash + 1);
        }
        return c;
    }

    private static List<String> entries(String list) {
        List<String> out = new ArrayList<>();
        if (list == null || list.equals("null")) return out;
        for (String e : list.split(":")) if (!e.trim().isEmpty()) out.add(e.trim());
        return out;
    }

    static boolean contains(String list, String service) {
        for (String e : entries(list)) if (same(e, service)) return true;
        return false;
    }

    /** The list with the service appended; other apps' services are kept. */
    static String with(String list, String service) {
        if (contains(list, service)) return join(entries(list));
        List<String> e = entries(list);
        e.add(service);
        return join(e);
    }

    /** The list without the service; other apps' services are kept. */
    static String without(String list, String service) {
        List<String> out = new ArrayList<>();
        for (String e : entries(list)) if (!same(e, service)) out.add(e);
        return join(out);
    }

    private static String join(List<String> entries) {
        StringBuilder b = new StringBuilder();
        for (String e : entries) b.append(b.length() == 0 ? "" : ":").append(e);
        return b.toString();
    }

    // ------------------------------------------------------------ crash log

    static File crashLog(Context context) {
        return new File(context.getFilesDir(), "crash.log");
    }

    private static void installCrashLog(Context context) {
        Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        File file = crashLog(context);
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                if (file.length() > CRASH_LOG_MAX) file.delete();
                try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                    w.println("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date()) + " thread " + thread.getName());
                    e.printStackTrace(w);
                }
            } catch (IOException | RuntimeException ignored) {
                // Never let the log get in the way of the crash itself.
            }
            if (next != null) next.uncaughtException(thread, e);
        });
    }
}
