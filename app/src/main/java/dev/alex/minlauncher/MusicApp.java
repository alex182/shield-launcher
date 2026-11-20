package dev.alex.minlauncher;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Plays an artist or one of their albums in the music app chosen on the
 * config page, shuffled or in order, using only what Android offers any app
 * so it isn't tied to one service. In order of preference:
 * <ol>
 *   <li>play-from-search with the artist or album as the focus (what voice
 *       assistants send): through the app's session when it has one, so
 *       nothing comes to the front, else as an intent (TIDAL takes both);</li>
 *   <li>a link to the artist or album that the app opens and plays, from
 *       MusicBrainz via the page (Spotify's TV app plays open.spotify.com
 *       artist and album links, and ignores play-from-search);</li>
 *   <li>else the app's web search link, which may play the wrong thing.</li>
 * </ol>
 * Then the shuffle mode: the media library's "set shuffle mode" command,
 * and if the app has its own Shuffle toggle, that when its icon (e.g.
 * TIDAL's ic_shuffle / ic_shuffle_active) says it's the wrong way round.
 * A request returns at once; {@link #status} reports progress.
 */
final class MusicApp {

    private static final String TAG = "MusicApp";
    private static final long WAIT_MS = 20_000;
    /** Tried first, in this order, when nothing is chosen. */
    static final List<String> PREFERRED = Collections.unmodifiableList(Arrays.asList(
            "com.aspiro.tidal", "com.spotify.tv.android", "com.spotify.music"));
    // MediaSessionCompat (AndroidX) takes these as custom actions.
    private static final String COMPAT_SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE";
    private static final String COMPAT_SHUFFLE_ARG = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE";
    private static final int SHUFFLE_MODE_NONE = 0, SHUFFLE_MODE_ALL = 1;
    private static final Pattern ICON_ON = Pattern.compile("(active|enabled|selected|checked|_on$|_on_)");

    /** What the last request did, for the page; "" before any. */
    static volatile String status = "";

    private MusicApp() { }

    // ------------------------------------------------------------- choosing

    /**
     * Installed apps that can be asked to play something: they take
     * play-from-search, or have a media browser service, and show on the
     * home screen. {pkg: label}, the preferred ones first.
     */
    static Map<String, String> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> pkgs = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)) {
            pkgs.add(r.activityInfo.packageName);
        }
        for (ResolveInfo r : pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
            pkgs.add(r.serviceInfo.packageName);
        }
        Map<String, String> out = new LinkedHashMap<>();
        List<String> order = new ArrayList<>(PREFERRED);
        Collections.sort(pkgs);
        order.addAll(pkgs);
        for (String p : order) {
            if (!pkgs.contains(p) || out.containsKey(p) || p.equals(c.getPackageName())) continue;
            if (pm.getLeanbackLaunchIntentForPackage(p) == null && pm.getLaunchIntentForPackage(p) == null) continue;
            try {
                out.put(p, pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString());
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return out;
    }

    /** The chosen app if it's installed, else the first preferred one there is; "" if none. */
    static String chosen(Context c, String saved) {
        Map<String, String> apps = installed(c);
        if (!saved.isEmpty() && apps.containsKey(saved)) return saved;
        for (String p : PREFERRED) if (apps.containsKey(p)) return p;
        return "";
    }

    static JSONArray list(Context c) throws JSONException {
        JSONArray a = new JSONArray();
        for (Map.Entry<String, String> e : installed(c).entrySet()) {
            a.put(new JSONObject().put("pkg", e.getKey()).put("label", e.getValue()));
        }
        return a;
    }

    // -------------------------------------------------------------- playing

    /** What to play: an artist, or an album of theirs ({@code album} non-empty). */
    static final class Request {
        final String artist, album;
        /** Web links to it (open.spotify.com, tidal.com, …); the first the app opens is used. */
        final List<String> links;
        final boolean shuffle;
        Request(String artist, String album, List<String> links, boolean shuffle) {
            this.artist = artist.trim();
            this.album = album == null ? "" : album.trim();
            this.links = links;
            this.shuffle = shuffle;
        }
        String what() { return album.isEmpty() ? artist : album + " by " + artist; }
    }

    static void play(Context context, String pkg, Request req) {
        Context c = context.getApplicationContext();
        String label = installed(c).get(pkg);
        if (label == null) {
            status = "No music app chosen (or it's not installed)";
            return;
        }
        status = "Asking " + label + " for " + req.what() + "…";
        new Thread(() -> run(c, pkg, label, req), "music-app").start();
    }

    private static void run(Context c, String pkg, String label, Request req) {
        boolean album = !req.album.isEmpty();
        String query = album ? req.album + " " + req.artist : req.artist;
        Bundle extras = new Bundle();
        extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, album
                ? MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE : MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE);
        extras.putString(MediaStore.EXTRA_MEDIA_ARTIST, req.artist);
        if (album) extras.putString(MediaStore.EXTRA_MEDIA_ALBUM, req.album);
        extras.putString(SearchManager.QUERY, query);

        MediaController before = session(c, pkg);
        String keyBefore = before == null ? "" : key(before);
        PlaybackState stBefore = before == null ? null : before.getPlaybackState();
        boolean wasPlaying = stBefore != null && stBefore.getState() == PlaybackState.STATE_PLAYING;
        long posBefore = stBefore == null ? -1 : stBefore.getPosition();
        String how, link;
        if (before != null && takesSearch(before)) {
            before.getTransportControls().playFromSearch(query, extras);
            how = "its session";
        } else if (start(c, new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(pkg).putExtras(extras))) {
            how = "play-from-search";
        } else if ((link = linkFor(c, pkg, req.links)) != null) {
            start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg));
            how = "a link";
        } else if (start(c, new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://open.spotify.com/search/" + Uri.encode(query))).setPackage(pkg))) {
            // Spotify plays a search link's top result, which may not be this artist or album.
            how = "a search: MusicBrainz has no link for it, so it may be the wrong one";
        } else {
            status = label + " can't be asked to play " + req.what();
            Log.i(TAG, status);
            return;
        }
        Log.i(TAG, "asked " + pkg + " for " + req.what() + " via " + how);
        status = "Asked " + label + " for " + req.what() + " (" + how + ")…";

        MediaController mc = waitForPlaying(c, pkg, keyBefore, wasPlaying, posBefore);
        if (mc == null) {
            status = label + " didn't start playing " + req.what() + " within " + WAIT_MS / 1000 + " s";
            Log.w(TAG, status);
            return;
        }
        MediaMetadata md = mc.getMetadata();
        String now = MediaNow.text(md, album ? MediaMetadata.METADATA_KEY_ALBUM : MediaMetadata.METADATA_KEY_ARTIST);
        status = "Playing " + (now.isEmpty() ? req.what() : now) + " on " + label + ", "
                + shuffle(c, pkg, mc, req.shuffle);
        Log.i(TAG, status);
    }

    /** The first link {@code pkg} opens itself, or null. */
    private static String linkFor(Context c, String pkg, List<String> links) {
        for (String l : links) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(l)).setPackage(pkg);
            if (i.resolveActivity(c.getPackageManager()) != null) return l;
        }
        return null;
    }

    /** Sets shuffle {@code on} or off; says what it did. */
    private static String shuffle(Context c, String pkg, MediaController mc, boolean on) {
        String word = on ? "shuffle on" : "in order";
        Bundle arg = new Bundle();
        arg.putInt(COMPAT_SHUFFLE_ARG, on ? SHUFFLE_MODE_ALL : SHUFFLE_MODE_NONE);
        mc.getTransportControls().sendCustomAction(COMPAT_SET_SHUFFLE, arg);
        SystemClock.sleep(800);

        PlaybackState.CustomAction toggle = shuffleAction(mc);
        if (toggle == null) return word + " (asked for)";   // only the library command, which can't be read back
        Boolean is = iconOn(c, pkg, toggle);
        if (is == null) return word + " (asked for; its own button's state can't be read)";
        if (is == on) return word;
        mc.getTransportControls().sendCustomAction(toggle, null);
        SystemClock.sleep(800);
        PlaybackState.CustomAction after = shuffleAction(mc);
        Boolean now = after == null ? null : iconOn(c, pkg, after);
        return now != null && now == on ? word : word + " (switched, not confirmed)";
    }

    private static PlaybackState.CustomAction shuffleAction(MediaController mc) {
        PlaybackState st = mc.getPlaybackState();
        if (st == null) return null;
        for (PlaybackState.CustomAction a : st.getCustomActions()) {
            String n = (a.getName() + " " + a.getAction()).toLowerCase(Locale.ROOT);
            if (n.contains("shuffle")) return a;
        }
        return null;
    }

    /** Whether a toggle's icon is its "on" one, from the drawable's name; null if unknown. */
    private static Boolean iconOn(Context c, String pkg, PlaybackState.CustomAction a) {
        try {
            Resources r = c.getPackageManager().getResourcesForApplication(pkg);
            String name = r.getResourceEntryName(a.getIcon()).toLowerCase(Locale.ROOT);
            if (name.contains("off") || name.contains("disabled") || name.contains("inactive")) return false;
            return ICON_ON.matcher(name).find();
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return null;
        }
    }

    private static boolean takesSearch(MediaController mc) {
        PlaybackState st = mc.getPlaybackState();
        return st != null && (st.getActions() & PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0;
    }

    private static boolean start(Context c, Intent i) {
        if (i.resolveActivity(c.getPackageManager()) == null) return false;
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot start " + i, e);
            return false;
        }
    }

    /**
     * Waits until the app plays what was asked for: a different track, or the
     * same one started over (an album already paused on its first track), or
     * playing where it wasn't.
     */
    private static MediaController waitForPlaying(Context c, String pkg, String keyBefore,
            boolean wasPlaying, long posBefore) {
        long until = SystemClock.elapsedRealtime() + WAIT_MS;
        while (SystemClock.elapsedRealtime() < until) {
            MediaController s = session(c, pkg);
            PlaybackState st = s == null ? null : s.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                boolean restarted = posBefore > 5_000 && st.getPosition() >= 0 && st.getPosition() < posBefore - 3_000;
                if (!key(s).equals(keyBefore) || !wasPlaying || restarted) return s;
            }
            SystemClock.sleep(500);
        }
        return null;
    }

    /** The app's media session, or null (none, or no notification-listener access). */
    static MediaController session(Context c, String pkg) {
        MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            for (MediaController mc : m.getActiveSessions(new ComponentName(c, MediaListener.class))) {
                if (pkg.equals(mc.getPackageName())) return mc;
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    /** Changes with the track or the album. */
    private static String key(MediaController mc) {
        MediaMetadata md = mc.getMetadata();
        return MediaNow.text(md, MediaMetadata.METADATA_KEY_TITLE) + "\n"
                + MediaNow.text(md, MediaMetadata.METADATA_KEY_ARTIST) + "\n"
                + MediaNow.text(md, MediaMetadata.METADATA_KEY_ALBUM);
    }
}
