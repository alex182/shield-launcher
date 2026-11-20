package dev.alex.minlauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A snapshot of the SHIELD's and the launcher's RAM, CPU, storage and network,
 * for the config page's resource monitor. Counters (CPU time, bytes) are
 * cumulative; the page turns two snapshots into rates.
 *
 * Device CPU: /proc/stat is closed to apps since Android 8, and SELinux keeps
 * apps from the cpuinfo service even with DUMP. So when it can't be read,
 * the load average and each core's clock speed are what's left; each part
 * is left out where it can't be read.
 */
final class Resources {

    private static final String TAG = "Resources";

    private Resources() { }

    static JSONObject snapshot(Context context) throws JSONException {
        JSONObject o = new JSONObject()
                .put("at", SystemClock.elapsedRealtime())
                .put("cores", Runtime.getRuntime().availableProcessors());
        o.put("device", device(context));
        o.put("app", app(context));
        return o;
    }

    private static JSONObject device(Context context) throws JSONException {
        JSONObject d = new JSONObject();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        d.put("memTotal", mi.totalMem).put("memAvailable", mi.availMem)
                .put("memLow", mi.lowMemory).put("memThreshold", mi.threshold);

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        d.put("storageTotal", fs.getTotalBytes()).put("storageFree", fs.getAvailableBytes());

        d.put("rxBytes", TrafficStats.getTotalRxBytes()).put("txBytes", TrafficStats.getTotalTxBytes());
        d.put("uptimeMs", SystemClock.elapsedRealtime());

        long[] stat = procStat();
        if (stat != null) {
            d.put("cpuBusyTicks", stat[0]).put("cpuTotalTicks", stat[1]);
        }
        String load = firstLine("/proc/loadavg");
        if (load != null) {
            String[] f = load.trim().split("\\s+");
            if (f.length >= 3) {
                d.put("load", new org.json.JSONArray().put(Double.parseDouble(f[0]))
                        .put(Double.parseDouble(f[1])).put(Double.parseDouble(f[2])));
            }
        }
        org.json.JSONArray freqs = new org.json.JSONArray();
        long maxFreq = 0;
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            String cur = firstLine(base + "scaling_cur_freq"), max = firstLine(base + "cpuinfo_max_freq");
            if (cur == null) continue;
            freqs.put(Long.parseLong(cur.trim()) / 1000);   // kHz → MHz
            if (max != null) maxFreq = Math.max(maxFreq, Long.parseLong(max.trim()) / 1000);
        }
        if (freqs.length() > 0) d.put("cpuMhz", freqs).put("cpuMaxMhz", maxFreq);
        return d;
    }

    private static JSONObject app(Context context) throws JSONException {
        JSONObject a = new JSONObject();
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        a.put("pssKb", mi.getTotalPss());
        if (Build.VERSION.SDK_INT >= 23) {
            JSONObject parts = new JSONObject();
            String[][] keys = {
                    { "native", "summary.native-heap" }, { "java", "summary.java-heap" },
                    { "graphics", "summary.graphics" }, { "code", "summary.code" },
                    { "stack", "summary.stack" }, { "other", "summary.private-other" },
                    { "system", "summary.system" } };
            for (String[] k : keys) {
                String v = mi.getMemoryStat(k[1]);
                if (v != null) parts.put(k[0], Long.parseLong(v));
            }
            a.put("pssPartsKb", parts);
        }

        long[] cpu = selfStat();
        if (cpu != null) a.put("cpuMs", cpu[0]).put("threads", cpu[1]);
        if (Build.VERSION.SDK_INT >= 24) {
            a.put("startedMs", Process.getStartElapsedRealtime());
        }

        int uid = Process.myUid();
        a.put("rxBytes", TrafficStats.getUidRxBytes(uid)).put("txBytes", TrafficStats.getUidTxBytes(uid));

        File data = context.getFilesDir().getParentFile();
        a.put("dataBytes", size(data));
        a.put("backgroundCacheBytes", size(new File(context.getFilesDir(), "backgrounds")));
        a.put("apkBytes", new File(context.getApplicationInfo().sourceDir).length());
        return a;
    }

    private static String firstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {busy, total} jiffies over all cores from /proc/stat; null where apps can't read it. */
    private static long[] procStat() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu ")) return null;
            String[] f = line.trim().split("\\s+");
            long total = 0, idle = 0;
            for (int i = 1; i < f.length && i <= 8; i++) {
                long v = Long.parseLong(f[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;   // idle, iowait
            }
            return new long[] { total - idle, total };
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {CPU ms (user + system), threads} for this process, from /proc/self. */
    private static long[] selfStat() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String line = r.readLine();
            // Fields after the ")" that closes the command name; utime and stime
            // are the 14th and 15th, num_threads the 20th, counting from pid = 1.
            String[] f = line.substring(line.lastIndexOf(')') + 2).split(" ");
            long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
            return new long[] { ticks * 10, Long.parseLong(f[17]) };   // USER_HZ is 100 on Android
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static long size(File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        File[] list = f.listFiles();
        long n = 0;
        if (list != null) for (File c : list) n += size(c);
        return n;
    }

    /**
     * A system service's dump, "" if it can't be had (a "Permission Denial"
     * line without DUMP). Through a pipe, not a file: SELinux won't let a
     * service write to our app data. Dumps can be bigger than a pipe holds, so
     * it is read on another thread while dumpService blocks writing it.
     */
    static String dump(String service) {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.w(TAG, "No pipe for the " + service + " dump", e);
            return "";
        }
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    synchronized (text) { text.write(buf, 0, n); }
                }
            } catch (IOException e) {
                Log.w(TAG, service + " dump cut short", e);
            }
        }, service + " dump");
        reader.start();
        try {
            Debug.dumpService(service, pipe[1].getFileDescriptor(), new String[0]);
        } finally {
            try { pipe[1].close(); } catch (IOException ignored) { }
        }
        try {
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (text) {
            return new String(text.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
