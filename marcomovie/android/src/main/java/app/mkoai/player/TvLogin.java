package app.mkoai.player;

import java.util.Locale;
import org.json.JSONObject;

/**
 * TV QR login (phone scans the TV's QR code; the TV signs in as the phone's
 * account). Pure Java + org.json, no Android classes, so it is unit-testable
 * on the JVM. The session itself is stored by SupabaseAuth.importSession on
 * the TV; this class only builds/parses the QR payload and validates the
 * session shape (the access token must be a JWT with a "sub" claim). Tokens
 * are never logged or written to any file here.
 */
public final class TvLogin {
    /** Max accepted /login body (two JWTs are ~1-2 KB). */
    public static final int MAX_LOGIN_BYTES = 16384;

    /** Decoded login target (from the TV's QR code). */
    public static final class Target {
        public final String ip;
        public final int port;
        public final String code;

        Target(String ip, int port, String code) {
            this.ip = ip;
            this.port = port;
            this.code = code;
        }
    }

    private TvLogin() {}

    /** QR payload the TV displays: {@code mkoai://tv-login?ip=&port=&code=}. */
    public static String buildQrContent(String ip, int port, String code) {
        return "mkoai://tv-login?ip=" + ip + "&port=" + port + "&code=" + code;
    }

    /**
     * Parses a scanned QR payload. Accepts the {@code mkoai://tv-login} form
     * (from the TV's QR code) and the {@code http://<ip>:<port>/login?code=...}
     * URL form (for manual entry from the TV's address line). Returns {@code
     * null} when the text is not a recognisable login address.
     */
    public static Target parseQrContent(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        try {
            if (t.startsWith("mkoai://tv-login?")) {
                String query = t.substring("mkoai://tv-login?".length());
                String ip = queryValue(query, "ip");
                String port = queryValue(query, "port");
                String code = queryValue(query, "code");
                if (ip == null || port == null || code == null || !validIp(ip)) return null;
                int p = Integer.parseInt(port);
                if (p < 1 || p > 65535 || code.isEmpty()) return null;
                return new Target(ip, p, code);
            }
            java.net.URL url = new java.net.URL(t);
            String host = url.getHost();
            int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
            String path = url.getPath();
            if (host == null || host.isEmpty() || port < 1 || port > 65535 || !validIp(host))
                return null;
            if (path != null && path.equals("/login")) {
                String query = url.getQuery();
                String code = query == null ? null : queryValue(query, "code");
                if (code == null || code.isEmpty()) return null;
                return new Target(host, port, code);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** A dotted-quad address (each octet 0-255); the TV's LAN IP. */
    static boolean validIp(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            try {
                if (Integer.parseInt(part) > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
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

    /**
     * Validates a handed-over Supabase session. Returns null when the session
     * looks usable, or a user-facing error string otherwise. The access token
     * must be a JWT whose payload carries a non-empty "sub" claim (the user
     * id); the refresh token may be empty (the TV can still use the access
     * token until it expires). The login code itself is checked separately
     * (via {@link TvPush#tokenMatches} against the server key).
     */
    public static String validateSession(String access, String refresh) {
        if (access == null || access.trim().isEmpty()) return "工作階段無效：沒有 access token";
        String sub = jwtSub(access.trim());
        if (sub == null || sub.isEmpty()) return "工作階段無效：無法辨識帳號";
        return null;
    }

    /** The "sub" (user id) claim of a JWT, or null when it cannot be read. */
    public static String jwtSub(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return null;
            byte[] payload = base64UrlDecode(parts[1]);
            if (payload == null) return null;
            JSONObject o = new JSONObject(new String(payload, "UTF-8"));
            String sub = o.optString("sub", null);
            return (sub == null || sub.isEmpty()) ? null : sub;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Minimal Base64URL decoder (pure Java: java.util.Base64 needs API 26+,
     * and this app's minSdk is 23; android.util.Base64 does not exist on the
     * JVM used by the unit tests).
     */
    static byte[] base64UrlDecode(String s) {
        if (s == null) return null;
        String t = s.replace('-', '+').replace('_', '/');
        while (t.length() % 4 != 0) t += "=";
        int len = t.length();
        if (len == 0) return new byte[0];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < len; i += 4) {
            int a = base64Value(t.charAt(i));
            int b = base64Value(t.charAt(i + 1));
            int c = base64Value(t.charAt(i + 2));
            int d = base64Value(t.charAt(i + 3));
            if (a < 0 || b < 0 || c < 0 || d < 0) return null;
            out.write((a << 2) | (b >> 4));
            if (t.charAt(i + 2) != '=') out.write(((b & 0x0f) << 4) | (c >> 2));
            if (t.charAt(i + 3) != '=') out.write(((c & 0x03) << 6) | d);
        }
        return out.toByteArray();
    }

    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        if (c == '=') return 0;
        return -1;
    }

    /** Short pairing code shown on the TV for manual entry (first 8 chars). */
    public static String pairingCode(String key) {
        return TvPush.pairingCode(key);
    }

    /** Full key or its 8-char pairing-code prefix (manual entry). */
    public static boolean codeMatches(String supplied, String key) {
        return TvPush.tokenMatches(supplied, key);
    }
}
