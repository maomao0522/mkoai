package app.mkoai.player;

/**
 * mkoai 2.0.70 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2070_checks.py.
 *
 * Covers the one pure-Java change of 2.0.70: the poster decode policy.
 * The hero poster is a 780x1170 TMDB image shown 1080px wide, but the
 * old non-detail pixel cap (800K) forced sample 2, so it decoded at
 * 390x585 and showed upscaled ~2.8x (soft). The cap is now 2M (the
 * detail value): the hero decodes at the full 780x1170 (912,600 px),
 * small cards are unaffected (their desired size already picks the
 * same sample), and the image LRU still bounds the total memory.
 *
 * The rest of 2.0.70 (entrance fades, deferred tab builds, glide /
 * retint durations, veil shader + bloom caches, frost dedup, hero
 * press feedback, commit -> apply, pull-refresh setText guard, grid
 * batching, resume re-kick rule, async wallpaper decode, poster
 * layout-time re-request + no-clobber guard) is Android-view code
 * and is source-checked by the runner.
 */
public class Home2070Checks {
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

    public static void main(String[] args) {
        // ---- the hero case: 780x1170 source, 480x720 desired ----
        // scale = max(480/780, 720/1170) = 0.615 -> desired 480x720.
        // Loop 1 stops at sample 1 (780/2 = 390 < 480). Loop 2 (the
        // cap): 780*1170 = 912,600 px <= 2M -> sample stays 1.
        // Under the old 800K cap the same loop forced sample 2.
        eq(ImageDecodePolicy.sample(780, 1170, 480, 720, true, false), 1,
                "hero poster decodes full-size now (sample 1, was 2 under the 800K cap)");

        // ---- a small desired size still downsamples ----
        // scale = max(320/780, 480/1170) = 0.41 -> desired 320x480.
        // Loop 1: 780/2 = 390 >= 320 and 1170/2 = 585 >= 480 -> sample 2;
        // 780/4 = 195 < 320 -> stop. 390*585 = 228K px <= 2M.
        eq(ImageDecodePolicy.sample(780, 1170, 320, 480, true, false), 2,
                "small desired size still downsamples (sample 2)");

        // ---- a second cap-bound case: 1600x2400, desired 480x720 ----
        // Loop 1 stops at sample 2 (1600/4 = 400 < 480). Loop 2:
        // 800*1200 = 960,000 px <= 2M -> stays 2 (old cap: 960K > 800K -> 4).
        eq(ImageDecodePolicy.sample(1600, 2400, 480, 720, true, false), 2,
                "1600x2400 at hero size: sample 2 (was 4 under the 800K cap)");

        // ---- grid cards are unaffected: 342x513 source, ~253x385 desired ----
        // Loop 1 stops at sample 1 (342/2 = 171 < 257). 342*513 = 175K px.
        eq(ImageDecodePolicy.sample(342, 513, 253, 385, true, false), 1,
                "grid card decode unchanged (sample 1)");

        // ---- a huge source still downsamples hard ----
        // scale = max(480/4000, 720/3000) = 0.24 -> desired 960x720.
        // Loop 1: 4000/4 = 1000 >= 960 and 3000/4 = 750 >= 720 -> sample 4;
        // 4000/8 = 500 < 960 -> stop. 1000*750 = 750K px <= 2M.
        eq(ImageDecodePolicy.sample(4000, 3000, 480, 720, true, false), 4,
                "huge source still downsamples (sample 4)");

        // ---- detail path is unchanged (its cap was already 2M) ----
        eq(ImageDecodePolicy.sample(780, 1170, 480, 720, true, true), 1,
                "detail decode unchanged (sample 1)");
        eq(ImageDecodePolicy.sample(1080, 1620, 1080, 1620, true, true), 1,
                "detail full-size decode unchanged (sample 1, 1.75M px <= 2M)");

        // ---- degenerate input ----
        eq(ImageDecodePolicy.sample(0, 0, 480, 720, true, false), 1,
                "zero-size source returns sample 1");

        // ---- target sizes (the 320px fallback is still the pre-layout value) ----
        eq(ImageDecodePolicy.targetWidth(0, false), 320,
                "targetWidth falls back to 320px before layout (unchanged — the re-request fixes it)");
        eq(ImageDecodePolicy.targetWidth(1080, false), 480,
                "targetWidth caps at 480px laid out (non-detail)");
        eq(ImageDecodePolicy.targetWidth(0, true), 720,
                "targetWidth falls back to 720px before layout (detail)");
        eq(ImageDecodePolicy.targetWidth(1080, true), 1080,
                "targetWidth caps at 1080px laid out (detail)");
        eq(ImageDecodePolicy.targetHeight(0, false), 480,
                "targetHeight falls back to 480px before layout (non-detail)");
        eq(ImageDecodePolicy.targetHeight(1621, false), 720,
                "targetHeight caps at 720px laid out (non-detail)");
        eq(ImageDecodePolicy.targetHeight(0, true), 1080,
                "targetHeight falls back to 1080px before layout (detail)");

        // ---- cache keys still separate sizes ----
        check(!ImageDecodePolicy.cacheKey("u", 320, 480, true, false)
                        .equals(ImageDecodePolicy.cacheKey("u", 480, 720, true, false)),
                "cacheKey separates the small and full-size decodes");

        System.out.println(passed + " Home2070 checks passed");
    }
}
