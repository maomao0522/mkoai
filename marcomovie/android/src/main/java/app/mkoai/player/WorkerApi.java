package app.mkoai.player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: shared HTTP helper for the Cloudflare Worker
 * (https://maoki-mkoai-api.maotai0522.workers.dev). All home-list fetchers
 * (Trakt / TVmaze / Simkl fallback) go through here so HTTP status is
 * visible: 429 is surfaced as {@link RateLimited} (with retry_after),
 * anything else non-200 becomes an IOException carrying the status and
 * the upstream {@code error} field. No API keys live in the app — the
 * Worker holds every secret; this class only calls Worker routes.
 *
 * <p>All methods are static and blocking: callers must invoke them from a
 * background thread, never the UI thread. Failures throw IOException (or
 * return null where the caller specified) — never fake data.
 */
final class WorkerApi {
    public static final String BASE = "https://maoki-mkoai-api.maotai0522.workers.dev";

    private static final int TIMEOUT_MS = 10000;

    /** Upstream rate limit (HTTP 429). {@link #retryAfter} is seconds. */
    public static final class RateLimited extends IOException {
        public final int retryAfter;

        public RateLimited(int r) {
            super("rate limited, retry after " + r + "s");
            retryAfter = r;
        }
    }

    /** URL-encode with UTF-8; a space becomes %20, not +. */
    public static String encode(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception ignored) {
            return s;
        }
    }

    /**
     * GET BASE + path, expecting a JSON object. 200 returns the parsed
     * object (a non-JSON body is an IOException); 429 throws RateLimited
     * with retry_after from the body (default 60s); 503 / 404 / anything
     * else throws IOException whose message carries the status and the
     * body's {@code error} field.
     */
    public static JSONObject getJson(String path) throws IOException {
        String body = request(path, "GET", null);
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("Worker 回傳非 JSON: " + e.getMessage());
        }
    }

    /**
     * GET BASE + path, returning the raw body (e.g. subtitle VTT).
     * Same error mapping as {@link #getJson}.
     */
    public static String getText(String path) throws IOException {
        return request(path, "GET", null);
    }

    /**
     * POST a JSON body to BASE + path, expecting a JSON object back.
     * Same error mapping as {@link #getJson}.
     */
    public static JSONObject postJson(String path, JSONObject body) throws IOException {
        String raw = request(path, "POST", body == null ? "{}" : body.toString());
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            throw new IOException("Worker 回傳非 JSON: " + e.getMessage());
        }
    }

    /** One request with the shared timeout / status mapping. Returns the body on 200. */
    private static String request(String path, String method, String jsonBody) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = jsonBody.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = (status >= 200 && status < 300)
                    ? c.getInputStream()
                    : c.getErrorStream();
            String body = readAll(in);
            if (status == 200) return body;
            if (status == 429) throw new RateLimited(parseRetryAfter(body));
            throw new IOException("HTTP " + status + errorSuffix(body));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** retry_after from a 429 body (string or number seconds); 60 when unreadable. */
    private static int parseRetryAfter(String body) {
        try {
            JSONObject o = new JSONObject(body);
            Object v = o.opt("retry_after");
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) return Integer.parseInt(((String) v).trim());
        } catch (Exception ignored) {
        }
        return 60;
    }

    /** " <error>" from an error body, or "" when there is none. */
    private static String errorSuffix(String body) {
        try {
            String err = new JSONObject(body).optString("error", "").trim();
            return err.isEmpty() ? "" : " " + err;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
