// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * mkoai 2.0.66: the year rules — recommendation rows are recent-only,
 * the hero prefers recent titles.
 *
 * <p><b>Hard rule (user 2026-11-15 ~20:48):</b> recommendation pages and
 * rows (home recommendation rows + their view-all pages) show ONLY
 * titles from the last 4 years. As of 2026-11-15 that is 2022 and later
 * — the cutoff is computed from the current year, never hardcoded.
 * The user confirmed (2026-11-15 ~20:53) that this is a deliberate
 * trade-off: old classics (Titanic 1997, The Truman Show 1998,
 * Interstellar 2014) do NOT enter recommendation rows, and the Douban
 * top-rated row counts as a recommendation row — no exception for it.
 * Not filtered: Continue Watching, search, category grids, favourites.
 *
 * <p><b>Soft rule:</b> hero candidates are preferably from the last 2
 * years (2024+). If there are not enough recent candidates the hero is
 * topped up with older ones — the hero must never go empty
 * (fail-open), so an older title is still better than no hero at all.
 *
 * <p><b>Why the 2015 title reached the hero:</b> there was no year
 * filter on the hero path at all. The "2025+" rule the user remembered
 * is {@link AwwratedHome#RELEASE_DELAY_MS} (48h): a title is only
 * "new" if it has been out at least 48h — a recency gate on the TMDB
 * home rows, not a year filter, and the hero path
 * (currentVideos &rarr; VideoKind2061.filterRecommendations &rarr;
 * buildHeroCarousel &rarr; filterNonAnimeHero) never touched it. A
 * 2015 title is well past 48h, so it sailed straight through. The year
 * itself lives in {@code Cms.Video.year} (CMS {@code vod_year} or
 * {@code <year>}, normalised by Cms.yearLabel to the first 19xx/20xx
 * value), and the TMDB / chart rows carry the same year in their own
 * Video objects — so one filter on {@code Cms.Video.year} covers every
 * recommendation source.
 *
 * <p><b>Missing year:</b> the hard rule FAILS OPEN on an unknown year
 * (kept, not dropped) — the same convention as the 48h gate, which
 * keeps titles whose date is unknown rather than guessing they are
 * old. A title with no year at all is not provably old, and dropping
 * it would empty rows fed by sources that carry no year. The hero
 * partition treats an unknown year as NOT-recent instead (filler
 * group): unknown-year titles are never the hero's first choice, but
 * they may fill it when recent candidates are short. The TVmaze
 * tonight row is EXEMPT from the hard rule: its year is the show's
 * premiere year, not the content's recency — a 2015 show airing a new
 * episode tonight IS new content, and dropping it would break the
 * schedule's purpose.
 *
 * <p>Pure Java — no Android classes — so every rule is JVM-testable.
 * The two choke points are in MobileShell: the home sections loop
 * ({@code renderHomeSections}) for the hard rule, and
 * {@code buildHeroCarousel} for the soft rule; the TMDB / chart /
 * Douban row builder ({@code startTmdbRows}) for the rows, which is
 * the same loop the view-all pages read through {@code snapshotDisplay}.
 */
final class HomeYear2066 {
    private HomeYear2066() {}

    /** How a caller reads a title's year (-1 = unknown). Kept local
     *  (not java.util.function) so this file stays pure-Java with no
     *  Android or desugar dependency. */
    interface YearOf<T> {
        int yearOf(T t);
    }

    /** Recommendation rows: titles from the last N years (inclusive). */
    static final int RECENT_WINDOW_YEARS = 4;

    /** Hero preference: titles from the last N years first (inclusive). */
    static final int HERO_WINDOW_YEARS = 2;

    /** Cutoff year for the recommendation rule, computed from {@code nowYear}. */
    static int recCutoffYear(int nowYear) {
        return nowYear - RECENT_WINDOW_YEARS;
    }

    /** Cutoff year for the hero preference, computed from {@code nowYear}. */
    static int heroCutoffYear(int nowYear) {
        return nowYear - HERO_WINDOW_YEARS;
    }

    /** The current year, local time (test seam: callers pass the year in). */
    static int currentYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    /**
     * Parses a year from a CMS year string the way Cms.yearLabel does:
     * the first 19xx/20xx token (e.g. "2025" from "2025", "2025 (共2季)",
     * "2025-11-15"). Returns -1 when there is no year — callers treat
     * -1 as "unknown", never as 0.
     */
    static int parseYear(String year) {
        if (year == null) return -1;
        int n = year.length();
        for (int i = 0; i + 3 < n; i++) {
            char c0 = year.charAt(i);
            if ((c0 == '1' || c0 == '2')
                    && (year.charAt(i + 1) == '9' || year.charAt(i + 1) == '0')
                    && Character.isDigit(year.charAt(i + 2))
                    && Character.isDigit(year.charAt(i + 3))) {
                return (c0 - '0') * 1000
                        + (year.charAt(i + 1) - '0') * 100
                        + (year.charAt(i + 2) - '0') * 10
                        + (year.charAt(i + 3) - '0');
            }
        }
        return -1;
    }

    /**
     * The hard rule: true when a title with this year may appear in a
     * recommendation row. Unknown year (-1) is KEPT (fail-open — see the
     * class doc); any known year below the cutoff is dropped.
     */
    static boolean keepInRecommendations(int year, int nowYear) {
        if (year < 0) return true;
        return year >= recCutoffYear(nowYear);
    }

    /**
     * The hero preference: true when a title with this year counts as
     * "recent" and goes in the hero's first-pick group. Unknown year
     * is NOT recent (filler group, never first pick).
     */
    static boolean isRecentForHero(int year, int nowYear) {
        if (year < 0) return false;
        return year >= heroCutoffYear(nowYear);
    }

    /**
     * Filters a list to the hard rule (unknown year kept). The input
     * order is preserved; the input list is never mutated.
     */
    static <T> List<T> filterRecommendations(List<T> in, YearOf<T> yearOf, int nowYear) {
        ArrayList<T> out = new ArrayList<T>();
        if (in == null) return out;
        for (T t : in) {
            if (keepInRecommendations(yearOf.yearOf(t), nowYear)) out.add(t);
        }
        return out;
    }

    /**
     * Reorders a list for the hero: recent titles first (input order
     * within each group), older titles after — the whole list is kept,
     * nothing is dropped, so the hero never goes empty for lack of
     * recent candidates. Unknown-year titles go in the older (filler)
     * group.
     */
    static <T> List<T> preferRecentForHero(List<T> in, YearOf<T> yearOf, int nowYear) {
        ArrayList<T> recent = new ArrayList<T>();
        ArrayList<T> older = new ArrayList<T>();
        if (in == null) return recent;
        for (T t : in) {
            if (isRecentForHero(yearOf.yearOf(t), nowYear)) recent.add(t);
            else older.add(t);
        }
        recent.addAll(older);
        return recent;
    }
}
