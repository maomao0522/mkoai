package app.mkoai.player;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera QR scanner for the TV push flow. Holds the phone in portrait and
 * points it at the TV; on a successful decode it returns the QR text to the
 * caller ("qr_text" extra). The camera permission is handled here, so the
 * caller does not need it. No camera → toast and finish (manual entry stays
 * available in the push dialog).
 */
public class QrScanActivity extends Activity
        implements SurfaceHolder.Callback, Camera.PreviewCallback {
    static final int REQUEST_CAMERA = 1;

    private Camera camera;
    private SurfaceView preview;
    private ExecutorService decoder;
    private volatile boolean found;
    private volatile boolean decoding;
    private volatile long lastAttempt;
    private int previewWidth, previewHeight;
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
        decoder = Executors.newSingleThreadExecutor();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("對準電視上的 QR 碼");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.TOP;
        hintLp.topMargin = 64;
        root.addView(hint, hintLp);

        TextView close = new TextView(this);
        close.setText("關閉");
        close.setTextColor(Color.WHITE);
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setPadding(48, 28, 48, 28);
        close.setBackgroundColor(0x66222222);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        closeLp.bottomMargin = 64;
        root.addView(close, closeLp);

        setContentView(root);
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[] {android.Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(
                                this,
                                "需要相機權限才能掃碼，可改用手動輸入 IP 與配對碼",
                                Toast.LENGTH_LONG)
                        .show();
                finish();
            }
        }
    }

    private void startCamera() {
        if (camera != null) return;
        // 2.0.75: the preview surface does not exist yet when onCreate runs
        // (it is created asynchronously after setContentView). Opening the
        // camera and calling setPreviewDisplay on the not-yet-created
        // surface starts a preview that renders nowhere — a black screen —
        // and surfaceCreated used to skip the re-attach because camera was
        // already non-null. Wait for the surface instead; surfaceCreated
        // calls startCamera() again once it exists.
        if (preview.getHolder().getSurface() == null
                || !preview.getHolder().getSurface().isValid()) {
            return;
        }
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = params.getPreviewSize();
            previewWidth = size.width;
            previewHeight = size.height;
            try {
                camera.setDisplayOrientation(90);
            } catch (Exception ignored) {
            }
            camera.setPreviewDisplay(preview.getHolder());
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            Toast.makeText(this, "無法開啟相機，可改用手動輸入", Toast.LENGTH_LONG).show();
            releaseCamera();
            finish();
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (camera == null) {
            startCamera();
            return;
        }
        // 2.0.75: the camera was already opened (e.g. onCreate raced ahead
        // of the surface, or the surface was recreated). The preview display
        // was attached to the old / not-yet-created surface, so re-attach it
        // to this holder or the preview stays black.
        try {
            camera.stopPreview();
        } catch (Exception ignored) {
        }
        try {
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (Exception e) {
            Toast.makeText(this, "無法開啟相機，可改用手動輸入", Toast.LENGTH_LONG).show();
            releaseCamera();
            finish();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera camera) {
        if (found || decoding || data == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 300) return;
        lastAttempt = now;
        decoding = true;
        final byte[] copy = data.clone();
        final int w = previewWidth, h = previewHeight;
        if (w <= 0 || h <= 0) {
            decoding = false;
            return;
        }
        decoder.execute(
                () -> {
                    try {
                        PlanarYUVLuminanceSource source =
                                new PlanarYUVLuminanceSource(copy, w, h, 0, 0, w, h, false);
                        Result result =
                                new QRCodeReader()
                                        .decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
                        String text = result.getText();
                        if (text != null && !text.isEmpty() && !found) {
                            found = true;
                            runOnUiThread(
                                    () -> {
                                        setResult(
                                                RESULT_OK,
                                                new Intent().putExtra("qr_text", text));
                                        finish();
                                    });
                        }
                    } catch (Exception ignored) {
                        // No QR in this frame; keep scanning.
                    } finally {
                        decoding = false;
                    }
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        if (decoder != null) decoder.shutdownNow();
    }
}
