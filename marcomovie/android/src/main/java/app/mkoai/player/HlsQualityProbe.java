package app.mkoai.player;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * mkoai 2.0.38 (items 21/22/23): HLS master-playlist quality probe.
 *
 * <p>Emby lists versions before playback because the server owns the files and knows every
 * rendition's resolution/bitrate. mkoai's CMS sources do NOT advertise quality, so the app
 * probes the stream itself: after the play URL is resolved (an .m3u8), it fetches the master
 * playlist once on a background thread, parses the #EXT-X-STREAM-INF variants (resolution,
 * BANDWIDTH, CODECS) and offers them as a quality list. Playback itself never waits for the
 * probe — it starts immediately and the selection is applied when the probe lands (or on the
 * next playback from the in-memory cache). Any fetch/parse failure falls back silently to
 * direct playback with no error and no menu.
 *
 * <p>Pure Java except for the network fetch (plain HttpURLConnection, no Android APIs), so
 * {@link #parse} and {@link #pickAuto} are unit-testable on the JVM.
 */
final class HlsQualityProbe {

    /** Quality preference: auto-pick the highest variant. */
    static final int PREF_HIGH = 0;
    /** Quality preference: pick the highest variant the current network can sustain. */
    static final int PREF_SMOOTH = 1;

    /** Probe has not run (not an HLS URL, or playback has not started probing). */
    static final int STATE_IDLE = 0;
    /** Probe request in flight. */
    static final int STATE_PENDING = 1;
    /** Probe finished (variants may still be empty: single-variant stream or failure). */
    static final int STATE_DONE = 2;

    private static final int FETCH_TIMEOUT_MS = 8000;
    private static final int MAX_PLAYLIST_BYTES = 256 * 1024;
    private static final String USER_AGENT = "mkoai/2.0.38 (HLS quality probe)";

    /** Fallback cap (bps) used by smooth mode when no bandwidth estimate exists yet. */
    static final long SMOOTH_UNKNOWN_CAP_BPS = 8_000_000L;

    /** Safety margin: smooth mode only picks a variant using at most 80% of the estimate. */
    private static final double SMOOTH_MARGIN = 0.8;

    private static final ExecutorService PROBE_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "hls-quality-probe");
                        t.setDaemon(true);
                        return t;
                    });

    /** One quality rendition from a master playlist. */
    static final class Variant {
        final int width;
        final int height;
        /** Declared average/peak bandwidth in bits per second (0 when the playlist omits it). */
        final long bandwidth;
        final String codecs;
        /** Absolute URI of the variant playlist. */
        final String uri;

        Variant(int width, int height, long bandwidth, String codecs, String uri) {
            this.width = width;
            this.height = height;
            this.bandwidth = bandwidth;
            this.codecs = codecs == null ? "" : codecs;
            this.uri = uri == null ? "" : uri;
        }

        /** Short tier label: 4K / 1080p / 720p / 480p / 360p (or "240p" style for others). */
        String tier() {
            int edge = Math.max(width, height);
            if (height >= 2160 || width >= 3840) return "4K";
            if (height >= 1080 || edge >= 1900) return "1080p";
            if (height >= 720 || edge >= 1200) return "720p";
            if (height >= 480) return "480p";
            if (height > 0) return height + "p";
            return "";
        }

        boolean isHevc() {
            String c = codecs.toLowerCase(Locale.ROOT);
            return c.contains("hvc1") || c.contains("hev1") || c.contains("dvh1");
        }

        boolean isAvc() {
            return codecs.toLowerCase(Locale.ROOT).contains("avc1");
        }

        /** Display label, e.g. "1080p · 5.3 Mbps" or "4K · HEVC · 15.0 Mbps". */
        String label() {
            StringBuilder sb = new StringBuilder();
            String t = tier();
            if (!t.isEmpty()) sb.append(t);
            if (isHevc()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("HEVC");
            }
            if (bandwidth > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(formatBitrate(bandwidth));
            }
            if (sb.length() == 0) sb.append("未知畫質");
            return sb.toString();
        }

        /** Stable signature used to remember a manual pick across playbacks. */
        String sig() {
            return width + "x" + height + "|" + bandwidth;
        }

        static String formatBitrate(long bps) {
            if (bps >= 1_000_000) {
                double mbps = bps / 1_000_000.0;
                return String.format(Locale.ROOT, "%.1f Mbps", mbps);
            }
            if (bps >= 1_000) return (bps / 1_000) + " Kbps";
            return bps + " bps";
        }
    }

    /** Probe outcome for one stream URL. Variants are sorted best-first. */
    static final class Result {
        final String url;
        final List<Variant> variants;
        /** True when the playlist was a single-variant media playlist (#EXTINF, no variants). */
        final boolean singleVariant;

        Result(String url, List<Variant> variants, boolean singleVariant) {
            this.url = url;
            this.variants =
                    Collections.unmodifiableList(
                            variants == null ? new ArrayList<Variant>() : variants);
            this.singleVariant = singleVariant;
        }

        boolean multiVariant() {
            return variants.size() > 1;
        }
    }

    interface Callback {
        /** Always invoked on the probe worker thread (never on the UI thread). Null = failure. */
        void onResult(Result result);
    }

    // ---- in-memory cache: stream URL -> probe result (session only) ----

    private static final Map<String, Result> CACHE = new HashMap<>();

    static Result cached(String url) {
        if (url == null) return null;
        synchronized (CACHE) {
            return CACHE.get(url);
        }
    }

    static void putCached(String url, Result result) {
        if (url == null || result == null) return;
        synchronized (CACHE) {
            if (CACHE.size() > 64) CACHE.clear();
            CACHE.put(url, result);
        }
    }

    // ---- manual selection store: (site key + title) -> variant sig ----

    private static final Map<String, String> MANUAL = new HashMap<>();
    private static volatile boolean manualLoaded = false;

    static String manualKey(String siteKey, String title) {
        return (siteKey == null ? "" : siteKey)
                + "\n"
                + (title == null ? "" : title.trim());
    }

    static String getManualSig(String key) {
        if (key == null) return null;
        synchronized (MANUAL) {
            return MANUAL.get(key);
        }
    }

    static void setManualSig(String key, String sig) {
        if (key == null) return;
        synchronized (MANUAL) {
            if (sig == null) MANUAL.remove(key);
            else MANUAL.put(key, sig);
        }
    }

    /** Index of the manually picked variant in {@code variants}, or -1 when none matches. */
    static int manualIndex(List<Variant> variants, String sig) {
        if (variants == null || sig == null) return -1;
        for (int i = 0; i < variants.size(); i++) {
            if (sig.equals(variants.get(i).sig())) return i;
        }
        return -1;
    }

    /** Serialise the manual map to a flat JSON object string (for SharedPreferences). */
    static String manualToJson() {
        synchronized (MANUAL) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : MANUAL.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":\"");
                sb.append(escape(e.getValue())).append('"');
            }
            return sb.append('}').toString();
        }
    }

    /** Load a JSON string previously produced by {@link #manualToJson()}. No-op when already loaded. */
    static void ensureManualLoaded(String json) {
        if (manualLoaded) return;
        synchronized (MANUAL) {
            if (manualLoaded) return;
            manualLoaded = true;
            if (json == null || json.trim().length() < 2) return;
            try {
                String body = json.trim();
                if (!body.startsWith("{") || !body.endsWith("}")) return;
                body = body.substring(1, body.length() - 1);
                for (String pair : splitJsonPairs(body)) {
                    int colon = pair.indexOf("\":\"");
                    if (colon < 0) continue;
                    String k = unescape(pair.substring(1, colon));
                    String v = unescape(pair.substring(colon + 3, pair.length() - 1));
                    MANUAL.put(k, v);
                }
            } catch (Exception ignored) {
                // Corrupt stored JSON must never break playback.
            }
        }
    }

    private static List<String> splitJsonPairs(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && inStr && i + 1 < body.length()) {
                cur.append(c).append(body.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') inStr = !inStr;
            if (c == ',' && !inStr) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'n') out.append('\n');
                else if (n == 'r') out.append('\r');
                else if (n == 't') out.append('\t');
                else out.append(n);
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---- master playlist parsing (pure) ----

    /**
     * Parse a playlist body. Returns null when the body is not a playlist at all. A media
     * playlist (single variant) yields a Result with an empty variant list and singleVariant
     * true; a master playlist yields the variants sorted best-first.
     */
    static Result parse(String text, String baseUrl) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (!trimmed.startsWith("#EXTM3U")) return null;
        List<Variant> variants = new ArrayList<>();
        String[] lines = trimmed.split("\n");
        boolean hasMedia = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXTINF")) hasMedia = true;
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            Map<String, String> attrs = parseAttributes(line.substring("#EXT-X-STREAM-INF:".length()));
            long bandwidth = parseLong(attrs.get("BANDWIDTH"), 0);
            // Some playlists only declare AVERAGE-BANDWIDTH.
            if (bandwidth <= 0) bandwidth = parseLong(attrs.get("AVERAGE-BANDWIDTH"), 0);
            int w = 0, h = 0;
            String res = attrs.get("RESOLUTION");
            if (res != null) {
                int x = res.indexOf('x');
                if (x > 0) {
                    w = parseLong(res.substring(0, x), 0);
                    h = parseLong(res.substring(x + 1), 0);
                }
            }
            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty()) continue;
                if (next.startsWith("#")) continue;
                uri = next;
                break;
            }
            if (uri == null) continue;
            String absolute = resolve(baseUrl, uri);
            // Audio-only renditions (no RESOLUTION, audio codec only, e.g. Apple's gear0)
            // are not video qualities — offering them would switch the picture off. A
            // variant that omits CODECS entirely is kept: it cannot be proven audio-only.
            String codecsAttr = attrs.get("CODECS");
            if (w == 0
                    && h == 0
                    && codecsAttr != null
                    && !codecsAttr.isEmpty()
                    && !hasVideoCodec(codecsAttr)) continue;
            Variant v = new Variant(w, h, bandwidth, attrs.get("CODECS"), absolute);
            boolean dup = false;
            for (Variant old : variants) {
                if (old.sig().equals(v.sig())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) variants.add(v);
        }
        variants.sort(
                new Comparator<Variant>() {
                    @Override
                    public int compare(Variant a, Variant b) {
                        int c = Long.compare(b.bandwidth, a.bandwidth);
                        if (c != 0) return c;
                        return Integer.compare(
                                Math.max(b.width, b.height), Math.max(a.width, a.height));
                    }
                });
        if (variants.isEmpty() && !hasMedia) return null;
        return new Result(baseUrl, variants, variants.isEmpty());
    }

    /** Split an attribute list on commas that are not inside double quotes. */
    static Map<String, String> parseAttributes(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            if (c == ',' && !inQuotes) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) parts.add(cur.toString());
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            out.put(k, v);
        }
        return out;
    }

    /** True when the CODECS string names a video codec (avc/hevc/vp9/av1/dolby-vision). */
    static boolean hasVideoCodec(String codecs) {
        if (codecs == null) return false;
        String c = codecs.toLowerCase(Locale.ROOT);
        return c.contains("avc1")
                || c.contains("avc3")
                || c.contains("hvc1")
                || c.contains("hev1")
                || c.contains("vp09")
                || c.contains("vp08")
                || c.contains("av01")
                || c.contains("dvh1")
                || c.contains("dvhe")
                || c.contains("dav1");
    }

    private static int parseLong(String s, int fallback) {
        if (s == null) return fallback;
        try {
            long v = Long.parseLong(s.trim());
            return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static String resolve(String baseUrl, String uri) {
        if (uri == null) return "";
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri;
        if (baseUrl == null || baseUrl.isEmpty()) return uri;
        try {
            return new URL(new URL(baseUrl), uri).toString();
        } catch (Exception ignored) {
            return uri;
        }
    }

    // ---- auto pick (pure) ----

    /**
     * Pick the variant index for automatic playback.
     *
     * <p>HIGH: the best variant, always. SMOOTH: the best variant whose bandwidth fits inside
     * 80% of the measured bandwidth estimate — fast network picks 1080p, slow network steps
     * down, and it never jumps straight to the lowest rendition while a better one fits. When
     * no estimate exists yet, the cap is {@link #SMOOTH_UNKNOWN_CAP_BPS} (8 Mbps, i.e. a
     * 1080p-capable guess). When nothing fits the cap, the lowest variant is the only stable
     * choice. Returns -1 when there is nothing to pick.
     */
    static int pickAuto(List<Variant> variants, int pref, long estimatedBps) {
        if (variants == null || variants.isEmpty()) return -1;
        if (pref != PREF_SMOOTH) return 0;
        long cap = estimatedBps > 0 ? (long) (estimatedBps * SMOOTH_MARGIN) : SMOOTH_UNKNOWN_CAP_BPS;
        int best = -1;
        for (int i = 0; i < variants.size(); i++) {
            Variant v = variants.get(i);
            long bw = v.bandwidth > 0 ? v.bandwidth : Long.MAX_VALUE;
            if (bw <= cap) {
                best = i;
                break;
            }
        }
        if (best >= 0) return best;
        return variants.size() - 1;
    }

    // ---- network probe (background thread, never throws) ----

    /** True when this URL is worth probing (an HLS playlist address). */
    static boolean isProbableHls(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        return lower.contains(".m3u8");
    }

    /** Fetch + parse on the probe thread. Callback runs on the worker thread; null = failure. */
    static void probeAsync(final String url, final Map<String, String> headers, final Callback cb) {
        PROBE_EXECUTOR.execute(
                () -> {
                    Result result = null;
                    try {
                        result = probe(url, headers);
                    } catch (Exception ignored) {
                        result = null;
                    }
                    if (result != null) putCached(url, result);
                    try {
                        cb.onResult(result);
                    } catch (Exception ignored) {
                    }
                });
    }

    /** Synchronous fetch + parse. Returns null on any failure (caller falls back silently). */
    static Result probe(String url, Map<String, String> headers) {
        if (url == null || !url.startsWith("http")) return null;
        HttpURLConnection c = null;
        try {
            URL target = new URL(url);
            for (int hop = 0; hop < 5; hop++) {
                c = (HttpURLConnection) target.openConnection();
                c.setConnectTimeout(FETCH_TIMEOUT_MS);
                c.setReadTimeout(FETCH_TIMEOUT_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", USER_AGENT);
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) {
                            c.setRequestProperty(e.getKey(), e.getValue());
                        }
                    }
                }
                int status = c.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    target = new URL(target, loc);
                    c.disconnect();
                    c = null;
                    continue;
                }
                if (status < 200 || status >= 300) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (out.size() + n > MAX_PLAYLIST_BYTES) return null;
                        out.write(buf, 0, n);
                    }
                }
                String text = out.toString("UTF-8");
                return parse(text, target.toString());
            }
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
