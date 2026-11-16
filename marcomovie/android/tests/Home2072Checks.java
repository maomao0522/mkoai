package app.mkoai.player;

/**
 * mkoai 2.0.72 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2072_checks.py.
 *
 * Covers the three 2.0.72 items (user field test of 2.0.71 on his POCO
 * F5, 2026-11-16 ~23:59-00:01, three screenshots):
 *
 * (1) Whole page the same colour as home: the watch-history page
 * (renderPage) now follows the hero tone (MainActivity.pageTone,
 * published by MobileShell.applyPageTone; the shell's Layer 0
 * canvas paints the flat tone instead of the opaque frost that hid
 * the root colour, so the search / live / my / settings tabs
 * actually follow the tone too). The walk flips only text that
 * sits directly on the page background, by its current colour
 * (HomeTone2066.retintPageColor) - the dark-glass subtrees (poster
 * art, episode badge, play button, progress track, "clear history"
 * pill, a focused card's highlight) are skipped by their
 * non-transparent background. The info "i" button's oval is
 * stroke-only and the "..." button has no background at all, so
 * both sit on the page background and their white glyphs flip too
 * (they are NOT dark glass, whatever the 2.0.71 notes assumed).
 *
 * (2) Hero top de-fog: topFadeHeightPx() is 0 on the home tab -
 * the poster runs clean to the very top edge. Other tabs keep the
 * short status-bar veil (statusBarHeight + 24dp). The bottom scrim
 * is untouched (the title's only protection) and the status-bar
 * icons were already tone-aware.
 *
 * (3) Page bottom not grey: the bloom's far colour is pageTone(bg)
 * on both sides now (was backgroundTone on a dark page - 30%
 * toward grey, 65% brightness - which faded the bottom of the
 * home page grey). Measured on the user's Wenwu hero (12 real
 * poster pixels from his 23:59 home screenshot, clean middle
 * band): the tone is a brown #6f564b (saturation 0.194) but the
 * old far was #443934 (saturation 0.133); the new far is #635047
 * (saturation 0.165, hue drift ~1 degree). backgroundTone itself
 * is untouched (the legacy bloomColors shape + its 2063-2065 pins).
 *
 * The welcome page (no config, TV-only) is untouched, and the
 * detail page's openListTone behaviour is unchanged (pinned by
 * the static greps in the runner, not here - it is Android code).
 */
public class Home2072Checks {
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

    /** HSL hue in degrees (0-360) of an opaque ARGB colour. */
    static double hue(int argb) {
        float r = ((argb >> 16) & 0xff) / 255f;
        float g = ((argb >> 8) & 0xff) / 255f;
        float b = (argb & 0xff) / 255f;
        float mx = Math.max(r, Math.max(g, b));
        float mn = Math.min(r, Math.min(g, b));
        if (mx == mn) return 0.0;
        float d = mx - mn;
        float h;
        if (mx == r) h = (g - b) / d + (g < b ? 6 : 0);
        else if (mx == g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        return h * 60.0;
    }

    /** HSL saturation (0-1) of an opaque ARGB colour. */
    static double saturation(int argb) {
        float r = ((argb >> 16) & 0xff) / 255f;
        float g = ((argb >> 8) & 0xff) / 255f;
        float b = (argb & 0xff) / 255f;
        float mx = Math.max(r, Math.max(g, b));
        float mn = Math.min(r, Math.min(g, b));
        float l = (mx + mn) / 2;
        if (mx == mn) return 0.0;
        float d = mx - mn;
        return l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
    }

    public static void main(String[] args) {
        int darkTone = 0xff70564b; // the Wenwu hero tone (measured)
        int lightTone = 0xffd4cabb; // a light warm tone (measured)

        // ---- (3) the grey bottom: the pipeline on real poster pixels ----
        // 12 real pixels from the user's Wenwu home screenshot
        // (clean middle band, between the top veil and the title).
        int[] wenwu = {
            0xff09090b, 0xff825444, 0xff37241d, 0xff1e1e1c, 0xff201c19, 0xff5c433e,
            0xff704534, 0xff9d7d72, 0xff766259, 0xff2c1d1a, 0xff926d5d, 0xff2c2422
        };
        int avg = HeroDiffusion2063.averageTone(wenwu, wenwu.length);
        eq(avg, 0xff7d5c4f, "wenwu sample: averageTone is #7d5c4f (still brown, muddy fallback kept the hue)");
        int page = HeroDiffusion2063.pageTone(avg);
        eq(page, 0xff6f564b, "wenwu sample: pageTone is #6f564b");
        near(Math.abs(hue(page) - hue(avg)), 0.0, 5.0,
                "wenwu sample: pageTone keeps the poster's hue (drift under 5 degrees)");
        check(saturation(page) >= 0.15,
                "wenwu sample: pageTone is not grey (saturation >= 0.15)");
        int far = HeroDiffusion2063.bloomColorsAnchored(page, 0.6753f)[7];
        eq(far, HeroDiffusion2063.pageTone(page),
                "wenwu sample: the bloom far colour is pageTone(tone) now, not backgroundTone");
        check(far != HeroDiffusion2063.backgroundTone(page),
                "wenwu sample: the old grey far (backgroundTone) is gone");
        eq(far, 0xff635047, "wenwu sample: the new far is #635047");
        near(Math.abs(hue(far) - hue(page)), 0.0, 5.0,
                "wenwu sample: the far keeps the hue (drift under 5 degrees)");
        check(saturation(far) >= saturation(page) * 0.8,
                "wenwu sample: the far keeps at least 80% of the tone's saturation (was ~68% with backgroundTone)");
        check(HomeTone2066.contrast(HomeTone2066.primaryText(far), far) >= 6.0,
                "wenwu far: primary text is >= 6:1 on the new far colour");
        check(HomeTone2066.contrast(HomeTone2066.mutedText(far), far) >= 4.8,
                "wenwu far: muted text is >= 4.8:1 on the new far colour");

        // ---- (3) backgroundTone itself is untouched (legacy shape) ----
        eq(HeroDiffusion2063.backgroundTone(0xffffffff), 0xff8c8c8c,
                "backgroundTone unchanged: white still mutes+darkens to 140 grey (2063 pin)");
        eq(HeroDiffusion2063.bloomColors(darkTone)[HeroDiffusion2063.BLOOM_STOPS - 1],
                HeroDiffusion2063.backgroundTone(darkTone),
                "bloomColors (legacy shape, 6 stops) still ends on backgroundTone - only the anchored bloom changed");

        // ---- (3) the new far holds contrast + the snap band on a sweep of tones ----
        int[] tones = {
            0xff70564b, // Wenwu dark brown
            0xff266d64, // vivid teal (pageTone of 0x2a9d8f)
            0xffd4cabb, // light warm
            0xff626262, // pure grey
            0xff363a43, // dark slate
            0xff267038, // saturated green (pageTone of 0x2fae4e)
        };
        for (int t : tones) {
            int f = HeroDiffusion2063.bloomColorsAnchored(t, 0.6753f)[7];
            eq(f, HeroDiffusion2063.pageTone(t),
                    "far is pageTone(tone) for tone #" + Integer.toHexString(t & 0xffffff));
            double lum = HomeTone2066.relativeLuminance(f);
            check(lum <= 0.13 || lum >= 0.47,
                    "far stays in the snap band (dark <= 0.13 / light >= 0.47) for tone #"
                            + Integer.toHexString(t & 0xffffff) + " (lum " + lum + ")");
            check(HomeTone2066.contrast(HomeTone2066.primaryText(f), f) >= 6.0,
                    "primary >= 6:1 on the far for tone #" + Integer.toHexString(t & 0xffffff));
            check(HomeTone2066.contrast(HomeTone2066.mutedText(f), f) >= 4.8,
                    "muted >= 4.8:1 on the far for tone #" + Integer.toHexString(t & 0xffffff));
            double hueDrift = Math.abs(hue(f) - hue(t));
            if (hueDrift > 180) hueDrift = 360 - hueDrift;
            check(saturation(t) < 0.01 || hueDrift <= 8.0,
                    "far keeps the hue (drift <= 8 degrees) for tone #"
                            + Integer.toHexString(t & 0xffffff));
        }

        // ---- (1) the history walk's colour roles (real retintPageColor) ----
        eq(HomeTone2066.retintPageColor(0xfff5f5f7, darkTone), 0xffffffff,
                "history role: page white on a dark tone stays white");
        eq(HomeTone2066.retintPageColor(0xfff5f5f7, lightTone), 0xff111827,
                "history role: page white on a light tone becomes near-black");
        eq(HomeTone2066.retintPageColor(0xffffffff, lightTone), 0xff111827,
                "history role: white heading on a light tone becomes near-black");
        eq(HomeTone2066.retintPageColor(0xffa1a1aa, darkTone), 0xffe5e7eb,
                "history role: muted grey on a dark tone stays light grey");
        eq(HomeTone2066.retintPageColor(0xffa1a1aa, lightTone), 0xff374151,
                "history role: muted grey on a light tone becomes dark slate");
        eq(HomeTone2066.retintPageColor(0xffa9c9f3, darkTone), 0xff5ac8fa,
                "history role: eyebrow blue on a dark tone becomes the sky-blue accent");
        eq(HomeTone2066.retintPageColor(0xffa9c9f3, lightTone), 0xff0d47a1,
                "history role: eyebrow blue on a light tone becomes the deep-blue accent");
        // Closure: flipping twice lands back on the same side's value.
        int[] roles = {
            0xfff5f5f7, 0xffffffff, 0xff111827,
            0xffa1a1aa, 0xff9ca3af, 0xffd1d1da, 0xffd1d5db, 0xffe5e7eb, 0xff374151,
            0xffa9c9f3, 0xff5ac8fa, 0xff0d47a1
        };
        for (int c : roles) {
            int toLight = HomeTone2066.retintPageColor(c, lightTone);
            int backDark = HomeTone2066.retintPageColor(toLight, darkTone);
            eq(backDark, HomeTone2066.retintPageColor(c, darkTone),
                    "history role closure: light then dark lands on the dark value for #"
                            + Integer.toHexString(c & 0xffffff));
            int toDark = HomeTone2066.retintPageColor(c, darkTone);
            int backLight = HomeTone2066.retintPageColor(toDark, lightTone);
            eq(backLight, toLight,
                    "history role closure: dark then light lands on the light value for #"
                            + Integer.toHexString(c & 0xffffff));
        }
        // Non-role colours are untouched (dark glass text, progress red, poster fallback).
        eq(HomeTone2066.retintPageColor(0x55ffffff, lightTone), 0x55ffffff,
                "history role: the half-transparent poster fallback title is untouched");
        eq(HomeTone2066.retintPageColor(0xffe6e6ee, lightTone), 0xffe6e6ee,
                "history role: the clear-history pill text is untouched");
        eq(HomeTone2066.retintPageColor(0xffe50914, lightTone), 0xffe50914,
                "history role: the progress red is untouched");
        eq(HomeTone2066.retintPageColor(0xffedf1f7, lightTone), 0xffedf1f7,
                "history role: the episode badge text is untouched");
        eq(HomeTone2066.retintPageColor(0xff282832, lightTone), 0xff282832,
                "history role: the dark-glass pill colour is untouched");
        // The flipped texts still read on the history background itself (the tone).
        check(HomeTone2066.contrast(HomeTone2066.primaryText(darkTone), darkTone) >= 6.0,
                "history bg: primary >= 6:1 on the dark Wenwu tone");
        check(HomeTone2066.contrast(HomeTone2066.mutedText(darkTone), darkTone) >= 4.8,
                "history bg: muted >= 4.8:1 on the dark Wenwu tone");
        check(HomeTone2066.contrast(HomeTone2066.primaryText(lightTone), lightTone) >= 6.0,
                "history bg: primary >= 6:1 on a light tone");
        check(HomeTone2066.contrast(HomeTone2066.mutedText(lightTone), lightTone) >= 4.8,
                "history bg: muted >= 4.8:1 on a light tone");

        // ---- (2) the top veil is gone on home, kept on other tabs (model) ----
        double sbFn = 41.0; // statusBarHeight() (the function)
        double homeVeilH = 0.0; // topFadeHeightPx() on the home tab
        near(homeVeilH, 0.0, 0.5, "home veil height is 0dp (no top fog on the poster)");
        double otherVeilH = sbFn + 24.0; // topFadeHeightPx() on other tabs
        near(otherVeilH, 65.0, 0.5, "other-tab veil stays ~65dp (statusBar + 24dp)");
        check(homeVeilH < otherVeilH,
                "home has no veil while other tabs keep the short one");

        System.out.println(passed + " Home2072 checks passed");
    }
}
