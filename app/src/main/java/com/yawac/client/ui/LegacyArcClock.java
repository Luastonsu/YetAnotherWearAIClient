package com.yawac.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.TextClock;

/** A timezone-aware native clock with a shallow arc on round screens. */
final class LegacyArcClock extends TextClock {
    private final Path arc = new Path();
    private final RectF bounds = new RectF();

    public LegacyArcClock(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
        setFormat12Hour("h:mm");
        setFormat24Hour("HH:mm");
        setIncludeFontPadding(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        Paint.FontMetrics metrics = getPaint().getFontMetrics();
        float radius = getResources().getDisplayMetrics().widthPixels * 0.75f;
        float halfAngle = getPaint().measureText(getText().toString()) / (2f * radius);
        float drop = radius * (1f - (float) Math.cos(halfAngle));
        int desired = (int) Math.ceil(metrics.descent - metrics.ascent + gap() * 2 + drop);
        setMeasuredDimension(getMeasuredWidth(), resolveSize(Math.max(getMinimumHeight(), desired), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!getResources().getConfiguration().isScreenRound()) {
            super.onDraw(canvas);
            return;
        }
        Paint paint = getPaint();
        paint.setColor(getCurrentTextColor());
        paint.setTextAlign(Paint.Align.LEFT);
        String time = getText().toString();
        float radius = getResources().getDisplayMetrics().widthPixels * 0.75f;
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = metrics.descent - metrics.ascent - 2.5f * getResources().getDisplayMetrics().density;
        float center = getWidth() / 2f;
        bounds.set(center - radius, baseline, center + radius, baseline + radius * 2);
        arc.reset();
        arc.addArc(bounds, 180f, 180f);
        float offset = ((float) Math.PI * radius - paint.measureText(time)) / 2f;
        canvas.drawTextOnPath(time, arc, offset, 0f, paint);
    }

    private float gap() { return 3f * getResources().getDisplayMetrics().density; }
}
