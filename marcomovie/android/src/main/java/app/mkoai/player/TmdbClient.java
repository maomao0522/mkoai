package app.mkoai.player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.38: TMDB metadata + lists, direct from the phone (no server).
 *
 * <p>Uses the movmao TMDB API key (same key as movmao / kanpian). All
 * requests use language=zh-TW so TMDB returns Traditional Chinese titles
 * and Chinese posters; searches fall back to zh-CN when zh-TW finds
 * nothing (some very new Chinese titles are only indexed under zh-CN so
 * far). Posters are chosen from /images with a preference for
 * iso_639_1 == "zh", falling back to the first poster.
 *
 * <p>Rate limit: TMDB allows 40 requests / 10s. Every call funnels
 * through get(), which enforces a 300ms gap between requests. Results
 * are cached on disk (tmdb-cache.json, modeled on AwwratedHome's
 * availability cache): single-title lookups 7 days, lists 6 hours.
 * A cached "miss" is stored too, so a title TMDB does not carry is not
 * re-searched on every visit.
 *
 * <p>Matching reuses the app's own title logic: script variants +
 * the 78-pair alias table (SearchVariants), then a script-insensitive
 * normTitle comparison. A lookup that finds nothing returns null and
 * the caller keeps the source's own poster/text (never blank).
 */
final class TmdbClient {
    // mkoai 2.0.38: TMDB API key (same key as movmao / kanpian).
    // Injected at build time from the movmao .env — never log it.
    private static final String API_KEY = "f51c2b4a37c52f402e4de98a67caec57";

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    private static final String POSTER_SIZE = "w780";
    // 2.0.40: backdrops are w1280 (was w780) — the detail page shows them
    // full-bleed and w780 upscaled on a phone looks soft.
    // 2.0.46: cards now use w780 (was w500, POSTER_SIZE) — TMDB's
    // largest standard poster size, ~1080p-equivalent for a portrait
    // card. ImageLoader upgrades older w500 URLs in place.
    private static final String BACKDROP_SIZE = "w1280";

    private static final String CACHE_FILE = "tmdb-cache.json";
    private static final long ITEM_TTL_MS = 7L * 86_400_000L; // 7 days
    private static final long LIST_TTL_MS = 6L * 3_600_000L; // 6 hours

    // TMDB: 40 requests / 10s. 300ms gap keeps us well under it even
    // when several rows refresh at once.
    private static final long MIN_GAP_MS = 300L;
    private static final Object LOCK = new Object();
    private static long lastCallMs = 0;

    /** One TMDB title (movie or tv). */
    static final class Item {
        int id;
        String type; // "movie" | "tv"
        String title;
        String date; // yyyy-MM-dd, may be ""
        String year;
        String poster; // full https URL, may be ""
        String backdrop; // full https URL, may be ""
        String overview;
        double vote;
        String cast; // "A、B、C", may be ""
        public String airInfo = ""; // mkoai 2.0.62: TVmaze 播出資訊（"今晚 21:00 · S1E2"），首頁轉換後抄到 remarks 用

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("mt", type == null ? "" : type);
                o.put("t", title == null ? "" : title);
                o.put("d", date == null ? "" : date);
                o.put("y", year == null ? "" : year);
                o.put("p", poster == null ? "" : poster);
                o.put("b", backdrop == null ? "" : backdrop);
                o.put("o", overview == null ? "" : overview);
                o.put("v", vote);
                o.put("c", cast == null ? "" : cast);
            } catch (Throwable ignored) {
            }
            return o;
        }

        static Item fromJson(JSONObject o) {
            if (o == null) return null;
            Item it = new Item();
            it.id = o.optInt("id", 0);
            it.type = o.optString("mt", "");
            it.title = o.optString("t", "");
            it.date = o.optString("d", "");
            it.year = o.optString("y", "");
            it.poster = o.optString("p", "");
            it.backdrop = o.optString("b", "");
            it.overview = o.optString("o", "");
            it.vote = o.optDouble("v", 0);
            it.cast = o.optString("c", "");
            return it;
        }
    }

    // ---- throttled HTTP ----

    /** One throttled GET. Throws on failure. Never logs the API key. */
    private static String get(String url) throws Exception {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long wait = MIN_GAP_MS - (now - lastCallMs);
            if (wait > 0) {
                try {
                    LOCK.wait(wait);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("metadata cancelled");
                }
            }
            lastCallMs = System.currentTimeMillis();
        }
        return Cms.fetch(url, new HashMap<String, String>());
    }

    private static JSONObject getJson(String url) throws Exception {
        return new JSONObject(get(url));
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception ignored) {
            return s == null ? "" : s;
        }
    }

    static String posterUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http")) return path;
        return IMG + POSTER_SIZE + path;
    }

    static String backdropUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http")) return path;
        return IMG + BACKDROP_SIZE + path;
    }

    // ---- disk cache (modeled on AwwratedHome's availability cache) ----

    private static synchronized JSONObject loadCache(File filesDir) {
        JSONObject out = new JSONObject();
        if (filesDir == null) return out;
        try {
            File f = new File(filesDir, CACHE_FILE);
            if (!f.exists() || f.length() <= 0 || f.length() > 8L*1024*1024) return out;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return new JSONObject(new String(buf, "UTF-8"));
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static synchronized void saveCache(File filesDir, JSONObject cache) {
        if (filesDir == null || cache == null) return;
        try {
            JSONObject merged = MetadataCachePolicy.merge(loadCache(filesDir), cache);
            File target = new File(filesDir, CACHE_FILE);
            File temp = new File(filesDir, CACHE_FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(merged.toString().getBytes("UTF-8"));
            }
            if (!temp.renameTo(target)) temp.delete();
        } catch (Throwable ignored) {
        }
    }

    private static String itemKey(String title, String year) {
        String t = title == null ? "" : title.trim();
        t = t.replaceAll("\\s*[\\(\\（]共\\d+季[\\)\\）]\\s*$", "").trim();
        return Cms.normTitle(ChineseConvert.t2s(t))
                + "|"
                + (year == null ? "" : year.trim());
    }

    private static String listKey(String kind, String extra) {
        return kind + "|" + (extra == null ? "" : extra);
    }

    // ---- title matching (same normalization as the app's search) ----

    private static String matchKey(String s) {
        if (s == null) return "";
        return Cms.normTitle(ChineseConvert.t2s(s.trim()));
    }

    /** 0-100: exact 100, prefix 80, contains 60, else 0. */
    private static int titleScore(String queryTitle, String resultTitle) {
        String q = matchKey(queryTitle);
        String r = matchKey(resultTitle);
        if (q.isEmpty() || r.isEmpty()) return 0;
        if (r.equals(q)) return 100;
        if (r.startsWith(q) || q.startsWith(r)) return 80;
        if (r.contains(q) || q.contains(r)) return 60;
        return 0;
    }

    /** Query variants: aliases + both scripts, capped so one lookup stays cheap. */
    private static List<String> queryVariants(String title) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (title == null) return new ArrayList<>(out);
        String base = title.trim().replaceAll("\\s*[\\(\\（]共\\d+季[\\)\\）]\\s*$", "").trim();
        if (base.isEmpty()) return new ArrayList<>(out);
        for (String a : SearchVariants.aliasVariants(base)) {
            for (String v : SearchVariants.scriptVariants(a)) {
                out.add(v);
                if (out.size() >= 4) return new ArrayList<>(out);
            }
        }
        return new ArrayList<>(out);
    }

    // ---- single-title lookup ----

    /**
     * Find one title on TMDB (movie or tv). Language zh-TW first, then
     * zh-CN; query variants from the alias table + both scripts. Returns
     * null when nothing matches well enough — the caller keeps the
     * source's own poster/text. Never throws.
     */
    static Item search(File filesDir, String title, String year) {
        if (title == null || title.trim().isEmpty()) return null;
        String key = itemKey(title, year);
        long now = System.currentTimeMillis();
        JSONObject cache = loadCache(filesDir);
        JSONObject items = cache.optJSONObject("items");
        if (items != null) {
            JSONObject hit = items.optJSONObject(key);
            if (hit != null && now - hit.optLong("ts", 0) <= ITEM_TTL_MS) {
                if (hit.optBoolean("miss", false)) return null;
                return Item.fromJson(hit.optJSONObject("it"));
            }
        }
        Item best = null;
        int bestScore = 0;
        try {
            List<String> variants = queryVariants(title);
            String[] langs = {"zh-TW", "zh-CN"};
            String[] types = {"movie", "tv"};
            outer:
            for (String lang : langs) {
                for (String variant : variants) {
                    for (String type : types) {
                        String url =
                                API
                                        + "/search/"
                                        + type
                                        + "?api_key="
                                        + API_KEY
                                        + "&language="
                                        + lang
                                        + "&query="
                                        + enc(variant)
                                        + "&page=1&include_adult=false";
                        JSONObject res = getJson(url);
                        JSONArray arr = res.optJSONArray("results");
                        if (arr == null) continue;
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject r = arr.optJSONObject(i);
                            if (r == null) continue;
                            String rt =
                                    "movie".equals(type)
                                            ? r.optString("title", "")
                                            : r.optString("name", "");
                            String rd =
                                    "movie".equals(type)
                                            ? r.optString("release_date", "")
                                            : r.optString("first_air_date", "");
                            int score = titleScore(title, rt);
                            if (score <= 0) continue;
                            if (year != null && !year.trim().isEmpty() && rd.length() >= 4) {
                                String ry = rd.substring(0, 4);
                                if (ry.equals(year.trim())) score += 20;
                                else {
                                    try {
                                        int diff =
                                                Math.abs(
                                                        Integer.parseInt(ry)
                                                                - Integer.parseInt(
                                                                        year.trim()));
                                        if (diff > 1) score -= 30;
                                        else score += 10;
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                            if (score > bestScore) {
                                bestScore = score;
                                best = itemFromSearch(r, type);
                            }
                        }
                        if (bestScore >= 100) break outer;
                    }
                }
                if (bestScore >= 80) break;
            }
        } catch (Throwable ignored) {
            best = null;
        }
        // Cache the outcome (hit or miss) so repeat visits are free.
        try {
            if (items == null) {
                items = new JSONObject();
                cache.put("items", items);
            }
            JSONObject hit = new JSONObject();
            hit.put("ts", now);
            if (best != null && bestScore >= 80) {
                hit.put("it", best.toJson());
            } else {
                hit.put("miss", true);
                best = null;
            }
            items.put(key, hit);
            saveCache(filesDir, cache);
        } catch (Throwable ignored) {
        }
        return best;
    }

    /**
     * 2.0.58: translate an English title to its zh-TW name via TMDB (used
     * by TitleTranslator when awwrated has no Taiwan title). Searches with
     * language=zh-TW and accepts a result only when its original_title /
     * original_name matches the query, so a wrong work is never
     * substituted. Returns "" when nothing matches. Never throws.
     */
    static String translateTitle(String englishTitle) {
        if (englishTitle == null || englishTitle.trim().isEmpty()) return "";
        String q = englishTitle.trim();
        for (String type : new String[] {"tv", "movie"}) {
            try {
                String url =
                        API
                                + "/search/"
                                + type
                                + "?api_key="
                                + API_KEY
                                + "&language=zh-TW&query="
                                + enc(q)
                                + "&page=1&include_adult=false";
                String zh = pickTranslatedTitle(get(url), q, type);
                if (zh != null && !zh.isEmpty()) return zh;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /**
     * 2.0.58: pick the zh-TW title from a TMDB /search response (testable
     * without network). Accepts a result only when its original title
     * matches the query (titleScore >= 80); returns "" when the zh-TW
     * title is missing, identical to the query (TMDB has no translation,
     * it returns the original as-is), or there is no match. Never throws.
     */
    static String pickTranslatedTitle(String raw, String query, String type) {
        try {
            JSONObject res = new JSONObject(raw);
            JSONArray arr = res.optJSONArray("results");
            if (arr == null) return "";
            boolean movie = "movie".equals(type);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                String original =
                        movie
                                ? r.optString("original_title", "")
                                : r.optString("original_name", "");
                if (titleScore(query, original) < 80) continue;
                String zh = movie ? r.optString("title", "") : r.optString("name", "");
                zh = zh == null ? "" : zh.trim();
                if (zh.isEmpty()) continue;
                if (matchKey(zh).equals(matchKey(query))) continue;
                return zh;
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Base item from a /search result (poster already language-biased). */
    private static Item itemFromSearch(JSONObject r, String type) {
        Item it = new Item();
        it.id = r.optInt("id", 0);
        it.type = type;
        it.title =
                "movie".equals(type) ? r.optString("title", "") : r.optString("name", "");
        it.date =
                "movie".equals(type)
                        ? r.optString("release_date", "")
                        : r.optString("first_air_date", "");
        it.year = it.date.length() >= 4 ? it.date.substring(0, 4) : "";
        it.poster = posterUrl(r.optString("poster_path", ""));
        it.backdrop = backdropUrl(r.optString("backdrop_path", ""));
        it.overview = r.optString("overview", "");
        it.vote = r.optDouble("vote_average", 0);
        it.cast = "";
        return it;
    }

    /**
     * Enrich one item with its best Chinese poster + backdrop + top cast.
     * Posters come from /images with include_image_language=zh-TW,en,null
     * and prefer iso_639_1 == "zh"; backdrops take the highest-voted one.
     * Returns the same item (mutated); on failure the item is unchanged.
     * Never throws.
     */
    static Item enrich(File filesDir, Item it) {
        if (it == null || it.id <= 0) return it;
        try {
            String base = API + "/" + it.type + "/" + it.id;
            JSONObject imgs =
                    getJson(
                            base
                                    + "/images?api_key="
                                    + API_KEY
                                    + "&language=zh-TW&include_image_language=zh-TW,en,null");
            JSONArray posters = imgs.optJSONArray("posters");
            String bestPoster = pickPoster(posters, it.poster);
            if (bestPoster != null && !bestPoster.isEmpty()) it.poster = bestPoster;
            JSONArray backdrops = imgs.optJSONArray("backdrops");
            String bestBackdrop = pickBackdrop(backdrops, it.backdrop);
            if (bestBackdrop != null && !bestBackdrop.isEmpty()) it.backdrop = bestBackdrop;
            JSONObject credits =
                    getJson(base + "/credits?api_key=" + API_KEY + "&language=zh-TW");
            JSONArray castArr = credits.optJSONArray("cast");
            if (castArr != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < castArr.length() && i < 5; i++) {
                    JSONObject c = castArr.optJSONObject(i);
                    if (c == null) continue;
                    String name = c.optString("name", "").trim();
                    if (name.isEmpty()) continue;
                    if (sb.length() > 0) sb.append("、");
                    sb.append(name);
                }
                if (sb.length() > 0) it.cast = sb.toString();
            }
            // Refresh the cached entry with the enriched item.
            JSONObject cache = loadCache(filesDir);
            JSONObject items = cache.optJSONObject("items");
            if (items != null) {
                String key = itemKey(it.title, it.year);
                JSONObject hit = items.optJSONObject(key);
                if (hit != null && !hit.optBoolean("miss", false)) {
                    hit.put("it", it.toJson());
                    hit.put("ts", System.currentTimeMillis());
                    saveCache(filesDir, cache);
                }
            }
        } catch (Throwable ignored) {
        }
        return it;
    }

    /** Prefer a zh poster (highest voted); else the first poster; else keep current. */
    private static String pickPoster(JSONArray posters, String current) {
        if (posters == null || posters.length() == 0) return current;
        JSONObject bestZh = null;
        double bestZhVote = -1;
        for (int i = 0; i < posters.length(); i++) {
            JSONObject p = posters.optJSONObject(i);
            if (p == null) continue;
            if (!"zh".equals(p.optString("iso_639_1", ""))) continue;
            double v = p.optDouble("vote_average", 0);
            if (bestZh == null || v > bestZhVote) {
                bestZh = p;
                bestZhVote = v;
            }
        }
        if (bestZh != null) return posterUrl(bestZh.optString("file_path", ""));
        JSONObject first = posters.optJSONObject(0);
        if (first != null) {
            String path = first.optString("file_path", "");
            if (!path.isEmpty()) return posterUrl(path);
        }
        return current;
    }

    /** Highest-voted backdrop; else the first; else keep current. */
    private static String pickBackdrop(JSONArray backdrops, String current) {
        if (backdrops == null || backdrops.length() == 0) return current;
        JSONObject best = null;
        double bestVote = -1;
        for (int i = 0; i < backdrops.length(); i++) {
            JSONObject b = backdrops.optJSONObject(i);
            if (b == null) continue;
            double v = b.optDouble("vote_average", 0);
            if (best == null || v > bestVote) {
                best = b;
                bestVote = v;
            }
        }
        if (best != null) {
            String path = best.optString("file_path", "");
            if (!path.isEmpty()) return backdropUrl(path);
        }
        return current;
    }

    // ---- lists (trending / now playing / top rated / discover) ----

    /**
     * One named list. kinds: trending_movie, trending_tv, now_playing,
     * airing_today, top_rated (movie+tv merged, vote desc), latest
     * (now_playing + airing_today merged, date desc), documentary
     * (movie+tv genre 99 merged), discover_movie, discover_tv.
     * `extra` is the discover query string (e.g. "with_genres=16").
     * Disk cache (6h) first; network when stale. Never throws.
     */
    static List<Item> list(File filesDir, String kind, String extra) {
        List<Item> cached = cachedList(filesDir, kind, extra);
        if (cached != null) return cached;
        List<Item> fresh = fetchList(kind, extra);
        if (fresh == null) return new ArrayList<>();
        try {
            JSONObject cache = loadCache(filesDir);
            JSONObject lists = cache.optJSONObject("lists");
            if (lists == null) {
                lists = new JSONObject();
                cache.put("lists", lists);
            }
            JSONObject entry = new JSONObject();
            entry.put("ts", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            for (Item it : fresh) arr.put(it.toJson());
            entry.put("items", arr);
            lists.put(listKey(kind, extra), entry);
            saveCache(filesDir, cache);
        } catch (Throwable ignored) {
        }
        return fresh;
    }

    /** Disk-cached list only (no network): null when absent or stale. */
    static List<Item> cachedList(File filesDir, String kind, String extra) {
        try {
            JSONObject cache = loadCache(filesDir);
            JSONObject lists = cache.optJSONObject("lists");
            if (lists == null) return null;
            JSONObject entry = lists.optJSONObject(listKey(kind, extra));
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.optLong("ts", 0) > LIST_TTL_MS) return null;
            JSONArray arr = entry.optJSONArray("items");
            if (arr == null) return null;
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Item it = Item.fromJson(arr.optJSONObject(i));
                if (it != null && it.title != null && !it.title.isEmpty()) out.add(it);
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Network fetch for one list kind. Returns null on failure. */
    private static List<Item> fetchList(String kind, String extra) {
        try {
            if ("latest".equals(kind)) {
                List<Item> out = new ArrayList<>();
                out.addAll(fetchPage(API + "/movie/now_playing?language=zh-TW&region=TW", "movie"));
                out.addAll(
                        fetchPage(
                                API
                                        + "/tv/airing_today?language=zh-TW&timezone=Asia/Taipei",
                                "tv"));
                out.sort(
                        (a, b) -> {
                            String da = a.date == null ? "" : a.date;
                            String db = b.date == null ? "" : b.date;
                            return db.compareTo(da);
                        });
                return out;
            }
            if ("top_rated".equals(kind)) {
                List<Item> out = new ArrayList<>();
                out.addAll(fetchPage(API + "/movie/top_rated?language=zh-TW", "movie"));
                out.addAll(fetchPage(API + "/tv/top_rated?language=zh-TW", "tv"));
                out.sort((a, b) -> Double.compare(b.vote, a.vote));
                return out;
            }
            if ("documentary".equals(kind)) {
                List<Item> out = new ArrayList<>();
                out.addAll(
                        fetchPage(
                                API
                                        + "/discover/movie?language=zh-TW&with_genres=99"
                                        + "&sort_by=popularity.desc",
                                "movie"));
                out.addAll(
                        fetchPage(
                                API
                                        + "/discover/tv?language=zh-TW&with_genres=99"
                                        + "&sort_by=popularity.desc",
                                "tv"));
                return out;
            }
            if ("trending_movie".equals(kind)) {
                return fetchPage(API + "/trending/movie/week?language=zh-TW", "movie");
            }
            if ("trending_tv".equals(kind)) {
                return fetchPage(API + "/trending/tv/week?language=zh-TW", "tv");
            }
            if ("now_playing".equals(kind)) {
                return fetchPage(API + "/movie/now_playing?language=zh-TW&region=TW", "movie");
            }
            if ("airing_today".equals(kind)) {
                return fetchPage(
                        API + "/tv/airing_today?language=zh-TW&timezone=Asia/Taipei", "tv");
            }
            if ("discover_movie".equals(kind)) {
                String q = extra == null ? "" : extra;
                return fetchPage(
                        API
                                + "/discover/movie?language=zh-TW&sort_by=popularity.desc"
                                + (q.isEmpty() ? "" : "&" + q),
                        "movie");
            }
            if ("discover_tv".equals(kind)) {
                String q = extra == null ? "" : extra;
                return fetchPage(
                        API
                                + "/discover/tv?language=zh-TW&sort_by=popularity.desc"
                                + (q.isEmpty() ? "" : "&" + q),
                        "tv");
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** One list page (20 items). Appends api_key + page=1. */
    private static List<Item> fetchPage(String url, String type) throws Exception {
        String full = url + (url.contains("?") ? "&" : "?") + "api_key=" + API_KEY + "&page=1";
        JSONObject res = getJson(full);
        JSONArray arr = res.optJSONArray("results");
        List<Item> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            Item it = itemFromSearch(r, type);
            if (it.title != null && !it.title.isEmpty()) out.add(it);
        }
        return out;
    }

    /** A TMDB item as a home/detail card: tap runs the normal source search. */
    static Cms.Video toVideo(Item it) {
        Cms.Video v = new Cms.Video();
        v.id = "msearch:" + (it.title == null ? "" : it.title);
        v.tmdbId = it.id; // mkoai 2.0.62: TMDB id 供 OMDb / Fanart 查詢用（0 = 未知）
        v.title = it.title == null ? "" : it.title;
        v.poster = it.poster == null ? "" : it.poster;
        v.backdrop = it.backdrop == null ? "" : it.backdrop;
        v.remarks = it.vote > 0 ? ("★ " + trimVote(it.vote)) : "";
        v.year = it.year == null ? "" : it.year;
        v.tag = "tmdb";
        v.description = it.overview == null ? "" : it.overview;
        v.mediaType = it.type == null ? "" : it.type; // mkoai 2.0.61: "movie" | "tv"
        v.area = "";
        v.genre = "";
        return v;
    }

    private static String trimVote(double v) {
        String s = Double.toString(Math.round(v * 10) / 10.0);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * mkoai 2.0.62: TMDB external ids — a title's IMDb id (movie or tv).
     * GET {API}/{type}/{tmdbId}/external_ids. Returns the imdb_id, or
     * null on any failure. Never throws. filesDir is reserved for a
     * future cache and currently unused.
     */
    public static String externalImdbId(java.io.File filesDir, String type, int tmdbId) {
        if (tmdbId <= 0 || type == null || type.isEmpty()) return null;
        try {
            JSONObject res =
                    getJson(API + "/" + type + "/" + tmdbId + "/external_ids?api_key=" + API_KEY);
            String id = res.optString("imdb_id", "").trim();
            return id.isEmpty() ? null : id;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * mkoai 2.0.62: TMDB external ids — a show's TVDB id (type is always
     * "tv"). Returns the tvdb_id, or null when absent / on any failure.
     * Never throws. filesDir is reserved for a future cache and currently
     * unused.
     */
    public static Integer externalTvdbId(java.io.File filesDir, int tmdbId) {
        if (tmdbId <= 0) return null;
        try {
            JSONObject res =
                    getJson(API + "/tv/" + tmdbId + "/external_ids?api_key=" + API_KEY);
            int id = res.optInt("tvdb_id", 0);
            return id <= 0 ? null : Integer.valueOf(id);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
