// mkoai 2.0.65: the Android Material switch for the settings pages
// (phone only — TV keeps IosSwitchView inside its card rows, where the
// D-pad focus treatment belongs).
//
// Why a new view instead of reusing IosSwitchView: the iOS switch is
// 50x30dp with a 26dp thumb on a 2dp pad — the thumb nearly fills the
// track, which is the iOS look. The Material switch is 52x32dp with a
// 24dp thumb on a 4dp pad, so a visible ring of track shows around the
// thumb; the OFF track is a solid mid-grey, the ON track is the mkoai
// coral #E34B58 (the app's own accent, kept as the "on" colour — no
// Material rule conflicts with it, it is the primary-colour slot).
// The drawing code is the same proven pattern as IosSwitchView
// (Canvas track + thumb, 160ms DecelerateInterpolator animation, no
// new threads, no layout work on the UI thread beyond a redraw) with
// only the geometry and the OFF-track colour changed, and the public
// API is identical (constructor, isChecked, setChecked) so the
// settings rows call it the same way they called IosSwitchView.
package app.mkoai.player;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class MaterialSwitch2065 {

    /** Material switch size: 52dp wide, 32dp tall, 24dp thumb. */
    static final float TRACK_WIDTH_DP = 52f;
    static final float TRACK_HEIGHT_DP = 32f;
    static final float THUMB_DP = 24f;
    /** Thumb inset from the track edge (4dp each side, both states). */
    static final float THUMB_PAD_DP = 4f;
    /** ON track: the mkoai coral accent (same value as IosSwitchView). */
    static final int TRACK_ON = 0xffe34b58;
    /** OFF track: solid mid-grey, visible on the dark settings pages. */
    static final int TRACK_OFF = 0xff55575b;

    static final class MaterialSwitchView extends View {
        private final MainActivity a;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        private float progress;
        private ValueAnimator animator;

        MaterialSwitchView(MainActivity activity, boolean initialChecked) {
            super(activity);
            this.a = activity;
            this.checked = initialChecked;
            this.progress = initialChecked ? 1f : 0f;
            setClickable(false);
            setFocusable(false);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean next, boolean animate) {
            this.checked = next;
            float target = next ? 1f : 0f;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animate || Math.abs(progress - target) < 0.01f) {
                progress = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(160);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(
                    anim -> {
                        progress = (Float) anim.getAnimatedValue();
                        invalidate();
                    });
            animator.start();
        }

        private static int blendColor(int c1, int c2, float ratio) {
            float r = Math.max(0f, Math.min(1f, ratio));
            int a1 = (c1 >>> 24) & 0xff,
                    r1 = (c1 >> 16) & 0xff,
                    g1 = (c1 >> 8) & 0xff,
                    b1 = c1 & 0xff;
            int a2 = (c2 >>> 24) & 0xff,
                    r2 = (c2 >> 16) & 0xff,
                    g2 = (c2 >> 8) & 0xff,
                    b2 = c2 & 0xff;
            int oa = Math.round(a1 + (a2 - a1) * r);
            int or = Math.round(r1 + (r2 - r1) * r);
            int og = Math.round(g1 + (g2 - g1) * r);
            int ob = Math.round(b1 + (b2 - b1) * r);
            return (oa << 24) | (or << 16) | (og << 8) | ob;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float radius = h / 2f;
            int trackColor = blendColor(TRACK_OFF, TRACK_ON, progress);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(trackColor);
            RectF trackRect = new RectF(0, 0, w, h);
            c.drawRoundRect(trackRect, radius, radius, paint);

            if (progress < 0.95f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, a.dp(1f)));
                paint.setColor(blendColor(0x33ffffff, 0x00ffffff, progress));
                float inset = paint.getStrokeWidth() / 2f;
                c.drawRoundRect(
                        new RectF(inset, inset, w - inset, h - inset), radius, radius, paint);
            }

            float pad = a.dp(THUMB_PAD_DP);
            float thumbRadius = (h - pad * 2f) / 2f;
            float minCx = pad + thumbRadius;
            float maxCx = w - pad - thumbRadius;
            float cx = minCx + (maxCx - minCx) * progress;
            float cy = h / 2f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33000000);
            c.drawCircle(cx, cy + a.dp(0.8f), thumbRadius, paint);

            paint.setColor(Color.WHITE);
            c.drawCircle(cx, cy, thumbRadius, paint);
        }
    }

    private MaterialSwitch2065() {}
}
