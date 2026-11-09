package com.yawac.client.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.yawac.client.R;

/** Draws guidance above the real UI; gestures and taps pass through to actual controls. */
final class GuideOverlay extends FrameLayout {
    static final int TAP = 0, RIGHT = 1, LEFT = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private View target;
    private String[] lines = {};
    private int direction;
    private float phase = .45f;
    private ValueAnimator animator;
    private final TextView skip;

    static GuideOverlay attach(Activity activity, Runnable skipAction) {
        GuideOverlay guide = new GuideOverlay(activity, skipAction);
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);
        View page = root.getChildAt(0);
        if (page instanceof SwipeBackLayout) {
            ((FrameLayout) ((SwipeBackLayout) page).getChildAt(0)).addView(guide, new FrameLayout.LayoutParams(-1, -1));
        } else activity.addContentView(guide, new FrameLayout.LayoutParams(-1, -1));
        return guide;
    }
    private GuideOverlay(Activity activity, Runnable skipAction) {
        super(activity);
        setId(R.id.guideOverlay);
        setWillNotDraw(false); setVisibility(GONE);
        paint.setTypeface(Typography.regular(activity));
        paint.setSubpixelText(true);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        skip = new TextView(activity);
        skip.setId(R.id.skipGuide); skip.setText("跳过引导"); skip.setTextColor(0xFFB0B5BF);
        skip.setTextSize(11); skip.setGravity(Gravity.CENTER); skip.setContentDescription("跳过操作引导");
        LayoutParams params = new LayoutParams(dp(100), dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(20);
        addView(skip, params);
        skip.setOnClickListener(v -> skipAction.run());
    }
    void show(String text, View target, int direction) {
        this.lines = text.split("\n"); this.target = target; this.direction = direction;
        setContentDescription(text); setVisibility(VISIBLE);
        announceForAccessibility(text);
        stop();
        if (ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(1500);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        } else { phase = .45f; invalidate(); }
    }
    void hide() { stop(); setVisibility(GONE); }
    private void stop() { if (animator != null) { animator.cancel(); animator = null; } }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF hole;
        if (target != null && target.isShown()) {
            int[] at = new int[2], here = new int[2]; target.getLocationOnScreen(at); getLocationOnScreen(here);
            hole = new RectF(at[0] - here[0] - dp(4), at[1] - here[1] - dp(4),
                    at[0] - here[0] + target.getWidth() + dp(4), at[1] - here[1] + target.getHeight() + dp(4));
        } else hole = new RectF(dp(8), getHeight() * .34f, getWidth() - dp(18), getHeight() * .50f);
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.drawColor(0xB0000000); canvas.drawRoundRect(hole, dp(18), dp(18), clear); canvas.restoreToCount(layer);
        paint.setColor(0xFF3FD18A); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(hole, dp(18), dp(18), paint); paint.setStyle(Paint.Style.FILL);
        float centerY = hole.centerY() < getHeight() * .58f ? getHeight() * .67f : getHeight() * .43f;
        paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        float lineHeight = Math.max(dp(20), paint.getFontSpacing() + dp(4));
        float boxHeight = lines.length * lineHeight + dp(16);
        paint.setColor(0xF0202124);
        canvas.drawRoundRect(new RectF(dp(20), centerY - boxHeight / 2, getWidth() - dp(20), centerY + boxHeight / 2), dp(12), dp(12), paint);
        paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baselineOffset = -(metrics.ascent + metrics.descent) / 2;
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], getWidth() / 2f,
                centerY - (lines.length - 1) * lineHeight / 2 + i * lineHeight + baselineOffset, paint);
        paint.setColor(0xFF3FD18A);
        float x = hole.centerX(), y = hole.centerY();
        if (direction != TAP) {
            // Returning pages require a drag starting inside SwipeBackLayout's 28 dp edge.
            float from = direction == RIGHT ? hole.left + dp(12) : hole.right - dp(20);
            float to = direction == RIGHT ? hole.right - dp(20) : hole.left + dp(20);
            x = from + (to - from) * Math.min(1, phase * 1.4f);
            paint.setStrokeWidth(dp(2)); canvas.drawLine(from, y, to, y, paint);
            canvas.drawLine(to, y, to - direction * dp(7), y - dp(5), paint);
            canvas.drawLine(to, y, to - direction * dp(7), y + dp(5), paint);
        } else { x = hole.right - dp(14); y = hole.bottom - dp(10); }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
        paint.setAlpha(Math.round(230 * (1 - phase)));
        canvas.drawCircle(x, y, dp(7 + 12 * phase), paint);
        paint.setStyle(Paint.Style.FILL); paint.setAlpha(255); canvas.drawCircle(x, y, dp(4), paint);
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
