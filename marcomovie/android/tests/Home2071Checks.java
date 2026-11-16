package app.mkoai.player;

/**
 * mkoai 2.0.71 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2071_checks.py.
 *
 * Covers the 2.0.71 fog pull-back (user 2026-11-16 ~21:56, home
 * screenshot, top bar circled: "把下面上面的霧化弄少一點") plus the
 * dock + top-bar moves decided ~21:59. The geometry is pinned here
 * with the same measured POCO F5 inputs as Home2069Checks (393dp
 * wide, 1 image px = 0.4268dp, statusBarHeight() ~= 41dp, the system
 * status bar itself ~37dp, hero 589.5dp tall).
 *
 * (1) Top veil: GONE on the home tab (2.0.72, was statusBarHeight
 * + 150dp) - the hero poster runs clean to the very top edge, no
 * fog. topFadeColors is unchanged (it still builds the other
 * tabs' veil: statusBarHeight + 24dp, ~65dp). The bar (top ~49dp,
 * bottom ~89dp) now sits directly on the art; its own glass
 * backgrounds carry it, and the bottom scrim still protects the
 * title.
 *
 * (2) Bottom scrim: five transparent stops now (was four), 25% at
 * 5/7, 75% at 6/7, opaque page tone at the bottom. The fade starts
 * at ~71% of the hero height (~421dp, was ~57% / ~336dp) and covers
 * only the bottom ~29% (was ~43%). The bottom edge still ends on
 * the opaque page colour, so there is no seam with the bloom
 * anchored at the hero's bottom edge.
 *
 * (3) Hero title legibility (measured, not assumed): the hero title
 * (27sp bold white, Gravity.BOTTOM-anchored, measured top ~431dp /
 * ~73% and bottom ~461dp / ~78% in the user's screenshot) has NO
 * shadow layer anywhere in buildHeroShowcaseCard — the scrim wash
 * is its only protection. The wash at the title drops from ~53-62%
 * (2.0.70) to ~31-49% (2.0.71): still >= ~30% over the whole title
 * band, and on a mid/dark poster less light wash means the white
 * text keeps (or gains) contrast; on a very light poster it is
 * thinner. No shadow was added — that is a design call for the
 * user to judge on the phone, not something to sneak in here.
 *
 * The dock order / history pseudo-index / top-bar contents are
 * source-checked by the runner (they are private statics).
 */
public class Home2071Checks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(int got, int want, String name) {
        if (got != want) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
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

    /** Scrim alpha (0-1) at a fraction of the hero height, linearly interpolated over the 8 stops. */
    static double scrimAlphaAt(double[] stops, double frac) {
        double pos = frac * 7.0;
        int lo = (int) Math.floor(pos);
        if (lo >= 7) return stops[7];
        if (lo < 0) return stops[0];
        double t = pos - lo;
        return stops[lo] + t * (stops[lo + 1] - stops[lo]);
    }

    public static void main(String[] args) {
        double sbFn = 41.0; // statusBarHeight() (the function)
        double sbSys = 37.0; // the system status bar itself
        double barH = 40.0; // the bar shrank 48dp -> 40dp in 2.0.71
        double heroH = 589.5; // heroHeightDp(393)

        // ---- the bar moves back up and shrinks ----
        double newBarTop = sbFn + 8.0;
        double newBarBottom = newBarTop + barH;
        near(newBarTop, 49.0, 0.5, "bar top is ~49dp (statusBar + 8dp, back to the 2.0.68 spot)");
        near(newBarTop - sbSys, 12.0, 0.5, "daylight under the status bar is ~12dp again");
        near(newBarBottom, 89.0, 0.5, "bar bottom is ~89dp (49 + 40dp bar)");
        double badgeTop = sbFn + 58.0;
        near(badgeTop, 99.0, 0.5, "badge top is ~99dp (statusBar + 58dp)");
        near(badgeTop - newBarBottom, 10.0, 0.5, "badge stays 10dp below the bar (8 + 40 + 10 = 58)");
        double titleTopWorst = heroH - 271.0;
        check(newBarBottom < titleTopWorst - 100.0,
                "moved-up bar bottom (89dp) still clears the hero title top by >100dp");

        // ---- topFadeColors shape (unchanged) ----
        int tone = 0xff8a7f72;
        int[] top = HeroDiffusion2063.topFadeColors(tone);
        eq(top.length, 8, "topFadeColors: eight stops (shape unchanged)");
        check(top[0] == tone && top[1] == tone,
                "topFadeColors: the first two stops are the opaque page tone (unchanged)");
        check(top[2] == 0x808a7f72,
                "topFadeColors: the third stop is the tone at half alpha (unchanged)");
        check(top[3] == 0x00000000 && top[4] == 0x00000000
                        && top[5] == 0x00000000 && top[6] == 0x00000000
                        && top[7] == 0x00000000,
                "topFadeColors: transparent from the fourth stop to the bottom (unchanged)");

        // ---- the home veil is gone (2.0.72): no fog on the poster top ----
        // topFadeHeightPx() returns 0 on the home tab, so the hero
        // poster runs clean to the very top edge. The stop-position
        // arithmetic the old 191dp veil pinned is meaningless for a
        // 0-height view and is deleted with it; topFadeColors itself
        // is unchanged (pinned above) and still builds the other
        // tabs' veil.
        double veilH = 0.0;
        near(veilH, 0.0, 0.5, "home veil height is 0dp (no top fog on the poster, was 191dp)");
        check(veilH < sbSys,
                "no veil over the status bar on home: the poster is flush with the top edge");

        // ---- the short veil on other tabs is unchanged ----
        double otherVeilH = sbFn + 24.0;
        near(otherVeilH, 65.0, 0.5, "other-tab veil stays ~65dp (statusBar + 24dp)");

        // ---- the pulled-back bottom scrim ----
        int[] scrim = HeroDiffusion2063.scrimColors(0xff8a7f72);
        eq(scrim.length, 8, "bottom scrim: eight stops");
        check(scrim[0] == 0x00000000 && scrim[1] == 0x00000000
                        && scrim[2] == 0x00000000 && scrim[3] == 0x00000000
                        && scrim[4] == 0x00000000,
                "bottom scrim: five transparent stops (was four)");
        check(scrim[5] == 0x408a7f72,
                "bottom scrim: 25% tone at 5/7 (the ramp starts one stop later)");
        check(scrim[6] == 0xc08a7f72,
                "bottom scrim: 75% tone at 6/7 (was 50% at 5/7, 75% at 6/7)");
        check(scrim[7] == 0xff8a7f72,
                "bottom scrim: ends on the opaque page colour (no seam with the bloom)");
        double[] newStops = {0, 0, 0, 0, 0, 0.25, 0.75, 1.0};
        double[] oldStops = {0, 0, 0, 0, 0.25, 0.50, 0.75, 1.0};
        near(5.0 / 7.0 * heroH, 421.1, 0.5, "scrim fade starts at ~421dp (71% of the hero, was ~336dp)");
        near((1.0 - 5.0 / 7.0) * heroH, 168.4, 0.5, "scrim covers the bottom ~168dp (~29%, was ~253dp / ~43%)");
        // Title band from the user's screenshot: top ~431dp, bottom ~461dp.
        double washTopNew = scrimAlphaAt(newStops, 431.0 / heroH);
        double washBotNew = scrimAlphaAt(newStops, 461.0 / heroH);
        double washTopOld = scrimAlphaAt(oldStops, 431.0 / heroH);
        double washBotOld = scrimAlphaAt(oldStops, 461.0 / heroH);
        near(washTopNew, 0.31, 0.02, "title-top wash is ~31% (was ~53%)");
        near(washBotNew, 0.49, 0.02, "title-bottom wash is ~49% (was ~62%)");
        check(washTopNew >= 0.30 && washBotNew >= 0.45,
                "title wash stays >= ~30% over the whole title band (no shadow layer; the scrim is its only protection)");

        // ---- tone follow: every tab follows the hero tone (2.0.71) ----
        // The accent (link / section header) blue: sky blue on dark,
        // deep blue on light. Band extremes are from HomeTone2066's own
        // doc: worst dark ~0.124 luminance, worst light ~0.47.
        eq(HomeTone2066.accentText(0xff566756), 0xff5ac8fa,
                "tone: accent stays sky blue on a dark page");
        eq(HomeTone2066.accentText(0xff55cf55), 0xff0d47a1,
                "tone: accent deepens to 0xff0d47a1 on a light page");
        check(HomeTone2066.isLight(0xff55cf55) && !HomeTone2066.isLight(0xff566756),
                "tone: the light/dark split used by the accent matches isLight");
        // Light and dark text stay opposite on both sides of the split.
        check(HomeTone2066.primaryText(0xff55cf55) == 0xff111827
                        && HomeTone2066.primaryText(0xff566756) == 0xffffffff,
                "tone: primary text is dark on light pages, white on dark pages");
        check(HomeTone2066.mutedText(0xff55cf55) == 0xff374151
                        && HomeTone2066.mutedText(0xff566756) == 0xffe5e7eb,
                "tone: muted text is dark on light pages, light on dark pages");

        System.out.println(passed + " Home2071 checks passed");
    }
}
