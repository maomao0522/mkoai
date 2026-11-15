// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

/**
 * mkoai 2.0.63: phone-home hero swipe decisions (pure Java — no Android
 * classes — so the gesture rules are JVM-testable; the feel itself can
 * only be verified on the phone).
 *
 * <p>What was wrong (user video report, 2026-11-15): the hero was hard to
 * swipe — it did not follow the finger, a light swipe often snapped back
 * to the same card, and a swipe sometimes opened the detail page instead.
 * Three separate causes, three fixes:
 *
 * <ol>
 *   <li>Parent theft: the hero sits inside the home's vertical ScrollView,
 *       which steals a slightly diagonal swipe, so the horizontal scroll
 *       stops tracking the finger. The pager now calls
 *       requestDisallowInterceptTouchEvent(true) as soon as the movement
 *       is clearly horizontal (and never on DOWN, so a vertical drag that
 *       starts on the hero still scrolls the page).</li>
 *   <li>No fling: the old snap only looked at distance (nearest page =
 *       a 50% threshold), so a quick light swipe snapped back. Now a
 *       flick faster than {@link #FLING_VELOCITY_DP_PER_SEC} changes the
 *       page in the flick direction whatever the distance, and the plain
 *       distance threshold is a quarter page instead of half.</li>
 *   <li>Tap vs swipe: a drag now sets a shared flag the hero card's click
 *       listener checks, so a swipe can never open the detail page and a
 *       tap (movement under the tap slop) still opens it.</li>
 * </ol>
 *
 * <p>After a manual swipe the auto-advance stays paused for
 * {@link #MANUAL_PAUSE_MS} (two 12s cycles) instead of resuming after one
 * cycle — it must not snatch the card back right after the user swiped.
 * A pure tap does not pause anything (the normal 12s cycle resumes).
 *
 * <p>Slow rotation (2.0.63): the auto-advance fires every
 * {@link #AUTO_ADVANCE_MS} (12s, not 8s) and glides to the next card over
 * {@link #AUTO_TRANSITION_MS} (900ms) instead of the default ~250ms snap,
 * so the change is a slow glide, never a sudden switch. The manual settle
 * on release stays fast — a slow snap after a swipe would feel laggy.
 *
 * <p>Short drags (2.0.63): the distance rule is a tenth of a page, not a
 * quarter — a light swipe / short drag changes the card. (The old
 * half-page rule is what made him drag most of a card before anything
 * happened.) A flick still wins over distance whatever the length.
 */
final class HeroSwipe2063 {
    private HeroSwipe2063() {}

    /** Movement below this (dp) still counts as a tap, not a drag. */
    static final float TAP_SLOP_DP = 12f;

    /** A flick faster than this (dp/s) changes the page even on a short drag. */
    static final float FLING_VELOCITY_DP_PER_SEC = 400f;

    /** Fraction of a page the drag must cover (without a fling) to change page. */
    static final float PAGE_CHANGE_FRACTION = 0.10f;

    /** The regular auto-advance interval (slow rotation: 12s, not 8s). */
    static final long AUTO_ADVANCE_MS = 12000L;

    /** Auto-advance stays paused this long after a manual swipe (two 12s cycles). */
    static final long MANUAL_PAUSE_MS = 24000L;

    /** How long the auto-advance glide to the next card takes (slow, smooth). */
    static final long AUTO_TRANSITION_MS = 900L;

    /** True when the finger barely moved — a tap, not a drag. */
    static boolean isTap(float dxDp, float dyDp) {
        return Math.abs(dxDp) < TAP_SLOP_DP && Math.abs(dyDp) < TAP_SLOP_DP;
    }

    /** Fling threshold in px/s for this screen density. */
    static float flingThresholdPxPerSec(float density) {
        return FLING_VELOCITY_DP_PER_SEC * density;
    }

    /** Page index clamped into range; count &lt;= 0 yields 0. */
    static int clampPage(int page, int count) {
        if (count <= 0) return 0;
        return Math.max(0, Math.min(count - 1, page));
    }

    /**
     * Which page to settle on after the finger lifts. A fast flick wins
     * over distance (left flick, velocityX &lt; 0, goes to the next page;
     * right flick goes to the previous page); otherwise the drag must
     * cover {@link #PAGE_CHANGE_FRACTION} of a page toward the neighbour.
     * {@code velocityX} is px/s (from VelocityTracker); the threshold is
     * {@link #flingThresholdPxPerSec(float)}.
     */
    static int targetPage(int current, int count, int scrollX, int pageWidth,
            float velocityX, float flingThresholdPxPerSec) {
        if (count <= 1 || pageWidth <= 0) return clampPage(current, count);
        if (velocityX >= flingThresholdPxPerSec) return clampPage(current - 1, count);
        if (velocityX <= -flingThresholdPxPerSec) return clampPage(current + 1, count);
        int offset = scrollX - current * pageWidth;
        int threshold = Math.round(pageWidth * PAGE_CHANGE_FRACTION);
        if (offset > threshold) return clampPage(current + 1, count);
        if (offset < -threshold) return clampPage(current - 1, count);
        return clampPage(current, count);
    }

    /** True while the auto-advance must stay paused after a manual swipe. */
    static boolean shouldPauseAutoAdvance(long nowMs, long lastManualMs, long pauseMs) {
        return nowMs - lastManualMs < pauseMs;
    }

    /** Delay before the auto-advance resumes: long after a manual swipe, normal otherwise. */
    static long resumeDelayMs(boolean manualSwipe) {
        return manualSwipe ? MANUAL_PAUSE_MS : AUTO_ADVANCE_MS;
    }
}
