package app.mkoai.player;

/**
 * mkoai 2.0.55 JVM checks (pure Java, no Android): the episode-matching
 * gate that decides whether a play press can open the player immediately
 * with the already-shown source.
 *
 * detailHasEpisode (AutoSourceFallback) needs MainActivity at compile
 * time, so this test exercises its real building blocks instead: real
 * Cms.Video/Line/Episode objects + real WatchHistory.normEpisode, with
 * the same iterate-and-match predicate the gate uses. The runner also
 * statically asserts the real method body carries both comparisons.
 */
public final class PlayFlow2055Checks {
    static int checks = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        checks++;
        System.out.println("PASS " + name);
    }

    /** Mirror of AutoSourceFallback.detailHasEpisode's match rule. */
    static boolean hasEpisode(Cms.Video detail, String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return true;
        if (detail == null || detail.lines == null) return false;
        String wantNorm = WatchHistory.normEpisode(wanted);
        String wantTrim = wanted.trim();
        for (Cms.Line line : detail.lines) {
            if (line == null || line.episodes == null) continue;
            for (Cms.Episode ep : line.episodes) {
                if (ep == null || ep.name == null) continue;
                if (WatchHistory.normEpisode(ep.name).equals(wantNorm)
                        || ep.name.trim().equals(wantTrim)) return true;
            }
        }
        return false;
    }

    static Cms.Video detailWith(String lineName, String... eps) {
        Cms.Video v = new Cms.Video();
        Cms.Line line = new Cms.Line();
        line.name = lineName;
        for (String name : eps) {
            Cms.Episode ep = new Cms.Episode();
            ep.name = name;
            line.episodes.add(ep);
        }
        v.lines.add(line);
        return v;
    }

    public static void main(String[] args) {
        // normEpisode: the building block of the immediate-play gate.
        check(WatchHistory.normEpisode("第01集").equals("第1集"),
                "normEpisode strips leading zeros");
        check(WatchHistory.normEpisode("第 1 集").equals("第1集"),
                "normEpisode strips whitespace");
        check(WatchHistory.normEpisode("第1話").equals("第1集"),
                "normEpisode unifies 話 to 集");
        check(WatchHistory.normEpisode("第01話").equals("第1集"),
                "normEpisode unifies 話 and strips zeros");
        check(WatchHistory.normEpisode(null).equals(""), "normEpisode null is empty");
        check(WatchHistory.normEpisode("正片").equals("正片"),
                "normEpisode leaves 正片 alone");

        // The gate predicate on real Cms objects (mirrors detailHasEpisode).
        Cms.Video jlm = detailWith("jlm3u8", "正片");
        check(hasEpisode(jlm, "正片"), "gate: shown detail carries 正片 -> play now");
        check(hasEpisode(jlm, null), "gate: null wanted episode always matches");
        check(hasEpisode(jlm, "  "), "gate: blank wanted episode always matches");
        check(!hasEpisode(jlm, "第1集"), "gate: shown detail lacks 第1集 -> fresh check");
        Cms.Video multi = detailWith("jlm3u8", "第01集", "第02集");
        check(hasEpisode(multi, "第1集"), "gate: 第01集 matches wanted 第1集");
        check(hasEpisode(multi, "第 2 集"), "gate: 第 2 集 matches stored 第02集");
        check(!hasEpisode(multi, "第3集"), "gate: 第3集 not carried -> fresh check");
        check(hasEpisode(multi, "第1話"), "gate: 第1話 norm-matches 第01集 (same episode)");
        Cms.Video none = new Cms.Video();
        check(!hasEpisode(none, "正片"), "gate: detail with no lines cannot play now");
        check(!hasEpisode(null, "正片"), "gate: null detail cannot play now");
        System.out.println(checks + " PlayFlow2055 checks passed");
    }
}
