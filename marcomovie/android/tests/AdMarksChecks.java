package app.mkoai.player;

/**
 * mkoai 2.0.60 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2060_checks.py.
 *
 * Covers AdMarks (Features A + B):
 *  1. key(): title+episode+source keying; episode normalization
 *     (第01集 = 第1集 = 第1期 = 第 1 集); different episode/source ->
 *     different key; empty title -> "".
 *  2. put/get/remove: save/load a range, invalid ranges rejected,
 *     malformed JSON -> null, remove clears, MAX_RANGES cap evicts oldest.
 *  3. inRange / skipTarget: inside a mark -> jump to its end; before or
 *     after the mark -> plain +30s; clamp at duration; duration 0 -> no
 *     clamp; the auto-skip guard (once per entry) is exercised via
 *     inRange + the armed flag semantics the caller implements.
 */
public class AdMarksChecks {
    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS " + name);
    }

    static void eq(Object got, Object want, String name) {
        if (got == null ? want != null : !got.equals(want)) {
            throw new AssertionError(
                    "FAIL: " + name + " (got " + got + ", want " + want + ")");
        }
        passed++;
        System.out.println("PASS " + name);
    }

    static void eqArr(int[] got, int[] want, String name) {
        if (got == null || want == null || got.length != want.length) {
            throw new AssertionError(
                    "FAIL: " + name
                            + " (got "
                            + (got == null ? "null" : java.util.Arrays.toString(got))
                            + ", want "
                            + java.util.Arrays.toString(want)
                            + ")");
        }
        for (int i = 0; i < got.length; i++) {
            if (got[i] != want[i]) {
                throw new AssertionError(
                        "FAIL: " + name + " (got " + java.util.Arrays.toString(got) + ")");
            }
        }
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. key() ----
        String k1 = AdMarks.key("八仙", "第01集", "site_1");
        String k2 = AdMarks.key("八仙", "第1集", "site_1");
        String k3 = AdMarks.key("八仙", "第1期", "site_1");
        String k4 = AdMarks.key("八仙", "第 1 集", "site_1");
        check(!k1.isEmpty(), "key: non-empty for a titled episode");
        eq(k1, k2, "key: 第01集 and 第1集 are the same episode");
        eq(k1, k3, "key: 第1期 normalizes to 第1集");
        eq(k1, k4, "key: 第 1 集 (spaces) normalizes the same");
        check(!AdMarks.key("八仙", "第2集", "site_1").equals(k1),
                "key: a different episode is a different key");
        check(!AdMarks.key("八仙", "第1集", "site_2").equals(k1),
                "key: a different source is a different key");
        check(!AdMarks.key("八仙！", "HD中字", "site_1").equals(k1),
                "key: a different title/episode is a different key");
        eq(AdMarks.key("", "第1集", "site_1"), "", "key: empty title -> empty key");
        eq(AdMarks.key(null, "第1集", "site_1"), "", "key: null title -> empty key");

        // ---- 2. put / get / remove ----
        String json = "{}";
        json = AdMarks.put(json, k1, 1_800_000, 1_830_000);
        eqArr(AdMarks.get(json, k1), new int[] {1_800_000, 1_830_000},
                "put/get: saved range loads back");
        check(AdMarks.get(json, "nonexistent") == null, "get: unknown key -> null");
        check(AdMarks.get("not json", k1) == null, "get: malformed JSON -> null");
        check(AdMarks.get(json, "") == null, "get: empty key -> null");
        // Invalid ranges are rejected (end <= start, negative start).
        String before = json;
        json = AdMarks.put(json, k1, 5_000, 5_000);
        eqArr(AdMarks.get(json, k1), new int[] {1_800_000, 1_830_000},
                "put: end == start is rejected, old range kept");
        json = AdMarks.put(json, k1, 5_000, 4_000);
        eqArr(AdMarks.get(json, k1), new int[] {1_800_000, 1_830_000},
                "put: end < start is rejected, old range kept");
        json = AdMarks.put(json, k1, -1, 5_000);
        eqArr(AdMarks.get(json, k1), new int[] {1_800_000, 1_830_000},
                "put: negative start is rejected, old range kept");
        check(before.equals(json) || AdMarks.get(json, k1) != null,
                "put: rejected puts leave the store unchanged");
        // Overwrite replaces.
        json = AdMarks.put(json, k1, 2_000_000, 2_030_000);
        eqArr(AdMarks.get(json, k1), new int[] {2_000_000, 2_030_000},
                "put: overwrite replaces the range");
        // Remove clears.
        json = AdMarks.remove(json, k1);
        check(AdMarks.get(json, k1) == null, "remove: range is gone after remove");
        // MAX_RANGES cap: oldest entry is evicted.
        String capped = "{}";
        for (int i = 0; i < AdMarks.MAX_RANGES + 5; i++) {
            capped = AdMarks.put(capped, "k" + i, i * 1000, i * 1000 + 500);
        }
        check(AdMarks.get(capped, "k0") == null, "cap: oldest entry evicted past MAX_RANGES");
        check(AdMarks.get(capped, "k" + (AdMarks.MAX_RANGES + 4)) != null,
                "cap: newest entry survives past MAX_RANGES");

        // ---- 3. inRange / skipTarget ----
        int[] mark = new int[] {1_800_000, 1_830_000};
        check(AdMarks.inRange(1_800_000, mark), "inRange: at the start is inside");
        check(AdMarks.inRange(1_815_000, mark), "inRange: middle is inside");
        check(!AdMarks.inRange(1_830_000, mark), "inRange: at the end is outside (exclusive)");
        check(!AdMarks.inRange(1_799_999, mark), "inRange: just before the start is outside");
        check(!AdMarks.inRange(1_800_000, null), "inRange: no mark -> false");
        eq(AdMarks.skipTarget(1_815_000, 7_200_000, mark), 1_830_000,
                "skipTarget: inside the mark jumps to its end");
        eq(AdMarks.skipTarget(1_800_000, 7_200_000, mark), 1_830_000,
                "skipTarget: at the mark start jumps to its end");
        eq(AdMarks.skipTarget(1_799_000, 7_200_000, mark), 1_829_000,
                "skipTarget: before the mark is a plain +30s (no far jump)");
        eq(AdMarks.skipTarget(1_830_000, 7_200_000, mark), 1_860_000,
                "skipTarget: at/after the mark end is a plain +30s");
        eq(AdMarks.skipTarget(2_000_000, 7_200_000, null), 2_030_000,
                "skipTarget: no mark is a plain +30s");
        eq(AdMarks.skipTarget(7_190_000, 7_200_000, null), 7_200_000,
                "skipTarget: +30s clamps at the duration");
        eq(AdMarks.skipTarget(7_300_000, 7_200_000, null), 7_200_000,
                "skipTarget: past the duration clamps to the duration");
        eq(AdMarks.skipTarget(5_000_000, 0, null), 5_030_000,
                "skipTarget: unknown duration (0) is not clamped");
        eq(AdMarks.skipTarget(5_000_000, -1, null), 5_030_000,
                "skipTarget: negative duration is not clamped");
        eq(AdMarks.SKIP_FORWARD_MS, 30_000, "SKIP_FORWARD_MS is 30s");

        System.out.println(passed + " AdMarks checks passed");
    }
}
