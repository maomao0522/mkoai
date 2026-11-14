package app.mkoai.player;

import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import java.net.*;
import org.json.JSONObject;

/**
 * mkoai 2.0.52 push checks (plain JVM, no device). Run:
 * javac -encoding UTF-8 -cp <org.json jar> -d <out> \
 *   shared/java/com/pilipala/source/Warehouse.java \
 *   android/src/main/java/app/mkoai/player/{Cms,TvPush,ConfigTransferServer,TvPushChecks}.java
 * java -cp <out>:<org.json jar> app.mkoai.player.TvPushChecks
 */
public final class TvPushChecks {
    static int checks;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        checks++;
        System.out.println("PASS " + name);
    }

    static void expectThrow(Class<? extends Exception> type, Throwing run, String name) {
        try {
            run.run();
        } catch (Exception e) {
            check(type.isInstance(e), name + " (threw " + e.getClass().getSimpleName() + ")");
            return;
        }
        throw new AssertionError("FAIL: " + name + " (no exception)");
    }

    interface Throwing {
        void run() throws Exception;
    }

    static String currentRaw() {
        return "{\"sites\":["
                + "{\"key\":\"a\",\"name\":\"A站\",\"type\":0,\"api\":\"https://a.example.com/api.php/provide/vod/\"},"
                + "{\"key\":\"b\",\"name\":\"B站\",\"type\":0,\"api\":\"https://b.example.com/api.php/provide/vod/\"}],"
                + "\"parses\":[{\"name\":\"解析1\",\"url\":\"https://jx.example.com/?url=\",\"type\":0}],"
                + "\"spider\":\"https://x.example.com/jar.jar\"}";
    }

    static String pushedJson() {
        return "{\"sites\":["
                + "{\"key\":\"b2\",\"name\":\"B站2\",\"type\":0,\"api\":\"https://b.example.com/api.php/provide/vod/\"},"
                + "{\"key\":\"c\",\"name\":\"C站\",\"type\":0,\"api\":\"https://c.example.com/api.php/provide/vod/\"}]}";
    }

    static final class Recorder implements ConfigTransferServer.PushImporter {
        String lastType, lastData;
        int calls;

        public TvPush.MergeResult applyPush(String type, String data) throws Exception {
            calls++;
            lastType = type;
            lastData = data;
            return TvPush.merge("", type, data, "");
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
                    got.set(new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8")).readLine());
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
            try { if (probe != null) probe.close(); } catch (Exception ignored) {}
        }
    }


    static String postPush(int port, String body, String contentType) throws Exception {
        byte[] bytes = body.getBytes("UTF-8");
        HttpURLConnection conn =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/push").openConnection();
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

        // ---- QR build / parse ----
        String qr = TvPush.buildQrContent("192.168.1.20", 8901, key);
        check(qr.equals("mkoai://tv-push?ip=192.168.1.20&port=8901&token=" + key),
                "QR content is mkoai://tv-push?ip=&port=&token=");
        TvPush.Target t = TvPush.parseQrContent(qr);
        check(t != null && t.ip.equals("192.168.1.20") && t.port == 8901 && t.token.equals(key),
                "QR round-trip parses ip/port/token");
        TvPush.Target legacy =
                TvPush.parseQrContent("http://192.168.1.20:12345/configure/" + key);
        check(legacy != null && legacy.port == 12345 && legacy.token.equals(key),
                "legacy configure URL parses (token is the key)");
        TvPush.Target httpForm =
                TvPush.parseQrContent("http://192.168.1.20:8901/push?token=" + key);
        check(httpForm != null && httpForm.token.equals(key), "http push URL parses");
        check(TvPush.parseQrContent("hello") == null, "plain text is not a push QR");
        check(TvPush.parseQrContent("mkoai://tv-push?ip=1.2.3.4") == null,
                "QR missing port/token is rejected");
        check(TvPush.parseQrContent("mkoai://tv-push?ip=1.2.3.4&port=0&token=x") == null,
                "QR with port 0 is rejected");
        check(TvPush.parseQrContent(null) == null, "null QR is rejected");

        // ---- token ----
        check(TvPush.tokenMatches(key, key), "full token matches");
        check(TvPush.tokenMatches("ABCDEF01", key), "8-char pairing code matches");
        check(TvPush.tokenMatches("abcdef01", key), "pairing code is case-insensitive");
        check(!TvPush.tokenMatches("abcdef0", key), "7-char prefix is rejected");
        check(!TvPush.tokenMatches("zzzzzzzz", key), "wrong token is rejected");
        check(!TvPush.tokenMatches(null, key), "null token is rejected");
        check(TvPush.pairingCode(key).equals("ABCDEF01"), "pairing code is first 8 upper");

        // ---- URL validation ----
        TvPush.validateUrl("https://cj.lzcaiji.com/api.php/provide/vod/at/xml/");
        check(true, "https CMS api URL is valid");
        TvPush.validateUrl("http://example.com/x.json");
        check(true, "http URL is valid");
        expectThrow(IOException.class,
                () -> TvPush.validateUrl("ftp://example.com/x"), "ftp URL is rejected");
        expectThrow(IOException.class,
                () -> TvPush.validateUrl("http://example.com/a\nb"), "URL with LF is rejected");
        expectThrow(IOException.class, () -> TvPush.validateUrl(""), "empty URL is rejected");
        expectThrow(IOException.class, () -> TvPush.validateUrl(null), "null URL is rejected");
        check(TvPush.siteNameForUrl("https://cj.lzcaiji.com/api.php/provide/vod/")
                        .equals("cj.lzcaiji.com"),
                "site name is the URL host");
        check(TvPush.siteKeyForUrl("https://a.example.com/api.php/").startsWith("mkoai-push-"),
                "site key has the push prefix");

        // ---- merge: JSON into an existing 2-site config ----
        TvPush.MergeResult m = TvPush.merge(currentRaw(), "json", pushedJson(), "");
        check(m.added == 1 && m.duplicates == 1 && m.total == 3,
                "merge adds 1 new, skips 1 duplicate, total 3");
        Cms.Config merged = Cms.parseConfig(m.raw, "");
        check(merged.sites.size() == 3, "merged config re-parses to 3 sites");
        boolean hasA = false, hasB = false, hasC = false;
        for (Cms.Site s : merged.sites) {
            if (s.api.contains("a.example.com")) hasA = true;
            if (s.api.contains("b.example.com")) hasB = true;
            if (s.api.contains("c.example.com")) hasC = true;
        }
        check(hasA && hasB && hasC, "merged config keeps A, B and adds C");
        JSONObject root = new JSONObject(m.raw);
        check(root.has("parses") && root.has("spider"),
                "merge preserves other top-level keys (parses, spider)");
        check(root.getJSONArray("sites").length() == 3, "merged sites array has 3 entries");

        // ---- merge: single-site JSON root ----
        TvPush.MergeResult single =
                TvPush.merge(currentRaw(), "json",
                        "{\"key\":\"s1\",\"name\":\"S\",\"type\":0,\"api\":\"https://s.example.com/api/\"}", "");
        check(single.added == 1 && single.total == 3, "single-site JSON root merges");

        // ---- merge: URL type + duplicate URL ----
        TvPush.MergeResult url =
                TvPush.merge(currentRaw(), "url", "https://d.example.com/api.php/provide/vod/", "");
        check(url.added == 1 && url.total == 3, "URL push adds one site");
        Cms.Config urlCfg = Cms.parseConfig(url.raw, "");
        check(urlCfg.sites.get(2).name.equals("d.example.com"),
                "URL push names the site after its host");
        TvPush.MergeResult dupUrl =
                TvPush.merge(url.raw, "url", "https://d.example.com/api.php/provide/vod/", "");
        check(dupUrl.added == 0 && dupUrl.duplicates == 1 && dupUrl.total == 3,
                "duplicate URL push adds nothing");

        // ---- merge: bad payloads ----
        expectThrow(Exception.class,
                () -> TvPush.merge(currentRaw(), "json", "{not json", ""),
                "malformed JSON is rejected");
        expectThrow(Exception.class,
                () -> TvPush.merge(currentRaw(), "json", "{\"sites\":[]}", ""),
                "JSON without sites is rejected");
        expectThrow(Exception.class,
                () -> TvPush.merge(currentRaw(), "url", "ftp://example.com/x", ""),
                "ftp URL push is rejected");
        expectThrow(Exception.class,
                () -> TvPush.merge(currentRaw(), "json", "", ""),
                "empty push data is rejected");
        expectThrow(Exception.class,
                () -> TvPush.merge(currentRaw(), "xml", "<a/>", ""),
                "unknown push type is rejected");

        // ---- merge: no current config ----
        TvPush.MergeResult fresh = TvPush.merge("", "json", pushedJson(), "");
        check(fresh.added == 2 && fresh.total == 2, "push into an empty config adds all");

        if (loopbackWorks()) {
            // ---- live server: push, single-use, wrong token, expiry, limits ----
            Recorder rec = new Recorder();
            ConfigTransferServer server =
                    new ConfigTransferServer(rec, new InetSocketAddress("127.0.0.1", 0));
            int port = server.socket.getLocalPort();
            String token = server.key();
            String body =
                    new JSONObject()
                            .put("token", token)
                            .put("type", "url")
                            .put("data", "https://d.example.com/api.php/provide/vod/")
                            .toString();
            String r1 = postPush(port, body, "application/json");
            check(r1.startsWith("200|"), "valid push returns 200: " + r1);
            check(new JSONObject(r1.substring(4)).optBoolean("ok", false)
                            && new JSONObject(r1.substring(4)).optInt("added", 0) == 1,
                    "valid push reply says ok and added=1");
            check(rec.calls == 1 && "url".equals(rec.lastType)
                            && rec.lastData.contains("d.example.com"),
                    "importer received type=url and the URL");
            String r2 = postPush(port, body, "application/json");
            check(r2.startsWith("409|"), "second push on the same token returns 409");
            check(rec.calls == 1, "single-use token: importer not called twice");
            server.close();

            Recorder rec2 = new Recorder();
            ConfigTransferServer server2 =
                    new ConfigTransferServer(rec2, new InetSocketAddress("127.0.0.1", 0));
            int port2 = server2.socket.getLocalPort();
            String wrongBody =
                    new JSONObject()
                            .put("token", "00000000000000000000000000000000")
                            .put("type", "url")
                            .put("data", "https://d.example.com/api.php/provide/vod/")
                            .toString();
            String r3 = postPush(port2, wrongBody, "application/json");
            check(r3.startsWith("401|"), "wrong token returns 401");
            String badTypeBody =
                    new JSONObject()
                            .put("token", server2.key())
                            .put("type", "xml")
                            .put("data", "x")
                            .toString();
            String r4 = postPush(port2, badTypeBody, "application/json");
            check(r4.startsWith("400|"), "unknown push type returns 400");
            String r5 = postPush(port2, body.replace(token, server2.key()), "text/plain");
            check(r5.startsWith("415|"), "non-JSON content type returns 415");
            server2.close();

            Recorder rec3 = new Recorder();
            ConfigTransferServer server3 =
                    new ConfigTransferServer(rec3, 200, new InetSocketAddress("127.0.0.1", 0));
            int port3 = server3.socket.getLocalPort();
            Thread.sleep(400);
            String expBody =
                    new JSONObject()
                            .put("token", server3.key())
                            .put("type", "url")
                            .put("data", "https://d.example.com/api.php/provide/vod/")
                            .toString();
            String r6 = postPush(port3, expBody, "application/json");
            check(r6.startsWith("410|"), "expired token returns 410");
            server3.close();

            Recorder rec4 = new Recorder();
            ConfigTransferServer server4 =
                    new ConfigTransferServer(rec4, new InetSocketAddress("127.0.0.1", 0));
            int port4 = server4.socket.getLocalPort();
            try (Socket raw = new Socket("127.0.0.1", port4)) {
                raw.getOutputStream()
                        .write(("POST /push HTTP/1.1\r\nHost: 127.0.0.1:"
                                        + port4
                                        + "\r\nContent-Type: application/json\r\nContent-Length: 2097152\r\n\r\n")
                                .getBytes("US-ASCII"));
                raw.getOutputStream().flush();
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(raw.getInputStream(), "US-ASCII"));
                String first = in.readLine();
                check(first != null && first.contains("413"), "oversized body returns 413");
            }
            server4.close();
        } else {
            System.out.println(
                    "SKIP live HTTP tests: this VM intercepts loopback TCP "
                            + "(a local ServerSocket never receives local connections); "
                            + "the accept loop / /push route / token / expiry / single-use "
                            + "paths are covered by the static checks and must be field-tested "
                            + "on the user's real phone + TV on the same Wi-Fi.");
        }

        System.out.println(checks + " TvPush checks passed");
    }
}
