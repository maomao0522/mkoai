// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

/**
 * mkoai 2.0.66: the hero ratings line (Emby-style info hierarchy).
 *
 * <p>Emby's hero shows the big title + year + a 2–3 line synopsis +
 * the ratings side by side (IMDb / Douban). mkoai's hero had the
 * title, the year and the synopsis — but no ratings at all. The row
 * below the synopsis is the fix: "TMDB 8.4 · 豆瓣 9.1" (TMDB first,
 * Douban when the source already carries it), and the whole row is
 * hidden when there is no score — no blank placeholder, no "—".
 *
 * <p>No new network requests: the score must already be in hand.
 * Two sources, both free at hero time —
 * <ol>
 * <li>{@code Cms.Video.remarks} — several row builders already write
 * a score into the remarks ("★ 8.4" from TmdbClient.toVideo,
 * "8.4 分" from AwwratedHome, "豆瓣 9.1 分" from DoubanHome);
 * <li>the TMDB item disk cache — {@link TmdbClient#cachedSearch}
 * (disk only, 7-day TTL, never the network) returns the cached Item
 * with {@code vote}, populated during normal use by the poster /
 * year enrichment steps.
 * </ol>
 * OMDb ratings are NOT available at hero time: the detail page fetches
 * them asynchronously and keeps them page-scoped, so the hero shows
 * TMDB (and Douban from remarks) only. The ratings TextView starts
 * GONE and is revealed later when a lazy backdrop/ratings lookup
 * lands — the hero card's height is minimum-height based, so the
 * reveal never jumps the layout.
 *
 * <p>Pure Java — no Android classes — so the parsing is JVM-testable.
 */
final class HeroRatings2066 {
    private HeroRatings2066() {}

    /** The star glyph the TMDB row builder writes into remarks. */
    private static final String STAR = "\u2605";

    /**
     * Builds the ratings line from a Video's remarks, or "" when the
     * remarks carry no score. Recognised shapes: "★ 8.4" (TMDB),
     * "8.4 分" (AwwratedHome), "豆瓣 9.1 分" (DoubanHome — the Douban
     * part is kept and shown as "豆瓣 9.1"). A remarks string may
     * carry more than one score ("豆瓣 9.1 分 · ★ 8.4"); both are
     * shown, TMDB first. Scores are clamped to 0–10 and rendered with
     * one decimal; anything that does not parse is dropped, never
     * shown raw.
     */
    static String ratingsLineFromRemarks(String remarks) {
        if (remarks == null || remarks.isEmpty()) return "";
        String tmdb = extractScoreAfter(remarks, STAR);
        if (tmdb == null && remarks.indexOf("豆瓣") < 0) {
            // AwwratedHome writes the TMDB vote as a bare "8.4 分" (no
            // star): the number before 分 is the TMDB score. Only when
            // there is no 豆瓣 fragment — "豆瓣 9.1 分" must not be
            // counted twice (once as TMDB, once as Douban).
            tmdb = extractScoreBefore(remarks, "分");
        }
        String douban = extractDoubanScore(remarks);
        StringBuilder out = new StringBuilder();
        if (tmdb != null) out.append("TMDB ").append(tmdb);
        if (douban != null) {
            if (out.length() > 0) out.append("  ·  ");
            out.append("豆瓣 ").append(douban);
        }
        return out.toString();
    }

    /**
     * Builds the ratings line from a cached TMDB item vote plus the
     * remarks line: the TMDB vote wins when present (it is the same
     * number the TMDB row builder writes into remarks, but from the
     * cache it is available even when the remarks carry nothing).
     * Returns "" when neither has a score.
     */
    static String ratingsLine(String remarks, double tmdbVote) {
        if (tmdbVote > 0 && tmdbVote <= 10) {
            String line = ratingsLineFromRemarks(remarks);
            String tmdbPart = "TMDB " + formatScore(tmdbVote);
            if (line.startsWith("TMDB ")) {
                // The remarks already carry the TMDB score — the
                // cached vote is the same number; keep the remarks
                // line (it may also carry a Douban score).
                return line;
            }
            if (line.isEmpty()) return tmdbPart;
            return tmdbPart + "  ·  " + line;
        }
        return ratingsLineFromRemarks(remarks);
    }

    /** The score after a marker ("★ 8.4" &rarr; "8.4"), or null. */
    private static String extractScoreAfter(String text, String marker) {
        int i = text.indexOf(marker);
        if (i < 0) return null;
        return parseScore(text, i + marker.length());
    }

    /** The decimal number immediately before a marker ("8.4 分" &rarr; "8.4"), or null. */
    private static String extractScoreBefore(String text, String marker) {
        int i = text.indexOf(marker);
        if (i <= 0) return null;
        int end = i;
        while (end > 0 && text.charAt(end - 1) == ' ') end--;
        int start = end;
        while (start > 0
                && (Character.isDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) {
            start--;
        }
        if (start == end) return null;
        try {
            return formatScore(Double.parseDouble(text.substring(start, end)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The score in a "豆瓣 9.1 分" fragment, or null. */
    private static String extractDoubanScore(String text) {
        int i = text.indexOf("豆瓣");
        if (i < 0) return null;
        return parseScore(text, i + 2);
    }

    /** Reads the first decimal number at or after {@code from}, or null. */
    private static String parseScore(String text, int from) {
        int n = text.length();
        int i = from;
        while (i < n && !Character.isDigit(text.charAt(i)) && text.charAt(i) != '.') i++;
        if (i >= n) return null;
        int start = i;
        while (i < n && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) i++;
        if (start == i) return null;
        try {
            return formatScore(Double.parseDouble(text.substring(start, i)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One decimal, clamped to 0–10 ("8.4", "9.0"). */
    private static String formatScore(double v) {
        if (v < 0) v = 0;
        if (v > 10) v = 10;
        return String.format(java.util.Locale.US, "%.1f", v);
    }
}
