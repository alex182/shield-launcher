package dev.alex.minlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * What's playing, and its controls, for the visualizer's on-screen media
 * controls. Reads the media sessions ({@link MediaSessionManager}), which
 * needs this app's {@link MediaListener} allowed as a notification listener
 * ({@code cmd notification allow_listener}, done by {@code build.sh install}).
 * Without it there's no track info, and the controls fall back to media keys,
 * which reach whichever player has the media session.
 */
final class MediaNow {

    private MediaNow() { }

    private static final int ART_PX = 256;

    /** The art last turned into a data: URL, and for which track, so it's done once a track. */
    private static String artKey = "", artUrl = "";

    /** The session to show: one that's playing, else the most recent. Null: none, or no access. */
    private static MediaController active(Context c) {
        MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        List<MediaController> list;
        try {
            list = m.getActiveSessions(new ComponentName(c, MediaListener.class));
        } catch (SecurityException e) {
            return null;
        }
        for (MediaController mc : list) {
            PlaybackState s = mc.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return mc;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * {access, app, title, artist, album, playing, position, duration (ms;
     * -1 unknown), speed, key (changes with the track), art (a data: URL key
     * for {@link #art})}. {@code access} false: no notification-listener
     * access, so nothing else is known.
     */
    static JSONObject info(Context c) {
        JSONObject o = new JSONObject();
        try {
            MediaController mc = active(c);
            if (mc == null) return o.put("access", hasAccess(c));
            o.put("access", true);
            MediaMetadata md = mc.getMetadata();
            PlaybackState st = mc.getPlaybackState();
            String title = text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            String album = text(md, MediaMetadata.METADATA_KEY_ALBUM);
            long duration = md == null ? -1 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            long pos = st == null ? -1 : st.getPosition();
            if (st != null && playing && pos >= 0 && st.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
            if (duration > 0 && pos > duration) pos = duration;
            String pkg = mc.getPackageName();
            o.put("app", label(c, pkg)).put("title", title).put("artist", artist).put("album", album)
                    .put("playing", playing).put("position", pos).put("duration", duration <= 0 ? -1 : duration)
                    .put("speed", st == null ? 0 : st.getPlaybackSpeed())
                    .put("key", pkg + "\n" + title + "\n" + artist + "\n" + album);
            o.put("art", artFor(md, pkg + "\n" + title + "\n" + album) ? artKey : "");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    /** The current track's art as a data: URL, "" for none; asked for when {@code info}'s art changes. */
    static synchronized String art() { return artUrl; }

    /** "prev", "toggle", "next": to the session if there's access, else as a media key. */
    static void action(Context c, String what) {
        MediaController mc = active(c);
        if (mc != null) {
            MediaController.TransportControls t = mc.getTransportControls();
            PlaybackState st = mc.getPlaybackState();
            switch (what) {
                case "prev": t.skipToPrevious(); return;
                case "next": t.skipToNext(); return;
                case "toggle":
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause();
                    else t.play();
                    return;
                default: return;
            }
        }
        int key = what.equals("prev") ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : what.equals("next") ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private static boolean hasAccess(Context c) {
        try {
            ((MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE))
                    .getActiveSessions(new ComponentName(c, MediaListener.class));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Turns the track's art into a small JPEG data: URL once a track; false if it has none. */
    private static synchronized boolean artFor(MediaMetadata md, String key) {
        if (key.equals(artKey)) return !artUrl.isEmpty();
        artKey = key;
        artUrl = "";
        Bitmap b = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null && md != null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null && md != null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b == null) return false;
        float scale = Math.min(1f, (float) ART_PX / Math.max(b.getWidth(), b.getHeight()));
        Bitmap small = scale < 1
                ? Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)),
                        Math.max(1, Math.round(b.getHeight() * scale)), true)
                : b;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 85, out);
        artUrl = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        return true;
    }

    private static String text(MediaMetadata md, String... keys) {
        if (md == null) return "";
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
