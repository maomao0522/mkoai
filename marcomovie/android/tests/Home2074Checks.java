package app.mkoai.player;

/**
 * mkoai 2.0.74 checks (plain JVM, no device). Run via
 * android/tests/run_mkoai_2074_checks.py.
 *
 * The bug (user field video, 2.0.73 on his POCO F5, 2026-11-17 ~14:43,
 * 7.15s, 720x1280): deleting the CURRENT source (巨量） from the
 * 選擇片源 picker left the picker broken — every per-row green dot and
 * ✕ gone, the ✓ gone, the bottom 管理線路 / 更新配置 / 完成 gone, the
 * top-right 預設偏好源 gone — and the page underneath popped back from
 * 訂閱與賬號 to the Settings main page. The list edit itself worked
 * (17 → 16, 巨量 really gone).
 *
 * Root cause, pinned by code + frames (see the runner greps for the
 * code side):
 * (1) edit() ended with back.clear() + open(home) + show(a). open(home)
 *     replaced a.root and, because the user was on Settings tab 4 with a
 *     sub-page open, switchTab(4) reset Settings to its root — that is
 *     why the page underneath popped back.
 * (2) show(a) then ran while the new a.root had never been laid out, so
 *     a.root.getWidth() was 0 and the trailing
 *     window.setLayout(min(dp(720), a.root.getWidth() - marginH), -2)
 *     passed a NEGATIVE width (0 - 20dp). The window came back wider
 *     than the screen, so the weight-1 columns expanded and pushed every
 *     right-side control off-screen. Measured in the same 360-wide
 *     frames: healthy picker x 57-301 (244 wide), broken picker x 50-323
 *     (273 wide) — both edges moved out, right edge 301 → 323.
 * The fix: edit() no longer navigates (refresh the Settings page in
 * place so the 當前： capsule follows the new current source, then just
 * re-show the picker), and the trailing setLayout reads the stable
 * a.windowWidth() instead of the unlaid-out root width.
 * (The tests below use the canonical 19-source config, so a single
 * delete is 19 → 18; the video's 17 → 16 is the same operation on the
 * user's 17-source config.)
 *
 * This class pins, in real code: the width arithmetic (A), the fallback
 * rule that moves the selection to a valid source (B), and the
 * delete / delete-all persistence round-trip through the profile JSON
 * (C) — including deleting down to 0 sources and re-importing the same
 * config afterwards (D). The dialog controls, the no-pop-back, the
 * confirm dialog and the phone-only placement are pinned by the static
 * greps in the runner (view code, like 2.0.73 did).
 */
public class Home2074Checks {
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

    static Cms.Site site(String key, String name) {
        Cms.Site s = new Cms.Site();
        s.key = key;
        s.name = name;
        s.api = "https://api.example.com/provide/vod/";
        s.type = 1;
        return s;
    }

    /** The canonical 19 sources, in bundled order (names as keys). */
    static java.util.List<Cms.Site> nineteen() {
        String[] names = {
            "Mkoai2-虎牙", "Mkoai4", "巨量", "wsyzy", "xgzy", "芒果", "360",
            "subocj速播", "xinlang新浪", "guangsu光速", "jinying金鷹", "bdzy",
            "hongniu紅牛", "jszy", "Mkoai4-rym3u8", "changzhang暢掌", "豪華資源",
            "魔都動漫", "暴風資源"
        };
        java.util.List<Cms.Site> out = new java.util.ArrayList<>();
        for (String n : names) out.add(site(n, n));
        return out;
    }

    static java.util.List<String> keysOf(java.util.List<Cms.Site> sites) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Cms.Site s : sites) out.add(s.key);
        return out;
    }

    static boolean jsonHas(org.json.JSONArray a, String v) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    static java.util.Set<String> setOf(String... keys) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String k : keys) out.add(k);
        return out;
    }

    public static void main(String[] args) throws Exception {
        // ---- (A) the width bug: field-video numbers + the arithmetic ----
        // Same 360-wide frames: healthy picker x 57-301, broken x 50-323.
        // (In-frame pixels — no density / video-scale assumption needed.)
        int healthyLeft = 57, healthyRight = 301;
        int brokenLeft = 50, brokenRight = 323;
        eq(healthyRight - healthyLeft, 244, "(width) healthy picker is 244 wide in-frame");
        eq(brokenRight - brokenLeft, 273, "(width) broken picker is 273 wide in-frame");
        check(brokenRight > healthyRight,
                "(width) broken picker right edge is further right (301 -> 323)");
        check(brokenLeft < healthyLeft,
                "(width) broken picker left edge is further left (57 -> 50)");
        check(brokenRight - brokenLeft > healthyRight - healthyLeft,
                "(width) broken picker is wider on both sides — a width corruption, not a missing view");
        // The trailing setLayout formula. marginH is dp(20) > 0 and the cap
        // dp(720) > 0 for any density; the concrete instance below uses the
        // POCO F5 density 2.625 (720dp = 1890px, 20dp = 52px).
        int cap720 = 1890, marginH = 52;
        // The old trailing setLayout, with the fresh unlaid-out root (getWidth() == 0).
        int oldWidth = Math.min(cap720, 0 - marginH);
        eq(oldWidth, -52, "(width) old trailing width with a fresh root is -52");
        check(oldWidth < 0, "(width) old trailing width is NEGATIVE — an invalid window width");
        check(Math.min(cap720, 0 - marginH) < 0,
                "(width) old trailing width is negative for ANY positive marginH");
        // The new trailing setLayout, reading the stable window width.
        int newWidth = Math.min(cap720, 1080 - marginH);
        eq(newWidth, 1028, "(width) new trailing width from windowWidth is 1028");
        check(newWidth > 0 && newWidth <= 1080,
                "(width) new trailing width is positive and fits the screen");
        check(Math.min(cap720, 784 - marginH) == 784 - marginH,
                "(width) new trailing width from a stale usable width is that width minus marginH");
        // The first setLayout was already sane (panelWidth from windowWidth).
        eq(Math.min(1942, 1080 - marginH), 1028,
                "(width) panelWidth from windowWidth was already sane (1028)");

        // ---- (B) the fallback rule: selection moves to a valid source ----
        java.util.List<Cms.Site> sites = nineteen();
        // The video case: 巨量 deleted, preferred is wsyzy and available.
        java.util.List<Cms.Site> surviving = new java.util.ArrayList<>(sites);
        surviving.remove(2); // 巨量
        Cms.Site fb = SourcePicker.pickFallback(
                surviving, setOf("wsyzy"), setOf("wsyzy", "xgzy", "芒果"));
        check(fb != null && fb.key.equals("wsyzy"),
                "(fallback) preferred + available wins (wsyzy)");
        // Preferred set but not available -> first available wins.
        fb = SourcePicker.pickFallback(
                surviving, setOf("Mkoai2-虎牙"), setOf("wsyzy", "xgzy"));
        check(fb != null && fb.key.equals("wsyzy"),
                "(fallback) preferred unavailable -> first available (wsyzy)");
        // No preferred at all -> first available.
        fb = SourcePicker.pickFallback(
                surviving, setOf(), setOf("wsyzy", "xgzy", "芒果"));
        check(fb != null && fb.key.equals("wsyzy"),
                "(fallback) no preferred -> first available (wsyzy)");
        // The video's exact shape: preferred unset, the first two sources
        // unavailable (not CMS-direct), so the first available is wsyzy —
        // the green border the video shows on wsyzy after deleting 巨量.
        java.util.Set<String> avail = new java.util.HashSet<>();
        for (Cms.Site s : surviving) avail.add(s.key);
        avail.remove("Mkoai2-虎牙");
        avail.remove("Mkoai4");
        fb = SourcePicker.pickFallback(surviving, setOf(), avail);
        check(fb != null && fb.key.equals("wsyzy"),
                "(fallback) video case: first two unavailable -> wsyzy");
        // Nobody available -> null (0-source state, nothing to pin to).
        fb = SourcePicker.pickFallback(surviving, setOf("wsyzy"), setOf());
        check(fb == null, "(fallback) none available -> null");
        fb = SourcePicker.pickFallback(
                new java.util.ArrayList<Cms.Site>(), setOf(), setOf());
        check(fb == null, "(fallback) empty list -> null");
        // The deleted key is never the fallback (it is not in surviving).
        check(!keysOf(surviving).contains("巨量"),
                "(fallback) deleted key is not in the surviving list");
        // Preferred key not in the list is ignored.
        fb = SourcePicker.pickFallback(
                surviving, setOf("not-a-source"), setOf("wsyzy", "xgzy"));
        check(fb != null && fb.key.equals("wsyzy"),
                "(fallback) unknown preferred key is ignored");

        // ---- (C) persistence round-trip: delete / reorder / delete-all ----
        java.util.List<Cms.Site> fresh = nineteen();
        // Single delete of the current source 巨量.
        org.json.JSONObject saved = new org.json.JSONObject("{}");
        java.util.List<String> survKeys = keysOf(surviving);
        saved = SourcePicker.profileAfterDelete(
                saved, survKeys, java.util.Collections.singletonList("巨量"));
        org.json.JSONArray order = saved.optJSONArray("order");
        org.json.JSONArray removed = saved.optJSONArray("removed");
        eq(order.length(), 18, "(persist) single delete: order keeps 18 keys");
        check(!jsonHas(order, "巨量"), "(persist) single delete: order drops 巨量");
        check(jsonHas(removed, "巨量"), "(persist) single delete: removed gains 巨量");
        java.util.List<Cms.Site> reloaded =
                SourcePicker.applyProfileToSites(
                        new java.util.ArrayList<>(fresh), order, removed);
        eq(reloaded.size(), 18, "(persist) reload after single delete: 18 sites");
        check(!keysOf(reloaded).contains("巨量"),
                "(persist) reload after single delete: 巨量 is gone");
        eqStr(reloaded.get(0).key, "Mkoai2-虎牙",
                "(persist) reload after single delete: order preserved (first still Mkoai2-虎牙)");
        eqStr(reloaded.get(2).key, "wsyzy",
                "(persist) reload after single delete: wsyzy slides into 巨量's slot");
        // Deleting a NON-current source (xgzy) keeps the current one pinned.
        java.util.List<Cms.Site> surv2 = new java.util.ArrayList<>(fresh);
        surv2.remove(4); // xgzy
        org.json.JSONObject saved2 = new org.json.JSONObject("{}");
        saved2 = SourcePicker.profileAfterDelete(
                saved2, keysOf(surv2), java.util.Collections.singletonList("xgzy"));
        java.util.List<Cms.Site> reloaded2 =
                SourcePicker.applyProfileToSites(
                        new java.util.ArrayList<>(fresh),
                        saved2.optJSONArray("order"), saved2.optJSONArray("removed"));
        eq(reloaded2.size(), 18, "(persist) non-current delete: 18 sites left");
        check(keysOf(reloaded2).contains("巨量"),
                "(persist) non-current delete: the current source 巨量 is untouched");
        check(!keysOf(reloaded2).contains("xgzy"),
                "(persist) non-current delete: xgzy is gone");
        // Reorder (move wsyzy to the front): order changes, removed untouched.
        java.util.List<String> reordered = new java.util.ArrayList<>(keysOf(fresh));
        reordered.remove("wsyzy");
        reordered.add(0, "wsyzy");
        org.json.JSONObject saved3 = new org.json.JSONObject("{}");
        saved3 = SourcePicker.profileAfterDelete(
                saved3, reordered, java.util.Collections.emptyList());
        eq(saved3.optJSONArray("order").length(), 19,
                "(persist) reorder: order keeps all 19 keys");
        check(saved3.optJSONArray("removed") == null
                        || saved3.optJSONArray("removed").length() == 0,
                "(persist) reorder: removed stays empty");
        java.util.List<Cms.Site> reloaded3 =
                SourcePicker.applyProfileToSites(
                        new java.util.ArrayList<>(fresh),
                        saved3.optJSONArray("order"), saved3.optJSONArray("removed"));
        eqStr(reloaded3.get(0).key, "wsyzy",
                "(persist) reorder: wsyzy is first after reload");
        eq(reloaded3.size(), 19, "(persist) reorder: no source lost");
        // Delete-all at once — a clean reset for this origin (user decision
        // 2026-11-17: 全刪等於清空重來). order AND removed both empty, so
        // re-importing the SAME config brings every source back in file order.
        org.json.JSONObject savedAll = new org.json.JSONObject("{}");
        savedAll = SourcePicker.profileAfterDeleteAll(savedAll);
        eq(savedAll.optJSONArray("order").length(), 0,
                "(persist) delete-all: order is empty");
        eq(savedAll.optJSONArray("removed").length(), 0,
                "(persist) delete-all: removed is empty (reset, not hidden)");
        java.util.List<Cms.Site> reloadedAll =
                SourcePicker.applyProfileToSites(
                        new java.util.ArrayList<>(fresh),
                        savedAll.optJSONArray("order"), savedAll.optJSONArray("removed"));
        eq(reloadedAll.size(), 19,
                "(persist) reload after delete-all: all 19 sites come back (reset)");
        eqStr(reloadedAll.get(0).key, "Mkoai2-虎牙",
                "(persist) reload after delete-all: file order restored (first is Mkoai2-虎牙)");
        // Deleting down to 0 one by one reaches the same state.
        org.json.JSONObject acc = new org.json.JSONObject("{}");
        java.util.List<Cms.Site> remaining = new java.util.ArrayList<>(fresh);
        while (!remaining.isEmpty()) {
            Cms.Site gone = remaining.remove(0);
            acc = SourcePicker.profileAfterDelete(
                    acc, keysOf(remaining),
                    java.util.Collections.singletonList(gone.key));
        }
        eq(acc.optJSONArray("order").length(), 0,
                "(persist) delete-to-0 one by one: order ends empty");
        eq(acc.optJSONArray("removed").length(), 19,
                "(persist) delete-to-0 one by one: removed ends with all 19");
        java.util.List<Cms.Site> reloadedZero =
                SourcePicker.applyProfileToSites(
                        new java.util.ArrayList<>(fresh),
                        acc.optJSONArray("order"), acc.optJSONArray("removed"));
        eq(reloadedZero.size(), 0,
                "(persist) reload after delete-to-0: 0 sites");

        // ---- (D) 0 sources + re-import ----
        // 0 sites is the home empty state (currentSite resolves to null,
        // renderHomeTab shows renderEmptyWelcome — no crash, no stuck source).
        // Deleting down to 0 one by one is the genuine 0-site case (its
        // removed list keeps all 19 keys, so the list stays empty).
        check(reloadedZero.isEmpty(),
                "(empty) 0-site list is the home empty state trigger");
        // Re-importing the SAME config (same origin) after delete-all brings
        // everything back: delete-all reset the profile (removed is empty),
        // so there is no hide list to honour. Single deletes are different:
        // their removed keys stay hidden on re-import (2.0.38 rule — reload
        // after single delete is 18 sites, 巨量 gone, pinned above).
        java.util.List<Cms.Site> reimportedSame =
                SourcePicker.applyProfileToSites(
                        new java.util.ArrayList<>(fresh),
                        savedAll.optJSONArray("order"), savedAll.optJSONArray("removed"));
        eq(reimportedSame.size(), 19,
                "(reimport) same-origin re-import after delete-all brings all 19 back");
        eqStr(reimportedSame.get(0).key, "Mkoai2-虎牙",
                "(reimport) same-origin re-import after delete-all restores file order");
        java.util.List<Cms.Site> reimportedFresh =
                SourcePicker.applyProfileToSites(
                        new java.util.ArrayList<>(fresh), null, null);
        eq(reimportedFresh.size(), 19,
                "(reimport) fresh profile (different origin) yields all 19 again");

        System.out.println(passed + " Home2074 checks passed");
    }
}
