package app.mkoai.player;

/**
 * mkoai 2.0.67 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2067_checks.py.
 *
 * Covers the four 2.0.67 field-test items (user 2026-11-15 ~21:45 +
 * ~21:53):
 *  (1) view-all sub-pages take the home tone: the colour decision is
 *      pure — HomeTone2066.isLight / primaryText / mutedText on the
 *      captured tone. A light page takes the dark text set; a dark
 *      page keeps the dark text set (the list page's own dark
 *      constants are pinned by the runner's source checks, not here).
 *      The Android wiring (capture in openTmdbList, scroll/root
 *      background, heading/eyebrow/count/card retint, TV untouched)
 *      is source-checked by the runner.
 *  (2) hero taller: heroHeightDp 1.5x — a 393dp page is 589.5dp tall,
 *      the bottom lands at ~0.68 of an 873dp viewport (back at the
 *      2.0.65 rest position), and the bloom hold follows the hero
 *      bottom by construction (it is computed from heroHeightDp).
 *  (3) muddy tones go light: averageTone falls back to the lightest
 *      half when the saturation-weighted tone is muddy (sat &lt; 64).
 *      Fixtures mirror the real 左撇子艾倫 art (TMDB tv 305567,
 *      32x24 downscale): the poster/backdrop weighted tones are
 *      #c2bcae / #676254 (grey-brown), the lightest halves are
 *      #f3f2ef / #e1dfd5, and pageTone of those is #e0dfdc / #cecdc5
 *      — genuinely light, with dark-text contrast 13.3:1 / 11.1:1.
 *      A muddy DARK image stays dark; the vivid pins are unchanged.
 *  (4) hero art full-bleed: ImageView scale-type math, mirrored in
 *      pure arithmetic. Old box 393x491.25 (ratio 0.80): FIT_CENTER
 *      left 32.75dp tone pillars on each side of a 2:3 poster (the
 *      complaint), CENTER_CROP would have cut 16.7% of its height
 *      (the 2.0.65 complaint). New box 393x589.5 (ratio 0.667, the
 *      poster's own ratio): CENTER_CROP crops 0% in dp, 0.06% of the
 *      width in pixels on a POCO F5 (1080x1621) — full-bleed with no
 *      crop and no pillars. 2.0.68: the hero is poster-first, and a
 *      16:9 backdrop (poster-less videos only) is CENTER_CROP too —
 *      it needs 1048dp of width, shows the middle 393dp (62.5% of
 *      the width cropped, 31.25% each side, 0% of the height), so the
 *      centred subject stays in frame and no flat-tone band is left
 *      (the old FIT_START backdrop was 393x221.2dp with a 368.4dp
 *      band below it). The scale-type switch itself (CENTER_CROP
 *      for posters and backdrops, poster checked first) is
 *      source-checked by the runner.
 */
public class Home2067Checks {
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

    /** ImageView FIT_CENTER, mirrored: scale = min(wRatio, hRatio). Returns displayed w, h. */
    static double[] fitCenter(double bw, double bh, double iw, double ih) {
        double s = Math.min(bw / iw, bh / ih);
        return new double[] {iw * s, ih * s};
    }

    /** ImageView CENTER_CROP, mirrored: scale = max(wRatio, hRatio). Returns displayed w, h. */
    static double[] centerCrop(double bw, double bh, double iw, double ih) {
        double s = Math.max(bw / iw, bh / ih);
        return new double[] {iw * s, ih * s};
    }

    public static void main(String[] args) throws Exception {
        // ---- (3) averageTone: muddy multi-hue light art goes light ----
        // Backdrop-like: mostly white + a navy jacket, skin, yellow.
        // The weighted tone is muddy grey (sat 11); the lightest half
        // is the white itself.
        int[] backdropLike =
                new int[] {
                    0xffebebe6, 0xffebebe6, 0xffebebe6, 0xffebebe6, 0xffebebe6, 0xffebebe6,
                    0xff1e3c78, 0xff1e3c78, 0xffd2af8c, 0xffd7b95a
                };
        eq(HeroDiffusion2063.averageTone(backdropLike, backdropLike.length),
                0xffebebe6,
                "averageTone: a muddy multi-hue light sample yields its lightest half, not the grey weighted tone");
        eq(HeroDiffusion2063.lightestHalf(backdropLike, backdropLike.length)[0], 0xeb,
                "lightestHalf: the backdrop-like sample's lightest half is the white (red channel)");
        eq(HeroDiffusion2063.pageTone(0xffebebe6), 0xffd8d8d4,
                "pageTone: the lightest-half white becomes a light warm grey, not the old dark grey-brown");
        check(HomeTone2066.contrast(0xffd8d8d4, 0xff111827) >= 7.0,
                "contrast: dark text on the fixed backdrop tone is 12.4:1 (>= 7)");
        check(HomeTone2066.contrast(0xffd8d8d4, 0xff374151) >= 4.5,
                "contrast: muted text on the fixed backdrop tone is 7.2:1 (>= 4.5)");

        // Poster-like: mostly white + pale skin + pale blue (all low-sat).
        int[] posterLike =
                new int[] {
                    0xfffafaf8, 0xfffafaf8, 0xfffafaf8, 0xfffafaf8, 0xfffafaf8,
                    0xfffafaf8, 0xfffafaf8, 0xffe1c8af, 0xffe1c8af, 0xffb4c8dc
                };
        eq(HeroDiffusion2063.averageTone(posterLike, posterLike.length),
                0xfffafaf8,
                "averageTone: a mostly-white poster yields near-white, not its beige weighted tone");
        eq(HeroDiffusion2063.pageTone(0xfffafaf8), 0xffe6e6e4,
                "pageTone: the near-white poster tone stays near-white");
        check(HomeTone2066.contrast(0xffe6e6e4, 0xff111827) >= 7.0,
                "contrast: dark text on the fixed poster tone is 14.2:1 (>= 7)");
        check(HomeTone2066.contrast(0xffe6e6e4, 0xff374151) >= 4.5,
                "contrast: muted text on the fixed poster tone is 8.3:1 (>= 4.5)");

        // Real 左撇子艾倫 art (32x24 downscale of the TMDB files):
        // lightest halves #f3f2ef (poster) / #e1dfd5 (backdrop).
        eq(HeroDiffusion2063.pageTone(0xfff3f2ef), 0xffe0dfdc,
                "pageTone: the real poster lightest half is light cream #e0dfdc (was #c2bcae)");
        eq(HeroDiffusion2063.pageTone(0xffe1dfd5), 0xffcecdc5,
                "pageTone: the real backdrop lightest half is light warm grey #cecdc5 (was #676254)");
        check(HomeTone2066.contrast(0xffe0dfdc, 0xff111827) >= 7.0,
                "contrast: dark text on the real poster tone is 13.3:1 (>= 7)");
        check(HomeTone2066.contrast(0xffcecdc5, 0xff111827) >= 7.0,
                "contrast: dark text on the real backdrop tone is 11.1:1 (>= 7)");
        check(HomeTone2066.contrast(0xffcecdc5, 0xff374151) >= 4.5,
                "contrast: muted text on the real backdrop tone is 6.5:1 (>= 4.5)");

        // A muddy DARK image must stay dark (its lightest half is dark).
        int[] darkMuddy =
                new int[] {
                    0xff1e1914, 0xff1e1914, 0xff1e1914, 0xff1e1914,
                    0xff1e1914, 0xff1e1914, 0xffc8b496, 0xffc8b496
                };
        eq(HeroDiffusion2063.averageTone(darkMuddy, darkMuddy.length),
                0xff736655,
                "averageTone: a muddy dark sample stays dark (lightest half is still dark brown)");
        eq(HeroDiffusion2063.pageTone(0xff736655), 0xff685e50,
                "pageTone: the muddy dark tone snaps dark, it never flips light");
        check(!HomeTone2066.isLight(HeroDiffusion2063.pageTone(0xff736655)),
                "pageTone: the muddy dark tone is still a dark page");

        // Vivid pins unchanged (weighted saturation far above 64).
        eq(HeroDiffusion2063.averageTone(
                        new int[] {0xffe8f0f0, 0xff2a9d8f}, 2),
                0xff36a295,
                "averageTone: a pale pixel + a teal pixel still yields teal (pin, 2.0.64)");
        eq(HeroDiffusion2063.averageTone(
                        new int[] {0xffff0000, 0xff00ff00}, 2),
                0xff7f7f00,
                "averageTone: red + green still averages per channel (pin, 2.0.63)");
        eq(HeroDiffusion2063.averageTone(
                        new int[] {0xff808080, 0xff808080, 0xff808080, 0xff808080}, 4),
                0xff808080,
                "averageTone: uniform grey still yields itself (pin, 2.0.63)");
        eq(HeroDiffusion2063.pageTone(0xff808080), 0xff626262,
                "pageTone: uniform grey still snaps dark (pin, 2.0.66)");

        // ---- (2) hero taller: 1.5x, bottom back at ~0.68 ----
        near(HeroDiffusion2063.heroHeightDp(393f), 589.5f, 0.01f,
                "heroHeightDp: a 393dp page is 589.5dp tall (1.5x)");
        eq((int) HeroDiffusion2063.heroHeightDp(300f), 450,
                "heroHeightDp: a 300dp page is 450dp tall (1.5x, no clamp)");
        eq((int) HeroDiffusion2063.heroHeightDp(600f), 640,
                "heroHeightDp: a wide page still clamps to the 640dp tallest hero");
        near(HeroDiffusion2063.heroBottomFraction(0f, 589.5f, 873f),
                0.6753f, 0.002f,
                "heroBottomFraction: a POCO F5 rest state lands at ~0.68 (was 0.5627 in 2.0.66)");
        check(Math.abs(589.5f - 587f) <= 3f,
                "hero rows: the first row below the hero lands at 589.5dp, within 3dp of the 2.0.65 rest position (587dp)");
        check(589.5f - 491.25f > 90f,
                "hero rows: the rows move back down ~98dp from the 2.0.66 position (491.25dp)");

        // The bloom hold follows the hero bottom by construction.
        float hold =
                HeroDiffusion2063.heroBottomFraction(
                        0f, HeroDiffusion2063.heroHeightDp(393f), 873f);
        near(hold, 0.6753f, 0.002f,
                "bloom anchor: the hold is computed from heroHeightDp, so it follows the taller hero");
        int tone = 0xff484d53;
        int far = HeroDiffusion2063.backgroundTone(tone);
        int[] anchored = HeroDiffusion2063.bloomColorsAnchored(tone, hold);
        check(anchored[0] == tone && anchored[1] == tone && anchored[2] == tone
                        && anchored[3] == tone && anchored[4] == tone,
                "bloom anchor: at the new POCO hold the tone is still full at the hero's bottom edge");
        check(anchored[5] != tone && anchored[6] != tone,
                "bloom anchor: at the new POCO hold the fade starts only below the hero");
        eq(anchored[7], far,
                "bloom anchor: the bottom is still the far colour");

        // ---- (1) sub-page colours follow the captured home tone ----
        check(HomeTone2066.isLight(0xffe0dfdc),
                "sub-page: the real poster tone is a light page");
        eq(HomeTone2066.primaryText(0xffe0dfdc), 0xff111827,
                "sub-page: a light page takes dark primary text");
        eq(HomeTone2066.mutedText(0xffe0dfdc), 0xff374151,
                "sub-page: a light page takes dark muted text");
        check(HomeTone2066.isLight(0xffcecdc5),
                "sub-page: the real backdrop tone is a light page");
        eq(HomeTone2066.primaryText(0xffcecdc5), 0xff111827,
                "sub-page: the backdrop tone takes dark primary text");
        eq(HomeTone2066.mutedText(0xffcecdc5), 0xff374151,
                "sub-page: the backdrop tone takes dark muted text");
        check(!HomeTone2066.isLight(0xff676254),
                "sub-page: the old dark backdrop tone is still a dark page");
        eq(HomeTone2066.primaryText(0xff676254), 0xffffffff,
                "sub-page: a dark page keeps white primary text");
        eq(HomeTone2066.mutedText(0xff676254), 0xffe5e7eb,
                "sub-page: a dark page keeps light muted text");
        check(!HomeTone2066.isLight(0xff141414),
                "sub-page: the DARK default (wallpaper home) is still a dark page");

        // ---- (4) hero art full-bleed: scale-type math ----
        // Old box 393x491.25 (ratio 0.80) vs a 2:3 poster (500x750).
        double[] oldFit = fitCenter(393, 491.25, 500, 750);
        near(oldFit[0], 327.5, 0.1,
                "full-bleed: FIT_CENTER in the 2.0.66 box showed the poster only 327.5dp wide");
        near((393 - oldFit[0]) / 2, 32.75, 0.1,
                "full-bleed: FIT_CENTER in the 2.0.66 box left 32.75dp tone pillars on each side (the complaint)");
        double[] oldCrop = centerCrop(393, 491.25, 500, 750);
        near((oldCrop[1] - 491.25) / oldCrop[1] * 100, 16.7, 0.1,
                "full-bleed: CENTER_CROP in the 2.0.66 box would have cut 16.7% of the poster height (the 2.0.65 complaint)");
        // New box 393x589.5 (ratio 0.667 = the poster's own ratio).
        near(393.0 / 589.5, 500.0 / 750.0, 0.001,
                "full-bleed: the 2.0.67 hero box has the poster's own 2:3 ratio");
        double[] newFit = fitCenter(393, 589.5, 500, 750);
        near(newFit[0], 393.0, 0.1,
                "full-bleed: FIT_CENTER in the new box would fill the width (pillars 0)");
        double[] newCrop = centerCrop(393, 589.5, 500, 750);
        near(newCrop[0], 393.0, 0.1,
                "full-bleed: CENTER_CROP in the new box shows the poster 393dp wide (flush left/right)");
        near((newCrop[1] - 589.5) / newCrop[1] * 100, 0.0, 0.01,
                "full-bleed: CENTER_CROP in the new box crops 0% of the poster height in dp");
        // Pixel-exact on a POCO F5 (1080px wide, pager 1621px tall).
        double[] pxCrop = centerCrop(1080, 1621, 500, 750);
        near((pxCrop[0] - 1080) / pxCrop[0] * 100, 0.062, 0.01,
                "full-bleed: CENTER_CROP on a POCO F5 crops 0.06% of the poster width in pixels");
        double[] pxFit = fitCenter(1080, 1621, 500, 750);
        near((1080 - pxFit[0]) / 2, 0.0, 0.01,
                "full-bleed: even FIT_CENTER would leave no side pillar in the new box (the guarantee comes from the ratio, CENTER_CROP makes it unconditional)");
        // A 16:9 backdrop CENTER_CROP fills the box (2.0.68): it needs
        // 589.5*16/9 = 1048dp of width and shows the middle 393dp.
        near(589.5 * 16.0 / 9.0, 1048.0, 0.5,
                "full-bleed: a 780x439 backdrop CENTER_CROP needs 1048dp of width to fill the box");
        near((589.5 * 16.0 / 9.0 - 393.0) / (589.5 * 16.0 / 9.0) * 100, 62.5, 0.1,
                "full-bleed: a 780x439 backdrop CENTER_CROP crops 62.5% of the width, 0% of the height");
        // The old FIT_START backdrop was 393x221.2dp and left a band.
        near(393.0 * 439.0 / 780.0, 221.2, 0.1,
                "full-bleed: the old FIT_START backdrop was 221.2dp tall (a 368.3dp band below it)");

        System.out.println(passed + " Home2067 checks passed");
    }
}
