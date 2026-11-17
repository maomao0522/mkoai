package app.mkoai.player;

/**
 * mkoai 2.0.76 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2076_checks.py.
 *
 * The feature (user, 2026-11-17 ~19:46): 推薦優先地區 — one region
 * pick for dramas (劇） and one for movies (電影）, options 不限 (default)
 * / 台灣 / 中國 / 韓國 / 日本 / 美國. The picks live on the search page
 * below 最近搜尋 (one UI for phone + TV). "Priority" means the matching
 * titles are boosted to the front of the 推薦 grid (separately per
 * kind); 不限 is the current behaviour. Fail-open: an unknown/empty
 * area never drops a title, and nothing is ever filtered out by the
 * reorder — it only reorders.
 *
 * This class pins, in real code: the 日本 → JP branch in
 * AwwratedHome.regionOf and that JP behaves exactly like OTHER in the
 * home filter's platformPasses / isAnyRegion (so the branch changes no
 * home-filter behaviour); the code/label tables; and the reorder
 * (stable partition, per-kind picks, fail-open on unknown area / kind /
 * pick, null-safe, input never mutated).
 * The search-page UI, the TV composeHomeVideos wiring and the phone
 * renderHomeSections wiring are pinned by the static greps in the
 * runner (view code, like 2.0.73 did).
 */
public class Recommend2076Checks {
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

    static void eqStr(String got, String want, String name) {
        if (got == null || !got.equals(want)) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static Cms.Video movie(String area) {
        Cms.Video v = new Cms.Video();
        v.mediaType = "movie";
        v.area = area;
        return v;
    }

    static Cms.Video series(String area) {
        Cms.Video v = new Cms.Video();
        v.mediaType = "tv";
        v.area = area;
        return v;
    }

    static String titles(java.util.List<Cms.Video> in) {
        StringBuilder sb = new StringBuilder();
        for (Cms.Video v : in) {
            if (sb.length() > 0) sb.append("|");
            sb.append(v == null ? "null" : (v.title == null ? "?" : v.title));
        }
        return sb.toString();
    }

    static java.util.List<Cms.Video> named(String... specs) {
        // spec: "M:area:title" (movie) or "S:area:title" (series); area "-" = null.
        java.util.List<Cms.Video> out = new java.util.ArrayList<>();
        for (String spec : specs) {
            String[] parts = spec.split(":", 3);
            Cms.Video v = "M".equals(parts[0]) ? movie(null) : series(null);
            v.area = "-".equals(parts[1]) ? null : parts[1];
            v.title = parts[2];
            out.add(v);
        }
        return out;
    }

    public static void main(String[] args) {
        // ---- A. regionOf: 日本 → JP, existing mappings unchanged ----
        eqStr(AwwratedHome.regionOf("日本"), "JP", "(regionOf) 日本 → JP");
        eqStr(AwwratedHome.regionOf("日本,美国"), "JP", "(regionOf) 日本,美国 → JP (first match)");
        eqStr(AwwratedHome.regionOf("JP"), "JP", "(regionOf) JP token → JP");
        eqStr(AwwratedHome.regionOf("jp"), "JP", "(regionOf) lowercase jp token → JP");
        eqStr(AwwratedHome.regionOf("台湾"), "TW", "(regionOf) 台湾 → TW (unchanged)");
        eqStr(AwwratedHome.regionOf("大陆"), "CN", "(regionOf) 大陆 → CN (unchanged)");
        eqStr(AwwratedHome.regionOf("中国"), "CN", "(regionOf) 中国 → CN (unchanged)");
        eqStr(AwwratedHome.regionOf("香港"), "CN", "(regionOf) 香港 → CN (unchanged)");
        eqStr(AwwratedHome.regionOf("韩国"), "KR", "(regionOf) 韩国 → KR (unchanged)");
        eqStr(AwwratedHome.regionOf("美国"), "US", "(regionOf) 美国 → US (unchanged)");
        eqStr(AwwratedHome.regionOf(null), "UNKNOWN", "(regionOf) null → UNKNOWN");
        eqStr(AwwratedHome.regionOf(""), "UNKNOWN", "(regionOf) empty → UNKNOWN");
        eqStr(AwwratedHome.regionOf("法国"), "OTHER", "(regionOf) 法国 → OTHER (unchanged)");
        // JP behaves exactly like OTHER in the home filter's region gates.
        check(!AwwratedHome.platformPasses(null, "JP"),
                "(regionOf) platformPasses(null, JP) is false, like OTHER");
        check(AwwratedHome.platformPasses(null, "CN"),
                "(regionOf) platformPasses(null, CN) is true (control)");
        check(AwwratedHome.isAnyRegion("JP"),
                "(regionOf) isAnyRegion(JP) is true, like OTHER");
        check(!AwwratedHome.isAnyRegion("UNKNOWN"),
                "(regionOf) isAnyRegion(UNKNOWN) is false (control)");

        // ---- B. code / label tables ----
        eq(RecommendRegion2076.REGION_CODES.length,
                RecommendRegion2076.REGION_LABELS.length,
                "(table) codes and labels have the same length");
        eq(RecommendRegion2076.REGION_CODES.length, 6, "(table) six region options");
        eqStr(RecommendRegion2076.labelFor(""), "不限", "(label) blank → 不限");
        eqStr(RecommendRegion2076.labelFor("TW"), "台灣", "(label) TW → 台灣");
        eqStr(RecommendRegion2076.labelFor("CN"), "中國", "(label) CN → 中國");
        eqStr(RecommendRegion2076.labelFor("KR"), "韓國", "(label) KR → 韓國");
        eqStr(RecommendRegion2076.labelFor("JP"), "日本", "(label) JP → 日本");
        eqStr(RecommendRegion2076.labelFor("US"), "美國", "(label) US → 美國");
        eqStr(RecommendRegion2076.labelFor("XX"), "不限", "(label) unknown code → 不限");
        eqStr(RecommendRegion2076.labelFor(null), "不限", "(label) null → 不限");
        check(RecommendRegion2076.isRegionCode("TW"), "(code) TW is a region code");
        check(!RecommendRegion2076.isRegionCode(""), "(code) blank is not a region code");
        check(!RecommendRegion2076.isRegionCode(null), "(code) null is not a region code");
        check(!RecommendRegion2076.isRegionCode("XX"), "(code) XX is not a region code");

        // ---- C. reorder: stable partition, per-kind picks ----
        java.util.List<Cms.Video> bothBlank =
                named("S:中国:A", "M:日本:B", "S:台湾:C");
        java.util.List<Cms.Video> kept = RecommendRegion2076.reorder(bothBlank, "", "");
        eqStr(titles(kept), "A|B|C", "(reorder) both picks blank → order unchanged");
        check(kept != bothBlank, "(reorder) both picks blank → new list, not the input");

        java.util.List<Cms.Video> dramaTw =
                named("S:中国:A", "S:台湾:B", "M:台湾:C", "M:美国:D");
        eqStr(titles(RecommendRegion2076.reorder(dramaTw, "TW", "")),
                "B|A|C|D",
                "(reorder) drama=TW boosts only the TW series, TW movie stays");
        java.util.List<Cms.Video> movieJp =
                named("M:日本:A", "S:日本:B", "M:中国:C");
        eqStr(titles(RecommendRegion2076.reorder(movieJp, "", "JP")),
                "A|B|C",
                "(reorder) movie=JP boosts only the JP movie, JP series stays");
        java.util.List<Cms.Video> both =
                named("S:中国:A", "M:日本:B", "S:台湾:C", "M:美国:D", "M:台湾:E");
        eqStr(titles(RecommendRegion2076.reorder(both, "TW", "JP")),
                "B|C|A|D|E",
                "(reorder) drama=TW + movie=JP: matching titles front in original order");
        java.util.List<Cms.Video> stable =
                named("S:台湾:A", "M:中国:X", "S:台湾:B", "S:台湾:C");
        eqStr(titles(RecommendRegion2076.reorder(stable, "TW", "")),
                "A|B|C|X",
                "(reorder) matching titles keep their relative order (stable)");

        // ---- D. fail-open: unknown area / kind / pick never move, nothing dropped ----
        java.util.List<Cms.Video> unknownArea =
                named("S:-:A", "S:台湾:B", "M:-:C");
        eqStr(titles(RecommendRegion2076.reorder(unknownArea, "TW", "TW")),
                "B|A|C",
                "(reorder) null-area titles never move");
        Cms.Video unclassified = new Cms.Video();
        unclassified.area = "台湾";
        unclassified.title = "U";
        java.util.List<Cms.Video> withOther = named("S:台湾:A");
        withOther.add(0, unclassified);
        eqStr(titles(RecommendRegion2076.reorder(withOther, "TW", "TW")),
                "A|U",
                "(reorder) unclassifiable kind never moves even when its area matches");
        java.util.List<Cms.Video> badPick = named("S:台湾:A", "M:日本:B");
        eqStr(titles(RecommendRegion2076.reorder(badPick, "XX", "YY")),
                "A|B",
                "(reorder) unknown pick codes are treated as blank");
        java.util.List<Cms.Video> nullIn = RecommendRegion2076.reorder(null, "TW", "JP");
        eq(nullIn.size(), 0, "(reorder) null input → empty list");
        java.util.List<Cms.Video> withNull = named("S:台湾:A", "M:日本:B");
        withNull.add(1, null);
        java.util.List<Cms.Video> nullKept =
                RecommendRegion2076.reorder(withNull, "TW", "JP");
        eq(nullKept.size(), 3, "(reorder) null element is kept, not dropped");
        eqStr(titles(nullKept), "A|B|null",
                "(reorder) null element stays in the back group, order kept");
        java.util.List<Cms.Video> orig = named("S:中国:A", "S:台湾:B", "M:日本:C");
        RecommendRegion2076.reorder(orig, "TW", "JP");
        eqStr(titles(orig), "A|B|C", "(reorder) the input list is never mutated");

        System.out.println(passed + " Recommend2076 checks passed");
    }
}
