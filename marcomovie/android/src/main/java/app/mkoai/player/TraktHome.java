package app.mkoai.player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: Trakt trending / popular lists via the Cloudflare Worker
 * (GET /api/trakt?type=movies|shows&list=trending|popular).
 *
 * <p>Response shape: trending wraps each entry in a {@code movie} /
 * {@code show} object ({@code {watchers, movie:{title, year,
 * ids:{trakt, slug, imdb, tmdb}}}}); popular returns bare objects.
 * Trakt carries no images, so poster stays "" and the existing card
 * fallback chain supplies one. {@code watchers} is never shown as a
 * rating (vote stays 0).
 *
 * <p>Rate-limit fallback: if trending returns 429, popular is tried
 * exactly once; if popular fails too the error is thrown, never retried.
 * All methods are static and blocking — call from a background thread.
 *
 * <p>2.0.62 home sections: {@link #fetchMoviesVideos} /
 * {@link #fetchShowsVideos} convert the items to {@link Cms.Video} via
 * {@link TmdbClient#toVideo}, then best-effort fill posters from Fanart
 * (movies by TMDB id, shows by the TVDB id Trakt itself returns) under a
 * hard 3 s total budget. A poster that cannot be filled stays "" and the
 * card falls back to a placeholder + title.
 */
final class TraktHome {
    private static final int MAX_ITEMS = 20;

    /** Trakt trending movies as home items. Throws on failure. */
    public static List<TmdbClient.Item> fetchMoviesItems() throws IOException {
        return fetchItems("movies", true);
    }

    /** Trakt trending shows as home items. Throws on failure. */
    public static List<TmdbClient.Item> fetchShowsItems() throws IOException {
        return fetchItems("shows", false);
    }

    /** mkoai 2.0.62: Trakt trending movies as home videos. Throws on failure. */
    public static List<Cms.Video> fetchMoviesVideos() throws IOException {
        List<Entry> entries = fetchEntries("movies", true);
        List<Cms.Video> videos = toVideos(entries, "電影");
        enrichPosters(videos, entries, "movies");
        return videos;
    }

    /** mkoai 2.0.62: Trakt trending shows as home videos. Throws on failure. */
    public static List<Cms.Video> fetchShowsVideos() throws IOException {
        List<Entry> entries = fetchEntries("shows", false);
        List<Cms.Video> videos = toVideos(entries, "影劇");
        enrichPosters(videos, entries, "tv");
        return videos;
    }

    /** One Trakt entry: the home item plus the show's TVDB id (0 when absent). */
    private static final class Entry {
        final TmdbClient.Item item;
        final int tvdbId;

        Entry(TmdbClient.Item item, int tvdbId) {
            this.item = item;
            this.tvdbId = tvdbId;
        }
    }

    /** Convert entries to home videos: TMDB shape, Trakt tag, Trakt label. */
    private static List<Cms.Video> toVideos(List<Entry> entries, String kind) {
        List<Cms.Video> out = new ArrayList<>();
        for (Entry e : entries) {
            Cms.Video v = TmdbClient.toVideo(e.item);
            v.tag = "trakt-" + kind;
            // Trakt carries no rating (vote stays 0, so toVideo leaves
            // remarks ""), so label the row instead of showing nothing.
            if (v.remarks == null || v.remarks.isEmpty()) {
                v.remarks = "Trakt 熱門" + kind;
            }
            out.add(v);
        }
        return out;
    }

    /**
     * Best-effort poster fill via Fanart, under a hard 3 s total budget.
     * Movies look up by TMDB id, shows by TVDB id; an entry without an id
     * is skipped without a network call. Runs in parallel (max 4 workers);
     * anything that times out or fails leaves the poster "" — the main
     * list is returned either way, never delayed past the budget.
     */
    private static void enrichPosters(
            List<Cms.Video> videos, List<Entry> entries, String fanartType) {
        List<java.util.concurrent.Callable<String>> jobs = new ArrayList<>();
        for (Entry e : entries) {
            final int artId = "movies".equals(fanartType) ? e.item.id : e.tvdbId;
            if (artId <= 0) {
                jobs.add(() -> null);
                continue;
            }
            final String idStr = String.valueOf(artId);
            jobs.add(
                    () -> {
                        JSONObject data = FanartClient.fetchArt(fanartType, idStr);
                        return data == null ? null : FanartClient.pickPoster(data);
                    });
        }
        List<String> posters;
        try {
            posters = BoundedBatch.run(jobs, jobs.size(), 3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int i = 0; i < videos.size() && i < posters.size(); i++) {
            String url = posters.get(i);
            if (url != null && !url.isEmpty()) videos.get(i).poster = url;
        }
    }

    private static List<TmdbClient.Item> fetchItems(String type, boolean isMovie)
            throws IOException {
        List<Entry> entries = fetchEntries(type, isMovie);
        List<TmdbClient.Item> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add(e.item);
        return out;
    }

    private static List<Entry> fetchEntries(String type, boolean isMovie)
            throws IOException {
        JSONArray data;
        try {
            JSONObject res =
                    WorkerApi.getJson("/api/trakt?type=" + type + "&list=trending");
            data = res.optJSONArray("data");
        } catch (WorkerApi.RateLimited limited) {
            // trending 被上游限流：改打一次 popular 當備援。popular 再失敗
            // 就直接拋出，不要連打（Trakt 會對 Worker 出口 IP 限流）。
            JSONObject res =
                    WorkerApi.getJson("/api/trakt?type=" + type + "&list=popular");
            data = res.optJSONArray("data");
        }
        List<String> titles = new ArrayList<>();
        List<JSONObject> raws = new ArrayList<>(); // entries that passed the title check
        List<Integer> tvdbIds = new ArrayList<>(); // shows only; movies stay 0
        if (data != null) {
            for (int i = 0; i < data.length() && titles.size() < MAX_ITEMS; i++) {
                JSONObject entry = data.optJSONObject(i);
                if (entry == null) continue;
                // trending 有 movie/show 包裝層，popular 是裸物件。
                JSONObject obj = isMovie ? entry.optJSONObject("movie")
                        : entry.optJSONObject("show");
                if (obj == null) obj = entry;
                String title = obj.optString("title", "").trim();
                if (title.isEmpty()) continue;
                titles.add(title);
                raws.add(entry);
                JSONObject ids = obj.optJSONObject("ids");
                tvdbIds.add(ids == null ? 0 : ids.optInt("tvdb", 0));
            }
        }
        // 先收集全部標題再一次翻譯（與 SimklHome 相同用法）。
        List<String> displayTitles = TitleTranslator.toTraditionalAll(titles);
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            out.add(new Entry(parseItem(raws.get(i), isMovie, displayTitles.get(i)),
                    tvdbIds.get(i)));
        }
        return out;
    }

    /**
     * mkoai 2.0.62: one Trakt entry -> home Item. Pure (no network), so the
     * JVM checks can feed it canned trending (movie/show wrapper) and
     * popular (bare object) payloads. The title comes from the caller
     * (already translated); the wrapper unwrap, year and TMDB id come from
     * the entry. TVDB stays with the caller (see fetchEntries).
     */
    static TmdbClient.Item parseItem(JSONObject entry, boolean isMovie, String displayTitle) {
        JSONObject obj = null;
        if (entry != null) {
            obj = isMovie ? entry.optJSONObject("movie") : entry.optJSONObject("show");
            if (obj == null) obj = entry;
        }
        TmdbClient.Item it = new TmdbClient.Item();
        it.type = isMovie ? "movie" : "tv";
        it.title = displayTitle;
        int year = obj == null ? 0 : obj.optInt("year", 0);
        it.year = year > 0 ? String.valueOf(year) : "";
        JSONObject ids = obj == null ? null : obj.optJSONObject("ids");
        it.id = ids == null ? 0 : ids.optInt("tmdb", 0);
        it.poster = ""; // Trakt 沒有海報，留空走現成 fallback 鏈
        it.backdrop = "";
        it.overview = "";
        it.vote = 0; // 不要把 watchers 冒充評分
        it.date = ""; // fail-open，避開 2 天延遲檢查
        it.cast = "";
        return it;
    }
}
