package app.mkoai.player;

import androidx.media3.common.Effect;
import androidx.media3.effect.ConvolutionFunction1D;
import androidx.media3.effect.SeparableConvolution;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.Collections;
import java.util.List;

/**
 * mkoai 2.0.79: picture enhancement (sharpening) for the Media3 player.
 *
 * <p>Maoki's super-resolution is MPV-only Anime4K GLSL shaders, which cannot run in
 * Media3/ExoPlayer — so this is the feasible equivalent on the current player: a
 * single-pass separable sharpen convolution applied through {@link
 * ExoPlayer#setVideoEffects(List)}. It uses Media3's own tested {@link
 * SeparableConvolution} shader program (which handles the external-OES input texture,
 * the texture transform and the framebuffer plumbing), so no hand-written GLSL is
 * involved. The real Anime4K arrives when the player unifies on media_kit after the
 * merge.
 *
 * <p>Kernel (3-tap sharpen, exact): {@code [-a, 1 + 2a, -a]}. The continuous function
 * is three unit-area triangles — a positive one centred at 0 and a negative one at
 * each of -1 and +1 — so the kernel sums to 1 under both point sampling and area
 * sampling, and brightness is preserved. Mode 0 (off) is the default: with no
 * effects set, ExoPlayer takes its ordinary path and playback is byte-for-byte the
 * old behaviour. The effect is applied only to the ExoPlayer backend; the system
 * player has no ExoPlayer, and tunneled playback bypasses the video graph, so on
 * those paths the mode is stored but nothing changes on screen.
 */
final class VideoEnhance2079 {

    static final int MODE_OFF = 0;
    static final int MODE_EFFICIENCY = 1;
    static final int MODE_QUALITY = 2;

    /** Menu labels, in mode order. */
    static final String[] LABELS = {"關閉", "效率", "質量"};

    /** Sharpen amount per mode: efficiency is gentle, quality is visibly stronger. */
    static final float STRENGTH_EFFICIENCY = 0.35f;
    static final float STRENGTH_QUALITY = 0.6f;

    private VideoEnhance2079() {}

    static int clampMode(int mode) {
        return mode < MODE_OFF || mode > MODE_QUALITY ? MODE_OFF : mode;
    }

    static float strengthFor(int mode) {
        switch (clampMode(mode)) {
            case MODE_EFFICIENCY:
                return STRENGTH_EFFICIENCY;
            case MODE_QUALITY:
                return STRENGTH_QUALITY;
            default:
                return 0f;
        }
    }

    /** The effect list for a mode: empty for off (ExoPlayer keeps its plain path). */
    static List<Effect> effectsFor(int mode) {
        if (clampMode(mode) == MODE_OFF) return Collections.emptyList();
        return Collections.singletonList(new SharpenConvolution(strengthFor(mode)));
    }

    /**
     * Applies the mode to a live player. Fail-soft by design: if the video graph
     * cannot be (re)built — tunneled playback, a device that rejects effects — the
     * call must never break playback, so the failure is logged and the player keeps
     * playing without enhancement.
     */
    static void applyTo(ExoPlayer player, int mode) {
        if (player == null) return;
        try {
            player.setVideoEffects(effectsFor(mode));
        } catch (RuntimeException error) {
            AppLog.w("VideoEnhance2079", "setVideoEffects failed, playing without enhancement", error);
        }
    }

    /** Sharpen as a Media3 separable convolution: one 1-D kernel, run horizontally then vertically. */
    static final class SharpenConvolution extends SeparableConvolution {
        private final float strength;

        SharpenConvolution(float strength) {
            super(1f, 1f);
            this.strength = strength;
        }

        float strength() {
            return strength;
        }

        @Override
        public ConvolutionFunction1D getConvolution(long presentationTimeUs) {
            return new SharpenFunction(strength);
        }
    }

    /**
     * Three unit-area triangles: {@code (1 + 2a)} at 0, {@code -a} at -1 and +1.
     * Integer samples are exactly {@code [-a, 1 + 2a, -a]} (sum 1), and the integral
     * over the domain is also exactly 1.
     */
    static final class SharpenFunction implements ConvolutionFunction1D {
        private final float strength;

        SharpenFunction(float strength) {
            this.strength = strength;
        }

        @Override
        public float domainStart() {
            return -2f;
        }

        @Override
        public float domainEnd() {
            return 2f;
        }

        @Override
        public float value(float x) {
            float center = (1f + 2f * strength) * triangle(x);
            float left = strength * triangle(x + 1f);
            float right = strength * triangle(x - 1f);
            return center - left - right;
        }

        private static float triangle(float x) {
            float a = Math.abs(x);
            return a >= 1f ? 0f : 1f - a;
        }
    }
}
