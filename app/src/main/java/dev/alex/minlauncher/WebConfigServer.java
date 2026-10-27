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
    private ServerSocket server;
    private Thread thread;

    WebConfigServer(Context context, Store store) {
        this.context = context.getApplicationContext();
        this.store = store;
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
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            return buf.toByteArray();
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
                .put("haUrl", store.haUrl())
                .put("haTokenSet", !store.haToken().isEmpty());
    }

    private void save(JSONObject o) throws JSONException {
        if (o.has("pinned")) store.setPinned(strings(o.getJSONArray("pinned")));
        if (o.has("hidden")) store.setHidden(new HashSet<>(strings(o.getJSONArray("hidden"))));
        if (o.has("actions")) {
            List<Store.Action> list = new ArrayList<>();
            JSONArray a = o.getJSONArray("actions");
            for (int i = 0; i < a.length(); i++) list.add(Store.Action.fromJson(a.getJSONObject(i)));
            store.setActions(list);
        }
        if (o.has("holdMs")) store.setHold(Math.max(500, Math.min(10_000, o.getLong("holdMs"))));
        if (o.has("haUrl")) store.setHaUrl(o.getString("haUrl").trim());
        if (o.optBoolean("clearToken")) store.setHaToken("");
        else if (!o.optString("haToken").isEmpty()) store.setHaToken(o.getString("haToken").trim());
    }

    private static List<String> strings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }
}
