package dev.alex.minlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A card in the top-right corner, over whatever app is in front, at each
 * of the set times ({@link Store#reminderTimes()}, say 60, 30 and 10 minutes)
 * before a calendar event starts, with a soft chime; and, in the evening
 * ({@link Store#digestTime()}, 20:30 by default), one listing tomorrow's
 * events. It reads the same calendars as the line under the date on HOME
 * ({@link AgendaController#read}).
 *
 * While the screen is on it looks every minute, and re-reads Home Assistant
 * every {@link #FETCH_MS}. Each reminder comes once, and only the latest one
 * due: the ones shown are kept, with their start times, so a restart doesn't
 * chime again, and a TV turned on late skips the reminders it missed. All-day
 * events have no moment to remind of. The evening summary comes once a day,
 * at its time or, if the TV is off then, when it is turned on before
 * midnight; a day with nothing tomorrow gets none.
 *
 * Like the alert banner the window takes neither focus nor touches, so the
 * app keeps playing; it goes by itself after {@link #SHOW_MS}, and
 * {@link QuickMenuService} closes it sooner on Back or the Netflix button.
 */
final class EventReminders {

    private static final String TAG = "Reminders";
    private static final long CHECK_MS = 60_000;
    private static final long FETCH_MS = 5 * 60_000;
    private static final long SHOW_MS = 60_000;
    /** The evening summary has more to read. */
    private static final long DIGEST_SHOW_MS = 120_000;
    /** Rows on the evening summary before "+N more". */
    private static final int DIGEST_ROWS = 6;
    private static final int SAMPLE_RATE = 44_100;

    private final Context context;
    private final WindowManager wm;
    private final Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable check = this::check;
    private final Runnable expire = this::hide;
    private final Tone tone;

    private View root;
    /** Events from now to the end of tomorrow as last read, and when. Main thread. */
    private List<Agenda.Event> events = Collections.emptyList();
    private long fetchedAt;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                check();
            } else {
                handler.removeCallbacks(check);
                hide();
            }
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = (p, key) -> {
        if (Store.KEY_REMINDER_TIMES.equals(key) || Store.KEY_CALENDARS.equals(key)
                || Store.KEY_HA_URL.equals(key) || Store.KEY_HA_TOKEN.equals(key)) {
            fetchedAt = 0;
            check();
        } else if (Store.KEY_DIGEST_SHOWN.equals(key) || Store.KEY_DIGEST_TIME.equals(key)) {
            check();
        }
    };

    EventReminders(Context context, WindowManager wm, Store store) {
        this.context = context;
        this.wm = wm;
        this.store = store;
        this.tone = new Tone(context, handler);
    }

    void start() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(screen, f);
        store.prefs().registerOnSharedPreferenceChangeListener(prefsChanged);
        if (((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isInteractive()) check();
    }

    void shutdown() {
        handler.removeCallbacks(check);
        try { context.unregisterReceiver(screen); } catch (IllegalArgumentException ignored) { }
        store.prefs().unregisterOnSharedPreferenceChangeListener(prefsChanged);
        hide();
        worker.shutdownNow();
    }

    boolean isShowing() { return root != null; }

    /** Closed from the remote. It stays seen, so it doesn't come back. */
    void dismiss() { hide(); }

    /** The config page's test: a made-up event, the shortest reminder time away, with the chime. Main thread. */
    void test() {
        Log.i(TAG, "test from the config page");
        List<Integer> leads = store.reminderTimes();
        int minutes = leads.isEmpty() ? 10 : leads.get(0);
        long start = System.currentTimeMillis() + minutes * 60_000L;
        show(Collections.singletonList(new Agenda.Event(context.getString(R.string.reminder_test),
                start, start + 3600_000L, false)), true);
    }

    /** The config page's other test: the evening summary now, from a fresh read. Main thread. */
    void testDigest() {
        Log.i(TAG, "evening summary test from the config page");
        long now = System.currentTimeMillis();
        worker.execute(() -> {
            List<Agenda.Event> read;
            try {
                read = AgendaController.read(store, now, Agenda.dayStart(now, 2), new ArrayList<>(), new ArrayList<>());
            } catch (IOException e) {
                Log.w(TAG, "cannot reach Home Assistant: " + e.getMessage());
                read = Collections.emptyList();
            }
            List<Agenda.Event> r = read;
            handler.post(() -> showDigest(r, now, true));
        });
    }

    // ----------------------------------------------------------- calendar

    private void check() {
        handler.removeCallbacks(check);
        handler.postDelayed(check, CHECK_MS);
        List<Integer> leads = store.reminderTimes();
        if (leads.isEmpty() && !store.digestShown()) return;
        long now = System.currentTimeMillis();
        if (now - fetchedAt < FETCH_MS) {
            remind(now, leads);
            return;
        }
        // Through tomorrow, for the evening summary, and far enough ahead that
        // an event can't slip in between two reads.
        long to = Math.max(Agenda.dayStart(now, 2),
                now + (leads.isEmpty() ? 0 : leads.get(leads.size() - 1)) * 60_000L + 2 * FETCH_MS);
        worker.execute(() -> {
            List<String> ids = new ArrayList<>(), failed = new ArrayList<>();
            try {
                List<Agenda.Event> read = AgendaController.read(store, now, to, ids, failed);
                if (!failed.isEmpty()) Log.w(TAG, "could not read " + TextUtils.join(", ", failed));
                handler.post(() -> {
                    events = read;
                    fetchedAt = now;
                    remind(System.currentTimeMillis(), leads);
                });
            } catch (IOException e) {
                // Try again next minute; reminders already read still come.
                Log.w(TAG, "cannot reach Home Assistant: " + e.getMessage());
                handler.post(() -> remind(System.currentTimeMillis(), leads));
            }
        });
    }

    /** Reminders first; the evening summary waits for a free corner. */
    private void remind(long now, List<Integer> leads) {
        Map<String, Long> seen = seen(now);
        List<Agenda.Event> due = Agenda.due(events, now, leads, seen.keySet());
        if (due.isEmpty()) {
            if (root == null) digest(now);
            return;
        }
        for (Agenda.Event e : due) {
            for (String k : Agenda.keysThrough(e, Agenda.slot(e, now, leads), leads)) seen.put(k, e.start);
        }
        store.setRemindersSeen(new JSONObject(seen).toString());
        Log.i(TAG, "reminding of " + due);
        show(due, store.reminderChime());
    }

    private void digest(long now) {
        if (!store.digestShown() || fetchedAt < Agenda.dayStart(now, 0)
                || !Agenda.digestDue(now, Agenda.parseClock(store.digestTime()), store.digestDay())) {
            return;
        }
        store.setDigestDay(Agenda.dayStart(now, 0));
        long tomorrow = Agenda.dayStart(now, 1);
        if (Agenda.forDay(events, tomorrow, Agenda.dayStart(now, 2), now).isEmpty()) {
            Log.i(TAG, "evening summary: nothing tomorrow");
            return;
        }
        showDigest(events, now, store.reminderChime());
    }

    /** Events reminded of already, less those that have started: they can't come round again. */
    private Map<String, Long> seen(long now) {
        Map<String, Long> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(store.remindersSeen());
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                if (o.getLong(k) > now) out.put(k, o.getLong(k));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- window

    private void show(List<Agenda.Event> due, boolean withChime) {
        long now = System.currentTimeMillis();
        int minutes = Agenda.minutesUntil(due.get(0).start, now);
        LinearLayout box = card(minutes == 0 ? context.getString(R.string.reminder_now)
                : context.getString(R.string.reminder_in, minutes));

        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (Agenda.Event e : due) {
            TextView title = text(e.summary, 26, 0xFFFFFFFF);
            title.setPadding(0, dp(6), 0, 0);
            box.addView(title);
            box.addView(text(hm.format(new Date(e.start)), 16, 0xFFD6D6D6));
        }
        put(box, SHOW_MS, withChime);
    }

    /** Tomorrow's events from {@code all}, one row each, under the date. */
    private void showDigest(List<Agenda.Event> all, long now, boolean withChime) {
        long tomorrow = Agenda.dayStart(now, 1), after = Agenda.dayStart(now, 2);
        List<Agenda.Event> day = Agenda.forDay(all, tomorrow, after, now);
        Log.i(TAG, "evening summary: " + day);
        LinearLayout box = card(context.getString(R.string.digest_heading,
                new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date(tomorrow)))
                .toUpperCase(Locale.getDefault()));
        if (day.isEmpty()) {
            TextView none = text(context.getString(R.string.digest_nothing), 20, 0xFFFFFFFF);
            none.setPadding(0, dp(8), 0, 0);
            box.addView(none);
        }
        int shown = day.size() > DIGEST_ROWS ? DIGEST_ROWS - 1 : day.size();
        for (int i = 0; i < shown; i++) {
            Agenda.Event e = day.get(i);
            LinearLayout row = new LinearLayout(context);
            row.setPadding(0, dp(i == 0 ? 8 : 4), 0, 0);
            TextView time = text(Agenda.time(e, tomorrow, after), 18, 0xFFBDBDBD);
            time.setMinWidth(dp(96));
            row.addView(time);
            row.addView(text(e.summary, 20, 0xFFFFFFFF));
            box.addView(row);
        }
        if (shown < day.size()) {
            TextView more = text(context.getString(R.string.digest_more, day.size() - shown), 18, 0xFFBDBDBD);
            more.setPadding(0, dp(4), 0, 0);
            box.addView(more);
        }
        put(box, DIGEST_SHOW_MS, withChime);
    }

    /** The dark card, with its small heading. */
    private LinearLayout card(String heading) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01C1C1C);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        box.setPadding(dp(24), dp(18), dp(28), dp(16));
        box.setMinimumWidth(dp(320));
        TextView h = text(heading, 14, 0xFFBDBDBD);
        h.setLetterSpacing(0.08f);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(h);
        return box;
    }

    /** Puts the card up in the top-right corner, in place of any other, for {@code showMs}. */
    private void put(LinearLayout box, long showMs, boolean withChime) {
        hide();
        TextView hint = text(context.getString(R.string.alert_hint), 13, 0xFF8A8A8A);
        hint.setPadding(0, dp(10), 0, 0);
        box.addView(hint);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(48);
        lp.y = dp(40);
        root = box;
        wm.addView(root, lp);
        handler.postDelayed(expire, showMs);
        if (withChime) tone.play(Agenda.chime(SAMPLE_RATE), SAMPLE_RATE);
    }

    private void hide() {
        handler.removeCallbacks(expire);
        if (root == null) return;
        wm.removeView(root);
        root = null;
    }

    private TextView text(String s, int sizeSp, int color) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(560));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
