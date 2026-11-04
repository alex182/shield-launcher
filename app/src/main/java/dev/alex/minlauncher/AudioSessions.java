package dev.alex.minlauncher;

import android.content.Context;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The audio sessions playing right now, and which app plays each, read from
 * {@code dumpsys media.audio_flinger}: the one place a non-system app can
 * learn another app's session. The visualizer attaches to that session,
 * because on the SHIELD media goes out through a bus device that the
 * output-mix capture (session 0) is not on; see {@link VisualizerOverlay}.
 */
final class AudioSessions {

    private static final String TAG = "AudioSessions";

    private AudioSessions() { }

    static final class Playing {
        final int session, pid;
        /** The app's package, or "" when the dump doesn't name the pid. */
        final String pkg;
        Playing(int session, int pid, String pkg) { this.session = session; this.pid = pid; this.pkg = pkg; }
        @Override public boolean equals(Object o) {
            return o instanceof Playing && ((Playing) o).session == session && ((Playing) o).pid == pid
                    && ((Playing) o).pkg.equals(pkg);
        }
        @Override public int hashCode() { return session * 31 + pid; }
        @Override public String toString() { return session + "/" + pid + "/" + pkg; }
    }

    /**
     * A row of an output thread's track table: an optional type letter (F
     * fast, S static, P patch), then id, active, client pid, session. Record
     * threads put Active first, so their rows don't match.
     */
    private static final Pattern TRACK =
            Pattern.compile("^\\s*(?:[A-Z]\\s+)?\\d+\\s+(yes|no)\\s+(\\d+)\\s+(\\d+)\\s");
    /** A row of the "Notification Clients" table: pid, uid, name. */
    private static final Pattern CLIENT = Pattern.compile("^\\s*(\\d+)\\s+\\d+\\s+(\\S+)\\s*$");

    /**
     * Active playback tracks, one per session, in dump order. Session 0 (the
     * system's own patch tracks) and our own process (the alert tone, the
     * chime) are left out.
     */
    static List<Playing> active(String dump, int ownPid) {
        Map<Integer, String> names = new HashMap<>();
        List<int[]> tracks = new ArrayList<>();   // {session, pid}
        boolean inTracks = false, inClients = false;
        for (String line : dump.split("\n")) {
            if (line.startsWith("Notification Clients:")) {
                inClients = true;
                continue;
            }
            if (inClients) {
                Matcher c = CLIENT.matcher(line);
                if (c.find()) names.put(Integer.parseInt(c.group(1)), c.group(2));
                else if (!line.trim().startsWith("pid")) inClients = false;
                continue;
            }
            if (line.contains("Id Active Client Session")) {
                inTracks = true;
                continue;
            }
            if (!inTracks) continue;
            Matcher m = TRACK.matcher(line);
            if (!m.find()) {
                inTracks = false;
                continue;
            }
            int pid = Integer.parseInt(m.group(2));
            int session = Integer.parseInt(m.group(3));
            if (m.group(1).equals("yes") && session != 0 && pid != ownPid) tracks.add(new int[] { session, pid });
        }
        List<Playing> out = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        for (int[] t : tracks) {
            if (seen.contains(t[0])) continue;
            seen.add(t[0]);
            String pkg = names.get(t[1]);
            out.add(new Playing(t[0], t[1], pkg == null ? "" : pkg));
        }
        return out;
    }

    /** The dump said the permission is missing. */
    static boolean denied(String dump) { return dump.startsWith("Permission Denial"); }

    /** audio_flinger's dump, "" if it can't be had; a "Permission Denial" line without DUMP. */
    static String dump(Context context) {
        // A pipe, not a file: SELinux won't let audioserver write to our app
        // data. The dump is bigger than a pipe holds, so it is read on another
        // thread while dumpService blocks writing it.
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.w(TAG, "No pipe for the audio_flinger dump", e);
            return "";
        }
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) text.write(buf, 0, n);
            } catch (IOException e) {
                Log.w(TAG, "audio_flinger dump cut short", e);
            }
        }, "audio_flinger dump");
        reader.start();
        try {
            Debug.dumpService("media.audio_flinger", pipe[1].getFileDescriptor(), new String[0]);
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

    static List<Playing> read(Context context) {
        return active(dump(context), Process.myPid());
    }
}
