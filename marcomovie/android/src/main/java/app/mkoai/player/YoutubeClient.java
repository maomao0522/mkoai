package app.mkoai.player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: YouTube trailer search, through the Cloudflare Worker (GET /api/youtube). Pure
 * logic, no UI. All network goes through {@link WorkerApi}; no API key lives in the app.
 *
 * <p>Worker shape: 200 {@code {source, query, maxResults, cached, generated_at,
 * results:[{videoId, title, channel, thumbnail}]}} (thumbnail may be null). Quota exhaustion is
 * 429 {@code {error:"upstream_rate_limited", retry_after}}; missing key is 503
 * {@code {error:"youtube_key_not_configured"}}.
 */
public final class YoutubeClient {

    /** One trailer search hit. */
    public static final class Trailer {
        public String videoId;
        public String title;
        public String channel;
        public String thumbnail;
    }

    /** One search per title per session: key is the lower-cased query, value is the hit list. */
    private static final Map<String, List<Trailer>> CACHE =
            Collections.synchronizedMap(new HashMap<String, List<Trailer>>());

    private YoutubeClient() {}

    /**
     * Search trailers for a title. The caller passes the title; " trailer" is appended here.
     * Returns the cached list on a repeat query for the same title (no network). Throws
     * {@link WorkerApi.RateLimited} on 429 and {@link IOException} on any other failure.
     */
    public static List<Trailer> search(String query) throws IOException {
        if (query == null) query = "";
        String key = query.toLowerCase(Locale.ROOT);
        List<Trailer> cached = CACHE.get(key);
        if (cached != null) return cached;

        String path =
                "/api/youtube?q=" + WorkerApi.encode(query + " trailer") + "&maxResults=5";
        JSONObject envelope = WorkerApi.getJson(path);
        List<Trailer> out = new ArrayList<>();
        JSONArray results = envelope == null ? null : envelope.optJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                Trailer t = new Trailer();
                t.videoId = item.optString("videoId", null);
                t.title = item.optString("title", null);
                t.channel = item.optString("channel", null);
                t.thumbnail = item.optString("thumbnail", null);
                if (t.videoId == null || t.videoId.isEmpty()) continue;
                out.add(t);
            }
        }
        List<Trailer> frozen = Collections.unmodifiableList(out);
        CACHE.put(key, frozen);
        return frozen;
    }

    /** Canonical watch URL for a video id. */
    public static String watchUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }
}
