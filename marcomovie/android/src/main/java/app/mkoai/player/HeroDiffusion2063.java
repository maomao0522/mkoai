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
 * <p>The hero bottom fade is a 6-stop gradient (evenly spaced stops —
 * GradientDrawable has no public stop-position API below API 29, so the
 * fade is shaped with the stops themselves): the top four stops are fully
 * transparent, the fifth is a faint dark tint, the sixth is the opaque
 * page colour — so the fade covers roughly the bottom 40% of the hero.
 */
final class HeroDiffusion2063 {
    private HeroDiffusion2063() {}

    /** Fallback page background while the hero bitmap is not loaded yet. */
    static final int FALLBACK_BG = 0xff6b7683;

    /** Desaturation: 0 = full grey, 1 = original saturation. */
    static final float MUTE_FACTOR = 0.45f;

    /** Brightness scale applied after muting. */
    static final float DARKEN_FACTOR = 0.55f;

    /** Brightest channel a derived background may keep (white text stays readable). */
    static final int MAX_BG_CHANNEL = 140;

    /** Number of stops in the hero bottom-fade gradient. */
    static final int SCRIM_STOPS = 6;

    /** Faint dark tint at the second-to-last scrim stop (keeps hero text legible). */
    static final int SCRIM_TINT = 0x1e000000;

    /**
     * Average tone of the sampled pixels (opaque ARGB). Fully transparent
     * pixels are ignored; an empty sample (or no opaque pixel at all)
     * returns {@link #FALLBACK_BG}. Only the first {@code count} entries
     * are read (clamped to the array length).
     */
    static int averageTone(int[] pixels, int count) {
        if (pixels == null || pixels.length == 0 || count <= 0) return FALLBACK_BG;
        int n = Math.min(count, pixels.length);
        long r = 0;
        long g = 0;
        long b = 0;
        int seen = 0;
        for (int i = 0; i < n; i++) {
            int px = pixels[i];
            if ((px >>> 24) == 0) continue;
            r += (px >> 16) & 0xff;
            g += (px >> 8) & 0xff;
            b += px & 0xff;
            seen++;
        }
        if (seen == 0) return FALLBACK_BG;
        int ar = (int) (r / seen);
        int ag = (int) (g / seen);
        int ab = (int) (b / seen);
        return 0xff000000 | (ar << 16) | (ag << 8) | ab;
    }

    /**
     * Mute + darken a sampled tone into a page background colour (opaque
     * ARGB): each channel is pulled 55% of the way toward the grey mean,
     * scaled to 55% brightness, then clamped so the brightest channel is
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
     * four transparent stops, a faint dark tint, then the opaque page
     * colour — with evenly spaced stops the fade covers roughly the
     * bottom 40% of the hero and ends exactly on the page colour, so
     * there is no hard edge and no black band between hero and rows.
     */
    static int[] scrimColors(int bg) {
        return new int[] {0x00000000, 0x00000000, 0x00000000, 0x00000000, SCRIM_TINT, bg};
    }
}
