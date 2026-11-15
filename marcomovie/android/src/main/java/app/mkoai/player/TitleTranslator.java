package app.mkoai.player;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai foreign-title translator. Foreign (e.g. English) titles from sources
 * like Simkl are shown in Traditional Chinese by looking up the title's real
 * published Chinese name on Douban — never by machine-converting the English
 * text (which produces garbage). Douban's suggest API returns the Chinese
 * title (Simplified) plus the English sub-title; a result is accepted only
 * when its English sub-title matches the queried title, so a wrong title is
 * never shown. The Simplified Chinese title is then converted to Traditional
 * with the (fixed, code-point-safe) ChineseConvert.s2t.
 *
 * <p>Translations run in parallel (one short request per title) and are
 * cached in memory for the session, so repeat visits are instant. Any
 * failure (network, no match, timeout) leaves the original title untouched.
 */
final class TitleTranslator {
    private static final String SUGGEST_URL =
            "https://movie.douban.com/j/subject_suggest?q=";
    // mkoai 2.0.58: the same suggest lookup through the Cloudflare Worker
    // (much more reliable from Taiwan). The route may not be live yet — a
    // 404/502 from it is just a fallback trigger, never fatal.
    private static final String WORKER_SUGGEST_URL =
            "https://maoki-mkoai-api.maotai0522.workers.dev/api/douban/suggest?q=";
    private static final Map<String, String> CACHE = new HashMap<>();

    /** Translate one foreign title to Traditional Chinese; original on failure. */
    static String toTraditional(String foreignTitle) {
        if (foreignTitle == null || foreignTitle.isEmpty()) return foreignTitle;
        // mkoai 2.0.63: a title with no Latin letters is already Chinese
        // (or numeric) — nothing to translate, and no network call is
        // ever spent on it. This fast path is what makes the
        // translate-every-home-row pass cheap: TMDB rows are mostly
        // Chinese already, so only the foreign titles reach the network.
        if (!looksForeign(foreignTitle)) return foreignTitle;
        synchronized (CACHE) {
            String hit = CACHE.get(foreignTitle);
            if (hit != null) return hit;
        }
        // mkoai: awwrated first — it carries the correct Taiwan Traditional
        // title directly (no conversion). 2.0.58: TMDB zh-TW is next
        // (reliable from Taiwan, and its original-title match keeps a
        // wrong work from being substituted); Douban suggest is last
        // (flaky from Taiwan — now via the Worker first, direct as
        // fallback). A translated title is also what the card's msearch
        // id uses, so this fixes both the display AND the search.
        String zh = AwwratedTitles.lookup(foreignTitle);
        String out;
        if (zh != null) {
            out = zh;
        } else {
            String tmdb = null;
            if (looksForeign(foreignTitle)) {
                try {
                    tmdb = TmdbClient.translateTitle(foreignTitle);
                } catch (Throwable ignored) {
                }
            }
            if (tmdb != null && !tmdb.isEmpty()) {
                out = stripSeason(tmdb);
            } else {
                String douban = lookupDouban(foreignTitle);
                out = douban == null ? foreignTitle : ChineseConvert.s2t(stripSeason(douban));
            }
        }
        synchronized (CACHE) {
            // mkoai 2.0.63: only a REAL translation is cached. Caching
            // the original on failure would poison the session: one slow
            // or rate-limited lookup (TMDB 429 during the home's parallel
            // fetch storm) would keep the card English until the app is
            // restarted, even after the network recovers. A failure stays
            // uncached, so the next home load retries it.
            if (!out.equals(foreignTitle)) CACHE.put(foreignTitle, out);
        }
        return out;
    }

    /** True when the title contains a Latin letter (an English/romanized title worth a TMDB lookup). */
    static boolean looksForeign(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }

    /** Translate a list of titles in parallel, preserving order. */
    static List<String> toTraditionalAll(List<String> titles) {
        return toTraditionalAll(titles, 8000L);
    }

    /**
     * mkoai 2.0.63: batch translation under ONE global budget (was: a
     * sequential 6s get per title, so ten slow titles could stall a
     * batch for up to 60s — far past the home's 12s fetch budget).
     * Titles that do not finish inside the budget keep the original
     * (fail-open, uncached, retried on the next home load). Never throws.
     */
    static List<String> toTraditionalAll(List<String> titles, long budgetMs) {
        List<String> out = new ArrayList<>();
        if (titles == null || titles.isEmpty()) return out;
        // Daemon threads (like BoundedBatch): a stalled translation must
        // never hold the process open after the caller has moved on.
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        Math.min(4, titles.size()),
                        runnable -> {
                            Thread thread = new Thread(runnable, "mkoai-translate");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (final String t : titles) {
                tasks.add(
                        new Callable<String>() {
                            @Override
                            public String call() {
                                return toTraditional(t);
                            }
                        });
            }
            List<Future<String>> futures;
            try {
                futures = pool.invokeAll(tasks, budgetMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                for (String t : titles) out.add(t);
                return out;
            }
            for (int i = 0; i < futures.size(); i++) {
                String r = null;
                try {
                    Future<String> f = futures.get(i);
                    if (!f.isCancelled()) r = f.get();
                } catch (Throwable ignored) {
                }
                out.add(r == null ? titles.get(i) : r);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * mkoai 2.0.63: translate home-row items in place (background thread
     * only — never the UI thread). Only titles with Latin letters reach
     * the network (see the fast path in {@link #toTraditional}); a title
     * that cannot be translated keeps its original (fail-open). The
     * caller builds the video (and its msearch id) from the item AFTER
     * this call, so display, search key and availability matching all
     * use the Chinese title. Never throws.
     */
    static void translateItems(List<TmdbClient.Item> items, long budgetMs) {
        if (items == null || items.isEmpty()) return;
        List<TmdbClient.Item> targets = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (TmdbClient.Item it : items) {
            if (it == null || it.title == null || it.title.isEmpty()) continue;
            if (!looksForeign(it.title)) continue;
            targets.add(it);
            titles.add(it.title);
        }
        if (targets.isEmpty()) return;
        try {
            List<String> translated = toTraditionalAll(titles, budgetMs);
            for (int i = 0; i < targets.size(); i++) {
                String zh = translated.get(i);
                if (zh != null && !zh.isEmpty() && !zh.equals(targets.get(i).title)) {
                    targets.get(i).title = zh;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * mkoai 2.0.63: the video-level twin of {@link #translateItems} —
     * for row builders that already hold {@link Cms.Video}s (e.g. a TV
     * chart section). A translated title also rewrites an
     * {@code msearch:} id, so tapping the card searches the Chinese
     * title; any other id (a source detail id) is left untouched. Never
     * throws.
     */
    static void translateVideos(List<Cms.Video> videos, long budgetMs) {
        if (videos == null || videos.isEmpty()) return;
        List<Cms.Video> targets = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (Cms.Video v : videos) {
            if (v == null || v.title == null || v.title.isEmpty()) continue;
            if (!looksForeign(v.title)) continue;
            targets.add(v);
            titles.add(v.title);
        }
        if (targets.isEmpty()) return;
        try {
            List<String> translated = toTraditionalAll(titles, budgetMs);
            for (int i = 0; i < targets.size(); i++) {
                String zh = translated.get(i);
                Cms.Video v = targets.get(i);
                if (zh != null && !zh.isEmpty() && !zh.equals(v.title)) {
                    v.id = rewriteSearchId(v.id, zh);
                    v.title = zh;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * mkoai 2.0.63: rewrite an {@code msearch:} id to a translated
     * title; every other id passes through unchanged (a source detail
     * id must never be rewritten — it is not a search key). Pure, so the
     * JVM checks cover it directly.
     */
    static String rewriteSearchId(String id, String newTitle) {
        if (id != null
                && id.startsWith("msearch:")
                && newTitle != null
                && !newTitle.isEmpty()) {
            return "msearch:" + newTitle;
        }
        return id;
    }

    private static Map<String, String> suggestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; POCO F5) AppleWebKit/537.36"
                        + " (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        headers.put("Referer", "https://movie.douban.com/");
        return headers;
    }

    /**
     * 2.0.58: Douban suggest lookup — Worker first, direct
     * movie.douban.com as fallback. A Worker 404/502 (route not live
     * yet) or a bad payload just moves on to direct; both failing
     * returns null. Returns the Chinese title (Simplified) or null.
     */
    private static String lookupDouban(String foreignTitle) {
        try {
            String raw =
                    Cms.fetch(
                            WORKER_SUGGEST_URL + URLEncoder.encode(foreignTitle, "UTF-8"),
                            suggestHeaders());
            String title = parseSuggest(raw, foreignTitle);
            if (title != null) return title;
        } catch (Throwable ignored) {
            // Worker route not live yet or Worker/Douban failed — direct.
        }
        try {
            String raw =
                    Cms.fetch(
                            SUGGEST_URL + URLEncoder.encode(foreignTitle, "UTF-8"),
                            suggestHeaders());
            return parseSuggest(raw, foreignTitle);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Parse a Douban subject_suggest response. Returns the Chinese title
     * (Simplified) or null — bad payload (e.g. a Worker 404 page), no
     * results, or a sub-title that does not match the query. Never throws.
     */
    static String parseSuggest(String raw, String foreignTitle) {
        try {
            JSONArray data = new JSONArray(raw);
            if (data.length() == 0) return null;
            JSONObject first = data.optJSONObject(0);
            if (first == null) return null;
            String sub = first.optString("sub_title", "").trim();
            String title = first.optString("title", "").trim();
            if (title.isEmpty()) return null;
            // Accept only when Douban's English sub-title matches the query,
            // so a similarly-named but different work is never substituted.
            if (!sub.isEmpty() && !matches(sub, foreignTitle)) return null;
            return title;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Loose English-title match (case-insensitive, ignores punctuation/space). */
    private static boolean matches(String sub, String query) {
        String a = sub.toLowerCase().replaceAll("[^a-z0-9]", "");
        String b = query.toLowerCase().replaceAll("[^a-z0-9]", "");
        return !a.isEmpty() && (a.equals(b) || a.startsWith(b) || b.startsWith(a));
    }

    /** Strip a trailing season marker like " 第一季" / " Season 2" from a Douban title. */
    private static String stripSeason(String title) {
        String t = title.replaceAll("\\s+第[一二三四五六七八九十\\d]+季\\s*$", "").trim();
        t = t.replaceAll("\\s+Season\\s+\\d+\\s*$", "").trim();
        return t.isEmpty() ? title : t;
    }
}
