package com.yawac.client.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;

/** Continuous touch preview with four discrete, server-backed settings committed on release. */
public final class ReasoningSlider extends View {
    interface Listener { void onLevelChanged(int level); }
    private static final String[] LABELS = {"关闭", "低", "高", "最高"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int slop;
    private final float density, scaledDensity;
    private Listener listener;
    private int level, resumeLevel = 2, shownLevel, previousLabel;
    private float position, press, labelAlpha = 1, downX, downY;
    private boolean touching, cancelled, dragging;
    private ValueAnimator settleAnimator, pressAnimator, labelAnimator;

    public ReasoningSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        paint.setTypeface(Typography.regular(context));
        paint.setSubpixelText(true);
        setFocusable(true); setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void setListener(Listener value) { listener = value; }
    int level() { return level; }
    float visualPosition() { return position; }
    void setLevel(int value, int enabledLevel, boolean animate) {
        level = Math.max(0, Math.min(3, value));
        resumeLevel = Math.max(1, Math.min(3, enabledLevel));
        super.setSelected(level > 0);
        updateDescription();
        if (!touching) moveTo(level, animate);
    }
    private void updateDescription() {
        setContentDescription(isEnabled() ? "思考强度，" + LABELS[level] + "，左右滑动调节"
                : "思考强度，当前服务不支持");
    }
    @Override public void setEnabled(boolean enabled) {
        if (!enabled) settle();
        super.setEnabled(enabled); updateDescription(); invalidate();
    }
    private void showLabel(int next, boolean animate) {
        if (next == shownLevel) return;
        if (labelAnimator != null) labelAnimator.cancel();
        previousLabel = shownLevel; shownLevel = next;
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { labelAlpha = 1; return; }
        labelAnimator = ValueAnimator.ofFloat(0, 1); labelAnimator.setDuration(120);
        labelAnimator.addUpdateListener(a -> { labelAlpha = (float) a.getAnimatedValue(); invalidate(); });
        labelAnimator.start();
    }
    private void preview(float value) {
        position = Math.max(0, Math.min(3, value));
        int next = Math.round(position);
        if (touching && !cancelled && next != shownLevel) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        showLabel(next, true); invalidate();
    }
    private void moveTo(float target, boolean animate) {
        if (settleAnimator != null) settleAnimator.cancel();
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            position = target; showLabel(Math.round(target), false); invalidate(); return;
        }
        if (Math.abs(position - target) < .001f) return;
        settleAnimator = ValueAnimator.ofFloat(position, target); settleAnimator.setDuration(220);
        settleAnimator.setInterpolator(new DecelerateInterpolator(2));
        settleAnimator.addUpdateListener(a -> preview((float) a.getAnimatedValue())); settleAnimator.start();
    }
    private void pressed(boolean value) {
        if (pressAnimator != null) pressAnimator.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) { press = value ? 1 : 0; invalidate(); return; }
        pressAnimator = ValueAnimator.ofFloat(press, value ? 1 : 0); pressAnimator.setDuration(value ? 90 : 160);
        pressAnimator.addUpdateListener(a -> { press = (float) a.getAnimatedValue(); invalidate(); }); pressAnimator.start();
    }
    private float at(float x) { return (x - dp(12)) / Math.max(1, getWidth() - dp(24)) * 3; }
    private void commit(int value) {
        int old = level; level = Math.max(0, Math.min(3, value));
        if (level > 0) resumeLevel = level;
        super.setSelected(level > 0); updateDescription(); moveTo(level, true);
        if (old != level) {
            if (listener != null) listener.onLevelChanged(level);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (settleAnimator != null) settleAnimator.cancel();
                touching = true; cancelled = false; dragging = false;
                downX = event.getX(); downY = event.getY(); pressed(true); preview(at(downX)); return true;
            case MotionEvent.ACTION_MOVE:
                if (!touching) return false;
                float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
                if (!dragging && dy > slop && dy > dx) { cancelled = true; moveTo(level, true); }
                if (!cancelled) {
                    if (dx > slop && dx > dy) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    preview(at(event.getX()));
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!touching) return false;
                touching = false; pressed(false);
                if (!cancelled) commit(Math.round(Math.max(0, Math.min(3, at(event.getX())))));
                else moveTo(level, true);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                touching = false; cancelled = true; pressed(false); moveTo(level, true);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return true;
    }
    @Override public boolean performClick() {
        if (!isEnabled()) return false;
        super.performClick(); commit(level == 0 ? resumeLevel : 0); return true;
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (isEnabled() && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            commit(level + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)); return true;
        }
        return super.onKeyDown(key, event);
    }
    @Override public CharSequence getAccessibilityClassName() { return SeekBar.class.getName(); }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 3, level));
        if (isEnabled()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            if (level < 3) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (level > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (isEnabled()) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { commit(level + 1); return true; }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { commit(level - 1); return true; }
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null) {
                float value = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, level);
                if (Float.isNaN(value) || Float.isInfinite(value)) return false;
                commit(Math.round(value)); return true;
            }
        }
        return super.performAccessibilityAction(action, args);
    }
    void settle() {
        if (settleAnimator != null) settleAnimator.cancel();
        if (pressAnimator != null) pressAnimator.cancel();
        if (labelAnimator != null) labelAnimator.cancel();
        touching = false; position = level; press = 0; shownLevel = level; labelAlpha = 1;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
    }
    @Override protected void onDetachedFromWindow() { settle(); super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(12), right = getWidth() - dp(12), y = dp(36), x = left + (right - left) * position / 3;
        paint.setTextSize(sp(12)); paint.setTextAlign(Paint.Align.LEFT); paint.setColor(0xFFB0B5BF);
        canvas.drawText("思考强度", dp(10), dp(15), paint);
        paint.setTextAlign(Paint.Align.RIGHT); paint.setColor(0xFF3FD18A);
        if (!isEnabled()) { paint.setColor(0xFFB0B5BF); canvas.drawText("不支持", getWidth() - dp(10), dp(15), paint); }
        else {
            paint.setAlpha(Math.round(255 * (1 - labelAlpha)));
            canvas.drawText(LABELS[previousLabel], getWidth() - dp(10), dp(15) - dp(3) * labelAlpha, paint);
            paint.setAlpha(Math.round(255 * labelAlpha));
            canvas.drawText(LABELS[shownLevel], getWidth() - dp(10), dp(15) + dp(3) * (1 - labelAlpha), paint);
        }
        paint.setAlpha(255); paint.setColor(0xFF55585E); paint.setStrokeWidth(dp(3)); paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(left, y, right, y, paint);
        paint.setColor(0xFF3FD18A); canvas.drawLine(left, y, x, y, paint);
        paint.setTextSize(sp(9)); paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 3; i++) {
            float tick = left + (right - left) * i / 3;
            paint.setColor(i <= Math.round(position) && position > .1f ? 0xFF3FD18A : 0xFF777B83);
            canvas.drawCircle(tick, y, dp(2), paint);
            paint.setColor(i == Math.round(position) ? 0xFFFFFFFF : 0xFFB0B5BF);
            canvas.drawText(LABELS[i], tick, dp(59), paint);
        }
        paint.setColor(0x303FD18A); canvas.drawCircle(x, y, dp(10 + 3 * press), paint);
        paint.setColor(0xFFFFFFFF); canvas.drawCircle(x, y, dp(5 + 2 * press), paint);
    }
    private float dp(float value) { return value * density; }
    private float sp(float value) { return value * scaledDensity; }
}
