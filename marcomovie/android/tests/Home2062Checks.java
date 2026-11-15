package app.mkoai.player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.62 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2062_checks.py.
 *
 * Covers the free-service wiring, all pure (no network, no Android):
 *  WorkerApi.encode / RateLimited
 *  TraktHome.parseItem — trending (movie/show wrapper) vs popular (bare)
 *  SimklHome.parseItem / trendingData — Worker envelope {data:[...]},
 *      trending entries carry NO title even with extended=full
 *      (live-verified 2026-11-15); poster / rating / year extraction
 *  TvmazeHome.parseAirstamp — offsets, Z suffix, garbage; the Taipei day of
 *      the live-verified lag case (airdate 2026-11-14 vs airstamp
 *      2026-11-15T06:02:00+00:00) must be 11-15, never the airdate
 *  OmdbClient.ratingValue — all three ratings, missing / "N/A"
 *  YoutubeClient.watchUrl
 *  FanartClient.pickBackground / pickLogo / pickPoster — en preference,
 *      fallbacks, empty data
 *  (2.0.68 removed the online subtitle feature, so the
 *      SubtitleClient.parseResults tests are gone with it; the local
 *      subtitle file import is untouched and covered by the runner.)
 *  TranslateClient.truncate / splitChunks — 5000 cap, chunk boundaries
 *  PostHogClient.buildEventBody — shape + NEVER an api_key; distinctId
 *      stability + UUID format; the analytics switch default
 *  TmdbClient.toVideo — the TMDB id survives into Cms.Video.tmdbId
 *
 * Network-bound behaviour (429 fallback, empty Fanart data:{}, TVmaze
 * tonight dedup, subtitle download errors) is covered by the runner's
 * static greps + the live curl verification, not here.
 */
public class Home2062Checks {
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

    public static void main(String[] args) throws Exception {
        // ---- WorkerApi ----
        eq(WorkerApi.encode("a b"), "a%20b", "WorkerApi.encode: space becomes %20, not +");
        String zh = WorkerApi.encode("全面啟動");
        check(zh.contains("%") && !zh.contains("+") && !zh.contains("全"),
                "WorkerApi.encode: CJK is percent-encoded");
        eq(WorkerApi.encode("a+b"), "a%2Bb", "WorkerApi.encode: + is encoded, not left as +");
        eq(WorkerApi.encode(null), "", "WorkerApi.encode: null -> empty");
        WorkerApi.RateLimited rl = new WorkerApi.RateLimited(116);
        eq(rl.retryAfter, 116, "WorkerApi.RateLimited carries retry_after (116s, live Trakt case)");
        check(rl.getMessage().contains("116"), "WorkerApi.RateLimited message names the wait");

        // ---- TraktHome.parseItem: trending (wrapper) vs popular (bare) ----
        JSONObject trendingMovie = new JSONObject(
                "{\"watchers\":5,\"movie\":{\"title\":\"Heart of the Beast\",\"year\":2026,"
                        + "\"ids\":{\"trakt\":1025172,\"slug\":\"heart-of-the-beast\","
                        + "\"imdb\":\"tt7526136\",\"tmdb\":1263337}}}");
        TmdbClient.Item m = TraktHome.parseItem(trendingMovie, true, "野獸之心");
        eq(m.title, "野獸之心", "Trakt trending movie: display title is used");
        eq(m.type, "movie", "Trakt trending movie: type movie");
        eq(m.year, "2026", "Trakt trending movie: year 2026");
        eq(m.id, 1263337, "Trakt trending movie: TMDB id 1263337");
        eq(m.poster, "", "Trakt trending movie: no poster (Trakt has none)");
        eq(m.vote, 0.0, "Trakt trending movie: watchers is NOT used as a rating");
        eq(m.date, "", "Trakt trending movie: date empty (fail-open, no 2-day check)");
        JSONObject trendingShow = new JSONObject(
                "{\"watchers\":3,\"show\":{\"title\":\"Breaking Bad\",\"year\":2008,"
                        + "\"ids\":{\"trakt\":1,\"imdb\":\"tt0903747\",\"tmdb\":1396,\"tvdb\":81189}}}");
        TmdbClient.Item s = TraktHome.parseItem(trendingShow, false, "絕命毒師");
        eq(s.type, "tv", "Trakt trending show: type tv");
        eq(s.id, 1396, "Trakt trending show: TMDB id 1396");
        eq(s.year, "2008", "Trakt trending show: year 2008");
        JSONObject popularMovie = new JSONObject(
                "{\"title\":\"Inception\",\"year\":2010,\"ids\":{\"tmdb\":27205}}");
        TmdbClient.Item p = TraktHome.parseItem(popularMovie, true, "全面啟動");
        eq(p.title, "全面啟動", "Trakt popular movie (bare object): display title is used");
        eq(p.id, 27205, "Trakt popular movie (bare object): TMDB id 27205");
        eq(p.year, "2010", "Trakt popular movie (bare object): year 2010");
        JSONObject popularShow = new JSONObject("{\"title\":\"NoIds\",\"year\":0}");
        TmdbClient.Item q = TraktHome.parseItem(popularShow, false, "無ID劇");
        eq(q.id, 0, "Trakt popular show without ids: TMDB id 0");
        eq(q.year, "", "Trakt popular show without year: year empty");
        TmdbClient.Item n = TraktHome.parseItem(null, true, "空");
        eq(n.title, "空", "Trakt parseItem(null): display title still set");
        eq(n.id, 0, "Trakt parseItem(null): id 0");

        // ---- SimklHome.parseItem / trendingData (Worker envelope, no-title trending) ----
        JSONObject noTitle = new JSONObject(
                "{\"poster\":\"abc123\",\"fanart\":\"def456\","
                        + "\"ids\":{\"simkl_id\":123456},\"release_date\":\"2026-11-01\","
                        + "\"rank\":1,\"ratings\":{\"simkl\":{\"rating\":8.4},\"imdb\":{\"rating\":7.9}},"
                        + "\"country\":\"US\",\"runtime\":120,\"status\":\"ended\"}");
        TmdbClient.Item nt = SimklHome.parseItem(noTitle, true);
        eq(nt.title, "", "Simkl trending item: NO title even with extended=full (live verified)");
        eq(nt.poster, "https://simkl.in/posters/abc123_m.jpg",
                "Simkl trending item: poster path expands to simkl.in _m.jpg");
        eq(nt.vote, 8.4, "Simkl trending item: Simkl rating preferred over IMDb");
        eq(nt.year, "2026", "Simkl trending item: year from release_date");
        eq(nt.type, "movie", "Simkl trending item: type movie");
        TmdbClient.Item ntv = SimklHome.parseItem(noTitle, false);
        eq(ntv.type, "tv", "Simkl trending item: TV variant type tv");
        JSONObject titled = new JSONObject(
                "{\"title\":\"Inception\",\"poster\":\"x9\","
                        + "\"ratings\":{\"imdb\":{\"rating\":8.8}},\"release_date\":\"2010-07-16\"}");
        TmdbClient.Item ti = SimklHome.parseItem(titled, true);
        eq(ti.title, "Inception", "Simkl: a titled entry keeps its title");
        eq(ti.vote, 8.8, "Simkl: IMDb rating used when Simkl has none");
        eq(ti.year, "2010", "Simkl: year from release_date");
        TmdbClient.Item nn = SimklHome.parseItem(null, true);
        eq(nn.title, "", "Simkl parseItem(null): title empty");
        eq(nn.poster, "", "Simkl parseItem(null): poster empty");
        eq(SimklHome.trendingData(new JSONObject("{\"source\":\"simkl\",\"type\":\"movies\","
                        + "\"list\":\"trending\",\"data\":[{\"poster\":\"a\"},{\"poster\":\"b\"}]}")).length(),
                2, "Simkl envelope: data array extracted (2 items)");
        eq(SimklHome.trendingData(new JSONObject("{}")).length(), 0,
                "Simkl envelope: missing data -> empty array");
        eq(SimklHome.trendingData(null).length(), 0, "Simkl envelope: null -> empty array");

        // ---- TvmazeHome.parseAirstamp ----
        long a = TvmazeHome.parseAirstamp("2026-11-15T06:02:00+00:00");
        check(a > 1700000000000L, "TVmaze airstamp parses to a real epoch millis");
        eq(a, TvmazeHome.parseAirstamp("2026-11-15T14:02:00+08:00"),
                "TVmaze airstamp: +08:00 offset is the same instant as +00:00");
        eq(a, TvmazeHome.parseAirstamp("2026-11-15T06:02:00Z"),
                "TVmaze airstamp: Z suffix is the same instant as +00:00");
        eq(a, TvmazeHome.parseAirstamp("2026-11-14T22:02:00-08:00"),
                "TVmaze airstamp: -08:00 offset is the same instant as +00:00");
        check(TvmazeHome.parseAirstamp("2026-11-15T06:02:00+00:00")
                        > TvmazeHome.parseAirstamp("2026-11-15T06:01:00+00:00"),
                "TVmaze airstamp: later times parse later (sort order works)");
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dayFmt.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
        timeFmt.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
        eq(dayFmt.format(new Date(a)), "2026-11-15",
                "TVmaze airstamp: Taipei day is 11-15, NOT the airdate 11-14 (live lag case)");
        eq(timeFmt.format(new Date(a)), "14:02",
                "TVmaze airstamp: Taipei time is 14:02 for 06:02 UTC");
        eq(TvmazeHome.parseAirstamp("garbage"), -1L, "TVmaze airstamp: garbage -> -1");
        eq(TvmazeHome.parseAirstamp(""), -1L, "TVmaze airstamp: empty -> -1");
        eq(TvmazeHome.parseAirstamp(null), -1L, "TVmaze airstamp: null -> -1");
        eq(TvmazeHome.parseAirstamp("2026-13-45T99:99:99+00:00"), -1L,
                "TVmaze airstamp: impossible date -> -1 (not lenient)");
        eq(TvmazeHome.yearOf("2008-01-20"), "2008", "TVmaze yearOf: 2008-01-20 -> 2008");
        eq(TvmazeHome.yearOf(""), "", "TVmaze yearOf: empty -> empty");
        eq(TvmazeHome.yearOf(null), "", "TVmaze yearOf: null -> empty");
        eq(TvmazeHome.stripHtml("<p>He said &quot;hi&quot; &amp; left.</p>"),
                "He said \"hi\" & left.", "TVmaze stripHtml: tags + entities stripped");
        eq(TvmazeHome.stripHtml(null), "", "TVmaze stripHtml: null -> empty");

        // ---- OmdbClient.ratingValue ----
        JSONObject omdb = new JSONObject(
                "{\"Title\":\"Guardians of the Galaxy Vol. 2\",\"Ratings\":["
                        + "{\"Source\":\"Internet Movie Database\",\"Value\":\"7.6/10\"},"
                        + "{\"Source\":\"Rotten Tomatoes\",\"Value\":\"85%\"},"
                        + "{\"Source\":\"Metacritic\",\"Value\":\"67/100\"}]}");
        eq(OmdbClient.ratingValue(omdb, "Internet Movie Database"), "7.6/10",
                "OMDb: IMDb rating 7.6/10");
        eq(OmdbClient.ratingValue(omdb, "Rotten Tomatoes"), "85%",
                "OMDb: Rotten Tomatoes rating 85%");
        eq(OmdbClient.ratingValue(omdb, "Metacritic"), "67/100",
                "OMDb: Metacritic rating 67/100");
        eq(OmdbClient.ratingValue(omdb, "No Such Source"), null,
                "OMDb: missing source -> null (badge hidden)");
        JSONObject na = new JSONObject(
                "{\"Ratings\":[{\"Source\":\"Metacritic\",\"Value\":\"N/A\"}]}");
        eq(OmdbClient.ratingValue(na, "Metacritic"), null,
                "OMDb: \"N/A\" value -> null (badge hidden)");
        eq(OmdbClient.ratingValue(new JSONObject("{}"), "Metacritic"), null,
                "OMDb: no Ratings array -> null");
        eq(OmdbClient.ratingValue(null, "Metacritic"), null,
                "OMDb: null data -> null");

        // ---- YoutubeClient.watchUrl ----
        eq(YoutubeClient.watchUrl("YoHD9XEInc0"),
                "https://www.youtube.com/watch?v=YoHD9XEInc0",
                "YouTube watchUrl: canonical watch link (live Inception trailer id)");

        // ---- FanartClient pickers ----
        JSONObject fanart = new JSONObject(
                "{\"moviebackground\":[{\"lang\":\"es\",\"url\":\"https://bg-es.jpg\"},"
                        + "{\"lang\":\"en\",\"url\":\"https://bg-en.jpg\"}],"
                        + "\"tvbanner\":[{\"lang\":\"en\",\"url\":\"https://banner.jpg\"}],"
                        + "\"hdtvlogo\":[{\"lang\":\"en\",\"url\":\"https://logo-tv.jpg\"}],"
                        + "\"clearart\":[{\"lang\":\"en\",\"url\":\"https://clear.jpg\"}],"
                        + "\"tvposter\":[{\"lang\":\"en\",\"url\":\"https://poster-tv.jpg\"}]}");
        eq(FanartClient.pickBackground(fanart), "https://bg-en.jpg",
                "Fanart background: en preferred over es");
        eq(FanartClient.pickBackground(new JSONObject(
                        "{\"tvbanner\":[{\"lang\":\"en\",\"url\":\"https://banner.jpg\"}]}")),
                "https://banner.jpg", "Fanart background: falls back to tvbanner");
        eq(FanartClient.pickBackground(new JSONObject("{}")), null,
                "Fanart background: empty data:{} -> null (no artwork, not success)");
        eq(FanartClient.pickBackground(null), null, "Fanart background: null -> null");
        eq(FanartClient.pickLogo(fanart), "https://logo-tv.jpg",
                "Fanart logo: hdtvlogo when no hdmovielogo");
        eq(FanartClient.pickLogo(new JSONObject(
                        "{\"clearart\":[{\"lang\":\"en\",\"url\":\"https://clear.jpg\"}]}")),
                "https://clear.jpg", "Fanart logo: falls back to clearart");
        eq(FanartClient.pickLogo(new JSONObject("{}")), null,
                "Fanart logo: empty data:{} -> null");
        JSONObject posters = new JSONObject(
                "{\"movieposter\":[{\"lang\":\"en\",\"url\":\"https://poster-m.jpg\"}],"
                        + "\"tvposter\":[{\"lang\":\"en\",\"url\":\"https://poster-tv.jpg\"}]}");
        eq(FanartClient.pickPoster(posters), "https://poster-m.jpg",
                "Fanart poster: movieposter first");
        eq(FanartClient.pickPoster(fanart), "https://poster-tv.jpg",
                "Fanart poster: tvposter when no movieposter");
        eq(FanartClient.pickPoster(new JSONObject("{}")), null,
                "Fanart poster: empty data:{} -> null");

        // ---- TranslateClient.truncate / splitChunks ----
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append('a');
        eq(TranslateClient.truncate(big.toString()).length(), 5000,
                "Translate: 6000 chars truncated to 5000 (DeepL limit)");
        eq(TranslateClient.truncate("short"), "short", "Translate: short text unchanged");
        eq(TranslateClient.truncate(null), "", "Translate: null -> empty");
        List<String> chunks = TranslateClient.splitChunks("第一句。第二句。", 500);
        eq(chunks.size(), 1, "Translate chunks: two short sentences fit in one chunk");
        StringBuilder longSentence = new StringBuilder();
        for (int i = 0; i < 1200; i++) longSentence.append('b');
        List<String> hard = TranslateClient.splitChunks(longSentence.toString(), 500);
        eq(hard.size(), 3, "Translate chunks: 1200-char sentence hard-splits into 3");
        boolean allFit = true;
        for (String c : hard) if (c.length() > 500) allFit = false;
        check(allFit, "Translate chunks: no chunk exceeds 500 (MyMemory limit)");
        List<String> sent = TranslateClient.splitChunks("aaa。bbb。", 5);
        eq(sent.size(), 2, "Translate chunks: sentence boundary split keeps 2 chunks");
        eq(sent.get(0), "aaa。", "Translate chunks: first chunk keeps its 。");
        eq(sent.get(1), "bbb。", "Translate chunks: second chunk keeps its 。");

        // ---- PostHogClient.buildEventBody + distinct_id + switch ----
        JSONObject props = new JSONObject();
        props.put("title", "全面啟動");
        JSONObject body = PostHogClient.buildEventBody("video_play", "uuid-1", props);
        eq(body.optString("event"), "video_play", "PostHog body: event video_play");
        eq(body.optString("distinct_id"), "uuid-1", "PostHog body: distinct_id carried");
        eq(body.optJSONObject("properties").optString("title"), "全面啟動",
                "PostHog body: properties carry only the title");
        check(!body.has("api_key"), "PostHog body: NEVER contains api_key (Worker injects it)");
        check(!body.has("url") && !body.has("source"),
                "PostHog body: no URL or source fields");
        JSONObject emptyBody = PostHogClient.buildEventBody("app_open", "uuid-1", null);
        check(emptyBody.optJSONObject("properties") != null
                        && emptyBody.optJSONObject("properties").length() == 0,
                "PostHog body: null properties -> empty object, not null");
        String id1 = PostHogClient.distinctId();
        String id2 = PostHogClient.distinctId();
        eq(id1, id2, "PostHog distinct_id: stable across calls in one process");
        check(id1.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "PostHog distinct_id: random UUID format (no personal identifier)");
        check(PostHogClient.isAnalyticsEnabled(),
                "PostHog switch: analytics defaults to ON");
        PostHogClient.setAnalyticsEnabled(false);
        check(!PostHogClient.isAnalyticsEnabled(),
                "PostHog switch: turning it off reads back off");
        PostHogClient.setAnalyticsEnabled(true);
        check(PostHogClient.isAnalyticsEnabled(),
                "PostHog switch: turning it back on reads back on");

        // ---- TmdbClient.toVideo carries the TMDB id ----
        TmdbClient.Item it = new TmdbClient.Item();
        it.id = 27205;
        it.type = "movie";
        it.title = "全面啟動";
        Cms.Video v = TmdbClient.toVideo(it);
        eq(v.tmdbId, 27205, "TmdbClient.toVideo: TMDB id survives into Cms.Video.tmdbId");

        System.out.println(passed + " Home2062 checks passed");
    }
}
