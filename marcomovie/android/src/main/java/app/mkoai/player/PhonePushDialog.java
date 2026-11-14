package app.mkoai.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import org.json.JSONObject;

/**
 * Phone sender for TV source push (same Wi-Fi, no cloud). Scans the TV's QR
 * code (or takes the IP / port / pairing code by hand), then POSTs either one
 * source URL the user typed or a whole TVBox JSON they pasted.
 */
final class PhonePushDialog {
    static final int REQUEST_QR_SCAN = 101;

    final MainActivity a;
    AppDialog dialog;
    EditText ipInput, portInput, codeInput, contentInput;
    TextView urlTab, jsonTab, statusView, clipBtn;
    boolean isJson;
    boolean sending;

    PhonePushDialog(MainActivity host) {
        a = host;
    }

    void show() {
        a.phonePush = this;
        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(4));

        TextView tip = a.text("先在電視上開啟「手機推送來源」，再掃描電視上的 QR 碼。", 13, MainActivity.MUTED);
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
        codeInput = input("配對碼（8 碼）", true);
        row.addView(portInput, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(codeInput, new LinearLayout.LayoutParams(0, -2, 2f));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 12);

        LinearLayout tabs = a.row();
        urlTab = tabButton("源網址", true);
        jsonTab = tabButton("JSON 內容", false);
        urlTab.setOnClickListener(v -> setType(false));
        jsonTab.setOnClickListener(v -> setType(true));
        tabs.addView(urlTab, new LinearLayout.LayoutParams(0, -2, 1f));
        tabs.addView(jsonTab, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        contentInput = input("https://…/api.php/provide/vod/at/xml/", true);
        contentInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        panel.addView(contentInput, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 12);

        // 2.0.53: read a tvbox JSON straight from the clipboard (JSON tab only).
        clipBtn = a.text("從剪貼簿讀取 JSON 並推送", 14, Color.WHITE);
        clipBtn.setTypeface(null, Typeface.BOLD);
        clipBtn.setGravity(Gravity.CENTER);
        clipBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        clipBtn.setBackground(a.bg(0xff2a3430, 999));
        clipBtn.setOnClickListener(v -> readClipboardAndPush());
        MobileDetailScreen.bindTouchFeedback(clipBtn);
        clipBtn.setVisibility(View.GONE);
        panel.addView(clipBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView pushBtn = a.text("推送到電視", 14, Color.WHITE);
        pushBtn.setTypeface(null, Typeface.BOLD);
        pushBtn.setGravity(Gravity.CENTER);
        pushBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        pushBtn.setBackground(a.bg(0xffe34b58, 999));
        pushBtn.setOnClickListener(v -> push());
        MobileDetailScreen.bindTouchFeedback(pushBtn);
        panel.addView(pushBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 10);

        statusView = a.text("", 13, MainActivity.MUTED);
        panel.addView(statusView);

        dialog =
                new AppDialog.Builder(a)
                        .setTitle("推送來源到電視")
                        .setView(panel)
                        .setNegativeButton("關閉", null)
                        .create();
        dialog.setOnDismissListener(
                d -> {
                    if (a.phonePush == this) a.phonePush = null;
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

    private TextView tabButton(String label, boolean selected) {
        TextView tv = a.text(label, 14, Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(a.dp(10), a.dp(9), a.dp(10), a.dp(9));
        tv.setBackground(a.bg(selected ? 0xffe34b58 : 0xff1e2623, 999));
        MobileDetailScreen.bindTouchFeedback(tv);
        return tv;
    }

    void setType(boolean json) {
        isJson = json;
        urlTab.setBackground(a.bg(!json ? 0xffe34b58 : 0xff1e2623, 999));
        jsonTab.setBackground(a.bg(json ? 0xffe34b58 : 0xff1e2623, 999));
        clipBtn.setVisibility(json ? View.VISIBLE : View.GONE);
        if (json) {
            contentInput.setHint("貼上整份 tvbox JSON（{\"sites\":[...]}）");
            contentInput.setSingleLine(false);
            contentInput.setMinLines(4);
            contentInput.setMaxLines(6);
            contentInput.setGravity(Gravity.TOP);
            contentInput.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            contentInput.setHint("https://…/api.php/provide/vod/at/xml/");
            contentInput.setSingleLine(true);
            contentInput.setMinLines(1);
            contentInput.setGravity(Gravity.CENTER_VERTICAL);
            contentInput.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
    }

    void scanQr() {
        a.startActivityForResult(new Intent(a, QrScanActivity.class), REQUEST_QR_SCAN);
    }

    /** 2.0.53: read a tvbox JSON from the clipboard and push it straight to the TV. */
    void readClipboardAndPush() {
        String clip = null;
        try {
            ClipboardManager cm =
                    (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence t = cd.getItemAt(0).coerceToText(a);
                    if (t != null) clip = t.toString().trim();
                }
            }
        } catch (Exception ignored) {
        }
        if (clip == null || clip.isEmpty()) {
            setStatus("剪貼簿是空的，請先複製 tvbox JSON");
            a.toast("剪貼簿是空的");
            return;
        }
        try {
            Cms.Config parsed = Cms.parseConfig(clip, "");
            if (parsed.sites.isEmpty()) throw new IOException("剪貼簿裡不是 JSON 設定");
        } catch (Exception e) {
            setStatus("剪貼簿裡不是 JSON 設定");
            a.toast("剪貼簿裡不是 JSON 設定");
            return;
        }
        if (!isJson) setType(true);
        contentInput.setText(clip);
        setStatus("已從剪貼簿讀取 JSON，正在推送…");
        push();
    }

    void onQrScanned(String text) {
        TvPush.Target target = TvPush.parseQrContent(text);
        if (target == null) {
            a.toast("無法識別這個 QR 碼，請確認是電視的推送碼");
            setStatus("無法識別這個 QR 碼，請確認是電視的推送碼（mkoai://tv-push）");
            return;
        }
        ipInput.setText(target.ip);
        portInput.setText(String.valueOf(target.port));
        codeInput.setText(target.token);
        setStatus("已掃到電視 " + target.ip + ":" + target.port + "，輸好內容後按推送");
        a.toast("已掃到電視 " + target.ip + ":" + target.port);
    }

    void setStatus(String message) {
        statusView.setText(message);
    }

    void push() {
        if (sending) return;
        final String ip = ipInput.getText().toString().trim();
        final String portText = portInput.getText().toString().trim();
        final String token = codeInput.getText().toString().trim();
        final String content = contentInput.getText().toString().trim();
        final boolean json = isJson;
        if (ip.isEmpty() || token.isEmpty()) {
            setStatus("請先掃描電視 QR 碼，或手動填上 IP 與配對碼");
            a.toast("請先掃描電視 QR 碼，或手動填上 IP 與配對碼");
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
        try {
            if (json) {
                Cms.Config parsed = Cms.parseConfig(content, "");
                if (parsed.sites.isEmpty()) throw new IOException("JSON 沒有可識別的站點");
            } else {
                TvPush.validateUrl(content);
            }
        } catch (Exception e) {
            String message = e.getMessage();
            setStatus(message == null || message.isEmpty() ? "內容格式不正確" : message);
            a.toast(message == null || message.isEmpty() ? "內容格式不正確" : message);
            return;
        }
        sending = true;
        setStatus("正在推送到 " + ip + ":" + port + "…");
        new Thread(
                        () -> {
                            String result;
                            try {
                                JSONObject body = new JSONObject();
                                body.put("token", token);
                                body.put("type", json ? "json" : "url");
                                body.put("data", content);
                                byte[] bytes = body.toString().getBytes("UTF-8");
                                HttpURLConnection conn =
                                        (HttpURLConnection)
                                                new URL("http://" + ip + ":" + port + "/push")
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
                                int code = conn.getResponseCode();
                                InputStream in =
                                        code >= 400 ? conn.getErrorStream() : conn.getInputStream();
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
                                        int added = r.optInt("added", 0);
                                        result =
                                                added > 0
                                                        ? "推送成功：已加入 "
                                                                + added
                                                                + " 個來源"
                                                        : r.optString("message", "推送成功");
                                    } else {
                                        result =
                                                "推送失敗："
                                                        + r.optString("message", "HTTP " + code);
                                    }
                                } catch (Exception e) {
                                    result = "推送失敗：電視回了無法識別的內容（HTTP " + code + ")";
                                }
                            } catch (Exception e) {
                                result = "推送失敗：連不上電視，請確認同一 Wi-Fi 且電視推送畫面還開著";
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
