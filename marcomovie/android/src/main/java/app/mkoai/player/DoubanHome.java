package app.mkoai.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai Douban hot titles (home fallback). When the selected source cannot
 * load its home page, the home shows Douban's hot list instead of an empty
 * error page. Each card's id is "msearch:<title>", so tapping it runs the
 * normal cross-source search for that title (and auto-plays the best match).
 * Douban titles are Simplified; the search expands them to Traditional too
 * (see SearchVariants), so both scripts find the title.
 */
final class DoubanHome {
    private static final String HOT_URL =
            "https://movie.douban.com/j/new_search_subjects"
                    + "?sort=U&range=0,10&tags=&playable=1&start=0";

    // mkoai 2.0.58: Douban new films (新片） for the TV home — newest
    // first (sort=T). Same endpoint shape as the hot list, same UA +
    // Referer headers. The Worker route may not be live yet, so the
    // Worker fetch is tried first and direct Douban is the fallback.
    private static final String NEW_URL =
            "https://movie.douban.com/j/new_search_subjects"
                    + "?sort=T&range=0,10&tags=&playable=1&start=0";
    private static final String WORKER_NEW_URL =
            "https://maoki-mkoai-api.maotai0522.workers.dev/api/douban/new";
    private static final int NEW_MAX = 10;

    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; POCO F5) AppleWebKit/537.36"
                        + " (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        headers.put("Referer", "https://movie.douban.com/");
        return headers;
    }

    /**
     * Fetch Douban new films. Tries the Cloudflare Worker first (much more
     * reliable from Taiwan than movie.douban.com directly); on ANY Worker
     * failure — route not live yet (404), upstream failure (502), or a bad
     * payload — falls back to direct Douban. Throws when both fail (the
     * caller treats it as "no Douban new films" and keeps the rest of the
     * home). Never returns null.
     */
    static List<Cms.Video> fetchNew() throws Exception {
        try {
            String raw = Cms.fetch(WORKER_NEW_URL, headers());
            if (isUsableNewPayload(raw)) return parseNew(raw);
        } catch (Throwable ignored) {
            // Worker route not live yet or Worker/Douban failed — direct.
        }
        return parseNew(Cms.fetch(NEW_URL, headers()));
    }

    /**
     * True when a payload is a usable new_search_subjects response (a
     * JSON object with a non-empty "data" array). A Worker 404/502 (HTML
     * page or {"error":...} JSON) is not usable, so the caller falls back
     * to direct Douban. Never throws.
     */
    static boolean isUsableNewPayload(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray data = root.optJSONArray("data");
            return data != null && data.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Parse a new_search_subjects payload into cards (cap 10). Throws on bad JSON. */
    static List<Cms.Video> parseNew(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray data = root.optJSONArray("data");
        List<Cms.Video> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length() && out.size() < NEW_MAX; i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) continue;
            String title = o.optString("title", "").trim();
            if (title.isEmpty()) continue;
            Cms.Video v = new Cms.Video();
            v.id = "msearch:" + title;
            v.title = title;
            v.poster = o.optString("cover", "");
            String rate = o.optString("rate", "").trim();
            v.remarks = rate.isEmpty() ? "豆瓣新片" : "豆瓣 " + rate + " 分";
            v.tag = "douban";
            out.add(v);
        }
        return out;
    }

    /** Fetch Douban hot titles. Throws on network/parse failure (caller falls back). */
    static List<Cms.Video> fetchHot() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; POCO F5) AppleWebKit/537.36"
                        + " (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        headers.put("Referer", "https://movie.douban.com/");
        String raw = Cms.fetch(HOT_URL, headers);
        JSONObject root = new JSONObject(raw);
        JSONArray data = root.optJSONArray("data");
        List<Cms.Video> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) continue;
            String title = o.optString("title", "").trim();
            if (title.isEmpty()) continue;
            Cms.Video v = new Cms.Video();
            v.id = "msearch:" + title;
            v.title = title;
            v.poster = o.optString("cover", "");
            String rate = o.optString("rate", "").trim();
            v.remarks = rate.isEmpty() ? "豆瓣熱門" : "豆瓣 " + rate + " 分";
            v.tag = "douban";
            out.add(v);
        }
        return out;
    }
}
