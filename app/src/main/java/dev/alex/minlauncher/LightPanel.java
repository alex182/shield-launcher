package dev.alex.minlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Brightness and colour for the lights of one quick action, opened by a long
 * press on its row. It sits over the quick actions panel; Back returns to it.
 *
 * Every change goes to Home Assistant as light.turn_on on all the action's
 * lights at once. Brightness is debounced, so holding left or right sends one
 * call when it settles rather than one per step.
 */
final class LightPanel {

    private static final int STEP = 5;
    private static final long BRIGHTNESS_DEBOUNCE_MS = 250;

    private final Context context;
    private final WindowManager wm;
    private final Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService calls = Executors.newSingleThreadExecutor();

    private LinearLayout root;
    private TextView status, brightnessLabel;
    private LevelBar bar;
    private List<String> ids;
    private String label;
    private int generation;   // bumps on every show/hide so stale replies drop

    private final Runnable sendBrightness = () -> {
        int pct = bar.value;
        if (pct == 0) send("light.turn_off", new JSONObject());
        else send("light.turn_on", put(new JSONObject(), "brightness_pct", pct));
    };

    LightPanel(Context context, WindowManager wm, Store store) {
        this.context = context;
        this.wm = wm;
        this.store = store;
    }

    boolean isShowing() { return root != null; }

    @SuppressWarnings("deprecation")
    void show(String label, List<String> entityIds) {
        if (root != null) return;
        this.label = label;
        ids = entityIds;
        root = new LinearLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) hide();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101010);
        root.setPadding(dp(40), dp(48), dp(40), dp(40));

        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(28);
        title.setSingleLine(true);
        title.setTextColor(context.getResources().getColor(R.color.fg));
        root.addView(title);

        status = new TextView(context);
        status.setTextSize(14);
        status.setTextColor(context.getResources().getColor(R.color.dim));
        status.setPadding(0, dp(2), 0, dp(24));
        status.setText(R.string.light_loading);
        root.addView(status);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(420), ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.END | Gravity.TOP;
        wm.addView(root, lp);

        int gen = ++generation;
        String url = store.haUrl(), token = store.haToken();
        List<String> lights = ids;
        calls.execute(() -> {
            Set<String> modes = new HashSet<>();
            boolean on = false;
            int brightness = 0;
            String error = null;
            try {
                // The first light that is on sets where the bar starts.
                for (String id : lights) {
                    JSONObject e = HomeAssistant.entity(url, token, id);
                    if (e == null) continue;
                    JSONObject attrs = e.optJSONObject("attributes");
                    JSONArray m = attrs == null ? null : attrs.optJSONArray("supported_color_modes");
                    for (int i = 0; m != null && i < m.length(); i++) modes.add(m.optString(i));
                    if (!on && "on".equals(e.optString("state"))) {
                        on = true;
                        brightness = attrs == null ? 255 : attrs.optInt("brightness", 255);
                    }
                }
            } catch (IOException ex) {
                error = ex.getMessage();
            }
            Lights.Support support = Lights.support(modes);
            int pct = Lights.percent(on, brightness);
            String err = error;
            handler.post(() -> {
                if (gen != generation) return;
                if (err != null) status.setText(context.getString(R.string.light_failed, err));
                else status.setText(lights.size() == 1 ? context.getString(R.string.light_one)
                        : context.getString(R.string.light_many, lights.size()));
                build(support, pct);
            });
        });
    }

    void hide() {
        if (root == null) return;
        generation++;
        handler.removeCallbacks(sendBrightness);
        wm.removeView(root);
        root = null;
        bar = null;
    }

    void shutdown() {
        hide();
        calls.shutdownNow();
    }

    // ----------------------------------------------------------------- build

    @SuppressWarnings("deprecation")
    private void build(Lights.Support support, int pct) {
        View first = null;
        if (support.brightness) {
            brightnessLabel = heading("");
            bar = new LevelBar(context, pct);
            bar.onChange = v -> {
                showBrightness(v);
                handler.removeCallbacks(sendBrightness);
                handler.postDelayed(sendBrightness, BRIGHTNESS_DEBOUNCE_MS);
            };
            showBrightness(pct);
            root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
            first = bar;
        }
        if (support.temp) {
            heading(context.getString(R.string.light_white));
            View v = swatches(Lights.WHITES);
            if (first == null) first = v;
        }
        if (support.color) {
            heading(context.getString(R.string.light_color));
            View v = swatches(Lights.COLORS);
            if (first == null) first = v;
        }
        if (first == null) status.setText(R.string.light_no_controls);
        else first.requestFocus();
    }

    @SuppressWarnings("deprecation")
    private TextView heading(String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(context.getResources().getColor(R.color.fg));
        t.setPadding(0, dp(18), 0, dp(10));
        root.addView(t);
        return t;
    }

    private void showBrightness(int pct) {
        brightnessLabel.setText(pct == 0 ? context.getString(R.string.light_brightness_off)
                : context.getString(R.string.light_brightness, pct));
    }

    /** A row of round swatches; select one to send it. Returns the first, for focus. */
    private View swatches(Lights.Swatch[] list) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View first = null;
        for (Lights.Swatch s : list) {
            View v = new View(context);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(s.shown);
            v.setBackground(d);
            v.setFocusable(true);
            // Focus is the only affordance on a remote: a thick white ring.
            v.setOnFocusChangeListener((view, has) -> {
                d.setStroke(has ? dp(4) : 0, 0xFFFFFFFF);
                view.setScaleX(has ? 1.2f : 1f);
                view.setScaleY(has ? 1.2f : 1f);
            });
            v.setOnClickListener(view -> {
                JSONObject data = new JSONObject();
                if (s.kelvin > 0) put(data, "color_temp_kelvin", s.kelvin);
                else put(data, "rgb_color", new JSONArray()
                        .put((s.rgb >> 16) & 0xFF).put((s.rgb >> 8) & 0xFF).put(s.rgb & 0xFF));
                // A colour on a dark light would be invisible: bring it up too.
                if (bar != null && bar.value == 0) {
                    bar.set(100);
                    showBrightness(100);
                    put(data, "brightness_pct", 100);
                }
                send("light.turn_on", data);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.rightMargin = dp(10);
            row.addView(v, lp);
            if (first == null) first = v;
        }
        row.setClipChildren(false);
        row.setPadding(dp(4), dp(4), 0, dp(4));
        root.setClipChildren(false);
        root.addView(row);
        return first;
    }

    // ------------------------------------------------------------------ calls

    private void send(String service, JSONObject data) {
        JSONArray entities = new JSONArray();
        for (String id : ids) entities.put(id);
        put(data, "entity_id", entities);
        Store.Action call = new Store.Action(label, "", service, data.toString());
        String url = store.haUrl(), token = store.haToken();
        int gen = generation;
        calls.execute(() -> {
            String error = null;
            try {
                HomeAssistant.call(url, token, call);
            } catch (IOException e) {
                error = e.getMessage();
            }
            if (error == null) return;
            String err = error;
            handler.post(() -> {
                if (gen == generation) status.setText(context.getString(R.string.light_failed, err));
            });
        });
    }

    private static JSONObject put(JSONObject o, String key, Object value) {
        try {
            return o.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only for non-finite numbers
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------- the bar

    interface OnChange { void changed(int value); }

    /** A 0–100 bar for a D-pad: left and right step it, holding repeats. */
    private static final class LevelBar extends View {
        int value;
        OnChange onChange;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        LevelBar(Context c, int value) {
            super(c);
            this.value = value;
            setFocusable(true);
            track.setColor(0xFF2A2A2A);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(0xFFFFFFFF);
            ring.setStrokeWidth(3 * c.getResources().getDisplayMetrics().density);
        }

        void set(int v) { value = v; invalidate(); }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            int delta = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -STEP
                    : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? STEP : 0;
            if (delta == 0) return super.onKeyDown(keyCode, event);
            int next = Lights.step(value, delta);
            if (next != value) {
                set(next);
                if (onChange != null) onChange.changed(next);
            }
            return true;   // keep focus on the bar at either end
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = ring.getStrokeWidth(), h = getHeight(), radius = h / 2;
            r.set(inset, inset, getWidth() - inset, h - inset);
            canvas.drawRoundRect(r, radius, radius, track);
            fill.setColor(isFocused() ? 0xFFF2F2F2 : 0xFF8A8A8A);
            float right = r.left + (r.right - r.left) * value / 100f;
            if (value > 0) {
                canvas.save();
                canvas.clipRect(r.left, r.top, Math.max(right, r.left + radius), r.bottom);
                canvas.drawRoundRect(r, radius, radius, fill);
                canvas.restore();
            }
            if (isFocused()) canvas.drawRoundRect(r, radius, radius, ring);
        }
    }
}
