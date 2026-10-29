package dev.alex.minlauncher;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Today's events from Home Assistant's calendars, as one line under the
 * date on HOME and the screensaver, and tomorrow's once today's last one is
 * over. While HOME is showing it reads the chosen calendars (every one, if
 * none are chosen) every five minutes; the minute tick re-draws from what it
 * has, so finished events drop off on time. Reads stay on one worker
 * thread; the views are touched on the main one.
 */
final class AgendaController {

    private static final String TAG = "Agenda";
    private static final long POLL_MS = 5 * 60_000;

    private final Store store;
    private final TextView line;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refresh;

    /** Today's and tomorrow's events as last read, and the midnight they were read from. Main thread. */
    private List<Agenda.Event> events = Collections.emptyList();
    private long fetchedFor = -1;
    private boolean running;

    AgendaController(Store store, TextView line) {
        this.store = store;
        this.line = line;
    }

    void start() {
        running = true;
        render();
        refresh();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
    }

    void shutdown() {
        stop();
        worker.shutdownNow();
    }

    /** Called every minute: drops finished events, and re-reads once the day turns over. */
    void onTimeTick() {
        if (fetchedFor != -1 && fetchedFor != Agenda.dayStart(System.currentTimeMillis(), 0)) refresh();
        else render();
    }

    /** Re-reads Home Assistant; also the response to a settings change. */
    void refresh() {
        handler.removeCallbacks(poll);
        if (running) handler.postDelayed(poll, POLL_MS);
        if (!store.calendarShown()) {
            events = Collections.emptyList();
            fetchedFor = -1;
            render();
            return;
        }
        worker.execute(this::update);
    }

    // ------------------------------------------------------------- worker

    private void update() {
        long from = Agenda.dayStart(System.currentTimeMillis(), 0), to = Agenda.dayStart(from, 2);
        List<String> ids = new ArrayList<>(), failed = new ArrayList<>();
        List<Agenda.Event> out;
        try {
            out = read(store, from, to, ids, failed);
        } catch (IOException e) {
            // Keep what is showing; it is at most five minutes old, or from before HA went away.
            Log.w(TAG, "cannot reach Home Assistant: " + e.getMessage());
            status(0, 0, "Home Assistant: " + e.getMessage());
            return;
        }
        Log.i(TAG, out.size() + " events from " + ids);
        status(ids.size(), out.size(), failed.isEmpty() ? null : "Could not read " + TextUtils.join(", ", failed));
        handler.post(() -> {
            events = out;
            fetchedFor = from;
            render();
        });
    }

    /**
     * The events overlapping [{@code from}, {@code to}) in the chosen
     * calendars, or every one Home Assistant has but Workday's. Fills in
     * {@code ids} with the calendars read and {@code failed} with those that
     * couldn't be; throws only when there is no list of calendars to read.
     * Blocking; shared with {@link EventReminders}.
     */
    static List<Agenda.Event> read(Store store, long from, long to, List<String> ids, List<String> failed)
            throws IOException {
        String url = store.haUrl(), token = store.haToken();
        ids.addAll(calendarIds(store, url, token));
        List<Agenda.Event> out = new ArrayList<>();
        for (String id : ids) {
            try {
                JSONArray a = HomeAssistant.calendarEvents(url, token, id, from, to);
                for (int i = 0; i < a.length(); i++) {
                    Agenda.Event e = event(a.optJSONObject(i));
                    if (e != null) out.add(e);
                }
            } catch (IOException e) {
                Log.w(TAG, id + ": " + e.getMessage());
                failed.add(id);
            }
        }
        return out;
    }

    private static List<String> calendarIds(Store store, String url, String token) throws IOException {
        List<String> chosen = store.calendars();
        if (!chosen.isEmpty()) return chosen;
        List<String> all = new ArrayList<>();
        JSONArray a = HomeAssistant.calendars(url, token);
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            String id = c == null ? "" : c.optString("entity_id");
            // The Workday integration's calendar marks every working day: noise
            // here. Ticking it on the config page still shows it.
            if (!id.isEmpty() && !id.contains("workday")) all.add(id);
        }
        return all;
    }

    /** One of Home Assistant's events; null if its times don't parse. */
    private static Agenda.Event event(JSONObject o) {
        if (o == null) return null;
        JSONObject s = o.optJSONObject("start"), e = o.optJSONObject("end");
        if (s == null) return null;
        String summary = o.optString("summary").trim();
        if (summary.isEmpty()) summary = "(No title)";
        if (s.has("date")) {
            long start = Agenda.parseDate(s.optString("date"));
            long end = e == null ? -1 : Agenda.parseDate(e.optString("date", null));
            if (start < 0) return null;
            return new Agenda.Event(summary, start, end < 0 ? Agenda.dayStart(start, 1) : end, true);
        }
        long start = HomeAssistant.parseTime(s.optString("dateTime", null));
        long end = e == null ? -1 : HomeAssistant.parseTime(e.optString("dateTime", null));
        if (start < 0) return null;
        return new Agenda.Event(summary, start, end < 0 ? start : end, false);
    }

    /** What the config page shows in the Calendar section. */
    private void status(int calendars, int events, String error) {
        try {
            JSONObject o = new JSONObject().put("at", System.currentTimeMillis())
                    .put("calendars", calendars).put("events", events);
            if (error != null) o.put("error", error);
            store.setCalendarStatus(o.toString());
        } catch (JSONException ignored) { }
    }

    // ---------------------------------------------------------------- main

    private void render() {
        long now = System.currentTimeMillis();
        // Read yesterday: say nothing until today's arrive, rather than show the wrong day.
        String text = !store.calendarShown() || fetchedFor != Agenda.dayStart(now, 0) ? null
                : Agenda.homeLine(events, now);
        line.setText(text);
        line.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }
}
