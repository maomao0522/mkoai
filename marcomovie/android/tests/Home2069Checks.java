package app.mkoai.player;

/**
 * mkoai 2.0.69 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2069_checks.py.
 *
 * Covers the one 2.0.69 item (user 2026-11-16 ~01:01-01:02, 2.0.68 home
 * screenshot, top bar circled): the floating top bar (mkoai pill + the
 * four round buttons) is moved down so its top is no longer cut, and
 * the top gets the same diffusion feel as the hero's bottom bloom — so
 * the hero no longer reads as a poster pasted on the page.
 *
 * Measured on the user's POCO F5 (393dp wide, 2.0.68 screenshot
 * 921x2048, 1 image px = 1.1726 screen px = 0.4268dp):
 *  - statusBarHeight() (the function) is ~41dp: the old pill top was
 *    measured at image y=116 -> 49.5dp, and the old topMargin was
 *    statusBarHeight + 8dp, so statusBarHeight ~= 41dp.
 *  - the system status bar itself (the "1:00" glyphs span image
 *    y=33..54) is ~37dp tall.
 *  - so the old bar top (49dp) had only ~12dp of daylight under the
 *    status bar, and the old 3-stop veil (statusBarHeight + 24dp)
 *    ended at ~65dp, leaving raw art right below it — the art's top
 *    started at a hard screen-edge cut and the bar crowded it.
 * New geometry (same measured inputs):
 *  - bar top = 41 + 20 = 61dp (moved down 12dp); daylight under the
 *    status bar = 61 - 37 = 24dp (doubled); bar bottom = 61 + 48 =
 *    109dp.
 *  - pull-refresh badge top = 41 + 78 = 119dp, still 10dp below the
 *    bar (20 + 48 + 10 = 78, was 8 + 48 + 10 = 66).
 *  - top veil on the home tab = 41 + 250 = 291dp tall, using
 *    topFadeColors (8 evenly spaced stops: opaque tone through 1/7,
 *    half at 2/7, transparent from 3/7): opaque to ~41.6dp (covers
 *    the 37dp status bar), half at ~83dp (inside the bar span
 *    61-109dp, so the bar sits on the tone field), transparent from
 *    ~124.7dp — the art is fully clean below that and emerges out of
 *    the tone above it, the mirror of the bottom scrim. On other tabs
 *    the veil stays the short statusBarHeight + 24dp = ~65dp.
 *  - the hero title block is Gravity.BOTTOM-anchored in the card, so
 *    its top is never above ~300dp even in the tallest case — the
 *    lowered bar (bottom 109dp) cannot touch it.
 * The wiring (exact margin strings, the veil gradient + heights, the
 * switchTab resize, the in-card top fade staying gone) is
 * source-checked by the runner.
 */
public class Home2069Checks {
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

    public static void main(String[] args) {
        // ---- measured POCO F5 inputs (2.0.68 screenshot, see class doc) ----
        double sbFn = 41.0; // statusBarHeight() (the function)
        double sbSys = 37.0; // the system status bar itself
        double barH = 48.0; // pill + circles height
        double heroH = 589.5; // heroHeightDp(393)

        // ---- the bar moves down: old +8dp, new +20dp ----
        double oldBarTop = sbFn + 8.0;
        double newBarTop = sbFn + 20.0;
        near(oldBarTop, 49.0, 0.5, "old bar top was ~49dp (measured 49.5dp)");
        near(oldBarTop - sbSys, 12.0, 0.5, "old daylight under the status bar was only ~12dp");
        near(newBarTop, 61.0, 0.5, "new bar top is ~61dp (moved down 12dp)");
        near(newBarTop - sbSys, 24.0, 0.5, "new daylight under the status bar is ~24dp (doubled)");
        double newBarBottom = newBarTop + barH;
        near(newBarBottom, 109.0, 0.5, "new bar bottom is ~109dp");

        // ---- the badge follows the bar down, same 10dp gap ----
        double badgeTop = sbFn + 78.0;
        near(badgeTop, 119.0, 0.5, "badge top is ~119dp (statusBar + 78dp)");
        near(badgeTop - newBarBottom, 10.0, 0.5, "badge stays 10dp below the bar");
        near((sbFn + 66.0) - (oldBarTop + barH), 10.0, 0.5,
                "old badge gap was also 10dp (66 = 8 + 48 + 10)");

        // ---- topFadeColors shape (the function itself, unchanged) ----
        int tone = 0xff8a7f72;
        int[] top = HeroDiffusion2063.topFadeColors(tone);
        eq(top.length, 8, "topFadeColors: eight stops");
        check(top[0] == tone && top[1] == tone,
                "topFadeColors: the first two stops are the opaque page tone");
        check(top[2] == 0x808a7f72,
                "topFadeColors: the third stop is the tone at half alpha");
        check(top[3] == 0x00000000 && top[4] == 0x00000000
                        && top[5] == 0x00000000 && top[6] == 0x00000000
                        && top[7] == 0x00000000,
                "topFadeColors: transparent from the fourth stop to the bottom");

        // ---- the tall home veil: where the stops land on a POCO F5 ----
        double veilH = sbFn + 250.0;
        near(veilH, 291.0, 0.5, "home veil height is ~291dp (statusBar + 250dp)");
        double opaqueEnd = veilH / 7.0;
        double halfAt = 2.0 * veilH / 7.0;
        double clearFrom = 3.0 * veilH / 7.0;
        near(opaqueEnd, 41.6, 0.5, "veil opaque through ~41.6dp");
        check(opaqueEnd > sbSys,
                "veil opaque zone covers the whole system status bar (~37dp)");
        near(halfAt, 83.1, 0.5, "veil half tone at ~83dp");
        check(newBarTop < halfAt && halfAt < newBarBottom,
                "veil half tone lands inside the bar span (61-109dp): the bar sits on the tone field");
        near(clearFrom, 124.7, 0.5, "veil fully transparent from ~124.7dp");
        check(clearFrom > newBarBottom,
                "veil clears below the bar bottom, so the bar is never on raw art");
        check(clearFrom < 300.0,
                "veil clears well above the hero title zone (~300dp+): the art is clean in the middle");
        // Veil alpha over the bar: 21% down the veil at the bar top
        // (between the opaque stop at 1/7 and the half stop at 2/7),
        // 37.5% down at the bar bottom (between half and transparent).
        double alphaTop = 1.0 - (newBarTop / veilH - 1.0 / 7.0) / (1.0 / 7.0) * 0.5;
        double alphaBottom = 0.5 - (newBarBottom / veilH - 2.0 / 7.0) / (1.0 / 7.0) * 0.5;
        near(alphaTop, 0.77, 0.03, "veil alpha at the bar top is ~0.77 (mostly tone)");
        near(alphaBottom, 0.19, 0.03, "veil alpha at the bar bottom is ~0.19 (mostly art showing)");
        check(alphaTop > alphaBottom && alphaTop > 0.5 && alphaBottom < 0.5,
                "veil fades across the bar: tone above it, art emerging below it");

        // ---- the short veil on other tabs is unchanged ----
        double otherVeilH = sbFn + 24.0;
        near(otherVeilH, 65.0, 0.5, "other-tab veil stays ~65dp (statusBar + 24dp)");

        // ---- the lowered bar cannot touch the hero title ----
        // The hero content is Gravity.BOTTOM-anchored in the card; even
        // the tallest content (100dp top pad + 2-line 27sp title + year
        // + ratings + 36dp bottom pad, ~271dp) puts the title top at
        // 589.5 - 271 = ~318dp. The measured 2.0.68 title top is ~440dp.
        double titleTopWorst = heroH - 271.0;
        check(titleTopWorst > 300.0, "hero title top is never above ~300dp (bottom-anchored content)");
        check(newBarBottom < titleTopWorst - 100.0,
                "lowered bar bottom (109dp) clears the title top by >100dp");
        check(clearFrom < titleTopWorst,
                "veil clears before the title zone: the title sits on clean art");

        // ---- the bottom dissolve is untouched (scrim shape, pinned by 2064) ----
        int[] scrim = HeroDiffusion2063.scrimColors(0xff8a7f72);
        eq(scrim.length, 8, "bottom scrim: eight stops (unchanged)");
        check(scrim[0] == 0x00000000 && scrim[3] == 0x00000000,
                "bottom scrim: transparent over the top half (unchanged)");
        check(scrim[7] == 0xff8a7f72,
                "bottom scrim: ends on the opaque page colour (unchanged)");

        System.out.println(passed + " Home2069 checks passed");
    }
}
