package com.yawac.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Dots follow the pager: settings, direct chat, records. */
public final class PageIndicatorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress = 1f;
    private int count = 3;

    public PageIndicatorView(Context context, AttributeSet attrs) { super(context, attrs); }

    void setCount(int value) {
        count = Math.max(1, value);
        progress = Math.max(0f, Math.min(count - 1, progress));
        invalidate();
    }

    void setProgress(float value) {
        progress = Math.max(0f, Math.min(count - 1, value));
        setContentDescription(description(Math.round(progress)));
        invalidate();
    }

    float progress() { return progress; }

    private String description(int page) {
        if (count == 3) {
            if (page == 0) return "设置页面，左侧圆点已选中";
            if (page == 2) return "对话记录页面，右侧圆点已选中";
            return "直聊页面，中间圆点已选中";
        }
        return "第 " + (page + 1) + " 页，共 " + count + " 页";
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
        float spacing = 10 * density;
        float offset = (count - 1) / 2f;
        paint.setColor(0xFF55585E);
        for (int i = 0; i < count; i++) {
            canvas.drawCircle(centerX + (i - offset) * spacing, centerY, 2.2f * density, paint);
        }
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(centerX + (progress - offset) * spacing, centerY, 2.6f * density, paint);
    }
}
