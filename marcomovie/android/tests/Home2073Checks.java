package app.mkoai.player;

/**
 * mkoai 2.0.73 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2073_checks.py.
 *
 * (1) The history back button and (2) the dock label "記錄" are
 * Android view code — they are pinned by the static greps in the
 * runner (identical glassBg call, "‹" 22sp, 38dp, onBackPressed pop,
 * tag history-back; HISTORY_DOCK_LABEL = "記錄" with every other
 * 觀看記錄 string untouched), not here.
 *
 * This class pins (3) the home-bottom white haze diagnosis with the
 * real screenshot numbers (user field test of 2.0.72 on his POCO F5,
 * 2026-11-17 ~10:30-10:32, two screenshots): the home page tone
 * (flat band, y 1600-1840) is #5d5151 = (93,81,81); the bottom band
 * below the dock (y 2010-2045) is (129,129,135), luminance ~130 vs
 * ~85, saturation ~0.05 vs ~0.13. The band is a uniform overlay
 * (every column the same grey at y=2030), 92dp tall, onset ~y1860 —
 * the BottomEdgeFadeView geometry. Its gradient bottom stop is 0xe6
 * alpha (bottomFadeColors). The veil's colour only followed the tone
 * inside the diffusion flip block, so a hero rotation that kept the
 * light class left it on a previous (lighter) hero's tone while the
 * page followed the new one: compositing a light-grey tone at 0xe6
 * over the page reproduces the measured band, while the current brown
 * tone cannot — even over pure white content it caps below the band.
 * Other tabs never showed it (switchTab re-tints the veil to the
 * current tone on leaving home). The fix removes the layer, so the
 * home bottom is the flat page tone like every other tab; the hero's
 * own bottom dissolve (updateHeroScrim) is untouched.
 */
public class Home2073Checks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(int got, int want, String name) {
        if (got != want) {
            throw new AssertionError(
                    "FAIL: " + name + " (got #" + Integer.toHexString(got)
                            + ", want #" + Integer.toHexString(want) + ")");
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

    /** Alpha-composite src over dst at alpha 0-255, per channel. */
    static int composite(int src, int alpha, int dst) {
        return (alpha * src + (255 - alpha) * dst + 127) / 255;
    }

    public static void main(String[] args) {
        // The veil's gradient stops, from HomeTone2066.bottomFadeColors
        // (the function is untouched — the layer using it is gone).
        int[] veil = HomeTone2066.bottomFadeColors(0x5d5151);
        eq(veil[0], 0x005d5151, "veil top stop is transparent");
        eq(veil[1], 0x905d5151, "veil middle stop is 0x90 alpha");
        eq(veil[2], 0xe65d5151, "veil bottom stop is 0xe6 alpha of the base colour");
        int alpha = 0xe6;

        // Measured page tone (Cunk hero, flat band y 1600-1840): (93,81,81).
        int pageR = 93, pageG = 81, pageB = 81;
        // Measured haze band below the dock (y 2010-2045): (129,129,135).
        int bandR = 129, bandG = 129, bandB = 135;

        // A stale light-grey veil base (a previous hero's tone,
        // ~(131,132,138)) at 0xe6 over the page tone reproduces the
        // measured band (within JPEG / sampling noise, +-6).
        near(composite(131, alpha, pageR), bandR, 6,
                "stale light veil over page tone reproduces band R");
        near(composite(132, alpha, pageG), bandG, 6,
                "stale light veil over page tone reproduces band G");
        near(composite(138, alpha, pageB), bandB, 6,
                "stale light veil over page tone reproduces band B");
        // The same stale base over poster content (~120,110,115) also
        // lands on the band (the haze washed posters and background alike).
        near(composite(131, alpha, 120), bandR, 6,
                "stale light veil over poster reproduces band R");
        near(composite(132, alpha, 110), bandG, 6,
                "stale light veil over poster reproduces band G");
        near(composite(138, alpha, 115), bandB, 6,
                "stale light veil over poster reproduces band B");

        // The CURRENT brown tone cannot explain the band: even over pure
        // white content the 0xe6 veil caps below the measured band.
        check(composite(pageR, alpha, 255) < bandR,
                "current-tone veil over white still darker than band R");
        check(composite(pageG, alpha, 255) < bandG,
                "current-tone veil over white still darker than band G");
        check(composite(pageB, alpha, 255) < bandB,
                "current-tone veil over white still darker than band B");
        // And with the current tone the veil is invisible over the page
        // itself (why the other tabs look flat: switchTab re-tints it
        // to the current tone on leaving home).
        eq(composite(pageR, alpha, pageR), pageR,
                "current-tone veil over page tone is the page tone (R)");
        eq(composite(pageG, alpha, pageG), pageG,
                "current-tone veil over page tone is the page tone (G)");
        eq(composite(pageB, alpha, pageB), pageB,
                "current-tone veil over page tone is the page tone (B)");

        // The haze is a lift + desaturation of the page, not a hue change.
        double bandLum = 0.299 * bandR + 0.587 * bandG + 0.114 * bandB;
        double pageLum = 0.299 * pageR + 0.587 * pageG + 0.114 * pageB;
        check(bandLum - pageLum > 40,
                "haze band is >40 luminance above the page tone");

        System.out.println(passed + " Home2073 checks passed");
    }
}
