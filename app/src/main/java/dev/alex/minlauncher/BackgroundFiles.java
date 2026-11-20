package dev.alex.minlauncher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the background images come from. With an NFS share set on the config
 * page (the default), manifest.json and the images are read from it over
 * {@link Nfs}, and each image is kept in a small cache in the app's files,
 * so the last ones still show while the NAS is unreachable. With the share
 * left blank, an APK built with {@code -PbundleBackgrounds} uses its own
 * assets/backgrounds/ instead.
 *
 * Blocking: call from a worker thread. HOME and the screensaver each have
 * one, sharing the cache under {@link #LOCK}.
 */
final class BackgroundFiles {

    static final String ASSETS = "backgrounds/";
    private static final String TAG = "Backgrounds";
    private static final String MANIFEST = "manifest.json";
    /** Images kept on the device; the least recently shown go first. */
    private static final int CACHE_IMAGES = 16;
    private static final Object LOCK = new Object();

    interface Source { InputStream open() throws IOException; }

    private final Context context;
    private final Store store;
    private final File cache;
    /** The last problem reaching the share, for the config page; null if none. */
    volatile String error;

    BackgroundFiles(Context context, Store store) {
        this.context = context.getApplicationContext();
        this.store = store;
        cache = new File(this.context.getFilesDir(), "backgrounds");
    }

    /** "the NFS share" or "the APK", for messages. */
    String where() {
        String spec = store.bgNfs();
        return spec.isEmpty() ? "the APK" : spec;
    }

    /**
     * What there is to pick from: the share's manifest (saved for next time),
     * else the saved copy, else the APK's. {@code network} false skips the
     * share, for a quick first image. Null if there is none at all.
     */
    Backgrounds.Manifest manifest(boolean network) {
        String spec = store.bgNfs();
        if (spec.isEmpty()) {
            error = null;
            try (InputStream in = context.getAssets().open(ASSETS + MANIFEST)) {
                return parse(HomeAssistant.readAll(in));
            } catch (IOException | JSONException e) {
                return null;
            }
        }
        File saved = new File(cache, MANIFEST);
        if (network) {
            try (Nfs nfs = open(spec)) {
                byte[] b = nfs.readAll(MANIFEST);
                Backgrounds.Manifest m = parse(new String(b, StandardCharsets.UTF_8));
                synchronized (LOCK) { writeAtomically(saved, out -> out.write(b)); }
                error = null;
                return m;
            } catch (IOException | JSONException | IllegalArgumentException e) {
                error = "NFS " + spec + ": " + e.getMessage();
                Log.w(TAG, error);
            }
        }
        synchronized (LOCK) {
            if (!saved.exists()) return null;
            try (InputStream in = new FileInputStream(saved)) {
                return parse(HomeAssistant.readAll(in));
            } catch (IOException | JSONException e) {
                return null;
            }
        }
    }

    /**
     * Opens {@code path}, a manifest entry. From the share it is fetched into
     * the cache first, unless the cached copy is current; if the share is
     * unreachable (or {@code network} is false) the cached copy is used.
     */
    Source image(String path, boolean network) throws IOException {
        if (path.startsWith("/") || path.contains("..")) throw new IOException("bad path " + path);
        String spec = store.bgNfs();
        if (spec.isEmpty()) return () -> context.getAssets().open(ASSETS + path);

        File f = new File(cache, path);
        if (network) {
            try (Nfs nfs = open(spec)) {
                Nfs.Node node = nfs.stat(path);
                synchronized (LOCK) {
                    // The cached file's time is when it was fetched or last shown,
                    // so a file changed on the share since then is newer.
                    if (f.length() != node.size || f.lastModified() < node.mtimeMs) {
                        Log.i(TAG, "fetching " + path + " (" + node.size + " bytes)");
                        writeAtomically(f, out -> {
                            if (nfs.read(node, out) != node.size) throw new IOException(path + " changed while reading");
                        });
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                error = "NFS " + spec + ": " + e.getMessage();
                Log.w(TAG, error);
            }
        }
        synchronized (LOCK) {
            if (!f.isFile()) throw new FileNotFoundException(path + " is not on the device and the share is unreachable");
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            prune(f);
        }
        return () -> new FileInputStream(f);
    }

    private Nfs open(String spec) throws IOException {
        return Nfs.open(spec, new Nfs.Auth(store.bgNfsUid(), store.bgNfsGid(), new int[0]));
    }

    static Backgrounds.Manifest parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        List<String> focals = new ArrayList<>();
        JSONArray fa = o.optJSONArray("focal_elements");
        for (int i = 0; fa != null && i < fa.length(); i++) focals.add(fa.getString(i));
        Set<String> files = new HashSet<>();
        JSONArray fs = o.optJSONArray("files");
        for (int i = 0; fs != null && i < fs.length(); i++) files.add(fs.getString(i));
        return new Backgrounds.Manifest(focals, files);
    }

    private interface Writer { void write(OutputStream out) throws IOException; }

    /** Writes to a temporary file and renames it, so a reader never sees half a file. */
    private static void writeAtomically(File f, Writer w) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            w.write(out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot write " + f);
        }
    }

    /** Keeps the {@link #CACHE_IMAGES} most recently shown images, and always {@code keep}. */
    private void prune(File keep) {
        List<File> images = new ArrayList<>();
        collect(cache, images);
        if (images.size() <= CACHE_IMAGES) return;
        Collections.sort(images, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : images.subList(CACHE_IMAGES, images.size())) {
            //noinspection ResultOfMethodCallIgnored
            if (!f.equals(keep)) f.delete();
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".webp")) out.add(f);
        }
    }
}
