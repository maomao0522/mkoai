package app.mkoai.player;

import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
import android.graphics.Color;
import android.graphics.Typeface;
import java.io.*;
import java.net.*;
import org.json.JSONObject;

/**
 * Phone sender for TV QR login (same Wi-Fi, no cloud). Scans the TV's login
 * QR code (or takes the IP / port / login code by hand), then POSTs this
 * phone's Supabase session (access + refresh tokens) to the TV's {@code
 * /login} endpoint so the TV signs in as the same account. The tokens belong
 * to the user's own account and go only to the TV on the same LAN; they are
 * never logged.
 */
final class PhoneLoginDialog {
    static final int REQUEST_QR_SCAN = 102;

    final MainActivity a;
    AppDialog dialog;
    EditText ipInput, portInput, codeInput;
    TextView statusView;
    boolean sending;

    PhoneLoginDialog(MainActivity host) {
        a = host;
    }

    void show() {
        a.phoneLogin = this;
        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(4));

        TextView tip = a.text("先在電視上開啟「掃碼登入」，再掃描電視上的 QR 碼。", 13, MainActivity.MUTED);
        panel.addView(tip);
        a.gap(panel, 10);

        TextView scanBtn = a.text("掃描電視 QR 碼", 14, Color.WHITE);
        scanBtn.setTypeface(null, Typeface.BOLD);
        scanBtn.setGravity(Gravity.CENTER);
        scanBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        scanBtn.setBackground(a.bg(0xffe34b58, 999));
        scanBtn.setOnClickListener(v -> scanQr());
        MobileDetailScreen.bindTouchFeedback(scanBtn);
        panel.addView(scanBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 10);

        panel.addView(a.text("或手動輸入電視畫面上的資訊", 13, MainActivity.MUTED));
        a.gap(panel, 8);
        ipInput = input("電視區網 IP，例如 192.168.1.20", true);
        panel.addView(ipInput, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);
        LinearLayout row = a.row();
        portInput = input("Port（預設 8901）", true);
        codeInput = input("登入碼（8 碼）", true);
        row.addView(portInput, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(codeInput, new LinearLayout.LayoutParams(0, -2, 2f));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 12);

        TextView loginBtn = a.text("登入電視", 14, Color.WHITE);
        loginBtn.setTypeface(null, Typeface.BOLD);
        loginBtn.setGravity(Gravity.CENTER);
        loginBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        loginBtn.setBackground(a.bg(0xffe34b58, 999));
        loginBtn.setOnClickListener(v -> login());
        MobileDetailScreen.bindTouchFeedback(loginBtn);
        panel.addView(loginBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 10);

        statusView = a.text("", 13, MainActivity.MUTED);
        panel.addView(statusView);

        dialog =
                new AppDialog.Builder(a)
                        .setTitle("掃碼登入電視")
                        .setView(panel)
                        .setNegativeButton("關閉", null)
                        .create();
        dialog.setOnDismissListener(
                d -> {
                    if (a.phoneLogin == this) a.phoneLogin = null;
                });
        dialog.show();
    }

    private EditText input(String hint, boolean singleLine) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setHintTextColor(0xff6b7280);
        et.setTextColor(MainActivity.WHITE);
        et.setTextSize(14);
        et.setSingleLine(singleLine);
        et.setShowSoftInputOnFocus(true);
        et.setLongClickable(true);
        et.setTextIsSelectable(true);
        et.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        et.setBackground(a.bg(0xff1e2623, 12));
        return et;
    }

    void scanQr() {
        a.startActivityForResult(new Intent(a, QrScanActivity.class), REQUEST_QR_SCAN);
    }

    void onQrScanned(String text) {
        TvLogin.Target target = TvLogin.parseQrContent(text);
        if (target == null) {
            a.toast("無法識別這個 QR 碼，請確認是電視的掃碼登入碼");
            setStatus("無法識別這個 QR 碼，請確認是電視的掃碼登入碼（mkoai://tv-login）");
            return;
        }
        ipInput.setText(target.ip);
        portInput.setText(String.valueOf(target.port));
        codeInput.setText(target.code);
        setStatus("已掃到電視 " + target.ip + ":" + target.port + "，按登入電視");
        a.toast("已掃到電視 " + target.ip + ":" + target.port);
    }

    void setStatus(String message) {
        statusView.setText(message);
    }

    void login() {
        if (sending) return;
        final String ip = ipInput.getText().toString().trim();
        final String portText = portInput.getText().toString().trim();
        final String code = codeInput.getText().toString().trim();
        if (ip.isEmpty() || code.isEmpty()) {
            setStatus("請先掃描電視 QR 碼，或手動填上 IP 與登入碼");
            a.toast("請先掃描電視 QR 碼，或手動填上 IP 與登入碼");
            return;
        }
        int port;
        try {
            port = portText.isEmpty() ? TvPush.PUSH_PORT : Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            setStatus("Port 不正確");
            a.toast("Port 不正確");
            return;
        }
        if (!SupabaseAuth.isSignedIn(a)) {
            setStatus("請先在手機登入帳號，再掃碼登入電視");
            a.toast("請先在手機登入帳號");
            return;
        }
        sending = true;
        setStatus("正在登入電視 " + ip + ":" + port + "…");
        new Thread(
                        () -> {
                            String result;
                            try {
                                String access = SupabaseAuth.validAccessToken(a);
                                if (access == null || access.isEmpty())
                                    throw new IOException("手機登入已過期，請重新登入");
                                String refresh = SupabaseAuth.refreshToken(a);
                                long expiresIn = SupabaseAuth.sessionExpiresInSeconds(a);
                                JSONObject body = new JSONObject();
                                body.put("code", code);
                                body.put("access_token", access);
                                body.put("refresh_token", refresh == null ? "" : refresh);
                                body.put("expires_in", expiresIn);
                                byte[] bytes = body.toString().getBytes("UTF-8");
                                HttpURLConnection conn =
                                        (HttpURLConnection)
                                                new URL("http://" + ip + ":" + port + "/login")
                                                        .openConnection();
                                conn.setRequestMethod("POST");
                                conn.setRequestProperty(
                                        "Content-Type", "application/json; charset=utf-8");
                                conn.setFixedLengthStreamingMode(bytes.length);
                                conn.setConnectTimeout(10000);
                                conn.setReadTimeout(15000);
                                conn.setDoOutput(true);
                                try (OutputStream out = conn.getOutputStream()) {
                                    out.write(bytes);
                                }
                                int httpCode = conn.getResponseCode();
                                InputStream in =
                                        httpCode >= 400
                                                ? conn.getErrorStream()
                                                : conn.getInputStream();
                                String reply = "";
                                if (in != null) {
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    byte[] chunk = new byte[4096];
                                    int n;
                                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                                    reply = buf.toString("UTF-8");
                                }
                                conn.disconnect();
                                try {
                                    JSONObject r = new JSONObject(reply);
                                    if (r.optBoolean("ok", false)) {
                                        result = r.optString("message", "電視已登入");
                                    } else {
                                        result =
                                                "登入失敗："
                                                        + r.optString("message", "HTTP " + httpCode);
                                    }
                                } catch (Exception e) {
                                    result = "登入失敗：電視回了無法識別的內容（HTTP " + httpCode + ")";
                                }
                            } catch (Exception e) {
                                String message = e.getMessage();
                                if (message != null && message.startsWith("手機登入已過期")) {
                                    result = message;
                                } else {
                                    result = "登入失敗：連不上電視，請確認同一 Wi-Fi 且電視掃碼登入畫面還開著";
                                }
                            }
                            final String message = result;
                            a.ui.post(
                                    () -> {
                                        sending = false;
                                        setStatus(message);
                                        a.toast(message);
                                    });
                        })
                .start();
    }
}
