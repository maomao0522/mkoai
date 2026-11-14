package app.mkoai.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * mkoai account sign-in via Supabase Auth (Google OAuth, implicit flow).
 *
 * <p>Tokens are stored in the app's own SharedPreferences (file "movie"). Only the
 * publishable/anon key is embedded in the app (public client key by design); the
 * service_role key is never used here.
 */
final class SupabaseAuth {
    static final String SUPABASE_URL = "https://fdedhzgovuzgbzggejfk.supabase.co";
    static final String ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZkZWRoemdvdnV6Z2J6Z2dlamZrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQwNjY5OTUsImV4cCI6MjEwOTY0Mjk5NX0.6BdIGeD46kYsVquyI-dh-ytH0NRrNs8bb9LH817QrxY";
    static final String REDIRECT_URI = "mkoai://login-callback";

    private static final String KEY_ACCESS = "supabase_access_token";
    private static final String KEY_REFRESH = "supabase_refresh_token";
    private static final String KEY_USER_ID = "supabase_user_id";
    private static final String KEY_EMAIL = "supabase_user_email";
    private static final String KEY_EXPIRES_AT = "supabase_expires_at_ms";

    private SupabaseAuth() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
    }

    static boolean isSignedIn(Context c) {
        return prefs(c).getString(KEY_ACCESS, null) != null
                && prefs(c).getString(KEY_USER_ID, null) != null;
    }

    static String userId(Context c) {
        return prefs(c).getString(KEY_USER_ID, null);
    }

    static String email(Context c) {
        return prefs(c).getString(KEY_EMAIL, null);
    }

    /** Open the system browser for Google sign-in. Supabase redirects back to mkoai://login-callback. */
    static void signInWithGoogle(Activity a) {
        try {
            String url =
                    SUPABASE_URL
                            + "/auth/v1/authorize?provider=google&redirect_to="
                            + URLEncoder.encode(REDIRECT_URI, "UTF-8");
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            a.startActivity(i);
        } catch (Exception e) {
            AppLog.w("SupabaseAuth", "open sign-in failed", e);
            android.widget.Toast.makeText(
                            a, "無法開啟登入頁面", android.widget.Toast.LENGTH_SHORT)
                    .show();
        }
    }

    /** Result of a password sign-in attempt (user-facing Chinese message on failure). */
    static final class PasswordResult {
        final boolean ok;
        final String error;

        PasswordResult(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }
    }

    /** Store a token pair (from the password grant) the same way handleCallback does. */
    private static boolean storeTokens(
            Context c, String access, String refresh, long expiresIn) {
        if (access == null || access.isEmpty()) return false;
        String uid = jwtClaim(access, "sub");
        String mail = jwtClaim(access, "email");
        if (uid == null || uid.isEmpty()) return false;
        prefs(c)
                .edit()
                .putString(KEY_ACCESS, access)
                .putString(KEY_REFRESH, refresh == null ? "" : refresh)
                .putString(KEY_USER_ID, uid)
                .putString(KEY_EMAIL, mail == null ? "" : mail)
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
                .apply();
        return true;
    }

    /**
     * Store a handed-over session (TV QR login: the phone's session is posted
     * to the TV over the LAN). Same storage as handleCallback / password
     * grant; returns false when the session is invalid. Never logs tokens.
     */
    static boolean importSession(Context c, String access, String refresh, long expiresIn) {
        return storeTokens(c, access, refresh, expiresIn);
    }

    /** Current refresh token (may be empty); used to hand the session to the TV. */
    static String refreshToken(Context c) {
        return prefs(c).getString(KEY_REFRESH, "");
    }

    /** Seconds until the current access token expires (3600 when unknown). */
    static long sessionExpiresInSeconds(Context c) {
        long expiresAt = prefs(c).getLong(KEY_EXPIRES_AT, 0);
        if (expiresAt <= 0) return 3600;
        return Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000L);
    }

    /**
     * Sign in with email + password (TV login; a Google-created account has
     * no password until one is set via password recovery). Must be called on
     * a background thread (does network I/O).
     */
    static PasswordResult signInWithPassword(Context c, String email, String password) {
        try {
            URL url = new URL(SUPABASE_URL + "/auth/v1/token?grant_type=password");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject().put("email", email).put("password", password);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                String resp = "";
                try {
                    InputStream err = conn.getErrorStream();
                    if (err != null) resp = readAll(err);
                } catch (Exception ignored) {
                }
                AppLog.w("SupabaseAuth", "password sign-in failed: HTTP " + code + " " + resp, null);
                if (code == 400 || code == 401)
                    return new PasswordResult(false, "帳號或密碼不正確");
                return new PasswordResult(false, "登入失敗（HTTP " + code + "）");
            }
            String resp = readAll(conn.getInputStream());
            JSONObject o = new JSONObject(resp);
            String access = o.optString("access_token", "");
            String refresh = o.optString("refresh_token", "");
            long expiresIn = o.optLong("expires_in", 3600);
            if (!storeTokens(c, access, refresh, expiresIn)) {
                return new PasswordResult(false, "登入失敗：伺服器回應異常");
            }
            return new PasswordResult(true, null);
        } catch (Exception e) {
            AppLog.w("SupabaseAuth", "password sign-in failed", e);
            return new PasswordResult(false, "連不上伺服器，請檢查網路");
        }
    }

    /**
     * Send a password-reset email (for Google-created accounts that have no
     * password yet). Must be called on a background thread. Returns null when
     * the email was sent, a user-facing error string otherwise.
     */
    static String sendPasswordRecovery(Context c, String email) {
        try {
            URL url = new URL(SUPABASE_URL + "/auth/v1/recover");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject().put("email", email);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code == 200) return null;
            AppLog.w("SupabaseAuth", "password recovery failed: HTTP " + code, null);
            return "寄送失敗（HTTP " + code + "）";
        } catch (Exception e) {
            AppLog.w("SupabaseAuth", "password recovery failed", e);
            return "連不上伺服器，請檢查網路";
        }
    }

    /** True when this intent is the Supabase OAuth callback (mkoai://login-callback). */
    static boolean isLoginCallback(Uri uri) {
        return uri != null
                && "mkoai".equals(uri.getScheme())
                && "login-callback".equals(uri.getHost());
    }

    /**
     * Handle the OAuth callback. Supabase returns the tokens in the URL fragment
     * (#access_token=...&refresh_token=...&expires_in=...&token_type=bearer).
     * Returns true when tokens were stored.
     */
    static boolean handleCallback(Context c, Uri uri) {
        if (!isLoginCallback(uri)) return false;
        try {
            String frag = uri.getFragment();
            if (frag == null || frag.isEmpty()) {
                // Some browsers put the tokens in the query instead.
                frag = uri.getEncodedQuery();
            }
            if (frag == null || frag.isEmpty()) return false;
            String access = null, refresh = null;
            long expiresIn = 3600;
            for (String part : frag.split("&")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = Uri.decode(part.substring(0, eq));
                String v = Uri.decode(part.substring(eq + 1));
                if ("access_token".equals(k)) access = v;
                else if ("refresh_token".equals(k)) refresh = v;
                else if ("expires_in".equals(k)) {
                    try {
                        expiresIn = Long.parseLong(v);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (access == null || access.isEmpty()) return false;
            String uid = jwtClaim(access, "sub");
            String mail = jwtClaim(access, "email");
            if (uid == null || uid.isEmpty()) return false;
            long expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
            prefs(c)
                    .edit()
                    .putString(KEY_ACCESS, access)
                    .putString(KEY_REFRESH, refresh == null ? "" : refresh)
                    .putString(KEY_USER_ID, uid)
                    .putString(KEY_EMAIL, mail == null ? "" : mail)
                    .putLong(KEY_EXPIRES_AT, expiresAt)
                    .apply();
            return true;
        } catch (Exception e) {
            AppLog.w("SupabaseAuth", "callback failed", e);
            return false;
        }
    }

    /** Decode one claim from a JWT without verifying (the token came from Supabase over HTTPS). */
    static String jwtClaim(String jwt, String claim) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String payload = parts[1];
            // Base64URL -> Base64
            payload = payload.replace('-', '+').replace('_', '/');
            while (payload.length() % 4 != 0) payload += "=";
            byte[] bytes = Base64.decode(payload, Base64.DEFAULT);
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return o.optString(claim, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Return a valid access token, refreshing it first when expired. Must be called
     * on a background thread (does network I/O). Returns null when signed out or refresh fails.
     */
    static String validAccessToken(Context c) {
        SharedPreferences p = prefs(c);
        String access = p.getString(KEY_ACCESS, null);
        if (access == null) return null;
        long expiresAt = p.getLong(KEY_EXPIRES_AT, 0);
        if (System.currentTimeMillis() < expiresAt - 60_000L) return access;
        // Expired (or about to): refresh.
        String refresh = p.getString(KEY_REFRESH, null);
        if (refresh == null || refresh.isEmpty()) return access; // try the old one anyway
        try {
            URL url = new URL(SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("apikey", ANON_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject().put("refresh_token", refresh);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                AppLog.w("SupabaseAuth", "refresh failed: HTTP " + code, null);
                return access;
            }
            String resp = readAll(conn.getInputStream());
            JSONObject o = new JSONObject(resp);
            String newAccess = o.optString("access_token", "");
            String newRefresh = o.optString("refresh_token", refresh);
            long expiresIn = o.optLong("expires_in", 3600);
            if (newAccess.isEmpty()) return access;
            String uid = jwtClaim(newAccess, "sub");
            String mail = jwtClaim(newAccess, "email");
            SharedPreferences.Editor ed = p.edit().putString(KEY_ACCESS, newAccess);
            ed.putString(KEY_REFRESH, newRefresh);
            ed.putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L);
            if (uid != null && !uid.isEmpty()) ed.putString(KEY_USER_ID, uid);
            if (mail != null && !mail.isEmpty()) ed.putString(KEY_EMAIL, mail);
            ed.apply();
            return newAccess;
        } catch (Exception e) {
            AppLog.w("SupabaseAuth", "refresh failed", e);
            return access;
        }
    }

    static void signOut(Context c) {
        prefs(c)
                .edit()
                .remove(KEY_ACCESS)
                .remove(KEY_REFRESH)
                .remove(KEY_USER_ID)
                .remove(KEY_EMAIL)
                .remove(KEY_EXPIRES_AT)
                .apply();
    }

    static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }
}
