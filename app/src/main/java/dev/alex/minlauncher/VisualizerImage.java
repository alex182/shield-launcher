package dev.alex.minlauncher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The visualizer's source images: pictures, uploaded on the config page,
 * that the page feeds into the preset's feedback for it to melt and mix,
 * one at a time, cycling in upload order (how and when, by
 * {@link Store#visualizer()}'s image settings). Kept as files in the app's
 * own storage, not in the prefs, so they stay out of SharedPreferences and
 * the settings backup. Each is named by an id, its upload time in ms, so a
 * listing sorted by name is upload order. The config page scales them down
 * before upload; this only checks they decode.
 *
 * A built-in set ships in the APK (assets/visualizer/defaults/, made by
 * scripts/generate_visualizer_images.py) and is copied in the first time
 * the folder is used, after any images already there, so it can be pruned
 * like uploads. Which built-in image became which id is remembered: an
 * update that ships new ones adds just those (once per launch, checked), a
 * removed one stays removed, and {@link #restoreDefaults} brings back the
 * removed ones.
 */
final class VisualizerImage {

    /** One upload's limit; the page sends about 1280 px on the long side, well under this. */
    static final int MAX_BYTES = 6 * 1024 * 1024;
    static final int MAX_IMAGES = 50;
    private static final String DIR = "visualizer-images";
    /** Where the one image went before there could be several. */
    private static final String OLD = "visualizer-image";
    private static final String DEFAULTS_ASSETS = "visualizer/defaults";
    /** JSON: built-in image file name → the id it was copied in as. Absent until the first copy. */
    private static final String DEFAULTS_STATE = "visualizer-defaults.json";
    /** Whether this process has looked for newly shipped built-in images yet. */
    private static boolean defaultsChecked;

    private VisualizerImage() { }

    /** An id is digits only, so it can't name a file outside the folder. */
    static boolean validId(String id) { return id != null && id.matches("\\d{1,19}"); }

    private static synchronized File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        File old = new File(c.getFilesDir(), OLD);
        if (old.isFile()) //noinspection ResultOfMethodCallIgnored
            old.renameTo(new File(d, String.valueOf(old.lastModified())));
        if (!defaultsChecked) {
            defaultsChecked = true;
            installDefaults(c, d, false);
        }
        return d;
    }

    /** Copies in the built-in images that aren't there (all of them, the first time); returns how many. */
    static synchronized int restoreDefaults(Context c) {
        return installDefaults(c, dir(c), true);
    }

    /**
     * Copies in built-in images: with {@code restore}, every one that isn't
     * there; without, only ones never copied in before (new in this APK).
     */
    private static int installDefaults(Context c, File d, boolean restore) {
        File stateFile = new File(c.getFilesDir(), DEFAULTS_STATE);
        JSONObject state = new JSONObject();
        byte[] saved = readFile(stateFile);
        if (saved != null) {
            try { state = new JSONObject(new String(saved, StandardCharsets.UTF_8)); }
            catch (JSONException ignored) { }
        }
        String[] names;
        try { names = c.getAssets().list(DEFAULTS_ASSETS); } catch (IOException e) { names = null; }
        if (names == null) names = new String[0];
        Arrays.sort(names);   // NN_name.png: the order they cycle in
        String[] present = d.list();
        int count = present == null ? 0 : present.length;
        long next = System.currentTimeMillis();
        int added = 0;
        for (String n : names) {
            if (!n.matches("(?i).+\\.(png|jpe?g|webp)")) continue;
            String had = state.optString(n, "");
            if (!restore && state.has(n)) continue;
            if (validId(had) && new File(d, had).isFile()) continue;
            if (count >= MAX_IMAGES) break;
            while (new File(d, String.valueOf(next)).exists()) next++;
            String id = String.valueOf(next++);
            File tmp = new File(d, id + ".tmp");
            try (InputStream in = c.getAssets().open(DEFAULTS_ASSETS + "/" + n);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                continue;
            }
            if (!tmp.renameTo(new File(d, id))) continue;
            try { state.put(n, id); } catch (JSONException ignored) { }
            count++;
            added++;
        }
        try (FileOutputStream out = new FileOutputStream(stateFile)) {
            out.write(state.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        return added;
    }

    /** The images' ids, in upload order. */
    static List<String> ids(Context c) {
        List<String> out = new ArrayList<>();
        String[] names = dir(c).list();
        if (names != null) for (String n : names) if (validId(n)) out.add(n);
        // Same length (13 digits) for centuries, but sort by value to be sure.
        Collections.sort(out, (a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
        return out;
    }

    /** Adds one; throws if the bytes aren't one Android can decode, or there are too many. Returns its id. */
    static synchronized String add(Context c, byte[] bytes) throws IOException {
        if (ids(c).size() >= MAX_IMAGES) throw new IOException("There are already " + MAX_IMAGES + " images; remove some first");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("That isn't an image this can read");
        File d = dir(c);
        long id = System.currentTimeMillis();
        while (new File(d, String.valueOf(id)).exists()) id++;
        File tmp = new File(d, id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(new File(d, String.valueOf(id)))) throw new IOException("Could not save the image");
        return String.valueOf(id);
    }

    static void remove(Context c, String id) {
        if (validId(id)) //noinspection ResultOfMethodCallIgnored
            new File(dir(c), id).delete();
    }

    static void clear(Context c) {
        for (String id : ids(c)) remove(c, id);
    }

    /** The image's bytes, or null for none. */
    static byte[] read(Context c, String id) {
        if (!validId(id)) return null;
        return readFile(new File(dir(c), id));
    }

    private static byte[] readFile(File f) {
        if (!f.isFile()) return null;
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) in.length()];
            in.readFully(b);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    /** Its media type, from the first bytes. */
    static String type(byte[] b) {
        if (b.length > 3 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 11 && b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E') return "image/webp";
        if (b.length > 2 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        return "image/jpeg";
    }

    /** A data: URL for the visualizer page, "" for none. data: keeps WebGL from calling it cross-origin. */
    static String dataUrl(Context c, String id) {
        byte[] b = read(c, id);
        if (b == null) return "";
        return "data:" + type(b) + ";base64," + Base64.encodeToString(b, Base64.NO_WRAP);
    }
}
