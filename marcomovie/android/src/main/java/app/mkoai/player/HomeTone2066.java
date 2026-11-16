// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

/**
 * mkoai 2.0.66: the phone-home light/dark text + chrome palette (Emby
 * style). The 2.0.63–2.0.65 diffusion always forced a dark background,
 * so every home text colour was a fixed light value. 2.0.66 lets the
 * page tone stay light (see {@link HeroDiffusion2063#pageTone}), so the
 * text and the floating chrome must follow the tone: light background
 * takes dark text, dark background keeps white text (Emby uses dark
 * text on its light pages).
 *
 * <p>The tone pipeline snaps every background out of the awkward middle
 * (see {@link HeroDiffusion2063#pageTone}): a shown background is always
 * dark (relative luminance &le; ~0.13) or light (&ge; ~0.47), never near
 * the threshold — the numbers are empirical (a colour-cube sweep: worst
 * dark ~0.124, worst light ~0.47 for a saturated green). The flip
 * threshold here ({@link #LIGHT_THRESHOLD} = 0.30) sits in that empty
 * middle, so the palette never flickers near the boundary — a tone is
 * clearly one side or the other.
 *
 * <p>Contrast (WCAG, checked by Home2066Checks at both empirical band
 * extremes): the primary text is &ge; 4.5:1 on either side (worst dark
 * 6.05:1 white on 0xff566756, worst light 8.82:1 near-black on
 * 0xff55cf55); the muted (secondary) text is &ge; 4:1 (worst 4.89:1
 * dark, 5.13:1 light); the dock's selected label on a light pill uses
 * a darker coral ({@link #DOCK_SELECTED_LIGHT}) for &ge; 4.5:1 (5.00:1
 * on the pill's bottom stop). The selected label on a dark pill keeps
 * the existing mkoai coral (status quo — it is a bold accent with the
 * lens + glyph carrying the selected state, not a body-text contrast
 * case).
 *
 * <p>Pure Java — no Android classes — so the palette math is
 * JVM-testable. The Android wiring lives in MobileShell
 * (registerHomeText / retintHomeText / retintDock / retintTopOverlay):
 * home text is re-tinted only when the light/dark state flips (or a
 * fresh build lands), never per animation frame, so a rotation never
 * pays a full re-layout.
 */
final class HomeTone2066 {
    private HomeTone2066() {}

    /** Luminance at/above which a background takes the dark text set. */
    static final double LIGHT_THRESHOLD = 0.30;

    /** Primary text on a dark background (white). */
    static final int TEXT_PRIMARY_DARK_BG = 0xffffffff;

    /** Primary text on a light background (near-black). */
    static final int TEXT_PRIMARY_LIGHT_BG = 0xff111827;

    /** Secondary text on a dark background (light grey — the existing look). */
    static final int TEXT_MUTED_DARK_BG = 0xffe5e7eb;

    /** Secondary text on a light background (dark slate grey). */
    static final int TEXT_MUTED_LIGHT_BG = 0xff374151;

    /** Accent (link / section header) text on a dark background (the existing sky blue). */
    static final int TEXT_ACCENT_DARK_BG = 0xff5ac8fa;

    /** Accent text on a light background (deep blue — the sky blue is near-invisible on light). */
    static final int TEXT_ACCENT_LIGHT_BG = 0xff0d47a1;

    /** Accent (link / section header) text colour for a page background. */
    static int accentText(int bg) {
        return isLight(bg) ? TEXT_ACCENT_LIGHT_BG : TEXT_ACCENT_DARK_BG;
    }

    /** Floating top pill / circles on a light background (near-white). */
    static final int TOP_PILL_LIGHT_BG = 0xf2ffffff;

    /** Floating top pill / circles on a dark background (dark glass). */
    static final int TOP_PILL_DARK_BG = 0xcc222226;

    /** Hairline stroke for the floating top pill on a light background. */
    static final int TOP_PILL_LIGHT_STROKE = 0x1f000000;

    /** Hairline stroke for the floating top pill on a dark background. */
    static final int TOP_PILL_DARK_STROKE = 0x36ffffff;

    /** Bottom dock pill, light background: top / bottom gradient stops. */
    static final int DOCK_LIGHT_TOP_RGB = 0x00ffffff;
    static final int DOCK_LIGHT_BOTTOM_RGB = 0x00f2f2f2;

    /** Bottom dock pill, dark background: top / bottom gradient stops (today's look). */
    static final int DOCK_DARK_TOP_RGB = 0x001b2421;
    static final int DOCK_DARK_BOTTOM_RGB = 0x00111715;

    /** Bottom dock pill stroke on a light background. */
    static final int DOCK_LIGHT_STROKE = 0x1f000000;

    /** Bottom dock pill stroke on a dark background (today's look). */
    static final int DOCK_DARK_STROKE = 0x36ffffff;

    /** Selected dock label / glyph on a dark pill (the mkoai coral, status quo). */
    static final int DOCK_SELECTED_DARK = 0xffe34b58;

    /** Selected dock label / glyph on a light pill (darker coral, &ge; 4.5:1 on white). */
    static final int DOCK_SELECTED_LIGHT = 0xffc22e3c;

    /** Unselected dock label / glyph on a dark pill (light grey, today's look). */
    static final int DOCK_LABEL_DARK_BG = 0xffe5e7eb;

    /** Unselected dock label / glyph on a light pill (near-black). */
    static final int DOCK_LABEL_LIGHT_BG = 0xff1f2937;

    /** Active-tab lens on a dark pill (white glass, today's look). */
    static final int DOCK_LENS_DARK_TOP = 0x38ffffff;
    static final int DOCK_LENS_DARK_BOTTOM = 0x1effffff;
    static final int DOCK_LENS_DARK_STROKE = 0x52ffffff;

    /** Active-tab lens on a light pill (dark glass — the inverse of today's). */
    static final int DOCK_LENS_LIGHT_TOP = 0x1f000000;
    static final int DOCK_LENS_LIGHT_BOTTOM = 0x14000000;
    static final int DOCK_LENS_LIGHT_STROKE = 0x40000000;

    /** Continue Watching progress track on a dark background. */
    static final int PROGRESS_TRACK_DARK_BG = 0x33ffffff;

    /** Continue Watching progress track on a light background. */
    static final int PROGRESS_TRACK_LIGHT_BG = 0x33000000;

    /** Continue Watching progress fill (coral, both sides). */
    static final int PROGRESS_FILL = 0xffe34b58;

    /** WCAG relative luminance of an opaque ARGB colour (0 = black, 1 = white). */
    static double relativeLuminance(int argb) {
        return 0.2126 * linear((argb >> 16) & 0xff)
                + 0.7152 * linear((argb >> 8) & 0xff)
                + 0.0722 * linear(argb & 0xff);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio between two opaque ARGB colours (1 = same, 21 = max). */
    static double contrast(int a, int b) {
        double x = relativeLuminance(a);
        double y = relativeLuminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    /** True when the background takes the dark text set (light background). */
    static boolean isLight(int bg) {
        return relativeLuminance(bg) >= LIGHT_THRESHOLD;
    }

    /** Primary (title / heading) text colour for a page background. */
    static int primaryText(int bg) {
        return isLight(bg) ? TEXT_PRIMARY_LIGHT_BG : TEXT_PRIMARY_DARK_BG;
    }

    /** Secondary (year line / subtitle / view-all) text colour for a page background. */
    static int mutedText(int bg) {
        return isLight(bg) ? TEXT_MUTED_LIGHT_BG : TEXT_MUTED_DARK_BG;
    }

    /**
     * mkoai 2.0.72: the colour roles a tone-following renderPage walk
     * recognises (history page). A TextView's CURRENT colour decides
     * its role, so the walk is closed under repeated flips: a colour
     * the walk wrote is recognised again on the next flip and mapped
     * to the other side's value, never left stranded. Colours that
     * are not a role (dark-glass text, the progress red, the
     * half-transparent poster fallback) are returned unchanged.
     * PRIMARY: the page white (MainActivity.WHITE) and the two
     * primary values themselves. MUTED: the page muted grey plus the
     * other muted greys the settings walk already recognises (same
     * family, so the two walks agree). ACCENT: the renderPage
     * eyebrow blue plus the two accent values themselves.
     */
    static final int[] PAGE_PRIMARY_COLORS = {0xfff5f5f7, 0xffffffff, 0xff111827};
    static final int[] PAGE_MUTED_COLORS = {
        0xffa1a1aa, 0xff9ca3af, 0xffd1d1da, 0xffd1d5db, 0xffe5e7eb, 0xff374151
    };
    static final int[] PAGE_ACCENT_COLORS = {0xffa9c9f3, 0xff5ac8fa, 0xff0d47a1};

    /** Re-tint one flat-page text colour for a page background, by its current-colour role. */
    static int retintPageColor(int color, int bg) {
        for (int c : PAGE_PRIMARY_COLORS) if (c == color) return primaryText(bg);
        for (int c : PAGE_MUTED_COLORS) if (c == color) return mutedText(bg);
        for (int c : PAGE_ACCENT_COLORS) if (c == color) return accentText(bg);
        return color;
    }

    /** Floating top pill background for a page background. */
    static int topPillBg(int bg) {
        return isLight(bg) ? TOP_PILL_LIGHT_BG : TOP_PILL_DARK_BG;
    }

    /** Floating top pill hairline stroke for a page background. */
    static int topPillStroke(int bg) {
        return isLight(bg) ? TOP_PILL_LIGHT_STROKE : TOP_PILL_DARK_STROKE;
    }

    /** Bottom dock pill top gradient stop (RGB; the caller adds the intensity alpha). */
    static int dockTopRgb(int bg) {
        return isLight(bg) ? DOCK_LIGHT_TOP_RGB : DOCK_DARK_TOP_RGB;
    }

    /** Bottom dock pill bottom gradient stop (RGB; the caller adds the intensity alpha). */
    static int dockBottomRgb(int bg) {
        return isLight(bg) ? DOCK_LIGHT_BOTTOM_RGB : DOCK_DARK_BOTTOM_RGB;
    }

    /** Bottom dock pill hairline stroke for a page background. */
    static int dockStroke(int bg) {
        return isLight(bg) ? DOCK_LIGHT_STROKE : DOCK_DARK_STROKE;
    }

    /** Dock label / glyph colour for a page background and selected state. */
    static int dockLabel(int bg, boolean selected) {
        if (selected) return isLight(bg) ? DOCK_SELECTED_LIGHT : DOCK_SELECTED_DARK;
        return isLight(bg) ? DOCK_LABEL_LIGHT_BG : DOCK_LABEL_DARK_BG;
    }

    /** Active-tab lens top stop for a page background. */
    static int dockLensTop(int bg) {
        return isLight(bg) ? DOCK_LENS_LIGHT_TOP : DOCK_LENS_DARK_TOP;
    }

    /** Active-tab lens bottom stop for a page background. */
    static int dockLensBottom(int bg) {
        return isLight(bg) ? DOCK_LENS_LIGHT_BOTTOM : DOCK_LENS_DARK_BOTTOM;
    }

    /** Active-tab lens stroke for a page background. */
    static int dockLensStroke(int bg) {
        return isLight(bg) ? DOCK_LENS_LIGHT_STROKE : DOCK_LENS_DARK_STROKE;
    }

    /** Continue Watching progress track colour for a page background. */
    static int progressTrack(int bg) {
        return isLight(bg) ? PROGRESS_TRACK_LIGHT_BG : PROGRESS_TRACK_DARK_BG;
    }

    /**
     * The bottom-edge veil's three gradient stops for base colour
     * {@code baseRgb} — transparent at the top, 0x90 in the middle,
     * 0xe6 at the bottom (the same shape as the 2.0.38 dark veil, only
     * the base colour varies, so {@code bottomFadeColors(0x060a08)}
     * reproduces the original veil exactly). The input is masked to
     * 24-bit RGB: callers pass the opaque ARGB page tone, and without
     * the mask its 0xff alpha would OR into every stop and turn the
     * veil into a solid band.
     */
    static int[] bottomFadeColors(int baseRgb) {
        int rgb = baseRgb & 0x00ffffff;
        return new int[] {0x00000000 | rgb, 0x90000000 | rgb, 0xe6000000 | rgb};
    }
}
