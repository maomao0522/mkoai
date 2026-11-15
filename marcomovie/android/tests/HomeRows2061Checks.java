package app.mkoai.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * mkoai 2.0.61 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2061_checks.py.
 *
 * Covers:
 *  (a) SettingsScreen.accountSyncLinkKeys — the 「掃碼登入電視」 link is on
 *      the phone's account page whether signed in or not, and never on TV.
 *  (b) HomeRows2061 — year lines, row definitions, Simplified labels.
 *  (c) AppDialog.tvFocusTarget — TV dialog focus rules (safe default =
 *      negative; opted-in positive; list selection / form field win).
 *  (e) UpdateScreen.installSucceeded + UpdateManager.shouldDeleteAfterUpgrade
 *      — the downloaded APK is deleted only after a confirmed install.
 *  (f) HomeScreen.shouldAutoLoadMore — TV auto-load fires once per bottom.
 *  (g) HomeRows2061.TMDB_ROW_DEFS two-level order — broad movies, broad
 *      dramas, variety, mixed rows, then subdivisions (movies first).
 *  (h) VideoKind2061.isRecommendation — 推薦 keeps movies + dramas
 *      (incl. anime), drops variety/documentary/kids/known-other, and
 *      keeps a genuinely unclassified title (fail-open).
 *  (i) HomeScreen.mergeAllSources — the merged TV home mixes >1 source.
 *  (j) VideoKind2061.badgeLabel — 電影 / 劇 / no label when unknown.
 *  (k) SourcePreferences priority — 巨量, 360, 魔都動漫, xgzy (host
 *      matching), and the merged dedup prefers the higher-priority copy.
 *  (l) PlaybackControls.surfaceSize — the new 上下填滿 / 左右填滿 modes
 *      fill one dimension (cropping the other when the video overflows),
 *      and modes 0-4 are unchanged.
 *  (d) is covered by static greps in the runner (the permission-return
 *      path is UI code, not pure).
 */
public class HomeRows2061Checks {
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

    static Cms.Video video(String id, String title) {
        Cms.Video v = new Cms.Video();
        v.id = id;
        v.title = title;
        return v;
    }

    static Cms.Video kindVideo(
            String id,
            String title,
            String typeName,
            String genre,
            String tag,
            String mediaType,
            String sourceApi) {
        Cms.Video v = video(id, title);
        v.typeName = typeName;
        v.genre = genre;
        v.tag = tag;
        v.mediaType = mediaType;
        v.sourceApi = sourceApi;
        return v;
    }

    static Cms.Site site(String key, String api) {
        Cms.Site s = new Cms.Site();
        s.key = key;
        s.name = key;
        s.api = api;
        return s;
    }

    static int rowIndex(String label) {
        for (int i = 0; i < HomeRows2061.TMDB_ROW_DEFS.length; i++) {
            if (label.equals(HomeRows2061.TMDB_ROW_DEFS[i][0])) return i;
        }
        return -1;
    }

    public static void main(String[] args) {
        // ---- (a) 掃碼登入電視 on the phone's account page ----
        List<String> phoneIn = SettingsScreen.accountSyncLinkKeys(true, false);
        check(phoneIn.contains("sync-qr-login-phone"),
                "(a) signed-in phone account page has 掃碼登入電視");
        check(phoneIn.contains("sync-now") && phoneIn.contains("sync-signout"),
                "(a) signed-in phone still has 立即同步 + 登出帳號");
        check(!phoneIn.contains("sync-signin"),
                "(a) signed-in phone does not show the sign-in link");
        check(phoneIn.indexOf("sync-qr-login-phone") == phoneIn.size() - 1,
                "(a) 掃碼登入電視 is the last link on the signed-in phone page");
        List<String> phoneOut = SettingsScreen.accountSyncLinkKeys(false, false);
        check(phoneOut.contains("sync-qr-login-phone"),
                "(a) signed-out phone account page has 掃碼登入電視");
        check(phoneOut.contains("sync-signin"),
                "(a) signed-out phone still has the sign-in link");
        List<String> tvIn = SettingsScreen.accountSyncLinkKeys(true, true);
        check(!tvIn.contains("sync-qr-login-phone"),
                "(a) signed-in TV account page has no 掃碼登入電視 (TV is the target)");
        check(tvIn.contains("sync-now") && tvIn.contains("sync-signout"),
                "(a) signed-in TV still has 立即同步 + 登出帳號");
        List<String> tvOut = SettingsScreen.accountSyncLinkKeys(false, true);
        check(tvOut.contains("sync-password") && tvOut.contains("sync-qr-login"),
                "(a) signed-out TV has 帳號密碼登入 + 掃碼登入");
        check(!tvOut.contains("sync-qr-login-phone"),
                "(a) signed-out TV has no phone-side 掃碼登入電視");

        // ---- (b) home year lines + row definitions ----
        Cms.Video tvNow = kindVideo("t1", "劇", null, null, null, "tv", null);
        tvNow.year = "2026";
        eq(HomeRows2061.yearLine(tvNow, 2026), "2026 - 现在",
                "(b) airing TV show (year == current year) reads '2026 - 现在'");
        Cms.Video tvOld = kindVideo("t2", "劇", null, null, null, "tv", null);
        tvOld.year = "2024";
        eq(HomeRows2061.yearLine(tvOld, 2026), "2024",
                "(b) ended TV show reads just the year");
        Cms.Video movie = kindVideo("t3", "電影", null, null, null, "movie", null);
        movie.year = "2026";
        eq(HomeRows2061.yearLine(movie, 2026), "2026",
                "(b) a movie from the current year reads just the year");
        Cms.Video noYear = kindVideo("t4", "無年份", null, null, null, "movie", null);
        noYear.year = "";
        eq(HomeRows2061.yearLine(noYear, 2026), "",
                "(b) no year -> no year line (hidden)");
        eq(HomeRows2061.yearLine(null, 2026), "", "(b) null video -> no year line");
        eq(HomeRows2061.TMDB_ROW_DEFS.length, 30,
                "(b) the phone home keeps all 25 reference rows + 5 new 2.0.62 rows (got 30)");
        Set<String> labels = new HashSet<>();
        for (String[] def : HomeRows2061.TMDB_ROW_DEFS) labels.add(def[0]);
        eq(labels.size(), 30, "(b) no duplicate row labels");
        check(HomeRows2061.isDoubanRow(HomeRows2061.KIND_DOUBAN_TOP),
                "(b) 豆瓣高分 is a Douban-backed row");
        check(!HomeRows2061.isDoubanRow("trending_movie"),
                "(b) 热门电影 is a TMDB row, not Douban");
        eq(HomeRows2061.HOME_SLATE_BG, 0xff6b7683,
                "(b) home background is the muted blue-grey, not pure black");

        // ---- (c) TV dialog focus ----
        eq(AppDialog.tvFocusTarget(true, false, false, true, true, false),
                "selected", "(c) a checked list item keeps focus");
        eq(AppDialog.tvFocusTarget(false, true, false, true, true, false),
                "view", "(c) a form field keeps focus");
        eq(AppDialog.tvFocusTarget(false, false, true, true, true, false),
                "positive", "(c) an opted-in dialog focuses the positive button");
        eq(AppDialog.tvFocusTarget(false, false, false, true, true, false),
                "negative", "(c) safe default focuses the negative button");
        eq(AppDialog.tvFocusTarget(false, false, false, true, false, false),
                "positive", "(c) a single-button dialog focuses its only button");
        eq(AppDialog.tvFocusTarget(false, false, false, false, false, true),
                "neutral", "(c) a neutral-only dialog focuses the neutral button");
        eq(AppDialog.tvFocusTarget(false, false, false, false, false, false),
                "content", "(c) a button-less dialog falls back to the content");

        // ---- (e) delete the downloaded APK only after a confirmed install ----
        check(UpdateScreen.installSucceeded(android.app.Activity.RESULT_OK),
                "(e) RESULT_OK counts as a successful install");
        check(!UpdateScreen.installSucceeded(android.app.Activity.RESULT_CANCELED),
                "(e) RESULT_CANCELED does not count as a successful install");
        check(!UpdateScreen.installSucceeded(android.app.Activity.RESULT_FIRST_USER),
                "(e) any other result does not count as a successful install");
        check(UpdateManager.shouldDeleteAfterUpgrade(20063, 20062),
                "(e) a higher installed version deletes the old APK");
        check(!UpdateManager.shouldDeleteAfterUpgrade(20062, 20062),
                "(e) the same version does not delete the APK");
        check(!UpdateManager.shouldDeleteAfterUpgrade(20061, 20062),
                "(e) a lower installed version does not delete the APK");

        // ---- (f) TV auto-load-more fires once per bottom ----
        check(HomeScreen.shouldAutoLoadMore(true, false, true, true, false,
                        "mkoai2:0:1:40", null),
                "(f) auto-load fires at the bottom with more windows");
        check(HomeScreen.shouldAutoLoadMore(true, false, true, false, true,
                        "mkoai2:0:1:40", null),
                "(f) auto-load fires at the bottom with more pages");
        check(!HomeScreen.shouldAutoLoadMore(true, true, true, true, false,
                        "mkoai2:0:1:40", null),
                "(f) auto-load does not fire while a load is running");
        check(!HomeScreen.shouldAutoLoadMore(true, false, false, true, false,
                        "mkoai2:0:1:40", null),
                "(f) auto-load does not fire when the pill is hidden");
        check(!HomeScreen.shouldAutoLoadMore(true, false, true, false, false,
                        "mkoai2:0:1:40", null),
                "(f) auto-load does not fire when there is nothing more");
        check(!HomeScreen.shouldAutoLoadMore(true, false, true, true, false,
                        "mkoai2:0:1:40", "mkoai2:0:1:40"),
                "(f) auto-load does not fire twice for the same bottom");
        check(!HomeScreen.shouldAutoLoadMore(false, false, true, true, false,
                        "mkoai2:0:1:40", null),
                "(f) auto-load does not fire on an inactive screen");

        // ---- (g) two-level row order ----
        String[] broadMovies = {"热门电影", "豆瓣高分", "全部电影"};
        String[] broadDramas = {"热门剧集", "Netflix 新片", "全部剧集"};
        String[] movieSubs = {"动画电影", "动作电影", "国语电影", "儿童电影"};
        String[] dramaSubs = {"短剧", "国漫", "日漫", "国产剧", "港台剧", "美剧", "日剧", "韩剧", "英剧"};
        String[] docKids = {"纪录片", "儿童中文", "儿童英语"};
        int maxBroadMovie = -1, minBroadDrama = 99, maxBroadDrama = -1;
        for (String l : broadMovies) maxBroadMovie = Math.max(maxBroadMovie, rowIndex(l));
        for (String l : broadDramas) {
            minBroadDrama = Math.min(minBroadDrama, rowIndex(l));
            maxBroadDrama = Math.max(maxBroadDrama, rowIndex(l));
        }
        check(maxBroadMovie < minBroadDrama,
                "(g) every broad movie row comes before every broad drama row");
        check(maxBroadDrama < rowIndex("综艺"),
                "(g) every broad drama row comes before the variety row");
        check(rowIndex("综艺") < rowIndex("最新上架")
                        && rowIndex("综艺") < rowIndex("高分佳作"),
                "(g) the variety row comes before the mixed recommendation rows");
        int minSub = 99;
        for (String l : movieSubs) minSub = Math.min(minSub, rowIndex(l));
        for (String l : dramaSubs) minSub = Math.min(minSub, rowIndex(l));
        for (String l : docKids) minSub = Math.min(minSub, rowIndex(l));
        check(Math.max(rowIndex("最新上架"), rowIndex("高分佳作")) < minSub,
                "(g) every broad/variety/mixed row comes before every subdivision");
        int maxMovieSub = -1, minDramaSub = 99, maxDramaSub = -1, minDocKids = 99;
        for (String l : movieSubs) maxMovieSub = Math.max(maxMovieSub, rowIndex(l));
        for (String l : dramaSubs) {
            minDramaSub = Math.min(minDramaSub, rowIndex(l));
            maxDramaSub = Math.max(maxDramaSub, rowIndex(l));
        }
        for (String l : docKids) minDocKids = Math.min(minDocKids, rowIndex(l));
        check(maxMovieSub < minDramaSub,
                "(g) movie subdivisions come before drama subdivisions");
        check(maxDramaSub < minDocKids,
                "(g) drama subdivisions come before documentary/kids");

        // ---- (h)(j) movie vs drama classification ----
        Cms.Video vMovie = kindVideo("m1", "電影甲", "電影", null, null, null, null);
        eq(VideoKind2061.kindOf(vMovie), VideoKind2061.MOVIE,
                "(h) typeName 電影 -> movie");
        eq(VideoKind2061.badgeLabel(vMovie), "電影", "(j) movie card says 電影");
        check(VideoKind2061.isRecommendation(vMovie),
                "(h) a movie stays in 推薦");
        Cms.Video vMovieTrad = kindVideo("m2", "電影乙", "電影", null, null, null, null);
        eq(VideoKind2061.kindOf(vMovieTrad), VideoKind2061.MOVIE,
                "(h) typeName 電影 (traditional) -> movie");
        Cms.Video vDrama = kindVideo("d1", "連續劇甲", "電視劇", null, null, null, null);
        eq(VideoKind2061.kindOf(vDrama), VideoKind2061.SERIES,
                "(h) typeName 電視劇 -> series");
        eq(VideoKind2061.badgeLabel(vDrama), "劇", "(j) drama card says 劇");
        check(VideoKind2061.isRecommendation(vDrama),
                "(h) a drama stays in 推薦");
        Cms.Video vAnime = kindVideo("a1", "動漫甲", "動漫", null, null, null, null);
        eq(VideoKind2061.kindOf(vAnime), VideoKind2061.SERIES,
                "(h) typeName 動漫 -> series (anime counts as 劇)");
        eq(VideoKind2061.badgeLabel(vAnime), "劇", "(j) anime card says 劇");
        check(VideoKind2061.isRecommendation(vAnime),
                "(h) an anime series stays in 推薦");
        Cms.Video vVariety = kindVideo("v1", "綜藝甲", "綜藝", null, null, null, null);
        eq(VideoKind2061.kindOf(vVariety), VideoKind2061.OTHER,
                "(h) typeName 綜藝 -> other");
        eq(VideoKind2061.badgeLabel(vVariety), "", "(j) a variety card gets no label");
        check(!VideoKind2061.isRecommendation(vVariety),
                "(h) a variety show is dropped from 推薦");
        Cms.Video vDoc = kindVideo("v2", "紀錄片甲", "紀錄片", null, null, null, null);
        check(!VideoKind2061.isRecommendation(vDoc),
                "(h) a documentary is dropped from 推薦");
        Cms.Video vKidsMovie = kindVideo("k1", "兒童電影甲", "儿童电影", null, null, null, null);
        eq(VideoKind2061.kindOf(vKidsMovie), VideoKind2061.MOVIE,
                "(h) 儿童电影 is a movie by kind");
        eq(VideoKind2061.badgeLabel(vKidsMovie), "電影",
                "(j) a kids movie card still says 電影 on its own row");
        check(!VideoKind2061.isRecommendation(vKidsMovie),
                "(h) a kids movie is still dropped from 推薦 (kids exclusion wins)");
        Cms.Video vKids = kindVideo("k2", "兒童甲", "儿童", null, null, null, null);
        check(!VideoKind2061.isRecommendation(vKids),
                "(h) a kids title is dropped from 推薦");
        Cms.Video vSports = kindVideo("s1", "體育甲", "體育", null, null, null, null);
        check(!VideoKind2061.isRecommendation(vSports),
                "(h) a known other category (sports) is dropped from 推薦");
        Cms.Video vTmdbMovie = kindVideo("t5", "TMDB電影", null, null, "tmdb", "movie", null);
        eq(VideoKind2061.kindOf(vTmdbMovie), VideoKind2061.MOVIE,
                "(h) TMDB mediaType movie -> movie");
        check(VideoKind2061.isRecommendation(vTmdbMovie),
                "(h) a TMDB movie stays in 推薦");
        Cms.Video vTmdbTv = kindVideo("t6", "TMDB劇", null, null, "tmdb", "tv", null);
        eq(VideoKind2061.kindOf(vTmdbTv), VideoKind2061.SERIES,
                "(h) TMDB mediaType tv -> series");
        Cms.Video vDouban = kindVideo("t7", "豆瓣電影", null, null, "douban", null, null);
        eq(VideoKind2061.kindOf(vDouban), VideoKind2061.MOVIE,
                "(h) a Douban item -> movie");
        check(VideoKind2061.isRecommendation(vDouban),
                "(h) a Douban item stays in 推薦");
        Cms.Video vSimklMovie = kindVideo("t8", "Simkl電影", null, null, "simkl-電影", null, null);
        eq(VideoKind2061.kindOf(vSimklMovie), VideoKind2061.MOVIE,
                "(h) Simkl 電影 -> movie");
        Cms.Video vSimklTv = kindVideo("t9", "Simkl影劇", null, null, "simkl-影劇", null, null);
        eq(VideoKind2061.kindOf(vSimklTv), VideoKind2061.SERIES,
                "(h) Simkl 影劇 -> series");
        Cms.Video vGenreMovie = kindVideo("t10", "喜劇電影", null, "电影,喜剧", null, null, null);
        eq(VideoKind2061.kindOf(vGenreMovie), VideoKind2061.MOVIE,
                "(h) a genre tag with the movie word still reads as a movie");
        Cms.Video vGenreOnly = kindVideo("t11", "劇情片", null, "剧情,喜剧", null, null, null);
        eq(VideoKind2061.kindOf(vGenreOnly), VideoKind2061.OTHER,
                "(h) a genre tag 劇情 must NOT imply series (it is a genre, not a category)");
        eq(VideoKind2061.badgeLabel(vGenreOnly), "",
                "(j) a genre-only title gets no label rather than a wrong guess");
        check(VideoKind2061.isRecommendation(vGenreOnly),
                "(h) a genre-only title is kept (fail-open: no category to judge by)");
        Cms.Video vUnknown = video("u1", "未知類型");
        eq(VideoKind2061.kindOf(vUnknown), VideoKind2061.OTHER,
                "(h) an unclassified title -> other");
        eq(VideoKind2061.badgeLabel(vUnknown), "",
                "(j) an unclassified title gets no label");
        check(VideoKind2061.isRecommendation(vUnknown),
                "(h) an unclassified title is kept (fail-open)");
        List<Cms.Video> mixed =
                Arrays.asList(vMovie, vDrama, vAnime, vVariety, vDoc, vKidsMovie, vUnknown);
        List<Cms.Video> kept = VideoKind2061.filterRecommendations(mixed);
        eq(kept.size(), 4, "(h) filter keeps movies+dramas+anime+unknown, drops 3");
        check(kept.contains(vMovie) && kept.contains(vDrama)
                        && kept.contains(vAnime) && kept.contains(vUnknown),
                "(h) filter keeps exactly the movie, drama, anime and unknown titles");
        eq(VideoKind2061.filterRecommendations(null).size(), 0,
                "(h) filter handles a null list");
        check(!VideoKind2061.isRecommendation(null),
                "(h) a null video is not a recommendation");
        eq(VideoKind2061.kindOf(null), VideoKind2061.OTHER,
                "(h) a null video has no kind");

        // ---- (i) merged TV home mixes >1 source ----
        check(HomeScreen.isAllSources(HomeScreen.allSourcesSite()),
                "(i) the __all__ sentinel is the merged home");
        check(!HomeScreen.isAllSources(null), "(i) a null site is not the merged home");
        check(!HomeScreen.isAllSources(site("mkoai2", "https://x.example/api")),
                "(i) a real source is not the merged home");
        List<List<Cms.Video>> perSource = new ArrayList<>();
        perSource.add(Arrays.asList(
                kindVideo("a1", "八仙", "電影", null, null, null,
                        "https://mango.example/api"),
                kindVideo("a2", "流浪地球", "電影", null, null, null,
                        "https://mango.example/api")));
        perSource.add(Arrays.asList(
                kindVideo("b1", "哪吒", "電影", null, null, null,
                        "https://bdzy.example/api"),
                kindVideo("b2", "長津湖", "電影", null, null, null,
                        "https://bdzy.example/api")));
        List<Cms.Video> merged = HomeScreen.mergeAllSources(perSource, 40);
        eq(merged.size(), 4, "(i) the merged home mixes titles from two sources");
        Set<String> mergedTitles = new HashSet<>();
        for (Cms.Video v : merged) mergedTitles.add(v.title);
        check(mergedTitles.contains("八仙") && mergedTitles.contains("哪吒"),
                "(i) the merged home contains titles from both sources");
        // Same title on two sources -> the higher-priority copy wins.
        List<List<Cms.Video>> dup = new ArrayList<>();
        dup.add(Arrays.asList(kindVideo("low1", "八仙", "電影", null, null, null,
                "https://mango.example/api")));
        dup.add(Arrays.asList(kindVideo("hi1", "八仙", "電影", null, null, null,
                "https://api.juliang.live/api/provide/vod/at/xml/")));
        List<Cms.Video> mergedDup = HomeScreen.mergeAllSources(dup, 40);
        eq(mergedDup.size(), 1, "(i) the same title from two sources merges to one");
        eq(mergedDup.get(0).id, "hi1",
                "(i) the merged copy is the higher-priority source's (巨量 over 芒果）");
        List<List<Cms.Video>> dupReverse = new ArrayList<>();
        dupReverse.add(Arrays.asList(kindVideo("hi1", "八仙", "電影", null, null, null,
                "https://api.juliang.live/api/provide/vod/at/xml/")));
        dupReverse.add(Arrays.asList(kindVideo("low1", "八仙", "電影", null, null, null,
                "https://mango.example/api")));
        List<Cms.Video> mergedRev = HomeScreen.mergeAllSources(dupReverse, 40);
        eq(mergedRev.size(), 1, "(i) reverse arrival still merges to one");
        eq(mergedRev.get(0).id, "hi1",
                "(i) a low-priority copy arriving later cannot replace 巨量");
        // Per-source cap still holds.
        List<Cms.Video> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add(video("x" + i, "片" + i));
        List<List<Cms.Video>> oneBig = new ArrayList<>();
        oneBig.add(many);
        eq(HomeScreen.mergeAllSources(oneBig, 40).size(), 10,
                "(i) one source still contributes at most 10 titles");

        // ---- (k) priority order: 巨量, 360, 魔都動漫, xgzy ----
        eq(SourcePreferences.priorityIndex(
                        "https://api.juliang.live/api/provide/vod/at/xml/"),
                0, "(k) 巨量 is priority 0");
        eq(SourcePreferences.priorityIndex("https://360zyzz.com/api.php/provide/vod"),
                1, "(k) 360 is priority 1 (host match, not the old caiji URL)");
        eq(SourcePreferences.priorityIndex(
                        "https://www.mdzyapi.com/api.php/provide/vod/"),
                2, "(k) 魔都動漫 is priority 2 (www. host still matches)");
        eq(SourcePreferences.priorityIndex(
                        "https://caiji.xgzyapi.com/api.php/provide/vod/at/xml/"),
                3, "(k) xgzy is priority 3");
        eq(SourcePreferences.priorityIndex("https://mango.example/api"), -1,
                "(k) an unlisted source is not prioritized");
        eq(SourcePreferences.priorityIndex((String) null), -1,
                "(k) a null api is not prioritized");
        check(SourcePreferences.apiMatchesHost(
                        "https://www.mdzyapi.com/api.php/provide/vod/", "mdzyapi.com"),
                "(k) host matching is a substring match");
        check(!SourcePreferences.apiMatchesHost(
                        "https://mango.example/api", "mdzyapi.com"),
                "(k) host matching rejects a different host");
        List<Cms.Site> sites =
                Arrays.asList(
                        site("mango", "https://mango.example/api"),
                        site("xgzy", "https://caiji.xgzyapi.com/api.php/provide/vod/at/xml/"),
                        site("juliang", "https://api.juliang.live/api/provide/vod/at/xml/"),
                        site("360", "https://360zyzz.com/api.php/provide/vod"),
                        site("mdzy", "https://www.mdzyapi.com/api.php/provide/vod/"),
                        site("bdzy", "https://bdzy.example/api"));
        List<Cms.Site> ordered = SourcePreferences.prioritized("", sites);
        eq(ordered.get(0).key, "juliang", "(k) auto-select tries 巨量 first");
        eq(ordered.get(1).key, "360", "(k) auto-select tries 360 second");
        eq(ordered.get(2).key, "mdzy", "(k) auto-select tries 魔都動漫 third");
        eq(ordered.get(3).key, "xgzy", "(k) xgzy is just after the three ad-rule sources");
        eq(ordered.get(4).key, "mango", "(k) the rest keep config order after the priority sources");
        eq(ordered.get(5).key, "bdzy", "(k) the last source keeps its config position");
        List<Cms.Site> orderedPref =
                SourcePreferences.prioritized("bdzy\nhttps://bdzy.example/api", sites);
        eq(orderedPref.get(0).key, "bdzy",
                "(k) the user's explicit preferred site still wins over the priority list");
        eq(orderedPref.get(1).key, "juliang",
                "(k) the priority list follows the preferred site");
        // Merged dedup prefers 360 over xgzy, and 魔都動漫 over xgzy.
        List<List<Cms.Video>> dup360 = new ArrayList<>();
        dup360.add(Arrays.asList(kindVideo("x1", "八仙", "電影", null, null, null,
                "https://caiji.xgzyapi.com/api.php/provide/vod/at/xml/")));
        dup360.add(Arrays.asList(kindVideo("c1", "八仙", "電影", null, null, null,
                "https://360zyzz.com/api.php/provide/vod")));
        eq(HomeScreen.mergeAllSources(dup360, 40).get(0).id, "c1",
                "(k) the merged copy prefers 360 over xgzy");
        List<List<Cms.Video>> dupMdzy = new ArrayList<>();
        dupMdzy.add(Arrays.asList(kindVideo("x1", "八仙", "電影", null, null, null,
                "https://caiji.xgzyapi.com/api.php/provide/vod/at/xml/")));
        dupMdzy.add(Arrays.asList(kindVideo("m1", "八仙", "電影", null, null, null,
                "https://www.mdzyapi.com/api.php/provide/vod/")));
        eq(HomeScreen.mergeAllSources(dupMdzy, 40).get(0).id, "m1",
                "(k) the merged copy prefers 魔都動漫 over xgzy");

        // ---- (l) aspect modes: 上下填滿 / 左右填滿 (resize math) ----
        // surfaceSize(mode, videoW, videoH, layerW, layerH) -> {w, h}.
        int[] s;
        // Mode 5 上下填滿: height fills; a 21:9 video on a 16:9 screen
        // overflows the width (left/right crop).
        s = PlaybackControls.surfaceSize(5, 2520, 1080, 1920, 1080);
        eq(s[1], 1080, "(l) 上下填滿 fills the height (21:9 video on 16:9 screen)");
        check(s[0] > 1920, "(l) 上下填滿 crops the width when the video is wider than the screen");
        eq(s[0], 2520, "(l) 上下填滿 width follows the video ratio (1080 x 21/9)");
        // Mode 5: a 16:9 video on a wider (21:9) screen — height fills,
        // the width does not overflow (the video is narrower, so the side
        // bars stay; nothing to crop without distortion).
        s = PlaybackControls.surfaceSize(5, 1920, 1080, 2560, 1080);
        eq(s[1], 1080, "(l) 上下填滿 fills the height (16:9 video on a wider screen)");
        eq(s[0], 1920, "(l) 上下填滿 keeps the video width (no crop when narrower)");
        // Mode 6 左右填滿: width fills; a 4:3 video on a 16:9 screen
        // overflows the height (top/bottom crop).
        s = PlaybackControls.surfaceSize(6, 1440, 1080, 1920, 1080);
        eq(s[0], 1920, "(l) 左右填滿 fills the width (4:3 video on 16:9 screen)");
        check(s[1] > 1080, "(l) 左右填滿 crops the height when the video is taller than the screen");
        eq(s[1], 1440, "(l) 左右填滿 height follows the video ratio (1920 / 4/3)");
        // Mode 6: a 16:9 video on a 16:9 screen fits exactly.
        s = PlaybackControls.surfaceSize(6, 1920, 1080, 1920, 1080);
        eq(s[0], 1920, "(l) 左右填滿 fills the width exactly for a matching ratio");
        eq(s[1], 1080, "(l) 左右填滿 needs no crop for a matching ratio");
        // Modes 0-4 are unchanged.
        s = PlaybackControls.surfaceSize(0, 1920, 1080, 1280, 1024);
        eq(s[0], 1280, "(l) 自動 (fit) still fills the width on a narrower screen");
        eq(s[1], 720, "(l) 自動 (fit) still letterboxes — never crops");
        s = PlaybackControls.surfaceSize(0, 1440, 1080, 1920, 1080);
        eq(s[0], 1440, "(l) 自動 (fit) still pillarboxes a narrow video");
        eq(s[1], 1080, "(l) 自動 (fit) keeps the full height");
        s = PlaybackControls.surfaceSize(4, 1920, 1080, 1280, 1024);
        eq(s[1], 1024, "(l) 裁切填充 still fills the height (cover)");
        check(s[0] > 1280, "(l) 裁切填充 still crops the width (cover)");
        s = PlaybackControls.surfaceSize(1, 1920, 1080, 1280, 1024);
        eq(s[0], 1280, "(l) 鋪滿螢幕 still stretches to the layer width");
        eq(s[1], 1024, "(l) 鋪滿螢幕 still stretches to the layer height");
        s = PlaybackControls.surfaceSize(2, 1440, 1080, 1920, 1080);
        eq(s[0], 1920, "(l) 16:9 mode still forces the 16:9 ratio");
        eq(s[1], 1080, "(l) 16:9 mode still fits");
        s = PlaybackControls.surfaceSize(3, 1920, 1080, 1920, 1080);
        eq(s[0], 1440, "(l) 4:3 mode still forces the 4:3 ratio");
        eq(s[1], 1080, "(l) 4:3 mode still fits the height");

        System.out.println(passed + " HomeRows2061 checks passed");
    }
}
