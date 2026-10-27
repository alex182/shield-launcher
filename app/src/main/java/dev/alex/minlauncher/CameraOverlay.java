package dev.alex.minlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen grid of Home Assistant cameras over whatever app is in front.
 * Select a tile for that camera alone; Back steps out, then closes.
 *
 * Frames are snapshots from /api/camera_proxy, re-fetched on a timer rather
 * than streamed: simple, works for every camera integration, and a frame or
 * two a second is plenty for a glance. Closes itself when the screen turns
 * off so it never polls a sleeping TV.
 */
final class CameraOverlay {

    private static final long GRID_REFRESH_MS = 2000;
    private static final long SINGLE_REFRESH_MS = 1000;

    private static final class Camera {
        final String id, name;
        Camera(String id, String name) { this.id = id; this.name = name; }
    }

    private final Context context;
    private final WindowManager wm;
    private final Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService fetches = Executors.newFixedThreadPool(4);

    private FrameLayout root;
    private List<Camera> cameras = new ArrayList<>();
    /** Visible image per camera id: every tile in the grid, or the one in single view. */
    private final Map<String, ImageView> visible = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private Camera single;          // non-null while one camera fills the screen
    private int generation;         // bumps on every show/hide/layout so stale fetches drop

    private final Runnable tick = this::refresh;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { hide(); }
    };

    CameraOverlay(Context context, WindowManager wm, Store store) {
        this.context = context;
        this.wm = wm;
        this.store = store;
    }

    boolean isShowing() { return root != null; }

    void show() {
        if (root != null) return;
        root = new FrameLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) back();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setBackgroundColor(0xFF000000);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        wm.addView(root, lp);
        context.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));

        message(context.getString(R.string.cameras_loading));
        int gen = ++generation;
        String url = store.haUrl(), token = store.haToken();
        List<String> chosen = store.cameras();
        fetches.execute(() -> {
            List<Camera> found;
            String error = null;
            try {
                found = listCameras(HomeAssistant.states(url, token), chosen);
            } catch (IOException | JSONException e) {
                found = new ArrayList<>();
                error = e.getMessage();
            }
            List<Camera> result = found;
            String err = error;
            handler.post(() -> {
                if (gen != generation) return;
                cameras = result;
                if (err != null) message(context.getString(R.string.cameras_failed, err));
                else if (cameras.isEmpty()) message(context.getString(R.string.cameras_none));
                else if (cameras.size() == 1) showSingle(cameras.get(0));
                else showGrid(null);
            });
        });
    }

    void hide() {
        if (root == null) return;
        generation++;
        handler.removeCallbacks(tick);
        try { context.unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { }
        wm.removeView(root);
        root = null;
        single = null;
        visible.clear();
        inFlight.clear();
    }

    void shutdown() {
        hide();
        fetches.shutdownNow();
    }

    private void back() {
        if (single != null && cameras.size() > 1) showGrid(single.id);
        else hide();
    }

    /**
     * Camera entities from /api/states. With a configured list, those in that
     * order (skipping any that no longer exist); otherwise all, by name.
     */
    private static List<Camera> listCameras(String statesJson, List<String> chosen)
            throws JSONException {
        Map<String, Camera> all = new HashMap<>();
        JSONArray states = new JSONArray(statesJson);
        for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.getJSONObject(i);
            String id = s.getString("entity_id");
            if (!id.startsWith("camera.")) continue;
            JSONObject attrs = s.optJSONObject("attributes");
            String name = attrs == null ? id : attrs.optString("friendly_name", id);
            all.put(id, new Camera(id, name));
        }
        List<Camera> out = new ArrayList<>();
        if (chosen.isEmpty()) {
            out.addAll(all.values());
            Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        } else {
            for (String id : chosen) if (all.containsKey(id)) out.add(all.get(id));
        }
        return out;
    }

    // ----------------------------------------------------------------- layout

    private void message(String text) {
        root.removeAllViews();
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(20);
        t.setTextColor(context.getResources().getColor(R.color.dim));
        t.setGravity(Gravity.CENTER);
        root.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 1–2 cameras side by side, up to 4 in 2×2, more in rows of 3. */
    private void showGrid(String focusId) {
        startLayout();
        single = null;
        int n = cameras.size();
        int cols = n <= 2 ? n : n <= 4 ? 2 : 3;
        int rowsCount = (n + cols - 1) / cols;

        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(24), dp(24), dp(24), dp(24));
        View focus = null;
        for (int r = 0; r < rowsCount; r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                View tile = i < n ? makeTile(cameras.get(i)) : new View(context);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                row.addView(tile, lp);
                if (i < n && (focus == null || cameras.get(i).id.equals(focusId))) focus = tile;
            }
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        root.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View f = focus;
        // As elsewhere, focus only sticks once the views are laid out.
        root.post(() -> { if (f != null) f.requestFocus(); });
        refresh();
    }

    private View makeTile(Camera cam) {
        FrameLayout tile = new FrameLayout(context);
        tile.setBackgroundResource(R.drawable.banner_bg);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        tile.setFocusable(true);
        tile.setOnClickListener(v -> showSingle(cam));

        ImageView img = new ImageView(context);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(0xFF101010);
        tile.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.addView(label(cam.name), labelParams());
        visible.put(cam.id, img);
        return tile;
    }

    private void showSingle(Camera cam) {
        startLayout();
        single = cam;
        ImageView img = new ImageView(context);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(label(cam.name), labelParams());
        visible.put(cam.id, img);
        refresh();
    }

    private void startLayout() {
        generation++;
        handler.removeCallbacks(tick);
        root.removeAllViews();
        visible.clear();
        inFlight.clear();
    }

    private TextView label(String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(context.getResources().getColor(R.color.fg));
        t.setShadowLayer(6, 0, 0, 0xFF000000);
        t.setPadding(dp(14), dp(8), dp(14), dp(10));
        return t;
    }

    private static FrameLayout.LayoutParams labelParams() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
    }

    // ----------------------------------------------------------------- frames

    /** Fetches a fresh frame for every visible camera not already loading. */
    private void refresh() {
        if (root == null) return;
        int gen = generation;
        String url = store.haUrl(), token = store.haToken();
        for (Map.Entry<String, ImageView> e : visible.entrySet()) {
            String id = e.getKey();
            ImageView img = e.getValue();
            if (!inFlight.add(id)) continue;
            int targetWidth = Math.max(img.getWidth(), dp(320));
            fetches.execute(() -> {
                Bitmap bmp = null;
                try {
                    bmp = decode(HomeAssistant.cameraSnapshot(url, token, id), targetWidth);
                } catch (IOException | OutOfMemoryError ignored) {
                    // Keep the last frame; the next tick tries again.
                }
                Bitmap frame = bmp;
                handler.post(() -> {
                    if (gen != generation) return;
                    inFlight.remove(id);
                    if (frame != null) img.setImageBitmap(frame);
                });
            });
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, single != null ? SINGLE_REFRESH_MS : GRID_REFRESH_MS);
    }

    /** Decodes at no more than ~2× the target width, so 4K cameras stay cheap. */
    private static Bitmap decode(byte[] data, int targetWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
