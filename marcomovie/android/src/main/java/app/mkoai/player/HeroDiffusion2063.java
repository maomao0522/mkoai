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

    /**
     * mkoai 2.0.67: a saturation-weighted tone whose saturation is below
     * this is "muddy" — no hue dominates the sample — and {@link
     * #averageTone} falls back to the lightest half instead. 64 is a
     * quarter of the 0-255 saturation range: the real muddy cases sit
     * at 31-38 (the 左撇子艾倫 poster/backdrop), the vivid cases at
     * 97+ (a blue element) and the pinned teal cases at 108-115, so
     * the threshold has margin on both sides.
     */
    static final int MUDDY_SAT_MAX = 64;

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
     *
     * <p>mkoai 2.0.67: when the weighted tone itself is muddy
     * (saturation below {@link #MUDDY_SAT_MAX}) the lightest half of
     * the sample is used instead ({@link #lightestHalf}). A mostly-
     * white poster or a landscape backdrop mixes several pale hues,
     * so the weighted average lands grey-brown even though the image
     * reads light — the hero shows the backdrop, so that grey-brown
     * was the whole page below it. The lightest half is the image's
     * own light side, and {@link #pageTone} still snaps it into the
     * light band with its contrast guarantee. A muddy DARK image
     * stays dark (its lightest half is still dark); a vivid image
     * never reaches this branch.
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
        int[] opaque = new int[n];
        for (int i = 0; i < n; i++) {
            int px = pixels[i];
            if ((px >>> 24) == 0) continue;
            opaque[seen] = px;
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
            if (Math.max(ar, Math.max(ag, ab)) - Math.min(ar, Math.min(ag, ab))
                    < MUDDY_SAT_MAX) {
                // mkoai 2.0.67: the weighted tone is muddy — see the
                // averageTone doc. The lightest half is the image's
                // own light side, not a guessed constant.
                int[] light = lightestHalf(opaque, seen);
                ar = light[0];
                ag = light[1];
                ab = light[2];
            }
        }
        return 0xff000000 | (ar << 16) | (ag << 8) | ab;
    }

    /**
     * mkoai 2.0.67: the average colour of the lightest half of the
     * opaque pixels (brightest by channel sum, ties broken by sample
     * order so the result is deterministic). {@code pixels} holds
     * exactly {@code seen} opaque entries; the top half is indices
     * {@code seen / 2 .. seen - 1} of the brightness-sorted order
     * (for an even sample exactly half, for an odd sample the larger
     * half). A uniform sample returns itself.
     */
    static int[] lightestHalf(int[] pixels, int seen) {
        Integer[] order = new Integer[seen];
        for (int i = 0; i < seen; i++) order[i] = i;
        java.util.Arrays.sort(
                order,
                (x, y) -> {
                    int bx = brightness(pixels[x]);
                    int by = brightness(pixels[y]);
                    if (bx != by) return Integer.compare(bx, by);
                    return Integer.compare(x, y);
                });
        long r = 0;
        long g = 0;
        long b = 0;
        int m = 0;
        for (int j = seen / 2; j < seen; j++) {
            int px = pixels[order[j]];
            r += (px >> 16) & 0xff;
            g += (px >> 8) & 0xff;
            b += px & 0xff;
            m++;
        }
        return new int[] {(int) (r / m), (int) (g / m), (int) (b / m)};
    }

    /** Brightness for the lightest-half sort: plain channel sum (0-765). */
    static int brightness(int argb) {
        return ((argb >> 16) & 0xff) + ((argb >> 8) & 0xff) + (argb & 0xff);
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

    /**
     * mkoai 2.0.66: light-preserving page tone (Emby style). The same
     * saturation-weighted mute as {@link #backgroundTone}, but gentler
     * (MUTE_LIGHT_FACTOR 0.85 toward grey, BRIGHTEN_LIGHT_FACTOR 0.92
     * brightness) and a higher clamp (MAX_LIGHT_CHANNEL 235), so a
     * light poster stays light instead of being forced dark — the
     * 2.0.63–2.0.65 pipeline could only ever yield a dark page, which
     * is the biggest difference from Emby. The luminance snap
     * ({@link #snapTone}) then pushes the tone out of the awkward
     * middle around the 0.30 flip threshold: after it, a page tone
     * is always dark (relative luminance &le; ~0.13, white text) or
     * light (&ge; ~0.47, dark text), never near the threshold. The
     * numbers are empirical, not the 0.12 / 0.55 snap targets — the
     * lighten mix undershoots 0.55 for saturated greens (luminance
     * is nonlinear in channels; worst case over a colour-cube sweep
     * is a pure green landing at ~0.47, worst dark ~0.124), but the
     * gap (0.13..0.47) still holds the 0.30 flip with huge margin,
     * and the contrast guarantees in HomeTone2066 hold at both
     * extremes (primary &ge; 6:1, muted &ge; 4.8:1). backgroundTone
     * is unchanged: the far bloom stops of dark heroes and every
     * 2063–2065 regression pin still use it.
     */
    static final float MUTE_LIGHT_FACTOR = 0.85f;
    static final float BRIGHTEN_LIGHT_FACTOR = 0.92f;
    static final int MAX_LIGHT_CHANNEL = 235;
    /** A snapped dark background is never brighter than this (target; empirical worst ~0.124). */
    static final double DARK_SNAP_LUMINANCE = 0.12;
    /** The lighten target for a snapped light background (the mix undershoots it for saturated
     * greens — empirical worst ~0.47 — see the pageTone doc; the flip threshold is what
     * matters, and it sits far from both). */
    static final double LIGHT_SNAP_LUMINANCE = 0.55;
    /** Below this luminance a mid tone snaps dark, above it light. */
    static final double SNAP_DARK_BELOW = 0.30;

    static int pageTone(int argb) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        int grey = (r + g + b) / 3;
        int mr = Math.round(grey + (r - grey) * MUTE_LIGHT_FACTOR);
        int mg = Math.round(grey + (g - grey) * MUTE_LIGHT_FACTOR);
        int mb = Math.round(grey + (b - grey) * MUTE_LIGHT_FACTOR);
        int br = Math.round(mr * BRIGHTEN_LIGHT_FACTOR);
        int bg = Math.round(mg * BRIGHTEN_LIGHT_FACTOR);
        int bb = Math.round(mb * BRIGHTEN_LIGHT_FACTOR);
        int max = Math.max(br, Math.max(bg, bb));
        if (max > MAX_LIGHT_CHANNEL) {
            float scale = (float) MAX_LIGHT_CHANNEL / max;
            br = Math.round(br * scale);
            bg = Math.round(bg * scale);
            bb = Math.round(bb * scale);
        }
        return snapTone(0xff000000 | (br << 16) | (bg << 8) | bb);
    }

    /**
     * Pushes a tone out of the awkward middle: a tone whose relative
     * luminance is in (0.12, 0.55) is darkened toward ~0.12 when below
     * 0.30, or lightened toward ~0.55 when at/above 0.30. Tones already
     * outside the band pass through untouched. The darken scale is
     * (0.12/L)^(1/2.2) — luminance scales with roughly the 2.2 power
     * of the channel scale — and the lighten mixes toward white by
     * t = (0.55-L)/(1-L), which targets ~0.55. Both are approximate
     * (luminance is nonlinear in channels): the empirical band after
     * pageTone is dark &le; ~0.13 / light &ge; ~0.47 — still a wide
     * empty middle around the 0.30 flip, which is what matters.
     */
    static int snapTone(int argb) {
        double l = HomeTone2066.relativeLuminance(argb);
        if (l <= DARK_SNAP_LUMINANCE || l >= LIGHT_SNAP_LUMINANCE) return argb;
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        if (l < SNAP_DARK_BELOW) {
            double scale = Math.pow(DARK_SNAP_LUMINANCE / l, 1.0 / 2.2);
            r = (int) Math.round(r * scale);
            g = (int) Math.round(g * scale);
            b = (int) Math.round(b * scale);
        } else {
            double t = (LIGHT_SNAP_LUMINANCE - l) / (1.0 - l);
            r = (int) Math.round(r + (255 - r) * t);
            g = (int) Math.round(g + (255 - g) * t);
            b = (int) Math.round(b + (255 - b) * t);
        }
        return 0xff000000 | (clampChannel(r) << 16) | (clampChannel(g) << 8) | clampChannel(b);
    }

    private static int clampChannel(int c) {
        return Math.max(0, Math.min(255, c));
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
     * family. Kept for the 2063–2065 regression pins; the live home
     * uses {@link #bloomColorsAnchored}.
     */
    static int[] bloomColors(int bg) {
        int far = backgroundTone(bg);
        int mid = lerpColor(bg, far, 0.5f);
        return new int[] {bg, bg, bg, mid, far, far};
    }

    /**
     * mkoai 2.0.65: the hero's height in dp for a page {@code pageWidthDp}
     * wide — the same formula the carousel uses (tallest 640dp, shortest
     * 420dp, 1.5x the page width in between). mkoai 2.0.67: 1.25x moved
     * the hero's bottom edge up to ~0.56 of the viewport on a POCO F5
     * (491dp) and every row below it followed, ~100dp higher than the
     * 2.0.65 rest state (~0.67, 587dp). 1.5x puts the bottom back at
     * ~0.68 (589.5dp on a 393dp page) — the rows land where they were —
     * and a 2:3 poster now fits the 393x589.5 box exactly (393/589.5 =
     * 0.667), so the poster shows whole with no crop and no pillars.
     * The hero still starts at the screen top (2.0.66); only the
     * height changes, so the bloom hold ({@link #heroBottomFraction})
     * follows it with no extra work.
     */
    static float heroHeightDp(float pageWidthDp) {
        return Math.min(640f, Math.max(420f, pageWidthDp * 1.5f));
    }

    /**
     * mkoai 2.0.65: where the hero's bottom edge lands in the viewport
     * at rest, as a fraction of the viewport height (0 = top, 1 =
     * bottom). The hero starts at the screen top in 2.0.66 (the top
     * bar is a floating overlay now, not a band above the hero), so
     * the only inputs are the hero's top offset ({@code heroTopDp},
     * 0 at rest) and its height ({@code heroDp}). The fraction is
     * clamped to {@link #HOLD_FRACTION_MIN}..{@link #HOLD_FRACTION_MAX}:
     * below 0.40 the old bloom shape is kept, above 0.90 there would be
     * no room left for the fade at all.
     *
     * <p>Why this exists: the 2.0.64 bloom is viewport-fixed and holds
     * the tone only to 40% of the viewport, but the hero's bottom edge
     * at rest lands at ~0.6–0.8 of the viewport (a 450–640dp hero) —
     * right where the bloom has already faded toward the far colour —
     * while the hero's bottom scrim ends at the full tone. That step is
     * the seam the user sees at rest. Scrolling down a bit slides the
     * hero's bottom edge up into the bloom's pure-tone zone, which is
     * why the scrolled state already looked right. Anchoring the hold
     * to the hero's bottom edge makes the rest state match the scrolled
     * state: scrolling only ever moves the hero's bottom edge UP,
     * deeper into the pure-tone zone, so the seam can never come back
     * in any scroll position.
     */
    static float heroBottomFraction(float heroTopDp, float heroDp, float viewportDp) {
        if (viewportDp <= 0) return HOLD_FRACTION_MIN;
        float f = (heroTopDp + heroDp) / viewportDp;
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
     * greyer — never grey and never black. mkoai 2.0.66: on a light
     * page the far colour is {@link #pageTone} of the tone (a slightly
     * deeper light tone, still light — the snap guarantees it), so the
     * bloom never fades a light page down into darkness; on a dark
     * page the far colour is still {@link #backgroundTone}, exactly
     * today's look.
     */
    static int[] bloomColorsAnchored(int bg, float holdFraction) {
        float hold =
                Math.max(HOLD_FRACTION_MIN, Math.min(HOLD_FRACTION_MAX, holdFraction));
        int far = HomeTone2066.isLight(bg) ? pageTone(bg) : backgroundTone(bg);
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
