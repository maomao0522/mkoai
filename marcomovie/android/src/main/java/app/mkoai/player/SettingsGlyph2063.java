// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * mkoai 2.0.63 (phase 2a): flat near-white vector glyphs for the legacy
 * {@link SettingsScreen} phone rows, so the 帳號同步 page reads as the same
 * flat Level-2 language as {@code MobileIosSettings} (24dp plain glyph,
 * no coloured badge disc). Pure drawing code — no state, no background
 * work, no network. Kinds are mapped from the row key/title in
 * {@link #kindFor(String, String)}.
 */
final class SettingsGlyph2063 extends View {
    static final int SYNC = 1;
    static final int SIGN_IN = 2;
    static final int SIGN_OUT = 3;
    static final int QR = 4;
    static final int INFO = 5;

    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    SettingsGlyph2063(MainActivity a, int kind) {
        super(a);
        this.kind = kind;
        stroke.setColor(0xfff5f5f7);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, a.dp(1.8f)));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(0xfff5f5f7);
        fill.setStyle(Paint.Style.FILL);
    }

    /** Map a SettingsScreen link key/title to a glyph kind. Unknown -> INFO. */
    static int kindFor(String key, String title) {
        String k = key == null ? "" : key;
        if (k.contains("sync-now")) return SYNC;
        if (k.contains("sync-signout")) return SIGN_OUT;
        if (k.contains("sync-signin")) return SIGN_IN;
        if (k.contains("sync-qr-login")) return QR;
        return INFO;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float r = w * 0.34f;
        switch (kind) {
            case SYNC:
                {
                    // Circular refresh: one 300-degree arc + arrowhead at its end.
                    rect.set(cx - r, cy - r, cx + r, cy + r);
                    c.drawArc(rect, -90f, 300f, false, stroke);
                    // Arrowhead at the arc end (angle 210 deg, y-down coords).
                    double endRad = Math.toRadians(210f);
                    float ex = cx + r * (float) Math.cos(endRad);
                    float ey = cy + r * (float) Math.sin(endRad);
                    path.reset();
                    path.moveTo(ex - w * 0.02f, ey - h * 0.16f);
                    path.lineTo(ex + w * 0.14f, ey + h * 0.02f);
                    path.lineTo(ex - w * 0.13f, ey + h * 0.11f);
                    path.close();
                    c.drawPath(path, fill);
                    break;
                }
            case SIGN_IN:
                {
                    // Person: head circle + shoulders arc.
                    c.drawCircle(cx, h * 0.32f, w * 0.13f, fill);
                    rect.set(w * 0.20f, h * 0.52f, w * 0.80f, h * 1.06f);
                    c.drawArc(rect, 180f, 180f, false, stroke);
                    break;
                }
            case SIGN_OUT:
                {
                    // Door outline + exit arrow.
                    rect.set(w * 0.14f, h * 0.18f, w * 0.58f, h * 0.82f);
                    c.drawRoundRect(rect, w * 0.06f, w * 0.06f, stroke);
                    float ay = h * 0.50f;
                    c.drawLine(w * 0.42f, ay, w * 0.82f, ay, stroke);
                    path.reset();
                    path.moveTo(w * 0.82f, ay);
                    path.lineTo(w * 0.68f, ay - h * 0.10f);
                    path.lineTo(w * 0.68f, ay + h * 0.10f);
                    path.close();
                    c.drawPath(path, fill);
                    break;
                }
            case QR:
                {
                    // Four corner brackets (arm length m - s) + centre dot.
                    float s = w * 0.16f;
                    float e = w * 0.84f;
                    float m = w * 0.30f;
                    float arm = m - s;
                    // Top-left.
                    c.drawLine(s, m, s, s, stroke);
                    c.drawLine(s, s, m, s, stroke);
                    // Top-right.
                    c.drawLine(e, m, e, s, stroke);
                    c.drawLine(e, s, e - arm, s, stroke);
                    // Bottom-left.
                    c.drawLine(s, e - arm, s, e, stroke);
                    c.drawLine(s, e, m, e, stroke);
                    // Bottom-right.
                    c.drawLine(e, e - arm, e, e, stroke);
                    c.drawLine(e, e, e - arm, e, stroke);
                    c.drawCircle(cx, cy, w * 0.06f, fill);
                    break;
                }
            case INFO:
            default:
                {
                    c.drawCircle(cx, cy, r, stroke);
                    c.drawCircle(cx, h * 0.33f, w * 0.045f, fill);
                    c.drawLine(cx, h * 0.46f, cx, h * 0.70f, stroke);
                    break;
                }
        }
    }
}
