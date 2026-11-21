package app.mkoai.player;

/** JVM checks for the 2.0.78 HD-first / TC-last quality tier (pure part only). */
public class QualityTier2078Checks {
    static int total = 0;
    static int failed = 0;

    static void check(boolean cond, String name) {
        total++;
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static Cms.Line line(String name, int episodes) {
        Cms.Line l = new Cms.Line();
        l.name = name;
        for (int i = 0; i < episodes; i++) {
            Cms.Episode e = new Cms.Episode();
            e.name = "第" + (i + 1) + "集";
            e.url = "https://example.com/" + (i + 1) + ".m3u8";
            l.episodes.add(e);
        }
        return l;
    }

    static Cms.Video video(Cms.Line... lines) {
        Cms.Video v = new Cms.Video();
        v.title = "測試片";
        for (Cms.Line l : lines) v.lines.add(l);
        return v;
    }

    static AutoSourceFallback.RankedCandidate rc(
            int tier, int priority, boolean hasEpisode, boolean verified,
            long latencyMs, long emaMs, boolean coolingDown) {
        Cms.Site s = new Cms.Site();
        s.key = "x";
        s.name = "x";
        s.api = "https://example.com/api.php/provide/vod/";
        Cms.Video v = new Cms.Video();
        return new AutoSourceFallback.RankedCandidate(
                new AutoSourceFallback.Playable(s, v, v),
                hasEpisode, verified, priority, latencyMs, emaMs, coolingDown, tier);
    }

    public static void main(String[] args) {
        // ---- isTc: TC / TS / CAM tokens + CJK cam labels ----
        check(QualityTier2078.isTc("TC"), "isTc TC");
        check(QualityTier2078.isTc("tc"), "isTc lowercase tc");
        check(QualityTier2078.isTc("TC線"), "isTc TC線");
        check(QualityTier2078.isTc("高清TC"), "isTc 高清TC");
        check(QualityTier2078.isTc("槍版"), "isTc 槍版");
        check(QualityTier2078.isTc("抢先版"), "isTc 抢先版");
        check(QualityTier2078.isTc("TS"), "isTc TS");
        check(QualityTier2078.isTc("CAM"), "isTc CAM");
        check(!QualityTier2078.isTc("Watch"), "isTc Watch is NOT tc (token guard)");
        check(!QualityTier2078.isTc("高清"), "isTc 高清 is not TC");
        check(!QualityTier2078.isTc("量子"), "isTc 量子 is not TC");
        check(!QualityTier2078.isTc(null), "isTc null is not TC");
        check(!QualityTier2078.isTc(""), "isTc empty is not TC");

        // ---- isHd: HD markers, SD markers stay out ----
        check(QualityTier2078.isHd("高清"), "isHd 高清");
        check(QualityTier2078.isHd("超清"), "isHd 超清");
        check(QualityTier2078.isHd("4K"), "isHd 4K");
        check(QualityTier2078.isHd("藍光"), "isHd 藍光");
        check(QualityTier2078.isHd("蓝光"), "isHd 蓝光");
        check(QualityTier2078.isHd("1080P"), "isHd 1080P");
        check(QualityTier2078.isHd("1080"), "isHd 1080");
        check(QualityTier2078.isHd("720P"), "isHd 720P");
        check(!QualityTier2078.isHd("普清"), "isHd 普清 is not HD");
        check(!QualityTier2078.isHd("流暢"), "isHd 流暢 is not HD");
        check(!QualityTier2078.isHd("標清"), "isHd 標清 is not HD");
        check(!QualityTier2078.isHd(null), "isHd null is not HD");

        // ---- tierOf: TC beats HD when both are present ----
        check(QualityTier2078.tierOf("高清TC") == QualityTier2078.TIER_TC,
                "tierOf 高清TC = TC (TC beats HD)");
        check(QualityTier2078.tierOf("高清") == QualityTier2078.TIER_HD,
                "tierOf 高清 = HD");
        check(QualityTier2078.tierOf("量子") == QualityTier2078.TIER_NORMAL,
                "tierOf 量子 = NORMAL");
        check(QualityTier2078.tierOf(null) == QualityTier2078.TIER_NORMAL,
                "tierOf null = NORMAL");

        // ---- tierForDetail: tier of the line auto-pick would play ----
        Cms.Video hdFewTcMany = video(line("高清", 2), line("TC", 100));
        check(QualityTier2078.tierForDetail(hdFewTcMany, "", false)
                        == QualityTier2078.TIER_HD,
                "tierForDetail HD line (2 eps) beats TC line (100 eps)");
        Cms.Video onlyTc = video(line("TC", 10), line("槍版", 5));
        check(QualityTier2078.tierForDetail(onlyTc, "", false)
                        == QualityTier2078.TIER_TC,
                "tierForDetail all-TC video = TC");
        Cms.Video onlyNormal = video(line("量子", 10), line("非凡", 8));
        check(QualityTier2078.tierForDetail(onlyNormal, "", false)
                        == QualityTier2078.TIER_NORMAL,
                "tierForDetail no markers = NORMAL");
        Cms.Video prefTc = video(line("高清", 10), line("TC", 10));
        check(QualityTier2078.tierForDetail(prefTc, "TC", false)
                        == QualityTier2078.TIER_TC,
                "tierForDetail saved preferred TC line is honoured (tier TC)");
        check(QualityTier2078.tierForDetail(null, "", false)
                        == QualityTier2078.TIER_NORMAL,
                "tierForDetail null detail = NORMAL");
        check(QualityTier2078.tierForDetail(video(), "", false)
                        == QualityTier2078.TIER_NORMAL,
                "tierForDetail no lines = NORMAL");

        // ---- lineIndex: TC penalty (2.0.78) ----
        Cms.Video tcManyVsNormalOne = video(line("TC", 100), line("量子", 1));
        check(SourcePreferences.lineIndex("", false, tcManyVsNormalOne) == 1,
                "lineIndex TC (100 eps) loses to normal (1 ep)");
        Cms.Video tcM3u8VsNormalM3u8 =
                video(line("TC-m3u8", 50), line("高清m3u8", 1));
        check(SourcePreferences.lineIndex("", false, tcM3u8VsNormalM3u8) == 1,
                "lineIndex TC m3u8 loses to HD m3u8");
        Cms.Video allTc = video(line("TC", 5), line("槍版", 10));
        check(SourcePreferences.lineIndex("", false, allTc) == 1,
                "lineIndex all-TC still picks the best TC line (never null)");

        // ---- compareRanked: quality beats speed, not priority ----
        AutoSourceFallback.RankedCandidate hdSlow =
                rc(QualityTier2078.TIER_HD, 1, true, false, 9999, -1, false);
        AutoSourceFallback.RankedCandidate tcFast =
                rc(QualityTier2078.TIER_TC, 1, true, true, 10, -1, false);
        check(AutoSourceFallback.compareRanked(hdSlow, tcFast) < 0,
                "compareRanked HD (slow, unverified) beats TC (fast, verified)");
        AutoSourceFallback.RankedCandidate normalMid =
                rc(QualityTier2078.TIER_NORMAL, 1, true, false, 5000, -1, false);
        check(AutoSourceFallback.compareRanked(normalMid, tcFast) < 0,
                "compareRanked NORMAL beats TC even when TC is faster");
        check(AutoSourceFallback.compareRanked(hdSlow, normalMid) < 0,
                "compareRanked HD beats NORMAL");
        AutoSourceFallback.RankedCandidate tcTopPriority =
                rc(QualityTier2078.TIER_TC, 0, true, false, 10, -1, false);
        AutoSourceFallback.RankedCandidate hdLowPriority =
                rc(QualityTier2078.TIER_HD, 1, true, false, 10, -1, false);
        check(AutoSourceFallback.compareRanked(tcTopPriority, hdLowPriority) < 0,
                "compareRanked ad-rule priority still beats quality (pri 0 TC wins)");
        AutoSourceFallback.RankedCandidate hdNoEpisode =
                rc(QualityTier2078.TIER_HD, 1, false, false, 10, -1, false);
        AutoSourceFallback.RankedCandidate tcHasEpisode =
                rc(QualityTier2078.TIER_TC, 1, true, false, 10, -1, false);
        check(AutoSourceFallback.compareRanked(tcHasEpisode, hdNoEpisode) < 0,
                "compareRanked carrying the episode still beats quality");
        AutoSourceFallback.RankedCandidate hdFast =
                rc(QualityTier2078.TIER_HD, 1, true, false, 10, -1, false);
        check(AutoSourceFallback.compareRanked(hdFast, hdSlow) < 0,
                "compareRanked same tier: faster still wins");

        System.out.println("\n" + (total - failed) + " QualityTier2078 checks passed");
        if (failed > 0) System.out.println(failed + " FAILED");
    }
}
