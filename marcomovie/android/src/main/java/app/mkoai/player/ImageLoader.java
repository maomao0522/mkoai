package app.mkoai.player;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded asynchronous poster/artwork loader, memory cache trimming, and background atmosphere
 * rendering extracted from {@link MainActivity}.
 */
final class ImageLoader {
    /** 2.0.54 TV lite: TV card posters load at w342 instead of w780 (set from MainActivity.onCreate). */
    static volatile boolean tvCardsSmall = false;

    private static final java.util.Map<String, java.util.List<ImageView>> IN_FLIGHT =
            new java.util.HashMap<>();

    // View-owned callback: a global weak-key map would still leak if its callback captured the view.
    private static final int FAILURE_TAG = 0x7f0e5a50;
    private static Runnable takeFailure(ImageView image) {
        Object callback = image.getTag(FAILURE_TAG);
        image.setTag(FAILURE_TAG, null);
        return callback instanceof Runnable ? (Runnable) callback : null;
    }
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> FAILED = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean failed(String url) {
        if (url == null) return false;
        Long at = FAILED.get(url);
        if (at == null) return false;
        if (System.currentTimeMillis() - at >= 60000L) { FAILED.remove(url, at); return false; }
        return true;
    }
    private static void markFailed(String url) {
        if (FAILED.size() >= 1024) FAILED.clear();
        FAILED.put(url, System.currentTimeMillis());
    }
    static void onFailure(ImageView image, Runnable retry) { image.setTag(FAILURE_TAG, retry); }
    private static boolean validImage(byte[] bytes) {
        if (bytes == null || bytes.length <= 128) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
    }
    private ImageLoader() {}

    static void cancel(MainActivity a) {
        synchronized (IN_FLIGHT) {
            IN_FLIGHT.clear();
        }
        for (Future<?> request : a.artworkRequests) request.cancel(true);
        a.artworkRequests.clear();
        ((ThreadPoolExecutor) a.artwork).purge();
    }

    static void trimMemory(MainActivity a, int level) {
        if (a.images == null) return;
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level >= 15) {
            a.images.evictAll();
        } else if (level >= 10) {
            UiCompat.trimCache(a.images, a.images.maxSize() / 2);
        }
    }

    static void poster(MainActivity a, String address, ImageView image, boolean detail) {
        image.setTag(address);
        if (address == null || (!Cms.isHttp(address) && !address.startsWith("catpaw://"))) {
            if (address != null) markFailed(address);
            Runnable retry = takeFailure(image);
            if (retry != null) a.ui.post(() -> {
                if (!a.isFinishing() && java.util.Objects.equals(address, image.getTag())) retry.run();
            });
            return;
        }
        final int ticket = a.generation;
        int initialW = ImageDecodePolicy.targetWidth(image.getWidth(), detail);
        int initialH = ImageDecodePolicy.targetHeight(image.getHeight(), detail);
        boolean initialCrop = image.getScaleType() == ImageView.ScaleType.CENTER_CROP;
        String initialKey =
                ImageDecodePolicy.cacheKey(address, initialW, initialH, initialCrop, detail);
        Bitmap immediate = a.images.get(initialKey);
        if (immediate != null && !immediate.isRecycled()) {
            takeFailure(image);
            image.setImageBitmap(immediate);
            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
            return;
        }
        Bitmap preview = a.images.get(address);
        if (preview != null && !preview.isRecycled()) {
            image.setImageBitmap(preview);
            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
            if (preview.getWidth() >= initialW && preview.getHeight() >= initialH) {
                return;
            }
        }
        startPosterLoad(a, address, image, detail, ticket);
    }

    private static void startPosterLoad(
            MainActivity a, String address, ImageView image, boolean detail, final int ticket) {
        if (ticket != a.generation || !address.equals(image.getTag())) return;
        final int width = ImageDecodePolicy.targetWidth(image.getWidth(), detail);
        final int height = ImageDecodePolicy.targetHeight(image.getHeight(), detail);
        final boolean crop = image.getScaleType() == ImageView.ScaleType.CENTER_CROP;
        final String key = ImageDecodePolicy.cacheKey(address, width, height, crop, detail);
        Bitmap cached = a.images.get(key);
        if (cached != null && !cached.isRecycled()) {
            takeFailure(image);
            image.setImageBitmap(cached);
            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(image);
            return;
        }
        final java.util.List<ImageView> owned = new java.util.ArrayList<>(2);
        synchronized (IN_FLIGHT) {
            java.util.List<ImageView> waiters = IN_FLIGHT.get(key);
            if (waiters != null) {
                if (!waiters.contains(image)) waiters.add(image);
                return;
            }
            owned.add(image);
            IN_FLIGHT.put(key, owned);
        }
        Future<?> request =
                RequestScope.submit(
                        a.artwork,
                        () -> {
                            if (ticket != a.generation) {
                                synchronized (IN_FLIGHT) {
                                    if (IN_FLIGHT.get(key) == owned) IN_FLIGHT.remove(key);
                                }
                                return;
                            }
                            boolean loaded = false;
                            HttpURLConnection connection = null;
                            try {
                                Cms.ImageRequest source = Cms.imageRequest(address);
                                String resolvedUrl = Cms.normalizeHttp(source.url);
                                if (resolvedUrl.startsWith("catpaw://")) {
                                    resolvedUrl =
                                            new com.pilipala.catpaw.CatPawHost(a)
                                                    .resolve(resolvedUrl);
                                }
                                String targetUrl = upgradePosterUrl(resolvedUrl, detail);
                                byte[] bytes =
                                        fetchPosterBytesWithFallback(
                                                a, source, resolvedUrl, targetUrl, detail);
                                if (bytes == null || bytes.length == 0) return;
                                BitmapFactory.Options options = new BitmapFactory.Options();
                                options.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                                options.inSampleSize =
                                        ImageDecodePolicy.sample(
                                                options.outWidth,
                                                options.outHeight,
                                                width,
                                                height,
                                                crop,
                                                detail);
                                options.inJustDecodeBounds = false;
                                RequestScope.current().check();
                                Bitmap bitmap =
                                        BitmapFactory.decodeByteArray(
                                                bytes, 0, bytes.length, options);
                                if (bitmap != null) {
                                    loaded = true;
                                    FAILED.remove(address);
                                    if (ticket != a.generation
                                            || Thread.currentThread().isInterrupted()) {
                                        bitmap.recycle();
                                        return;
                                    }
                                    a.images.put(key, bitmap);
                                    Bitmap existingPreview = a.images.get(address);
                                    if (existingPreview == null
                                            || existingPreview.isRecycled()
                                            || bitmap.getWidth() >= existingPreview.getWidth()) {
                                        a.images.put(address, bitmap);
                                    }
                                    final java.util.List<ImageView> targets;
                                    synchronized (IN_FLIGHT) {
                                        java.util.List<ImageView> removed =
                                                IN_FLIGHT.get(key) == owned
                                                        ? IN_FLIGHT.remove(key)
                                                        : null;
                                        targets =
                                                removed == null
                                                        ? java.util.Collections.singletonList(image)
                                                        : new java.util.ArrayList<>(removed);
                                    }
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation || a.isFinishing())
                                                    return;
                                                for (ImageView target : targets) {
                                                    if (target != null
                                                            && address.equals(target.getTag())) {
                                                        // mkoai 2.0.38: network-loaded posters
                                                        // fade in (180ms, hardware-accelerated)
                                                        // instead of popping in. Views with a
                                                        // custom alpha (e.g. the hero backdrop
                                                        // at 0.45) keep it: only fade views
                                                        // currently at full opacity.
                                                        takeFailure(target);
                                                        if (target.getAlpha() == 1f) {
                                                            target.setAlpha(0f);
                                                            target.setImageBitmap(bitmap);
                                                            target.animate()
                                                                    .alpha(1f)
                                                                    .setDuration(180)
                                                                    .start();
                                                        } else {
                                                            target.setImageBitmap(bitmap);
                                                        }
                                                        com.pilipala.glass.GlassworkSurface
                                                                .invalidateBackdrop(target);
                                                    }
                                                }
                                            });
                                }
                            } catch (Exception ignored) {
                            } finally {
                                final java.util.List<ImageView> failedTargets;
                                synchronized (IN_FLIGHT) {
                                    failedTargets = IN_FLIGHT.get(key) == owned
                                            ? new java.util.ArrayList<>(owned) : java.util.Collections.emptyList();
                                    if (IN_FLIGHT.get(key) == owned) IN_FLIGHT.remove(key);
                                }
                                if (!loaded && ticket == a.generation && !Thread.currentThread().isInterrupted()) {
                                    markFailed(address);
                                    a.ui.post(() -> {
                                        if (ticket != a.generation || a.isFinishing()) return;
                                        for (ImageView target : failedTargets) {
                                            if (!address.equals(target.getTag())) continue;
                                            Runnable retry = takeFailure(target);
                                            if (retry != null) retry.run();
                                        }
                                    });
                                }
                                if (connection != null) {
                                    RequestScope.detach(connection);
                                    connection.disconnect();
                                }
                            }
                        });
        for (java.util.Iterator<Future<?>> it = a.artworkRequests.iterator(); it.hasNext(); ) {
            if (it.next().isDone()) it.remove();
        }
        a.artworkRequests.add(request);
    }

    private static File diskCacheFile(MainActivity a, String url) {
        try {
            File dir = new File(a.getCacheDir(), "posters_v2");
            if (!dir.exists()) dir.mkdirs();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format(java.util.Locale.ROOT, "%02x", d[i] & 255));
            }
            return new File(dir, sb.toString() + ".img");
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readDiskCachedPoster(MainActivity a, String url) {
        try {
            File f = diskCacheFile(a, url);
            if (f == null || !f.isFile()) return null;
            long len = f.length();
            if (len <= 128 || len > 8 * 1024 * 1024) return null;
            if (System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000L) {
                return null;
            }
            byte[] buf = new byte[(int) len];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r < 0) break;
                    off += r;
                }
                if (off != buf.length) return null;
            }
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDiskCachedPoster(MainActivity a, String url, byte[] bytes) {
        try {
            File f = diskCacheFile(a, url);
            if (f == null) return;
            File temp = new File(f.getParentFile(), f.getName() + "." + Thread.currentThread().getId() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                out.write(bytes);
            }
            if (!temp.renameTo(f)) temp.delete();
        } catch (Exception ignored) {
        }
    }

    /**
     * 2.0.40: detail-page TMDB chain is w1280 -> original -> w780 ->
     * original URL, so a missing w1280 still resolves to a sharp image
     * instead of failing the load. Non-TMDB detail keeps the old chain
     * (upgraded target, card-size upgrade, original URL). Package-visible
     * for the plain-JVM checks. Duplicates are skipped by the caller.
     */
    static String[] detailCandidates(String targetUrl, String resolvedUrl, boolean detail) {
        if (detail && targetUrl.contains("image.tmdb.org/t/p/")) {
            return new String[] {
                targetUrl,
                targetUrl.replace("/t/p/w1280/", "/t/p/original/"),
                targetUrl.replace("/t/p/w1280/", "/t/p/w780/"),
                resolvedUrl
            };
        }
        if (detail && !targetUrl.equals(resolvedUrl)) {
            return new String[] {targetUrl, upgradePosterUrl(resolvedUrl, false), resolvedUrl};
        }
        return new String[] {targetUrl, resolvedUrl};
    }

    private static byte[] fetchPosterBytesWithFallback(
            MainActivity a,
            Cms.ImageRequest source,
            String resolvedUrl,
            String targetUrl,
            boolean detail)
            throws Exception {
        String[] candidates = detailCandidates(targetUrl, resolvedUrl, detail);
        // 2.0.57 (ported from 2.0.56): whitelisted poster hosts go through the
        // Cloudflare Worker proxy first (/img?url=), with the direct URL kept
        // as fallback, so a proxy failure never loses a poster that loads
        // fine directly. Disk cache is keyed by the URL actually fetched.
        java.util.ArrayList<String> fetchList = new java.util.ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) continue;
            String proxied = proxyPosterUrl(candidate);
            if (!proxied.equals(candidate)) fetchList.add(proxied);
            fetchList.add(candidate);
        }
        candidates = fetchList.toArray(new String[0]);
        Exception lastError = null;
        java.util.HashSet<String> tried = new java.util.HashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty() || !tried.add(candidate)) continue;
            byte[] cached = readDiskCachedPoster(a, candidate);
            if (validImage(cached)) return cached;
            if (cached != null) { File broken = diskCacheFile(a, candidate); if (broken != null) broken.delete(); }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(candidate).openConnection();
                for (Map.Entry<String, String> header : source.headers.entrySet())
                    connection.setRequestProperty(header.getKey(), header.getValue());
                String fragment = new URL(candidate).getRef();
                if (fragment != null && fragment.startsWith("mm-emby=")) {
                    connection.setInstanceFollowRedirects(false);
                    com.pilipala.emby.EmbyLibrary library =
                            com.pilipala.emby.EmbyAccounts.open(
                                    a, "emby://account/" + fragment.substring(8));
                    for (Map.Entry<String, String> h : library.imageHeaders(candidate).entrySet())
                        connection.setRequestProperty(h.getKey(), h.getValue());
                }
                applyArtworkHeaders(connection, candidate, source.headers);
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(5500);
                if (!source.headers.containsKey("User-Agent"))
                    connection.setRequestProperty("User-Agent", Cms.USER_AGENT);
                RequestScope.attach(connection);
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        RequestScope.current().check();
                        if (out.size() + n > (detail ? 8 : 3) * 1024 * 1024)
                            throw new IOException("Poster too large");
                        out.write(buffer, 0, n);
                    }
                    byte[] bytes = out.toByteArray();
                    if (validImage(bytes)) {
                        writeDiskCachedPoster(a, candidate, bytes);
                        return bytes;
                    }
                }
            } catch (Exception e) {
                lastError = e;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (lastError != null) throw lastError;
        return null;
    }

    /**
     * 2.0.57 (ported from 2.0.56): poster CDN proxy. Whitelisted hosts (image.tmdb.org,
     * empimage.slcache.com, *.doubanio.com) are fetched through the
     * Cloudflare Worker (/img?url=...), which caches and serves them from
     * the edge; every other host is returned unchanged, so the Worker never
     * sees (and never 400s) a URL it cannot serve. The predicate mirrors
     * the Worker's imgAllowed() exactly. An already-proxied URL passes
     * through untouched. Package-visible for the plain-JVM checks.
     */
    static final String POSTER_PROXY_BASE =
            "https://maoki-mkoai-api.maotai0522.workers.dev/img?url=";

    static String proxyPosterUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.startsWith(POSTER_PROXY_BASE)) return url;
        try {
            java.net.URL parsed = new java.net.URL(url);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) return url;
            String host = parsed.getHost().toLowerCase(java.util.Locale.ROOT);
            boolean allowed =
                    "image.tmdb.org".equals(host)
                            || "empimage.slcache.com".equals(host)
                            || host.endsWith(".doubanio.com");
            if (!allowed) return url;
            return POSTER_PROXY_BASE
                    + java.net.URLEncoder.encode(url, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return url;
        }
    }

    static String upgradePosterUrl(String rawUrl, boolean detail) {
        if (rawUrl == null || rawUrl.isEmpty()) return "";
        String url = rawUrl;
        if (url.contains("doubanio.com")) {
            url =
                    url.replaceAll(
                            "https?://(?:img[4-9]|qnmob\\d*-sign)\\.doubanio\\.com/",
                            "https://img1.doubanio.com/");
            int qIdx = url.indexOf('?');
            if (qIdx > 0 && url.substring(0, qIdx).endsWith(".jpg")) {
                url = url.substring(0, qIdx);
            }
            if (detail) {
                url =
                        url.replace("/s_ratio_poster/", "/l_ratio_poster/")
                                .replace("/m_ratio_poster/", "/l_ratio_poster/")
                                .replace("/view/photo/spic/", "/view/photo/lpic/")
                                .replace("/view/photo/mpic/", "/view/photo/lpic/")
                                .replace("/view/photo/s/", "/view/photo/l/")
                                .replace("/view/photo/m/", "/view/photo/l/")
                                .replace("/view/photo/large/", "/view/photo/l/");
            } else {
                url =
                        url.replace("/s_ratio_poster/", "/m_ratio_poster/")
                                .replace("/view/photo/spic/", "/view/photo/mpic/");
            }
        }
        if (url.contains("image.tmdb.org/t/p/")) {
            // 2.0.53: TV card posters loaded at w500 (tvCardsSmall) — the
            // weak TV SoC decodes less, and TV card posters are only ~110dp
            // wide. Detail pages stay w1280 on both.
            // 2.0.54 TV lite: TV cards drop from w500 to w342 (still >2x
            // the ~110dp display width; ~55% fewer source pixels than w500).
            url =
                    url.replaceAll(
                            "/t/p/w[0-9]+/",
                            detail ? "/t/p/w1280/" : (tvCardsSmall ? "/t/p/w342/" : "/t/p/w780/"));
            if (detail) {
                // 2.0.40: detail pages load TMDB art at w1280 (was w780) —
                // the full-bleed backdrop/poster is upscaled to 1080px wide
                // on the phone, so the source must be sharper than the
                // display. Cards (detail=false) load at w780 (2.0.46).
                url =
                        url.replace("/t/p/w185/", "/t/p/w1280/")
                                .replace("/t/p/w300/", "/t/p/w1280/")
                                .replace("/t/p/w342/", "/t/p/w1280/")
                                .replace("/t/p/w500/", "/t/p/w1280/")
                                .replace("/t/p/w780/", "/t/p/w1280/");
            } else {
                // mkoai 2.0.46: cards load TMDB posters at w780 (was
                // w500) — TMDB's largest standard poster size, about
                // 1080p-equivalent for a portrait card. Older w500 URLs
                // (TMDB disk cache, stored cards) upgrade in place.
                // 2.0.53: on TV the target was w500 (tvCardsSmall).
                // 2.0.54 TV lite: on TV the target is w342.
                String cardSize = tvCardsSmall ? "w342" : "w780";
                url =
                        url.replace("/t/p/w185/", "/t/p/" + cardSize + "/")
                                .replace("/t/p/w300/", "/t/p/" + cardSize + "/")
                                .replace("/t/p/w342/", "/t/p/" + cardSize + "/")
                                .replace("/t/p/w500/", "/t/p/" + cardSize + "/");
            }
        }
        if (detail) {
            if (url.contains("lain.bgm.tv") || url.contains("bgm.tv/pic/cover/")) {
                url =
                        url.replace("/pic/cover/c/", "/pic/cover/l/")
                                .replace("/pic/cover/m/", "/pic/cover/l/")
                                .replace("/pic/cover/s/", "/pic/cover/l/")
                                .replace("/r/400/", "/r/800/");
            }
            if (url.contains("hdslb.com") || url.contains("biliimg.com")) {
                int at = url.indexOf('@');
                if (at > 0) url = url.substring(0, at);
            }
            if (url.contains("bcebos.com")) {
                int q = url.indexOf('?');
                if (q > 0) url = url.substring(0, q);
            }
        }
        return url;
    }

    static void applyArtworkHeaders(
            HttpURLConnection connection, String url, Map<String, String> existing) {
        if (existing != null && existing.containsKey("Referer")) return;
        String lower = url.toLowerCase();
        if (lower.contains("doubanio.com") || lower.contains("douban.com")) {
            connection.setRequestProperty("Referer", "https://movie.douban.com/");
        } else if (lower.contains("hdslb.com")
                || lower.contains("biliimg.com")
                || lower.contains("bilibili.com")) {
            connection.setRequestProperty("Referer", "https://www.bilibili.com/");
        } else if (lower.contains("bcebos.com") || lower.contains("baidu.com")) {
            connection.setRequestProperty("Referer", "https://baike.baidu.com/");
        } else if (lower.contains("bgm.tv") || lower.contains("bangumi.tv")) {
            connection.setRequestProperty("Referer", "https://bgm.tv/");
        } else if (lower.contains("bing.net") || lower.contains("bing.com")) {
            connection.setRequestProperty("Referer", "https://cn.bing.com/");
        } else if (lower.contains("qpic.cn") || lower.contains("gtimg.cn")) {
            connection.setRequestProperty("Referer", "https://v.qq.com/");
        } else if (lower.contains("sinaimg.cn")) {
            connection.setRequestProperty("Referer", "https://weibo.com/");
        }
    }

    static View createAtmosphere(MainActivity a) {
        return new Atmosphere(a);
    }

    static final class Atmosphere extends View {
        final MainActivity a;
        final Paint paint = new Paint(3);
        Bitmap wallpaper;

        Atmosphere(Context c) {
            super(c);
            a = (MainActivity) c;
            if (a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false))
                wallpaper =
                        BitmapFactory.decodeFile(
                                new File(a.getFilesDir(), "wallpaper.jpg").getAbsolutePath());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // 2.0.53: TV — solid dark background instead of the frosted
            // bitmap (paintFrost) + wallpaper blit every frame; the weak
            // TV SoC pays a full-screen bitmap draw per invalidate otherwise.
            if (a.isTvDevice()) {
                canvas.drawColor(0xff141414);
                return;
            }
            // 2.0.41: the shared frosted-matte dark base (same as the shell
            // canvas and the home ambient) — no poster diffusion anywhere.
            HomeAmbient.paintFrost(canvas, getWidth(), getHeight(), paint);
            if (wallpaper != null) {
                float scale =
                        Math.max(
                                (float) getWidth() / wallpaper.getWidth(),
                                (float) getHeight() / wallpaper.getHeight());
                float w = wallpaper.getWidth() * scale, h = wallpaper.getHeight() * scale;
                canvas.drawBitmap(
                        wallpaper,
                        null,
                        new RectF(
                                (getWidth() - w) / 2,
                                (getHeight() - h) / 2,
                                (getWidth() + w) / 2,
                                (getHeight() + h) / 2),
                        paint);
                int dim = Prefs.clampWallpaperDim(a.prefs.getInt(Prefs.WALLPAPER_DIM, 60));
                canvas.drawColor(Color.argb(Math.round(255 * dim / 100f), 0, 0, 0));
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (wallpaper != null) {
                wallpaper.recycle();
                wallpaper = null;
            }
        }
    }
}
