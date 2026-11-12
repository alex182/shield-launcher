package dev.alex.minlauncher;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The config page at http://&lt;shield&gt;:8080, hand-rolled on a ServerSocket
 * to keep the zero-dependency build. Open to the LAN by design, except that
 * the Home Assistant token is write-only: the page learns whether one is set,
 * never what it is.
 *
 * Requests are handled one at a time on a single thread; this serves one
 * person at a desk, not traffic.
 */
final class WebConfigServer implements Runnable {

    static final int PORT = 8080;
    private static final String TAG = "WebConfigServer";
    private static final int MAX_BODY = 256 * 1024;

    private final Context context;
    private final Store store;
    /** Put the radar, the visualizer or a test alert, reminder or evening summary up on the TV; the service hops to the main thread. */
    private final Runnable showRadar, testAlert, testReminder, testDigest;
    private final ShowVisualizer showVisualizer;

    /** {@link VisualizerOverlay#show(String, String)}, on the main thread. */
    interface ShowVisualizer { void show(String playlist, String preset); }
    private ServerSocket server;
    private Thread thread;

    WebConfigServer(Context context, Store store, Runnable showRadar, Runnable testAlert,
            Runnable testReminder, Runnable testDigest, ShowVisualizer showVisualizer) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.showRadar = showRadar;
        this.testAlert = testAlert;
        this.testReminder = testReminder;
        this.testDigest = testDigest;
        this.showVisualizer = showVisualizer;
    }

    void start() {
        if (thread != null) return;
        thread = new Thread(this, TAG);
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        thread = null;
    }

    @Override
    public void run() {
        try (ServerSocket s = new ServerSocket(PORT)) {
            server = s;
            while (!s.isClosed()) {
                try (Socket client = s.accept()) {
                    client.setSoTimeout(10_000);
                    handle(client);
                } catch (IOException e) {
                    if (!s.isClosed()) Log.w(TAG, "request failed", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot listen on " + PORT, e);
        }
    }

    // --------------------------------------------------------------- HTTP

    private void handle(Socket client) throws IOException {
        InputStream in = new BufferedInputStream(client.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0], path = parts[1];

        int length = 0;
        for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(h.substring(colon + 1).trim());
            }
        }
        if (length > MAX_BODY) {
            send(client, 413, "text/plain", "Too large");
            return;
        }
        byte[] body = new byte[length];
        for (int got = 0; got < length; ) {
            int n = in.read(body, got, length - got);
            if (n < 0) break;
            got += n;
        }
        String text = new String(body, StandardCharsets.UTF_8);

        try {
            if (method.equals("GET") && path.equals("/")) {
                send(client, 200, "text/html; charset=utf-8", page());
            } else if (method.equals("GET") && path.equals("/mdi.ttf")) {
                send(client, 200, "font/ttf", asset(Icons.FONT), true);
            } else if (method.equals("GET") && path.equals("/mdi-names.tsv")) {
                send(client, 200, "text/plain; charset=utf-8", asset(Icons.NAMES), true);
            } else if (method.equals("GET") && path.equals("/visualizer-presets.js")) {
                // The page lists preset names from the same bundle the TV draws.
                send(client, 200, "text/javascript; charset=utf-8",
                        asset("visualizer/butterchurnPresets.min.js"), true);
            } else if (method.equals("GET") && path.equals("/api/visualizer")) {
                send(client, 200, "application/json", visualizerNow().toString());
            } else if (method.equals("POST") && path.equals("/api/visualizer/show")) {
                JSONObject o = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                showVisualizer.show(o.optString("playlist"), o.optString("preset"));
                sendResult(client, () -> "Showing on the TV");
            } else if (method.equals("POST") && path.equals("/api/visualizer/like")) {
                // Saved at once, not with the page's Save: the TV likes too.
                JSONObject o = new JSONObject(text);
                store.setVisualizerLiked(o.getString("preset"), o.getBoolean("liked"));
                send(client, 200, "application/json", visualizerNow().toString());
            } else if (method.equals("GET") && path.equals("/api/state")) {
                send(client, 200, "application/json", state().toString());
            } else if (method.equals("POST") && path.equals("/api/state")) {
                save(new JSONObject(text));
                send(client, 200, "application/json", state().toString());
            } else if (method.equals("POST") && path.equals("/api/test-ha")) {
                JSONObject o = new JSONObject(text);
                sendResult(client, () -> HomeAssistant.ping(
                        o.optString("haUrl"), tokenOr(o.optString("haToken"))));
            } else if (method.equals("POST") && (path.equals("/api/ha/states")
                    || path.equals("/api/ha/services"))) {
                // Passed through verbatim; the page does the filtering.
                JSONObject o = new JSONObject(text);
                String url = o.optString("haUrl"), token = tokenOr(o.optString("haToken"));
                try {
                    send(client, 200, "application/json", path.endsWith("states")
                            ? HomeAssistant.states(url, token) : HomeAssistant.services(url, token));
                } catch (IOException e) {
                    send(client, 502, "application/json", error(e.getMessage()));
                }
            } else if (method.equals("GET") && path.equals("/api/backup")) {
                send(client, 200, "application/json", backup().toString(1));
            } else if (method.equals("POST") && path.equals("/api/restore")) {
                JSONObject o = new JSONObject(text);
                restore(o.getJSONObject("backup"), strings(o.getJSONArray("groups")));
                sendResult(client, () -> "Restored");
            } else if (method.equals("POST") && path.equals("/api/restore/undo")) {
                String undo = store.prefs().getString(Backup.KEY_UNDO, "");
                if (undo.isEmpty()) {
                    sendResult(client, () -> { throw new IOException("Nothing to undo"); });
                } else {
                    Backup.apply(store.prefs(), Backup.read(new JSONObject(undo), Backup.groupIds()));
                    store.prefs().edit().remove(Backup.KEY_UNDO).commit();
                    sendResult(client, () -> "Back to how it was before the restore");
                }
            } else if (method.equals("GET") && path.equals("/api/ambilight")) {
                send(client, 200, "application/json", AmbilightService.status().toString());
            } else if (method.equals("POST") && path.equals("/api/ambilight/preview")) {
                // Where each LED of the page's (unsaved) layout sits and what it shows.
                JSONObject c = AmbilightConfig.normalize(new JSONObject(text).optJSONObject("config"));
                send(client, 200, "application/json", AmbilightConfig.preview(c).toString());
            } else if (method.equals("POST") && path.equals("/api/ambilight/start")) {
                // With no strips saved it still captures, so the page shows that the capture works.
                boolean none = store.ambilight().getJSONArray("strips").length() == 0;
                AmbilightService.start(context);
                sendResult(client, () -> none ? "Starting on the TV, but no strips are saved yet, so nothing lights"
                        : "Starting on the TV");
            } else if (method.equals("POST") && path.equals("/api/ambilight/stop")) {
                AmbilightService.stop(context);
                sendResult(client, () -> "Stopped");
            } else if (method.equals("POST") && path.equals("/api/ambilight/test")) {
                JSONObject o = new JSONObject(text);
                AmbilightService.test(context, o.optJSONObject("config"), o.optString("pattern"), o.optInt("strip"));
                sendResult(client, () -> "Test pattern on the strips for a minute");
            } else if (method.equals("POST") && path.equals("/api/radar/show")) {
                showRadar.run();
                sendResult(client, () -> "Showing on the TV");
            } else if (method.equals("POST") && path.equals("/api/alerts/test")) {
                testAlert.run();
                sendResult(client, () -> "Test alert on the TV");
            } else if (method.equals("POST") && path.equals("/api/reminders/test")) {
                testReminder.run();
                sendResult(client, () -> "Test reminder on the TV");
            } else if (method.equals("POST") && path.equals("/api/digest/test")) {
                testDigest.run();
                sendResult(client, () -> "Tomorrow's events on the TV");
            } else if (method.equals("POST") && path.equals("/api/run")) {
                JSONObject o = new JSONObject(text);
                Store.Action a = Store.Action.fromJson(o.getJSONObject("action"));
                sendResult(client, () -> HomeAssistant.call(
                        o.optString("haUrl"), tokenOr(o.optString("haToken")), a));
            } else {
                send(client, 404, "text/plain", "Not found");
            }
        } catch (JSONException e) {
            send(client, 400, "application/json", error(e.getMessage()));
        }
    }

    private interface Call { String run() throws IOException; }

    /** Runs a Home Assistant call and reports {ok, message} either way. */
    private void sendResult(Socket client, Call call) throws IOException {
        JSONObject r = new JSONObject();
        try {
            try {
                String reply = call.run().trim();
                r.put("ok", true).put("message", reply.length() > 300 ? reply.substring(0, 300) + "…" : reply);
            } catch (IOException e) {
                r.put("ok", false).put("message", e.getMessage());
            }
        } catch (JSONException ignored) { }
        send(client, 200, "application/json", r.toString());
    }

    /** A token typed on the page wins; blank means "use the saved one". */
    private String tokenOr(String typed) {
        return typed == null || typed.isEmpty() ? store.haToken() : typed;
    }

    private static String error(String message) {
        try { return new JSONObject().put("error", message).toString(); }
        catch (JSONException e) { return "{}"; }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (int b; (b = in.read()) >= 0; ) {
            if (b == '\n') return buf.toString("UTF-8").replace("\r", "");
            buf.write(b);
            if (buf.size() > 8192) throw new IOException("header line too long");
        }
        return buf.size() == 0 ? null : buf.toString("UTF-8");
    }

    private static void send(Socket client, int code, String type, String body) throws IOException {
        send(client, code, type, body.getBytes(StandardCharsets.UTF_8), false);
    }

    /** {@code cacheable}: the bundled icon files only change with the APK. */
    private static void send(Socket client, int code, String type, byte[] bytes,
            boolean cacheable) throws IOException {
        String head = String.format(Locale.ROOT,
                "HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\n"
                        + "Cache-Control: %s\r\nConnection: close\r\n\r\n",
                code, code < 400 ? "OK" : "Error", type, bytes.length,
                cacheable ? "max-age=86400" : "no-store");
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            return HomeAssistant.readBytes(in);
        }
    }

    private String page() throws IOException {
        try (InputStream in = context.getAssets().open("config.html")) {
            return HomeAssistant.readAll(in);
        }
    }

    // -------------------------------------------------------------- state

    private JSONObject state() throws JSONException {
        JSONArray apps = new JSONArray();
        for (Apps.App a : Apps.sorted(new ArrayList<>(Apps.collect(context).values()))) {
            apps.put(new JSONObject().put("pkg", a.pkg).put("label", a.label));
        }
        JSONArray actions = new JSONArray();
        for (Store.Action a : store.actions()) actions.put(a.toJson());
        String name = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.DEVICE_NAME);

        return new JSONObject()
                .put("device", name == null ? "SHIELD" : name)
                .put("apps", apps)
                .put("pinned", new JSONArray(store.pinned()))
                .put("hidden", new JSONArray(store.hidden()))
                .put("actions", actions)
                .put("holdMs", store.holdMs())
                .put("singlePress", store.singlePress())
                .put("sleepMinutes", store.sleepMinutes())
                .put("sleepShown", store.sleepShown())
                .put("cameras", new JSONArray(store.cameras()))
                .put("calendarShown", store.calendarShown())
                .put("calendars", new JSONArray(store.calendars()))
                .put("calendarStatus", new JSONObject(store.calendarStatus()))
                .put("reminderTimes", Agenda.formatLeads(store.reminderTimes()))
                .put("reminderChime", store.reminderChime())
                .put("digestShown", store.digestShown())
                .put("digestTime", store.digestTime())
                .put("visualizerHold", store.visualizerHold())
                .put("visualizer", store.visualizer())
                .put("radarShown", store.radarShown())
                .put("radarSource", store.radarSource())
                .put("radarDefault", Store.DEFAULT_RADAR)
                .put("radarLightningDistance", store.radarLightningDistance())
                .put("radarLightningStrike", store.radarLightningStrike())
                .put("radarLightningMiles", store.radarLightningMiles())
                .put("alertsShown", store.alertsShown())
                .put("alertsSeverity", store.alertsSeverity())
                .put("alertsToneSeconds", store.alertsToneSeconds())
                .put("alertsPoint", store.alertsPoint())
                .put("alertsSeverities", new JSONArray(Alerts.SEVERITIES))
                .put("alertsStatus", new JSONObject(store.alertsStatus()))
                .put("ambilight", store.ambilight())
                .put("backupGroups", Backup.groupsJson())
                .put("restoreUndo", undoInfo())
                .put("haUrl", store.haUrl())
                .put("haTokenSet", !store.haToken().isEmpty())
                .put("background", background());
    }

    /**
     * What the visualizer listens to (its last status), the preset it shows,
     * the liked ones, and what is playing right now, for picking an app;
     * polled by the page, so it is kept out of /api/state.
     */
    private JSONObject visualizerNow() throws JSONException {
        String dump = AudioSessions.dump(context);
        JSONArray playing = new JSONArray();
        for (AudioSessions.Playing p : AudioSessions.active(dump, android.os.Process.myPid())) {
            playing.put(new JSONObject().put("session", p.session).put("pkg", p.pkg));
        }
        return new JSONObject()
                .put("status", new JSONObject(store.visualizerStatus()))
                .put("showing", store.visualizerShowing())
                .put("liked", store.visualizerLiked())
                .put("playing", playing)
                .put("dumpDenied", AudioSessions.denied(dump));
    }

    /** When the settings an undo would bring back were saved; null when there's nothing to undo. */
    private Object undoInfo() {
        try {
            JSONObject u = new JSONObject(store.prefs().getString(Backup.KEY_UNDO, ""));
            return new JSONObject().put("at", u.optLong("createdAt"));
        } catch (JSONException e) {
            return JSONObject.NULL;
        }
    }

    private JSONObject background() throws JSONException {
        return new JSONObject()
                .put("weather", store.bgWeather())
                .put("sun", store.bgSun())
                .put("season", store.bgSeason())
                .put("holidays", store.bgHolidays())
                .put("defaultHolidays", Backgrounds.DEFAULT_HOLIDAYS)
                .put("override", new JSONObject(store.bgOverrideJson()))
                .put("scrimDay", store.scrimDay())
                .put("scrimNight", store.scrimNight())
                .put("scrimEnd", store.scrimEnd())
                .put("contentWidth", store.contentWidth())
                .put("status", new JSONObject(store.bgStatus()))
                .put("seasons", new JSONArray(Backgrounds.SEASONS))
                .put("times", new JSONArray(Backgrounds.TIMES))
                .put("conditions", new JSONArray(Backgrounds.CONDITIONS));
    }

    private String deviceName() {
        String name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        return name == null ? "SHIELD" : name;
    }

    private JSONObject backup() throws JSONException {
        return Backup.create(store.prefs().getAll(), deviceName(), System.currentTimeMillis());
    }

    /** Checks the whole backup first (read throws), keeps what it replaces for undo, then writes it. */
    private void restore(JSONObject b, List<String> groups) throws JSONException {
        java.util.Map<String, Object> changes = Backup.read(b, groups);
        String undo = backup().toString();
        Backup.apply(store.prefs(), changes);
        store.prefs().edit().putString(Backup.KEY_UNDO, undo).commit();
    }

    private void save(JSONObject o) throws JSONException {
        JSONObject bg = o.optJSONObject("background");
        // Checked before anything is written, so a typo saves nothing.
        if (bg != null && bg.has("holidays")) {
            List<String> errors = Backgrounds.parseHolidays(bg.getString("holidays")).errors;
            if (!errors.isEmpty()) throw new JSONException("Holidays: " + errors.get(0));
        }
        if (o.has("pinned")) store.setPinned(strings(o.getJSONArray("pinned")));
        if (o.has("hidden")) store.setHidden(new HashSet<>(strings(o.getJSONArray("hidden"))));
        if (o.has("actions")) {
            List<Store.Action> list = new ArrayList<>();
            JSONArray a = o.getJSONArray("actions");
            for (int i = 0; i < a.length(); i++) list.add(Store.Action.fromJson(a.getJSONObject(i)));
            store.setActions(list);
        }
        if (o.has("holdMs")) store.setHold(Math.max(500, Math.min(10_000, o.getLong("holdMs"))));
        if (o.has("singlePress")) store.setSinglePress(o.getString("singlePress"));
        if (o.has("sleepMinutes")) store.setSleep(o.getInt("sleepMinutes"), o.optBoolean("sleepShown", true));
        if (o.has("cameras")) store.setCameras(strings(o.getJSONArray("cameras")));
        if (o.has("calendarShown")) {
            JSONArray c = o.optJSONArray("calendars");
            store.setCalendars(o.getBoolean("calendarShown"), c == null ? store.calendars() : strings(c));
        }
        if (o.has("digestShown")) store.setDigest(o.getBoolean("digestShown"), o.optString("digestTime"));
        if (o.has("reminderTimes")) {
            store.setReminders(o.getString("reminderTimes"), o.optBoolean("reminderChime", true));
        }
        if (o.has("visualizerHold")) {
            JSONObject v = o.optJSONObject("visualizer");
            store.setVisualizer(o.getBoolean("visualizerHold"), v == null ? store.visualizer() : v);
        }
        if (o.has("ambilight")) store.setAmbilight(o.getJSONObject("ambilight"));
        if (o.has("radarShown")) store.setRadar(o.getBoolean("radarShown"), o.optString("radarSource").trim());
        if (o.has("alertsShown")) {
            store.setAlerts(o.getBoolean("alertsShown"), o.optString("alertsSeverity"),
                    o.optInt("alertsToneSeconds", Alerts.DEFAULT_TONE_SECONDS), o.optString("alertsPoint").trim());
        }
        if (o.has("radarLightningMiles")) {
            store.setRadarLightning(o.optString("radarLightningDistance").trim(),
                    o.optString("radarLightningStrike").trim(), o.getInt("radarLightningMiles"));
        }
        if (o.has("haUrl")) store.setHaUrl(o.getString("haUrl").trim());
        if (o.optBoolean("clearToken")) store.setHaToken("");
        else if (!o.optString("haToken").isEmpty()) store.setHaToken(o.getString("haToken").trim());
        if (bg != null) {
            JSONObject override = bg.optJSONObject("override");
            store.setBackground(trimmed(bg, "weather"), trimmed(bg, "sun"), trimmed(bg, "season"),
                    bg.has("holidays") ? bg.getString("holidays") : null,
                    override == null ? null : override.toString());
            if (bg.has("scrimDay")) store.setPercent(Store.KEY_SCRIM_DAY, bg.getInt("scrimDay"));
            if (bg.has("scrimNight")) store.setPercent(Store.KEY_SCRIM_NIGHT, bg.getInt("scrimNight"));
            if (bg.has("scrimEnd")) store.setPercent(Store.KEY_SCRIM_END, bg.getInt("scrimEnd"));
            // Below 30% there is no room for a banner.
            if (bg.has("contentWidth")) {
                store.setPercent(Store.KEY_CONTENT_WIDTH, Math.max(30, bg.getInt("contentWidth")));
            }
        }
    }

    private static String trimmed(JSONObject o, String key) throws JSONException {
        return o.has(key) ? o.getString(key).trim() : null;
    }

    private static List<String> strings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }
}
