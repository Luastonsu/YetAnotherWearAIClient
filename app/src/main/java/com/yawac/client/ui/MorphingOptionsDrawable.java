package com.yawac.client.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Three slider bars thicken and join into a stop square without swapping images. */
final class MorphingOptionsDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private float progress;
    private int tint, stopTint, alpha = 255;
    MorphingOptionsDrawable(float density) { size = Math.round(24 * density); }
    void frame(float value, int idle, int stop) { progress = value; tint = idle; stopTint = stop; invalidateSelf(); }

    @Override public void draw(Canvas canvas) {
        int saved = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        int color = blend(tint, stopTint, progress);
        paint.setColor(color); paint.setAlpha(Math.round(Color.alpha(color) * alpha / 255f));
        float fill = ComposerMorphLayout.smooth(.64f, 1, progress);
        paint.setAlpha(Math.round(alpha * fill));
        canvas.drawRoundRect(4, 4, 20, 20, .8f, .8f, paint);
        paint.setAlpha(alpha);
        for (int i = 0; i < 3; i++) {
            float center = mix(6 + i * 6, 6.6f + i * 5.4f, progress);
            float halfHeight = mix(.9f, 2.8f, progress);
            float left = mix(3, 4, progress), right = mix(21, 20, progress);
            canvas.drawRoundRect(left, center - halfHeight, right, center + halfHeight, .7f, .7f, paint);
            float handleX = i == 0 ? 10 : i == 1 ? 16 : 8;
            float halfWidth = 1.6f * (1 - progress), handleHeight = 3 * (1 - progress);
            canvas.drawRoundRect(handleX - halfWidth, center - handleHeight, handleX + halfWidth,
                    center + handleHeight, .5f, .5f, paint);
        }
        canvas.restoreToCount(saved);
    }
    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static int blend(int a, int b, float t) {
        return Color.argb(Math.round(mix(Color.alpha(a), Color.alpha(b), t)), Math.round(mix(Color.red(a), Color.red(b), t)),
                Math.round(mix(Color.green(a), Color.green(b), t)), Math.round(mix(Color.blue(a), Color.blue(b), t)));
    }
}
