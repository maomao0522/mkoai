package app.mkoai.player;

import android.graphics.*;
import android.widget.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * TV QR login screen (same Wi-Fi, no cloud). Shows a QR code with the TV's
 * LAN address, port and one-time login code; the phone scans it and POSTs its
 * Supabase session (access + refresh tokens) to {@code /login}, and the TV
 * signs in as the phone's account. The LAN server runs only while this dialog
 * is open and stops on dismiss. Tokens are never logged or written to files.
 */
final class TvLoginDialog {
    final MainActivity a;
    ConfigTransferServer server;
    AppDialog dialog;
    boolean finished;
    final Runnable expiry = this::expire;

    void expire() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            a.toast("登入碼已過期，請重新開啟掃碼登入");
        }
    }

    TvLoginDialog(MainActivity host) {
        a = host;
    }

    void show() {
        if (!a.isTvDevice()) {
            a.toast("掃碼登入僅在電視上開啟");
            return;
        }
        try {
            String ip = PhoneConfigDialog.lanAddress();
            ConfigTransferServer.LoginImporter importer =
                    (access, refresh, expiresIn) -> {
                        CountDownLatch applied = new CountDownLatch(1);
                        AtomicReference<Exception> failure = new AtomicReference<>();
                        AtomicReference<String> email = new AtomicReference<>();
                        a.ui.post(
                                () -> {
                                    try {
                                        if (server.closed
                                                || a.isFinishing()
                                                || a.tvLogin != this)
                                            throw new java.io.IOException("Session closed");
                                        if (!SupabaseAuth.importSession(
                                                a, access, refresh, expiresIn))
                                            throw new java.io.IOException("登入失敗：工作階段無效");
                                        email.set(SupabaseAuth.email(a));
                                        finished = true;
                                        if (dialog != null) dialog.dismiss();
                                        a.toast(
                                                "已登入 "
                                                        + (email.get() == null
                                                                ? ""
                                                                : email.get()));
                                    } catch (Exception e) {
                                        failure.set(e);
                                    } finally {
                                        applied.countDown();
                                    }
                                });
                        if (!applied.await(20, TimeUnit.SECONDS))
                            throw new java.io.IOException("Apply timeout");
                        if (failure.get() != null) throw failure.get();
                        // Sync after the session is stored (async inside).
                        a.ui.post(() -> SupabaseSync.syncNow(a));
                        return email.get();
                    };
            ConfigTransferServer srv;
            try {
                srv =
                        new ConfigTransferServer(
                                importer,
                                new InetSocketAddress(ip, TvPush.PUSH_PORT));
            } catch (java.io.IOException busy) {
                // Port 8901 taken (e.g. another receiver): fall back to ephemeral.
                srv = new ConfigTransferServer(importer, new InetSocketAddress(ip, 0));
            }
            server = srv;
            int port = server.socket.getLocalPort();
            String qrText = TvLogin.buildQrContent(ip, port, server.key());
            LinearLayout panel = a.vertical();
            panel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            TextView note =
                    a.text("用手機 mkoai 掃碼，電視就登入手機同一個帳號，不用在電視上打帳號密碼。", 15, MainActivity.MUTED);
            panel.addView(note);
            a.gap(panel, 12);
            int qrSize = a.dp(190);
            BitMatrix matrix =
                    new QRCodeWriter().encode(qrText, BarcodeFormat.QR_CODE, qrSize, qrSize);
            int[] pixels = new int[qrSize * qrSize];
            for (int y = 0; y < qrSize; y++)
                for (int x = 0; x < qrSize; x++)
                    pixels[y * qrSize + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            ImageView qr = new ImageView(a);
            qr.setTag("tv-login-qr");
            qr.setContentDescription("手機掃碼登入二維碼");
            qr.setImageBitmap(Bitmap.createBitmap(pixels, qrSize, qrSize, Bitmap.Config.ARGB_8888));
            panel.addView(qr, new LinearLayout.LayoutParams(a.dp(190), a.dp(190)));
            TextView address = a.text(server.loginAddress(ip), 12, MainActivity.WHITE);
            address.setTag("tv-login-address");
            address.setGravity(android.view.Gravity.CENTER);
            address.setPadding(0, a.dp(10), 0, 0);
            panel.addView(address);
            TextView code =
                    a.text("登入碼：" + TvPush.pairingCode(server.key()) + "（掃不到時手動輸入用）", 13, MainActivity.WHITE);
            code.setTag("tv-login-code");
            code.setGravity(android.view.Gravity.CENTER);
            code.setPadding(0, a.dp(6), 0, 0);
            panel.addView(code);
            a.gap(panel, 6);
            panel.addView(a.text("登入碼只在本次有效 · 5 分鐘有效 · 關閉後停止接收", 12, MainActivity.MUTED));
            dialog =
                    new AppDialog.Builder(a)
                            .setTitle("掃碼登入")
                            .setView(panel)
                            .setPositiveButton("重新整理", (d, n) -> a.tvLoginDialog())
                            .setNegativeButton("完成", null)
                            .create();
            dialog.setOnDismissListener(
                    d -> {
                        a.ui.removeCallbacks(expiry);
                        if (finished) a.ui.postDelayed(this::close, 2000);
                        else close();
                    });
            dialog.show();
            dialog.getButton(-2).requestFocus();
            a.ui.postDelayed(expiry, TimeUnit.MINUTES.toMillis(5));
        } catch (Exception error) {
            close();
            a.toast("未能開啟掃碼登入，可改用帳號密碼登入");
        }
    }

    void close() {
        a.ui.removeCallbacks(expiry);
        if (server != null) server.close();
        if (a.tvLogin == this) a.tvLogin = null;
    }
}
