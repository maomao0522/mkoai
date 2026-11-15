package app.mkoai.player;

import java.util.ArrayList;
import java.util.List;

/** Explicit defaults remain separate from a one-time manual source switch. */
final class SourcePreferences {
    private static String identity(Cms.Site site) {
        return site.key + "\n" + site.api;
    }

    static boolean isPreferred(MainActivity a, Cms.Site site) {
        return identity(site).equals(a.prefs.getString(Prefs.PREFERRED_SITE, ""));
    }

    static void restoreDefault(MainActivity a) {
        if (a.config == null) return;
        for (Cms.Site site : a.config.sites) {
            if (isPreferred(a, site) && a.available(site)) {
                a.prefs.edit().putString(Prefs.SELECTED_SITE, site.key).apply();
                return;
            }
        }
    }

    static void choose(MainActivity a) {
        if (a.config == null) return;
        List<Cms.Site> sites = new ArrayList<>();
        for (Cms.Site site : a.config.sites) if (a.available(site)) sites.add(site);
        String[] names = new String[sites.size() + 1];
        names[0] = "跟隨上次選擇";
        for (int i = 0; i < sites.size(); i++)
            names[i + 1] = (isPreferred(a, sites.get(i)) ? "★ " : "") + sites.get(i).name;
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("預設偏好源")
                .setItems(
                        names,
                        (d, index) -> {
                            a.prefs
                                    .edit()
                                    .putString(
                                            Prefs.PREFERRED_SITE,
                                            index == 0 ? "" : identity(sites.get(index - 1)))
                                    .apply();
                            a.toast(index == 0 ? "已取消預設偏好源" : "已儲存預設偏好源");
                            SourcePicker.show(a);
                        })
                .show();
    }

    /**
     * Auto-select the BEST playback line (mkoai): the user never picks a line by hand.
     * User's preferred line (set via 「預設播放線路」) still wins, except a
     * player (playback-page) line, which never beats an available m3u8
     * line (2.0.45: it cannot play). Otherwise score each line: most
     * episodes first (most complete), then quality markers in the line name
     * (line names come from the source, so both Simplified and Traditional forms count).
     */
    /** mkoai: name-only netdisk check (used where the site is not available). */
    static boolean isNetdiskLineName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("网盘")
                || n.contains("網盤")
                || n.contains("百度")
                || n.contains("摆渡")
                || n.contains("夸克")
                || n.contains("夸父")
                || n.contains("阿里")
                || n.contains("天翼")
                || n.contains("原画")
                || n.contains("原畫")
                || n.contains("115")
                || n.contains("123")
                || n.contains("pan")
                || n.contains("quark")
                || n.contains("baidu")
                || n.contains("uc");
    }

    /** mkoai: true when this source is a netdisk (cloud drive) source, by name. Used to exclude netdisk sources from global search when the user hides them. */
    static boolean isNetdiskSite(Cms.Site site) {
        if (site == null || site.name == null) return false;
        String n = site.name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("网盘")
                || n.contains("網盤")
                || n.contains("云盘")
                || n.contains("雲盤")
                || n.contains("四盘")
                || n.contains("四盤")
                || n.contains("百度")
                || n.contains("摆渡")
                || n.contains("夸克")
                || n.contains("夸父")
                || n.contains("阿里")
                || n.contains("天翼")
                || n.contains("115")
                || n.contains("123")
                || n.contains("pan")
                || n.contains("quark")
                || n.contains("baidu");
    }

    /**
     * mkoai 2.0.45: a "player" line (playback-page URL, e.g. jlplayer)
     * can never be fed to ExoPlayer directly, so it ranks last — it is
     * only picked when no other line can play. Case-insensitive; a
     * null name counts as neither.
     */
    static boolean isPlayerLineName(String name) {
        if (name == null) return false;
        return name.toLowerCase(java.util.Locale.ROOT).contains("player");
    }

    /** mkoai 2.0.45: a line whose name promises a direct m3u8 stream. */
    static boolean isM3u8LineName(String name) {
        if (name == null) return false;
        return name.toLowerCase(java.util.Locale.ROOT).contains("m3u8");
    }

    static int lineIndex(MainActivity a, Cms.Video video) {
        return lineIndex(
                a.prefs.getString(Prefs.PREFERRED_LINE, ""),
                a.prefs.getBoolean(Prefs.HIDE_NETDISK, false),
                video);
    }

    /**
     * Pure line ranking (no Android — tested by TestLinePick2045).
     * The user's saved preferred line still wins, EXCEPT a player line:
     * a saved player-line preference never beats an available m3u8
     * line, because the player line cannot play at all (its URL is a
     * playback page, not media). A manual tap on a line in the picker
     * bypasses this entirely — this only governs auto-pick.
     *
     * <p>Scoring, per line with episodes: most episodes first, then
     * quality markers — plus a decisive m3u8 bonus / player penalty
     * (+/-1,000,000, far above any realistic episode-count gap of
     * episodes*100), so a direct m3u8 line always beats a playback-page
     * line and a player line always loses to any playable line. With
     * only player lines left, the best of them is still returned (never
     * null / never "no line").
     */
    static int lineIndex(String preferredLine, boolean hideNetdisk, Cms.Video video) {
        if (video == null || video.lines == null || video.lines.isEmpty()) return 0;
        if (preferredLine != null && !preferredLine.isEmpty()) {
            for (int i = 0; i < video.lines.size(); i++) {
                Cms.Line line = video.lines.get(i);
                if (line == null
                        || line.name == null
                        || !line.name.equals(preferredLine)
                        || line.episodes.isEmpty()) continue;
                // A saved player-line preference must not win over an
                // m3u8 line that could actually play.
                if (isPlayerLineName(line.name)
                        && hasPlayableM3u8Line(video, hideNetdisk, i)) {
                    break; // fall through to scoring
                }
                return i;
            }
        }
        int best = -1, bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < video.lines.size(); i++) {
            Cms.Line line = video.lines.get(i);
            if (line == null || line.episodes.isEmpty()) continue;
            // mkoai: when the user hides netdisk features, never auto-pick a netdisk
            // line (the detail page already drops them, this is a safety net).
            if (hideNetdisk && isNetdiskLineName(line.name)) continue;
            int score = line.episodes.size() * 100;
            String name = line.name == null ? "" : line.name;
            if (name.contains("4K") || name.contains("藍光") || name.contains("蓝光")) score += 30;
            if (name.contains("秒播")) score += 20;
            if (name.contains("不卡")) score += 15;
            if (name.contains("高清") || name.contains("多線") || name.contains("多线")) score += 10;
            if (name.contains("1080")) score += 5;
            // mkoai 2.0.45: prefer a direct m3u8 line, rank a playback-page
            // ("player") line last — verified live 2026-11-14: 巨量's
            // 《艾倫的宇宙》 carries jlm3u8 (direct m3u8) and jlplayer
            // (playback page); auto-pick used to take jlplayer and the
            // player failed with error 3002.
            if (isM3u8LineName(name)) score += 1_000_000;
            if (isPlayerLineName(name)) score -= 1_000_000;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best >= 0 ? best : 0;
    }

    /** True when some line other than {@code skipIndex} is a playable m3u8 line. */
    private static boolean hasPlayableM3u8Line(
            Cms.Video video, boolean hideNetdisk, int skipIndex) {
        for (int i = 0; i < video.lines.size(); i++) {
            if (i == skipIndex) continue;
            Cms.Line line = video.lines.get(i);
            if (line == null || line.episodes.isEmpty()) continue;
            if (hideNetdisk && isNetdiskLineName(line.name)) continue;
            if (isM3u8LineName(line.name)) return true;
        }
        return false;
    }

    /**
     * mkoai 2.0.42: priority sources — ad-free sources the user asked to
     * prefer. Auto-select and cross-source search try these first, in list
     * order (a source is used only when it actually has the title; when it
     * doesn't, or playback fails, selection falls back to the other
     * sources).
     *
     * <p>mkoai 2.0.61 (k): the three sources with HLS ad-filter rules are
     * now the top preference — their ads can be removed — in this order:
     * 巨量 (least ad time, ~38.7s), 360 (~140.5s), 魔都動漫 (~141.1s).
     * xgzy (previously #2, chosen because its lines have no burned-in ads)
     * moves to just after them. Identified by api host substring — stable
     * across config URL variants (360 is "360zyzz.com/api.php/provide/vod",
     * 魔都動漫 is "www.mdzyapi.com/api.php/provide/vod/"); the same host
     * matching the ad rules use (Cms.builtinHlsAdRules). Extensible: append
     * a new host here to prefer another source.
     */
    static final String[] PRIORITY_SOURCE_HOSTS = {
        "api.juliang.live",   // 巨量
        "360zyzz.com",        // 360
        "mdzyapi.com",        // 魔都動漫
        "caiji.xgzyapi.com",  // xgzy
    };

    /** True when this api URL belongs to the given priority host. */
    static boolean apiMatchesHost(String api, String host) {
        if (api == null || host == null || host.isEmpty()) return false;
        return api.toLowerCase(java.util.Locale.ROOT)
                .contains(host.toLowerCase(java.util.Locale.ROOT));
    }

    /** Index of this api URL in the priority list, or -1 when not prioritized. */
    static int priorityIndex(String api) {
        if (api == null) return -1;
        for (int i = 0; i < PRIORITY_SOURCE_HOSTS.length; i++) {
            if (apiMatchesHost(api, PRIORITY_SOURCE_HOSTS[i])) return i;
        }
        return -1;
    }

    /** Index of this source in the priority list, or -1 when not prioritized. */
    static int priorityIndex(Cms.Site site) {
        return site == null ? -1 : priorityIndex(site.api);
    }

    /**
     * Full api URL of the highest-priority source present in the config
     * (巨量 first), or "" when none. Used where a concrete api URL is
     * needed (e.g. a live poster fetch) rather than just the order.
     */
    static String firstPriorityApi(MainActivity a) {
        if (a == null || a.config == null || a.config.sites == null) return "";
        for (String host : PRIORITY_SOURCE_HOSTS) {
            for (Cms.Site s : a.config.sites) {
                if (s != null && apiMatchesHost(s.api, host))
                    return s.api == null ? "" : s.api;
            }
        }
        return "";
    }

    static List<Cms.Site> prioritized(MainActivity a, List<Cms.Site> sites) {
        return prioritized(a.prefs.getString(Prefs.PREFERRED_SITE, ""), sites);
    }

    /** Pure ordering (no Android): preferred identity first, then the
     * priority (ad-free) sources in list order, then the rest in config
     * order. Tested by TestPriority2042. */
    static List<Cms.Site> prioritized(String preferredIdentity, List<Cms.Site> sites) {
        List<Cms.Site> ordered = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // 1. the user's explicit manual preferred site still wins.
        for (Cms.Site site : sites) {
            if (preferredIdentity.equals(identity(site))) {
                ordered.add(site);
                seen.add(identity(site));
            }
        }
        // 2. priority (ad-free) sources, in list order.
        for (String host : PRIORITY_SOURCE_HOSTS) {
            for (Cms.Site site : sites) {
                if (apiMatchesHost(site.api, host) && !seen.contains(identity(site))) {
                    ordered.add(site);
                    seen.add(identity(site));
                }
            }
        }
        // 3. everything else, config order preserved.
        for (Cms.Site site : sites) {
            if (!seen.contains(identity(site))) ordered.add(site);
        }
        return ordered;
    }

    static void chooseLine(MainActivity a, Cms.Video video) {
        String[] names = new String[video.lines.size() + 1];
        names[0] = "自動選擇";
        String preferred = a.prefs.getString(Prefs.PREFERRED_LINE, "");
        for (int i = 0; i < video.lines.size(); i++) {
            String name = video.lines.get(i).name;
            names[i + 1] = (name.equals(preferred) ? "★ " : "") + name;
        }
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("預設播放線路")
                .setItems(
                        names,
                        (d, index) -> {
                            a.prefs
                                    .edit()
                                    .putString(
                                            Prefs.PREFERRED_LINE,
                                            index == 0 ? "" : video.lines.get(index - 1).name)
                                    .apply();
                            a.toast("已儲存，下次開啟影片時優先選擇；觀看記錄仍優先續播");
                        })
                .show();
    }
}
