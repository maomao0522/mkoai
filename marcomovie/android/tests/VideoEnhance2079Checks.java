package app.mkoai.player;

import androidx.media3.common.Effect;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.SeparableConvolution;
import java.util.List;

/** JVM checks for the 2.0.79 Media3 sharpen enhancement (pure part only). */
public class VideoEnhance2079Checks {
    static int total = 0;
    static int failed = 0;

    static void check(boolean cond, String name) {
        total++;
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static boolean near(float a, float b, float eps) {
        return Math.abs(a - b) <= eps;
    }

    /** Numerical integral of the kernel over its domain (trapezoid, 4001 samples). */
    static float integral(VideoEnhance2079.SharpenFunction f) {
        int n = 4000;
        float lo = f.domainStart(), hi = f.domainEnd();
        float h = (hi - lo) / n, sum = 0f;
        for (int i = 0; i <= n; i++) {
            float w = (i == 0 || i == n) ? 0.5f : 1f;
            sum += w * f.value(lo + i * h);
        }
        return sum * h;
    }

    public static void main(String[] args) {
        // ---- modes & labels ----
        check(VideoEnhance2079.MODE_OFF == 0, "MODE_OFF is 0");
        check(VideoEnhance2079.MODE_EFFICIENCY == 1, "MODE_EFFICIENCY is 1");
        check(VideoEnhance2079.MODE_QUALITY == 2, "MODE_QUALITY is 2");
        check(VideoEnhance2079.LABELS.length == 3, "3 mode labels");
        check("關閉".equals(VideoEnhance2079.LABELS[0]), "label 0 is 關閉");
        check("效率".equals(VideoEnhance2079.LABELS[1]), "label 1 is 效率");
        check("質量".equals(VideoEnhance2079.LABELS[2]), "label 2 is 質量");
        check(VideoEnhance2079.clampMode(-1) == 0, "clampMode -1 -> off");
        check(VideoEnhance2079.clampMode(0) == 0, "clampMode 0 -> off");
        check(VideoEnhance2079.clampMode(1) == 1, "clampMode 1 stays");
        check(VideoEnhance2079.clampMode(2) == 2, "clampMode 2 stays");
        check(VideoEnhance2079.clampMode(3) == 0, "clampMode 3 -> off");
        check(VideoEnhance2079.clampMode(99) == 0, "clampMode 99 -> off");
        check(near(VideoEnhance2079.strengthFor(0), 0f, 1e-6f), "strength off is 0");
        check(near(VideoEnhance2079.strengthFor(1), 0.35f, 1e-6f), "strength efficiency is 0.35");
        check(near(VideoEnhance2079.strengthFor(2), 0.6f, 1e-6f), "strength quality is 0.6");
        check(near(VideoEnhance2079.strengthFor(7), 0f, 1e-6f), "strength invalid is 0");

        // ---- sharpen kernel: efficiency (a = 0.35) ----
        VideoEnhance2079.SharpenFunction eff =
                new VideoEnhance2079.SharpenFunction(VideoEnhance2079.STRENGTH_EFFICIENCY);
        check(near(eff.domainStart(), -2f, 1e-6f), "domain start is -2");
        check(near(eff.domainEnd(), 2f, 1e-6f), "domain end is 2");
        check(near(eff.width(), 4f, 1e-6f), "kernel width is 4");
        check(near(eff.value(0f), 1.7f, 1e-5f), "value(0) is 1 + 2a = 1.7");
        check(near(eff.value(1f), -0.35f, 1e-5f), "value(1) is -a = -0.35");
        check(near(eff.value(-1f), -0.35f, 1e-5f), "value(-1) is -a = -0.35");
        check(near(eff.value(2f), 0f, 1e-5f), "value(2) is 0");
        check(near(eff.value(-2f), 0f, 1e-5f), "value(-2) is 0");
        check(near(eff.value(0.5f), 0.5f + 0.5f * 0.35f, 1e-5f), "value(0.5) is the triangle blend");
        check(near(integral(eff), 1f, 0.01f), "kernel integral is 1 (brightness preserved)");
        float discrete =
                eff.value(0f) + 2f * eff.value(1f) + 2f * eff.value(2f);
        check(near(discrete, 1f, 1e-5f), "integer taps sum to 1: [-a, 1+2a, -a]");

        // ---- sharpen kernel: quality (a = 0.6) ----
        VideoEnhance2079.SharpenFunction qual =
                new VideoEnhance2079.SharpenFunction(VideoEnhance2079.STRENGTH_QUALITY);
        check(near(qual.value(0f), 2.2f, 1e-5f), "quality value(0) is 2.2");
        check(near(qual.value(1f), -0.6f, 1e-5f), "quality value(1) is -0.6");
        check(near(integral(qual), 1f, 0.01f), "quality kernel integral is 1");

        // ---- effects list ----
        List<Effect> off = VideoEnhance2079.effectsFor(VideoEnhance2079.MODE_OFF);
        check(off.isEmpty(), "effectsFor(off) is empty (plain ExoPlayer path)");
        List<Effect> effList = VideoEnhance2079.effectsFor(VideoEnhance2079.MODE_EFFICIENCY);
        check(effList.size() == 1, "effectsFor(efficiency) has one effect");
        check(effList.get(0) instanceof GlEffect, "efficiency effect is a GlEffect");
        check(effList.get(0) instanceof SeparableConvolution,
                "efficiency effect is a SeparableConvolution (Media3's own program)");
        VideoEnhance2079.SharpenConvolution conv =
                (VideoEnhance2079.SharpenConvolution) effList.get(0);
        check(near(conv.strength(), 0.35f, 1e-6f), "efficiency convolution carries 0.35");
        check(conv.getConvolution(0L) instanceof VideoEnhance2079.SharpenFunction,
                "getConvolution returns the sharpen function");
        List<Effect> qualList = VideoEnhance2079.effectsFor(VideoEnhance2079.MODE_QUALITY);
        check(qualList.size() == 1, "effectsFor(quality) has one effect");
        check(near(((VideoEnhance2079.SharpenConvolution) qualList.get(0)).strength(), 0.6f, 1e-6f),
                "quality convolution carries 0.6");
        check(VideoEnhance2079.effectsFor(99).isEmpty(), "effectsFor(invalid) is empty (clamped off)");

        // ---- applyTo null guard ----
        try {
            VideoEnhance2079.applyTo(null, VideoEnhance2079.MODE_QUALITY);
            check(true, "applyTo(null, quality) is a no-op, no throw");
        } catch (Exception e) {
            check(false, "applyTo(null, quality) is a no-op, no throw");
        }

        // ---- Prefs key ----
        check("video_enhance".equals(Prefs.VIDEO_ENHANCE), "Prefs key is video_enhance");

        System.out.println("\n" + (total - failed) + " VideoEnhance2079 checks passed");
        if (failed > 0) System.out.println(failed + " FAILED");
    }
}
