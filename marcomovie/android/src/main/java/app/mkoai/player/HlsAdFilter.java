package app.mkoai.player;

import java.net.URI;
import java.util.*;

/** Conservative VOD-only rewrite. A discontinuity, short segment or new CDN is never ad evidence. */
final class HlsAdFilter {
    static final int MAX_CHARS = 1024 * 1024;
    static final double MAX_POD_SECONDS = 180, MAX_TOTAL_SECONDS = 600;

    static final class Policy {
        final Set<String> hosts = new HashSet<>();
        final List<URI> prefixes = new ArrayList<>();
        Policy(List<String> hosts, List<String> prefixes) {
            for (String value : hosts) {
                if (this.hosts.size() >= 32) break;
                String host = value.trim().toLowerCase(Locale.ROOT);
                try {
                    URI uri = new URI("https://" + host);
                    if (host.equals(uri.getHost()) && uri.getPort() < 0 && uri.getRawUserInfo() == null
                            && uri.getRawQuery() == null && uri.getRawFragment() == null
                            && (uri.getPath() == null || uri.getPath().isEmpty())) this.hosts.add(host);
                } catch (Exception ignored) {}
            }
            for (String value : prefixes) {
                if (this.prefixes.size() >= 32) break;
                try {
                    URI uri = new URI(value.trim()).normalize();
                    if (http(uri) && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                            && uri.getRawFragment() == null && uri.getPath() != null
                            && uri.getPath().length() > 1 && uri.getPath().endsWith("/")) this.prefixes.add(uri);
                } catch (Exception ignored) {}
            }
        }
        boolean matches(String base, String value) {
            try {
                URI uri = new URI(base).resolve(value).normalize();
                if (!http(uri) || uri.getPath() == null || uri.getPath().contains("/../")
                        || uri.getPath().contains("/./") || uri.getPath().endsWith("/..")) return false;
                if (hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) return true;
                for (URI prefix : prefixes) {
                    if (prefix.getScheme().equalsIgnoreCase(uri.getScheme())
                            && prefix.getHost().equalsIgnoreCase(uri.getHost())
                            && prefix.getPort() == uri.getPort()
                            && uri.getPath().startsWith(prefix.getPath())) return true;
                }
            } catch (Exception ignored) {}
            return false;
        }
        static boolean http(URI uri) {
            return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()));
        }
    }

    static final class Result {
        final String text;
        final int removedSegments;
        final double removedSeconds;
        Result(String text, int count, double seconds) {
            this.text = text; removedSegments = count; removedSeconds = seconds;
        }
        boolean changed() { return removedSegments > 0; }
    }
    private static final class Segment {
        final List<String> tags;
        final String uri;
        final double duration;
        final boolean ad;
        Segment(List<String> tags, String uri, double duration, boolean ad) {
            this.tags = new ArrayList<>(tags); this.uri = uri; this.duration = duration; this.ad = ad;
        }
    }
    private static Result unchanged(String input) { return new Result(input, 0, 0); }

    static Result filter(String base, String input, Policy policy) {
        if (input == null || input.length() > MAX_CHARS) return unchanged(input);
        String normalized = input.startsWith("\ufeff") ? input.substring(1) : input;
        if (!normalized.trim().startsWith("#EXTM3U") || !normalized.contains("#EXT-X-ENDLIST")) return unchanged(input);
        // Index-dependent encryption/byte ranges and separate fMP4 maps need their own timeline model.
        for (String marker : new String[]{"#EXT-X-STREAM-INF", "#EXT-X-MEDIA:", "#EXT-X-KEY:",
                "#EXT-X-MAP:", "#EXT-X-BYTERANGE:", "#EXT-X-PART:", "#EXT-X-SKIP:",
                "#EXT-X-I-FRAMES-ONLY", "#EXT-X-DEFINE:", "#EXT-X-START:"}) {
            if (normalized.contains(marker)) return unchanged(input);
        }
        List<String> header = new ArrayList<>(), pending = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        double duration = -1, total = 0, removed = 0, pod = 0, expected = -1;
        boolean cue = false, ended = false;
        int removedCount = 0;
        try {
            for (String raw : normalized.split("\\r?\\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (ended) return unchanged(input);
                if (line.equals("#EXT-X-ENDLIST")) {
                    if (cue || duration >= 0) return unchanged(input);
                    ended = true; continue;
                }
                if (line.startsWith("#EXT-X-CUE-OUT-CONT")) {
                    if (!cue) return unchanged(input);
                    continue;
                }
                if (line.equals("#EXT-X-CUE-OUT") || line.startsWith("#EXT-X-CUE-OUT:")) {
                    if (cue || duration >= 0) return unchanged(input);
                    cue = true; pod = 0; expected = -1;
                    if (line.indexOf(':') >= 0) {
                        String value = line.substring(line.indexOf(':') + 1).trim();
                        if (value.startsWith("DURATION=")) value = value.substring(9).split(",", 2)[0].replace("\"", "").trim();
                        expected = Double.parseDouble(value);
                        if ((Double.isNaN(expected) || Double.isInfinite(expected)) || expected <= 0 || expected > MAX_POD_SECONDS) return unchanged(input);
                    }
                    continue;
                }
                if (line.equals("#EXT-X-CUE-IN") || line.startsWith("#EXT-X-CUE-IN:")) {
                    if (!cue || pod <= 0 || pod > MAX_POD_SECONDS || duration >= 0
                            || (expected >= 0 && Math.abs(expected - pod) > 1)) return unchanged(input);
                    cue = false; continue;
                }
                if (line.startsWith("#EXTINF:")) {
                    if (duration >= 0) return unchanged(input);
                    String value = line.substring(8).split(",", 2)[0];
                    duration = Double.parseDouble(value);
                    if ((Double.isNaN(duration) || Double.isInfinite(duration)) || duration <= 0 || duration > 3600) return unchanged(input);
                    pending.add(raw); continue;
                }
                if (line.startsWith("#")) {
                    if (segments.isEmpty() && duration < 0 && !cue && headerTag(line)) header.add(raw);
                    else pending.add(raw);
                    continue;
                }
                if (duration < 0) return unchanged(input);
                boolean ad = cue || (policy != null && policy.matches(base, line));
                total += duration;
                if (Double.isNaN(total) || Double.isInfinite(total) || total > 86400) return unchanged(input);
                if (cue) pod += duration;
                if (ad) { removed += duration; removedCount++; }
                segments.add(new Segment(pending, raw, duration, ad));
                pending.clear(); duration = -1;
            }
        } catch (RuntimeException malformed) { return unchanged(input); }
        if (!ended || cue || duration >= 0 || removedCount == 0 || removedCount == segments.size()
                || removed > MAX_TOTAL_SECONDS || removed > total * 0.20) return unchanged(input);
        double run = 0;
        for (Segment s : segments) {
            run = s.ad ? run + s.duration : 0;
            if (run > MAX_POD_SECONDS) return unchanged(input);
        }
        StringBuilder out = new StringBuilder(input.length());
        for (String line : header) out.append(line).append('\n');
        boolean gap = false;
        for (Segment s : segments) {
            if (s.ad) { gap = true; continue; }
            if (gap && !s.tags.contains("#EXT-X-DISCONTINUITY")) out.append("#EXT-X-DISCONTINUITY\n");
            for (String tag : s.tags) out.append(tag).append('\n');
            out.append(s.uri).append('\n'); gap = false;
        }
        for (String line : pending) out.append(line).append('\n');
        out.append("#EXT-X-ENDLIST\n");
        return new Result(out.toString(), removedCount, removed);
    }
    private static boolean headerTag(String line) {
        return line.equals("#EXTM3U") || line.startsWith("#EXT-X-VERSION:")
                || line.startsWith("#EXT-X-TARGETDURATION:") || line.startsWith("#EXT-X-MEDIA-SEQUENCE:")
                || line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")
                || line.startsWith("#EXT-X-PLAYLIST-TYPE:") || line.equals("#EXT-X-INDEPENDENT-SEGMENTS");
    }
}
