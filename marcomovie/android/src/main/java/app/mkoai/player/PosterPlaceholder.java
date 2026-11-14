package app.mkoai.player;

/** Readable artwork fallback; never substitutes another programme's cover. */
final class PosterPlaceholder extends android.graphics.drawable.Drawable {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final String title;
    PosterPlaceholder(String title) { this.title = title == null ? "" : title; }
    @Override public void draw(android.graphics.Canvas canvas) {
        android.graphics.Rect b = getBounds();
        paint.setColor(0xff202823); canvas.drawRect(b, paint);
        paint.setColor(0xffa7b5ae); paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        paint.setTextSize(Math.max(12, Math.min(b.width()/12f, b.height()/12f)));
        String label = title.length() > 11 ? title.substring(0, 10) + "…" : title;
        canvas.drawText(label, b.exactCenterX(), b.exactCenterY(), paint);
        paint.setTextSize(paint.getTextSize()*0.8f);
        canvas.drawText("暫無封面", b.exactCenterX(), b.exactCenterY()+paint.getTextSize()*1.8f, paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
}
