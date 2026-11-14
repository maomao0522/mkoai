package app.mkoai.player;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai Simkl trending titles (primary home content, user choice 2026-11-12).
 * Simkl is a global (non-mainland) service, so it loads from Taiwan where
 * Douban fails. No API key needed for the trending endpoints.
 *
 * <p>TV: https://api.simkl.com/tv/trending?extended=posters
 * Movies: https://api.simkl.com/movies/trending?extended=posters
 * Each returns a JSON array (50 items) with title, poster (partial path),
 * and ratings {simkl:{rating}, imdb:{rating}}. Poster full URL is
 * https://simkl.in/posters/&lt;path&gt;_m.jpg (verified HTTP 200).
 *
 * <p>Each card's id is "msearch:&lt;title&gt;", so tapping it runs the normal
 * cross-source search for that title (and auto-plays the best match).
 * Titles are English/romanized; the search expands them to Traditional too
 * (see SearchVariants), so both scripts find the title.
 */
final class SimklHome {
    private static final String TV_URL =
            "https://api.simkl.com/tv/trending?extended=posters";
    private static final String MOVIES_URL =
            "https://api.simkl.com/movies/trending?extended=posters";
    private static final String POSTER_BASE = "https://simkl.in/posters/";
    private static final int PER_SECTION = 10;

    /** Fetch Simkl trending (10 TV + 10 movies). Throws on failure. */
    static List<Cms.Video> fetchHot() throws Exception {
        List<Cms.Video> out = new ArrayList<>();
        try {
            out.addAll(fetchSection(TV_URL, "影劇"));
        } catch (Throwable ignored) {
        }
        try {
            out.addAll(fetchSection(MOVIES_URL, "電影"));
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) throw new java.io.IOException("Simkl 無資料");
        return out;
    }

    private static List<Cms.Video> fetchSection(String url, String kind) throws Exception {
        String raw = Cms.fetch(url, null);
        JSONArray data = new JSONArray(raw);
        List<String> titles = new ArrayList<>();
        List<String> posters = new ArrayList<>();
        List<String> remarks = new ArrayList<>();
        for (int i = 0; i < data.length() && titles.size() < PER_SECTION; i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) continue;
            String title = o.optString("title", "").trim();
            if (title.isEmpty()) continue;
            titles.add(title);
            String poster = o.optString("poster", "").trim();
            posters.add(poster.isEmpty() ? "" : POSTER_BASE + poster + "_m.jpg");
            String rate = rating(o);
            remarks.add(rate.isEmpty() ? kind + "熱門" : kind + " " + rate + " 分");
        }
        // mkoai: translate foreign titles to correct Traditional Chinese
        // via Douban (TitleTranslator) — never the broken char converter.
        // The translated Chinese title is also what msearch uses, since the
        // user's source carries Chinese titles.
        List<String> displayTitles = TitleTranslator.toTraditionalAll(titles);
        List<Cms.Video> out = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            Cms.Video v = new Cms.Video();
            v.id = "msearch:" + displayTitles.get(i);
            v.title = displayTitles.get(i);
            v.poster = posters.get(i);
            v.remarks = remarks.get(i);
            // mkoai: tag carries the kind so the home renders two
            // sections (Simkl 影劇 / Simkl 電影) instead of one.
            v.tag = "simkl-" + kind;
            out.add(v);
        }
        return out;
    }

    /** Prefer the Simkl rating, fall back to IMDb. Returns "" when neither exists. */
    private static String rating(JSONObject o) {
        JSONObject ratings = o.optJSONObject("ratings");
        if (ratings == null) return "";
        JSONObject simkl = ratings.optJSONObject("simkl");
        if (simkl != null) {
            double r = simkl.optDouble("rating", 0);
            if (r > 0) return trimRating(r);
        }
        JSONObject imdb = ratings.optJSONObject("imdb");
        if (imdb != null) {
            double r = imdb.optDouble("rating", 0);
            if (r > 0) return trimRating(r);
        }
        return "";
    }

    private static String trimRating(double r) {
        String s = Double.toString(Math.round(r * 10) / 10.0);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
