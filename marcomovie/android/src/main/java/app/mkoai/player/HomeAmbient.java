package app.mkoai.player;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.widget.ImageView;
import java.util.Random;

/**
 * mkoai 2.0.41: plain frosted-matte home background, shared by every page.
 *
 * <p>2.0.39/2.0.40 diffused the hero poster (heavy blur + enlarge) across
 * the page. The user says it still looks bad — a black-and-white poster
 * turns the whole page grey and hazy, and the stale diffusion even lingered
 * behind other tabs (e.g. 收藏）. So the poster is no longer used for the
 * background at all, anywhere.
 *
 * <p>The background is now a clean, slightly dark matte field: a very
 * subtle vertical gradient (#1c1c1c at the top fading to #141414 at the
 * bottom) with a fine deterministic grain baked in for a frosted/matte
 * feel. No poster colours, no grey haze, nothing recognisable as any
 * artwork. The same field is painted by the shell canvas
 * (AmbientCanvasView) and the renderPage ambient (ImageLoader.Atmosphere),
 * so every page — 首頁、收藏、設定、搜尋、清單頁、詳情頁 — shares exactly
 * the same base. The detail page's full-bleed poster itself is untouched
 * (that is content, not background diffusion).
 *
 * <p>{@link #follow} / {@link #posterReady} are kept as no-ops so the
 * existing call sites (hero card, HomeScreen) keep compiling; they no
 * longer do any work.
 */
final class HomeAmbient extends View {
    final MainActivity a;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final RectF destination = new RectF();
    /** The hero ImageView currently at the top of the viewport (tracked for API compat only). */
    ImageView wanted;

    HomeAmbient(MainActivity host) {
        super(host);
        a = host;
        setTag("home-ambient");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** No-op since 2.0.41: the background no longer follows any poster. */
    void follow(ImageView image) {
        wanted = image;
    }

    /** No-op since 2.0.41: posters are no longer diffused into the background. */
    void posterReady(ImageView image) {}

    /** The shared frosted-matte field (240x135), generated once and reused by every page. */
    private static Bitmap frostBitmap;

    static Bitmap frostField() {
        if (frostBitmap != null && !frostBitmap.isRecycled()) return frostBitmap;
        Bitmap field = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(field);
        Paint p = new Paint();
        // Very subtle dark vertical gradient: slightly lifted at the top,
        // exactly #141414 at the bottom so it melts into the page.
        p.setShader(
                new LinearGradient(
                        0,
                        0,
                        0,
                        135,
                        new int[] {0xff1c1c1c, 0xff181818, 0xff141414},
                        new float[] {0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 240, 135, p);
        p.setShader(null);
        // Fine grain: deterministic so every launch looks identical.
        // A few thousand 1px dots at very low alpha — matte/frosted feel,
        // never visible as noise. Every pixel stays dark and near-grey.
        Random rnd = new Random(0x5eed41L);
        for (int i = 0; i < 1600; i++) {
            int x = rnd.nextInt(240);
            int y = rnd.nextInt(135);
            boolean light = rnd.nextBoolean();
            int alpha = 3 + rnd.nextInt(5); // 3..7 / 255 — barely visible
            p.setColor(
                    light
                            ? Color.argb(alpha, 255, 255, 255)
                            : Color.argb(alpha, 0, 0, 0));
            c.drawPoint(x, y, p);
        }
        frostBitmap = field;
        return field;
    }

    /** Paint the shared frosted-matte field stretched over the given area. */
    static void paintFrost(Canvas c, int w, int h, Paint p) {
        Bitmap field = frostField();
        if (field == null || field.isRecycled() || w <= 0 || h <= 0) return;
        c.drawBitmap(field, null, new RectF(0, 0, w, h), p);
    }

    @Override
    protected void onDraw(Canvas c) {
        destination.set(0, 0, getWidth(), getHeight());
        Bitmap field = frostField();
        if (field != null && !field.isRecycled()) {
            c.drawBitmap(field, null, destination, paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        wanted = null;
        super.onDetachedFromWindow();
    }
}
