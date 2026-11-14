package app.mkoai.player;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * mkoai 2.0.54 TV QR login checks (plain JVM, no device). Run:
 * javac -encoding UTF-8 -cp <org.json jar> -d <out> \
 *   shared/java/com/pilipala/source/Warehouse.java \
 *   android/src/main/java/app/mkoai/player/{Cms,TvPush,TvLogin,ConfigTransferServer,TvLoginChecks}.java
 * java -cp <out>:<org.json jar> app.mkoai.player.TvLoginChecks
 *
 * The live /login server tests only run when the VM lets a local
 * ServerSocket accept a local connection (loopbackWorks probe); this VM
 * intercepts loopback TCP, so they usually SKIP here and must be field-tested
 * on the user's real phone + TV on the same Wi-Fi.
 */
public final class TvLoginChecks {
    static int checks;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        checks++;
        System.out.println("PASS " + name);
    }

    static String fakeJwt(String payloadJson) {
        String h =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"none\",\"typ\":\"JWT\"}"
                                        .getBytes(StandardCharsets.UTF_8));
        String p =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return h + "." + p + ".sig";
    }

    static final class Recorder implements ConfigTransferServer.LoginImporter {
        String lastAccess, lastRefresh;
        long lastExpiresIn = -1;
        int calls;

        public String applyLogin(String access, String refresh, long expiresIn) throws Exception {
            calls++;
            lastAccess = access;
            lastRefresh = refresh;
            lastExpiresIn = expiresIn;
            return "maotai0522@gmail.com";
        }
    }

    /** VM sanity probe: can a local ServerSocket accept a local connection? */
    static boolean loopbackWorks() {
        ServerSocket probe = null;
        try {
            probe = new ServerSocket();
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            final ServerSocket p = probe;
            final AtomicReference<String> got = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try (Socket c = p.accept()) {
                    got.set(
                            new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))
                                    .readLine());
                } catch (Exception ignored) {
                }
            });
            t.setDaemon(true);
            t.start();
            try (Socket out = new Socket()) {
                out.connect(new InetSocketAddress("127.0.0.1", p.getLocalPort()), 2000);
                out.setSoTimeout(2000);
                out.getOutputStream().write("ping\n".getBytes("UTF-8"));
                out.getOutputStream().flush();
            }
            t.join(3000);
            return "ping".equals(got.get());
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (probe != null) probe.close();
            } catch (Exception ignored) {
            }
        }
    }

    static String postLogin(int port, String body, String contentType) throws Exception {
        byte[] bytes = body.getBytes("UTF-8");
        HttpURLConnection conn =
                (HttpURLConnection)
                        new URL("http://127.0.0.1:" + port + "/login").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setFixedLengthStreamingMode(bytes.length);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String reply = "";
        if (in != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            reply = buf.toString("UTF-8");
        }
        conn.disconnect();
        return code + "|" + reply;
    }

    public static void main(String[] args) throws Exception {
        String key = "abcdef0123456789abcdef0123456789";
        String access = fakeJwt("{\"sub\":\"user-1\",\"exp\":9999999999}");
        String refresh = "refresh-token-abc";

        // ---- QR build / parse ----
        String qr = TvLogin.buildQrContent("192.168.1.20", 8901, key);
        check(qr.equals("mkoai://tv-login?ip=192.168.1.20&port=8901&code=" + key),
                "QR content is mkoai://tv-login?ip=&port=&code=");
        TvLogin.Target t = TvLogin.parseQrContent(qr);
        check(t != null && t.ip.equals("192.168.1.20") && t.port == 8901 && t.code.equals(key),
                "QR round-trip parses ip/port/code");
        TvLogin.Target httpForm =
                TvLogin.parseQrContent("http://192.168.1.20:8901/login?code=" + key);
        check(httpForm != null
                        && httpForm.ip.equals("192.168.1.20")
                        && httpForm.port == 8901
                        && httpForm.code.equals(key),
                "http login URL parses");
        check(TvLogin.parseQrContent("hello") == null, "plain text is not a login QR");
        check(TvLogin.parseQrContent("mkoai://tv-push?ip=1.2.3.4&port=8901&token=" + key) == null,
                "a push QR is not a login QR");
        check(TvLogin.parseQrContent("mkoai://tv-login?ip=1.2.3.4") == null,
                "QR missing port/code is rejected");
        check(TvLogin.parseQrContent("mkoai://tv-login?ip=1.2.3.4&port=0&code=x") == null,
                "QR with port 0 is rejected");
        check(TvLogin.parseQrContent(null) == null, "null QR is rejected");
        check(TvLogin.parseQrContent("mkoai://tv-login?ip=999.999.1.1&port=8901&code=x") == null,
                "QR with a bad IP is rejected");
        check(TvLogin.parseQrContent("http://999.999.1.1:8901/login?code=x") == null,
                "http login URL with a bad IP is rejected");
        check(TvLogin.validIp("192.168.1.20") && !TvLogin.validIp("256.1.1.1")
                        && !TvLogin.validIp("1.2.3") && !TvLogin.validIp("a.b.c.d"),
                "validIp accepts a dotted quad and rejects the rest");

        // ---- pairing code matches the push one (same token space) ----
        check(TvLogin.pairingCode(key).equals("ABCDEF01"), "pairing code is first 8 upper");
        check(TvLogin.codeMatches(key, key), "full code matches");
        check(TvLogin.codeMatches("abcdef01", key), "8-char pairing code matches");
        check(!TvLogin.codeMatches("zzzzzzzz", key), "wrong code is rejected");

        // ---- JWT sub + session validation ----
        check(TvLogin.jwtSub(access).equals("user-1"), "jwtSub decodes the sub claim");
        check(TvLogin.jwtSub("garbage") == null, "jwtSub is null for a non-JWT");
        check(TvLogin.jwtSub(fakeJwt("{\"exp\":123}")) == null,
                "jwtSub is null when the payload has no sub");
        check(TvLogin.validateSession(access, refresh) == null,
                "a JWT access + refresh session is valid");
        check(TvLogin.validateSession("not-a-jwt", refresh) != null,
                "a non-JWT access token is rejected");
        check(TvLogin.validateSession(fakeJwt("{\"exp\":123}"), refresh) != null,
                "a JWT without sub is rejected");
        check(TvLogin.validateSession(access, "") == null,
                "an empty refresh token is accepted (TV can still use the access token)");
        check(TvLogin.validateSession(access, null) == null,
                "a null refresh token is accepted (same reason)");
        check(TvLogin.validateSession(null, refresh) != null, "a null access token is rejected");
        check(TvLogin.validateSession("", refresh) != null, "an empty access token is rejected");
        check(TvLogin.MAX_LOGIN_BYTES == 16384, "login body cap is 16KB");

        if (loopbackWorks()) {
            // ---- live server: login, single-use, wrong code, bad session, expiry ----
            Recorder rec = new Recorder();
            ConfigTransferServer server =
                    new ConfigTransferServer(rec, new InetSocketAddress("127.0.0.1", 0));
            int port = server.socket.getLocalPort();
            String token = server.key();
            check(server.loginAddress("127.0.0.1").equals("http://127.0.0.1:" + port + "/login"),
                    "loginAddress is http://host:port/login");
            String body =
                    new JSONObject()
                            .put("code", token)
                            .put("access_token", access)
                            .put("refresh_token", refresh)
                            .put("expires_in", 3600)
                            .toString();
            String r1 = postLogin(port, body, "application/json");
            check(r1.startsWith("200|"), "valid login returns 200: " + r1);
            check(new JSONObject(r1.substring(4)).optBoolean("ok", false),
                    "valid login reply says ok");
            check(r1.contains("maotai0522@gmail.com"), "valid login reply names the email");
            check(rec.calls == 1
                            && access.equals(rec.lastAccess)
                            && refresh.equals(rec.lastRefresh)
                            && rec.lastExpiresIn == 3600,
                    "importer received the access + refresh tokens and expiry");
            String r2 = postLogin(port, body, "application/json");
            check(r2.startsWith("409|"), "second login on the same code returns 409");
            check(rec.calls == 1, "single-use code: importer not called twice");
            server.close();

            Recorder rec2 = new Recorder();
            ConfigTransferServer server2 =
                    new ConfigTransferServer(rec2, new InetSocketAddress("127.0.0.1", 0));
            int port2 = server2.socket.getLocalPort();
            String wrongBody =
                    new JSONObject()
                            .put("code", "00000000000000000000000000000000")
                            .put("access_token", access)
                            .put("refresh_token", refresh)
                            .put("expires_in", 3600)
                            .toString();
            String r3 = postLogin(port2, wrongBody, "application/json");
            check(r3.startsWith("401|"), "wrong code returns 401");
            check(rec2.calls == 0, "wrong code never reaches the importer");
            String badSessionBody =
                    new JSONObject()
                            .put("code", server2.key())
                            .put("access_token", "not-a-jwt")
                            .put("refresh_token", refresh)
                            .put("expires_in", 3600)
                            .toString();
            String r4 = postLogin(port2, badSessionBody, "application/json");
            check(r4.startsWith("400|"), "bad session returns 400");
            String r5 = postLogin(port2, body.replace(token, server2.key()), "text/plain");
            check(r5.startsWith("415|"), "non-JSON content type returns 415");
            String r6 = postLogin(port2, "{not json", "application/json");
            check(r6.startsWith("400|"), "malformed JSON returns 400");
            try (Socket raw = new Socket("127.0.0.1", port2)) {
                raw.getOutputStream()
                        .write(("POST /login HTTP/1.1\r\nHost: 127.0.0.1:"
                                        + port2
                                        + "\r\nContent-Type: application/json\r\nContent-Length: 20000\r\n\r\n")
                                .getBytes("US-ASCII"));
                raw.getOutputStream().flush();
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(raw.getInputStream(), "US-ASCII"));
                String first = in.readLine();
                check(first != null && first.contains("413"), "oversized body returns 413");
            }
            server2.close();

            Recorder rec3 = new Recorder();
            ConfigTransferServer server3 =
                    new ConfigTransferServer(rec3, 200, new InetSocketAddress("127.0.0.1", 0));
            int port3 = server3.socket.getLocalPort();
            Thread.sleep(400);
            String expBody =
                    new JSONObject()
                            .put("code", server3.key())
                            .put("access_token", access)
                            .put("refresh_token", refresh)
                            .put("expires_in", 3600)
                            .toString();
            String r7 = postLogin(port3, expBody, "application/json");
            check(r7.startsWith("410|"), "expired code returns 410");
            check(rec3.calls == 0, "expired code never reaches the importer");
            server3.close();
        } else {
            System.out.println(
                    "SKIP live HTTP tests: this VM intercepts loopback TCP "
                            + "(a local ServerSocket never receives local connections); "
                            + "the /login route / code / session / single-use / expiry "
                            + "paths are covered by the static checks and must be field-tested "
                            + "on the user's real phone + TV on the same Wi-Fi.");
        }

        System.out.println(checks + " TvLogin checks passed");
    }
}
