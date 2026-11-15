package app.mkoai.player;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: PostHog analytics client, through the Cloudflare Worker (POST /api/posthog).
 * Pure logic, no UI. All network goes through {@link WorkerApi}; no key lives in the app.
 *
 * <p>NOTE: the /api/posthog route is not deployed yet (POSTHOG_API_KEY Worker Secret still has to
 * be set by the user before it can be deployed), so every capture currently fails server-side.
 * That is expected: {@link #capture(String, JSONObject)} swallows all failures silently and can
 * never break the app.
 *
 * <p>Prefs access: {@code Prefs} holds key constants only — this tree has no Prefs read/write
 * helpers (see {@code SupabaseAuth.prefs(Context)}). The caller must call {@link #init(Context)}
 * once (e.g. from MainActivity) so the distinct_id and the opt-out switch persist in
 * SharedPreferences (file "movie"). Before init, an in-memory random id is used and the switch
 * reads as enabled, so nothing crashes.
 *
 * <p>Privacy: the distinct_id is a random UUID generated on first use and stored locally. It is
 * never derived from any personal identifier — no account, device id, or phone number.
 *
 * <p>Only these five events are sent (wired by the caller elsewhere): app_open, video_play
 * (properties only {@code {title}} — never a URL or source), video_search, trailer_open,
 * subtitle_download.
 */
public final class PostHogClient {

    private static final String KEY_DISTINCT_ID = "posthog_distinct_id";
    private static final String KEY_ANALYTICS_ENABLED = "analytics_enabled";

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "posthog-capture");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private static volatile Context appContext;
    private static volatile String memoryDistinctId;
    private static volatile Boolean memoryAnalyticsEnabled;

    private PostHogClient() {}

    /** Give this client an application context so ids/switches persist. Safe to call twice. */
    public static void init(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        Context c = appContext;
        if (c == null) return null;
        return c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
    }

    /**
     * Stable anonymous distinct_id. Reads the stored {@code posthog_distinct_id}; when absent,
     * generates a random UUID, stores it, and returns it. Before {@link #init(Context)} an
     * in-memory UUID is used (not persisted).
     */
    public static String distinctId() {
        SharedPreferences p = prefs();
        if (p != null) {
            String id = p.getString(KEY_DISTINCT_ID, null);
            if (id == null || id.isEmpty()) {
                id = UUID.randomUUID().toString();
                p.edit().putString(KEY_DISTINCT_ID, id).apply();
            }
            return id;
        }
        if (memoryDistinctId == null) {
            synchronized (PostHogClient.class) {
                if (memoryDistinctId == null) memoryDistinctId = UUID.randomUUID().toString();
            }
        }
        return memoryDistinctId;
    }

    /** Analytics opt-out switch (Prefs boolean {@code analytics_enabled}); default true. */
    public static boolean isAnalyticsEnabled() {
        SharedPreferences p = prefs();
        if (p != null) return p.getBoolean(KEY_ANALYTICS_ENABLED, true);
        if (memoryAnalyticsEnabled != null) return memoryAnalyticsEnabled;
        return true;
    }

    /** Set the analytics opt-out switch. */
    public static void setAnalyticsEnabled(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putBoolean(KEY_ANALYTICS_ENABLED, on).apply();
            return;
        }
        memoryAnalyticsEnabled = on;
    }

    /**
     * mkoai 2.0.62: the event body sent to /api/posthog:
     * {@code {event, distinct_id, properties}}. Pure, so the JVM checks can
     * verify the shape — in particular that the app NEVER sends an api_key
     * (the Worker injects POSTHOG_API_KEY server-side).
     */
    static JSONObject buildEventBody(String event, String distinctId, JSONObject properties) {
        JSONObject body = new JSONObject();
        try {
            body.put("event", event);
            body.put("distinct_id", distinctId);
            body.put("properties", properties == null ? new JSONObject() : properties);
        } catch (Exception ignored) {
        }
        return body;
    }

    /**
     * Send one analytics event in a background daemon thread. Does nothing when analytics is
     * switched off. Any failure (including the /api/posthog route not being deployed yet) is
     * swallowed silently — never throws, never retries, never touches the main flow. Body:
     * {@code {event, distinct_id, properties}}.
     */
    public static void capture(final String event, final JSONObject properties) {
        if (event == null || event.isEmpty()) return;
        if (!isAnalyticsEnabled()) return;
        final String id = distinctId();
        EXECUTOR.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            WorkerApi.postJson("/api/posthog", buildEventBody(event, id, properties));
                        } catch (Throwable ignored) {
                            // Analytics must never break the app; route may not exist yet.
                        }
                    }
                });
    }
}
