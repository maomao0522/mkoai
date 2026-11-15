package app.mkoai.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * mkoai 2.0.66 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2066_checks.py.
 *
 * Covers the 2.0.66 Emby-style phone home + quota-hide work:
 *  pageTone (HeroDiffusion2063) — the light-preserving page tone:
 *      exact fixtures, plus the empirical snap band (a colour-cube
 *      sweep: dark &le; ~0.13, light &ge; ~0.47 — the lighten mix
 *      undershoots the 0.55 target for saturated greens, so the band
 *      is stated from the sweep, not the target), and the 3-arg
 *      heroBottomFraction (the hero starts at the screen top now).
 *  HomeTone2066 — the light/dark text + chrome palette: the flip at
 *      0.30, the per-side colours, and the WCAG contrast at both
 *      empirical band extremes (worst dark 0xff566756, worst light
 *      0xff55cf55) plus the dock's selected label on its light pill.
 *  bloomColorsAnchored — light-aware far colour (pageTone for a
 *      light tone, backgroundTone for a dark one — dark heroes
 *      byte-identical to 2.0.65); bloomColors unchanged (pin).
 *  HomeYear2066 — the year rules: rec cutoff 2022 (4y), hero cutoff
 *      2024 (2y); the hard filter drops Titanic 1997 / Truman 1998 /
 *      Interstellar 2014 but keeps unknown-year titles (fail-open);
 *      the hero preference reorders recent-first and never empties.
 *  HeroRatings2066 — the ratings line: "★ 8.4" / "8.4 分" (TMDB),
 *      "豆瓣 9.1 分" (Douban), both combined TMDB-first, clamped to
 *      0–10, "" when there is no score; the cached TMDB vote wins
 *      when the remarks carry none.
 *  QuotaState2066 — the quota-hide state: DeepL quota hides 24h,
 *      YouTube hides until the next midnight Pacific, OMDb until the
 *      next midnight UTC, a transient limit hides for retry_after
 *      (0/negative &rarr; 60s default), and anything without a quota
 *      signal (null error, unknown source, retry_after null on a
 *      transient source) never hides; an expired entry self-clears
 *      and the feature re-tests on the next call.
 */
public class Home2066Checks {
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

    static void eq(long got, long want, String name) {
        if (got != want) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static void seq(String got, String want, String name) {
        if (got == null ? want != null : !got.equals(want)) {
            throw new AssertionError(
                    "FAIL: " + name + " (got \"" + got + "\", want \"" + want + "\")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static void near(double got, double want, double eps, String name) {
        if (Math.abs(got - want) > eps) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static void near(float got, float want, float eps, String name) {
        if (Math.abs(got - want) > eps) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        // ---- pageTone: exact fixtures ----
        eq(HeroDiffusion2063.pageTone(0xfff5f0e6), 0xffe0ddd5,
                "pageTone: an off-white poster stays light (0xffe0ddd5)");
        eq(HeroDiffusion2063.pageTone(0xffe6e0f0), 0xffd4cfdc,
                "pageTone: a light lavender poster stays light (0xffd4cfdc)");
        eq(HeroDiffusion2063.pageTone(0xff0e7a6b), 0xff166b5f,
                "pageTone: a vivid teal poster snaps dark (0xff166b5f)");
        eq(HeroDiffusion2063.pageTone(0xff808080), 0xff626262,
                "pageTone: a mid-grey poster snaps dark (0xff626262)");
        eq(HeroDiffusion2063.pageTone(0xff6b7683), 0xff5a636c,
                "pageTone: the fallback slate stays a dark slate (0xff5a636c)");
        eq(HeroDiffusion2063.pageTone(0xff484d53), 0xff43474b,
                "pageTone: a dark slate stays dark (0xff43474b)");
        eq(HeroDiffusion2063.pageTone(0xff000000), 0xff000000,
                "pageTone: black stays black");
        eq(HeroDiffusion2063.pageTone(0xffffffff), 0xffebebeb,
                "pageTone: white stays near-white (0xffebebeb)");
        eq(HeroDiffusion2063.pageTone(0xffe34b58), 0xffa54049,
                "pageTone: the coral snaps dark (0xffa54049)");
        eq(HeroDiffusion2063.pageTone(0xff3b82f6), 0xff3561aa,
                "pageTone: a sky blue snaps dark (0xff3561aa)");

        // ---- pageTone: the empirical snap band ----
        // Worst cases from a colour-cube sweep: darkest snapped dark
        // 0.124 (grey-green), lightest snapped light 0.472 (pure
        // green). Every page tone lands at L <= 0.13 or L >= 0.47 —
        // far from the 0.30 flip, so the palette never flickers.
        int[] sweep = {
            0xfff5f0e6, 0xffe6e0f0, 0xff0e7a6b, 0xff808080, 0xff6b7683,
            0xff484d53, 0xff000000, 0xffffffff, 0xffe34b58, 0xff3b82f6,
            0xff00e000, 0xfffacc15, 0xff80a080, 0xff22c55e, 0xff8b5cf6,
            0xffec4899, 0xfff97316, 0xff7f1d1d, 0xfffff7ed, 0xff1e3a5f,
        };
        for (int c : sweep) {
            int tone = HeroDiffusion2063.pageTone(c);
            double l = HomeTone2066.relativeLuminance(tone);
            check(l <= 0.13 || l >= 0.47,
                    "pageTone band: 0x" + Integer.toHexString(c)
                            + " lands at L=" + String.format(java.util.Locale.US, "%.3f", l)
                            + " (dark <= 0.13 or light >= 0.47)");
            check(HomeTone2066.isLight(tone) == (l >= HomeTone2066.LIGHT_THRESHOLD),
                    "pageTone band: 0x" + Integer.toHexString(c)
                            + " isLight agrees with its luminance");
        }
        eq(HeroDiffusion2063.pageTone(0xff00e000), 0xff55cf55,
                "pageTone band: a pure green is the worst light case (0xff55cf55, L=0.472)");
        eq(HeroDiffusion2063.pageTone(0xff80a080), 0xff566756,
                "pageTone band: a grey-green is the worst dark case (0xff566756, L=0.124)");

        // ---- HomeTone2066: the flip + per-side colours ----
        check(HomeTone2066.isLight(0xffe0ddd5) && !HomeTone2066.isLight(0xff626262),
                "palette flip: the off-white page is light, the mid-grey page is dark");
        check(HomeTone2066.isLight(0xff55cf55) && !HomeTone2066.isLight(0xff566756),
                "palette flip: the worst light / worst dark pages split at 0.30");
        eq(HomeTone2066.primaryText(0xffe0ddd5), 0xff111827,
                "palette: primary text on a light page is near-black");
        eq(HomeTone2066.primaryText(0xff626262), 0xffffffff,
                "palette: primary text on a dark page is white");
        eq(HomeTone2066.mutedText(0xffe0ddd5), 0xff374151,
                "palette: muted text on a light page is dark slate");
        eq(HomeTone2066.mutedText(0xff626262), 0xffe5e7eb,
                "palette: muted text on a dark page is light grey");
        eq(HomeTone2066.topPillBg(0xffe0ddd5), 0xf2ffffff,
                "palette: the floating top pill is near-white on a light page");
        eq(HomeTone2066.topPillBg(0xff626262), 0xcc222226,
                "palette: the floating top pill is dark glass on a dark page");
        eq(HomeTone2066.dockTopRgb(0xffe0ddd5), 0x00ffffff,
                "palette: the dock pill is white on a light page");
        eq(HomeTone2066.dockTopRgb(0xff626262), 0x001b2421,
                "palette: the dock pill keeps the dark gradient on a dark page");
        eq(HomeTone2066.dockLabel(0xffe0ddd5, true), 0xffc22e3c,
                "palette: the dock's selected label is the darker coral on a light pill");
        eq(HomeTone2066.dockLabel(0xff626262, true), 0xffe34b58,
                "palette: the dock's selected label keeps the mkoai coral on a dark pill");
        eq(HomeTone2066.dockLabel(0xffe0ddd5, false), 0xff1f2937,
                "palette: the dock's unselected label is near-black on a light pill");
        eq(HomeTone2066.dockLabel(0xff626262, false), 0xffe5e7eb,
                "palette: the dock's unselected label is light grey on a dark pill");
        eq(HomeTone2066.dockLensTop(0xffe0ddd5), 0x1f000000,
                "palette: the active-tab lens is dark glass on a light pill");
        eq(HomeTone2066.dockLensTop(0xff626262), 0x38ffffff,
                "palette: the active-tab lens is white glass on a dark pill");
        eq(HomeTone2066.progressTrack(0xffe0ddd5), 0x33000000,
                "palette: the CW progress track is dark on a light page");
        eq(HomeTone2066.progressTrack(0xff626262), 0x33ffffff,
                "palette: the CW progress track is light on a dark page");
        eq(HomeTone2066.PROGRESS_FILL, 0xffe34b58,
                "palette: the CW progress fill is the mkoai coral on both sides");

        // ---- HomeTone2066: WCAG contrast at the empirical extremes ----
        check(HomeTone2066.contrast(0xffffffff, 0xff566756) >= 6.0,
                "contrast: white on the worst dark page is >= 6.0:1 (6.05:1)");
        check(HomeTone2066.contrast(0xff111827, 0xff55cf55) >= 8.5,
                "contrast: near-black on the worst light page is >= 8.5:1 (8.82:1)");
        check(HomeTone2066.contrast(0xffe5e7eb, 0xff566756) >= 4.8,
                "contrast: muted grey on the worst dark page is >= 4.8:1 (4.89:1)");
        check(HomeTone2066.contrast(0xff374151, 0xff55cf55) >= 5.0,
                "contrast: muted slate on the worst light page is >= 5.0:1 (5.13:1)");
        check(HomeTone2066.contrast(0xffffffff, 0xff626262) >= 6.0,
                "contrast: white on the mid-grey page is >= 6.0:1 (6.10:1)");
        check(HomeTone2066.contrast(0xff111827, 0xffe0ddd5) >= 12.0,
                "contrast: near-black on the off-white page is >= 12:1 (13.07:1)");
        check(HomeTone2066.contrast(0xffc22e3c, 0xffffffff) >= 5.5,
                "contrast: the dock's selected coral on the light pill top is >= 5.5:1 (5.60:1)");
        check(HomeTone2066.contrast(0xffc22e3c, 0xfff2f2f2) >= 4.9,
                "contrast: the dock's selected coral on the light pill bottom is >= 4.9:1 (5.00:1)");

        // ---- HomeTone2066: the bottom-edge veil ----
        int[] veil = HomeTone2066.bottomFadeColors(0x060a08);
        eq(veil.length, 3, "bottom veil: three stops");
        eq(veil[0], 0x00060a08, "bottom veil: the top stop is transparent");
        eq(veil[1], 0x90060a08, "bottom veil: the middle stop is 0x90");
        eq(veil[2], 0xe6060a08, "bottom veil: the bottom stop is 0xe6");
        check(HomeTone2066.bottomFadeColors(0x060a08)[0] == 0x00060a08
                        && HomeTone2066.bottomFadeColors(0x060a08)[2] == 0xe6060a08,
                "bottom veil: 0x060a08 reproduces the original 2.0.38 dark veil exactly");
        int[] lightVeil = HomeTone2066.bottomFadeColors(0xffe0ddd5);
        check(lightVeil[0] == 0x00e0ddd5 && lightVeil[2] == 0xe6e0ddd5,
                "bottom veil: a light page gets a light veil (same shape, page colour)");
        int[] masked = HomeTone2066.bottomFadeColors(0xff112233);
        check(masked[0] == 0x00112233 && masked[2] == 0xe6112233,
                "bottom veil: the input alpha is masked off (an opaque tone never makes a solid band)");

        // ---- heroBottomFraction (3-arg: the hero starts at the top) ----
        near(HeroDiffusion2063.heroBottomFraction(0f, 491.25f, 873f),
                0.5627f, 0.002f,
                "heroBottomFraction: a POCO F5-ish rest state lands at ~0.56, not 0.67");
        check(HeroDiffusion2063.heroBottomFraction(0f, 491.25f, 873f) > 0.40f,
                "heroBottomFraction: the rest hero still ends below the old bloom's 0.40 hold");
        eq((int) (HeroDiffusion2063.heroBottomFraction(0f, 420f, 2000f) * 100), 40,
                "heroBottomFraction: a very tall viewport clamps the hold to 0.40");
        eq((int) (HeroDiffusion2063.heroBottomFraction(0f, 491f, 500f) * 100), 90,
                "heroBottomFraction: a very short viewport clamps the hold to 0.90");
        eq((int) (HeroDiffusion2063.heroBottomFraction(0f, 491f, 0f) * 100), 40,
                "heroBottomFraction: a zero viewport never divides, holds 0.40");

        // ---- bloomColorsAnchored: light-aware far colour ----
        int tone = 0xff484d53;
        int far = HeroDiffusion2063.backgroundTone(tone);
        int[] poco = HeroDiffusion2063.bloomColorsAnchored(tone, 0.5627f);
        eq(poco.length, HeroDiffusion2063.ANCHORED_BLOOM_STOPS,
                "anchored bloom: eight stops");
        check(poco[0] == tone && poco[1] == tone && poco[2] == tone && poco[3] == tone,
                "anchored bloom: at the POCO hold the tone is still full at the hero's bottom edge");
        check(poco[4] != tone && poco[5] != tone,
                "anchored bloom: at the POCO hold the fade starts only below the hero");
        eq(poco[7], far,
                "anchored bloom: a dark hero's far colour is still backgroundTone(tone) — byte-identical to 2.0.65");
        int lightTone = 0xffe0ddd5;
        int[] lightBloom = HeroDiffusion2063.bloomColorsAnchored(lightTone, 0.5627f);
        eq(lightBloom[7], HeroDiffusion2063.pageTone(lightTone),
                "anchored bloom: a light hero's far colour is pageTone(tone), not backgroundTone");
        check(lightBloom[7] != lightTone && lightBloom[0] == lightTone,
                "anchored bloom: a light hero holds its tone then fades into a deeper pass of itself");
        check(!HomeTone2066.isLight(
                        HeroDiffusion2063.bloomColorsAnchored(tone, 0.40f)[7]),
                "anchored bloom: a dark hero's far colour stays dark");

        // ---- bloomColors: unchanged (regression pin) ----
        int[] bloom = HeroDiffusion2063.bloomColors(tone);
        eq(bloom.length, HeroDiffusion2063.BLOOM_STOPS,
                "bloomColors unchanged: six stops");
        check(bloom[0] == tone && bloom[1] == tone && bloom[2] == tone,
                "bloomColors unchanged: the tone holds for the top three stops");
        eq(bloom[5], far,
                "bloomColors unchanged: the far colour holds to the bottom");

        // ---- HomeYear2066: cutoffs + parsing ----
        eq(HomeYear2066.recCutoffYear(2026), 2022,
                "year rule: the recommendation cutoff is 2022 (last 4 years)");
        eq(HomeYear2066.heroCutoffYear(2026), 2024,
                "year rule: the hero cutoff is 2024 (last 2 years)");
        eq(HomeYear2066.parseYear("2025"), 2025,
                "year parse: a plain year parses");
        eq(HomeYear2066.parseYear("2025 (共2季)"), 2025,
                "year parse: the season suffix is ignored");
        eq(HomeYear2066.parseYear("2025-11-15"), 2025,
                "year parse: a full date parses to its year");
        eq(HomeYear2066.parseYear("第 2 季"), -1,
                "year parse: no year in the string is -1 (unknown)");
        eq(HomeYear2066.parseYear(""), -1,
                "year parse: an empty string is -1 (unknown)");
        eq(HomeYear2066.parseYear(null), -1,
                "year parse: null is -1 (unknown)");
        eq(HomeYear2066.parseYear("上映 2019 年"), 2019,
                "year parse: the first 19xx/20xx token wins");
        check(HomeYear2066.keepInRecommendations(2022, 2026)
                        && !HomeYear2066.keepInRecommendations(2021, 2026),
                "year rule: 2022 stays in recommendations, 2021 drops");
        check(!HomeYear2066.keepInRecommendations(1997, 2026)
                        && !HomeYear2066.keepInRecommendations(1998, 2026)
                        && !HomeYear2066.keepInRecommendations(2014, 2026),
                "year rule: Titanic 1997 / Truman 1998 / Interstellar 2014 never enter a rec row");
        check(HomeYear2066.keepInRecommendations(-1, 2026),
                "year rule: an unknown year is KEPT in recommendations (fail-open)");
        check(HomeYear2066.isRecentForHero(2024, 2026)
                        && !HomeYear2066.isRecentForHero(2023, 2026),
                "year rule: 2024 is recent for the hero, 2023 is not");
        check(!HomeYear2066.isRecentForHero(-1, 2026),
                "year rule: an unknown year is NOT recent for the hero (filler group)");

        // ---- HomeYear2066: the list operations ----
        HomeYear2066.YearOf<Integer> yearOf = new HomeYear2066.YearOf<Integer>() {
            @Override public int yearOf(Integer y) { return y.intValue(); }
        };
        List<Integer> recs = HomeYear2066.filterRecommendations(
                Arrays.asList(1997, 1998, 2014, 2026, -1, 2022), yearOf, 2026);
        eq(recs.size(), 3,
                "year rule: the rec filter drops the three old classics and keeps 3");
        check(recs.equals(Arrays.asList(2026, -1, 2022)),
                "year rule: the rec filter keeps order and keeps the unknown year");
        List<Integer> hero = HomeYear2066.preferRecentForHero(
                Arrays.asList(2015, 2026, 1997, 2025), yearOf, 2026);
        check(hero.equals(Arrays.asList(2026, 2025, 2015, 1997)),
                "year rule: the hero preference puts recent first, older after, nothing dropped");
        List<Integer> oldHero = HomeYear2066.preferRecentForHero(
                Arrays.asList(2015, 1997), yearOf, 2026);
        check(oldHero.equals(Arrays.asList(2015, 1997)) && !oldHero.isEmpty(),
                "year rule: with no recent candidates the hero keeps the old ones (fail-open, never empty)");
        check(HomeYear2066.preferRecentForHero(
                        new ArrayList<Integer>(), yearOf, 2026).isEmpty(),
                "year rule: an empty hero list stays empty (no crash, no filler invented)");

        // ---- HeroRatings2066 ----
        seq(HeroRatings2066.ratingsLineFromRemarks("★ 8.4"), "TMDB 8.4",
                "hero ratings: a star score reads as TMDB");
        seq(HeroRatings2066.ratingsLineFromRemarks("豆瓣 9.1 分"), "豆瓣 9.1",
                "hero ratings: a Douban score reads as 豆瓣");
        seq(HeroRatings2066.ratingsLineFromRemarks("豆瓣 9.1 分 · ★ 8.4"),
                "TMDB 8.4  ·  豆瓣 9.1",
                "hero ratings: both scores show, TMDB first");
        seq(HeroRatings2066.ratingsLineFromRemarks("8.4 分"), "TMDB 8.4",
                "hero ratings: a bare score reads as TMDB (the AwwratedHome shape)");
        seq(HeroRatings2066.ratingsLineFromRemarks("第 2 季"), "",
                "hero ratings: an episode status carries no score");
        seq(HeroRatings2066.ratingsLineFromRemarks(""), "",
                "hero ratings: empty remarks give an empty line");
        seq(HeroRatings2066.ratingsLineFromRemarks(null), "",
                "hero ratings: null remarks give an empty line");
        seq(HeroRatings2066.ratingsLineFromRemarks("★ 12.7"), "TMDB 10.0",
                "hero ratings: a score above 10 clamps to 10.0");
        seq(HeroRatings2066.ratingsLineFromRemarks("豆瓣 9.1 分"), "豆瓣 9.1",
                "hero ratings: a Douban score is never also counted as TMDB");
        seq(HeroRatings2066.ratingsLine("豆瓣 9.1 分", 8.4), "TMDB 8.4  ·  豆瓣 9.1",
                "hero ratings: the cached TMDB vote is prepended when the remarks carry only Douban");
        seq(HeroRatings2066.ratingsLine("★ 8.4", 8.4), "TMDB 8.4",
                "hero ratings: the remarks line is kept when it already carries the TMDB score");
        seq(HeroRatings2066.ratingsLine("", 7.5), "TMDB 7.5",
                "hero ratings: a cached vote alone still shows");
        seq(HeroRatings2066.ratingsLine("第 2 季", 0), "",
                "hero ratings: no vote and no score in the remarks hides the whole row");
        seq(HeroRatings2066.ratingsLine("豆瓣 9.1 分", 0), "豆瓣 9.1",
                "hero ratings: a zero cached vote does not erase the Douban score");

        // ---- QuotaState2066 ----
        // 2026-11-15 12:00 UTC (a fixed instant, so the midnights are exact).
        long now = 1794744000000L;
        eq(QuotaState2066.classifyHideUntil("deepl_quota_exhausted", null, null, now),
                1794830400000L,
                "quota: a DeepL quota hides for 24h (now + 24h)");
        eq(QuotaState2066.classifyHideUntil("upstream_rate_limited", "youtube", null, now),
                1794816000000L,
                "quota: a YouTube quota hides until the next midnight Pacific (20h away)");
        eq(QuotaState2066.classifyHideUntil("upstream_rate_limited", "omdb", null, now),
                1794787200000L,
                "quota: an OMDb quota hides until the next midnight UTC (12h away)");
        eq(QuotaState2066.classifyHideUntil("upstream_rate_limited", "trakt", 120, now),
                1794744120000L,
                "quota: a transient limit hides for its retry_after (120s)");
        eq(QuotaState2066.classifyHideUntil("upstream_rate_limited", "trakt", 0, now),
                1794744060000L,
                "quota: a retry_after of 0 hides for the 60s default, not forever");
        eq(QuotaState2066.classifyHideUntil("upstream_rate_limited", "trakt", null, now),
                -1L,
                "quota: a transient limit with no retry_after never hides (fail-open)");
        eq(QuotaState2066.classifyHideUntil(null, null, null, now),
                -1L,
                "quota: no error at all (a network failure) never hides");
        eq(QuotaState2066.classifyHideUntil("some_other_error", null, null, now),
                -1L,
                "quota: a non-quota error never hides");
        eq(QuotaState2066.nextMidnightPacific(now), 1794816000000L,
                "quota: next midnight Pacific is 2026-11-16 08:00 UTC");
        eq(QuotaState2066.nextMidnightUtc(now), 1794787200000L,
                "quota: next midnight UTC is 2026-11-16 00:00 UTC");
        Map<String, Long> map = new HashMap<String, Long>();
        QuotaState2066.Store store = QuotaState2066.mapStore(map);
        eq(QuotaState2066.record(store, QuotaState2066.TRANSLATE,
                        "deepl_quota_exhausted", null, null, now),
                1794830400000L,
                "quota: recording a DeepL quota returns the stored hideUntil");
        check(QuotaState2066.isHidden(store, QuotaState2066.TRANSLATE, now + 3600000L),
                "quota: the feature stays hidden an hour later");
        check(!QuotaState2066.isHidden(store, QuotaState2066.TRANSLATE,
                        1794830400000L + 1),
                "quota: the feature returns after 24h (expiry is the re-test)");
        eq(QuotaState2066.hideUntil(store, QuotaState2066.TRANSLATE), 0L,
                "quota: an expired entry self-clears from the store");
        eq(QuotaState2066.record(store, QuotaState2066.TRAILER, null, null, null, now),
                -1L,
                "quota: recording a network failure stores nothing (-1)");
        check(!QuotaState2066.isHidden(store, QuotaState2066.TRAILER, now + 1000L),
                "quota: a feature with nothing recorded is never hidden");
        seq(QuotaState2066.key(QuotaState2066.TRANSLATE), "quota_hide_translate",
                "quota: the storage key is quota_hide_<feature>");
        check(QuotaState2066.FEATURES.length == 8,
                "quota: eight features are tracked");
        boolean hasSubtitle = false;
        for (String f : QuotaState2066.FEATURES) {
            if (QuotaState2066.SUBTITLE.equals(f)) hasSubtitle = true;
        }
        check(hasSubtitle,
                "quota: the subtitle slot exists for the future Worker quota marker");

        System.out.println(passed + " Home2066 checks passed");
    }
}
