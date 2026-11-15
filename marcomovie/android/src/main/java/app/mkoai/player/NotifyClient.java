package app.mkoai.player;

import java.io.IOException;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: ntfy push client, through the Cloudflare Worker (POST /api/notify). Pure logic, no
 * UI. All network goes through {@link WorkerApi}; no key lives in the app.
 *
 * <p>Worker shape: POST body {@code {title, message, topic?, priority?}} (topic defaults
 * server-side to {@link #TOPIC} when omitted). Success 200 {@code {source:"ntfy", topic, title,
 * message_id, result}}. Failure is always 502 {@code {error:"notify_failed", status, retry_after,
 * hint}} — even for an upstream 429, so <b>rate limiting must be read from the body
 * {@code retry_after}, not the status code</b>. The caller (settings page) shows a failure and
 * does not retry immediately.
 */
public final class NotifyClient {

    /** The ntfy topic name, shown on the settings page. Not sent in the POST body — the server
     * uses this as its default topic. */
    public static final String TOPIC = "maoki-mkoai-gq6wmzc2";

    private NotifyClient() {}

    /**
     * Send a test push via the Worker. No topic is sent (server default). Returns the upstream
     * JSON on success. On failure throws {@link IOException} whose message carries the error and
     * {@code retry_after} when the Worker reported one.
     */
    public static JSONObject sendTest(String title, String message) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("title", title == null ? "" : title);
            body.put("message", message == null ? "" : message);
        } catch (Exception e) {
            throw new IOException("notify_request_failed", e);
        }
        return WorkerApi.postJson("/api/notify", body);
    }
}
