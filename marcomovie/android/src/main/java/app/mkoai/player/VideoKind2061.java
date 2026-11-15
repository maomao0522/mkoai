// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import java.util.ArrayList;
import java.util.List;

/**
 * mkoai 2.0.61 (h)(j): movie vs drama/series classification for the
 * recommendations content and the card type badge.
 *
 * <p>Two jobs, one source of truth:
 *
 * <ul>
 *   <li>(h) {@link #isRecommendation(Cms.Video)} — the 推薦 page shows only
 *       movies and dramas/series. Variety, documentaries and kids titles are
 *       dropped even when their kind would otherwise be movie/series (e.g.
 *       儿童电影 is a movie, but it is a kids title, so it never appears in
 *       推薦）. Anime series （動漫/國漫/日漫） count as 劇 and stay. A title
 *       with NO category at all is kept (fail-open — never guess a title
 *       away), while a title whose category is known but is neither movie
 *       nor series (sports, music, live, …) is dropped.
 *   </li>
 *   <li>(j) {@link #badgeLabel(Cms.Video)} — every home/recommendation
 *       card shows 「電影」 for a movie and 「劇」 for a drama/series.
 *       Unknown type gets no label rather than a wrong guess. The label
 *       is rendered bottom-left in the poster's bottom overlay row
 *       (2.0.66; was a top-left dark pill before).
 *   </li>
 *   <li>2.0.63 {@link #isAnime(Cms.Video)} / {@link #filterNonAnime} — the
 *       phone-home hero never headlines anime (animation categories in
 *       either script). The caller falls back to the unfiltered list when
 *       the filter would empty the hero (fail-open: showing something
 *       beats showing nothing).
 *   </li>
 *   <li>2.0.64 {@link #isAnimeHero} / {@link #filterNonAnimeHero} — the
 *       same hero rule, plus two signals for candidates the category
 *       fields cannot classify: the source's own category name resolved
 *       from the numeric {@code type_id} (a source may omit the
 *       per-video {@code type_name} while keeping the id — e.g. an
 *       anime with an empty {@code vod_class}), and — last resort only —
 *       anime words in the title itself. Both are hero-only: a title
 *       keyword can misfire (a live-action title that happens to say
 *       動畫）, but a misfire costs at most one hero candidate and the
 *       fail-open still guards an empty hero. {@link #isRecommendation}
 *       and {@link #badgeLabel} never see either signal.
 *   </li>
 * </ul>
 *
 * <p>Signals, in priority order ({@link #kindOf}): the TMDB {@code mediaType}
 * ("movie"/"tv"), the Douban tag (all Douban lists are movies), the Simkl tag
 * ("simkl-電影" / "simkl-影劇"), the CMS category ({@code Cms.Video.typeName},
 * the per-video {@code type_name} / XML {@code <type>}), and — last resort —
 * the genre, but only for the movie word: a genre tag like 「劇情」 is a genre,
 * not a category, so it must never imply "series". The category is matched in
 * both scripts （電影/电视剧/综艺/动漫 and 电影/電視劇/綜藝/動漫）.
 *
 * <p>All methods are pure (no Android) and JVM-tested by HomeRows2061Checks.
 */
final class VideoKind2061 {
    private VideoKind2061() {}

    static final int MOVIE = 0;
    static final int SERIES = 1;
    static final int OTHER = 2;

    /** 「電影」 for a movie, 「劇」 for a drama/series, "" (no label) otherwise. */
    static String badgeLabel(Cms.Video v) {
        int k = kindOf(v);
        if (k == MOVIE) return "電影";
        if (k == SERIES) return "劇";
        return "";
    }

    /**
     * True when this title belongs on the 推薦 page: a movie or a
     * drama/series, and not a variety / documentary / kids title.
     * Fail-open for a genuinely unknown type: a title with NO category
     * at all is kept (never guess it away — the same rule as the (j)
     * badge). A title that HAS a category but is neither movie nor
     * series is a known other type (sports, music, live, …) and is
     * dropped, as is any variety / documentary / kids title (even a
     * kids movie: 儿童电影 is a movie, but it is a kids title).
     */
    static boolean isRecommendation(Cms.Video v) {
        if (v == null) return false;
        if (isExcludedCategory(v)) return false;
        int k = kindOf(v);
        if (k == MOVIE || k == SERIES) return true;
        return v.typeName == null || v.typeName.isEmpty();
    }

    /** Keep only the recommendations (new list; null in → empty out). */
    static List<Cms.Video> filterRecommendations(List<Cms.Video> in) {
        List<Cms.Video> out = new ArrayList<>();
        if (in == null) return out;
        for (Cms.Video v : in) if (isRecommendation(v)) out.add(v);
        return out;
    }

    /**
     * Animation words, either script — one list for the category check
     * and the hero-only title fallback. 2.0.64 adds the live-found gaps:
     * 漫剧 (a source files anime under "AI漫剧", which contains none of
     * the older words), 漫画 / 漫畫 (manga), 卡通 (cartoon). A category
     * NAMED with one of these words is animation; the title fallback
     * uses the same list, hero-only.
     */
    static final String[] ANIME_WORDS = {
        "动画", "動畫",
        "动漫", "動漫",
        "日漫",
        "国漫", "國漫",
        "番剧", "番劇",
        "新番",
        "里番",
        "漫剧",
        "漫画", "漫畫",
        "卡通"
    };

    /**
     * mkoai 2.0.63: true when this title is anime — its category / genre /
     * tag names animation in either script: 动画/動畫， 动漫/動漫， 日漫，
     * 国漫/國漫. "动画" also covers 动画电影/動畫電影. Only the category
     * fields are checked (never the title), so a live-action title that
     * happens to have 動畫 in its name is not dropped. A title with NO
     * category at all is NOT anime (fail-open — never guess a title away).
     * mkoai 2.0.64: also 番剧/番劇 and 新番 (the CMS words for anime
     * series that carry no 动画/动漫 at all — e.g. a source that files
     * anime under "番剧"), and 里番 (adult anime — it must never
     * headline the hero either); plus 漫剧 (live: a source files anime
     * under "AI漫剧"), 漫画/漫畫 and 卡通.
     */
    static boolean isAnime(Cms.Video v) {
        if (v == null) return false;
        String hay = join(v.typeName, v.genre, v.tag);
        return containsAny(hay, ANIME_WORDS);
    }

    /** Drop the anime titles (new list; null in → empty out). */
    static List<Cms.Video> filterNonAnime(List<Cms.Video> in) {
        List<Cms.Video> out = new ArrayList<>();
        if (in == null) return out;
        for (Cms.Video v : in) if (!isAnime(v)) out.add(v);
        return out;
    }

    /**
     * mkoai 2.0.64: the hero-only anime check. {@link #isAnime} first;
     * then the source's own category name for this title's numeric
     * {@code typeId}, resolved by the caller from the source's cached
     * category list ({@code typeNames}, type id → name; null/empty when
     * the cache is not for this site — the check then degrades to
     * {@link #isAnime}); then — last resort only — the same anime
     * words in the title itself. The title step is hero-only on
     * purpose: a live-action title can contain an anime word, but a
     * misfire costs at most one hero candidate and the caller's
     * fail-open still guards an empty hero. Never used by
     * {@link #isRecommendation} or {@link #badgeLabel}.
     */
    static boolean isAnimeHero(Cms.Video v, java.util.Map<Integer, String> typeNames) {
        if (v == null) return false;
        if (isAnime(v)) return true;
        if (typeNames != null && v.typeId != 0) {
            String resolved = typeNames.get(v.typeId);
            if (resolved != null && containsAny(resolved, ANIME_WORDS)) return true;
        }
        return v.title != null && containsAny(v.title, ANIME_WORDS);
    }

    /** Drop the anime titles by the hero-only check (new list; null in → empty out). */
    static List<Cms.Video> filterNonAnimeHero(
            List<Cms.Video> in, java.util.Map<Integer, String> typeNames) {
        List<Cms.Video> out = new ArrayList<>();
        if (in == null) return out;
        for (Cms.Video v : in) if (!isAnimeHero(v, typeNames)) out.add(v);
        return out;
    }

    /**
     * Movie vs series vs other. Priority: TMDB mediaType, Douban tag, Simkl
     * tag, CMS category (typeName), then the genre — movie word only.
     */
    static int kindOf(Cms.Video v) {
        if (v == null) return OTHER;
        if ("movie".equals(v.mediaType)) return MOVIE;
        if ("tv".equals(v.mediaType)) return SERIES;
        if ("douban".equals(v.tag)) return MOVIE;
        // Simkl / Trakt: the tag carries the kind ("simkl-電影" /
        // "simkl-影劇", "trakt-電影" / "trakt-影劇" — the 2.0.62 TV
        // ranking rows). Same word matching as the CMS category below.
        if (v.tag != null && (v.tag.startsWith("simkl-") || v.tag.startsWith("trakt-"))) {
            String kind = v.tag.substring(v.tag.indexOf('-') + 1);
            if (containsMovieWord(kind)) return MOVIE;
            if (containsSeriesWord(kind)) return SERIES;
            return OTHER;
        }
        // CMS category first (the true category); the genre only when the
        // category is missing — and then only the movie word, because a
        // genre tag like 「劇情」 must never imply "series".
        String category = v.typeName != null && !v.typeName.isEmpty()
                ? v.typeName
                : "";
        if (!category.isEmpty()) {
            if (containsMovieWord(category)) return MOVIE;
            if (containsSeriesWord(category)) return SERIES;
            return OTHER;
        }
        String genre = v.genre == null ? "" : v.genre;
        if (containsMovieWord(genre)) return MOVIE;
        return OTHER;
    }

    /**
     * Variety / documentary / kids — never on the 推薦 page, even when the
     * kind is movie or series (e.g. 儿童电影 is a movie, but it is a kids
     * title). Checked against the category, the genre and the tag together,
     * in both scripts.
     */
    static boolean isExcludedCategory(Cms.Video v) {
        if (v == null) return false;
        String hay = join(v.typeName, v.genre, v.tag);
        return containsAny(
                hay,
                "综艺", "綜藝",
                "纪录片", "紀錄片", "纪录", "紀錄",
                "儿童", "兒童", "少儿", "少兒", "幼儿", "幼兒");
    }

    // (j): the 「電影」/「劇」 label — see {@link #badgeLabel}. mkoai
    // 2.0.66: it is no longer a chip of its own (the top-left dark pill
    // is gone). The label is rendered as the left element of the
    // poster's bottom overlay row (MobileShell.addPosterOverlay's
    // 5-arg overload) — bottom-left, white bold text with a shadow on
    // the same bottom scrim as the bottom-right ★ rating, no pill, no
    // background — so the two sides balance. The phone home card and
    // the TV home card both call that overload with badgeLabel(v).

    // --- word matching (both scripts) -------------------------------------

    private static boolean containsMovieWord(String s) {
        return s != null && (s.contains("电影") || s.contains("電影"));
    }

    private static boolean containsSeriesWord(String s) {
        if (s == null) return false;
        // Any "…劇" category (電視劇/連續劇/劇集/短劇/美劇/影劇 …), plus the
        // animation words that carry no 劇 (動漫/動畫 as a series category).
        // Checked after the movie word, so "动画电影"/"動畫電影" is a movie.
        return s.contains("剧")
                || s.contains("劇")
                || s.contains("动漫")
                || s.contains("動漫")
                || s.contains("动画")
                || s.contains("動畫");
    }

    private static boolean containsAny(String hay, String... words) {
        if (hay == null) return false;
        for (String w : words) if (hay.contains(w)) return true;
        return false;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
