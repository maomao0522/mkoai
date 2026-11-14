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
 * TV receive screen for phone-to-TV source push (same Wi-Fi, no cloud).
 * Shows a QR code with the TV's LAN address, port and one-time token; the
 * phone scans it and POSTs a source URL or a TVBox JSON to {@code /push}.
 * The LAN server runs only while this dialog is open and stops on dismiss.
 */
final class TvPushDialog {
    final MainActivity a;
    ConfigTransferServer server;
    AppDialog dialog;
    boolean finished;
    final Runnable expiry = this::expire;

    void expire() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            a.toast("配對碼已過期，請重新開啟手機推送");
        }
    }

    TvPushDialog(MainActivity host) {
        a = host;
    }

    void show() {
        if (!a.isTvDevice()) {
            a.toast("手機推送接收僅在電視上開啟");
            return;
        }
        try {
            String ip = PhoneConfigDialog.lanAddress();
            ConfigTransferServer.PushImporter importer =
                    (type, data) -> {
                        String currentRaw =
                                (a.config != null && a.config.raw != null) ? a.config.raw : "";
                        String origin = a.configOrigin == null ? "" : a.configOrigin;
                        TvPush.MergeResult merged = TvPush.merge(currentRaw, type, data, origin);
                        CountDownLatch applied = new CountDownLatch(1);
                        AtomicReference<Exception> failure = new AtomicReference<>();
                        a.ui.post(
                                () -> {
                                    try {
                                        if (server.closed
                                                || a.isFinishing()
                                                || a.tvPush != this)
                                            throw new java.io.IOException("Session closed");
                                        if (merged.added > 0) {
                                            Cms.Config cfg =
                                                    Cms.parseConfig(merged.raw, origin);
                                            a.saveConfig(cfg, origin);
                                        } else {
                                            a.toast("該來源已存在，無需加入");
                                        }
                                        finished = true;
                                        if (dialog != null) dialog.dismiss();
                                    } catch (Exception e) {
                                        failure.set(e);
                                    } finally {
                                        applied.countDown();
                                    }
                                });
                        if (!applied.await(20, TimeUnit.SECONDS))
                            throw new java.io.IOException("Apply timeout");
                        if (failure.get() != null) throw failure.get();
                        return merged;
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
            String qrText = TvPush.buildQrContent(ip, port, server.key());
            LinearLayout panel = a.vertical();
            panel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            TextView note =
                    a.text("手機與電視連線同一網路，用 mkoai 掃碼後輸入源網址或貼上 JSON，直接推送到這台電視。", 15, MainActivity.MUTED);
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
            qr.setTag("tv-push-qr");
            qr.setContentDescription("手機掃碼推送二維碼");
            qr.setImageBitmap(Bitmap.createBitmap(pixels, qrSize, qrSize, Bitmap.Config.ARGB_8888));
            panel.addView(qr, new LinearLayout.LayoutParams(a.dp(190), a.dp(190)));
            TextView address = a.text(server.pushAddress(ip), 12, MainActivity.WHITE);
            address.setTag("tv-push-address");
            address.setGravity(android.view.Gravity.CENTER);
            address.setPadding(0, a.dp(10), 0, 0);
            panel.addView(address);
            TextView code =
                    a.text("配對碼：" + TvPush.pairingCode(server.key()) + "（掃不到時手動輸入用）", 13, MainActivity.WHITE);
            code.setTag("tv-push-code");
            code.setGravity(android.view.Gravity.CENTER);
            code.setPadding(0, a.dp(6), 0, 0);
            panel.addView(code);
            a.gap(panel, 6);
            panel.addView(a.text("配對碼與推送只在本次有效 · 5 分鐘有效 · 關閉後停止接收", 12, MainActivity.MUTED));
            dialog =
                    new AppDialog.Builder(a)
                            .setTitle("手機推送來源")
                            .setView(panel)
                            .setNeutralButton("遙控輸入", (d, n) -> a.addressImportDialog())
                            .setPositiveButton("重新整理", (d, n) -> a.tvPushDialog())
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
            a.toast("未能開啟區域網推送，可先使用遙控輸入或本地檔案");
            a.addressImportDialog();
        }
    }

    void close() {
        a.ui.removeCallbacks(expiry);
        if (server != null) server.close();
        if (a.tvPush == this) a.tvPush = null;
    }
}
