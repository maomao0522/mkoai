package app.mkoai.player;

/**
 * mkoai 2.0.57 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2057_checks.py.
 *
 * Covers:
 *  1. Poster Worker proxy (ImageLoader.proxyPosterUrl, ported from 2.0.56):
 *     whitelisted hosts (image.tmdb.org, empimage.slcache.com,
 *     *.doubanio.com) go through /img?url=; every other host, plain
 *     http, and already-proxied URLs pass through unchanged.
 *  2. CN/KR platform exemption (AwwratedHome.platformPasses, 2.0.57):
 *     a title with a Netflix/Disney+ platform always passes; a title
 *     with no platform passes only when its region is China or Korea.
 *  3. Pass-count comparison on one fixed list: the new rule keeps
 *     strictly more CN/KR titles than the old (platform-only) rule,
 *     and keeps exactly the same set for every non-CN/KR title.
 */
public class Home2057Checks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(Object got, Object want, String name) {
        if (got == null ? want != null : !got.equals(want)) {
            throw new AssertionError(
                    "FAIL: " + name + " (got=" + got + ", want=" + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    // (title, platform, region) triples for the pass-count comparison.
    static final String[][] LIST = {
        {"CN-no-platform-1", "", "CN"},
        {"CN-no-platform-2", "", "CN"},
        {"CN-no-platform-3", "", "CN"},
        {"KR-no-platform-1", "", "KR"},
        {"KR-no-platform-2", "", "KR"},
        {"TW-no-platform-1", "", "TW"},
        {"TW-no-platform-2", "", "TW"},
        {"US-no-platform-1", "", "US"},
        {"US-no-platform-2", "", "US"},
        {"US-netflix-1", "netflix", "US"},
        {"US-netflix-2", "netflix", "US"},
        {"CN-netflix-1", "netflix", "CN"},
        {"EU-no-platform-1", "", "OTHER"},
        {"EU-netflix-1", "netflix", "OTHER"},
    };

    static boolean isPresent(String region) {
        return "TW".equals(region) || "CN".equals(region)
                || "KR".equals(region) || "US".equals(region);
    }

    // The OLD rule (2.0.28-2.0.56): a title only passes when it has a platform.
    static boolean oldRule(String platform, String region) {
        return isPresent(region) && platform != null && !platform.isEmpty();
    }

    // The NEW rule (2.0.57): isPresent + platformPasses.
    static boolean newRule(String platform, String region) {
        return isPresent(region) && AwwratedHome.platformPasses(platform, region);
    }

    public static void main(String[] args) {
        // ---- 1. proxyPosterUrl ----
        String tmdb = "https://image.tmdb.org/t/p/w780/abc123.jpg";
        String proxied = ImageLoader.proxyPosterUrl(tmdb);
        check(proxied.startsWith(ImageLoader.POSTER_PROXY_BASE), "tmdb proxied");
        check(proxied.contains("image.tmdb.org"), "proxied url keeps original host");
        check(proxied.contains("abc123.jpg"), "proxied url keeps path");

        String slcache =
                "https://empimage.slcache.com/metadata/artwork/source/poster/x.jpg";
        check(ImageLoader.proxyPosterUrl(slcache).startsWith(ImageLoader.POSTER_PROXY_BASE),
                "slcache proxied");

        String douban = "https://img1.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg";
        check(ImageLoader.proxyPosterUrl(douban).startsWith(ImageLoader.POSTER_PROXY_BASE),
                "doubanio proxied");
        String douban2 = "https://img9.doubanio.com/view/photo/s_ratio_poster/public/p2.jpg";
        check(ImageLoader.proxyPosterUrl(douban2).startsWith(ImageLoader.POSTER_PROXY_BASE),
                "doubanio img9 proxied");

        String other = "https://example.com/poster.jpg";
        eq(ImageLoader.proxyPosterUrl(other), other, "other host unchanged");
        String http = "http://image.tmdb.org/t/p/w780/abc.jpg";
        eq(ImageLoader.proxyPosterUrl(http), http, "plain http unchanged");
        eq(ImageLoader.proxyPosterUrl(proxied), proxied, "already proxied unchanged");
        eq(ImageLoader.proxyPosterUrl(null), null, "null unchanged");
        eq(ImageLoader.proxyPosterUrl(""), "", "empty unchanged");
        String sub = "https://notimage.tmdb.org.evil.com/x.jpg";
        eq(ImageLoader.proxyPosterUrl(sub), sub, "lookalike host unchanged");

        // ---- 2. platformPasses matrix ----
        check(AwwratedHome.platformPasses("netflix", "US"), "netflix+US passes");
        check(AwwratedHome.platformPasses("disney+", "CN"), "disney++CN passes");
        check(AwwratedHome.platformPasses("netflix", "OTHER"), "netflix+OTHER passes");
        check(AwwratedHome.platformPasses("", "CN"), "no-platform+CN passes");
        check(AwwratedHome.platformPasses("", "KR"), "no-platform+KR passes");
        check(!AwwratedHome.platformPasses("", "TW"), "no-platform+TW dropped");
        check(!AwwratedHome.platformPasses("", "US"), "no-platform+US dropped");
        check(!AwwratedHome.platformPasses("", "OTHER"), "no-platform+OTHER dropped");
        check(!AwwratedHome.platformPasses("", "UNKNOWN"), "no-platform+UNKNOWN dropped");
        check(AwwratedHome.platformPasses(null, "CN"), "null-platform+CN passes");
        check(!AwwratedHome.platformPasses(null, "US"), "null-platform+US dropped");

        // ---- 3. pass-count comparison, same list ----
        int oldKept = 0, newKept = 0, newCnKr = 0;
        int oldNonCnKr = 0, newNonCnKr = 0;
        for (String[] row : LIST) {
            String platform = row[1];
            String region = row[2];
            boolean isCnKr = "CN".equals(region) || "KR".equals(region);
            if (oldRule(platform, region)) {
                oldKept++;
                if (!isCnKr) oldNonCnKr++;
            }
            if (newRule(platform, region)) {
                newKept++;
                if (isCnKr) newCnKr++;
                else newNonCnKr++;
            }
        }
        eq(oldKept, 3, "old rule keeps 3 (platform only)");
        eq(newKept, 8, "new rule keeps 8 (3 old + 5 CN/KR no-platform)");
        eq(newCnKr, 6, "new rule keeps 6 CN/KR titles");
        eq(oldNonCnKr, newNonCnKr, "non-CN/KR kept set unchanged");
        check(newKept > oldKept, "new rule keeps strictly more than old rule");

        // ---- 4. regionOf: Taiwan / China / Korea / USA strings ----
        eq(AwwratedHome.regionOf("台灣"), "TW", "regionOf 台灣 (traditional)");
        eq(AwwratedHome.regionOf("台湾"), "TW", "regionOf 台湾 (simplified)");
        eq(AwwratedHome.regionOf("TW"), "TW", "regionOf TW code");
        eq(AwwratedHome.regionOf("tw"), "TW", "regionOf tw lowercase");
        eq(AwwratedHome.regionOf("TW,US"), "TW", "regionOf TW,US token");
        eq(AwwratedHome.regionOf("中國"), "CN", "regionOf 中國");
        eq(AwwratedHome.regionOf("中国大陆"), "CN", "regionOf 中国大陆");
        eq(AwwratedHome.regionOf("CN"), "CN", "regionOf CN code");
        eq(AwwratedHome.regionOf("香港"), "CN", "regionOf 香港");
        eq(AwwratedHome.regionOf("韓國"), "KR", "regionOf 韓國");
        eq(AwwratedHome.regionOf("韩国"), "KR", "regionOf 韩国");
        eq(AwwratedHome.regionOf("KR"), "KR", "regionOf KR code");
        eq(AwwratedHome.regionOf("美國"), "US", "regionOf 美國");
        eq(AwwratedHome.regionOf("美国"), "US", "regionOf 美国");
        eq(AwwratedHome.regionOf("US"), "US", "regionOf US code");
        eq(AwwratedHome.regionOf("日本"), "OTHER", "regionOf 日本 is OTHER");
        eq(AwwratedHome.regionOf("法國"), "OTHER", "regionOf 法國 is OTHER");
        eq(AwwratedHome.regionOf("AUS"), "OTHER", "regionOf AUS is OTHER, not US");
        eq(AwwratedHome.regionOf(null), "UNKNOWN", "regionOf null is UNKNOWN");
        eq(AwwratedHome.regionOf(""), "UNKNOWN", "regionOf empty is UNKNOWN");
        eq(AwwratedHome.regionOf("   "), "UNKNOWN", "regionOf blank is UNKNOWN");

        // ---- 5. Taiwan Netflix end-to-end (the 2.0.57 follow-up) ----
        // A Taiwan title on Netflix: awwrated marks it platform=netflix,
        // the source says area="TW". Both halves must resolve right.
        String twRegion = AwwratedHome.regionOf("TW");
        eq(twRegion, "TW", "TW Netflix title region resolves to TW");
        check(AwwratedHome.platformPasses("netflix", twRegion),
                "TW Netflix title passes platform rule");
        check(isPresent(twRegion), "TW Netflix title is a present region");
        check(newRule("netflix", twRegion), "TW Netflix title kept by new rule");
        // The old code dropped it: regionOf(\"TW\") was OTHER, so
        // isPresent failed even though the platform was Netflix.
        check(!isPresent("OTHER"), "OTHER is not a present region (old drop)");

        System.out.println(passed + " Home2057 checks passed");
    }
}
