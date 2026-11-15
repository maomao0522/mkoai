// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

/**
 * mkoai 2.0.61: phone-home row definitions and layout constants for the
 * reference-app restyle (Terminus+ Emby client). Pure Java — no Android
 * classes — so the row order, labels and year lines are JVM-testable.
 *
 * First try: Simplified Chinese labels (user: 先用簡體字試試看）; the
 * row CONTENTS are still mkoai's own (TMDB + the user's CMS sources),
 * only the layout and style match the reference. Rows that cannot be
 * filled are hidden by the caller (fail-soft) — never an empty row.
 */
final class HomeRows2061 {
    private HomeRows2061() {}

    /** Muted blue-grey home background (reference app), NOT pure black. */
    static final int HOME_SLATE_BG = 0xff6b7683;

    /** Year text under a card title (reference app): "2026" or "2026 - 现在". */
    static final int YEAR_TEXT_COLOR = 0xffd7dde2;

    /** 「查看全部 >」 link colour (reference app). */
    static final int VIEW_ALL_COLOR = 0xffd7dde2;

    /** TMDB kinds (same names TmdbClient.list() already understands). */
    static final String KIND_DOUBAN_TOP = "douban_top";

    /**
     * mkoai 2.0.62: external-ranking row kinds. NOT TmdbClient kinds —
     * the caller fetches them from TraktHome / SimklHome / TvmazeHome
     * instead (same pattern as KIND_DOUBAN_TOP).
     */
    static final String KIND_TRAKT_MOVIES = "trakt_movies";
    static final String KIND_TRAKT_SHOWS = "trakt_shows";
    static final String KIND_SIMKL_MOVIES = "simkl_movies";
    static final String KIND_SIMKL_SHOWS = "simkl_shows";
    static final String KIND_TVMAZE_TONIGHT = "tvmaze_tonight";

    /**
     * Phone home rows, in the reference app's order (reference rows first,
     * the pre-2.0.61 extras at the end). Each entry is {label, kind, extra}.
     * "douban_top" is NOT a TmdbClient kind — the caller fetches it from
     * DoubanHome instead. "短剧" uses the TMDB episode-runtime proxy
     * (with_runtime.lte=15): if the API ignores it, the row is still valid
     * popular Chinese TV (fail-soft).
     *
     * mkoai 2.0.61 (g): TWO-LEVEL order — broad categories first, then
     * subdivisions （細分）. Broad movies （热门电影/全部电影 + the
     * movie-typed 豆瓣高分） → broad dramas （热门剧集/全部剧集 + the
     * TV-typed Netflix 新片） → 综艺 → the two mixed recommendation
     * rows （最新上架/高分佳作， neither movie- nor drama-typed) →
     * subdivisions (movie subdivisions first, then drama subdivisions,
     * then documentary/kids). No subdivision is ever mixed in among
     * the broad rows. All 25 rows are kept — only the order changed.
     * mkoai 2.0.62: 5 external-ranking rows appended after 「高分佳作」
     * (30 rows total); the two-level order is unchanged.
     */
    static final String[][] TMDB_ROW_DEFS = {
        // Broad movies.
        {"热门电影", "trending_movie", ""},
        {"豆瓣高分", KIND_DOUBAN_TOP, ""},
        {"全部电影", "discover_movie", ""},
        // Broad dramas / series.
        {"热门剧集", "trending_tv", ""},
        {
            "Netflix 新片",
            "discover_tv",
            "with_networks=213&sort_by=first_air_date.desc&first_air_date.lte="
                    + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(new java.util.Date())
        },
        {"全部剧集", "discover_tv", ""},
        // Variety.
        {"综艺", "discover_tv", "with_genres=10764"},
        // Mixed recommendation rows (just before the subdivisions).
        {"最新上架", "latest", ""},
        {"高分佳作", "top_rated", ""},
        // mkoai 2.0.62: external rankings — after the broad/mixed rows,
        // before the subdivisions (same two-level order as 2.0.61).
        {"Trakt 热门电影", KIND_TRAKT_MOVIES, ""},
        {"Trakt 热门剧集", KIND_TRAKT_SHOWS, ""},
        {"Simkl 热门电影", KIND_SIMKL_MOVIES, ""},
        {"Simkl 热门剧集", KIND_SIMKL_SHOWS, ""},
        {"今晚播出", KIND_TVMAZE_TONIGHT, ""},
        // Subdivisions — movies first.
        {"动画电影", "discover_movie", "with_genres=16"},
        {"动作电影", "discover_movie", "with_genres=28"},
        {"国语电影", "discover_movie", "with_original_language=zh"},
        {"儿童电影", "discover_movie", "with_genres=16,10751"},
        // Subdivisions — dramas / series.
        {"短剧", "discover_tv", "with_runtime.lte=15&with_original_language=zh"},
        {"国漫", "discover_tv", "with_genres=16&with_original_language=zh"},
        {"日漫", "discover_tv", "with_genres=16&with_original_language=ja"},
        {"国产剧", "discover_tv", "with_original_language=zh"},
        {"港台剧", "discover_tv", "with_origin_country=HK,TW"},
        {"美剧", "discover_tv", "with_origin_country=US"},
        {"日剧", "discover_tv", "with_origin_country=JP"},
        {"韩剧", "discover_tv", "with_origin_country=KR"},
        {"英剧", "discover_tv", "with_origin_country=GB"},
        // Subdivisions — documentary / kids.
        {"纪录片", "documentary", ""},
        {"儿童中文", "discover_tv", "with_genres=10762&with_original_language=zh"},
        {"儿童英语", "discover_tv", "with_genres=10762&with_original_language=en"},
    };

    /** True when this row kind is Douban-backed (fetched by DoubanHome, not TMDB). */
    static boolean isDoubanRow(String kind) {
        return KIND_DOUBAN_TOP.equals(kind);
    }

    /** True when this row kind is Trakt-backed (fetched by TraktHome, not TMDB). */
    static boolean isTraktRow(String kind) {
        return KIND_TRAKT_MOVIES.equals(kind) || KIND_TRAKT_SHOWS.equals(kind);
    }

    /** True when this row kind is Simkl-backed (fetched by SimklHome, not TMDB). */
    static boolean isSimklRow(String kind) {
        return KIND_SIMKL_MOVIES.equals(kind) || KIND_SIMKL_SHOWS.equals(kind);
    }

    /** True when this row kind is TVmaze-backed (fetched by TvmazeHome, not TMDB). */
    static boolean isTvmazeRow(String kind) {
        return KIND_TVMAZE_TONIGHT.equals(kind);
    }

    /** True when this row kind comes from an external ranking provider (not TMDB/Douban). */
    static boolean isExternalRow(String kind) {
        return isTraktRow(kind) || isSimklRow(kind) || isTvmazeRow(kind);
    }

    /**
     * Year line under a card title. A TV show whose year is the current
     * year is treated as still airing ("2026 - 现在"); TMDB list data
     * carries no status flag, so the year is the proxy. "" when there is
     * no year (the caller hides the line).
     */
    static String yearLine(Cms.Video v, int currentYear) {
        if (v == null) return "";
        String y = Cms.yearLabel(v.year);
        if (y.isEmpty()) return "";
        if ("tv".equals(v.mediaType)) {
            try {
                if (Integer.parseInt(y) == currentYear) return y + " - 现在";
            } catch (NumberFormatException ignored) {
                // Not a plain year — fall through to the raw year.
            }
        }
        return y;
    }
}
