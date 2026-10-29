package dev.alex.minlauncher;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Minimal Home Assistant REST client. Blocking: call off the main thread.
 * https://developers.home-assistant.io/docs/api/rest/
 */
final class HomeAssistant {

    /** Generous: /api/states on a large install is a few MB. */
    private static final int TIMEOUT_MS = 10_000;

    private HomeAssistant() { }

    /** An error status from Home Assistant, so callers can tell 404 from a dead network. */
    static final class HttpError extends IOException {
        final int code;
        HttpError(int code, String message) { super(message); this.code = code; }
    }

    /** GET /api/ — "API running." when the URL and token are good. */
    static String ping(String url, String token) throws IOException {
        return request(url, token, "GET", "/api/", null);
    }

    /** GET /api/states — every entity, for the config page's picker. */
    static String states(String url, String token) throws IOException {
        return request(url, token, "GET", "/api/states", null);
    }

    /** GET /api/services — each domain's services, with descriptions. */
    static String services(String url, String token) throws IOException {
        return request(url, token, "GET", "/api/services", null);
    }

    /** GET /api/states/{entity} — {state, attributes, …}, or null if there is no such entity. */
    static JSONObject entity(String url, String token, String entityId) throws IOException {
        try {
            return new JSONObject(request(url, token, "GET", "/api/states/" + entityId, null));
        } catch (HttpError e) {
            if (e.code == 404) return null;
            throw e;
        } catch (JSONException e) {
            throw new IOException("Bad state for " + entityId + ": " + e.getMessage());
        }
    }

    /** An entity's state string, or null if there is no such entity. */
    static String entityState(String url, String token, String entityId) throws IOException {
        JSONObject o = entity(url, token, entityId);
        return o == null ? null : o.optString("state", null);
    }

    /** GET /api/camera_proxy/{entity} — the camera's current frame, usually JPEG. */
    static byte[] cameraSnapshot(String url, String token, String entityId) throws IOException {
        return raw(url, token, "GET", "/api/camera_proxy/" + entityId, null);
    }

    /**
     * The daily forecast from weather.get_forecasts (the forecast attribute is
     * gone from weather entities since 2024.3), oldest first; empty if none.
     */
    static JSONArray dailyForecast(String url, String token, String entityId) throws IOException {
        try {
            String body = new JSONObject().put("entity_id", entityId).put("type", "daily").toString();
            JSONObject o = new JSONObject(request(url, token, "POST",
                    "/api/services/weather/get_forecasts?return_response", body));
            JSONObject e = o.getJSONObject("service_response").optJSONObject(entityId);
            JSONArray f = e == null ? null : e.optJSONArray("forecast");
            return f == null ? new JSONArray() : f;
        } catch (JSONException e) {
            throw new IOException("Bad forecast for " + entityId + ": " + e.getMessage());
        }
    }

    /** GET /api/calendars — [{entity_id, name}] for every calendar entity. */
    static JSONArray calendars(String url, String token) throws IOException {
        try {
            return new JSONArray(request(url, token, "GET", "/api/calendars", null));
        } catch (JSONException e) {
            throw new IOException("Bad calendar list: " + e.getMessage());
        }
    }

    /**
     * GET /api/calendars/{entity}?start=…&amp;end=… — the events that overlap
     * the range, each with {summary, start, end}; start and end hold either
     * "dateTime" (ISO 8601) or, for all-day events, "date".
     */
    static JSONArray calendarEvents(String url, String token, String entityId, long startMs, long endMs)
            throws IOException {
        try {
            return new JSONArray(request(url, token, "GET", "/api/calendars/" + entityId
                    + "?start=" + utc(startMs) + "&end=" + utc(endMs), null));
        } catch (JSONException e) {
            throw new IOException("Bad events for " + entityId + ": " + e.getMessage());
        }
    }

    /** "2026-10-29T05:00:00Z": UTC, so there is no offset's "+" to escape in a query string. */
    private static String utc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** POST /api/services/{domain}/{service} with the action's JSON data. */
    static String call(String url, String token, Store.Action action) throws IOException {
        int dot = action.service.indexOf('.');
        if (dot <= 0 || dot == action.service.length() - 1) {
            throw new IOException("Service must look like domain.service");
        }
        String path = "/api/services/" + action.service.substring(0, dot)
                + "/" + action.service.substring(dot + 1);
        String body = action.data.trim().isEmpty() ? "{}" : action.data;
        return request(url, token, "POST", path, body);
    }

    private static String request(String url, String token, String method, String path,
            String body) throws IOException {
        return new String(raw(url, token, method, path, body), StandardCharsets.UTF_8);
    }

    private static byte[] raw(String url, String token, String method, String path,
            String body) throws IOException {
        String base = url == null ? "" : url.trim();
        if (base.isEmpty()) throw new IOException("Home Assistant URL is not set");
        if (token == null || token.isEmpty()) throw new IOException("Home Assistant token is not set");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            byte[] bytes = in == null ? new byte[0] : readBytes(in);
            if (code >= 400) {
                throw new HttpError(code, "HTTP " + code + " "
                        + new String(bytes, StandardCharsets.UTF_8).trim());
            }
            return bytes;
        } finally {
            c.disconnect();
        }
    }

    /**
     * A plain GET outside Home Assistant (the NWS radar and alerts), with no
     * token. The NWS asks clients to say who they are.
     */
    static byte[] fetchUrl(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "dev.alex.minlauncher (github.com/alex182/shield-launcher)");
            int code = c.getResponseCode();
            if (code >= 400) throw new HttpError(code, "HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readBytes(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** An ISO 8601 timestamp ("2026-10-28T14:02:04+00:00", as HA and the NWS write them) in epoch ms; -1 if it isn't one. */
    static long parseTime(String s) {
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return -1;
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1;   // unknown, unavailable
        }
    }

    static String readAll(InputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
