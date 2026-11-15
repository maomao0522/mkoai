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

    /**
     * Upstream rate limit (HTTP 429). {@link #retryAfter} is seconds
     * (60 when the body had no usable retry_after, the old default).
     * mkoai 2.0.66: also carries the Worker's {@code error} /
     * {@code source} fields and the raw {@code retry_after}
     * ({@link #retryAfterOrNull}, null when the body had none) — the
     * quota-hide classifier needs them: a null retry_after is
     * meaningful, because the daily quotas (YouTube, OMDb) send
     * retry_after:null and the app must compute the reset itself.
     */
    public static final class RateLimited extends IOException {
        public final int retryAfter;
        /** The Worker's error field (e.g. "upstream_rate_limited",
         * "deepl_quota_exhausted"), or null when the body had none. */
        public final String error;
        /** The Worker's source field (e.g. "youtube"), or null. */
        public final String source;
        /** retry_after from the body, null when absent / null /
         * unreadable. */
        public final Integer retryAfterOrNull;

        public RateLimited(int r) {
            this(r, null, null, null);
        }

        public RateLimited(int r, String error, String source, Integer retryAfterOrNull) {
            super("rate limited, retry after " + r + "s");
            retryAfter = r;
            this.error = error;
            this.source = source;
            this.retryAfterOrNull = retryAfterOrNull;
        }
    }

    /**
     * mkoai 2.0.66: a non-200, non-429 Worker failure, carrying the
     * status and the body's {@code error} / {@code retry_after} so
     * quota-hide can classify it (the notify route reports its
     * upstream 429 inside a 502 body: {error:"notify_failed",
     * status:429, retry_after}). Extends IOException, and the message
     * keeps the exact old format ("HTTP 502 notify_failed"), so every
     * existing catch still works.
     */
    public static final class HttpError extends IOException {
        public final int status;
        public final String error;
        public final Integer retryAfterOrNull;

        public HttpError(int status, String error, Integer retryAfterOrNull) {
            super("HTTP " + status + (error == null || error.isEmpty() ? "" : " " + error));
            this.status = status;
            this.error = error;
            this.retryAfterOrNull = retryAfterOrNull;
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
     * with retry_after from the body (default 60s) plus the body's
     * error / source fields; any other non-200 throws HttpError
     * carrying the status and the body's error / retry_after, with the
     * same message format as before ("HTTP 502 notify_failed").
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
            if (status == 429) {
                Integer ra = parseRetryAfterOrNull(body);
                throw new RateLimited(
                        ra == null ? 60 : ra.intValue(),
                        parseErrorField(body), parseSourceField(body), ra);
            }
            throw new HttpError(status, parseErrorField(body), parseRetryAfterOrNull(body));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** retry_after from an error body (string or number seconds); null when absent / null / unreadable. */
    private static Integer parseRetryAfterOrNull(String body) {
        try {
            JSONObject o = new JSONObject(body);
            Object v = o.opt("retry_after");
            if (v instanceof Number) return Integer.valueOf(((Number) v).intValue());
            if (v instanceof String) return Integer.valueOf(Integer.parseInt(((String) v).trim()));
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The body's error field, or "" when there is none. */
    private static String parseErrorField(String body) {
        try {
            return new JSONObject(body).optString("error", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** The body's source field, or null when there is none. */
    private static String parseSourceField(String body) {
        try {
            String s = new JSONObject(body).optString("source", "").trim();
            return s.isEmpty() ? null : s;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** " <error>" from an error body, or "" when there is none. */
    private static String errorSuffix(String body) {
        String err = parseErrorField(body);
        return err.isEmpty() ? "" : " " + err;
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
