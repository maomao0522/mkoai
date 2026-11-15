// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
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
 *   <li>(j) {@link #badgeLabel(Cms.Video)} / {@link #addTypeBadge} — every
 *       home/recommendation card shows 「電影」 for a movie and 「劇」 for a
 *       drama/series. Unknown type gets no label rather than a wrong guess.
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
 * <p>All methods except {@link #addTypeBadge} are pure (no Android) and
 * JVM-tested by HomeRows2061Checks.
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
     * Movie vs series vs other. Priority: TMDB mediaType, Douban tag, Simkl
     * tag, CMS category (typeName), then the genre — movie word only.
     */
    static int kindOf(Cms.Video v) {
        if (v == null) return OTHER;
        if ("movie".equals(v.mediaType)) return MOVIE;
        if ("tv".equals(v.mediaType)) return SERIES;
        if ("douban".equals(v.tag)) return MOVIE;
        // Simkl: the tag carries the kind (SimklHome, "simkl-電影" / "simkl-影劇").
        if (v.tag != null && v.tag.startsWith("simkl-")) {
            String kind = v.tag.substring("simkl-".length());
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

    // (j): small 「電影」/「劇」 chip at the poster's top-start corner.
    // No-op when the type is not movie/series. The poster art is already a
    // FrameLayout on both the phone and the TV cards, so the chip sits above
    // the image and inside the poster's rounded outline.
    static void addTypeBadge(MainActivity a, FrameLayout art, Cms.Video v) {
        String label = badgeLabel(v);
        if (label.isEmpty() || art == null || a == null) return;
        TextView badge = a.text(label, a.isTvDevice() ? 13 : 11, 0xffffffff);
        badge.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD);
        badge.setSingleLine(true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(a.dp(7), a.dp(3), a.dp(7), a.dp(3));
        badge.setBackground(a.bg(0xcc1f2937, 999));
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(a.dp(6), a.dp(6), 0, 0);
        art.addView(badge, lp);
    }

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
