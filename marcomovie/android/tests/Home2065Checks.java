package app.mkoai.player;

/**
 * mkoai 2.0.65 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2065_checks.py.
 *
 * Covers the 2.0.65 home rest-state diffusion (HeroDiffusion2063):
 *  heroHeightDp — the same tall/short clamps the carousel uses
 *      (420dp shortest, 640dp tallest, 1.25x the page width between)
 *  heroBottomFraction — where the hero's bottom edge lands in the
 *      viewport at rest (mkoai 2.0.66: the hero starts at the screen
 *      top now, so the fraction takes only (heroTopDp, heroDp,
 *      viewportDp); a POCO F5-ish phone lands at ~0.56), clamped to
 *      0.40..0.90. A POCO F5-ish phone (393dp wide, 873dp tall)
 *      lands at ~0.56 — the hero ends well below the old bloom's
 *      0.40 hold, which is the seam the rest state showed.
 *  bloomColorsAnchored — 8 stops: the tone holds at full strength
 *      down to the hold fraction (the hero's bottom edge at rest),
 *      then fades outward into the far colour (backgroundTone of the
 *      tone). The far colour is the same hue, deeper and greyer —
 *      never grey and never black. A hold of 0.40 is the same shape
 *      as the old bloomColors.
 *  topFadeColors — 8 stops: the page tone over the top of the hero
 *      artwork (opaque through the first ~14%, half at ~28%,
 *      transparent from ~43% down), the mirror of the bottom scrim,
 *      so the top bar melts into the hero instead of ending at a line.
 *  bloomColors — unchanged (regression pin: 6 stops, tone held for
 *      the top three, far colour at the bottom).
 * Covers the 2.0.65 Material switch constants (MaterialSwitch2065):
 *  52x32dp track, 24dp thumb on a 4dp pad (the thumb fits the track
 *  exactly: 24 + 2x4 = 32), ON track the mkoai coral #E34B58, OFF
 *  track a solid mid-grey.
 */
public class Home2065Checks {
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

    static void near(float got, float want, float eps, String name) {
        if (Math.abs(got - want) > eps) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        // ---- heroHeightDp ----
        near(HeroDiffusion2063.heroHeightDp(393f), 491.25f, 0.01f,
                "heroHeightDp: a 393dp page is 491.25dp tall (1.25x)");
        eq((int) HeroDiffusion2063.heroHeightDp(300f), 420,
                "heroHeightDp: a narrow page clamps to the 420dp shortest hero");
        eq((int) HeroDiffusion2063.heroHeightDp(600f), 640,
                "heroHeightDp: a wide page clamps to the 640dp tallest hero");

        // ---- heroBottomFraction ----
        // mkoai 2.0.66: the 4-arg form is gone with the flat top bar —
        // the hero starts at the screen top now, so the fraction takes
        // only (heroTopDp, heroDp, viewportDp). A POCO F5-ish phone
        // (393dp wide, 873dp tall): hero top 0 + hero 491.25 = 491.25dp
        // over an 873dp viewport.
        near(HeroDiffusion2063.heroBottomFraction(0f, 491.25f, 873f),
                0.5627f, 0.002f,
                "heroBottomFraction: a POCO F5-ish rest state lands at ~0.56, not 0.40");
        check(HeroDiffusion2063.heroBottomFraction(0f, 491.25f, 873f) > 0.40f,
                "heroBottomFraction: the rest hero ends below the old bloom's 0.40 hold (the seam)");
        eq((int) (HeroDiffusion2063.heroBottomFraction(0f, 420f, 2000f) * 100), 40,
                "heroBottomFraction: a very tall viewport clamps the hold to 0.40");
        eq((int) (HeroDiffusion2063.heroBottomFraction(0f, 491f, 500f) * 100), 90,
                "heroBottomFraction: a very short viewport clamps the hold to 0.90");
        eq((int) (HeroDiffusion2063.heroBottomFraction(0f, 491f, 0f) * 100), 40,
                "heroBottomFraction: a zero viewport never divides, holds 0.40");

        // ---- bloomColorsAnchored: hold 0.40 (the old shape) ----
        int tone = 0xff484d53;
        int far = HeroDiffusion2063.backgroundTone(tone);
        int[] anchored = HeroDiffusion2063.bloomColorsAnchored(tone, 0.40f);
        eq(anchored.length, HeroDiffusion2063.ANCHORED_BLOOM_STOPS,
                "bloomColorsAnchored: eight stops");
        check(anchored[0] == tone && anchored[1] == tone && anchored[2] == tone,
                "bloomColorsAnchored: the tone holds for the stops above the hold");
        eq(anchored[7], far,
                "bloomColorsAnchored: the last stop is the far colour");
        eq(anchored[7], HeroDiffusion2063.backgroundTone(tone),
                "bloomColorsAnchored: the far colour is backgroundTone(tone) by definition");
        eq(anchored[3],
                HeroDiffusion2063.lerpColor(tone, far, (3f / 7f - 0.40f) / (1f - 0.40f)),
                "bloomColorsAnchored: the first faded stop is the lerp by definition");
        check(anchored[3] != tone && anchored[3] != far,
                "bloomColorsAnchored: the first faded stop is between tone and far, not a jump");
        int[] black = HeroDiffusion2063.bloomColorsAnchored(0xff000000, 0.67f);
        check(black[0] == 0xff000000 && black[4] == 0xff000000 && black[7] == 0xff000000,
                "bloomColorsAnchored: a black tone blooms to black everywhere");

        // ---- bloomColorsAnchored: the POCO hold (~0.56, 2.0.66) ----
        // The hero starts at the screen top now, so the rest hold is
        // 491.25/873 ≈ 0.5627 (was 0.6727 with the flat bar above it).
        int[] poco = HeroDiffusion2063.bloomColorsAnchored(tone, 0.5627f);
        check(poco[0] == tone && poco[1] == tone && poco[2] == tone
                        && poco[3] == tone,
                "bloomColorsAnchored: at the POCO hold the tone is still full at the hero's bottom edge");
        check(poco[4] != tone && poco[5] != tone,
                "bloomColorsAnchored: at the POCO hold the fade starts only below the hero");
        eq(poco[7], far,
                "bloomColorsAnchored: at the POCO hold the bottom is still the far colour");

        // ---- bloomColorsAnchored: hold clamps ----
        int[] lowHold = HeroDiffusion2063.bloomColorsAnchored(tone, 0.10f);
        check(lowHold[2] == tone && lowHold[3] != tone,
                "bloomColorsAnchored: a hold below 0.40 clamps to 0.40");
        int[] highHold = HeroDiffusion2063.bloomColorsAnchored(tone, 1.50f);
        check(highHold[6] == tone && highHold[7] == far,
                "bloomColorsAnchored: a hold above 0.90 clamps to 0.90");
        int[] teal = HeroDiffusion2063.bloomColorsAnchored(0xff30625c, 0.67f);
        eq(teal[7], 0xff253c39,
                "bloomColorsAnchored: a teal tone fades into a deeper teal, never grey or black");
        check(((teal[6] >> 8) & 0xff) > ((teal[6] >> 16) & 0xff)
                        && ((teal[6]) & 0xff) > ((teal[6] >> 16) & 0xff),
                "bloomColorsAnchored: the teal fade stays in the same hue family (green/blue above red)");

        // ---- topFadeColors ----
        int[] top = HeroDiffusion2063.topFadeColors(tone);
        eq(top.length, HeroDiffusion2063.ANCHORED_BLOOM_STOPS,
                "topFadeColors: eight stops");
        eq(top[0], tone,
                "topFadeColors: the first stop is the page tone itself (the bar's colour)");
        eq(top[1], tone,
                "topFadeColors: the second stop is still the page tone");
        eq(top[2], 0x80484d53,
                "topFadeColors: the third stop is the tone at half alpha");
        eq(top[2] & 0x00ffffff, tone & 0x00ffffff,
                "topFadeColors: the fade keeps the tone's hue, only the alpha falls");
        check(top[3] == 0x00000000 && top[4] == 0x00000000 && top[7] == 0x00000000,
                "topFadeColors: transparent from the fourth stop to the bottom");
        int[] topSlate = HeroDiffusion2063.topFadeColors(0xff6b7683);
        check(topSlate[0] == 0xff6b7683 && topSlate[2] == 0x806b7683
                        && topSlate[7] == 0x00000000,
                "topFadeColors: the slate tone fades the same way");
        int[] topBlack = HeroDiffusion2063.topFadeColors(0xff000000);
        check(topBlack[0] == 0xff000000 && topBlack[2] == 0x80000000
                        && topBlack[7] == 0x00000000,
                "topFadeColors: a black tone fades to transparent, never a solid band");

        // ---- bloomColors: unchanged (regression pin) ----
        int[] bloom = HeroDiffusion2063.bloomColors(tone);
        eq(bloom.length, HeroDiffusion2063.BLOOM_STOPS,
                "bloomColors unchanged: six stops");
        check(bloom[0] == tone && bloom[1] == tone && bloom[2] == tone,
                "bloomColors unchanged: the tone holds for the top three stops");
        eq(bloom[5], far,
                "bloomColors unchanged: the far colour holds to the bottom");

        // ---- MaterialSwitch2065 constants ----
        near(MaterialSwitch2065.TRACK_WIDTH_DP, 52f, 0.01f,
                "Material switch: 52dp wide track");
        near(MaterialSwitch2065.TRACK_HEIGHT_DP, 32f, 0.01f,
                "Material switch: 32dp tall track");
        near(MaterialSwitch2065.THUMB_DP, 24f, 0.01f,
                "Material switch: 24dp thumb");
        near(MaterialSwitch2065.THUMB_PAD_DP, 4f, 0.01f,
                "Material switch: 4dp thumb pad");
        check(Math.abs(MaterialSwitch2065.THUMB_DP
                        + 2f * MaterialSwitch2065.THUMB_PAD_DP
                        - MaterialSwitch2065.TRACK_HEIGHT_DP) < 0.01f,
                "Material switch: the thumb fits the track exactly (24 + 2x4 = 32)");
        check(MaterialSwitch2065.TRACK_WIDTH_DP - MaterialSwitch2065.THUMB_DP
                        - 2f * MaterialSwitch2065.THUMB_PAD_DP > 0f,
                "Material switch: the thumb has room to travel (52 - 24 - 8 = 20dp)");
        eq(MaterialSwitch2065.TRACK_ON, 0xffe34b58,
                "Material switch: ON track is the mkoai coral #E34B58");
        eq(MaterialSwitch2065.TRACK_OFF, 0xff55575b,
                "Material switch: OFF track is the solid mid-grey");
        check(Math.abs(((MaterialSwitch2065.TRACK_OFF >> 16) & 0xff)
                                - ((MaterialSwitch2065.TRACK_OFF >> 8) & 0xff)) <= 8
                        && Math.abs(((MaterialSwitch2065.TRACK_OFF >> 8) & 0xff)
                                - (MaterialSwitch2065.TRACK_OFF & 0xff)) <= 8,
                "Material switch: the OFF track is a neutral grey, not a colour");

        System.out.println(passed + " Home2065 checks passed");
    }
}
