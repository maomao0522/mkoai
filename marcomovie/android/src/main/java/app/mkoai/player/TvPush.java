package app.mkoai.player;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Phone-to-TV source push over the same Wi-Fi (no cloud, no SQL).
 *
 * <p>TV shows a QR code ({@link #buildQrContent}) with its LAN address, port and
 * one-time token. The phone scans it and POSTs JSON to the TV's {@code /push}
 * endpoint. Payload is either one source URL (added as a single CMS site) or a
 * whole TVBox JSON config (its sites are merged into the TV's current config,
 * deduplicated by resolved {@code api}). Pure Java + org.json, no Android
 * classes, so it is unit-testable on the JVM.
 */
public final class TvPush {
    /** Preferred LAN port for the push receiver; falls back to an ephemeral port. */
    public static final int PUSH_PORT = 8901;
    /** Max accepted push body (a full tvbox config is a few KB). */
    public static final int MAX_PUSH_BYTES = 1024 * 1024;

    /** Result of merging pushed sites into the current config. */
    public static final class MergeResult {
        /** Serialized merged config JSON (ready for {@link Cms#parseConfig}). */
        public final String raw;
        /** Sites actually appended. */
        public final int added;
        /** Sites skipped because their api was already present. */
        public final int duplicates;
        /** Total sites in the merged config. */
        public final int total;

        MergeResult(String raw, int added, int duplicates, int total) {
            this.raw = raw;
            this.added = added;
            this.duplicates = duplicates;
            this.total = total;
        }
    }

    /** Decoded push target (from the TV's QR code). */
    public static final class Target {
        public final String ip;
        public final int port;
        public final String token;

        Target(String ip, int port, String token) {
            this.ip = ip;
            this.port = port;
            this.token = token;
        }
    }

    private TvPush() {}

    /** QR payload the TV displays: {@code mkoai://tv-push?ip=&port=&token=}. */
    public static String buildQrContent(String ip, int port, String token) {
        return "mkoai://tv-push?ip=" + ip + "&port=" + port + "&token=" + token;
    }

    /**
     * Parses a scanned QR payload. Accepts the {@code mkoai://tv-push} form,
     * the legacy configure URL ({@code http://ip:port/configure/<key>}, token is
     * the key) and {@code http://ip:port/push?token=...}. Returns null when
     * the text is not a recognisable TV push address.
     */
    public static Target parseQrContent(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        try {
            if (t.startsWith("mkoai://tv-push?")) {
                String query = t.substring("mkoai://tv-push?".length());
                String ip = queryValue(query, "ip");
                String port = queryValue(query, "port");
                String token = queryValue(query, "token");
                if (ip == null || port == null || token == null) return null;
                int p = Integer.parseInt(port);
                if (p < 1 || p > 65535 || token.isEmpty()) return null;
                return new Target(ip, p, token);
            }
            URL url = new URL(t);
            String host = url.getHost();
            int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
            String path = url.getPath();
            if (host == null || host.isEmpty() || port < 1 || port > 65535) return null;
            if (path != null && path.startsWith("/configure/")) {
                String key = path.substring("/configure/".length());
                if (key.isEmpty()) return null;
                return new Target(host, port, key);
            }
            if (path != null && path.equals("/push")) {
                String query = url.getQuery();
                String token = query == null ? null : queryValue(query, "token");
                if (token == null || token.isEmpty()) return null;
                return new Target(host, port, token);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String queryValue(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!pair.substring(0, eq).equals(name)) continue;
            try {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (Exception ignored) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** Short pairing code shown on the TV for manual entry (first 8 chars). */
    public static String pairingCode(String key) {
        if (key == null) return "";
        return key.length() >= 8
                ? key.substring(0, 8).toUpperCase(Locale.ROOT)
                : key.toUpperCase(Locale.ROOT);
    }

    /**
     * Accepts the full 32-hex token, or its 8-char pairing-code prefix (manual
     * entry). Anything else is rejected.
     */
    public static boolean tokenMatches(String supplied, String key) {
        if (supplied == null || key == null) return false;
        if (supplied.equals(key)) return true;
        return key.length() >= 8 && supplied.equalsIgnoreCase(key.substring(0, 8));
    }

    /** Validates a pushed source URL: http(s) only, bounded length, no CR/LF. */
    public static void validateUrl(String url) throws IOException {
        if (url == null) throw new IOException("請輸入有效的 HTTP 或 HTTPS 源網址");
        String u = url.trim();
        if (u.isEmpty()
                || u.length() > 8192
                || u.indexOf('\r') >= 0
                || u.indexOf('\n') >= 0) throw new IOException("請輸入有效的 HTTP 或 HTTPS 源網址");
        try {
            URL parsed = new URL(u);
            if (!parsed.getProtocol().equals("http") && !parsed.getProtocol().equals("https"))
                throw new IOException("請輸入有效的 HTTP 或 HTTPS 源網址");
            if (parsed.getHost() == null || parsed.getHost().isEmpty())
                throw new IOException("請輸入有效的 HTTP 或 HTTPS 源網址");
        } catch (MalformedURLException e) {
            throw new IOException("請輸入有效的 HTTP 或 HTTPS 源網址");
        }
    }

    /** Display name for a pushed URL: its host, or 「手機推送」 when unknown. */
    public static String siteNameForUrl(String url) {
        try {
            String host = new URL(url.trim()).getHost();
            if (host != null && !host.isEmpty()) return host;
        } catch (Exception ignored) {
        }
        return "手機推送";
    }

    /** Stable-ish key for a pushed URL site (parseConfig dedups on collision). */
    public static String siteKeyForUrl(String url) {
        String host = siteNameForUrl(url).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        if (host.isEmpty() || host.equals("-")) host = "push";
        if (host.length() > 40) host = host.substring(0, 40);
        return "mkoai-push-" + host + "-" + Integer.toHexString(url.trim().hashCode());
    }

    /** Single-site TVBox JSON for a pushed URL. */
    public static String siteJsonForUrl(String url) throws IOException, JSONException {
        validateUrl(url);
        JSONObject site = new JSONObject();
        site.put("key", siteKeyForUrl(url));
        site.put("name", siteNameForUrl(url));
        site.put("type", 0);
        site.put("api", url.trim());
        return new JSONObject().put("sites", new JSONArray().put(site)).toString();
    }

    /**
     * Merges pushed sites into the current config.
     *
     * @param currentRaw current config JSON (empty when the TV has no config)
     * @param type "url" (data is one source URL) or "json" (data is TVBox JSON)
     * @param data the pushed payload
     * @param origin origin used to resolve relative apis (current config origin)
     * @return merged config JSON plus added / duplicate counts
     * @throws Exception when the payload is not a valid config or has no sites
     */
    public static MergeResult merge(String currentRaw, String type, String data, String origin)
            throws Exception {
        if (data == null || data.trim().isEmpty()) throw new IOException("推送內容為空");
        if (data.length() > MAX_PUSH_BYTES) throw new IOException("推送內容過大");
        String pushedJson;
        if ("url".equals(type)) {
            pushedJson = siteJsonForUrl(data);
        } else if ("json".equals(type)) {
            pushedJson = data.trim();
        } else {
            throw new IOException("不支援的推送類型");
        }
        // Validate with the real config parser (also caps sites at 500).
        Cms.Config pushed = Cms.parseConfig(pushedJson, origin);
        if (pushed.sites.isEmpty()) throw new IOException("推送內容沒有可識別的站點");

        // Existing resolved apis (from the already-parsed current config).
        Set<String> existingApis = new HashSet<>();
        if (currentRaw != null && !currentRaw.trim().isEmpty()) {
            Cms.Config current = Cms.parseConfig(currentRaw, origin);
            for (Cms.Site s : current.sites)
                if (s.api != null && !s.api.isEmpty()) existingApis.add(s.api);
        }

        // Raw site objects of the pushed payload, in original order.
        List<JSONObject> pushedRawSites = rawSiteObjects(pushedJson);
        if (pushedRawSites.isEmpty()) {
            // Parser synthesised sites from a shape without a plain sites
            // array (e.g. warehouse catalog): re-emit from parsed sites.
            for (Cms.Site s : pushed.sites) pushedRawSites.add(siteToJson(s));
        }

        // Merged root: keep the current config's other top-level keys
        // (parses, spider, lives, ...) and replace only its sites array.
        JSONObject mergedRoot;
        JSONArray mergedSites;
        if (currentRaw != null && !currentRaw.trim().isEmpty()) {
            JSONObject current = new JSONObject(currentRaw);
            if (current.has("sites") && current.optJSONArray("sites") != null) {
                mergedRoot = current;
                mergedSites = current.getJSONArray("sites");
            } else {
                mergedRoot = new JSONObject();
                mergedSites = new JSONArray();
                for (Cms.Site s : Cms.parseConfig(currentRaw, origin).sites)
                    mergedSites.put(siteToJson(s));
                mergedRoot.put("sites", mergedSites);
            }
        } else {
            mergedRoot = new JSONObject();
            mergedSites = new JSONArray();
            mergedRoot.put("sites", mergedSites);
        }

        int added = 0, duplicates = 0;
        for (JSONObject rawSite : pushedRawSites) {
            String api = Cms.resolve(origin, rawSite.optString("api", ""));
            if (api == null || api.isEmpty() || existingApis.contains(api)) {
                duplicates++;
                continue;
            }
            if (!rawSite.has("key") || rawSite.optString("key", "").isEmpty())
                rawSite.put("key", "site-" + mergedSites.length());
            mergedSites.put(rawSite);
            existingApis.add(api);
            added++;
        }
        return new MergeResult(
                mergedRoot.toString(), added, duplicates, mergedSites.length());
    }

    /** Raw site JSON objects of a config (its "sites" array, or the root itself). */
    static List<JSONObject> rawSiteObjects(String configJson) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        JSONObject root = new JSONObject(configJson);
        JSONArray sites = root.optJSONArray("sites");
        if (sites != null) {
            for (int i = 0; i < sites.length(); i++) {
                JSONObject j = sites.optJSONObject(i);
                if (j != null) out.add(j);
            }
        } else if (root.has("api")) {
            out.add(root);
        }
        return out;
    }

    /** Best-effort re-emit of a parsed site back to TVBox JSON. */
    static JSONObject siteToJson(Cms.Site s) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("key", s.key == null ? "" : s.key);
        j.put("name", s.name == null ? (s.key == null ? "" : s.key) : s.name);
        j.put("type", s.type);
        j.put("api", s.api == null ? "" : s.api);
        if (s.playUrl != null && !s.playUrl.isEmpty()) j.put("playUrl", s.playUrl);
        if (s.jar != null && !s.jar.isEmpty()) j.put("jar", s.jar);
        j.put("searchable", s.searchable ? 1 : 0);
        if (s.ext instanceof JSONObject) j.put("ext", (JSONObject) s.ext);
        else if (s.ext instanceof String) j.put("ext", (String) s.ext);
        String ua = s.headers.get("User-Agent");
        if (ua != null && !ua.equals(Cms.USER_AGENT)) j.put("ua", ua);
        return j;
    }
}
