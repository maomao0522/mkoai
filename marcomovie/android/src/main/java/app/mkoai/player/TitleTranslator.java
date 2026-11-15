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
            CACHE.put(foreignTitle, out);
        }
        return out;
    }

    /** True when the title contains a Latin letter (an English/romanized title worth a TMDB lookup). */
    private static boolean looksForeign(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }

    /** Translate a list of titles in parallel, preserving order. */
    static List<String> toTraditionalAll(List<String> titles) {
        List<String> out = new ArrayList<>();
        if (titles == null || titles.isEmpty()) return out;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, titles.size()));
        List<Future<String>> futures = new ArrayList<>();
        for (final String t : titles) {
            futures.add(
                    pool.submit(
                            new Callable<String>() {
                                @Override
                                public String call() {
                                    return toTraditional(t);
                                }
                            }));
        }
        pool.shutdown();
        for (Future<String> f : futures) {
            try {
                out.add(f.get(6, TimeUnit.SECONDS));
            } catch (Throwable ignored) {
                out.add(null);
            }
        }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) == null) out.set(i, titles.get(i));
        }
        return out;
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
