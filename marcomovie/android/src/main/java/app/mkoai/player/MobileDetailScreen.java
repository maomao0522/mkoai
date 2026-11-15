package app.mkoai.player;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.pilipala.glass.GlassworkSurface;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * iPhone / Apple TV+ / Infuse 1:1 portrait & tablet immersive video detail screen:
 * - True full-bleed poster from physical screen top (top: 0) down to 77% of screen height
 * - Dynamic ambient color extraction from poster artwork with progressive lower frosted-glass blend
 * - Floating Liquid Glass top navigation bar (left circular back button, right independent Source
 *   selector capsule + Download/Favorite dual capsule)
 * - Centered hero typography, metadata pills, TMDB / Douban badges, expandable synopsis, and
 *   full-width white stadium Play button
 * - Independent Season selector button ("第 X 季 ⌄"), horizontal circular episode number strip, and
 *   horizontal 16:9 landscape episode preview cards.
 */
final class MobileDetailScreen {
    private MobileDetailScreen() {}

    // mkoai 2.0.62: last OMDb payload for the detail page now showing (single
    // entry, keyed by normalized title). updateRatingBadges() is re-called by
    // the Douban resolver after the OMDb fetch lands; without this the
    // OMDb-priority Rotten Tomatoes value would revert to the regex fallback.
    // Fail-soft by design: null = no OMDb data yet, regex fallback applies.
    private static String sOmdbTitleKey;
    private static JSONObject sOmdbData;

    private static JSONObject cachedOmdbFor(Cms.Video v) {
        if (v == null || v.title == null || sOmdbData == null || sOmdbTitleKey == null) {
            return null;
        }
        return sOmdbTitleKey.equals(Cms.normTitle(v.title)) ? sOmdbData : null;
    }

    static void bindTouchFeedback(View v) {
        v.setFocusable(false);
        v.setFocusableInTouchMode(false);
        v.setOnTouchListener(
                (view, event) -> {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        view.setAlpha(0.72f);
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        view.setAlpha(1.0f);
                    }
                    return false;
                });
    }

    static GradientDrawable glassBg(
            MainActivity a, int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(a.dp(radiusDp));
        gd.setStroke(Math.max(1, a.dp(0.8f)), strokeColor);
        return gd;
    }

    static int getStatusBarHeight(MainActivity a) {
        return a.statusBarHeight();
    }

    // 2.0.41: no per-title tint anymore — the detail page base is the
    // same dark frosted matte as every other page. The full-bleed poster
    // itself is untouched (that is content, not background diffusion).
    static int defaultAmbientForTitle(String title) {
        return MainActivity.DARK;
    }

    static int extractAmbientColor(Bitmap bmp, int fallback) {
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() < 4 || bmp.getHeight() < 4) {
            return fallback;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        long rSum = 0, gSum = 0, bSum = 0, weightSum = 0;
        int startY = (int) (h * 0.35f);
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, (h - startY) / 24);
        float[] hsv = new float[3];
        for (int y = startY; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int c = bmp.getPixel(x, y);
                int alpha = Color.alpha(c);
                if (alpha < 128) continue;
                Color.colorToHSV(c, hsv);
                // Weight saturated mid-tone pixels higher so dull black/white bars don't dominate
                long weight = 1 + (long) (hsv[1] * 8f);
                if (hsv[2] > 0.12f && hsv[2] < 0.92f) weight += 3;
                rSum += Color.red(c) * weight;
                gSum += Color.green(c) * weight;
                bSum += Color.blue(c) * weight;
                weightSum += weight;
            }
        }
        if (weightSum <= 0) return fallback;
        int r = (int) (rSum / weightSum);
        int g = (int) (gSum / weightSum);
        int b = (int) (bSum / weightSum);
        Color.RGBToHSV(r, g, b, hsv);
        // Clamp saturation & value to produce a rich, dark Apple TV+ cinema canvas
        if (hsv[1] < 0.22f && hsv[1] > 0.04f) {
            hsv[1] = 0.28f;
        } else {
            hsv[1] = Math.min(0.65f, hsv[1] * 1.15f);
        }
        hsv[2] = Math.max(0.18f, Math.min(0.28f, hsv[2] * 0.42f));
        return Color.HSVToColor(hsv);
    }

    static final class EpisodeDisplay {
        final String cleanTitle;
        final String specBadge;

        EpisodeDisplay(String cleanTitle, String specBadge) {
            this.cleanTitle = cleanTitle;
            this.specBadge = specBadge;
        }
    }

    static EpisodeDisplay parseEpisodeDisplay(String rawName, int epNumber) {
        String s = rawName == null ? "" : rawName.trim();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl).trim();
        }
        s = s.replace('\r', ' ').replaceAll("\\s+", " ").trim();
        String badge = null;
        Matcher m = Pattern.compile("^\\[([^\\]]+)\\]\\s*(.*)$").matcher(s);
        if (m.matches()) {
            badge = m.group(1).trim();
            s = m.group(2).trim();
        }
        s = s.replaceAll("(?i)\\.(mkv|mp4|ts|flv|avi|mov|m3u8)$", "").trim();
        if (s.length() > 12 && s.length() % 2 == 1) {
            int half = s.length() / 2;
            if (s.charAt(half) == ' '
                    && s.substring(0, half).equalsIgnoreCase(s.substring(half + 1))) {
                s = s.substring(0, half).trim();
            }
        }
        if (s.isEmpty() || s.matches("(?i)^[Ee]?0*\\d+$")) {
            s = "E" + String.format(Locale.US, "%02d", epNumber);
        }
        return new EpisodeDisplay(s, badge);
    }

    static String formatPlayEpisodeLabel(String rawName, int epNumber) {
        EpisodeDisplay disp = parseEpisodeDisplay(rawName, epNumber);
        String clean = disp.cleanTitle == null ? "" : disp.cleanTitle.trim();
        Matcher mEp = Pattern.compile("第\\s*([一二三四五六七八九十百零两\\d]+)\\s*([集话期篇])").matcher(clean);
        if (mEp.find()) {
            return "第 " + mEp.group(1) + " " + mEp.group(2);
        }
        if (clean.matches("(?i)^[Ee]\\d+$")) {
            return "第 " + epNumber + " 集";
        }
        if (clean.length() > 10
                || clean.contains(".")
                || clean.matches(
                        "(?i).*(1080|2160|720|4k|web|h264|h265|x264|x265|ddp|aac|hdr|dv|remux|bluray).*")) {
            return "第 " + epNumber + " 集";
        }
        return clean.isEmpty() ? ("第 " + epNumber + " 集") : clean;
    }

    /**
     * Full-screen portrait backdrop canvas that renders:
     * 1. Solid ambient color matching the poster's dominant mood
     * 2. Full-bleed poster from y=0 to 77% screen height with vertical DST_IN alpha feathering
     * 3. Lower 58%..100% softened/blurred overlap transition + top 28% breathable status-bar scrim
     * 4. Smooth scroll-driven blur & veil cross-fade when scrolling down to the episode list.
     */
    static final class PortraitBackdropView extends View {
        private final MainActivity a;
        private final int posterWindowHeight;
        private final int topScrimHeight;
        private int ambientColor;
        private Bitmap rawBitmap;
        private Bitmap blurredBitmap;
        private float scrollProgress = 0f;
        private int blurStartY;
        private int revision = 0;

        private final Paint bitmapPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix drawMatrix = new Matrix();

        PortraitBackdropView(
                MainActivity activity,
                int posterWindowHeight,
                int topScrimHeight,
                int initAmbient) {
            super(activity);
            this.a = activity;
            this.posterWindowHeight = posterWindowHeight;
            this.topScrimHeight = topScrimHeight;
            this.ambientColor = initAmbient;
            this.blurStartY = Math.round(posterWindowHeight * 0.62f);
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        void setBlurStartY(int y) {
            int clamped = Math.max(a.dp(120), Math.min(posterWindowHeight - a.dp(40), y));
            if (Math.abs(clamped - blurStartY) > a.dp(2)) {
                blurStartY = clamped;
                invalidate();
            }
        }

        void setScrollOffset(int scrollY) {
            float maxScroll = Math.max(1f, a.dp(340));
            float next = Math.max(0f, Math.min(1f, scrollY / maxScroll));
            if (Math.abs(next - scrollProgress) > 0.008f) {
                scrollProgress = next;
                invalidate();
            }
        }

        void setPosterBitmap(Bitmap bmp, Runnable onAmbientChanged) {
            this.rawBitmap = bmp;
            final int token = ++revision;
            final int gen = a.generation;
            if (bmp == null) {
                blurredBitmap = null;
                invalidate();
                return;
            }
            a.artwork.execute(
                    () -> {
                        // 2.0.41: the blurred bitmap is still built for the
                        // hero window, but the extracted poster colour is
                        // NOT adopted anymore — the page base stays the
                        // unified dark matte (ambientColor keeps its DARK
                        // initial value).
                        Bitmap small = Bitmap.createScaledBitmap(bmp, 84, 126, true);
                        int[] pixels = new int[84 * 126];
                        small.getPixels(pixels, 0, 84, 0, 0, 84, 126);
                        if (small != bmp) small.recycle();
                        for (int pass = 0; pass < 4; pass++) {
                            int[] next = new int[pixels.length];
                            for (int y = 0; y < 126; y++) {
                                for (int x = 0; x < 84; x++) {
                                    int r = 0, g = 0, b = 0, count = 0;
                                    for (int dy = -4; dy <= 4; dy++) {
                                        for (int dx = -4; dx <= 4; dx++) {
                                            int c =
                                                    pixels[
                                                            Math.max(0, Math.min(125, y + dy)) * 84
                                                                    + Math.max(
                                                                            0,
                                                                            Math.min(83, x + dx))];
                                            r += Color.red(c);
                                            g += Color.green(c);
                                            b += Color.blue(c);
                                            count++;
                                        }
                                    }
                                    next[y * 84 + x] = Color.rgb(r / count, g / count, b / count);
                                }
                            }
                            pixels = next;
                        }
                        final Bitmap soft =
                                Bitmap.createBitmap(pixels, 84, 126, Bitmap.Config.ARGB_8888);
                        a.ui.post(
                                () -> {
                                    if (token != revision
                                            || gen != a.generation
                                            || a.isFinishing()) {
                                        soft.recycle();
                                        return;
                                    }
                                    // 2.0.41: ambientColor stays DARK (see
                                    // above) — only the blurred hero bitmap
                                    // is adopted.
                                    blurredBitmap = soft;
                                    if (onAmbientChanged != null) onAmbientChanged.run();
                                    invalidate();
                                });
                    });
            invalidate();
        }

        int getAmbientColor() {
            return ambientColor;
        }

        Bitmap getPosterBitmap() {
            return rawBitmap;
        }

        private void configureCenterCropMatrix(Bitmap bmp, int targetW, int targetH) {
            drawMatrix.reset();
            float bw = bmp.getWidth();
            float bh = bmp.getHeight();
            if (bw <= 0 || bh <= 0 || targetW <= 0 || targetH <= 0) return;
            float scale = Math.max(targetW / bw, targetH / bh);
            float dx = (targetW - bw * scale) * 0.5f;
            // Bias slightly toward upper-center so faces and key poster artwork fill the expanded
            // upper window
            float dy = (targetH - bh * scale) * 0.26f;
            drawMatrix.setScale(scale, scale);
            drawMatrix.postTranslate(dx, dy);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 1. Unified Solid Ambient Canvas
            canvas.drawColor(ambientColor);

            int winH = Math.min(h, posterWindowHeight);
            int bTop = Math.max(a.dp(120), Math.min(winH - a.dp(56), blurStartY - a.dp(10)));
            int bFull = Math.min(winH, bTop + a.dp(54));

            if (rawBitmap != null && !rawBitmap.isRecycled() && winH > 0) {
                int save = canvas.saveLayer(0, 0, w, winH, null);
                configureCenterCropMatrix(rawBitmap, w, winH);
                bitmapPaint.setAlpha(255);
                canvas.drawBitmap(rawBitmap, drawMatrix, bitmapPaint);

                // Progressive Frosted-Glass Blur starting from the movie title position
                // (bTop..bFull)
                // downward so poster graphics never interfere with reading the title & content
                // below
                if (blurredBitmap != null && !blurredBitmap.isRecycled()) {
                    int blurLayer = canvas.saveLayer(0, 0, w, winH, null);
                    configureCenterCropMatrix(blurredBitmap, w, winH);
                    bitmapPaint.setAlpha(255);
                    canvas.drawBitmap(blurredBitmap, drawMatrix, bitmapPaint);

                    int topBlurAlpha = Math.round(scrollProgress * 225f);
                    int topMaskColor =
                            (Math.max(0, Math.min(255, topBlurAlpha)) << 24) | 0x00ffffff;
                    float stopTop = Math.max(0.05f, Math.min(0.88f, bTop / (float) winH));
                    float stopFull =
                            Math.max(stopTop + 0.04f, Math.min(0.96f, bFull / (float) winH));
                    maskPaint.setShader(
                            new LinearGradient(
                                    0,
                                    0,
                                    0,
                                    winH,
                                    new int[] {topMaskColor, topMaskColor, 0xffffffff, 0xffffffff},
                                    new float[] {0f, stopTop, stopFull, 1f},
                                    Shader.TileMode.CLAMP));
                    canvas.drawRect(0, 0, w, winH, maskPaint);
                    maskPaint.setShader(null);
                    canvas.restoreToCount(blurLayer);
                }

                // Intrinsic vertical alpha feathering into ambient canvas
                maskPaint.setShader(
                        new LinearGradient(
                                0,
                                0,
                                0,
                                winH,
                                new int[] {
                                    0xffffffff, 0xffffffff, 0xd9ffffff, 0x66ffffff, 0x00ffffff
                                },
                                new float[] {0f, 0.78f, 0.88f, 0.95f, 1f},
                                Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, winH, maskPaint);
                maskPaint.setShader(null);
                canvas.restoreToCount(save);
            }

            // 2. Breathable 28% Top Status-Bar Scrim
            if (topScrimHeight > 0) {
                int cTop = withAlpha(ambientColor, 72);
                int cMid = withAlpha(ambientColor, 26);
                int cBot = withAlpha(ambientColor, 0);
                gradientPaint.setShader(
                        new LinearGradient(
                                0,
                                0,
                                0,
                                topScrimHeight,
                                new int[] {cTop, cMid, cBot},
                                new float[] {0f, 0.55f, 1f},
                                Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, topScrimHeight, gradientPaint);
                gradientPaint.setShader(null);
            }

            // 3. Progressive Ambient Readability Gradient starting at the movie title (bTop)
            int lockTop = Math.max(0, bTop - a.dp(8));
            gradientPaint.setShader(
                    new LinearGradient(
                            0,
                            lockTop,
                            0,
                            h,
                            new int[] {
                                withAlpha(ambientColor, 0),
                                withAlpha(ambientColor, 148),
                                withAlpha(ambientColor, 226),
                                ambientColor,
                                ambientColor
                            },
                            new float[] {0f, 0.16f, 0.34f, 0.48f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, lockTop, w, h, gradientPaint);
            gradientPaint.setShader(null);

            // 4. Scroll Veil
            if (scrollProgress > 0.01f) {
                int veilAlpha = Math.round(scrollProgress * 98f);
                canvas.drawColor(withAlpha(ambientColor, veilAlpha));
            }
        }

        private static int withAlpha(int rgb, int alpha) {
            return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00ffffff);
        }
    }

    /**
     * Hidden ImageView hook passed to `a.poster(url, proxyImage, true)` so whenever MainActivity's
     * artwork loader finishes decoding the poster Bitmap, it feeds `PortraitBackdropView`.
     */
    static final class PosterHookImageView extends ImageView {
        private final PortraitBackdropView target;
        private final Runnable onLoaded;
        final int targetW;
        final int targetH;
        private Bitmap lockedHd;

        PosterHookImageView(MainActivity a, PortraitBackdropView target, Runnable onLoaded) {
            super(a);
            this.target = target;
            this.onLoaded = onLoaded;
            setScaleType(ScaleType.CENTER_CROP);
            this.targetW = Math.max(1080, a.windowWidth());
            this.targetH = Math.max(1620, Math.round(targetW * 1.5f));
            setLayoutParams(new FrameLayout.LayoutParams(targetW, targetH));
            layout(0, 0, targetW, targetH);
        }

        void setHdBitmap(Bitmap hdBmp) {
            if (hdBmp == null || hdBmp.isRecycled()) return;
            this.lockedHd = hdBmp;
            super.setImageBitmap(hdBmp);
            target.setPosterBitmap(hdBmp, onLoaded);
        }

        void resetHdLock() {
            this.lockedHd = null;
        }

        @Override
        public void setImageBitmap(Bitmap bm) {
            if (lockedHd != null && !lockedHd.isRecycled() && bm != lockedHd) {
                return;
            }
            super.setImageBitmap(bm);
            target.setPosterBitmap(bm, onLoaded);
        }
    }

    static void refreshEpisodeStillBitmaps(View v, Bitmap hdBmp) {
        if (v == null || hdBmp == null || hdBmp.isRecycled()) return;
        if (v instanceof EpisodeStillCropView) {
            ((EpisodeStillCropView) v).setImageBitmap(hdBmp);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                refreshEpisodeStillBitmaps(vg.getChildAt(i), hdBmp);
            }
        }
    }

    /**
     * 16:9 Episode Still ImageView that crops a subtle per-episode camera pan from the series
     * poster so each episode card in the horizontal strip has a rich cinematic 16:9 frame.
     */
    static final class EpisodeStillCropView extends ImageView {
        private final int epIndex;
        private final Matrix cropMatrix = new Matrix();

        EpisodeStillCropView(MainActivity a, int epIndex) {
            super(a);
            this.epIndex = epIndex;
            setScaleType(ScaleType.MATRIX);
        }

        @Override
        protected boolean setFrame(int l, int t, int r, int b) {
            boolean changed = super.setFrame(l, t, r, b);
            updateCropMatrix(r - l, b - t);
            return changed;
        }

        @Override
        public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            updateCropMatrix(getWidth(), getHeight());
        }

        private void updateCropMatrix(int vw, int vh) {
            if (getDrawable() == null || vw <= 0 || vh <= 0) return;
            int dw = getDrawable().getIntrinsicWidth();
            int dh = getDrawable().getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            cropMatrix.reset();
            float baseScale = Math.max((float) vw / dw, (float) vh / dh);
            float zoom = 1.04f + (epIndex % 4) * 0.03f;
            float scale = baseScale * zoom;
            float[] yAnchors = {0.22f, 0.36f, 0.50f, 0.30f, 0.44f};
            float[] xAnchors = {0.50f, 0.38f, 0.62f, 0.45f, 0.55f};
            float ax = xAnchors[epIndex % xAnchors.length];
            float ay = yAnchors[epIndex % yAnchors.length];
            float dx = (vw - dw * scale) * ax;
            float dy = (vh - dh * scale) * ay;
            cropMatrix.setScale(scale, scale);
            cropMatrix.postTranslate(dx, dy);
            setImageMatrix(cropMatrix);
        }
    }

    static void show(MainActivity a, Cms.Site s, Cms.Video listing, Cms.Video ready) {
        if (a.redirectListing(s, listing)) return;

        a.clearPlaybackLoading();
        a.cancelArtwork();
        a.cancelPageRequests();
        if (MobileShell.activeSearchCoordinator != null) {
            MobileShell.activeSearchCoordinator.close();
            MobileShell.activeSearchCoordinator = null;
        }
        // 2.0.40: also stop a global search still running on the search
        // page — its coordinator is separate from the home shell's
        // activeSearchCoordinator above, and this page does not go
        // through preparePage (which is what normally closes the search
        // screen on navigation). Without this, tapping a result left
        // the 12 search threads running behind the detail page and
        // starved the detail load of network/CPU. Back restores the
        // results from staticResults, so cancelling loses nothing.
        if (a.searchScreen != null) {
            a.searchScreen.close();
            a.searchScreen = null;
        }
        if (a.current != null) a.current.home = false;
        a.stopWeb();
        a.stopMagnet();
        a.releasePlayer();
        a.generation++;

        // Edge-to-edge transparent status bar so the full-bleed poster reaches physical top y=0
        a.applyMobileSystemBars(false);

        final int topPad = getStatusBarHeight(a);
        final int screenH = a.getResources().getDisplayMetrics().heightPixels;
        final int posterWindowH =
                Math.max(a.dp(640), Math.min(a.dp(860), Math.round(screenH * 0.85f)));
        final int foregroundHeroH =
                Math.max(a.dp(510), Math.min(a.dp(660), Math.round(posterWindowH * 0.81f)));
        final int initAmbient = defaultAmbientForTitle(listing.title);

        a.root = new FrameLayout(a);
        a.root.setBackgroundColor(initAmbient);
        a.setContentView(a.root);

        // 1. Full-Screen Immersive Backdrop & Poster Layer
        final PortraitBackdropView backdropView =
                new PortraitBackdropView(a, posterWindowH, topPad + a.dp(54), initAmbient);
        backdropView.setTag("detail-backdrop");
        a.root.addView(backdropView, new FrameLayout.LayoutParams(-1, -1));

        final PosterHookImageView posterHook =
                new PosterHookImageView(
                        a,
                        backdropView,
                        () -> {
                            if (a.root != null) {
                                a.root.setBackgroundColor(backdropView.getAmbientColor());
                            }
                        });
        posterHook.setTag("detail-cover");
        posterHook.setVisibility(View.GONE);
        a.root.addView(
                posterHook, new FrameLayout.LayoutParams(posterHook.targetW, posterHook.targetH));
        if (listing.poster != null && !listing.poster.isEmpty()) {
            a.poster(listing.poster, posterHook, true);
        }
        ImmersivePoster.resolveHdPortraitPoster(
                a,
                listing.title,
                listing.year,
                listing.actor,
                listing.director,
                listing.poster,
                !SearchVariants.isComicOrShortDrama(listing, s.name),
                hdBmp -> {
                    posterHook.setHdBitmap(hdBmp);
                    refreshEpisodeStillBitmaps(a.root, hdBmp);
                });

        // 2. Main Scrollable Foreground Content
        final ScrollView scrollView = new ScrollView(a);
        scrollView.setTag("detail-scroll");
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> backdropView.setScrollOffset(scrollView.getScrollY()));
        a.scroll = scrollView;
        a.root.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout mainCol = a.vertical();
        mainCol.setPadding(0, 0, 0, a.dp(46));
        scrollView.addView(mainCol, new ScrollView.LayoutParams(-1, -2));

        // 2A. Foreground Hero Container (bottom-aligned over the lower poster transition zone)
        FrameLayout heroStage = new FrameLayout(a);
        heroStage.setTag("detail-hero");
        mainCol.addView(heroStage, new LinearLayout.LayoutParams(-1, foregroundHeroH));

        LinearLayout heroCenterCol = a.vertical();
        heroCenterCol.setGravity(Gravity.CENTER_HORIZONTAL);
        heroCenterCol.setPadding(a.dp(20), 0, a.dp(20), a.dp(14));
        FrameLayout.LayoutParams heroCenterLp =
                new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        heroStage.addView(heroCenterCol, heroCenterLp);

        // mkoai 2.0.62: Fanart clear logo, shown above the title once the
        // Fanart fetch lands. GONE (zero size) until then — never an empty slot.
        final ImageView fanartLogoView = new ImageView(a);
        fanartLogoView.setTag("detail-fanart-logo");
        fanartLogoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fanartLogoView.setAdjustViewBounds(true);
        fanartLogoView.setVisibility(View.GONE);
        LinearLayout.LayoutParams logoLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, a.dp(64));
        logoLp.bottomMargin = a.dp(6);
        heroCenterCol.addView(fanartLogoView, logoLp);

        final TextView titleView = a.text(listing.title, 28, MainActivity.WHITE);
        titleView.setTag("detail-title");
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setShadowLayer(a.dp(14), 0, a.dp(4), 0xb3000000);
        heroCenterCol.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        // mkoai 2.0.38 (Emby style): single centered meta line under the
        // title — "★ 7.2 | 2026 | Youku | 剧情". Filled in by
        // updateMetaLine once the detail (and the Douban score) arrives.
        final TextView metaLine = a.text("", 13, 0xccffffff);
        metaLine.setTag("detail-meta-line");
        metaLine.setGravity(Gravity.CENTER);
        metaLine.setSingleLine(true);
        metaLine.setEllipsize(TextUtils.TruncateAt.END);
        metaLine.setVisibility(View.GONE);
        LinearLayout.LayoutParams metaLineLp = new LinearLayout.LayoutParams(-1, -2);
        metaLineLp.topMargin = a.dp(8);
        heroCenterCol.addView(metaLine, metaLineLp);

        final LinearLayout pillsRow = a.row();
        pillsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pillsRowLp = new LinearLayout.LayoutParams(-1, -2);
        pillsRowLp.topMargin = a.dp(12);
        heroCenterCol.addView(pillsRow, pillsRowLp);

        // Sync progressive poster blur start Y with the unscrolled top of heroCenterCol (the movie
        // title)
        heroCenterCol.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    int titleTopY = heroStage.getTop() + t;
                    backdropView.setBlurStartY(titleTopY);
                });

        // Douban Rating + Rotten Tomatoes Rating + Site Name Row
        LinearLayout ratingRow = a.row();
        ratingRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ratingLp = new LinearLayout.LayoutParams(-2, -2);
        ratingLp.topMargin = a.dp(10);
        heroCenterCol.addView(ratingRow, ratingLp);

        final TextView doubanBadge = a.text("", 11.5f, 0xff6ee7b7);
        doubanBadge.setTag("detail-douban-rating");
        doubanBadge.setTypeface(null, Typeface.BOLD);
        doubanBadge.setPadding(a.dp(9), a.dp(3), a.dp(9), a.dp(3));
        doubanBadge.setBackground(glassBg(a, 0x3800b51d, 0x6634d399, 999));
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(-2, -2);
        dbLp.rightMargin = a.dp(7);
        ratingRow.addView(doubanBadge, dbLp);

        final TextView rtBadge = a.text("", 11.5f, 0xffffb4a2);
        rtBadge.setTag("detail-rt-rating");
        rtBadge.setTypeface(null, Typeface.BOLD);
        rtBadge.setPadding(a.dp(9), a.dp(3), a.dp(9), a.dp(3));
        rtBadge.setBackground(glassBg(a, 0x38fa320a, 0x66ff6b4a, 999));
        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(-2, -2);
        rtLp.rightMargin = a.dp(7);
        ratingRow.addView(rtBadge, rtLp);

        // mkoai 2.0.62: OMDb ratings — IMDb + Metacritic, same badge style.
        // Hidden until the OMDb fetch lands; updateRatingBadges() fills them.
        final TextView imdbBadge = a.text("", 11.5f, 0xfffde68a);
        imdbBadge.setTag("detail-imdb-rating");
        imdbBadge.setTypeface(null, Typeface.BOLD);
        imdbBadge.setPadding(a.dp(9), a.dp(3), a.dp(9), a.dp(3));
        imdbBadge.setBackground(glassBg(a, 0x38f5c518, 0x66fbbf24, 999));
        imdbBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams imdbLp = new LinearLayout.LayoutParams(-2, -2);
        imdbLp.rightMargin = a.dp(7);
        ratingRow.addView(imdbBadge, imdbLp);

        final TextView mcBadge = a.text("", 11.5f, 0xffa5b4fc);
        mcBadge.setTag("detail-mc-rating");
        mcBadge.setTypeface(null, Typeface.BOLD);
        mcBadge.setPadding(a.dp(9), a.dp(3), a.dp(9), a.dp(3));
        mcBadge.setBackground(glassBg(a, 0x384c6ef5, 0x666080f0, 999));
        mcBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(-2, -2);
        mcLp.rightMargin = a.dp(7);
        ratingRow.addView(mcBadge, mcLp);

        TextView siteBadge = a.text(s.name, 11.5f, 0xe6ffffff);
        siteBadge.setPadding(a.dp(9), a.dp(3), a.dp(9), a.dp(3));
        siteBadge.setBackground(glassBg(a, 0x26ffffff, 0x38ffffff, 999));
        ratingRow.addView(siteBadge);

        updateRatingBadges(doubanBadge, rtBadge, listing, 0.0);
        updateMetaLine(metaLine, listing, s.name, 0.0);
        ImmersivePoster.resolveDoubanRating(
                a,
                listing.title,
                listing.year,
                score -> {
                    updateRatingBadges(doubanBadge, rtBadge, listing, score);
                    updateMetaLine(metaLine, listing, s.name, score);
                });

        // 2B. Lower Content Column (Synopsis + White Capsule Play Button + Episodes Section)
        LinearLayout lowerBody = a.vertical();
        lowerBody.setPadding(a.dp(18), 0, a.dp(18), 0);
        mainCol.addView(lowerBody, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout synopsisWrap = a.vertical();
        synopsisWrap.setTag("detail-synopsis-wrap");
        synopsisWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        lowerBody.addView(synopsisWrap, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout playBtnWrap = a.vertical();
        LinearLayout.LayoutParams playWrapLp = new LinearLayout.LayoutParams(-1, -2);
        playWrapLp.topMargin = a.dp(14);
        lowerBody.addView(playBtnWrap, playWrapLp);

        final TextView loadingState = a.text("正在載入劇集資源…", 13.5f, 0xb3ffffff);
        loadingState.setTag("detail-loading");
        loadingState.setGravity(Gravity.CENTER);
        loadingState.setPadding(a.dp(4), a.dp(24), a.dp(4), a.dp(16));
        lowerBody.addView(loadingState, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout detailsBody = a.vertical();
        LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(-1, -2);
        detailsLp.topMargin = a.dp(18);
        lowerBody.addView(detailsBody, detailsLp);

        // 3. Floating Liquid Glass Top Navigation Bar
        //    Left: Circular `<` Back button
        //    Right: Independent `[ ⇄ 线路名 ⌄ ]` Source Picker + `[ ⬇ | ♥ ]` Download & Favorite Pill
        LinearLayout topNavRow = a.row();
        topNavRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams topNavLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topNavLp.setMargins(a.dp(16), topPad + a.dp(8), a.dp(16), 0);
        a.root.addView(topNavRow, topNavLp);

        TextView backBtn = a.text("‹", 22, Color.WHITE);
        backBtn.setTag("detail-back");
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(0, 0, a.dp(1), a.dp(2));
        a.glass(backBtn, 999);
        bindTouchFeedback(backBtn);
        backBtn.setOnClickListener(v -> a.onBackPressed());
        topNavRow.addView(backBtn, new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));

        View topSpacer = new View(a);
        topNavRow.addView(topSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // mkoai 2.0.38 (Emby style): the top-right source capsule is gone —
        // source switching lives in the subtle "片源：xxx · 點此切換" line
        // under the Play button, and line switching in the subtle
        // "線路：xxx · 點此切換" line. Top bar keeps only Back + actions.

        // Top-right Dual Action Capsule (`[ ⬇ | ♥ ]`)
        LinearLayout rightActionPill = a.row();
        rightActionPill.setGravity(Gravity.CENTER_VERTICAL);
        rightActionPill.setPadding(a.dp(4), 0, a.dp(4), 0);
        a.glass(rightActionPill, 999);
        topNavRow.addView(rightActionPill, new LinearLayout.LayoutParams(-2, a.dp(40)));

        final TextView downloadTopBtn = a.text("⬇", 16, Color.WHITE);
        downloadTopBtn.setTag("episodes-download");
        downloadTopBtn.setGravity(Gravity.CENTER);
        downloadTopBtn.setPadding(a.dp(11), 0, a.dp(11), 0);
        bindTouchFeedback(downloadTopBtn);
        downloadTopBtn.setOnClickListener(v -> a.toast("正在載入劇集列表，請稍候…"));
        rightActionPill.addView(downloadTopBtn, new LinearLayout.LayoutParams(-2, a.dp(36)));

        View pillDivider = new View(a);
        pillDivider.setBackgroundColor(0x36ffffff);
        rightActionPill.addView(pillDivider, new LinearLayout.LayoutParams(1, a.dp(18)));

        final TextView favTopBtn = a.text("♥", 17, Color.WHITE);
        favTopBtn.setTag("detail-favorite");
        favTopBtn.setGravity(Gravity.CENTER);
        favTopBtn.setPadding(a.dp(11), 0, a.dp(11), 0);
        bindTouchFeedback(favTopBtn);
        rightActionPill.addView(favTopBtn, new LinearLayout.LayoutParams(-2, a.dp(36)));

        final Runnable syncFavStyle =
                () -> {
                    boolean faved =
                            Favorites.contains(
                                    a.prefs.getString(Prefs.FAVORITES, "[]"), s.key, listing.id);
                    favTopBtn.setText(faved ? "♥" : "♡");
                    favTopBtn.setTextColor(faved ? 0xffff453a : Color.WHITE);
                };
        syncFavStyle.run();

        // Populate initial metadata pills from listing
        populateHeroPills(a, pillsRow, listing, 0);

        MainActivity.Done<Cms.Video> present =
                v -> {
                    if ((v.id == null || v.id.trim().isEmpty()) && listing.id != null) {
                        v.id = listing.id.trim();
                    }
                    if (v.poster == null || v.poster.isEmpty()) v.poster = listing.poster;
                    if (v.title == null || v.title.isEmpty()) v.title = listing.title;
                    titleView.setText(ChineseConvert.s2t(v.title));
                    if (!Objects.equals(v.title, listing.title)) {
                        posterHook.resetHdLock();
                    }
                    if (!Objects.equals(v.poster, listing.poster)
                            && v.poster != null
                            && !v.poster.isEmpty()) {
                        a.poster(v.poster, posterHook, true);
                    }
                    ImmersivePoster.resolveHdPortraitPoster(
                            a,
                            v.title,
                            v.year,
                            v.actor,
                            v.director,
                            v.poster,
                            !SearchVariants.isComicOrShortDrama(v, s.name),
                            hdBmp -> {
                                posterHook.setHdBitmap(hdBmp);
                                refreshEpisodeStillBitmaps(a.root, hdBmp);
                            });
                    lowerBody.removeView(loadingState);
                    synopsisWrap.removeAllViews();
                    playBtnWrap.removeAllViews();
                    detailsBody.removeAllViews();
                    // mkoai 2.0.62: drop the previous extras box (TVmaze line +
                    // translate button + translated text) so a re-present never
                    // duplicates it; it is rebuilt below.
                    View oldExtras = a.root.findViewWithTag("detail-extras-box");
                    if (oldExtras != null && oldExtras.getParent() instanceof ViewGroup) {
                        ((ViewGroup) oldExtras.getParent()).removeView(oldExtras);
                    }

                    for (java.util.Iterator<Cms.Line> it = v.lines.iterator(); it.hasNext(); ) {
                        Cms.Line line = it.next();
                        if (line.episodes.isEmpty()) {
                            it.remove();
                            continue;
                        }
                        // mkoai: when the user hides netdisk features, drop netdisk lines
                        // so they never appear (and the QR-login dialog never pops up).
                        if (a.prefs.getBoolean(Prefs.HIDE_NETDISK, false)) {
                            String firstUrl =
                                    line.episodes.isEmpty() ? "" : line.episodes.get(0).url;
                            if (PlaybackController.looksLikeNetdiskLine(s, line, firstUrl))
                                it.remove();
                        }
                    }
                    int firstLineCount =
                            v.lines.isEmpty()
                                    ? 0
                                    : v.lines
                                            .get(SourcePreferences.lineIndex(a, v))
                                            .episodes
                                            .size();
                    populateHeroPills(a, pillsRow, v, firstLineCount);
                    updateRatingBadges(doubanBadge, rtBadge, v, 0.0);
                    updateMetaLine(metaLine, v, s.name, 0.0);
                    ImmersivePoster.resolveDoubanRating(
                            a,
                            v.title,
                            v.year,
                            score -> {
                                updateRatingBadges(doubanBadge, rtBadge, v, score);
                                updateMetaLine(metaLine, v, s.name, score);
                            });

                    favTopBtn.setOnClickListener(
                            view -> {
                                a.prefs
                                        .edit()
                                        .putString(
                                                Prefs.FAVORITES,
                                                Favorites.toggle(
                                                        a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                        s.key,
                                                        v))
                                        .apply();
                                boolean faved =
                                        Favorites.contains(
                                                a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                s.key,
                                                v.id);
                                favTopBtn.setText(faved ? "♥" : "♡");
                                favTopBtn.setTextColor(faved ? 0xffff453a : Color.WHITE);
                                a.toast(faved ? "已加入收藏片庫" : "已從片庫移除");
                            });

                    // Centered Expandable Synopsis
                    String descRaw =
                            v.description == null
                                    ? ""
                                    : ChineseConvert.s2t(v.description.trim());
                    String castRaw =
                            (v.actor != null && !v.actor.trim().isEmpty())
                                    ? "演職員：" + ChineseConvert.s2t(v.actor.trim())
                                    : "";
                    if (!descRaw.isEmpty() || !castRaw.isEmpty()) {
                        final TextView descTv =
                                a.text(descRaw.isEmpty() ? castRaw : descRaw, 13.5f, 0xd9ffffff);
                        descTv.setTag("detail-synopsis");
                        descTv.setGravity(Gravity.CENTER);
                        descTv.setLineSpacing(a.dp(3.5f), 1f);
                        descTv.setMaxLines(2);
                        descTv.setEllipsize(TextUtils.TruncateAt.END);
                        synopsisWrap.addView(descTv, new LinearLayout.LayoutParams(-1, -2));

                        final TextView castTv = a.text(castRaw, 12, 0x99ffffff);
                        castTv.setTag("detail-cast");
                        castTv.setGravity(Gravity.CENTER);
                        castTv.setPadding(0, a.dp(5), 0, 0);
                        castTv.setMaxLines(3);
                        castTv.setEllipsize(TextUtils.TruncateAt.END);
                        castTv.setVisibility(View.GONE);
                        if (!descRaw.isEmpty() && !castRaw.isEmpty()) {
                            synopsisWrap.addView(castTv, new LinearLayout.LayoutParams(-1, -2));
                        }

                        final boolean[] expanded = {false};
                        synopsisWrap.setOnClickListener(
                                view -> {
                                    expanded[0] = !expanded[0];
                                    descTv.setMaxLines(expanded[0] ? 18 : 2);
                                    if (!descRaw.isEmpty() && !castRaw.isEmpty()) {
                                        castTv.setVisibility(
                                                expanded[0] ? View.VISIBLE : View.GONE);
                                    }
                                });
                    }

                    // mkoai 2.0.62: extras box under the synopsis — TVmaze airing
                    // info (TV only, filled by enrichDetailExtras), the
                    // translate button (non-Chinese synopsis only) and the
                    // translated text (shown under the original, never over it).
                    // All three start GONE; the box itself is rebuilt on every
                    // present so a re-present never duplicates it.
                    final LinearLayout extrasBox = a.vertical();
                    extrasBox.setTag("detail-extras-box");
                    extrasBox.setGravity(Gravity.CENTER_HORIZONTAL);
                    final TextView tvmazeInfo = a.text("", 12.5f, 0xccffffff);
                    tvmazeInfo.setTag("detail-tvmaze-info");
                    tvmazeInfo.setGravity(Gravity.CENTER);
                    tvmazeInfo.setVisibility(View.GONE);
                    tvmazeInfo.setPadding(0, a.dp(8), 0, 0);
                    extrasBox.addView(tvmazeInfo, new LinearLayout.LayoutParams(-1, -2));
                    // mkoai 2.0.63: no emoji (zero-emoji design language).
                    final TextView translateBtn = a.text("翻譯簡介", 13, 0xccffffff);
                    translateBtn.setTag("detail-translate-btn");
                    translateBtn.setGravity(Gravity.CENTER);
                    translateBtn.setPadding(a.dp(8), a.dp(10), a.dp(8), 0);
                    translateBtn.setVisibility(View.GONE);
                    bindTouchFeedback(translateBtn);
                    translateBtn.setOnClickListener(
                            view -> {
                                TextView currentDesc =
                                        (TextView) a.root.findViewWithTag("detail-synopsis");
                                String overview =
                                        currentDesc == null
                                                ? ""
                                                : currentDesc.getText().toString();
                                if (overview.trim().isEmpty()) return;
                                translateBtn.setEnabled(false);
                                a.toast("正在翻譯簡介…");
                                a.submitPage(
                                        () -> {
                                            try {
                                                final String translated =
                                                        TranslateClient.translate(overview.trim());
                                                a.ui.post(
                                                        () -> {
                                                            translateBtn.setEnabled(true);
                                                            if (a.isFinishing()
                                                                    || a.root == null
                                                                    || !isSameDetailPage(
                                                                            a, v.title)) {
                                                                return;
                                                            }
                                                            TextView translatedTv =
                                                                    (TextView)
                                                                            a.root.findViewWithTag(
                                                                                    "detail-synopsis-translated");
                                                            if (translatedTv == null) return;
                                                            if (translated == null
                                                                    || translated.trim().isEmpty()) {
                                                                a.toast("翻譯失敗，請稍後再試");
                                                                return;
                                                            }
                                                            translatedTv.setText(
                                                                    translated.trim());
                                                            translatedTv.setVisibility(
                                                                    View.VISIBLE);
                                                            translateBtn.setVisibility(View.GONE);
                                                        });
                                            } catch (WorkerApi.RateLimited rateLimited) {
                                                // mkoai 2.0.66: DeepL quota (or MyMemory
                                                // also failed — TranslateClient rethrows
                                                // the original DeepL RateLimited). The
                                                // record's return decides the hide: a
                                                // real quota signal hides the button until
                                                // it returns (24h auto-restore + re-test);
                                                // an unreadable 429 records nothing and
                                                // hides nothing (fail-open). A network
                                                // failure never reaches here.
                                                long until = QuotaState2066.record(
                                                        QuotaState2066.prefsStore(a.prefs),
                                                        QuotaState2066.TRANSLATE,
                                                        rateLimited.error, rateLimited.source,
                                                        rateLimited.retryAfterOrNull,
                                                        System.currentTimeMillis());
                                                a.ui.post(
                                                        () -> {
                                                            translateBtn.setEnabled(true);
                                                            if (until > 0) {
                                                                translateBtn.setVisibility(View.GONE);
                                                                a.toast("翻譯額度用完，24 小時後再試");
                                                            } else {
                                                                a.toast("翻譯失敗，請稍後再試");
                                                            }
                                                        });
                                            } catch (Throwable t) {
                                                a.ui.post(
                                                        () -> {
                                                            translateBtn.setEnabled(true);
                                                            a.toast("翻譯失敗，請稍後再試");
                                                        });
                                            }
                                        });
                            });
                    extrasBox.addView(translateBtn, new LinearLayout.LayoutParams(-1, -2));
                    final TextView translatedTv = a.text("", 13.5f, 0x99ffffff);
                    translatedTv.setTag("detail-synopsis-translated");
                    translatedTv.setGravity(Gravity.CENTER);
                    translatedTv.setLineSpacing(a.dp(3.5f), 1f);
                    translatedTv.setVisibility(View.GONE);
                    translatedTv.setPadding(0, a.dp(6), 0, 0);
                    extrasBox.addView(translatedTv, new LinearLayout.LayoutParams(-1, -2));
                    int synopsisIndex = lowerBody.indexOfChild(synopsisWrap);
                    lowerBody.addView(
                            extrasBox,
                            synopsisIndex >= 0 ? synopsisIndex + 1 : 0,
                            new LinearLayout.LayoutParams(-1, -2));
                    refreshTranslateButton(a);

                    if (v.lines.isEmpty()) {
                        TextView empty = a.text("這個片源沒有可播的劇集，正在自動尋找其他片源…", 14, 0xb3ffffff);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(a.dp(4), a.dp(20), a.dp(4), a.dp(20));
                        detailsBody.addView(empty, new LinearLayout.LayoutParams(-1, -2));
                        // mkoai: detail loaded but nothing playable — same fallback as
                        // a load failure: auto-search the other sources for this title.
                        AutoSourceFallback.find(
                                a,
                                listing,
                                s.key,
                                playable -> {
                                    if (playable != null) {
                                        a.toast("已自動切換片源：" + playable.site.name);
                                        a.autoPlayOnDetail = true;
                                        a.open(
                                                new MainActivity.Screen(
                                                        () ->
                                                                a.detail(
                                                                        playable.site,
                                                                        playable.listing,
                                                                        playable.detail)),
                                                false);
                                        return;
                                    }
                                    empty.setText("暫未獲取到可播放的劇集資源");
                                });
                        return;
                    }

                    WatchHistory.Entry bookmark = null;
                    for (WatchHistory.Entry entry : a.watchHistory()) {
                        if (entry.site.equals(s.key) && entry.id.equals(v.id)) {
                            bookmark = entry;
                            break;
                        }
                    }
                    final WatchHistory.Entry remembered = bookmark;

                    // Full-Width White Capsule Play Button (shows clean episode label only;
                    // resume time and line are shown in the small subtitle below)
                    SeasonMeta curSeason = parseSeasonMeta(v.title);
                    int remEpNum = 1;
                    if (remembered != null) {
                        for (Cms.Line l : v.lines) {
                            if (l.name.equals(remembered.line)) {
                                for (int idx = 0; idx < l.episodes.size(); idx++) {
                                    if (l.episodes.get(idx).name.equals(remembered.episode)) {
                                        remEpNum = idx + 1;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                    String playEpLabel =
                            remembered != null
                                    ? formatPlayEpisodeLabel(remembered.episode, remEpNum)
                                    : formatPlayEpisodeLabel(
                                            v.lines
                                                    .get(SourcePreferences.lineIndex(a, v))
                                                    .episodes
                                                    .get(0)
                                                    .name,
                                            1);
                    String playLabel = "▶   播放 · " + playEpLabel;

                    Button playBtn = new Button(a);
                    playBtn.setTag("detail-play");
                    playBtn.setAllCaps(false);
                    playBtn.setMaxLines(1);
                    playBtn.setSingleLine(true);
                    playBtn.setEllipsize(TextUtils.TruncateAt.END);
                    playBtn.setGravity(Gravity.CENTER);
                    playBtn.setIncludeFontPadding(false);
                    playBtn.setTextSize(14.5f);
                    playBtn.setTypeface(null, Typeface.BOLD);
                    playBtn.setTextColor(0xff121215);
                    playBtn.setBackground(a.bg(0xf5ffffff, 999));
                    playBtn.setPadding(a.dp(18), 0, a.dp(18), 0);
                    bindTouchFeedback(playBtn);
                    playBtn.setText(playLabel);
                    playBtn.setOnClickListener(
                            view -> {
                                // 2.0.55: play the shown source immediately
                                // (playWithFreshSource opens the player with
                                // the current site/detail as first candidate).
                                if (remembered != null)
                                    playWithFreshSource(a, s, v, remembered, null);
                                else
                                    playWithFreshSource(
                                            a,
                                            s,
                                            v,
                                            null,
                                            v.lines
                                                    .get(SourcePreferences.lineIndex(a, v))
                                                    .episodes
                                                    .get(0)
                                                    .name);
                            });
                    playBtnWrap.addView(playBtn, new LinearLayout.LayoutParams(-1, a.dp(48)));

                    // mkoai: manual/auto source picker on the detail page. "自動選擇最佳片源"
                    // keeps the current auto behaviour; tapping a source reloads this title
                    // from that source.
                    TextView sourceBtn = a.text("片源：" + s.name + " · 點此切換", 13, 0xccffffff);
                    sourceBtn.setGravity(Gravity.CENTER);
                    sourceBtn.setPadding(a.dp(8), a.dp(10), a.dp(8), 0);
                    bindTouchFeedback(sourceBtn);
                    sourceBtn.setOnClickListener(
                            view -> {
                                a.toast("正在搜尋可用片源…");
                                AutoSourceFallback.findAll(
                                        a,
                                        listing,
                                        s.key,
                                        playables -> {
                                            if (playables == null || playables.isEmpty()) {
                                                a.toast("沒有找到其他可用片源");
                                                return;
                                            }
                                            String[] names = new String[playables.size() + 1];
                                            names[0] = "✦ 自動選擇最佳片源";
                                            for (int i = 0; i < playables.size(); i++) {
                                                AutoSourceFallback.Playable p = playables.get(i);
                                                int eps = 0;
                                                if (p.detail != null && p.detail.lines != null)
                                                    for (Cms.Line ln : p.detail.lines)
                                                        if (ln.episodes != null)
                                                            eps += ln.episodes.size();
                                                names[i + 1] =
                                                        p.site.name
                                                                + " · "
                                                                + eps
                                                                + " 集"
                                                                + (p.site.key.equals(s.key)
                                                                        ? "（目前）"
                                                                        : "");
                                            }
                                            new AppDialog.Builder(a)
                                                    .wrapChoices()
                                                    .setTitle("選擇片源")
                                                    .setItems(
                                                            names,
                                                            (d, index) -> {
                                                                if (index == 0) return;
                                                                AutoSourceFallback.Playable p =
                                                                        playables.get(index - 1);
                                                                if (p.site.key.equals(s.key)) return;
                                                                a.autoPlayOnDetail = true;
                                                                a.open(
                                                                        new MainActivity.Screen(
                                                                                () ->
                                                                                        a.detail(
                                                                                                p.site,
                                                                                                p.listing,
                                                                                                p.detail)),
                                                                        false);
                                                            })
                                                    .show();
                                        });
                            });
                    playBtnWrap.addView(sourceBtn, new LinearLayout.LayoutParams(-1, -2));

                    // mkoai 2.0.62: trailer button — same subtle line style as
                    // the source picker. Search runs in the background; the
                    // result list opens YouTube in the browser. YoutubeClient
                    // caches one search per title per session, so reopening
                    // the list never re-queries.
                    TextView trailerBtn = a.text("🎬  預告片 · YouTube", 13, 0xccffffff);
                    trailerBtn.setTag("detail-trailer");
                    // mkoai 2.0.66: while the YouTube daily quota is out the
                    // button stays hidden (it returns after the midnight
                    // Pacific reset).
                    if (QuotaState2066.isHidden(
                            QuotaState2066.prefsStore(a.prefs),
                            QuotaState2066.TRAILER, System.currentTimeMillis())) {
                        trailerBtn.setVisibility(View.GONE);
                    }
                    trailerBtn.setGravity(Gravity.CENTER);
                    trailerBtn.setPadding(a.dp(8), a.dp(10), a.dp(8), 0);
                    bindTouchFeedback(trailerBtn);
                    trailerBtn.setOnClickListener(
                            view -> {
                                a.toast("正在搜尋預告片…");
                                final String queryTitle = v.title;
                                a.submitPage(
                                        () -> {
                                            final List<YoutubeClient.Trailer> trailers;
                                            try {
                                                trailers = YoutubeClient.search(queryTitle);
                                            } catch (WorkerApi.RateLimited rateLimited) {
                                                // mkoai 2.0.66: YouTube daily quota — the
                                                // record's return decides the hide (see
                                                // the translate catch above): a real quota
                                                // signal hides the button until the midnight
                                                // Pacific reset; an unreadable 429 hides
                                                // nothing. A network failure never reaches
                                                // here (only a Worker 429 does).
                                                long until = QuotaState2066.record(
                                                        QuotaState2066.prefsStore(a.prefs),
                                                        QuotaState2066.TRAILER,
                                                        rateLimited.error, rateLimited.source,
                                                        rateLimited.retryAfterOrNull,
                                                        System.currentTimeMillis());
                                                a.ui.post(
                                                        () -> {
                                                            if (until > 0) {
                                                                a.toast(
                                                                        "YouTube 額度用完，午夜太平洋時間重置");
                                                                trailerBtn.setVisibility(View.GONE);
                                                            } else {
                                                                a.toast("預告片搜尋失敗，請稍後再試");
                                                            }
                                                        });
                                                return;
                                            } catch (Throwable t) {
                                                a.ui.post(
                                                        () ->
                                                                a.toast(
                                                                        "預告片搜尋失敗，請稍後再試"));
                                                return;
                                            }
                                            a.ui.post(
                                                    () -> {
                                                        if (a.isFinishing() || a.root == null) {
                                                            return;
                                                        }
                                                        if (trailers == null
                                                                || trailers.isEmpty()) {
                                                            a.toast("沒有找到預告片");
                                                            return;
                                                        }
                                                        showTrailerList(a, queryTitle, trailers);
                                                    });
                                        });
                            });
                    playBtnWrap.addView(trailerBtn, new LinearLayout.LayoutParams(-1, -2));

                    // mkoai 2.0.38 (Emby style): the line picker is a subtle
                    // tappable line, not a card row. Its text and dialog are
                    // kept in sync by renderMobileEpisodes (tag
                    // "detail-line-picker"); until then it shows the best
                    // line and waits for the episode list to load.
                    TextView lineBtn =
                            a.text(
                                    "線路："
                                            + v.lines
                                                    .get(SourcePreferences.lineIndex(a, v))
                                                    .name
                                            + " · 點此切換",
                                    13,
                                    0x99ffffff);
                    lineBtn.setTag("detail-line-picker");
                    lineBtn.setGravity(Gravity.CENTER);
                    lineBtn.setPadding(a.dp(8), a.dp(6), a.dp(8), 0);
                    bindTouchFeedback(lineBtn);
                    lineBtn.setOnClickListener(view -> a.toast("正在載入劇集列表…"));
                    playBtnWrap.addView(lineBtn, new LinearLayout.LayoutParams(-1, -2));

                    // mkoai 2.0.38 (item 23): quality entry — same probe result the
                    // player uses. Tapping lists the probed renditions; the pick is
                    // remembered per title+source and honoured when Play is pressed.
                    // (Only direct stream URLs can be probed before playback; sources
                    // that need parsing probe after playback starts, in the player.)
                    final Cms.Episode playEp = detailPlayEpisode(a, v, remembered);
                    TextView qualityBtn =
                            a.text(detailQualityLabel(a, s, v, playEp), 13, 0x99ffffff);
                    qualityBtn.setTag("detail-quality-picker");
                    qualityBtn.setGravity(Gravity.CENTER);
                    qualityBtn.setPadding(a.dp(8), a.dp(6), a.dp(8), 0);
                    bindTouchFeedback(qualityBtn);
                    qualityBtn.setOnClickListener(
                            view -> showDetailQualityMenu(a, s, v, remembered, qualityBtn));
                    playBtnWrap.addView(qualityBtn, new LinearLayout.LayoutParams(-1, -2));

                    if (remembered != null) {
                        String bmEpTitle = formatPlayEpisodeLabel(remembered.episode, remEpNum);
                        String bmSummary =
                                remembered.position > 0
                                        ? "已觀看 ["
                                                + bmEpTitle
                                                + "] 至 "
                                                + PlaybackController.formatTime(remembered.position)
                                                + " · 線路："
                                                + remembered.line
                                        : "已觀看 [" + bmEpTitle + "] · 線路：" + remembered.line;
                        TextView bmInfo = a.text(bmSummary, 12, 0xccffffff);
                        bmInfo.setSingleLine(true);
                        bmInfo.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                        bmInfo.setGravity(Gravity.CENTER);
                        bmInfo.setPadding(a.dp(8), a.dp(8), a.dp(8), 0);
                        playBtnWrap.addView(bmInfo, new LinearLayout.LayoutParams(-1, -2));
                    }

                    int initLine = SourcePreferences.lineIndex(a, v), initPage = 0;
                    if (remembered != null) {
                        for (int i = 0; i < v.lines.size(); i++) {
                            if (v.lines.get(i).name.equals(remembered.line)) {
                                initLine = i;
                                for (int n = 0; n < v.lines.get(i).episodes.size(); n++) {
                                    if (v.lines
                                            .get(i)
                                            .episodes
                                            .get(n)
                                            .name
                                            .equals(remembered.episode)) {
                                        boolean desc =
                                                a.prefs.getBoolean(
                                                        Prefs.EPISODES_DESCENDING, false);
                                        initPage =
                                                (desc ? v.lines.get(i).episodes.size() - 1 - n : n)
                                                        / 50;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }

                    LinearLayout episodePanel = a.vertical();
                    episodePanel.setTag("detail-episodes");
                    detailsBody.addView(episodePanel, new LinearLayout.LayoutParams(-1, -2));

                    final Map<Integer, Cms.Video> discoveredSeasons = new TreeMap<>();
                    final Map<Integer, String> discoveredLabels = new TreeMap<>();
                    discoveredSeasons.put(curSeason.seasonNumber, v);
                    discoveredLabels.put(curSeason.seasonNumber, curSeason.seasonLabel);

                    renderMobileEpisodes(
                            a,
                            s,
                            v,
                            initLine,
                            initPage,
                            episodePanel,
                            remembered,
                            discoveredSeasons,
                            discoveredLabels);
                    // mkoai 2.0.38 (Emby style): the visible "片源" card row
                    // is gone. Source picking lives in the subtle
                    // "片源：xxx · 點此切換" line under the Play button,
                    // which runs findAll on demand when tapped.
                    populateRelatedSeasons(
                            a,
                            s,
                            v,
                            episodePanel,
                            initLine,
                            initPage,
                            remembered,
                            discoveredSeasons,
                            discoveredLabels);

                    // mkoai: card tap = play the best one. Consume the one-shot flag
                    // (going back from the player re-renders this page, but the flag
                    // is already gone, so it never loops). Auto-play only when there
                    // is nothing to choose: a resume bookmark, or a single-episode
                    // title (movie). Multi-episode titles stay on this page with the
                    // best line already selected.
                    if (a.autoPlayOnDetail) {
                        a.autoPlayOnDetail = false;
                        int bestEpCount =
                                v.lines
                                        .get(SourcePreferences.lineIndex(a, v))
                                        .episodes
                                        .size();
                        if (remembered != null || bestEpCount == 1) {
                            a.toast(
                                    "已自動選擇最佳線路："
                                            + v.lines
                                                    .get(SourcePreferences.lineIndex(a, v))
                                                    .name);
                            playBtn.performClick();
                        }
                    }

                    // mkoai 2.0.38: TMDB enrichment (high-quality poster,
                    // overview, vote, cast) — background lookup, falls back
                    // to the source's own artwork/text when TMDB misses.
                    enrichDetailWithTmdb(a, s, v);
                };

        if (ready == null) {
            if (listing.id != null && listing.id.startsWith("msearch:")) {
                // mkoai 2.0.36: home card — no real source id yet. The
                // detail page is already open; start the source search
                // AT the tap instead of waiting for a detail fetch to
                // fail. The user never sees the search page.
                findSourceAndOpen(a, s, listing, loadingState, lowerBody, null, false);
            } else {
                a.loadDetail(
                        s,
                        listing.id,
                        present,
                        error -> {
                            findSourceAndOpen(a, s, listing, loadingState, lowerBody, error, true);
                        });
            }
        } else {
            try {
                present.accept(ready);
            } catch (Exception e) {
                AppLog.w("MobileDetailScreen", "present detail failed", e);
            }
        }
    }

    /**
     * mkoai 2.0.36: the detail page has no playable source — its own
     * detail failed to load ({@code autoPlay} true: the failed source is
     * excluded, and the replacement auto-plays on a resume bookmark or
     * single episode, matching the 2.0.3 behaviour), or the listing is a
     * home card with no real source id yet ({@code autoPlay} false: no
     * source is excluded and the replacement opens its detail page
     * only — home tap → detail page, never straight to the player).
     * The replacement REPLACES the current screen (history=false) so
     * Back returns to where the user came from, not to this dead page.
     */
    private static void findSourceAndOpen(
            MainActivity a,
            Cms.Site s,
            Cms.Video listing,
            TextView loadingState,
            LinearLayout lowerBody,
            Throwable error,
            boolean autoPlay) {
        loadingState.setText(
                autoPlay ? "這個片源連不上，正在自動尋找其他片源…" : "正在搜尋片源…");
        AutoSourceFallback.find(
                a,
                listing,
                autoPlay ? s.key : null,
                playable -> {
                    if (playable != null) {
                        a.toast(
                                (autoPlay ? "已自動切換片源：" : "已找到片源：")
                                        + playable.site.name);
                        if (autoPlay) a.autoPlayOnDetail = true;
                        a.open(
                                new MainActivity.Screen(
                                        () ->
                                                a.detail(
                                                        playable.site,
                                                        playable.listing,
                                                        playable.detail)),
                                false);
                        return;
                    }
                    loadingState.setText(error == null ? "沒有找到可播放的片源" : a.message(error));
                    android.view.ViewParent parent = loadingState.getParent();
                    LinearLayout target =
                            parent instanceof LinearLayout ? (LinearLayout) parent : lowerBody;
                    a.addButton(target, a.button("重試", "primary", a::refresh));
                });
    }

    private static void populateHeroPills(
            MainActivity a, LinearLayout pillsRow, Cms.Video v, int epCount) {
        pillsRow.removeAllViews();
        List<String> tags = new ArrayList<>();
        if (v.year != null && !v.year.trim().isEmpty()) tags.add(v.year.trim());
        if (v.area != null && !v.area.trim().isEmpty())
            tags.add(ChineseConvert.s2t(v.area.trim()));
        if (v.tag != null && !v.tag.trim().isEmpty())
            tags.add(ChineseConvert.s2t(v.tag.trim()));
        if (v.remarks != null && !v.remarks.trim().isEmpty()) {
            tags.add(ChineseConvert.s2t(v.remarks.trim()));
        } else if (epCount > 1) {
            tags.add("共" + epCount + "集");
        }
        int limit = Math.min(4, tags.size());
        for (int i = 0; i < limit; i++) {
            TextView pill = a.text(tags.get(i), 12, 0xf2ffffff);
            pill.setTypeface(null, Typeface.BOLD);
            pill.setSingleLine(true);
            pill.setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
            a.glass(pill, 999);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = a.dp(8);
            pillsRow.addView(pill, lp);
        }
    }

    /**
     * mkoai 2.0.34: the "播放線路" card row on the detail page (like the
     * reference app's line cards). The first card is 「✦ 自動選源」 — the
     * default: it clears PREFERRED_LINE and plays the auto best line
     * (SourcePreferences.lineIndex: most episodes, then quality markers).
     * Then one card per line (name + episode count + quality badge).
     * Tapping a card saves the choice and re-renders the episodes for
     * that line. Rebuilt on every renderMobileEpisodes call, so it always
     * reflects the current selection even when the line was changed from
     * the top-bar picker dialog.
     */
    static void refreshLineCards(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            LinearLayout episodePanel,
            WatchHistory.Entry remembered,
            Map<Integer, Cms.Video> discoveredSeasons,
            Map<Integer, String> discoveredLabels) {
        if (a == null || episodePanel == null || episodePanel.getParent() == null) return;
        ViewGroup parent = (ViewGroup) episodePanel.getParent();
        View old = parent.findViewWithTag("detail-lines");
        if (old != null) parent.removeView(old);
        if (video == null || video.lines == null || video.lines.isEmpty()) return;

        String saved = a.prefs.getString(Prefs.PREFERRED_LINE, "");
        boolean autoSelected = true;
        for (Cms.Line l : video.lines) {
            if (l != null && l.name != null && l.name.equals(saved)) {
                autoSelected = false;
                break;
            }
        }

        LinearLayout section = a.vertical();
        section.setTag("detail-lines");
        section.setPadding(0, a.dp(12), 0, 0);

        TextView header = a.text("播放線路", 13, MainActivity.MUTED);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(a.dp(2), 0, a.dp(2), a.dp(8));
        section.addView(header, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView scroller = new HorizontalScrollView(a);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = a.row();
        scroller.addView(row, new LinearLayout.LayoutParams(-2, -2));
        section.addView(scroller, new LinearLayout.LayoutParams(-1, -2));

        row.addView(
                lineCard(
                        a,
                        site,
                        video,
                        episodePanel,
                        remembered,
                        discoveredSeasons,
                        discoveredLabels,
                        -1,
                        "✦ 自動選源",
                        "自動挑最穩的線路",
                        autoSelected),
                lineCardLp(a, true));
        for (int i = 0; i < video.lines.size(); i++) {
            Cms.Line l = video.lines.get(i);
            if (l == null) continue;
            String sub = l.episodes.size() + "集" + qualityBadge(l.name);
            row.addView(
                    lineCard(
                            a,
                            site,
                            video,
                            episodePanel,
                            remembered,
                            discoveredSeasons,
                            discoveredLabels,
                            i,
                            l.name,
                            sub,
                            !autoSelected && saved.equals(l.name)),
                    lineCardLp(a, false));
        }

        int idx = parent.indexOfChild(episodePanel);
        parent.addView(section, idx < 0 ? parent.getChildCount() : idx);
    }

    private static LinearLayout.LayoutParams lineCardLp(MainActivity a, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = first ? 0 : a.dp(8);
        return lp;
    }

    /**
     * mkoai 2.0.36: the "片源" row — every source that has this title, so
     * the user can pick one himself instead of only seeing the current
     * source's lines. {@code playables == null} shows the searching
     * placeholder; the row is refreshed when findAll returns. It sits
     * above the lines row and survives line switches (renderMobileEpisodes
     * only touches the lines section and the episode panel).
     */
    static void refreshSourceCards(
            MainActivity a,
            Cms.Site site,
            Cms.Video listing,
            LinearLayout episodePanel,
            java.util.List<AutoSourceFallback.Playable> playables) {
        if (a == null || episodePanel == null || episodePanel.getParent() == null) return;
        ViewGroup parent = (ViewGroup) episodePanel.getParent();
        View old = parent.findViewWithTag("detail-sources");
        if (old != null) parent.removeView(old);

        LinearLayout section = a.vertical();
        section.setTag("detail-sources");
        section.setPadding(0, a.dp(12), 0, 0);
        TextView header = a.text("片源", 13, MainActivity.MUTED);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(a.dp(2), 0, a.dp(2), a.dp(8));
        section.addView(header, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView scroller = new HorizontalScrollView(a);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = a.row();
        scroller.addView(row, new LinearLayout.LayoutParams(-2, -2));
        section.addView(scroller, new LinearLayout.LayoutParams(-1, -2));

        if (playables == null) {
            TextView searching = a.text("正在搜尋片源…", 12, MainActivity.MUTED);
            searching.setPadding(a.dp(2), a.dp(4), a.dp(2), a.dp(4));
            row.addView(searching);
        } else if (playables.isEmpty()) {
            TextView none = a.text("沒有找到其他片源", 12, MainActivity.MUTED);
            none.setPadding(a.dp(2), a.dp(4), a.dp(2), a.dp(4));
            row.addView(none);
        } else {
            row.addView(
                    sourceCard(a, site, listing, null, "✦ 自動選源", "自動挑最穩的片源", false),
                    lineCardLp(a, true));
            for (AutoSourceFallback.Playable p : playables) {
                if (p == null || p.site == null) continue;
                int eps = 0;
                if (p.detail != null && p.detail.lines != null)
                    for (Cms.Line ln : p.detail.lines)
                        if (ln.episodes != null) eps += ln.episodes.size();
                String sub = eps + "集" + (p.site.key.equals(site.key) ? " · 目前" : "");
                row.addView(
                        sourceCard(
                                a,
                                site,
                                listing,
                                p,
                                p.site.name,
                                sub,
                                p.site.key.equals(site.key)),
                        lineCardLp(a, false));
            }
        }

        View lines = parent.findViewWithTag("detail-lines");
        int idx = lines != null ? parent.indexOfChild(lines) : parent.indexOfChild(episodePanel);
        parent.addView(section, idx < 0 ? parent.getChildCount() : idx);
    }

    private static View sourceCard(
            final MainActivity a,
            final Cms.Site site,
            final Cms.Video listing,
            final AutoSourceFallback.Playable playable, // null = auto-select
            final String name,
            final String sub,
            final boolean selected) {
        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.dp(13), a.dp(9), a.dp(13), a.dp(9));
        card.setBackground(a.bg(selected ? 0x33e34b58 : 0xff232b28, 12));
        TextView nameView = a.text(name, 13, selected ? 0xffe34b58 : MainActivity.WHITE);
        nameView.setTypeface(null, Typeface.BOLD);
        nameView.setSingleLine(true);
        card.addView(nameView);
        if (sub != null && !sub.isEmpty()) {
            TextView subView = a.text(sub, 11, MainActivity.MUTED);
            subView.setSingleLine(true);
            subView.setPadding(0, a.dp(2), 0, 0);
            card.addView(subView);
        }
        bindTouchFeedback(card);
        card.setOnClickListener(
                v -> {
                    if (playable == null) {
                        a.toast("正在自動選源…");
                        AutoSourceFallback.find(
                                a,
                                listing,
                                null,
                                winner -> {
                                    if (winner == null) {
                                        a.toast("沒有找到可播放的片源");
                                        return;
                                    }
                                    if (winner.site.key.equals(site.key)) {
                                        a.toast("已是最佳片源：" + winner.site.name);
                                        return;
                                    }
                                    a.autoPlayOnDetail = true;
                                    a.open(
                                            new MainActivity.Screen(
                                                    () ->
                                                            a.detail(
                                                                    winner.site,
                                                                    winner.listing,
                                                                    winner.detail)),
                                            false);
                                });
                        return;
                    }
                    if (playable.site.key.equals(site.key)) return;
                    a.autoPlayOnDetail = true;
                    a.open(
                            new MainActivity.Screen(
                                    () ->
                                            a.detail(
                                                    playable.site,
                                                    playable.listing,
                                                    playable.detail)),
                            false);
                });
        return card;
    }

    /** Quality badge derived from the line name (超清 / 藍光 / 4K / …). */
    private static String qualityBadge(String lineName) {
        if (lineName == null) return "";
        if (lineName.contains("4K") || lineName.contains("4k")) return " · 4K";
        if (lineName.contains("藍光") || lineName.contains("蓝光")) return " · 藍光";
        if (lineName.contains("超清")) return " · 超清";
        if (lineName.contains("1080")) return " · 1080P";
        if (lineName.contains("高清")) return " · 高清";
        return "";
    }

    private static View lineCard(
            final MainActivity a,
            final Cms.Site site,
            final Cms.Video video,
            final LinearLayout episodePanel,
            final WatchHistory.Entry remembered,
            final Map<Integer, Cms.Video> discoveredSeasons,
            final Map<Integer, String> discoveredLabels,
            final int lineIndex, // -1 = auto-select
            final String name,
            final String sub,
            final boolean selected) {
        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.dp(13), a.dp(9), a.dp(13), a.dp(9));
        card.setBackground(a.bg(selected ? 0x33e34b58 : 0xff232b28, 12));
        TextView nameView = a.text(name, 13, selected ? 0xffe34b58 : MainActivity.WHITE);
        nameView.setTypeface(null, Typeface.BOLD);
        nameView.setSingleLine(true);
        card.addView(nameView);
        if (sub != null && !sub.isEmpty()) {
            TextView subView = a.text(sub, 11, MainActivity.MUTED);
            subView.setSingleLine(true);
            subView.setPadding(0, a.dp(2), 0, 0);
            card.addView(subView);
        }
        bindTouchFeedback(card);
        card.setOnClickListener(
                v -> {
                    if (lineIndex < 0) {
                        a.prefs.edit().putString(Prefs.PREFERRED_LINE, "").apply();
                        renderMobileEpisodes(
                                a,
                                site,
                                video,
                                SourcePreferences.lineIndex(a, video),
                                0,
                                episodePanel,
                                remembered,
                                discoveredSeasons,
                                discoveredLabels);
                    } else {
                        a.prefs
                                .edit()
                                .putString(
                                        Prefs.PREFERRED_LINE,
                                        video.lines.get(lineIndex).name)
                                .apply();
                        renderMobileEpisodes(
                                a,
                                site,
                                video,
                                lineIndex,
                                0,
                                episodePanel,
                                remembered,
                                discoveredSeasons,
                                discoveredLabels);
                    }
                });
        return card;
    }

    // ---- mkoai 2.0.38 (items 21/22/23): detail-page quality picker ----

    /** Episode the Play button would play: bookmarked episode, else the auto line's first. */
    private static Cms.Episode detailPlayEpisode(
            MainActivity a, Cms.Video v, WatchHistory.Entry remembered) {
        if (v == null || v.lines == null || v.lines.isEmpty()) return null;
        if (remembered != null) {
            for (Cms.Line ln : v.lines) {
                if (ln == null || ln.episodes == null) continue;
                if (remembered.line != null && !remembered.line.equals(ln.name)) continue;
                for (Cms.Episode ep : ln.episodes) {
                    if (ep != null
                            && remembered.episode != null
                            && remembered.episode.equals(ep.name)) {
                        return ep;
                    }
                }
            }
        }
        Cms.Line line = v.lines.get(SourcePreferences.lineIndex(a, v));
        if (line == null || line.episodes == null || line.episodes.isEmpty()) return null;
        return line.episodes.get(0);
    }

    /** Label for the detail quality entry: manual pick, else the auto preference. */
    static String detailQualityLabel(MainActivity a, Cms.Site s, Cms.Video v, Cms.Episode ep) {
        int pref = a.prefs.getInt(Prefs.QUALITY_PREF, Prefs.QUALITY_HIGH);
        String auto =
                pref == Prefs.QUALITY_SMOOTH ? "自動（流暢優先）" : "自動（高畫質優先）";
        String key = HlsQualityProbe.manualKey(s == null ? "" : s.key, v == null ? "" : v.title);
        String sig = HlsQualityProbe.getManualSig(key);
        if (sig != null) {
            HlsQualityProbe.Result cached = null;
            if (ep != null && ep.url != null) {
                cached = HlsQualityProbe.cached(LocalProxy.rewrite(ep.url));
            }
            if (cached != null) {
                int idx = HlsQualityProbe.manualIndex(cached.variants, sig);
                if (idx >= 0) {
                    return "畫質：" + cached.variants.get(idx).label() + " · 點此切換";
                }
            }
            return "畫質：手動已選 · 點此切換";
        }
        return "畫質：" + auto + " · 點此切換";
    }

    private static void showDetailQualityMenu(
            MainActivity a,
            Cms.Site s,
            Cms.Video v,
            WatchHistory.Entry remembered,
            TextView qualityBtn) {
        Cms.Episode ep = detailPlayEpisode(a, v, remembered);
        if (ep == null || ep.url == null || ep.url.isEmpty()) {
            a.toast("此片暫無可探測的播放連結");
            return;
        }
        String url = LocalProxy.rewrite(ep.url);
        HlsQualityProbe.Result cached = HlsQualityProbe.cached(url);
        if (cached != null) {
            showDetailQualityChoices(a, s, v, ep, cached, qualityBtn);
            return;
        }
        if (!HlsQualityProbe.isProbableHls(url)) {
            // Parser-dependent source: the direct stream URL only exists after
            // resolution at playback time, so there is nothing to probe yet.
            a.toast("此片源需解析後才能探測畫質，開始播放後可於播放器切換畫質");
            return;
        }
        qualityBtn.setText("畫質：偵測中…");
        Map<String, String> headers =
                Cms.inheritedHeaders(
                        s == null ? null : s.api, url, s == null ? null : s.headers);
        Cms.mergeHeaders(headers, ep.headers);
        HlsQualityProbe.probeAsync(
                url,
                headers,
                result ->
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing()) return;
                                    if (result == null || result.variants.isEmpty()) {
                                        qualityBtn.setText(detailQualityLabel(a, s, v, ep));
                                        a.toast(
                                                result != null && result.singleVariant
                                                        ? "此串流只有單一畫質"
                                                        : "畫質探測失敗，將以原始畫質播放");
                                        return;
                                    }
                                    showDetailQualityChoices(a, s, v, ep, result, qualityBtn);
                                }));
    }

    private static void showDetailQualityChoices(
            MainActivity a,
            Cms.Site s,
            Cms.Video v,
            Cms.Episode ep,
            HlsQualityProbe.Result result,
            TextView qualityBtn) {
        if (result.variants.size() <= 1) {
            a.toast(
                    "此串流只有單一畫質"
                            + (result.variants.isEmpty()
                                    ? ""
                                    : "：" + result.variants.get(0).label()));
            return;
        }
        int pref = a.prefs.getInt(Prefs.QUALITY_PREF, Prefs.QUALITY_HIGH);
        String key = HlsQualityProbe.manualKey(s == null ? "" : s.key, v == null ? "" : v.title);
        int manualIdx =
                HlsQualityProbe.manualIndex(
                        result.variants, HlsQualityProbe.getManualSig(key));
        String[] names = new String[result.variants.size() + 1];
        names[0] =
                (manualIdx < 0 ? "✓ " : "")
                        + (pref == Prefs.QUALITY_SMOOTH ? "自動（流暢優先）" : "自動（高畫質優先）");
        for (int i = 0; i < result.variants.size(); i++) {
            names[i + 1] = (manualIdx == i ? "✓ " : "") + result.variants.get(i).label();
        }
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("畫質")
                .setItems(
                        names,
                        (d, index) -> {
                            if (index == 0) {
                                HlsQualityProbe.setManualSig(key, null);
                            } else {
                                HlsQualityProbe.setManualSig(
                                        key, result.variants.get(index - 1).sig());
                            }
                            PlaybackController.saveManualQuality(a);
                            qualityBtn.setText(detailQualityLabel(a, s, v, ep));
                        })
                .show();
    }

    /**
     * 2.0.43: every detail-page play press re-selects a playable source first
     * (AutoSourceFallback.find across ALL sources — failedSiteKey null — in
     * prioritized order, 巨量/xgzy first), then plays the SAME episode from the
     * fresh detail. The found Playable.detail is used as-is, never re-fetched;
     * the episode is matched by normalized name (WatchHistory.normEpisode, so
     * 第01集 matches 第1集 across sources), preferred line first. When nothing
     * can play — or the fresh source does not carry this episode — show the
     * 「無法播放」 dialog instead of opening an empty player or silently
     * playing a different episode.
     *
     * 2.0.55: the re-select no longer blocks the tap. The player opens
     * IMMEDIATELY with the already-shown source (the detail page's
     * current site/detail — its preferred line is the line the user
     * sees, e.g. 巨量 jlm3u8) as the first candidate; a stale or dead
     * first candidate falls back through the player's own line/source
     * hop (PlaybackController). A fresh cross-source check only runs
     * when the shown detail cannot play this episode at all — and the
     * 「無法播放」 dialog only appears after that check genuinely
     * completes with zero available sources, never while it is still
     * running. This is the tap-responsiveness rule: a tap never waits
     * for background work.
     *
     * @param site the detail page's current site.
     * @param current the detail page's currently shown detail (has
     *     lines); also the title source for the fresh check.
     * @param remembered resume bookmark, or null for a fresh episode tap; its
     *     episode name is the wanted episode and resume() is the start offset.
     * @param wantedEpisode episode name to play when remembered is null.
     */
    private static void playWithFreshSource(
            MainActivity a,
            Cms.Site site,
            Cms.Video current,
            WatchHistory.Entry remembered,
            String wantedEpisode) {
        String title = Cms.searchTitle(current);
        String wantName =
                wantedEpisode != null
                        ? wantedEpisode
                        : (remembered != null ? remembered.episode : null);
        // mkoai 2.0.48: 「自動選源直接播放」 OFF -> show the source
        // picker first. Picking ✦ 自動選擇最佳片源 runs the auto path
        // below; picking a source plays the same episode from it.
        // ON (default) -> the auto path directly, never a picker.
        if (!AutoSourceFallback.autoDirectPlay(a)) {
            final String wantFinal = wantName;
            final int resumeAt = remembered != null ? remembered.resume() : 0;
            AutoSourceFallback.pickSource(
                    a,
                    current,
                    null,
                    playable ->
                            AutoSourceFallback.playPlayableEpisode(
                                    a,
                                    playable,
                                    wantFinal,
                                    remembered,
                                    resumeAt,
                                    () -> showUnplayable(a, title)),
                    () -> playWithFreshSourceAuto(a, site, current, remembered, wantedEpisode));
            return;
        }
        playWithFreshSourceAuto(a, site, current, remembered, wantedEpisode);
    }

    private static void playWithFreshSourceAuto(
            MainActivity a,
            Cms.Site site,
            Cms.Video current,
            WatchHistory.Entry remembered,
            String wantedEpisode) {
        String title = Cms.searchTitle(current);
        String wantName =
                wantedEpisode != null
                        ? wantedEpisode
                        : (remembered != null ? remembered.episode : null);
        // 2.0.55: the already-shown source is the first candidate — open
        // the player NOW instead of waiting for a fresh cross-source
        // re-check (which can take seconds and used to swallow the tap,
        // so the user had to press play several times). The player's own
        // line/source hop handles a dead first candidate, so no check
        // runs here at all.
        if (current != null
                && current.lines != null
                && !current.lines.isEmpty()
                && AutoSourceFallback.detailHasEpisode(current, wantName)) {
            AutoSourceFallback.Playable first =
                    new AutoSourceFallback.Playable(site, current, current);
            AutoSourceFallback.playPlayableEpisode(
                    a,
                    first,
                    wantName,
                    remembered,
                    remembered != null ? remembered.resume() : 0,
                    () -> startFreshSourceCheck(a, current, remembered, wantedEpisode));
            return;
        }
        // The shown detail cannot play this episode at all — the fresh
        // check is the only path. Show "checking" while it runs; the
        // 「無法播放」 dialog only fires after it genuinely completes.
        startFreshSourceCheck(a, current, remembered, wantedEpisode);
    }

    /**
     * 2.0.55: fresh cross-source check for a play press, used only when the
     * shown detail cannot play the wanted episode. Shows 「正在檢查片源…」
     * while the check runs; the 「無法播放」 dialog only appears after the
     * check genuinely completes with zero available sources — never while
     * it is still running (a premature dialog was the 2.0.55 issue 1).
     */
    private static void startFreshSourceCheck(
            MainActivity a,
            Cms.Video current,
            WatchHistory.Entry remembered,
            String wantedEpisode) {
        String title = Cms.searchTitle(current);
        String wantName =
                wantedEpisode != null
                        ? wantedEpisode
                        : (remembered != null ? remembered.episode : null);
        a.toast("正在檢查片源…");
        AutoSourceFallback.find(
                a,
                current,
                null,
                wantName,
                playable -> {
                    if (playable == null
                            || playable.detail == null
                            || playable.detail.lines.isEmpty()) {
                        showUnplayable(a, title);
                        return;
                    }
                    AutoSourceFallback.playPlayableEpisode(
                            a,
                            playable,
                            wantName,
                            remembered,
                            remembered != null ? remembered.resume() : 0,
                            () -> showUnplayable(a, title));
                });
    }

    private static void showUnplayable(MainActivity a, String title) {
        new AppDialog.Builder(a)
                .setTitle("無法播放")
                .setMessage("《" + (title == null ? "" : title) + "》目前沒有可用的片源。")
                .setPositiveButton("知道了", null)
                .show();
    }

    static void renderMobileEpisodes(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            int lineIndex,
            int page,
            LinearLayout panel,
            WatchHistory.Entry remembered,
            Map<Integer, Cms.Video> discoveredSeasons,
            Map<Integer, String> discoveredLabels) {
        // mkoai 2.0.38 (Emby style): the visible "播放線路" card row is
        // gone. Line picking lives in the subtle "線路：xxx · 點此切換"
        // line under the Play button (kept in sync below).
        if (a.current != null) {
            a.current.selection.select(
                    video.lines.get(lineIndex).name,
                    page,
                    video.lines.get(lineIndex).episodes.size());
        }
        panel.removeAllViews();

        final Cms.Line line = video.lines.get(lineIndex);
        final boolean descending = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
        final SeasonMeta curSeason = parseSeasonMeta(video.title);
        final int total = line.episodes.size();

        int activeEpIdx = 0;
        if (remembered != null && line.name.equals(remembered.line)) {
            for (int i = 0; i < total; i++) {
                if (line.episodes.get(i).name.equals(remembered.episode)) {
                    activeEpIdx = i;
                    break;
                }
            }
        }

        // Update Top-Right Floating Source Button, Play Button & Download Button
        if (a.root != null) {
            View playBtnView = a.root.findViewWithTag("detail-play");
            if (playBtnView instanceof Button && !line.episodes.isEmpty()) {
                Button playBtn = (Button) playBtnView;
                final int targetEpIdx = activeEpIdx;
                playBtn.setText(
                        "▶   播放 · "
                                + formatPlayEpisodeLabel(
                                        line.episodes.get(targetEpIdx).name, targetEpIdx + 1));
                playBtn.setOnClickListener(
                        v -> {
                            // 2.0.55: play the shown source immediately (the
                            // player opens now, with the current site/detail
                            // as first candidate — no fresh check blocks it).
                            if (remembered != null && line.name.equals(remembered.line)) {
                                playWithFreshSource(a, site, video, remembered, null);
                            } else {
                                playWithFreshSource(
                                        a, site, video, null, line.episodes.get(targetEpIdx).name);
                            }
                        });
            }
            // mkoai 2.0.38 (Emby style): keep the subtle "線路" line under
            // the Play button in sync with the current line. Tapping it
            // opens the line picker dialog (moved here from the removed
            // top-right source capsule).
            View linePickerView = a.root.findViewWithTag("detail-line-picker");
            if (linePickerView instanceof TextView) {
                TextView lineBtn = (TextView) linePickerView;
                lineBtn.setText("線路：" + line.name + " · 點此切換");
                lineBtn.setOnClickListener(
                        v -> {
                            String[] names = new String[video.lines.size()];
                            for (int i = 0; i < video.lines.size(); i++) {
                                Cms.Line l = video.lines.get(i);
                                names[i] =
                                        l.name
                                                + "  (共 "
                                                + l.episodes.size()
                                                + " 集 · "
                                                + site.name
                                                + ")";
                            }
                            new AppDialog.Builder(a)
                                    .wrapChoices()
                                    .setTitle("切換播放線路 (" + video.lines.size() + ")")
                                    .setSingleChoiceItems(
                                            names,
                                            lineIndex,
                                            (dialog, which) -> {
                                                dialog.dismiss();
                                                a.prefs
                                                        .edit()
                                                        .putString(
                                                                Prefs.PREFERRED_LINE,
                                                                video.lines.get(which).name)
                                                        .apply();
                                                renderMobileEpisodes(
                                                        a,
                                                        site,
                                                        video,
                                                        which,
                                                        0,
                                                        panel,
                                                        remembered,
                                                        discoveredSeasons,
                                                        discoveredLabels);
                                            })
                                    .setNeutralButton(
                                            "🔍 跨片源搜尋",
                                            (dialog, which) ->
                                                    a.crossSearch(Cms.searchTitle(video)))
                                    .setNegativeButton("取消", null)
                                    .show();
                        });
            }
            View topDownView = a.root.findViewWithTag("episodes-download");
            if (topDownView != null) {
                String pickFrom = "";
                if (remembered != null && line.name.equals(remembered.line)) {
                    pickFrom = remembered.episode;
                }
                final String currentName = pickFrom;
                topDownView.setOnClickListener(
                        v -> MobileDownloads.pick(a, site, video, line, currentName));
            }
        }

        // 1. Header Row:
        //    Left: Independent Season Selector Button (`第 X 季 ⌄`)
        //    Right: `[ ↑ 正序 ]` + `[ ⊞ 全部剧集 (N) ]`
        LinearLayout headerRow = a.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout seasonBtn = a.row();
        seasonBtn.setTag("detail-season-picker");
        seasonBtn.setGravity(Gravity.CENTER_VERTICAL);
        bindTouchFeedback(seasonBtn);

        TextView seasonTitle = a.text(curSeason.seasonLabel, 18, Color.WHITE);
        seasonTitle.setTypeface(null, Typeface.BOLD);
        seasonBtn.addView(seasonTitle);

        if (discoveredSeasons.size() > 1) {
            TextView countBadge = a.text("共" + discoveredSeasons.size() + "季", 10.5f, 0xf2ffffff);
            countBadge.setTypeface(null, Typeface.BOLD);
            countBadge.setPadding(a.dp(7), a.dp(2), a.dp(7), a.dp(2));
            countBadge.setBackground(a.bg(0x2effffff, 999));
            LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(-2, -2);
            cbLp.leftMargin = a.dp(6);
            seasonBtn.addView(countBadge, cbLp);
        }

        TextView seasonChevron = a.text(" ⌄", 16, 0xccffffff);
        seasonChevron.setTypeface(null, Typeface.BOLD);
        seasonBtn.addView(seasonChevron);

        seasonBtn.setOnClickListener(
                v ->
                        showSeasonPickerDialog(
                                a, site, video, curSeason, discoveredSeasons, discoveredLabels));
        headerRow.addView(seasonBtn, new LinearLayout.LayoutParams(0, -2, 1f));

        // Sort Pill (`↑ 正序` / `↓ 倒序`)
        TextView sortBtn = a.text(descending ? "↓ 倒序" : "↑ 正序", 12, 0xe6ffffff);
        sortBtn.setTag("episodes-order");
        sortBtn.setTypeface(null, Typeface.BOLD);
        sortBtn.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
        sortBtn.setBackground(glassBg(a, 0x24ffffff, 0x30ffffff, 999));
        bindTouchFeedback(sortBtn);
        sortBtn.setOnClickListener(
                v -> {
                    a.prefs.edit().putBoolean(Prefs.EPISODES_DESCENDING, !descending).apply();
                    renderMobileEpisodes(
                            a,
                            site,
                            video,
                            lineIndex,
                            0,
                            panel,
                            remembered,
                            discoveredSeasons,
                            discoveredLabels);
                });
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(-2, -2);
        sortLp.rightMargin = a.dp(8);
        headerRow.addView(sortBtn, sortLp);

        // All Episodes Grid Pill (`⊞ 全部剧集 (N)`)
        TextView allGridBtn = a.text("⊞ 全部劇集 (" + total + ")", 12, 0xe6ffffff);
        allGridBtn.setTag("episodes-all-grid");
        allGridBtn.setTypeface(null, Typeface.BOLD);
        allGridBtn.setPadding(a.dp(11), a.dp(5), a.dp(11), a.dp(5));
        allGridBtn.setBackground(glassBg(a, 0x24ffffff, 0x30ffffff, 999));
        bindTouchFeedback(allGridBtn);
        allGridBtn.setOnClickListener(
                v -> showAllEpisodesDialog(a, site, video, line, descending, remembered));
        headerRow.addView(allGridBtn);

        // 1b. Direct Multi-Season Selector Strip (visible whenever >= 2 seasons exist)
        LinearLayout seasonStripWrap = a.vertical();
        seasonStripWrap.setTag("detail-seasons");
        panel.addView(seasonStripWrap, new LinearLayout.LayoutParams(-1, -2));
        if (discoveredSeasons.size() > 1) {
            renderSeasonStrip(
                    a,
                    site,
                    video,
                    curSeason,
                    seasonStripWrap,
                    discoveredSeasons,
                    discoveredLabels);
        }

        int start = page * 50;
        int end = Math.min(start + 50, total);

        // 2. Quick Episode Number Strip (`1` `2` `3` `4` ... white circle when active)
        a.gap(panel, 12);
        HorizontalScrollView numScroll = new HorizontalScrollView(a);
        numScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout numRow = a.row();
        numScroll.addView(numRow);
        panel.addView(numScroll, new LinearLayout.LayoutParams(-1, -2));

        for (int i = start; i < end; i++) {
            final int posIdx = descending ? (total - 1 - i) : i;
            final Cms.Episode ep = line.episodes.get(posIdx);
            boolean isCurrent = posIdx == activeEpIdx;

            TextView numPill =
                    a.text(String.valueOf(posIdx + 1), 12.5f, isCurrent ? 0xff121215 : 0xd9ffffff);
            numPill.setTag("episode:" + line.name + ":" + posIdx);
            numPill.setTypeface(null, Typeface.BOLD);
            numPill.setGravity(Gravity.CENTER);
            numPill.setMinWidth(a.dp(32));
            numPill.setMinimumWidth(a.dp(32));
            numPill.setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5));
            numPill.setBackground(
                    isCurrent ? a.bg(Color.WHITE, 999) : glassBg(a, 0x24ffffff, 0x28ffffff, 999));
            bindTouchFeedback(numPill);
            numPill.setOnClickListener(
                    v -> playWithFreshSource(a, site, video, null, ep.name));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(30));
            lp.rightMargin = a.dp(7);
            numRow.addView(numPill, lp);
        }

        // 3. Horizontal 16:9 Landscape Episode Preview Cards (matches iPhone `detail_screen.dart`)
        a.gap(panel, 14);
        HorizontalScrollView cardScroll = new HorizontalScrollView(a);
        cardScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cardRow = a.row();
        cardScroll.addView(cardRow);
        panel.addView(cardScroll, new LinearLayout.LayoutParams(-1, -2));

        for (int i = start; i < end; i++) {
            final int posIdx = descending ? (total - 1 - i) : i;
            final Cms.Episode ep = line.episodes.get(posIdx);
            boolean isCurrent = posIdx == activeEpIdx;
            EpisodeDisplay disp = parseEpisodeDisplay(ep.name, posIdx + 1);
            String eBadgeText = "▶ E" + String.format(Locale.US, "%02d", posIdx + 1);

            LinearLayout cardWrap = a.vertical();
            cardWrap.setTag("ep-card:" + line.name + ":" + posIdx);
            bindTouchFeedback(cardWrap);
            cardWrap.setOnClickListener(
                    v -> playWithFreshSource(a, site, video, null, ep.name));
            cardWrap.setOnLongClickListener(
                    v -> {
                        List<Cms.Episode> one = Collections.singletonList(ep);
                        MobileDownloads.enqueue(a, site, video, line, one);
                        return true;
                    });

            FrameLayout frame = new FrameLayout(a);
            GradientDrawable frameBg = new GradientDrawable();
            frameBg.setColor(0xff141d18);
            frameBg.setCornerRadius(a.dp(14));
            frame.setBackground(frameBg);
            frame.setClipToOutline(true);

            EpisodeStillCropView stillImg = new EpisodeStillCropView(a, posIdx);
            frame.addView(stillImg, new FrameLayout.LayoutParams(-1, -1));
            View bdView = a.root != null ? a.root.findViewWithTag("detail-backdrop") : null;
            Bitmap currentBackdropBmp =
                    bdView instanceof PortraitBackdropView
                            ? ((PortraitBackdropView) bdView).getPosterBitmap()
                            : null;
            if (currentBackdropBmp != null && !currentBackdropBmp.isRecycled()) {
                stillImg.setImageBitmap(currentBackdropBmp);
            } else if (video.poster != null && !video.poster.isEmpty()) {
                a.poster(video.poster, stillImg, true);
            }

            // Subtle dark bottom gradient inside the 16:9 card
            View cardScrim = new View(a);
            GradientDrawable scrimGd =
                    new GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[] {0x18000000, 0x88000000});
            scrimGd.setCornerRadius(a.dp(14));
            cardScrim.setBackground(scrimGd);
            frame.addView(cardScrim, new FrameLayout.LayoutParams(-1, -1));

            // Top-right spec badge (e.g. `5.17GB` / `4K`)
            if (disp.specBadge != null && !disp.specBadge.isEmpty()) {
                TextView specTv = a.text(disp.specBadge, 10, Color.WHITE);
                specTv.setTypeface(null, Typeface.BOLD);
                specTv.setPadding(a.dp(7), a.dp(2), a.dp(7), a.dp(2));
                specTv.setBackground(glassBg(a, 0xb3121215, 0x40ffffff, 6));
                FrameLayout.LayoutParams specLp =
                        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
                specLp.setMargins(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
                frame.addView(specTv, specLp);
            }

            // Bottom overlay row (`▶ E01` pill on left, `line.name` on right)
            LinearLayout bottomBar = a.row();
            bottomBar.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams bottomLp =
                    new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            bottomLp.setMargins(a.dp(9), a.dp(8), a.dp(9), a.dp(8));
            frame.addView(bottomBar, bottomLp);

            TextView ePill = a.text(eBadgeText, 10.5f, isCurrent ? 0xff121215 : Color.WHITE);
            ePill.setTypeface(null, Typeface.BOLD);
            ePill.setPadding(a.dp(7), a.dp(2.5f), a.dp(7), a.dp(2.5f));
            ePill.setBackground(a.bg(isCurrent ? Color.WHITE : 0xa6121215, 6));
            bottomBar.addView(ePill);

            TextView lineLabel = a.text(line.name, 10.5f, 0xd9ffffff);
            lineLabel.setTypeface(null, Typeface.BOLD);
            lineLabel.setGravity(Gravity.RIGHT);
            lineLabel.setSingleLine(true);
            lineLabel.setEllipsize(TextUtils.TruncateAt.END);
            bottomBar.addView(lineLabel, new LinearLayout.LayoutParams(0, -2, 1f));

            // Foreground rounded border overlay (2dp white when current, 0.8dp glass otherwise)
            View borderOverlay = new View(a);
            GradientDrawable borderGd = new GradientDrawable();
            borderGd.setColor(Color.TRANSPARENT);
            borderGd.setCornerRadius(a.dp(14));
            borderGd.setStroke(a.dp(isCurrent ? 2f : 0.8f), isCurrent ? Color.WHITE : 0x33ffffff);
            borderOverlay.setBackground(borderGd);
            frame.addView(borderOverlay, new FrameLayout.LayoutParams(-1, -1));

            cardWrap.addView(frame, new LinearLayout.LayoutParams(a.dp(224), a.dp(126)));

            // Below card: meta + headline
            String yearPrefix =
                    (video.year != null && !video.year.trim().isEmpty())
                            ? video.year.trim() + " · "
                            : "";
            String subMeta =
                    yearPrefix
                            + "第"
                            + (posIdx + 1)
                            + "集"
                            + (disp.specBadge != null ? " · " + disp.specBadge : "");
            TextView subMetaTv = a.text(subMeta, 11, 0x99ffffff);
            subMetaTv.setSingleLine(true);
            subMetaTv.setEllipsize(TextUtils.TruncateAt.END);
            subMetaTv.setPadding(a.dp(2), a.dp(8), a.dp(2), 0);
            cardWrap.addView(subMetaTv);

            String headline =
                    disp.cleanTitle.startsWith("第")
                            ? ChineseConvert.s2t(disp.cleanTitle)
                            : "第 "
                                    + (posIdx + 1)
                                    + " 集 · "
                                    + ChineseConvert.s2t(disp.cleanTitle);
            TextView headlineTv = a.text(headline, 13.5f, Color.WHITE);
            headlineTv.setTypeface(null, Typeface.BOLD);
            headlineTv.setSingleLine(true);
            headlineTv.setEllipsize(TextUtils.TruncateAt.END);
            headlineTv.setPadding(a.dp(2), a.dp(2), a.dp(2), 0);
            cardWrap.addView(headlineTv);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(a.dp(224), -2);
            cardLp.rightMargin = a.dp(14);
            cardRow.addView(cardWrap, cardLp);
        }

        // 4. Range Capsules (`1 · 50`, `51 · 100` ...) when > 50 episodes
        int groupCount = (total + 49) / 50;
        if (groupCount > 1) {
            a.gap(panel, 12);
            HorizontalScrollView groupScroll = new HorizontalScrollView(a);
            groupScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout groupRow = a.row();
            groupScroll.addView(groupRow);
            for (int g = 0; g < groupCount; g++) {
                final int targetGroup = g;
                boolean selectedGroup = g == page;
                int gStart = g * 50 + 1;
                int gEnd = Math.min(total, (g + 1) * 50);
                String label =
                        descending
                                ? (total - g * 50) + " · " + Math.max(1, total - gEnd + 1)
                                : gStart + " · " + gEnd;
                TextView tab = a.text(label, 12.5f, selectedGroup ? 0xff121215 : 0xd9ffffff);
                tab.setTypeface(null, Typeface.BOLD);
                tab.setPadding(a.dp(15), a.dp(6), a.dp(15), a.dp(6));
                tab.setBackground(a.bg(selectedGroup ? Color.WHITE : 0x24ffffff, 999));
                bindTouchFeedback(tab);
                tab.setOnClickListener(
                        v ->
                                renderMobileEpisodes(
                                        a,
                                        site,
                                        video,
                                        lineIndex,
                                        targetGroup,
                                        panel,
                                        remembered,
                                        discoveredSeasons,
                                        discoveredLabels));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = a.dp(8);
                groupRow.addView(tab, lp);
            }
            panel.addView(groupScroll, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private static void showSeasonPickerDialog(
            MainActivity a,
            Cms.Site site,
            Cms.Video currentVideo,
            SeasonMeta curSeason,
            Map<Integer, Cms.Video> discoveredSeasons,
            Map<Integer, String> discoveredLabels) {
        final List<Integer> seasonNums = new ArrayList<>(discoveredSeasons.keySet());
        String[] items = new String[seasonNums.size()];
        int checkedIdx = 0;
        for (int i = 0; i < seasonNums.size(); i++) {
            int sNum = seasonNums.get(i);
            Cms.Video vid = discoveredSeasons.get(sNum);
            String sLabel = discoveredLabels.get(sNum);
            String rem =
                    (vid != null && vid.remarks != null && !vid.remarks.trim().isEmpty())
                            ? " · " + vid.remarks.trim()
                            : "";
            items[i] = sLabel + "  (" + (vid != null ? vid.title : currentVideo.title) + rem + ")";
            if ((vid != null && Objects.equals(vid.id, currentVideo.id))
                    || sNum == curSeason.seasonNumber) {
                checkedIdx = i;
            }
        }
        String title = seasonNums.size() > 1 ? "選擇季數（共 " + seasonNums.size() + " 季）" : "選擇季數";
        MobileShell.showDrawerPicker(
                a,
                title,
                items,
                checkedIdx,
                which -> {
                    if (which < 0 || which >= seasonNums.size()) return;
                    int sNum = seasonNums.get(which);
                    Cms.Video target = discoveredSeasons.get(sNum);
                    if (target != null && !Objects.equals(target.id, currentVideo.id)) {
                        show(a, site, target, null);
                    }
                });
    }

    private static void showAllEpisodesDialog(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            Cms.Line line,
            boolean descending,
            WatchHistory.Entry remembered) {
        int total = line.episodes.size();
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().setTag("app-dialog");
        }

        int dialogWidth = Math.min(a.windowWidth() - a.dp(28), a.dp(420));
        LinearLayout sheet = a.vertical();
        sheet.setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(18));
        GradientDrawable cardBg =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0xf41c2421, 0xf8111715});
        cardBg.setCornerRadius(a.dp(28));
        cardBg.setStroke(Math.max(1, a.dp(1)), 0x36ffffff);
        sheet.setBackground(cardBg);

        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleTv = a.text("全部劇集 · " + line.name + " (" + total + ")", 17, Color.WHITE);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView closeBtn = a.text("✕", 14, 0xd9ffffff);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackground(glassBg(a, 0x24ffffff, 0x2effffff, 999));
        bindTouchFeedback(closeBtn);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(closeBtn, new LinearLayout.LayoutParams(a.dp(30), a.dp(30)));
        sheet.addView(header, new LinearLayout.LayoutParams(-1, -2));
        a.gap(sheet, 14);

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout grid = a.vertical();
        grid.setPadding(0, a.dp(2), 0, a.dp(6));
        sv.addView(grid, new ScrollView.LayoutParams(-1, -2));
        int maxSheetBodyH =
                Math.min(
                        a.dp(460),
                        (int) (a.getResources().getDisplayMetrics().heightPixels * 0.60f));
        int rows = (total + 2) / 3;
        int estimatedH = rows * a.dp(50) + a.dp(12);
        sheet.addView(
                sv,
                new LinearLayout.LayoutParams(-1, estimatedH > maxSheetBodyH ? maxSheetBodyH : -2));

        int cols = 3;
        LinearLayout currentRow = null;
        for (int i = 0; i < total; i++) {
            if (i % cols == 0) {
                currentRow = a.row();
                grid.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
            }
            final int posIdx = descending ? (total - 1 - i) : i;
            final Cms.Episode ep = line.episodes.get(posIdx);
            boolean isCurrent =
                    remembered != null
                            && line.name.equals(remembered.line)
                            && ep.name.equals(remembered.episode);
            EpisodeDisplay disp = parseEpisodeDisplay(ep.name, posIdx + 1);
            TextView cell =
                    a.text(
                            ChineseConvert.s2t(disp.cleanTitle),
                            13,
                            isCurrent ? 0xff111114 : Color.WHITE);
            cell.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            cell.setGravity(Gravity.CENTER);
            cell.setSingleLine(true);
            cell.setEllipsize(TextUtils.TruncateAt.END);
            cell.setPadding(a.dp(8), a.dp(10), a.dp(8), a.dp(10));
            cell.setBackground(
                    isCurrent ? a.bg(Color.WHITE, 12) : glassBg(a, 0x26ffffff, 0x24ffffff, 12));
            bindTouchFeedback(cell);
            cell.setOnClickListener(
                    v -> {
                        dialog.dismiss();
                        playWithFreshSource(a, site, video, null, ep.name);
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
            lp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            currentRow.addView(cell, lp);
        }
        if (currentRow != null && total % cols != 0) {
            for (int pad = total % cols; pad < cols; pad++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
                currentRow.addView(new View(a), lp);
            }
        }

        dialog.setContentView(sheet);
        Window win = dialog.getWindow();
        if (win != null) {
            win.getDecorView().setTag("app-dialog");
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams wlp = win.getAttributes();
            wlp.width = dialogWidth;
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            wlp.gravity = Gravity.CENTER;
            wlp.dimAmount = 0.62f;
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            win.setAttributes(wlp);
        }
        dialog.show();
        if (!a.reduced) {
            sheet.setScaleX(0.95f);
            sheet.setScaleY(0.95f);
            sheet.setAlpha(0f);
            sheet.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start();
        }
    }

    private static void renderSeasonStrip(
            MainActivity a,
            Cms.Site site,
            Cms.Video currentVideo,
            SeasonMeta curMeta,
            LinearLayout seasonStripWrap,
            Map<Integer, Cms.Video> bySeason,
            Map<Integer, String> labels) {
        seasonStripWrap.removeAllViews();
        a.gap(seasonStripWrap, 11);
        HorizontalScrollView hsv = new HorizontalScrollView(a);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = a.row();
        hsv.addView(row);
        seasonStripWrap.addView(hsv, new LinearLayout.LayoutParams(-1, -2));

        for (final Integer sNum : bySeason.keySet()) {
            final Cms.Video targetVid = bySeason.get(sNum);
            String sLabel = labels.get(sNum);
            boolean active =
                    (targetVid != null && Objects.equals(targetVid.id, currentVideo.id))
                            || sNum == curMeta.seasonNumber;
            TextView pill = a.text(sLabel, 12.5f, active ? 0xff121215 : 0xe6ffffff);
            pill.setTag("detail-season:" + sNum);
            pill.setTypeface(null, Typeface.BOLD);
            pill.setPadding(a.dp(14), a.dp(5), a.dp(14), a.dp(5));
            pill.setBackground(
                    active ? a.bg(Color.WHITE, 999) : glassBg(a, 0x24ffffff, 0x30ffffff, 999));
            bindTouchFeedback(pill);
            pill.setOnClickListener(
                    v -> {
                        if (targetVid != null && !Objects.equals(targetVid.id, currentVideo.id)) {
                            show(a, site, targetVid, null);
                        }
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(32));
            lp.rightMargin = a.dp(8);
            row.addView(pill, lp);
        }
    }

    static final class SeasonMeta {
        final String baseTitle;
        final int seasonNumber;
        final String seasonLabel;
        final boolean explicit;

        SeasonMeta(String baseTitle, int seasonNumber, String seasonLabel, boolean explicit) {
            this.baseTitle = baseTitle;
            this.seasonNumber = seasonNumber;
            this.seasonLabel = seasonLabel;
            this.explicit = explicit;
        }
    }

    static int parseChineseNumber(String raw) {
        if (raw == null) return 1;
        String s = raw.trim();
        if (s.isEmpty()) return 1;
        try {
            int d = Integer.parseInt(s);
            if (d > 0) return d;
        } catch (NumberFormatException ignored) {
        }
        String digits = "零一二三四五六七八九";
        if ("十".equals(s) || "拾".equals(s)) return 10;
        if (s.contains("十") || s.contains("拾")) {
            String[] parts = s.split("[十拾]", -1);
            int tens = parts[0].isEmpty() ? 1 : Math.max(1, digits.indexOf(parts[0].charAt(0)));
            int ones =
                    (parts.length > 1 && !parts[1].isEmpty())
                            ? Math.max(0, digits.indexOf(parts[1].charAt(0)))
                            : 0;
            int val = tens * 10 + ones;
            return val > 0 ? val : 1;
        }
        int acc = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = digits.indexOf(c);
            if (c == '两') idx = 2;
            if (idx >= 0) acc = acc * 10 + idx;
        }
        return acc > 0 ? acc : 1;
    }

    static SeasonMeta parseSeasonMeta(String rawTitle) {
        String t = rawTitle == null ? "" : rawTitle.trim();
        t =
                t.replaceAll(
                                "(?i)\\s*[\\[【(（](?:4K|2160P|1080P|720P|HD|BD|国语|粤语|中字|双语|完结|全集)[^\\]】)）]*[\\]】)）]\\s*$",
                                "")
                        .trim();
        if (t.isEmpty() && rawTitle != null) t = rawTitle.trim();

        Matcher m1 =
                Pattern.compile(
                                "^(.+?)\\s*(?:[（(\\[【·:：\\-—]\\s*)?第\\s*([一二三四五六七八九十百零两\\d]+)\\s*([季部辑篇])(?:\\s*[)）\\]】])?\\s*$")
                        .matcher(t);
        if (m1.matches()) {
            String base = m1.group(1).trim();
            int num = parseChineseNumber(m1.group(2));
            String unit = m1.group(3);
            if (!base.isEmpty()) {
                return new SeasonMeta(base, num, "第 " + num + " " + unit, true);
            }
        }

        Matcher m2 =
                Pattern.compile(
                                "(?i)^(.+?)\\s*(?:[（(\\[【·:：\\-—]\\s*)?(?:Season\\s*0*(\\d{1,2})|S0*(\\d{1,2}))(?:\\s*[)）\\]】])?\\s*$")
                        .matcher(t);
        if (m2.matches()) {
            String base = m2.group(1).trim();
            String numStr = m2.group(2) != null ? m2.group(2) : m2.group(3);
            int num = parseChineseNumber(numStr);
            if (!base.isEmpty()) {
                return new SeasonMeta(base, num, "第 " + num + " 季", true);
            }
        }

        Matcher m3 =
                Pattern.compile(
                                "^([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9·\\s]{1,20}?)\\s*(?:[（(\\[【·\\-]\\s*)?(20[123]\\d)\\s*[季期](?:\\s*[)）\\]】])?\\s*$")
                        .matcher(t);
        if (m3.matches()) {
            String base = m3.group(1).trim();
            int yr = parseChineseNumber(m3.group(2));
            if (!base.isEmpty()) {
                return new SeasonMeta(base, yr, yr + " 季", true);
            }
        }

        return new SeasonMeta(t, 1, "第 1 季", false);
    }

    /**
     * mkoai 2.0.38 (Emby style): the single centered meta line under the
     * title — "★ 7.2 | 2026 | Youku | 剧情" (score | year | source | genre).
     * The score prefers the resolved Douban score and falls back to a score
     * parsed out of the listing's remarks/description, same as
     * updateRatingBadges. Hidden when nothing can be shown.
     */
    /**
     * mkoai 2.0.38: TMDB enrichment for the detail page. Looks the title
     * up on TMDB (zh-TW, Chinese poster preferred) on a background thread
     * and swaps in the high-quality poster, overview, vote and cast. Every
     * step falls back to the source's own artwork/text when TMDB misses,
     * and stale results (user already left or switched source) are dropped.
     */
    static void enrichDetailWithTmdb(final MainActivity a, final Cms.Site s, final Cms.Video v) {
        if (a == null || s == null || v == null || v.title == null || v.title.isEmpty()) return;
        final String title = v.title;
        final String year = v.year;
        final int gen = a.generation;
        a.submitPage(
                () -> {
                    try {
                        TmdbClient.Item hit = TmdbClient.search(a.getFilesDir(), title, year);
                        if (hit == null) {
                            // mkoai 2.0.62: no TMDB hit — still run the
                            // title-based extras (OMDb by title; TVmaze for
                            // series). Fanart needs an id, so it is skipped.
                            enrichDetailExtras(a, s, v, 0, null);
                            return;
                        }
                        TmdbClient.Item full = TmdbClient.enrich(a.getFilesDir(), hit);
                        if (full == null) full = hit;
                        final TmdbClient.Item item = full;
                        a.ui.post(
                                () -> {
                                    if (gen != a.generation || a.isFinishing() || a.root == null) {
                                        return;
                                    }
                                    TextView titleView =
                                            (TextView) a.root.findViewWithTag("detail-title");
                                    if (titleView == null) return;
                                    // Still the same detail page? The shown
                                    // title is s2t(title); compare normalized.
                                    String shown = titleView.getText().toString();
                                    if (!Cms.normTitle(ChineseConvert.s2t(title))
                                            .equals(Cms.normTitle(shown))) return;
                                    applyTmdbToDetail(a, s, v, item);
                                });
                    } catch (Throwable ignored) {
                    }
                });
    }

    private static void applyTmdbToDetail(
            MainActivity a, Cms.Site s, Cms.Video v, TmdbClient.Item item) {
        // mkoai 2.0.62: keep the TMDB id — the OMDb / Fanart lookups below
        // (and TmdbClient.toVideo, when it lands the same assignment) need it.
        // toVideo already sets v.tmdbId when the card came from a TMDB row;
        // writing the same value again here is harmless.
        v.tmdbId = item.id;
        // Poster: swap to the TMDB (Chinese) poster + HD resolve. The
        // backdrop follows automatically via PosterHookImageView.
        if (item.poster != null
                && !item.poster.isEmpty()
                && !item.poster.equals(v.poster)) {
            View cover = a.root.findViewWithTag("detail-cover");
            if (cover instanceof PosterHookImageView) {
                PosterHookImageView posterHook = (PosterHookImageView) cover;
                posterHook.resetHdLock();
                a.poster(item.poster, posterHook, true);
                ImmersivePoster.resolveHdPortraitPoster(
                        a,
                        v.title,
                        v.year,
                        v.actor,
                        v.director,
                        item.poster,
                        !SearchVariants.isComicOrShortDrama(v, s.name),
                        hdBmp -> {
                            posterHook.setHdBitmap(hdBmp);
                            refreshEpisodeStillBitmaps(a.root, hdBmp);
                        });
                v.poster = item.poster;
            }
        }
        // Overview: replace the synopsis when TMDB has one.
        if (item.overview != null && !item.overview.trim().isEmpty()) {
            String overview = ChineseConvert.s2t(item.overview.trim());
            TextView descTv = (TextView) a.root.findViewWithTag("detail-synopsis");
            if (descTv != null) {
                descTv.setText(overview);
            } else {
                LinearLayout wrap =
                        (LinearLayout) a.root.findViewWithTag("detail-synopsis-wrap");
                if (wrap != null) {
                    final TextView fresh = a.text(overview, 13.5f, 0xd9ffffff);
                    fresh.setTag("detail-synopsis");
                    fresh.setGravity(Gravity.CENTER);
                    fresh.setLineSpacing(a.dp(3.5f), 1f);
                    fresh.setMaxLines(2);
                    fresh.setEllipsize(TextUtils.TruncateAt.END);
                    wrap.addView(fresh, new LinearLayout.LayoutParams(-1, -2));
                    final boolean[] expanded = {false};
                    wrap.setOnClickListener(
                            view -> {
                                expanded[0] = !expanded[0];
                                fresh.setMaxLines(expanded[0] ? 18 : 2);
                            });
                }
            }
        }
        // Cast: replace the 演職員 line when TMDB has one. When the source
        // had no cast view at all, the new line shows directly (the
        // source's expand toggle only knows its own cast).
        if (item.cast != null && !item.cast.trim().isEmpty()) {
            String cast = "演職員：" + ChineseConvert.s2t(item.cast.trim());
            TextView castTv = (TextView) a.root.findViewWithTag("detail-cast");
            if (castTv != null) {
                castTv.setText(cast);
            } else {
                LinearLayout wrap =
                        (LinearLayout) a.root.findViewWithTag("detail-synopsis-wrap");
                if (wrap != null) {
                    TextView fresh = a.text(cast, 12, 0x99ffffff);
                    fresh.setTag("detail-cast");
                    fresh.setGravity(Gravity.CENTER);
                    fresh.setPadding(0, a.dp(5), 0, 0);
                    fresh.setMaxLines(3);
                    fresh.setEllipsize(TextUtils.TruncateAt.END);
                    wrap.addView(fresh, new LinearLayout.LayoutParams(-1, -2));
                }
            }
        }
        // Vote: fill the meta line's ★ slot only when Douban didn't.
        if (item.vote > 0) {
            TextView metaLine = (TextView) a.root.findViewWithTag("detail-meta-line");
            if (metaLine != null && !metaLine.getText().toString().contains("★")) {
                updateMetaLine(metaLine, v, s.name, item.vote);
            }
        }
        // mkoai 2.0.62: the overview may have just changed — re-decide
        // whether the translate button shows, then run the OMDb / Fanart /
        // TVmaze enrichment in the background (fail-soft, never blocks).
        refreshTranslateButton(a);
        enrichDetailExtras(a, s, v, item.id, item.type);
    }

    /** Still the same detail page? Compares the shown (s2t) title, normalized. */
    private static boolean isSameDetailPage(MainActivity a, String title) {
        if (a == null || a.root == null || title == null) return false;
        TextView titleView = (TextView) a.root.findViewWithTag("detail-title");
        if (titleView == null) return false;
        return Cms.normTitle(ChineseConvert.s2t(title))
                .equals(Cms.normTitle(titleView.getText().toString()));
    }

    private static boolean containsChinese(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    /**
     * mkoai 2.0.62: show the translate button only when the current synopsis
     * is non-empty and contains no CJK characters. Runs after present builds
     * the synopsis and after applyTmdbToDetail replaces it.
     */
    private static void refreshTranslateButton(MainActivity a) {
        if (a == null || a.root == null) return;
        TextView btn = (TextView) a.root.findViewWithTag("detail-translate-btn");
        if (btn == null) return;
        // mkoai 2.0.66: while the DeepL quota is out the button stays
        // hidden (it returns after 24h and re-tests on the next tap).
        if (QuotaState2066.isHidden(
                QuotaState2066.prefsStore(a.prefs),
                QuotaState2066.TRANSLATE, System.currentTimeMillis())) {
            btn.setVisibility(View.GONE);
            return;
        }
        TextView translatedTv =
                (TextView) a.root.findViewWithTag("detail-synopsis-translated");
        if (translatedTv != null && translatedTv.getVisibility() == View.VISIBLE) {
            btn.setVisibility(View.GONE);
            return;
        }
        TextView descTv = (TextView) a.root.findViewWithTag("detail-synopsis");
        String overview = descTv == null ? "" : descTv.getText().toString();
        boolean show =
                overview != null && !overview.trim().isEmpty() && !containsChinese(overview);
        btn.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Douban score currently shown on the page (0.0 when the badge is hidden). */
    private static double currentDoubanScore(MainActivity a) {
        if (a == null || a.root == null) return 0.0;
        TextView db = (TextView) a.root.findViewWithTag("detail-douban-rating");
        if (db == null || db.getVisibility() != View.VISIBLE) return 0.0;
        Matcher m =
                Pattern.compile("★\\s*([0-9]+(?:\\.[0-9])?)")
                        .matcher(db.getText().toString());
        if (m.find()) {
            try {
                double d = Double.parseDouble(m.group(1));
                if (d >= 2.0 && d <= 10.0) return d;
            } catch (Exception ignored) {
            }
        }
        return 0.0;
    }

    /** Trailer list dialog: tap opens the video in the browser (ACTION_VIEW). */
    private static void showTrailerList(
            MainActivity a, String title, List<YoutubeClient.Trailer> trailers) {
        String[] names = new String[trailers.size()];
        for (int i = 0; i < trailers.size(); i++) {
            YoutubeClient.Trailer t = trailers.get(i);
            String line = t.title == null ? "" : t.title;
            if (t.channel != null && !t.channel.isEmpty()) line += " — " + t.channel;
            names[i] = line;
        }
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("預告片")
                .setItems(
                        names,
                        (d, index) -> {
                            if (index < 0 || index >= trailers.size()) return;
                            YoutubeClient.Trailer t = trailers.get(index);
                            if (t.videoId == null || t.videoId.isEmpty()) return;
                            try {
                                JSONObject props = new JSONObject();
                                props.put("title", title);
                                PostHogClient.capture("trailer_open", props);
                            } catch (Exception ignored) {
                            }
                            try {
                                Intent intent =
                                        new Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(YoutubeClient.watchUrl(t.videoId)));
                                a.startActivity(intent);
                            } catch (Exception e) {
                                AppLog.w("MobileDetailScreen", "open trailer failed", e);
                                a.toast("無法開啟 YouTube");
                            }
                        })
                .show();
    }

    /**
     * mkoai 2.0.62: OMDb ratings + Fanart artwork + TVmaze airing info, all in
     * one background pass. Every step is fail-soft: a miss hides its badge /
     * view and leaves the page exactly as it was. Runs after the TMDB hit
     * (tmdbId &gt; 0) and also after a TMDB miss (tmdbId == 0: OMDb falls back
     * to a title lookup, Fanart is skipped, TVmaze still runs for series).
     */
    private static void enrichDetailExtras(
            final MainActivity a,
            final Cms.Site s,
            final Cms.Video v,
            final int tmdbId,
            final String tmdbType) {
        if (a == null || s == null || v == null || v.title == null || v.title.isEmpty()) return;
        final String title = v.title;
        final int gen = a.generation;
        final boolean isTv =
                "tv".equals(tmdbType) || VideoKind2061.kindOf(v) == VideoKind2061.SERIES;
        final String tmdbKind =
                "tv".equals(tmdbType)
                        ? "tv"
                        : ("movie".equals(tmdbType) ? "movie" : (isTv ? "tv" : "movie"));
        a.submitPage(
                () -> {
                    try {
                        // OMDb: TMDB external imdb_id first, title fallback.
                        JSONObject omdb = null;
                        // mkoai 2.0.66: while the OMDb daily quota is out,
                        // skip the fetch entirely — the ratings area stays
                        // hidden until the UTC midnight reset, and no
                        // request is made on every detail visit.
                        boolean omdbHidden = QuotaState2066.isHidden(
                                QuotaState2066.prefsStore(a.prefs),
                                QuotaState2066.OMDB, System.currentTimeMillis());
                        if (!omdbHidden) {
                            try {
                                String imdbId = null;
                                if (tmdbId > 0) {
                                    try {
                                        imdbId =
                                                TmdbClient.externalImdbId(
                                                        a.getFilesDir(), tmdbKind, tmdbId);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                omdb =
                                        (imdbId != null && !imdbId.isEmpty())
                                                ? OmdbClient.fetchByImdbIdOrThrow(imdbId)
                                                : OmdbClient.fetchByTitleOrThrow(title);
                            } catch (WorkerApi.RateLimited limited) {
                                // OMDb daily quota — record it so the
                                // ratings area stays hidden until the
                                // reset. A network failure never reaches
                                // here (only a Worker 429 does).
                                QuotaState2066.record(
                                        QuotaState2066.prefsStore(a.prefs),
                                        QuotaState2066.OMDB,
                                        limited.error, limited.source,
                                        limited.retryAfterOrNull,
                                        System.currentTimeMillis());
                            } catch (Throwable ignored) {
                            }
                        }

                        // Fanart: movies take the TMDB id; TV takes the TVDB id
                        // (TMDB external ids first, TVmaze externals as fallback).
                        JSONObject fanart = null;
                        String fanartBg = null;
                        String fanartLogo = null;
                        JSONObject tvShow = null;
                        try {
                            if (tmdbId > 0) {
                                if (isTv) {
                                    String tvdbId = null;
                                    try {
                                        // externalTvdbId returns Integer (not
                                        // String): null when absent.
                                        Integer tvdb =
                                                TmdbClient.externalTvdbId(
                                                        a.getFilesDir(), tmdbId);
                                        if (tvdb != null) tvdbId = String.valueOf(tvdb);
                                    } catch (Throwable ignored) {
                                    }
                                    if (tvdbId == null || tvdbId.isEmpty()) {
                                        try {
                                            tvShow = TvmazeHome.searchShow(title);
                                        } catch (Throwable ignored) {
                                        }
                                        if (tvShow != null) {
                                            JSONObject externals =
                                                    tvShow.optJSONObject("externals");
                                            if (externals != null) {
                                                tvdbId = externals.optString("tvdb", null);
                                            }
                                        }
                                    }
                                    if (tvdbId != null && !tvdbId.isEmpty()) {
                                        fanart = FanartClient.fetchArt("tv", tvdbId);
                                    }
                                } else {
                                    fanart = FanartClient.fetchArt("movies", String.valueOf(tmdbId));
                                }
                                if (fanart != null) {
                                    fanartBg = FanartClient.pickBackground(fanart);
                                    fanartLogo = FanartClient.pickLogo(fanart);
                                }
                            }
                        } catch (Throwable ignored) {
                        }

                        // TVmaze airing line (series only). Reuses the show
                        // object fetched above for the TVDB id when possible.
                        String tvmazeLine = null;
                        if (isTv) {
                            try {
                                if (tvShow == null) tvShow = TvmazeHome.searchShow(title);
                                tvmazeLine = buildTvmazeLine(tvShow);
                            } catch (Throwable ignored) {
                            }
                        }

                        // Fanart backdrop bitmap, downloaded on this thread.
                        Bitmap bgBmp = null;
                        if (fanartBg != null && !fanartBg.isEmpty()) {
                            try {
                                bgBmp = fetchBackdropBitmap(fanartBg);
                            } catch (Throwable ignored) {
                            }
                        }

                        final JSONObject omdbFinal = omdb;
                        final String logoFinal = fanartLogo;
                        final Bitmap bgFinal = bgBmp;
                        final String tvmazeFinal = tvmazeLine;
                        a.ui.post(
                                () -> {
                                    if (gen != a.generation
                                            || a.isFinishing()
                                            || a.root == null
                                            || !isSameDetailPage(a, title)) {
                                        return;
                                    }
                                    if (omdbFinal != null) {
                                        TextView db =
                                                (TextView)
                                                        a.root.findViewWithTag(
                                                                "detail-douban-rating");
                                        TextView rt =
                                                (TextView)
                                                        a.root.findViewWithTag("detail-rt-rating");
                                        TextView imdb =
                                                (TextView)
                                                        a.root.findViewWithTag(
                                                                "detail-imdb-rating");
                                        TextView mc =
                                                (TextView)
                                                        a.root.findViewWithTag("detail-mc-rating");
                                        if (db != null && rt != null) {
                                            updateRatingBadges(
                                                    db,
                                                    rt,
                                                    imdb,
                                                    mc,
                                                    v,
                                                    currentDoubanScore(a),
                                                    omdbFinal);
                                        }
                                    }
                                    if (logoFinal != null && !logoFinal.isEmpty()) {
                                        View logo =
                                                a.root.findViewWithTag("detail-fanart-logo");
                                        if (logo instanceof ImageView) {
                                            logo.setVisibility(View.VISIBLE);
                                            a.poster(logoFinal, (ImageView) logo, false);
                                        }
                                    }
                                    // Backdrop only: the bitmap goes straight
                                    // onto the backdrop canvas; the portrait
                                    // poster hook is left untouched.
                                    if (bgFinal != null && !bgFinal.isRecycled()) {
                                        View backdrop =
                                                a.root.findViewWithTag("detail-backdrop");
                                        if (backdrop instanceof PortraitBackdropView) {
                                            ((PortraitBackdropView) backdrop)
                                                    .setPosterBitmap(bgFinal, null);
                                        }
                                    }
                                    if (tvmazeFinal != null && !tvmazeFinal.isEmpty()) {
                                        TextView info =
                                                (TextView)
                                                        a.root.findViewWithTag(
                                                                "detail-tvmaze-info");
                                        if (info != null) {
                                            info.setText(tvmazeFinal);
                                            info.setVisibility(View.VISIBLE);
                                        }
                                    }
                                });
                    } catch (Throwable ignored) {
                    }
                });
    }

    /** "連載中 · Netflix · 下集：S3E5「標題」 11/20 21:00" — null when nothing usable. */
    private static String buildTvmazeLine(JSONObject show) {
        if (show == null) return null;
        List<String> parts = new ArrayList<>();
        String status = show.optString("status", null);
        if (status != null && !status.isEmpty()) parts.add(tvmazeStatusLabel(status));
        String network = null;
        JSONObject net = show.optJSONObject("network");
        if (net != null) network = net.optString("name", null);
        if (network == null || network.isEmpty()) {
            JSONObject wc = show.optJSONObject("webChannel");
            if (wc != null) network = wc.optString("name", null);
        }
        if (network != null && !network.isEmpty()) parts.add(network);
        // Next episode: airstamp (precise UTC) only — never airdate.
        String nextHref = null;
        JSONObject links = show.optJSONObject("_links");
        if (links != null) {
            JSONObject next = links.optJSONObject("nextepisode");
            if (next != null) nextHref = next.optString("href", null);
        }
        if (nextHref != null && !nextHref.isEmpty()) {
            try {
                JSONObject ep = TvmazeHome.fetchEpisode(nextHref);
                if (ep != null) {
                    String when = formatAirstampTaipei(ep.optString("airstamp", null));
                    String epName = ep.optString("name", null);
                    int season = ep.optInt("season", 0);
                    int number = ep.optInt("number", 0);
                    StringBuilder next = new StringBuilder("下集：");
                    if (season > 0 && number > 0) {
                        next.append("S").append(season).append("E").append(number).append(" ");
                    }
                    if (epName != null && !epName.isEmpty()) {
                        next.append("「").append(epName).append("」 ");
                    }
                    if (when != null) next.append(when);
                    String text = next.toString().trim();
                    if (!"下集：".equals(text)) parts.add(text);
                }
            } catch (Throwable ignored) {
            }
        }
        if (parts.isEmpty()) return null;
        return TextUtils.join(" · ", parts);
    }

    private static String tvmazeStatusLabel(String status) {
        if ("Running".equals(status)) return "連載中";
        if ("Ended".equals(status)) return "已完結";
        if ("To Be Determined".equals(status)) return "待定";
        if ("In Development".equals(status)) return "製作中";
        return status;
    }

    /**
     * TVmaze airstamp (UTC, e.g. "2026-11-20T13:00:00+00:00") to Taipei
     * "M/d HH:mm". The zone designator is stripped and the wall time parsed
     * as UTC — TVmaze always returns airstamp in UTC.
     */
    private static String formatAirstampTaipei(String airstamp) {
        if (airstamp == null || airstamp.isEmpty()) return null;
        try {
            String s = airstamp.trim();
            int cut = s.length();
            int z = s.indexOf('Z');
            if (z > 0) cut = Math.min(cut, z);
            int plus = s.indexOf('+', 10);
            if (plus > 0) cut = Math.min(cut, plus);
            int minus = s.indexOf('-', 10);
            if (minus > 0) cut = Math.min(cut, minus);
            String core = s.substring(0, cut);
            if (core.contains(".")) core = core.substring(0, core.indexOf('.'));
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(core);
            if (d == null) return null;
            SimpleDateFormat out = new SimpleDateFormat("M/d HH:mm", Locale.getDefault());
            out.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
            return out.format(d);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Download one image (max 2MB) and decode it downsampled to <= 1280px wide. */
    private static Bitmap fetchBackdropBitmap(String url) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            Cms.ImageRequest src = Cms.imageRequest(url);
            String target = Cms.normalizeHttp(src.url);
            c = (HttpURLConnection) new URL(target).openConnection();
            c.setConnectTimeout(4500);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", Cms.USER_AGENT);
            for (Map.Entry<String, String> h : src.headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (c.getResponseCode() / 100 != 2) return null;
            in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(tmp)) != -1) {
                total += n;
                if (total > 2 * 1024 * 1024) return null;
                buf.write(tmp, 0, n);
            }
            byte[] bytes = buf.toByteArray();
            if (bytes.length == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / sample > 1280) sample *= 2;
            o.inSampleSize = sample;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (c != null) c.disconnect();
        }
    }

    static void updateMetaLine(
            TextView metaLine, Cms.Video v, String siteName, double resolvedDouban) {
        if (metaLine == null) return;
        // mkoai 2.0.63: the score and the site already show on the
        // rating badges below (豆瓣 ★ / RT / IMDb / MC + site badge), so
        // the meta line keeps only year + genre — no duplicates.
        // (resolvedDouban / siteName stay in the signature so the five
        // call sites need no change.)
        List<String> parts = new ArrayList<>();
        if (v != null && v.year != null && !v.year.trim().isEmpty()) {
            parts.add(v.year.trim());
        }
        if (v != null && v.tag != null && !v.tag.trim().isEmpty()) {
            parts.add(ChineseConvert.s2t(v.tag.trim()));
        }
        if (parts.isEmpty()) {
            metaLine.setText("");
            metaLine.setVisibility(View.GONE);
            return;
        }
        metaLine.setText(TextUtils.join("  |  ", parts));
        metaLine.setVisibility(View.VISIBLE);
    }

    static void updateRatingBadges(
            TextView doubanBadge, TextView rtBadge, Cms.Video v, double resolvedDouban) {
        updateRatingBadges(doubanBadge, rtBadge, null, null, v, resolvedDouban, cachedOmdbFor(v));
    }

    /**
     * mkoai 2.0.62: rating badges with OMDb data. Rotten Tomatoes prefers the
     * OMDb value and only falls back to the old remarks/description regex;
     * IMDb and Metacritic badges show only when OMDb has the value. imdbBadge
     * / mcBadge may be null (TV DetailScreen has no such views). A non-null
     * omdb refreshes the page-scoped OMDb cache so later 4-arg calls keep
     * the OMDb-priority RT value. Every badge hides when it has no value —
     * never an empty badge.
     */
    static void updateRatingBadges(
            TextView doubanBadge,
            TextView rtBadge,
            TextView imdbBadge,
            TextView mcBadge,
            Cms.Video v,
            double resolvedDouban,
            JSONObject omdb) {
        if (omdb != null && v != null && v.title != null) {
            sOmdbTitleKey = Cms.normTitle(v.title);
            sOmdbData = omdb;
        }
        double dbScore = resolvedDouban;
        int rtPercent = -1;
        String imdbValue = null;
        String mcValue = null;
        if (omdb != null) {
            imdbValue = OmdbClient.ratingValue(omdb, "Internet Movie Database");
            String rtValue = OmdbClient.ratingValue(omdb, "Rotten Tomatoes");
            if (rtValue != null) {
                Matcher m = Pattern.compile("(\\d{1,3})\\s*%").matcher(rtValue);
                if (m.find()) {
                    try {
                        int parsed = Integer.parseInt(m.group(1));
                        if (parsed >= 1 && parsed <= 100) rtPercent = parsed;
                    } catch (Exception ignored) {
                    }
                }
            }
            mcValue = OmdbClient.ratingValue(omdb, "Metacritic");
        }
        if (v != null) {
            String blob =
                    (v.remarks == null ? "" : v.remarks)
                            + " "
                            + (v.description == null ? "" : v.description);
            if (dbScore < 2.0) {
                Matcher m =
                        Pattern.compile("(?:豆瓣|评分|TMDB|IMDb)\\s*[:：]?\\s*([2-9]\\.[0-9])\\s*分?")
                                .matcher(blob);
                if (m.find()) {
                    try {
                        dbScore = Double.parseDouble(m.group(1));
                    } catch (Exception ignored) {
                    }
                }
            }
            if (rtPercent <= 0) {
                Matcher rtm =
                        Pattern.compile("(?:烂番茄|新鲜度)\\s*[:：]?\\s*(\\d{2,3})\\s*%").matcher(blob);
                if (rtm.find()) {
                    try {
                        int parsed = Integer.parseInt(rtm.group(1));
                        if (parsed >= 1 && parsed <= 100) rtPercent = parsed;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (doubanBadge != null) {
            if (dbScore >= 2.0 && dbScore <= 10.0) {
                doubanBadge.setText(String.format(Locale.US, "豆瓣 ★ %.1f", dbScore));
                doubanBadge.setVisibility(View.VISIBLE);
            } else {
                doubanBadge.setText("");
                doubanBadge.setVisibility(View.GONE);
            }
        }
        if (rtBadge != null) {
            if (rtPercent > 0) {
                rtBadge.setText("爛番茄 " + rtPercent + "%");
                rtBadge.setVisibility(View.VISIBLE);
            } else {
                rtBadge.setText("");
                rtBadge.setVisibility(View.GONE);
            }
        }
        if (imdbBadge != null) {
            if (imdbValue != null && !imdbValue.isEmpty()) {
                imdbBadge.setText("IMDb " + imdbValue);
                imdbBadge.setVisibility(View.VISIBLE);
            } else {
                imdbBadge.setText("");
                imdbBadge.setVisibility(View.GONE);
            }
        }
        if (mcBadge != null) {
            if (mcValue != null && !mcValue.isEmpty()) {
                mcBadge.setText("Metacritic " + mcValue);
                mcBadge.setVisibility(View.VISIBLE);
            } else {
                mcBadge.setText("");
                mcBadge.setVisibility(View.GONE);
            }
        }
    }

    private static String normalizeBaseKey(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[\\s·:：\\-—_（）()【】\\[\\]《》〈〉\"“”'’!！?？,，.。]", "").trim();
    }

    static void populateRelatedSeasons(
            final MainActivity a,
            final Cms.Site site,
            final Cms.Video currentVideo,
            final LinearLayout episodePanel,
            final int currentLineIdx,
            final int currentPageIdx,
            final WatchHistory.Entry remembered,
            final Map<Integer, Cms.Video> bySeason,
            final Map<Integer, String> labels) {
        if (site == null || !site.supported()) return;
        final SeasonMeta curMeta = parseSeasonMeta(currentVideo.title);
        if (!curMeta.explicit) return;
        final String baseKey = normalizeBaseKey(curMeta.baseTitle);
        if (baseKey.isEmpty()) return;
        final boolean curIsComic = SearchVariants.isComicOrShortDrama(currentVideo);
        final int ticket = a.generation;
        a.submitPage(
                () -> {
                    try {
                        List<Cms.Video> found =
                                Cms.listing(
                                                SourceController.engine(a, site)
                                                        .search(curMeta.baseTitle, 1))
                                        .videos;
                        if (found == null || found.isEmpty()) return;
                        boolean added = false;
                        for (Cms.Video cand : found) {
                            if (cand == null || cand.title == null) continue;
                            if (SearchVariants.isComicOrShortDrama(cand) != curIsComic) continue;
                            SeasonMeta candMeta = parseSeasonMeta(cand.title);
                            if (!baseKey.equals(normalizeBaseKey(candMeta.baseTitle))) continue;
                            if (!curMeta.explicit
                                    && !candMeta.explicit
                                    && !Objects.equals(cand.id, currentVideo.id)) {
                                continue;
                            }
                            if (!bySeason.containsKey(candMeta.seasonNumber)) {
                                bySeason.put(candMeta.seasonNumber, cand);
                                labels.put(candMeta.seasonNumber, candMeta.seasonLabel);
                                added = true;
                            }
                        }
                        if (!added || bySeason.size() < 2) return;
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing()
                                            || a.isDestroyed()
                                            || ticket != a.generation) return;
                                    renderMobileEpisodes(
                                            a,
                                            site,
                                            currentVideo,
                                            currentLineIdx,
                                            currentPageIdx,
                                            episodePanel,
                                            remembered,
                                            bySeason,
                                            labels);
                                });
                    } catch (Exception ignored) {
                    }
                });
    }
}
