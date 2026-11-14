package app.mkoai.player;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/** Short-lived, token-scoped LAN configuration receiver; separate from playback proxy. */
final class ConfigTransferServer implements Closeable {
    interface Importer {
        void apply(String address) throws Exception;
    }

    /** Phone-to-TV push: receives one source URL or a whole TVBox config JSON. */
    interface PushImporter {
        TvPush.MergeResult applyPush(String type, String data) throws Exception;
    }

    /**
     * TV QR login: receives the phone's Supabase session (access + refresh
     * tokens) and signs the TV in as the phone's account. Returns the signed-in
     * account email (or null). Must not log the tokens.
     */
    interface LoginImporter {
        String applyLogin(String accessToken, String refreshToken, long expiresIn)
                throws Exception;
    }

    final ServerSocket socket;
    final String path;
    final long expires;
    final Importer importer;
    final PushImporter pushHandler;
    final LoginImporter loginHandler;
    volatile boolean closed;
    final AtomicBoolean importing = new AtomicBoolean(), applied = new AtomicBoolean();
    final ThreadPoolExecutor requests =
            new ThreadPoolExecutor(
                    0,
                    2,
                    10,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "movie-config-request");
                        t.setDaemon(true);
                        return t;
                    });

    ConfigTransferServer(Importer handler) throws IOException {
        this(handler, null, null, TimeUnit.MINUTES.toMillis(5), new InetSocketAddress("0.0.0.0", 0));
    }

    ConfigTransferServer(Importer handler, long ttl, InetSocketAddress bind) throws IOException {
        this(handler, null, null, ttl, bind);
    }

    /** Push receiver bound to the given LAN address (port 8901 preferred). */
    ConfigTransferServer(PushImporter handler, InetSocketAddress bind) throws IOException {
        this(null, handler, null, TimeUnit.MINUTES.toMillis(5), bind);
    }

    ConfigTransferServer(PushImporter handler, long ttl, InetSocketAddress bind)
            throws IOException {
        this(null, handler, null, ttl, bind);
    }

    /** QR login receiver bound to the given LAN address (port 8901 preferred). */
    ConfigTransferServer(LoginImporter handler, InetSocketAddress bind) throws IOException {
        this(null, null, handler, TimeUnit.MINUTES.toMillis(5), bind);
    }

    ConfigTransferServer(LoginImporter handler, long ttl, InetSocketAddress bind)
            throws IOException {
        this(null, null, handler, ttl, bind);
    }

    ConfigTransferServer(
            Importer handler, PushImporter push, LoginImporter login, long ttl, InetSocketAddress bind)
            throws IOException {
        importer = handler;
        pushHandler = push;
        loginHandler = login;
        expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttl);
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder key = new StringBuilder();
        for (byte b : bytes) key.append(String.format(Locale.ROOT, "%02x", b & 255));
        path = "/configure/" + key;
        socket = new ServerSocket();
        socket.bind(bind);
        Thread accept =
                new Thread(
                        () -> {
                            while (!closed)
                                try {
                                    Socket client = socket.accept();
                                    try {
                                        requests.execute(() -> serve(client));
                                    } catch (RejectedExecutionException full) {
                                        client.close();
                                    }
                                } catch (IOException error) {
                                    if (!closed) close();
                                }
                        },
                        "movie-config-listener");
        accept.setDaemon(true);
        accept.start();
    }

    String address(String host) {
        return "http://" + host + ":" + socket.getLocalPort() + path;
    }

    /** One-time token (the 32-hex key inside the configure path). */
    String key() {
        return path.substring("/configure/".length());
    }

    /** Push endpoint address for the phone app (token is in the JSON body). */
    String pushAddress(String host) {
        return "http://" + host + ":" + socket.getLocalPort() + "/push";
    }

    /** QR login endpoint address for the phone app (code is in the JSON body). */
    String loginAddress(String host) {
        return "http://" + host + ":" + socket.getLocalPort() + "/login";
    }

    boolean expired() {
        return closed || System.nanoTime() >= expires;
    }

    void serve(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            InetAddress peer = s.getInetAddress();
            if (!peer.isSiteLocalAddress() && !peer.isLoopbackAddress()) {
                reply(out, 403, "text/plain", "僅允許區域網連線");
                return;
            }
            String[] first = line(in, 4096).split(" ");
            if (first.length != 3) return;
            Map<String, String> headers = new HashMap<>();
            int total = 0;
            for (; ; ) {
                String row = line(in, 4096);
                total += row.length();
                if (total > 16384) throw new IOException("Header limit");
                if (row.isEmpty()) break;
                int colon = row.indexOf(':');
                if (colon > 0)
                    headers.put(
                            row.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            row.substring(colon + 1).trim());
            }
            if (first[1].equals("/push")) {
                handlePush(out, first, headers, s);
                return;
            }
            if (first[1].equals("/login")) {
                handleLogin(out, first, headers, s);
                return;
            }
            if (!first[1].equals(path)) {
                reply(out, 404, "text/plain", "頁面不存在");
                return;
            }
            if (expired()) {
                reply(out, 410, "text/plain", "二維碼已過期，請在電視上重新開啟");
                return;
            }
            String host = headers.containsKey("host") ? headers.get("host") : "";
            String expected = s.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
            if (!host.equals(expected)) {
                reply(out, 403, "text/plain", "請使用電視上的二維碼地址");
                return;
            }
            if (first[0].equals("GET")) {
                reply(out, 200, "text/html", page(""));
                return;
            }
            if (!first[0].equals("POST")) {
                reply(out, 405, "text/plain", "請求方式不支援");
                return;
            }
            String origin = headers.get("origin");
            if (origin != null && !origin.equals("http://" + host)) {
                reply(out, 403, "text/plain", "頁面來源不匹配");
                return;
            }
            String contentType =
                    headers.containsKey("content-type") ? headers.get("content-type") : "";
            if (!contentType.startsWith("application/x-www-form-urlencoded")
                    || headers.containsKey("transfer-encoding")) {
                reply(out, 415, "text/plain", "請使用配置頁面提交");
                return;
            }
            int length;
            try {
                String lenVal =
                        headers.containsKey("content-length")
                                ? headers.get("content-length")
                                : "-1";
                length = Integer.parseInt(lenVal);
            } catch (NumberFormatException e) {
                length = -1;
            }
            if (length < 0 || length > 32768) {
                reply(out, 413, "text/plain", "地址過長");
                return;
            }
            byte[] body = new byte[length];
            for (int n = 0; n < length; ) {
                int got = in.read(body, n, length - n);
                if (got < 0) throw new EOFException();
                n += got;
            }
            String address = "";
            for (String pair : new String(body, "UTF-8").split("&")) {
                String[] p = pair.split("=", 2);
                if (URLDecoder.decode(p[0], "UTF-8").equals("address") && p.length == 2)
                    address = URLDecoder.decode(p[1], "UTF-8").trim();
            }
            try {
                URL url = new URL(address);
                if (address.length() > 8192
                        || (!url.getProtocol().equals("http") && !url.getProtocol().equals("https"))
                        || address.indexOf('\r') >= 0
                        || address.indexOf('\n') >= 0) throw new IOException();
            } catch (Exception bad) {
                reply(out, 400, "text/html", page("請輸入有效的 HTTP 或 HTTPS 配置地址"));
                return;
            }
            if (applied.get()) {
                reply(out, 409, "text/html", page("電視已完成匯入，請關閉此頁面"));
                return;
            }
            if (!importing.compareAndSet(false, true)) {
                reply(out, 409, "text/html", page("正在匯入，請稍候檢視電視"));
                return;
            }
            try {
                if (applied.get()) {
                    reply(out, 409, "text/html", page("電視已完成匯入，請關閉此頁面"));
                    return;
                }
                if (expired()) throw new IOException();
                if (importer == null) throw new IOException();
                importer.apply(address);
                applied.set(true);
                reply(out, 200, "text/html", page("配置已匯入電視，可以關閉此頁面"));
            } catch (Exception error) {
                reply(out, 400, "text/html", page("匯入未完成，請檢查配置地址與電視網路後重試"));
            } finally {
                importing.set(false);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Phone-to-TV push endpoint: {@code POST /push} with a JSON body
     * {@code {"token":"...","type":"url"|"json","data":"..."}}. Token is the
     * one-time key (or its 8-char pairing code). Single-use, 5-minute TTL,
     * LAN peers only, same as the browser configure flow.
     */
    void handlePush(OutputStream out, String[] first, Map<String, String> headers, Socket s)
            throws IOException {
        if (pushHandler == null) {
            replyJson(out, 404, false, 0, 0, "不支援推送");
            return;
        }
        if (expired()) {
            replyJson(out, 410, false, 0, 0, "配對碼已過期，請在電視上重新開啟手機推送");
            return;
        }
        String host = headers.containsKey("host") ? headers.get("host") : "";
        String expected = s.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
        if (!host.equals(expected)) {
            replyJson(out, 403, false, 0, 0, "請使用電視上的推送地址");
            return;
        }
        if (!first[0].equals("POST")) {
            replyJson(out, 405, false, 0, 0, "請求方式不支援");
            return;
        }
        String contentType =
                headers.containsKey("content-type") ? headers.get("content-type") : "";
        if (!contentType.startsWith("application/json")
                || headers.containsKey("transfer-encoding")) {
            replyJson(out, 415, false, 0, 0, "請使用手機推送傳送 JSON");
            return;
        }
        int length;
        try {
            length =
                    Integer.parseInt(
                            headers.containsKey("content-length")
                                    ? headers.get("content-length")
                                    : "-1");
        } catch (NumberFormatException e) {
            length = -1;
        }
        if (length < 0 || length > TvPush.MAX_PUSH_BYTES) {
            replyJson(out, 413, false, 0, 0, "推送內容過大");
            return;
        }
        byte[] body = new byte[length];
        for (int n = 0; n < length; ) {
            int got = s.getInputStream().read(body, n, length - n);
            if (got < 0) throw new EOFException();
            n += got;
        }
        String token, type, data;
        try {
            JSONObject push = new JSONObject(new String(body, "UTF-8"));
            token = push.optString("token", "");
            type = push.optString("type", "");
            data = push.optString("data", "");
        } catch (Exception bad) {
            replyJson(out, 400, false, 0, 0, "推送內容不是有效的 JSON");
            return;
        }
        if (!TvPush.tokenMatches(token, key())) {
            replyJson(out, 401, false, 0, 0, "配對碼不正確，請重新掃描電視上的 QR 碼");
            return;
        }
        if (!type.equals("url") && !type.equals("json")) {
            replyJson(out, 400, false, 0, 0, "不支援的推送類型");
            return;
        }
        if (data == null || data.trim().isEmpty()) {
            replyJson(out, 400, false, 0, 0, "推送內容為空");
            return;
        }
        if (applied.get()) {
            replyJson(out, 409, false, 0, 0, "電視已完成一次推送，請重新開啟手機推送");
            return;
        }
        if (!importing.compareAndSet(false, true)) {
            replyJson(out, 409, false, 0, 0, "正在匯入，請稍候檢視電視");
            return;
        }
        try {
            if (applied.get()) {
                replyJson(out, 409, false, 0, 0, "電視已完成一次推送，請重新開啟手機推送");
                return;
            }
            if (expired()) throw new IOException();
            TvPush.MergeResult result = pushHandler.applyPush(type, data);
            applied.set(true);
            String message =
                    result.added > 0
                            ? "已加入 " + result.added + " 個來源"
                            : "該來源已存在，無需加入";
            replyJson(out, 200, true, result.added, result.duplicates, message);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) message = "匯入未完成，請檢查內容後重試";
            replyJson(out, 400, false, 0, 0, message);
        } finally {
            importing.set(false);
        }
    }

    /**
     * TV QR login endpoint: {@code POST /login} with a JSON body {@code
     * {"code":"...","access_token":"...","refresh_token":"...",
     * "expires_in":3600}}. The code is the one-time key (or its 8-char pairing
     * code). Single-use, 5-minute TTL, LAN peers only, same as {@code /push}.
     * The tokens are handed straight to the LoginImporter (which stores them
     * in SupabaseAuth); they are never logged or written to any file here.
     */
    void handleLogin(OutputStream out, String[] first, Map<String, String> headers, Socket s)
            throws IOException {
        if (loginHandler == null) {
            replyJson(out, 404, false, 0, 0, "不支援掃碼登入");
            return;
        }
        if (expired()) {
            replyJson(out, 410, false, 0, 0, "登入碼已過期，請在電視上重新開啟掃碼登入");
            return;
        }
        String host = headers.containsKey("host") ? headers.get("host") : "";
        String expected = s.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
        if (!host.equals(expected)) {
            replyJson(out, 403, false, 0, 0, "請使用電視上的掃碼登入地址");
            return;
        }
        if (!first[0].equals("POST")) {
            replyJson(out, 405, false, 0, 0, "請求方式不支援");
            return;
        }
        String contentType =
                headers.containsKey("content-type") ? headers.get("content-type") : "";
        if (!contentType.startsWith("application/json")
                || headers.containsKey("transfer-encoding")) {
            replyJson(out, 415, false, 0, 0, "請使用手機掃碼登入傳送 JSON");
            return;
        }
        int length;
        try {
            length =
                    Integer.parseInt(
                            headers.containsKey("content-length")
                                    ? headers.get("content-length")
                                    : "-1");
        } catch (NumberFormatException e) {
            length = -1;
        }
        if (length < 0 || length > TvLogin.MAX_LOGIN_BYTES) {
            replyJson(out, 413, false, 0, 0, "登入內容過大");
            return;
        }
        byte[] body = new byte[length];
        for (int n = 0; n < length; ) {
            int got = s.getInputStream().read(body, n, length - n);
            if (got < 0) throw new EOFException();
            n += got;
        }
        String code, access, refresh;
        long expiresIn = 3600;
        try {
            JSONObject login = new JSONObject(new String(body, "UTF-8"));
            code = login.optString("code", "");
            access = login.optString("access_token", "");
            refresh = login.optString("refresh_token", "");
            if (login.has("expires_in")) expiresIn = login.optLong("expires_in", 3600);
        } catch (Exception bad) {
            replyJson(out, 400, false, 0, 0, "登入內容不是有效的 JSON");
            return;
        }
        if (!TvPush.tokenMatches(code, key())) {
            replyJson(out, 401, false, 0, 0, "登入碼不正確，請重新掃描電視上的 QR 碼");
            return;
        }
        String sessionError = TvLogin.validateSession(access, refresh);
        if (sessionError != null) {
            replyJson(out, 400, false, 0, 0, sessionError);
            return;
        }
        if (applied.get()) {
            replyJson(out, 409, false, 0, 0, "電視已完成一次登入，請重新開啟掃碼登入");
            return;
        }
        if (!importing.compareAndSet(false, true)) {
            replyJson(out, 409, false, 0, 0, "正在登入，請稍候檢視電視");
            return;
        }
        try {
            if (applied.get()) {
                replyJson(out, 409, false, 0, 0, "電視已完成一次登入，請重新開啟掃碼登入");
                return;
            }
            if (expired()) throw new IOException();
            String email = loginHandler.applyLogin(access, refresh, expiresIn);
            applied.set(true);
            replyJson(
                    out,
                    200,
                    true,
                    0,
                    0,
                    email != null && !email.isEmpty() ? "電視已登入 " + email : "電視已登入");
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) message = "登入未完成，請檢查內容後重試";
            replyJson(out, 400, false, 0, 0, message);
        } finally {
            importing.set(false);
        }
    }

    static void replyJson(
            OutputStream out, int code, boolean ok, int added, int duplicates, String message)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("ok", ok);
            body.put("added", added);
            body.put("duplicates", duplicates);
            body.put("message", message);
        } catch (JSONException e) {
            // android.jar's org.json throws the checked JSONException; the
            // values here are primitives/strings and cannot fail in practice.
            throw new IOException(e);
        }
        byte[] bytes = body.toString().getBytes("UTF-8");
        String head =
                "HTTP/1.1 "
                        + code
                        + " Response\r\nConnection: close\r\nCache-Control: no-store\r\n"
                        + "X-Content-Type-Options: nosniff\r\nContent-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: "
                        + bytes.length
                        + "\r\n\r\n";
        out.write(head.getBytes("US-ASCII"));
        out.write(bytes);
        out.flush();
    }

    static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > max) throw new IOException("Line limit");
        }
        return b.toString("ISO-8859-1");
    }

    static void reply(OutputStream out, int code, String type, String text) throws IOException {
        byte[] body = text.getBytes("UTF-8");
        String head =
                "HTTP/1.1 "
                        + code
                        + " Response\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: same-origin\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\nContent-Type: "
                        + type
                        + "; charset=utf-8\r\nContent-Length: "
                        + body.length
                        + "\r\n\r\n";
        out.write(head.getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    static String page(String note) {
        return "<!doctype html><html lang=zh-CN><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>mkoai 配置</title><style>body{margin:0;background:#101013;color:#f5f5f7;font:17px system-ui;padding:28px}main{max-width:520px;margin:8vh auto}h1{font-size:30px}p{line-height:1.7;color:#bfbfc7}input,button{box-sizing:border-box;width:100%;border:0;border-radius:18px;padding:18px;font:inherit;margin:10px 0}input{background:#292930;color:white}button{background:#f5f5f7;color:#101013;font-weight:600}.note{color:#d8e8ff}</style><main><h1>mkoai</h1><p>將你自行獲取的配置地址貼上到下方，匯入到同一網路中的電視。請保持電視配置頁面開啟。</p><p class=note>"
                + note
                + "</p><form method=post accept-charset=UTF-8><input name=address type=url required maxlength=8192 autocomplete=off autocapitalize=off spellcheck=false placeholder='貼上配置地址'><button>匯入到電視</button></form><p>本產品僅為工具，不內建影視資源。此頁面只在本次掃碼配置期間可用。</p></main></html>";
    }

    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        requests.shutdownNow();
    }
}
