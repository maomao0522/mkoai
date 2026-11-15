// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

/**
 * mkoai 2.0.63: phone-home hero "diffusion" (擴散） — the Emby reference
 * look: the hero backdrop's colours bleed into the page below it with a
 * soft fade and no hard edge.
 *
 * <p>Pure Java — no Android classes — so the colour math is JVM-testable.
 * The Android wiring lives in MobileShell (applyHeroDiffusion): it samples
 * the already-decoded hero bitmap off the UI thread, caches one tone per
 * hero item, and animates the home ScrollView background to the new tone
 * when the hero rotates.
 *
 * <p>Design notes (and why it is not the 2.0.39/2.0.40 diffusion):
 * 2.0.41 removed poster diffusion because a black-and-white poster turned
 * the whole page grey and hazy and the stale wash lingered behind other
 * tabs. This version is scoped to the phone home only, and every tone is
 * muted (desaturated) AND darkened before it becomes a background, with
 * the brightest channel clamped — so a pale or saturated poster can only
 * ever produce a dark muted tone, never a grey haze. The fallback while
 * the bitmap is not loaded yet is the existing slate
 * ({@link HomeRows2061#HOME_SLATE_BG}).
 *
 * <p>mkoai 2.0.64: the 2.0.63 wash was too faint — the user could see the
 * background change on rotation, but a strongly teal poster still read as
 * flat grey. Two causes, both fixed here: (1) the flat per-pixel average
 * dilutes saturated art with the poster's pale background (a mostly-white
 * poster averages to near-grey no matter the mute), so {@link #averageTone}
 * now weights each pixel by its saturation — vivid pixels dominate, pale
 * pixels barely count; (2) the mute/darken were too strong (55% toward
 * grey, 55% brightness). The new values keep 70% of the saturation and
 * 65% of the brightness. The brightest-channel clamp stays 140 on
 * purpose: it is the white-text contrast guard, and with the new darken
 * it only ever binds on near-white, hue-less tones (a pure-white poster
 * still lands at 140 grey, ~3.4:1 for large white text); widening it
 * would only brighten grey, never add hue. One-line tweak: change
 * {@link #MUTE_FACTOR} / {@link #DARKEN_FACTOR} / {@link #MAX_BG_CHANNEL}.
 *
 * <p>2.0.64 also stops painting the page a single flat colour: the
 * background is a vertical bloom ({@link #bloomColors}) — the hero tone
 * at its strongest near the hero, fading outward into a deeper, greyer
 * pass of the same hue further down — so the hero's colour spills past
 * the image's edges and the hero dissolves into it instead of ending at
 * a line. The top bar and the top-edge fade take the same tone (the
 * bloom's top stop), so the whole top of the home is one field.
 *
 * <p>The hero bottom fade is an 8-stop gradient (evenly spaced stops —
 * GradientDrawable has no public stop-position API below API 29, so the
 * fade is shaped with the stops themselves): the top four stops are fully
 * transparent, the next three ramp the page colour in at 25% / 50% /
 * 75% alpha, the last is the opaque page colour — so the fade covers
 * roughly the bottom 55% of the hero and the image dissolves into the
 * page instead of ending at a line. (2.0.63 used 6 stops with a faint
 * black tint; the visible fade was only the bottom ~15% and read as a
 * hard edge.)
 */
final class HeroDiffusion2063 {
    private HeroDiffusion2063() {}

    /** Fallback page background while the hero bitmap is not loaded yet. */
    static final int FALLBACK_BG = 0xff6b7683;

    /** Desaturation: 0 = full grey, 1 = original saturation. 2.0.64: 0.70 (was 0.45). */
    static final float MUTE_FACTOR = 0.70f;

    /** Brightness scale applied after muting. 2.0.64: 0.65 (was 0.55). */
    static final float DARKEN_FACTOR = 0.65f;

    /**
     * Brightest channel a derived background may keep (white text stays
     * readable). 2.0.64: deliberately UNCHANGED at 140 — with the new
     * darken it only binds on near-white, hue-less tones (a pure-white
     * poster still lands at 140 grey); widening it would only brighten
     * grey and drop white-text contrast below ~3:1, never add hue. The
     * hue now comes from the saturation-weighted average + gentler mute.
     */
    static final int MAX_BG_CHANNEL = 140;

    /** Number of stops in the hero bottom-fade gradient. 2.0.64: 8 (was 6). */
    static final int SCRIM_STOPS = 8;

    /**
     * Number of stops in the page-background bloom gradient
     * ({@link #bloomColors}): the tone holds for the top three stops
     * (roughly the hero's height), then fades through the midpoint to
     * the far colour at the bottom.
     */
    static final int BLOOM_STOPS = 6;

    /**
     * Number of stops in the anchored bloom ({@link #bloomColorsAnchored})
     * and the hero top fade ({@link #topFadeColors}). Eight evenly spaced
     * stops shape the fade without any stop-position API.
     */
    static final int ANCHORED_BLOOM_STOPS = 8;

    /**
     * mkoai 2.0.65: the phone-home top bar's height in dp — 48dp
     * circles (search / history / account) + 6dp + 6dp bar padding.
     * Used by {@link #heroBottomFraction} to find where the hero's
     * bottom edge lands in the viewport at rest.
     */
    static final float HOME_TOP_BAR_HEIGHT_DP = 60f;

    /** Lowest / highest hold fraction {@link #heroBottomFraction} returns. */
    static final float HOLD_FRACTION_MIN = 0.40f;
    static final float HOLD_FRACTION_MAX = 0.90f;

    /**
     * The top-edge fade's fixed base colour (the near-black the 2.0.38
     * status-bar veil always used). It stays the fade's colour on every
     * tab except the phone home, and on the home before the first hero
     * tone arrives — so the first paint is exactly today's look.
     */
    static final int TOP_FADE_FIXED_RGB = 0x0d1c16;

    /**
     * mkoai 2.0.64: the phone-home top bar's background for page tone
     * {@code pageTone} — the page tone itself, no extra darkening. The
     * tone is already muted (30% toward grey) + darkened (35%) with its
     * brightest channel clamped to {@link #MAX_BG_CHANNEL} (140), which
     * is the same readability guarantee the page's own white text
     * already relies on — and the bar's foreground is the same
     * white/light glass (logo, ▶ pill, search / ⋯ / history circles,
     * account initial). A second darken would only re-open a seam
     * between the bar and the page it is meant to join, so the bar
     * reads as one continuous field with the hero and the rows below.
     * The input is always a {@link #backgroundTone} output (or the
     * slate fallback), never a raw poster average.
     */
    static int topBarTone(int pageTone) {
        return pageTone;
    }

    /**
     * mkoai 2.0.64: the three gradient stops for a top-edge fade with
     * base colour {@code baseRgb} — 84% opaque at the top, 47% at 58%
     * of the height, transparent at the bottom. The alphas and stops
     * are the 2.0.38 fade's; only the base colour varies, so
     * {@code fadeColors(TOP_FADE_FIXED_RGB)} reproduces the original
     * veil exactly ({@code 0xd80d1c16, 0x780d1c16, 0x000d1c16}).
     * The input is masked to 24-bit RGB: callers pass the opaque ARGB
     * hero tone, and without the mask its 0xff alpha would OR into
     * every stop and turn the veil into a solid band.
     */
    static int[] fadeColors(int baseRgb) {
        int rgb = baseRgb & 0x00ffffff;
        return new int[] {0xd8000000 | rgb, 0x78000000 | rgb, 0x00000000 | rgb};
    }

    /**
     * Average tone of the sampled pixels (opaque ARGB). Fully transparent
     * pixels are ignored; an empty sample (or no opaque pixel at all)
     * returns {@link #FALLBACK_BG}. Only the first {@code count} entries
     * are read (clamped to the array length).
     *
     * <p>mkoai 2.0.64: saturation-weighted, not flat. Each pixel is
     * weighted by its saturation (brightest minus darkest channel), so
     * the poster's vivid art sets the tone and its pale background
     * barely counts — a flat average dilutes a teal poster with its
     * white background down to grey, which is exactly why the 2.0.63
     * diffusion read as flat grey. When every sampled pixel is
     * grey/white/black (total weight zero) the plain average is used,
     * so a black-and-white poster still yields its honest grey.
     */
    static int averageTone(int[] pixels, int count) {
        if (pixels == null || pixels.length == 0 || count <= 0) return FALLBACK_BG;
        int n = Math.min(count, pixels.length);
        long r = 0;
        long g = 0;
        long b = 0;
        long weight = 0;
        long pr = 0;
        long pg = 0;
        long pb = 0;
        int seen = 0;
        for (int i = 0; i < n; i++) {
            int px = pixels[i];
            if ((px >>> 24) == 0) continue;
            int cr = (px >> 16) & 0xff;
            int cg = (px >> 8) & 0xff;
            int cb = px & 0xff;
            int sat = Math.max(cr, Math.max(cg, cb)) - Math.min(cr, Math.min(cg, cb));
            r += (long) cr * sat;
            g += (long) cg * sat;
            b += (long) cb * sat;
            weight += sat;
            pr += cr;
            pg += cg;
            pb += cb;
            seen++;
        }
        if (seen == 0) return FALLBACK_BG;
        int ar;
        int ag;
        int ab;
        if (weight == 0) {
            // No saturated pixel at all — the plain average is the tone.
            ar = (int) (pr / seen);
            ag = (int) (pg / seen);
            ab = (int) (pb / seen);
        } else {
            ar = (int) (r / weight);
            ag = (int) (g / weight);
            ab = (int) (b / weight);
        }
        return 0xff000000 | (ar << 16) | (ag << 8) | ab;
    }

    /**
     * Mute + darken a sampled tone into a page background colour (opaque
     * ARGB): each channel is pulled 30% of the way toward the grey mean,
     * scaled to 65% brightness, then clamped so the brightest channel is
     * at most {@link #MAX_BG_CHANNEL}. A pale or saturated poster can
     * therefore only ever yield a dark muted tone.
     */
    static int backgroundTone(int argb) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        int grey = (r + g + b) / 3;
        int mr = Math.round(grey + (r - grey) * MUTE_FACTOR);
        int mg = Math.round(grey + (g - grey) * MUTE_FACTOR);
        int mb = Math.round(grey + (b - grey) * MUTE_FACTOR);
        int dr = Math.round(mr * DARKEN_FACTOR);
        int dg = Math.round(mg * DARKEN_FACTOR);
        int db = Math.round(mb * DARKEN_FACTOR);
        int max = Math.max(dr, Math.max(dg, db));
        if (max > MAX_BG_CHANNEL) {
            float scale = (float) MAX_BG_CHANNEL / max;
            dr = Math.round(dr * scale);
            dg = Math.round(dg * scale);
            db = Math.round(db * scale);
        }
        return 0xff000000 | (dr << 16) | (dg << 8) | db;
    }

    /** Linear per-channel interpolation; {@code t} is clamped to 0..1. */
    static int lerpColor(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int fr = (from >> 16) & 0xff;
        int fg = (from >> 8) & 0xff;
        int fb = from & 0xff;
        int tr = (to >> 16) & 0xff;
        int tg = (to >> 8) & 0xff;
        int tb = to & 0xff;
        int r = Math.round(fr + (tr - fr) * t);
        int g = Math.round(fg + (tg - fg) * t);
        int b = Math.round(fb + (tb - fb) * t);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * The hero bottom-fade scrim colours for page colour {@code bg}:
     * four transparent stops, then the page colour ramping in at 25% /
     * 50% / 75% alpha, then the opaque page colour — with evenly spaced
     * stops the fade covers roughly the bottom 55% of the hero and ends
     * exactly on the page colour, so the hero dissolves into the page
     * with no hard edge and no black band between hero and rows. The
     * ramp is the page colour itself (not a black tint), so the image
     * melts into the bloom rather than darkening into black first.
     */
    static int[] scrimColors(int bg) {
        int rgb = bg & 0x00ffffff;
        return new int[] {
            0x00000000,
            0x00000000,
            0x00000000,
            0x00000000,
            0x40000000 | rgb,
            0x80000000 | rgb,
            0xc0000000 | rgb,
            bg
        };
    }

    /**
     * mkoai 2.0.64: the page-background bloom for page tone {@code bg} —
     * the diffusion is not one flat colour but a vertical fade that
     * weakens outward from the hero: the tone holds for the top three
     * stops (roughly the hero's height, so the hero's bottom edge sits
     * on its own colour), then fades through the midpoint into the far
     * colour at the bottom. The far colour is the tone diffused once
     * more ({@link #backgroundTone} applied to the tone itself) — a
     * deeper, greyer pass of the same hue, never grey and never black —
     * so the bloom fades outward without ever leaving the hero's colour
     * family. The top bar takes the same tone (the bloom's top stop),
     * so bar, hero and page are one continuous field.
     */
    static int[] bloomColors(int bg) {
        int far = backgroundTone(bg);
        int mid = lerpColor(bg, far, 0.5f);
        return new int[] {bg, bg, bg, mid, far, far};
    }

    /**
     * mkoai 2.0.65: the hero's height in dp for a page {@code pageWidthDp}
     * wide — the same formula the carousel uses (tallest 640dp, shortest
     * 420dp, 1.25x the page width in between).
     */
    static float heroHeightDp(float pageWidthDp) {
        return Math.min(640f, Math.max(420f, pageWidthDp * 1.25f));
    }

    /**
     * mkoai 2.0.65: where the hero's bottom edge lands in the viewport
     * at rest, as a fraction of the viewport height (0 = top, 1 =
     * bottom). The body stacks, top to bottom: the status bar, 8dp of
     * body padding, the top bar ({@code barDp}), 4dp of hero-container
     * padding, then the hero itself ({@code heroDp}). The fraction is
     * clamped to {@link #HOLD_FRACTION_MIN}..{@link #HOLD_FRACTION_MAX}:
     * below 0.40 the old bloom shape is kept, above 0.90 there would be
     * no room left for the fade at all.
     *
     * <p>Why this exists: the 2.0.64 bloom is viewport-fixed and holds
     * the tone only to 40% of the viewport, but the hero's bottom edge
     * at rest lands at ~0.6–0.8 of the viewport (a 450–640dp hero under
     * a 60dp bar) — right where the bloom has already faded toward the
     * far colour — while the hero's bottom scrim ends at the full tone.
     * That step is the seam the user sees at rest. Scrolling down a bit
     * slides the hero's bottom edge up into the bloom's pure-tone zone,
     * which is why the scrolled state already looked right. Anchoring
     * the hold to the hero's bottom edge makes the rest state match the
     * scrolled state: scrolling only ever moves the hero's bottom edge
     * UP, deeper into the pure-tone zone, so the seam can never come
     * back in any scroll position.
     */
    static float heroBottomFraction(
            float statusBarDp, float barDp, float heroDp, float viewportDp) {
        if (viewportDp <= 0) return HOLD_FRACTION_MIN;
        float f = (statusBarDp + 8f + barDp + 4f + heroDp) / viewportDp;
        return Math.max(HOLD_FRACTION_MIN, Math.min(HOLD_FRACTION_MAX, f));
    }

    /**
     * mkoai 2.0.65: the page-background bloom anchored to the hero's
     * bottom edge — the tone holds at full strength from the top down
     * to {@code holdFraction} of the viewport (the hero's bottom edge
     * at rest), then fades outward into the far colour over the rest of
     * the page. Eight evenly spaced stops: every stop at or above the
     * hold is the tone itself, the stops below it ramp the tone into
     * the far colour ({@link #backgroundTone} of the tone). With the
     * hold at 0.40 the shape is the same as {@link #bloomColors}; with
     * the hold at the hero's bottom edge the hero's bottom scrim (which
     * ends at the full tone) meets exactly the tone behind it, in every
     * scroll position. The far colour is the same hue, deeper and
     * greyer — never grey and never black.
     */
    static int[] bloomColorsAnchored(int bg, float holdFraction) {
        float hold =
                Math.max(HOLD_FRACTION_MIN, Math.min(HOLD_FRACTION_MAX, holdFraction));
        int far = backgroundTone(bg);
        int[] out = new int[ANCHORED_BLOOM_STOPS];
        for (int i = 0; i < ANCHORED_BLOOM_STOPS; i++) {
            float pos = i / (float) (ANCHORED_BLOOM_STOPS - 1);
            if (pos <= hold) {
                out[i] = bg;
            } else {
                float t = (pos - hold) / (1f - hold);
                out[i] = lerpColor(bg, far, t);
            }
        }
        return out;
    }

    /**
     * mkoai 2.0.65: the hero TOP fade — the mirror of the bottom scrim.
     * The top bar is a flat band of the page tone sitting directly above
     * the hero artwork, so the bar's bottom edge reads as a line against
     * the photo's top edge. This gradient lays the page tone over the
     * top of the artwork (opaque through the first ~14%, half at ~28%,
     * transparent from ~43% down), so the photo emerges out of the bar's
     * own colour instead of starting at a hard edge — the bar and the
     * hero read as one field in every scroll position, exactly like the
     * bottom scrim does for the rows below. Eight evenly spaced stops.
     */
    static int[] topFadeColors(int bg) {
        int rgb = bg & 0x00ffffff;
        return new int[] {
            bg,
            bg,
            0x80000000 | rgb,
            0x00000000,
            0x00000000,
            0x00000000,
            0x00000000,
            0x00000000
        };
    }
}
