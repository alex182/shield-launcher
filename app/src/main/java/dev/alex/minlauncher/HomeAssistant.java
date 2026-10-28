package dev.alex.minlauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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

    /** GET /api/states/{entity} — its state string, or null if there is no such entity. */
    static String entityState(String url, String token, String entityId) throws IOException {
        try {
            return new JSONObject(request(url, token, "GET", "/api/states/" + entityId, null))
                    .optString("state", null);
        } catch (HttpError e) {
            if (e.code == 404) return null;
            throw e;
        } catch (JSONException e) {
            throw new IOException("Bad state for " + entityId + ": " + e.getMessage());
        }
    }

    /** GET /api/camera_proxy/{entity} — the camera's current frame, usually JPEG. */
    static byte[] cameraSnapshot(String url, String token, String entityId) throws IOException {
        return raw(url, token, "GET", "/api/camera_proxy/" + entityId, null);
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
