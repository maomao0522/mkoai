package app.mkoai.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * mkoai launch splash screen.
 *
 * <p>The word "mkoai" in large, sharp, 3D letters. Each letter starts lying flat
 * (rotationX = 90, edge-on, hinged at its bottom edge as if lying on a surface)
 * and stands up (rotationX = 0) one by one in a continuous left-to-right wave:
 * each letter begins its rise while the previous one is still mid-rise
 * (stagger 100ms &lt; rise 500ms), so the motion is one smooth flow, not five
 * separate pops. Letters are a pink-to-gold gradient on near-black, sharp
 * condensed bold type. After all letters stand, a thin gold bar draws itself
 * under the word, the splash holds briefly, then fades out and removes itself.
 *
 * <p>Phone/tablet only, fresh launch only. The overlay sits on the window's
 * decor view (above all content); the main UI loads underneath as usual and
 * is never blocked. The decor view is used (not MainActivity.root) because
 * MainActivity calls setContentView(root) a second time while building the
 * main UI, which detaches root and would cancel the animation and remove a
 * splash living on root. 2.0.33: the fade waits for the home preload
 * (capped at 5s extra) so the home is already rendered when the splash
 * lifts — no loading spinner after the splash.
 */
final class SplashScreen {
    private static final int PINK = 0xFFF2A0B5;
    private static final int GOLD = 0xFFD4A94E;
    private static final int BG = 0xFF141414;
    private static final String WORD = "mkoai";
    private static final float LETTER_SP = 64f;
    // 2.0.40: the fused play triangle is measured against lowercase "a"
    // at runtime (same condensed-bold face) so its ink height matches "a"
    // and its ink bottom sits on the same baseline as the other letters.
    // The ▶ glyph is vertically centred in its em box, so at the old fixed
    // 40sp it floated above the baseline and looked too small; the measured
    // size + rest offset below replace the fixed values.
    private static final float PLAY_MIN_SP = 36f;

    /**
     * Measures the play triangle against lowercase "a" with the splash
     * typeface. Returns {playSizeSp, restTranslationYPx}: the text size
     * that makes the triangle's ink height equal to "a"'s ink height, and
     * the downward shift that puts the triangle's ink bottom on the same
     * baseline as "a" (getTextBounds bottom is the distance below the
     * baseline, so a triangle whose ink ends above the baseline needs a
     * positive push down). Falls back to the old 40sp / no shift when the
     * font has no measurable bounds for either glyph.
     */
    private static float[] playGeometry(MainActivity a) {
        float[] fallback = new float[] {40f, 0f};
        try {
            android.graphics.Paint p = new android.graphics.Paint();
            p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            float letterPx =
                    android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_SP,
                            LETTER_SP,
                            a.getResources().getDisplayMetrics());
            p.setTextSize(letterPx);
            android.graphics.Rect aBounds = new android.graphics.Rect();
            p.getTextBounds("a", 0, 1, aBounds);
            android.graphics.Rect triBounds = new android.graphics.Rect();
            p.getTextBounds("\u25B6", 0, 1, triBounds);
            if (aBounds.height() <= 0 || triBounds.height() <= 0) return fallback;
            float playSp = LETTER_SP * ((float) aBounds.height() / triBounds.height());
            playSp = Math.max(PLAY_MIN_SP, Math.min(LETTER_SP, playSp));
            float triBottomAtPlay = triBounds.bottom * (playSp / LETTER_SP);
            float restY = aBounds.bottom - triBottomAtPlay;
            if (restY < 0) restY = 0;
            return new float[] {playSp, restY};
        } catch (Throwable ignored) {
            return fallback;
        }
    }
    // Elegant, unhurried pace: letters rise slowly in a graceful wave,
    // the word holds so it can be read, then fades out gently.
    private static final long RISE_MS = 700;
    private static final long STAGGER_MS = 150;
    // mkoai 2.0.34: HOLD 1100 -> 800ms (splash felt too long) and the
    // preload wait cap 5000 -> 1500ms. The splash is a brand moment, not a
    // loading screen: it must end promptly even on a cold launch, and the
    // home now renders from the session cache instead of re-checking.
    private static final long HOLD_MS = 800;
    private static final long FADE_MS = 700;
    // Splash: the letters rise in a dramatic wave; the play triangle fused
    // into the word gets a press-down feel as it lands.
    private static final long PRESS_MS = 400;

    static void show(final MainActivity a, boolean freshLaunch) {
        if (a == null || !freshLaunch || a.isTvDevice()) return;
        // Attach to the window's decor view, not MainActivity.root: the main
        // UI is built after this call and MainActivity calls setContentView
        // a second time, which detaches root and would cancel the animation
        // and remove a splash living on root. The decor view is never
        // detached by that call, so the splash survives it.
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (decor == null) return;

        final float density = a.getResources().getDisplayMetrics().density;

        // Full-screen overlay over everything; swallows touches while up.
        final FrameLayout splash = new FrameLayout(a);
        splash.setBackgroundColor(BG);
        splash.setClickable(true);
        decor.addView(
                splash,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        // Centered column: letters row + gold bar. The play triangle is fused
        // into the word itself (it replaces the "o"), not a separate element.
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams columnLp =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        columnLp.gravity = Gravity.CENTER;
        splash.addView(column, columnLp);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        column.addView(
                row,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final float[] playGeom = playGeometry(a);
        final float playSp = playGeom[0];
        final float playRestY = playGeom[1];
        final List<TextView> letters = new ArrayList<>();
        for (int i = 0; i < WORD.length(); i++) {
            TextView tv = new TextView(a);
            // The play triangle is fused into the word: it replaces the "o",
            // so the word reads "mk▶ai". A lone triangle is a generic play
            // symbol (the YouTube rounded-rectangle play button is a
            // trademark, so it is not used).
            boolean isPlay = (i == 2);
            tv.setText(isPlay ? "\u25B6" : String.valueOf(WORD.charAt(i)));
            // 2.0.40: measured size (ink height == "a") instead of fixed
            // 40sp; the triangle also rests playRestY lower so its ink
            // bottom lands on the same baseline as the other letters.
            tv.setTextSize(isPlay ? playSp : LETTER_SP);
            // Sharp condensed bold type — no rounded font.
            tv.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            tv.setLetterSpacing(-0.02f);
            tv.setTextColor(GOLD); // fallback until the gradient shader is set
            tv.setRotationX(90f); // lying flat, edge-on
            tv.setAlpha(0f);
            // Start a little low; the letter glides up as it stands, so the
            // entrance is one continuous motion instead of a sudden pop.
            // 2.0.40: the triangle starts (and rests) playRestY lower so its
            // ink bottom aligns with the other letters' baseline.
            tv.setTranslationY(24 * density + (isPlay ? playRestY : 0f));
            tv.setScaleX(0.92f);
            tv.setScaleY(0.92f);
            row.addView(
                    tv,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            letters.add(tv);
        }

        // Thin gold bar under the word; draws left-to-right after the letters stand.
        final View bar = new View(a);
        bar.setBackgroundColor(GOLD);
        bar.setPivotX(0f);
        bar.setScaleX(0f);
        LinearLayout.LayoutParams barLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (2 * density));
        barLp.topMargin = (int) (18 * density);
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(bar, barLp);

        final AnimatorSet set = new AnimatorSet();
        final List<Animator> anims = new ArrayList<>();

        // Each letter rises while the previous one is still mid-rise, so the
        // wave flows continuously left to right.
        for (int i = 0; i < letters.size(); i++) {
            TextView tv = letters.get(i);
            long delay = i * STAGGER_MS;
            ObjectAnimator rot = ObjectAnimator.ofFloat(tv, "rotationX", 90f, 0f);
            rot.setDuration(RISE_MS);
            rot.setStartDelay(delay);
            rot.setInterpolator(new OvershootInterpolator(1.0f));
            anims.add(rot);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(tv, "alpha", 0f, 1f);
            // Fade over the whole rise (not a fast 220ms pop) so the letter
            // appears gradually as it stands — one continuous motion.
            alpha.setDuration(RISE_MS);
            alpha.setStartDelay(delay);
            alpha.setInterpolator(new DecelerateInterpolator());
            anims.add(alpha);
            ObjectAnimator ty =
                    ObjectAnimator.ofFloat(
                            tv,
                            "translationY",
                            24 * density + (i == 2 ? playRestY : 0f),
                            (i == 2 ? playRestY : 0f));
            ty.setDuration(RISE_MS);
            ty.setStartDelay(delay);
            ty.setInterpolator(new DecelerateInterpolator());
            anims.add(ty);
            ObjectAnimator sx = ObjectAnimator.ofFloat(tv, "scaleX", 0.92f, 1f);
            sx.setDuration(RISE_MS);
            sx.setStartDelay(delay);
            sx.setInterpolator(new DecelerateInterpolator());
            anims.add(sx);
            ObjectAnimator sy = ObjectAnimator.ofFloat(tv, "scaleY", 0.92f, 1f);
            sy.setDuration(RISE_MS);
            sy.setStartDelay(delay);
            sy.setInterpolator(new DecelerateInterpolator());
            anims.add(sy);
            // The fused play triangle gets a press-down feel: as it lands it
            // dips down and squashes a little, like a button being pressed,
            // then springs back.
            if (i == 2) {
                long pressStart = delay + RISE_MS;
                // 2.0.40: the press dips from the triangle's rest position
                // (playRestY, baseline-aligned) and returns to it.
                ObjectAnimator pressY =
                        ObjectAnimator.ofFloat(
                                tv,
                                "translationY",
                                playRestY,
                                playRestY + 8 * density,
                                playRestY);
                pressY.setDuration(PRESS_MS);
                pressY.setStartDelay(pressStart);
                pressY.setInterpolator(new OvershootInterpolator(1.5f));
                anims.add(pressY);
                ObjectAnimator pressSy =
                        ObjectAnimator.ofFloat(tv, "scaleY", 1f, 0.85f, 1f);
                pressSy.setDuration(PRESS_MS);
                pressSy.setStartDelay(pressStart);
                pressSy.setInterpolator(new OvershootInterpolator(1.5f));
                anims.add(pressSy);
                ObjectAnimator pressSx =
                        ObjectAnimator.ofFloat(tv, "scaleX", 1f, 0.94f, 1f);
                pressSx.setDuration(PRESS_MS);
                pressSx.setStartDelay(pressStart);
                pressSx.setInterpolator(new OvershootInterpolator(1.5f));
                anims.add(pressSx);
            }
        }

        long lastLetterEnd = (WORD.length() - 1) * STAGGER_MS + RISE_MS;
        ObjectAnimator barDraw = ObjectAnimator.ofFloat(bar, "scaleX", 0f, 1f);
        barDraw.setDuration(500);
        barDraw.setStartDelay(lastLetterEnd);
        barDraw.setInterpolator(new DecelerateInterpolator());
        anims.add(barDraw);

        // 2.0.33: the fade is NOT part of the letter set. It starts only
        // after the home preload (fetchHome + prefilter, kicked off in
        // MainActivity.onCreate alongside the splash) finishes, so the
        // home is already rendered when the splash lifts — no loading
        // spinner after the splash. The fade still starts at its normal
        // time when the preload is already done, and no later than 5s
        // after that, so the splash always ends (at most ~8s total).
        final ObjectAnimator fade = ObjectAnimator.ofFloat(splash, "alpha", 1f, 0f);
        fade.setDuration(FADE_MS);
        fade.setInterpolator(new DecelerateInterpolator());
        fade.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        try {
                            decor.removeView(splash);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        final android.os.Handler fadeHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());

        set.playTogether(anims);

        // Cancel everything if the splash leaves the window (activity destroyed).
        splash.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {}

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        set.cancel();
                        fade.cancel();
                        fadeHandler.removeCallbacksAndMessages(null);
                    }
                });

        // After layout: hinge each letter at its bottom edge, set the 3D camera
        // distance and the pink-to-gold gradient, then start the wave.
        splash.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (splash.getParent() == null) return; // activity gone
                        // Keep the splash above anything added later.
                        splash.bringToFront();
                        for (TextView tv : letters) {
                            tv.setPivotX(tv.getWidth() / 2f);
                            tv.setPivotY(tv.getHeight());
                            tv.setCameraDistance(8000 * density);
                            LinearGradient g =
                                    new LinearGradient(
                                            0,
                                            0,
                                            0,
                                            tv.getHeight(),
                                            new int[] {PINK, GOLD},
                                            null,
                                            Shader.TileMode.CLAMP);
                            tv.getPaint().setShader(g);
                            tv.invalidate();
                        }
                        set.start();
                        // 2.0.33: gate the fade on the home preload. First
                        // check at the normal fade time; then every 100ms
                        // until the preload is done or the cap is hit.
                        // 2.0.34: cap 5000 -> 1500ms — the splash must not
                        // linger while a cold preload runs; the home renders
                        // from the session cache / disk instead.
                        final long animStart = android.os.SystemClock.uptimeMillis();
                        final long normalFadeStart = lastLetterEnd + HOLD_MS;
                        final long fadeCap = normalFadeStart + 1500;
                        fadeHandler.postDelayed(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (splash.getParent() == null) return;
                                        long elapsed =
                                                android.os.SystemClock.uptimeMillis()
                                                        - animStart;
                                        if (AwwratedHome.homePreloadDone
                                                || elapsed >= fadeCap) {
                                            fade.start();
                                        } else {
                                            fadeHandler.postDelayed(this, 100);
                                        }
                                    }
                                },
                                normalFadeStart);
                    }
                });
    }
}
