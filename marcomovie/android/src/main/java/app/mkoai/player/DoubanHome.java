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
