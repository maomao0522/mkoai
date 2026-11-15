package app.mkoai.player;

/**
 * mkoai 2.0.63 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2063_checks.py.
 *
 * Covers HeroDiffusion2063 — all pure (no Android, no network):
 *  averageTone — uniform colour, transparent pixels ignored, empty
 *      sample falls back to the slate, count clamped to the array;
 *      2.0.64: saturation-weighted, so a pale poster with vivid art
 *      still yields a coloured tone (the flat average washed it grey)
 *  backgroundTone — mute + darken + brightest-channel clamp; expected
 *      values were computed independently (Python) from the formula,
 *      not copied from the Java output; 2.0.64 keeps 70% of the
 *      saturation and 65% of the brightness (was 45% / 55%), clamp
 *      stays 140 as the white-text contrast guard
 *  lerpColor — endpoints, midpoint, t clamped outside 0..1
 *  scrimColors — 8 stops, first four transparent, the page colour
 *      ramping in at 25% / 50% / 75% alpha, opaque page colour at the
 *      eighth (fade over the bottom ~55% of the hero, ending exactly
 *      on the page colour)
 *  FALLBACK_BG — the same slate the 2.0.61 home already uses
 * Covers HeroSwipe2063 — all pure:
 *  isTap — 12dp slop, both axes
 *  targetPage — flick wins over distance; otherwise a tenth of a page
 *      of drag changes the card; clamps at the first/last page
 *  shouldPauseAutoAdvance / resumeDelayMs — 24s pause after a manual
 *      swipe, 12s normal cycle
 *  constants — 12s interval, 24s post-swipe pause, 900ms glide,
 *      tenth-page distance rule
 * Covers the 2.0.63 hero anime filter (VideoKind2061.isAnime /
 * filterNonAnime): animation categories in either script are dropped;
 * a title with no category at all is kept; an all-anime list falls
 * back to the unfiltered list (fail-open).
 * Covers the 2.0.63 all-Chinese home cards (TitleTranslator /
 * TmdbClient.pickTranslatedTitle / parseSuggest): the fast path for
 * already-Chinese titles (no network), the 80-point TMDB match gate,
 * the Douban suggest sub-title check, the msearch-id rewrite, the
 * globally-bounded batch (fail-open on a blown budget), and the
 * translateItems / translateVideos fast-path behaviour. All pure —
 * no live network is needed (Chinese titles return before any call).
 */
public class Home2063Checks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(int got, int want, String name) {
        if (got != want) {
            throw new AssertionError(
                    "FAIL: " + name + " (got 0x" + Integer.toHexString(got)
                            + ", want 0x" + Integer.toHexString(want) + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static void eqL(long got, long want, String name) {
        if (got != want) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static Cms.Video video(String typeName, String genre, String tag) {
        Cms.Video v = new Cms.Video();
        v.typeName = typeName;
        v.genre = genre;
        v.tag = tag;
        return v;
    }

    public static void main(String[] args) throws Exception {
        // ---- averageTone ----
        eq(HeroDiffusion2063.averageTone(new int[] {0xffff0000}, 1),
                0xffff0000, "averageTone: single red pixel stays red");
        eq(HeroDiffusion2063.averageTone(new int[] {0xffff0000, 0xff00ff00}, 2),
                0xff7f7f00, "averageTone: red + green averages per channel");
        eq(HeroDiffusion2063.averageTone(new int[] {0x00000000, 0xff0000ff}, 2),
                0xff0000ff, "averageTone: fully transparent pixels are ignored");
        eq(HeroDiffusion2063.averageTone(new int[] {0x00000000}, 1),
                HeroDiffusion2063.FALLBACK_BG,
                "averageTone: all-transparent sample falls back to the slate");
        eq(HeroDiffusion2063.averageTone(new int[0], 0),
                HeroDiffusion2063.FALLBACK_BG, "averageTone: empty sample falls back");
        eq(HeroDiffusion2063.averageTone(null, 3),
                HeroDiffusion2063.FALLBACK_BG, "averageTone: null sample falls back");
        eq(HeroDiffusion2063.averageTone(
                        new int[] {0xffff0000, 0xff00ff00, 0xff0000ff}, 2),
                0xff7f7f00, "averageTone: count is clamped to the entries read");
        eq(HeroDiffusion2063.averageTone(
                        new int[] {0xffffffff, 0xffffffff, 0xff000000}, 3),
                0xffaaaaaa, "averageTone: white + white + black averages to 170");
        // ---- averageTone, 2.0.64: saturation-weighted ----
        eq(HeroDiffusion2063.averageTone(
                        new int[] {0xffe8f0f0, 0xff2a9d8f}, 2),
                0xff36a295,
                "averageTone: a pale pixel + a teal pixel yields teal, not the pale average");
        eq(HeroDiffusion2063.averageTone(
                        new int[] {0xffffffff, 0xffffffff, 0xffffffff, 0xff2a9d8f}, 4),
                0xff2a9d8f,
                "averageTone: three white pixels + one teal pixel yields the teal (white has no saturation)");
        eq(HeroDiffusion2063.averageTone(
                        new int[] {0xffffffff, 0xff000000}, 2),
                0xff7f7f7f,
                "averageTone: white + black (no saturation anywhere) falls back to the plain average");

        // ---- backgroundTone ---- (2.0.64: mute 70%, darken 65%, clamp 140)
        eq(HeroDiffusion2063.backgroundTone(0xffffffff),
                0xff8c8c8c, "backgroundTone: white mutes+darkens to 140 grey");
        eq(HeroDiffusion2063.backgroundTone(0xff000000),
                0xff000000, "backgroundTone: black stays black");
        eq(HeroDiffusion2063.backgroundTone(0xff6b7683),
                0xff484d53, "backgroundTone: the slate itself darkens, keeps its hue");
        eq(HeroDiffusion2063.backgroundTone(0xffc82828),
                0xff6d2424, "backgroundTone: saturated red stays a strong dark red (was washed out)");
        eq(HeroDiffusion2063.backgroundTone(0xff78b4dc),
                0xff587486, "backgroundTone: bright sky blue stays a clear slate blue (was washed out)");
        eq(HeroDiffusion2063.backgroundTone(0xff36a295),
                0xff30625c, "backgroundTone: a teal poster average stays clearly teal, dark");
        eq(HeroDiffusion2063.backgroundTone(0xfff2e6c9),
                0xff8c877b, "backgroundTone: a pale poster lands at 140 grey, keeps its warm hint");
        int pale = HeroDiffusion2063.backgroundTone(0xfff2e6c9);
        check(((pale >> 16) & 0xff) <= 140
                        && ((pale >> 8) & 0xff) <= 140 && (pale & 0xff) <= 140,
                "backgroundTone: a pale poster never yields a channel above 140");
        check((HeroDiffusion2063.backgroundTone(0xffc82828) & 0xff000000) != 0,
                "backgroundTone: result is always opaque");

        // ---- lerpColor ----
        eq(HeroDiffusion2063.lerpColor(0xff000000, 0xffffffff, 0f),
                0xff000000, "lerpColor: t=0 returns the start colour");
        eq(HeroDiffusion2063.lerpColor(0xff000000, 0xffffffff, 1f),
                0xffffffff, "lerpColor: t=1 returns the end colour");
        eq(HeroDiffusion2063.lerpColor(0xff000000, 0xffffffff, 0.5f),
                0xff808080, "lerpColor: t=0.5 is the midpoint");
        eq(HeroDiffusion2063.lerpColor(0xff112233, 0xff445566, -0.5f),
                0xff112233, "lerpColor: t below 0 clamps to the start colour");
        eq(HeroDiffusion2063.lerpColor(0xff112233, 0xff445566, 1.5f),
                0xff445566, "lerpColor: t above 1 clamps to the end colour");
        eq(HeroDiffusion2063.lerpColor(0xff6b7683, 0xff4e2626, 0.5f),
                0xff5d4e55, "lerpColor: slate to muted red at t=0.5");

        // ---- scrimColors ---- (2.0.64: 8 stops, tone ramps in)
        int[] scrim = HeroDiffusion2063.scrimColors(0xff112233);
        eq(scrim.length, HeroDiffusion2063.SCRIM_STOPS,
                "scrimColors: eight stops");
        check(scrim[0] == 0x00000000 && scrim[1] == 0x00000000
                        && scrim[2] == 0x00000000 && scrim[3] == 0x00000000,
                "scrimColors: first four stops fully transparent (fade only at the bottom)");
        eq(scrim[4], 0x40112233,
                "scrimColors: fifth stop is the page colour at 25% alpha");
        eq(scrim[5], 0x80112233,
                "scrimColors: sixth stop is the page colour at 50% alpha");
        eq(scrim[6], 0xc0112233,
                "scrimColors: seventh stop is the page colour at 75% alpha");
        eq(scrim[7], 0xff112233,
                "scrimColors: last stop is exactly the opaque page colour");
        check(HeroDiffusion2063.scrimColors(0xff6b7683)[7] == 0xff6b7683,
                "scrimColors: the fallback slate ends the fade on the slate");

        // ---- fallback ----
        eq(HeroDiffusion2063.FALLBACK_BG, 0xff6b7683,
                "FALLBACK_BG is the 2.0.61 slate 0xff6b7683");
        eq(HeroDiffusion2063.FALLBACK_BG, HomeRows2061.HOME_SLATE_BG,
                "FALLBACK_BG matches HomeRows2061.HOME_SLATE_BG");

        // ---- HeroSwipe2063: tap vs swipe ----
        check(HeroSwipe2063.isTap(0f, 0f),
                "isTap: no movement is a tap");
        check(HeroSwipe2063.isTap(11f, 0f),
                "isTap: 11dp horizontal is still a tap");
        check(HeroSwipe2063.isTap(0f, 11f),
                "isTap: 11dp vertical is still a tap");
        check(HeroSwipe2063.isTap(8f, 8f),
                "isTap: 8dp on both axes is still a tap");
        check(!HeroSwipe2063.isTap(12f, 0f),
                "isTap: exactly 12dp horizontal is a drag, not a tap");
        check(!HeroSwipe2063.isTap(0f, 13f),
                "isTap: 13dp vertical is a drag, not a tap");
        eq(Math.round(HeroSwipe2063.flingThresholdPxPerSec(2.0f)), 800,
                "flingThresholdPxPerSec: 400dp/s at density 2.0 is 800px/s");
        eq(Math.round(HeroSwipe2063.flingThresholdPxPerSec(3.0f)), 1200,
                "flingThresholdPxPerSec: 400dp/s at density 3.0 is 1200px/s");

        // ---- HeroSwipe2063: targetPage (count 5, pageWidth 1000) ----
        eq(HeroSwipe2063.targetPage(2, 5, 2000, 1000, 0f, 400f), 2,
                "targetPage: no drag, no fling stays on the page");
        eq(HeroSwipe2063.targetPage(2, 5, 2110, 1000, 0f, 400f), 3,
                "targetPage: dragged 110px (> tenth of a page) goes to the next page");
        eq(HeroSwipe2063.targetPage(2, 5, 2090, 1000, 0f, 400f), 2,
                "targetPage: dragged 90px (< tenth of a page) stays");
        eq(HeroSwipe2063.targetPage(2, 5, 1890, 1000, 0f, 400f), 1,
                "targetPage: dragged -110px goes to the previous page");
        eq(HeroSwipe2063.targetPage(2, 5, 1910, 1000, 0f, 400f), 2,
                "targetPage: dragged -90px stays");
        eq(HeroSwipe2063.targetPage(2, 5, 2000, 1000, -500f, 400f), 3,
                "targetPage: left flick (-500px/s) goes to the next page on distance 0");
        eq(HeroSwipe2063.targetPage(2, 5, 2000, 1000, 500f, 400f), 1,
                "targetPage: right flick (+500px/s) goes to the previous page on distance 0");
        eq(HeroSwipe2063.targetPage(2, 5, 2000, 1000, -399f, 400f), 2,
                "targetPage: -399px/s is below the fling threshold, no page change");
        eq(HeroSwipe2063.targetPage(0, 5, 2000, 1000, 500f, 400f), 0,
                "targetPage: right flick on the first page clamps to 0");
        eq(HeroSwipe2063.targetPage(4, 5, 4000, 1000, -500f, 400f), 4,
                "targetPage: left flick on the last page clamps to 4");
        eq(HeroSwipe2063.targetPage(0, 1, 0, 1000, -500f, 400f), 0,
                "targetPage: a single page never changes");
        eq(HeroSwipe2063.targetPage(2, 5, 2000, 0, -500f, 400f), 2,
                "targetPage: pageWidth 0 is a no-op");
        eq(HeroSwipe2063.clampPage(-1, 5), 0,
                "clampPage: -1 clamps to 0");
        eq(HeroSwipe2063.clampPage(5, 5), 4,
                "clampPage: 5 clamps to the last page 4");
        eq(HeroSwipe2063.clampPage(0, 0), 0,
                "clampPage: count 0 yields 0");

        // ---- HeroSwipe2063: auto-advance pause after a manual swipe ----
        check(HeroSwipe2063.shouldPauseAutoAdvance(100000L, 100000L, 24000L),
                "shouldPauseAutoAdvance: just swiped, paused");
        check(HeroSwipe2063.shouldPauseAutoAdvance(123999L, 100000L, 24000L),
                "shouldPauseAutoAdvance: 23999ms after the swipe, still paused");
        check(!HeroSwipe2063.shouldPauseAutoAdvance(124000L, 100000L, 24000L),
                "shouldPauseAutoAdvance: exactly 24000ms after the swipe, pause is over");
        check(!HeroSwipe2063.shouldPauseAutoAdvance(130000L, 100000L, 24000L),
                "shouldPauseAutoAdvance: 30000ms after the swipe, not paused");
        eqL(HeroSwipe2063.resumeDelayMs(true), 24000L,
                "resumeDelayMs: a manual swipe resumes after 24000ms");
        eqL(HeroSwipe2063.resumeDelayMs(false), 12000L,
                "resumeDelayMs: a tap resumes after the normal 12000ms");

        // ---- HeroSwipe2063: slow-rotation constants ----
        eqL(HeroSwipe2063.AUTO_ADVANCE_MS, 12000L,
                "AUTO_ADVANCE_MS is 12000ms (slow rotation, not 8s)");
        eqL(HeroSwipe2063.MANUAL_PAUSE_MS, 24000L,
                "MANUAL_PAUSE_MS is 24000ms (two 12s cycles after a manual swipe)");
        eqL(HeroSwipe2063.AUTO_TRANSITION_MS, 900L,
                "AUTO_TRANSITION_MS is 900ms (slow glide, not a ~250ms snap)");
        check(HeroSwipe2063.PAGE_CHANGE_FRACTION == 0.10f,
                "PAGE_CHANGE_FRACTION is 0.10 (a tenth of a page — a short drag changes the card)");

        // ---- VideoKind2061: hero anime filter ----
        check(VideoKind2061.isAnime(video("动漫", null, null)),
                "isAnime: category 动漫 is anime");
        check(VideoKind2061.isAnime(video("動漫", null, null)),
                "isAnime: category 動漫 (Traditional) is anime");
        check(VideoKind2061.isAnime(video("动画", null, null)),
                "isAnime: category 动画 is anime");
        check(VideoKind2061.isAnime(video("動畫電影", null, null)),
                "isAnime: category 動畫電影 is anime");
        check(VideoKind2061.isAnime(video("日漫", null, null)),
                "isAnime: category 日漫 is anime");
        check(VideoKind2061.isAnime(video("国漫", null, null)),
                "isAnime: category 国漫 is anime");
        check(VideoKind2061.isAnime(video("國漫", null, null)),
                "isAnime: category 國漫 (Traditional) is anime");
        check(VideoKind2061.isAnime(video(null, "动画", null)),
                "isAnime: genre 动画 is anime even with no category");
        check(!VideoKind2061.isAnime(video("电影", null, null)),
                "isAnime: category 电影 is not anime");
        check(!VideoKind2061.isAnime(video("电视剧", null, null)),
                "isAnime: category 电视剧 is not anime");
        check(!VideoKind2061.isAnime(video(null, null, null)),
                "isAnime: no category at all is not anime (fail-open)");
        check(!VideoKind2061.isAnime(null),
                "isAnime: null video is not anime");
        check(!VideoKind2061.isAnime(video("电影", "剧情", "simkl-電影")),
                "isAnime: a movie with a 剧情 genre and a Simkl movie tag is not anime");
        java.util.List<Cms.Video> mixed = new java.util.ArrayList<>();
        mixed.add(video("动漫", null, null));
        mixed.add(video("电影", null, null));
        mixed.add(video("日漫", null, null));
        mixed.add(video("电视剧", null, null));
        java.util.List<Cms.Video> kept = VideoKind2061.filterNonAnime(mixed);
        eq(kept.size(), 2,
                "filterNonAnime: two non-anime titles survive out of four");
        check("电影".equals(kept.get(0).typeName)
                        && "电视剧".equals(kept.get(1).typeName),
                "filterNonAnime: the survivors keep their original order");
        eq(VideoKind2061.filterNonAnime(null).size(), 0,
                "filterNonAnime: null in gives an empty list");
        java.util.List<Cms.Video> allAnime = new java.util.ArrayList<>();
        allAnime.add(video("动漫", null, null));
        allAnime.add(video("动画电影", null, null));
        eq(VideoKind2061.filterNonAnime(allAnime).size(), 0,
                "filterNonAnime: an all-anime list filters to empty (the caller falls back to the original)");

        // ---- TitleTranslator 2.0.63: every home card in Chinese ----
        check(!TitleTranslator.looksForeign("奧德賽"),
                "looksForeign: a Chinese title has nothing to translate");
        check(TitleTranslator.looksForeign("The Odyssey"),
                "looksForeign: an English title needs translation");
        check(TitleTranslator.looksForeign("奧德賽 The Odyssey"),
                "looksForeign: a mixed title needs translation");
        check(!TitleTranslator.looksForeign("12 12 12"),
                "looksForeign: digits only need no translation");
        check(!TitleTranslator.looksForeign(null),
                "looksForeign: null needs no translation");
        check(!TitleTranslator.looksForeign(""),
                "looksForeign: empty needs no translation");

        // Fast path: Chinese titles return untouched without any network.
        check("奧德賽".equals(TitleTranslator.toTraditional("奧德賽")),
                "toTraditional: a Chinese title returns as-is (fast path, no network)");
        check("12 12 12".equals(TitleTranslator.toTraditional("12 12 12")),
                "toTraditional: a numeric title returns as-is");
        check(TitleTranslator.toTraditional(null) == null,
                "toTraditional: null in gives null out");
        check("".equals(TitleTranslator.toTraditional("")),
                "toTraditional: empty in gives empty out");

        // pickTranslatedTitle (pure, canned TMDB JSON).
        check("奧德賽".equals(TmdbClient.pickTranslatedTitle(
                        "{\"results\":[{\"original_title\":\"The Odyssey\",\"title\":\"奧德賽\",\"release_date\":\"2026-07-15\"}]}",
                        "The Odyssey", "movie")),
                "pickTranslatedTitle: The Odyssey (movie) -> 奧德賽");
        check("".equals(TmdbClient.pickTranslatedTitle(
                        "{\"results\":[{\"original_name\":\"The Odyssey\",\"name\":\"The Odyssey\"}]}",
                        "The Odyssey", "tv")),
                "pickTranslatedTitle: a zh title identical to the query is not a translation");
        check("".equals(TmdbClient.pickTranslatedTitle(
                        "{\"results\":[{\"original_title\":\"Troy: The Odyssey\",\"title\":\"奧德賽：戰神傳奇\"}]}",
                        "The Odyssey", "movie")),
                "pickTranslatedTitle: a 60-point partial match is below the 80 gate");
        check("".equals(TmdbClient.pickTranslatedTitle("{\"results\":[]}", "The Odyssey", "movie")),
                "pickTranslatedTitle: empty results give nothing");
        check("".equals(TmdbClient.pickTranslatedTitle("not json", "The Odyssey", "movie")),
                "pickTranslatedTitle: garbage never throws, gives nothing");

        // parseSuggest (pure, canned Douban suggest JSON).
        check("奧德賽".equals(TitleTranslator.parseSuggest(
                        "[{\"title\":\"奧德賽\",\"sub_title\":\"The Odyssey\"}]", "The Odyssey")),
                "parseSuggest: a matching sub-title accepts the Chinese title");
        check(TitleTranslator.parseSuggest(
                        "[{\"title\":\"絕命毒師\",\"sub_title\":\"Breaking Bad\"}]", "The Odyssey")
                        == null,
                "parseSuggest: a mismatched sub-title is rejected");
        check(TitleTranslator.parseSuggest("[]", "The Odyssey") == null,
                "parseSuggest: empty results give null");
        check(TitleTranslator.parseSuggest("garbage", "The Odyssey") == null,
                "parseSuggest: garbage never throws, gives null");
        check(TitleTranslator.parseSuggest(
                        "[{\"title\":\"\",\"sub_title\":\"The Odyssey\"}]", "The Odyssey")
                        == null,
                "parseSuggest: an empty title is rejected");

        // rewriteSearchId (pure): the search key follows the translation.
        check("msearch:奧德賽".equals(
                        TitleTranslator.rewriteSearchId("msearch:The Odyssey", "奧德賽")),
                "rewriteSearchId: an msearch id is rewritten to the Chinese title");
        check("detail:123".equals(
                        TitleTranslator.rewriteSearchId("detail:123", "奧德賽")),
                "rewriteSearchId: a source detail id is never rewritten");
        check(TitleTranslator.rewriteSearchId(null, "奧德賽") == null,
                "rewriteSearchId: null id stays null");
        check("msearch:The Odyssey".equals(
                        TitleTranslator.rewriteSearchId("msearch:The Odyssey", "")),
                "rewriteSearchId: an empty new title leaves the id alone");

        // toTraditionalAll: a Chinese-only batch returns identical, no network.
        java.util.List<String> zhOnly = new java.util.ArrayList<>();
        zhOnly.add("奧德賽");
        zhOnly.add("野獸之心");
        zhOnly.add("12 12 12");
        java.util.List<String> zhOut = TitleTranslator.toTraditionalAll(zhOnly);
        check(zhOut.size() == 3 && "奧德賽".equals(zhOut.get(0))
                        && "野獸之心".equals(zhOut.get(1)) && "12 12 12".equals(zhOut.get(2)),
                "toTraditionalAll: a Chinese-only batch returns identical, in order");
        check(TitleTranslator.toTraditionalAll(null).isEmpty(),
                "toTraditionalAll: null in gives an empty list");
        check(TitleTranslator.toTraditionalAll(new java.util.ArrayList<String>()).isEmpty(),
                "toTraditionalAll: empty in gives an empty list");

        // toTraditionalAll: a 1ms budget cannot finish a network lookup —
        // the batch fails open to the original instead of hanging.
        java.util.List<String> one = new java.util.ArrayList<>();
        one.add("Zqxj Wobblefnord");
        long t0 = System.currentTimeMillis();
        java.util.List<String> oneOut = TitleTranslator.toTraditionalAll(one, 1L);
        long took = System.currentTimeMillis() - t0;
        check(oneOut.size() == 1 && "Zqxj Wobblefnord".equals(oneOut.get(0)),
                "toTraditionalAll: a blown budget fails open to the original title");
        check(took < 5000L,
                "toTraditionalAll: a blown budget returns promptly (took " + took + "ms)");

        // translateItems / translateVideos: fast path only — Chinese
        // titles pass through untouched, nulls never crash.
        java.util.List<TmdbClient.Item> items = new java.util.ArrayList<>();
        TmdbClient.Item it1 = new TmdbClient.Item();
        it1.title = "奧德賽";
        items.add(it1);
        TmdbClient.Item it2 = new TmdbClient.Item();
        it2.title = null;
        items.add(it2);
        items.add(null);
        TitleTranslator.translateItems(items, 5000L);
        check("奧德賽".equals(it1.title),
                "translateItems: a Chinese title is left alone");
        TitleTranslator.translateItems(null, 5000L);
        TitleTranslator.translateItems(new java.util.ArrayList<TmdbClient.Item>(), 5000L);
        java.util.List<Cms.Video> vids = new java.util.ArrayList<>();
        Cms.Video vv = new Cms.Video();
        vv.id = "msearch:奧德賽";
        vv.title = "奧德賽";
        vids.add(vv);
        Cms.Video vn = new Cms.Video();
        vn.title = null;
        vids.add(vn);
        vids.add(null);
        TitleTranslator.translateVideos(vids, 5000L);
        check("奧德賽".equals(vv.title) && "msearch:奧德賽".equals(vv.id),
                "translateVideos: a Chinese title and its msearch id are left alone");
        TitleTranslator.translateVideos(null, 5000L);
        TitleTranslator.translateVideos(new java.util.ArrayList<Cms.Video>(), 5000L);

        System.out.println(passed + " Home2063 checks passed");
    }
}
