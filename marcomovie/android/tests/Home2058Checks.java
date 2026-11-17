package app.mkoai.player;

import java.util.ArrayList;
import java.util.List;

/**
 * mkoai 2.0.58 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2058_checks.py.
 *
 * Covers:
 *  1. Douban 新片 (DoubanHome.fetchNew/parseNew/isUsableNewPayload):
 *     sort=T payload parsing, cap 10, remarks/tag, Worker-404 rejection
 *     (HTML + {"error":...} are NOT usable payloads, so the caller falls
 *     back to direct Douban).
 *  2. TitleTranslator.parseSuggest: valid match, non-matching sub-title,
 *     Worker-404 bodies (HTML + error JSON + empty array) -> null.
 *  3. TmdbClient.pickTranslatedTitle: TV + movie samples, original-title
 *     matching, no-translation (zh == original) and bad JSON -> "".
 *     (Full toTraditional is NOT tested here — it needs network for the
 *     TMDB/Douban lookups, and neither is reachable from the VM.)
 *  4. HomeScreen.composeHomeVideos: Douban-new-first + source, Simkl
 *     only when the source is empty, dedupe, null-tolerant.
 *  5. UpdatePolicy.apkUrl: the Drive /uc form AND the direct
 *     drive.usercontent.google.com/download?confirm=t form are accepted;
 *     non-Drive hosts, missing confirm/export, HTTP, fragments, extra
 *     params and bad ids are rejected; validate() accepts the new form.
 */
public class Home2058Checks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(Object got, Object want, String name) {
        if (got == null ? want != null : !got.equals(want)) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static Cms.Video v(String id, String title) {
        Cms.Video x = new Cms.Video();
        x.id = id;
        x.title = title;
        return x;
    }

    static List<String> titles(List<Cms.Video> vs) {
        List<String> out = new ArrayList<>();
        for (Cms.Video x : vs) out.add(x.title);
        return out;
    }

    static List<String> ids(List<Cms.Video> vs) {
        List<String> out = new ArrayList<>();
        for (Cms.Video x : vs) out.add(x.id);
        return out;
    }

    // ---- 1. Douban 新片 parsing ----
    static final String NEW_PAYLOAD =
            "{\"data\":["
                    + "{\"title\":\"流浪地球3\",\"rate\":\"8.9\","
                    + "\"cover\":\"https://img1.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg\","
                    + "\"id\":\"1\",\"url\":\"https://movie.douban.com/subject/1/\"},"
                    + "{\"title\":\"無評分新片\",\"rate\":\"\","
                    + "\"cover\":\"https://img1.doubanio.com/view/photo/s_ratio_poster/public/p2.jpg\","
                    + "\"id\":\"2\",\"url\":\"https://movie.douban.com/subject/2/\"},"
                    + "{\"title\":\"\",\"rate\":\"7.0\",\"cover\":\"https://x.jpg\","
                    + "\"id\":\"3\",\"url\":\"https://x\"},"
                    + "{\"title\":\"無海報\",\"rate\":\"7.5\",\"cover\":\"\","
                    + "\"id\":\"4\",\"url\":\"https://x\"}"
                    + "]}";

    static void doubanNewChecks() throws Exception {
        List<Cms.Video> out = DoubanHome.parseNew(NEW_PAYLOAD);
        eq(out.size(), 3, "parseNew skips the empty-title entry");
        eq(out.get(0).id, "msearch:流浪地球3", "parseNew id is msearch:<title>");
        eq(out.get(0).title, "流浪地球3", "parseNew title");
        eq(
                out.get(0).poster,
                "https://img1.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg",
                "parseNew poster from cover");
        eq(out.get(0).remarks, "豆瓣 8.9 分", "parseNew remarks carry the rate");
        eq(out.get(0).tag, "douban", "parseNew tag is douban");
        eq(out.get(1).remarks, "豆瓣新片", "parseNew remarks without rate is 豆瓣新片");
        eq(out.get(2).poster, "", "parseNew keeps an empty poster (no placeholder)");
        eq(DoubanHome.parseNew("{\"data\":[]}").size(), 0, "parseNew empty data -> empty");
        eq(DoubanHome.parseNew("{}").size(), 0, "parseNew missing data -> empty");
        boolean threw = false;
        try {
            DoubanHome.parseNew("<html>not json</html>");
        } catch (Throwable ignored) {
            threw = true;
        }
        check(threw, "parseNew throws on bad JSON (caller treats as failure)");

        // Cap: 12 entries -> 10.
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"title\":\"片").append(i).append("\",\"rate\":\"\",\"cover\":\"\"}");
        }
        sb.append("]}");
        eq(DoubanHome.parseNew(sb.toString()).size(), 10, "parseNew caps at 10");

        // Worker-404 detection: the fallback decision predicate.
        check(DoubanHome.isUsableNewPayload(NEW_PAYLOAD), "usable payload: valid data");
        check(
                !DoubanHome.isUsableNewPayload(
                        "<html><head><title>404 Not Found</title></head></html>"),
                "unusable payload: Worker 404 HTML -> fall back to direct");
        check(
                !DoubanHome.isUsableNewPayload("{\"error\":\"not_found\"}"),
                "unusable payload: Worker 404 JSON -> fall back to direct");
        check(
                !DoubanHome.isUsableNewPayload("{\"data\":[]}"),
                "unusable payload: empty data -> fall back to direct");
        check(!DoubanHome.isUsableNewPayload("not json"), "unusable payload: bad JSON");
        check(!DoubanHome.isUsableNewPayload(null), "unusable payload: null");
    }

    // ---- 2. Douban suggest parsing (TitleTranslator) ----
    static void suggestChecks() {
        eq(
                TitleTranslator.parseSuggest(
                        "[{\"title\":\"芝加哥警署\",\"sub_title\":\"Chicago P.D.\",\"id\":\"1\"}]",
                        "Chicago P.D."),
                "芝加哥警署",
                "parseSuggest returns the Chinese title on sub-title match");
        eq(
                TitleTranslator.parseSuggest(
                        "[{\"title\":\"芝加哥烈焰\",\"sub_title\":\"Chicago Fire\",\"id\":\"2\"}]",
                        "Chicago P.D."),
                null,
                "parseSuggest rejects a non-matching sub-title");
        eq(
                TitleTranslator.parseSuggest(
                        "<html><head><title>404 Not Found</title></head></html>",
                        "Chicago P.D."),
                null,
                "parseSuggest: Worker 404 HTML -> null (fallback triggers)");
        eq(
                TitleTranslator.parseSuggest("{\"error\":\"not_found\"}", "Chicago P.D."),
                null,
                "parseSuggest: Worker 404 JSON -> null (fallback triggers)");
        eq(TitleTranslator.parseSuggest("[]", "Chicago P.D."), null, "parseSuggest empty -> null");
        eq(
                TitleTranslator.parseSuggest(
                        "[{\"title\":\"\",\"sub_title\":\"Chicago P.D.\",\"id\":\"3\"}]",
                        "Chicago P.D."),
                null,
                "parseSuggest empty title -> null");
    }

    // ---- 3. TMDB zh-TW translation pick ----
    static final String TMDB_TV =
            "{\"page\":1,\"results\":["
                    + "{\"id\":1,\"name\":\"芝加哥警署\",\"original_name\":\"Chicago P.D.\","
                    + "\"first_air_date\":\"2014-01-08\",\"poster_path\":\"/x.jpg\"},"
                    + "{\"id\":2,\"name\":\"Chicago Fire\",\"original_name\":\"Chicago Fire\","
                    + "\"first_air_date\":\"2012-10-10\",\"poster_path\":\"/y.jpg\"}"
                    + "],\"total_results\":2,\"total_pages\":1}";
    static final String TMDB_MOVIE =
            "{\"results\":["
                    + "{\"id\":3,\"title\":\"南方公園大電影\","
                    + "\"original_title\":\"South Park: Bigger, Longer & Uncut\","
                    + "\"release_date\":\"1999-06-30\"}"
                    + "]}";

    static void tmdbChecks() {
        eq(
                TmdbClient.pickTranslatedTitle(TMDB_TV, "Chicago P.D.", "tv"),
                "芝加哥警署",
                "TMDB tv: Chicago P.D. -> 芝加哥警署");
        eq(
                TmdbClient.pickTranslatedTitle(TMDB_TV, "Chicago Fire", "tv"),
                "",
                "TMDB tv: no zh-TW translation (name == original) -> empty");
        eq(
                TmdbClient.pickTranslatedTitle(TMDB_TV, "South Park", "tv"),
                "",
                "TMDB tv: no original-title match -> empty");
        eq(
                TmdbClient.pickTranslatedTitle(
                        TMDB_MOVIE, "South Park: Bigger, Longer & Uncut", "movie"),
                "南方公園大電影",
                "TMDB movie: South Park film -> 南方公園大電影");
        eq(
                TmdbClient.pickTranslatedTitle(TMDB_MOVIE, "Chicago P.D.", "movie"),
                "",
                "TMDB movie: wrong type does not match");
        eq(TmdbClient.pickTranslatedTitle("<html>404</html>", "Chicago P.D.", "tv"), "",
                "TMDB: bad JSON -> empty");
        eq(TmdbClient.pickTranslatedTitle("{\"results\":[]}", "Chicago P.D.", "tv"), "",
                "TMDB: empty results -> empty");
        eq(TmdbClient.pickTranslatedTitle("{}", "Chicago P.D.", "tv"), "",
                "TMDB: missing results -> empty");
        eq(TmdbClient.pickTranslatedTitle(null, "Chicago P.D.", "tv"), "",
                "TMDB: null payload -> empty");
    }

    // ---- 4. Home composition ----
    static void composeChecks() {
        List<Cms.Video> douban = new ArrayList<>();
        douban.add(v("msearch:新片A", "新片A"));
        douban.add(v("msearch:新片B", "新片B"));
        List<Cms.Video> source = new ArrayList<>();
        source.add(v("s1", "來源片1"));
        source.add(v("s2", "來源片2"));
        List<Cms.Video> simkl = new ArrayList<>();
        simkl.add(v("msearch:Simkl片", "Simkl片"));

        List<Cms.Video> c1 = HomeScreen.composeHomeVideos(douban, source, simkl, "", "");
        eq(
                titles(c1).toString(),
                "[新片A, 新片B, 來源片1, 來源片2]",
                "compose: Douban 新片 first, then the source (Simkl NOT used)");
        List<Cms.Video> c2 = HomeScreen.composeHomeVideos(douban, new ArrayList<Cms.Video>(), simkl, "", "");
        eq(
                titles(c2).toString(),
                "[新片A, 新片B, Simkl片]",
                "compose: empty source -> Simkl as fallback after Douban 新片");
        List<Cms.Video> c3 = HomeScreen.composeHomeVideos(null, null, simkl, "", "");
        eq(titles(c3).toString(), "[Simkl片]", "compose: Simkl alone when nothing else");
        eq(HomeScreen.composeHomeVideos(null, null, null, "", "").size(), 0, "compose: all null -> empty");
        List<Cms.Video> dup = new ArrayList<>();
        dup.add(v("msearch:新片A", "新片A"));
        eq(
                HomeScreen.composeHomeVideos(douban, dup, null, "", "").size(),
                2,
                "compose: same id in Douban + source is deduped");
        List<Cms.Video> junk = new ArrayList<>();
        junk.add(null);
        Cms.Video emptyId = new Cms.Video();
        emptyId.id = "";
        emptyId.title = "空id";
        junk.add(emptyId);
        junk.add(v("s9", "有效"));
        eq(
                HomeScreen.composeHomeVideos(null, junk, null, "", "").size(),
                1,
                "compose: null and empty-id videos are dropped");
    }

    // ---- 5. Updater apkUrl ----
    static void updaterChecks() {
        String uc =
                "https://drive.google.com/uc?export=download&id=19Fpfw9NvO-Ym3THBh6BOqLl5NQL7cNBU";
        String direct =
                "https://drive.usercontent.google.com/download?id=19Fpfw9NvO-Ym3THBh6BOqLl5NQL7cNBU&export=download&confirm=t";
        String directReordered =
                "https://drive.usercontent.google.com/download?confirm=t&export=download&id=19Fpfw9NvO-Ym3THBh6BOqLl5NQL7cNBU";
        check(UpdatePolicy.apkUrl(uc), "apkUrl accepts the Drive /uc form");
        check(UpdatePolicy.apkUrl(direct), "apkUrl accepts the direct usercontent confirm form");
        check(UpdatePolicy.apkUrl(directReordered), "apkUrl accepts reordered confirm params");
        check(
                !UpdatePolicy.apkUrl(
                        "https://example.com/download?id=X&export=download&confirm=t"),
                "apkUrl rejects a non-Drive host");
        check(
                !UpdatePolicy.apkUrl(
                        "https://drive.usercontent.google.com/download?id=X&export=download"),
                "apkUrl rejects usercontent without confirm=t");
        check(
                !UpdatePolicy.apkUrl(
                        "https://drive.usercontent.google.com/download?id=X&confirm=t"),
                "apkUrl rejects usercontent without export=download");
        check(
                !UpdatePolicy.apkUrl(
                        "https://drive.usercontent.google.com/download?id=X&export=download&confirm=t&extra=1"),
                "apkUrl rejects usercontent with an extra param");
        check(
                !UpdatePolicy.apkUrl(
                        "http://drive.usercontent.google.com/download?id=X&export=download&confirm=t"),
                "apkUrl rejects http (https only)");
        check(
                !UpdatePolicy.apkUrl(direct + "#frag"),
                "apkUrl rejects a fragment");
        check(
                !UpdatePolicy.apkUrl(
                        "https://drive.usercontent.google.com/download?id=X!&export=download&confirm=t"),
                "apkUrl rejects a bad Drive id");
        check(
                !UpdatePolicy.apkUrl(
                        "https://drive.usercontent.google.com/file?id=X&export=download&confirm=t"),
                "apkUrl rejects the wrong path");
        check(
                !UpdatePolicy.apkUrl(
                        "https://drive.google.com/uc?export=download&id=X&confirm=t"),
                "apkUrl rejects the /uc form with an extra param");
        String hash = new String(new char[64]).replace('\0', 'a');
        UpdatePolicy.validate(
                20059, "2.0.58", 23, 62952882, hash, direct, false, "stable", true);
        check(true, "validate() accepts the direct confirm form (mobile)");
        boolean rejected = false;
        try {
            UpdatePolicy.validate(
                    20059, "2.0.58", 23, 62952882, hash,
                    "https://example.com/x.apk", false, "stable", true);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "validate() still rejects a non-Drive apkUrl");
    }

    public static void main(String[] args) throws Exception {
        doubanNewChecks();
        suggestChecks();
        tmdbChecks();
        composeChecks();
        updaterChecks();
        System.out.println(passed + " Home2058 checks passed");
    }
}
