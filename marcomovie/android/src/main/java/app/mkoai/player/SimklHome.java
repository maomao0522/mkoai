package app.mkoai.player;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai Simkl trending titles (primary home content, user choice 2026-11-12).
 * Simkl is a global (non-mainland) service, so it loads from Taiwan where
 * Douban fails.
 *
 * <p>Trending goes through the Cloudflare Worker, never direct:
 * GET /api/simkl?type=movies|tv&list=trending (the Worker holds the
 * SIMKL_CLIENT_ID secret and proxies api.simkl.com). A direct call to
 * api.simkl.com without client_id returns HTTP 412 client_id_failed
 * (live-verified 2026-11-15, movies + tv both 412) — do NOT call Simkl
 * directly from the app.
 *
 * <p>Trending entries carry NO title — verified live 2026-11-15 with the
 * real V1 Client ID, even with extended=full (each item has only poster /
 * fanart / ids{simkl_id} / release_date / rank / ratings / country /
 * runtime / status). So every title comes from the fallback:
 * GET /api/simkl?id=&lt;simkl_id&gt;&type=movies|tv, and an item whose
 * title cannot be resolved is skipped (never a blank card).
 * Poster full URL is https://simkl.in/posters/&lt;path&gt;_m.jpg
 * (verified HTTP 200). Ratings: {simkl:{rating}, imdb:{rating}}.
 *
 * <p>Each card's id is "msearch:&lt;title&gt;", so tapping it runs the normal
 * cross-source search for that title (and auto-plays the best match).
 * Titles are English/romanized; the search expands them to Traditional too
 * (see SearchVariants), so both scripts find the title.
 */
final class SimklHome {
    private static final String TV_URL = "/api/simkl?type=tv&list=trending";
    private static final String MOVIES_URL = "/api/simkl?type=movies&list=trending";
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

    /** mkoai 2.0.62: Simkl trending movies as home items. Throws on failure. */
    public static List<TmdbClient.Item> fetchMoviesItems() throws Exception {
        return fetchSectionItems(MOVIES_URL, "電影", true, null);
    }

    /** mkoai 2.0.66: same, recording a quota 429 into {@code quota} (phone home rows). */
    public static List<TmdbClient.Item> fetchMoviesItems(QuotaState2066.Store quota)
            throws Exception {
        return fetchSectionItems(MOVIES_URL, "電影", true, quota);
    }

    /** mkoai 2.0.62: Simkl trending TV as home items. Throws on failure. */
    public static List<TmdbClient.Item> fetchTvItems() throws Exception {
        return fetchSectionItems(TV_URL, "影劇", false, null);
    }

    /** mkoai 2.0.66: same, recording a quota 429 into {@code quota} (phone home rows). */
    public static List<TmdbClient.Item> fetchTvItems(QuotaState2066.Store quota)
            throws Exception {
        return fetchSectionItems(TV_URL, "影劇", false, quota);
    }

    /**
     * mkoai 2.0.62: Simkl trending movies as home videos — the exact
     * fetchSection output (id="msearch:"+翻譯標題、tag="simkl-電影").
     * Throws on failure; the caller hides the section.
     */
    public static List<Cms.Video> fetchMoviesVideos() throws Exception {
        return fetchSection(MOVIES_URL, "電影");
    }

    /**
     * mkoai 2.0.62: Simkl trending TV as home videos — the exact
     * fetchSection output (id="msearch:"+翻譯標題、tag="simkl-影劇").
     * Throws on failure; the caller hides the section.
     */
    public static List<Cms.Video> fetchTvVideos() throws Exception {
        return fetchSection(TV_URL, "影劇");
    }

    private static List<Cms.Video> fetchSection(String path, String kind) throws Exception {
        // 2.0.62: 與 fetchSectionItems 共用解析，再轉回舊形狀。
        // id / title / poster / remarks / tag 必須與 2.0.61 一字不改：
        // id="msearch:"+翻譯標題、remarks="影劇 x.x 分"／"影劇熱門"、
        // tag="simkl-影劇"／"simkl-電影"。
        List<TmdbClient.Item> items = fetchSectionItems(path, kind, kind.equals("電影"));
        List<Cms.Video> out = new ArrayList<>();
        for (TmdbClient.Item it : items) {
            Cms.Video v = new Cms.Video();
            v.id = "msearch:" + it.title;
            v.title = it.title;
            v.poster = it.poster;
            v.remarks = it.vote > 0 ? kind + " " + trimRating(it.vote) + " 分" : kind + "熱門";
            // mkoai: tag carries the kind so the home renders two
            // sections (Simkl 影劇 / Simkl 電影) instead of one.
            v.tag = "simkl-" + kind;
            out.add(v);
        }
        return out;
    }

    /**
     * Shared trending parser: up to PER_SECTION items with a title,
     * translated in one batch (see TitleTranslator). Item carries the
     * translated title, full poster URL, numeric rating, and the year
     * from release_date; date stays "" (fail-open).
     *
     * <p>The chart is fetched from the Worker, whose envelope is
     * {@code {source, type, list, data:[...]}} (see {@link #trendingData}).
     * The Worker route is not deployed yet (SIMKL_CLIENT_ID Secret
     * pending), so until then getJson throws and the caller hides the
     * row — expected fail-soft, never a crash.
     */
    private static List<TmdbClient.Item> fetchSectionItems(String path, String kind, boolean isMovie)
            throws Exception {
        return fetchSectionItems(path, kind, isMovie, null);
    }

    private static List<TmdbClient.Item> fetchSectionItems(String path, String kind,
            boolean isMovie, QuotaState2066.Store quota) throws Exception {
        JSONArray data;
        try {
            data = trendingData(WorkerApi.getJson(path));
        } catch (WorkerApi.RateLimited limited) {
            // mkoai 2.0.66: 限流就記進 QuotaState 再拋出，首頁在
            // retry_after 內不再重試（不再每次開首頁都打一次）。
            if (quota != null) {
                QuotaState2066.record(quota, QuotaState2066.SIMKL,
                        limited.error, limited.source, limited.retryAfterOrNull,
                        System.currentTimeMillis());
            }
            throw limited;
        }
        List<TmdbClient.Item> items = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < data.length() && items.size() < PER_SECTION; i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) continue;
            TmdbClient.Item it = parseItem(o, isMovie);
            if (it.title.isEmpty()) {
                // trending 無片名是常態（即使 extended=full 也沒有）：
                // /api/simkl?id= 查一次標題；查到就用，查不到就跳過
                // 該項（不產生空白卡）。該 Worker 路由尚未部署（待
                // SIMKL_CLIENT_ID Secret），目前查一定失敗、走跳過分支
                // ——預期內的 fail-soft，不要報錯。
                String title = lookupSimklTitle(o, isMovie);
                if (title == null || title.isEmpty()) continue;
                it.title = title;
            }
            items.add(it);
            titles.add(it.title);
        }
        // mkoai: translate foreign titles to correct Traditional Chinese
        // via Douban (TitleTranslator) — never the broken char converter.
        // The translated Chinese title is also what msearch uses, since the
        // user's source carries Chinese titles.
        List<String> displayTitles = TitleTranslator.toTraditionalAll(titles);
        for (int i = 0; i < items.size(); i++) items.get(i).title = displayTitles.get(i);
        return items;
    }

    /**
     * Pure: the trending array inside a Worker /api/simkl envelope
     * {@code {source, type, list, data:[...]}}. Empty when the envelope
     * has no data array — a 200 with no data hides the row, never crashes.
     */
    static JSONArray trendingData(JSONObject res) {
        if (res == null) return new JSONArray();
        JSONArray data = res.optJSONArray("data");
        return data != null ? data : new JSONArray();
    }

    /**
     * Pure: one Worker /api/simkl trending entry -> home Item. The title
     * is "" when the entry has none — trending never carries one (even
     * with extended=full), so the caller fills it via /api/simkl?id=
     * or skips the item. Poster expands to the full simkl.in URL;
     * rating prefers Simkl over IMDb; year comes from release_date.
     */
    static TmdbClient.Item parseItem(JSONObject o, boolean isMovie) {
        if (o == null) o = new JSONObject();
        TmdbClient.Item it = new TmdbClient.Item();
        it.type = isMovie ? "movie" : "tv";
        it.title = o.optString("title", "").trim();
        String poster = o.optString("poster", "").trim();
        it.poster = poster.isEmpty() ? "" : POSTER_BASE + poster + "_m.jpg";
        it.vote = ratingValue(o);
        it.year = yearOf(o.optString("release_date", "").trim());
        it.date = "";
        it.overview = "";
        it.backdrop = "";
        return it;
    }

    /** 缺片名時用 simkl_id 向 Worker 查標題；查不到回 null（呼叫端跳過該項）。 */
    private static String lookupSimklTitle(JSONObject o, boolean isMovie) {
        int simklId = 0;
        JSONObject ids = o.optJSONObject("ids");
        if (ids != null) simklId = ids.optInt("simkl_id", 0);
        if (simklId <= 0) simklId = o.optInt("id", 0);
        if (simklId <= 0) return null;
        try {
            JSONObject res = WorkerApi.getJson(
                    "/api/simkl?id=" + simklId + "&type=" + (isMovie ? "movies" : "tv"));
            JSONObject d = res.optJSONObject("data");
            if (d == null) return null;
            String title = d.optString("title", "").trim();
            return title.isEmpty() ? null : title;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Prefer the Simkl rating, fall back to IMDb. Returns "" when neither exists. */
    private static String rating(JSONObject o) {
        double r = ratingValue(o);
        return r > 0 ? trimRating(r) : "";
    }

    /** Prefer the Simkl rating, fall back to IMDb. Returns 0 when neither exists. */
    private static double ratingValue(JSONObject o) {
        JSONObject ratings = o.optJSONObject("ratings");
        if (ratings == null) return 0;
        JSONObject simkl = ratings.optJSONObject("simkl");
        if (simkl != null) {
            double r = simkl.optDouble("rating", 0);
            if (r > 0) return r;
        }
        JSONObject imdb = ratings.optJSONObject("imdb");
        if (imdb != null) {
            double r = imdb.optDouble("rating", 0);
            if (r > 0) return r;
        }
        return 0;
    }

    /** First 4 digits of a yyyy-MM-dd date, or "" when absent. */
    private static String yearOf(String date) {
        if (date == null || date.length() < 4) return "";
        String y = date.substring(0, 4);
        for (int i = 0; i < 4; i++) {
            if (y.charAt(i) < '0' || y.charAt(i) > '9') return "";
        }
        return y;
    }

    private static String trimRating(double r) {
        String s = Double.toString(Math.round(r * 10) / 10.0);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
