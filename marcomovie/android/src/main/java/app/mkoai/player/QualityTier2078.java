package app.mkoai.player;

import java.util.regex.Pattern;

/**
 * mkoai 2.0.78: HD-first / TC-last quality tier for auto source picking.
 *
 * <p>User rule (2026-11-21): prefer HD playback; play a TC (cam) line
 * only when no HD line exists; even if the TC line is the fastest, its
 * priority goes last.
 *
 * <p>The signal is the LINE NAME — that is where CMS sources label
 * quality ("高清" / "TC" / "槍版" / "搶先" ...), and it is the same
 * signal the existing line scoring already reads (SourcePreferences
 * lineIndex, MediaOptions, SearchVariants). Episode names are NOT
 * read: a long-running show's "第720集" would false-positive on a
 * numeric HD marker, and quality lives on the line, not the episode.
 *
 * <p>Three tiers, lower is better: HD (0) &gt; normal (1) &gt; TC (2).
 * TC is checked FIRST, so a line labelled "高清TC" counts as TC, not
 * HD — a cam recording does not become HD by sharing a name with one.
 *
 * <p>Where the tier bites (both layers, so the rule holds end to end):
 * <ul>
 *   <li>Within one video — {@link SourcePreferences#lineIndex}: a TC
 *       line takes a {@link #TC_SCORE_PENALTY} (-100,000). That beats
 *       any episode-count gap in practice (a line never carries ~1000
 *       episodes) but stays above the unplayable player-line penalty
 *       (-1,000,000), so a TC line still wins when it is the only
 *       playable line — "if there is no HD, play TC".</li>
 *   <li>Across sources — {@link AutoSourceFallback#compareRanked}: the
 *       tier is a step after the ad-rule source priority and before
 *       probe-verified / cooldown / speed. A TC candidate therefore
 *       loses to an HD candidate at the same priority even when the TC
 *       probe was far faster — "even if TC is fastest, TC goes last".
 *       The ad-rule priority (巨量 / 360 / 魔都動漫 / xgzy) still wins
 *       first: it is the user's explicit choice for ad handling, and
 *       quality is a ranking signal like speed, not a replacement for
 *       that choice. The tier is captured when the candidate is
 *       collected (from the line auto-pick would actually play), so
 *       warmed candidates carry it too.</li>
 * </ul>
 *
 * <p>What is deliberately NOT done: the probed stream height
 * (HlsQualityProbe, stored in the verdict cache) is not folded in —
 * the probe runs after the candidate is collected, and the names are
 * what the user means by HD/TC. A manually picked line (the picker,
 * or a saved "預設播放線路") bypasses the auto-pick entirely, exactly
 * as before.
 */
final class QualityTier2078 {
    /** HD line (高清 / 超清 / 4K / 藍光 / 1080 / 720P). */
    static final int TIER_HD = 0;

    /** Ordinary line (no quality marker either way). */
    static final int TIER_NORMAL = 1;

    /** TC line (cam recording: TC / TS / CAM / 槍版 / 搶先). */
    static final int TIER_TC = 2;

    /**
     * TC penalty in SourcePreferences.lineIndex scoring. Decisive
     * against any realistic episode-count gap, still above the
     * player-line penalty so a lone TC line remains playable.
     */
    static final int TC_SCORE_PENALTY = 100_000;

    /**
     * TC / TS / CAM as a standalone token. The surrounding
     * non-alphanumeric guard keeps "Watch" (wa-TC-h) from matching
     * while "TC", "TC線", "高清TC" and "HD-TC" all match.
     */
    private static final Pattern TC_TOKEN =
            Pattern.compile("(?i)(^|[^a-z0-9])(tc|ts|cam)([^a-z0-9]|$)");

    private QualityTier2078() {}

    /** True when the line name marks a cam (TC) recording. Null-safe. */
    static boolean isTc(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.contains("枪版")
                || name.contains("槍版")
                || name.contains("抢先")
                || name.contains("搶先")) return true;
        return TC_TOKEN.matcher(name).find();
    }

    /** True when the line name marks an HD stream. Null-safe. */
    static boolean isHd(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("4k")
                || n.contains("藍光")
                || n.contains("蓝光")
                || n.contains("1080")
                || n.contains("720p")
                || n.contains("超清")
                || n.contains("高清");
    }

    /** Tier of one line name: TC (2) beats HD (0) when both are present. */
    static int tierOf(String lineName) {
        if (isTc(lineName)) return TIER_TC;
        if (isHd(lineName)) return TIER_HD;
        return TIER_NORMAL;
    }

    /**
     * Tier of the line auto-pick would actually play for this detail
     * (the SourcePreferences.lineIndex pick, honouring a saved
     * preferred line and the hide-netdisk setting). A detail with no
     * lines counts as normal — never TC, never HD.
     */
    static int tierForDetail(Cms.Video detail, String preferredLine, boolean hideNetdisk) {
        if (detail == null || detail.lines == null || detail.lines.isEmpty()) {
            return TIER_NORMAL;
        }
        int idx = SourcePreferences.lineIndex(preferredLine, hideNetdisk, detail);
        if (idx < 0 || idx >= detail.lines.size()) return TIER_NORMAL;
        Cms.Line line = detail.lines.get(idx);
        return tierOf(line == null ? null : line.name);
    }
}
