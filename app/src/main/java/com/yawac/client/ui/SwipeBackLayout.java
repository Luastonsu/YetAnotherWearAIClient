package com.yawac.client.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import java.util.function.Supplier;

/** Moves one opaque foreground surface over its real parent, without fading either page. */
public class SwipeBackLayout extends FrameLayout {
    public interface Callback { void onSwipedFinish(); }
    private View content, target;
    private Callback callback;
    private Supplier<View> targetProvider;
    private final int edgeSize, slop, minFling, maxFling;
    private float downX, downY, startTranslation;
    private boolean candidate, tracking, closing, aborted;
    private VelocityTracker velocity;
    private ValueAnimator settle;

    public SwipeBackLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration config = ViewConfiguration.get(context);
        edgeSize = dp(28); slop = config.getScaledTouchSlop();
        minFling = Math.max(config.getScaledMinimumFlingVelocity(), dp(450));
        maxFling = config.getScaledMaximumFlingVelocity();
    }

    public void setOnSwipeFinish(Callback value) { callback = value; }
    public void setSwipeTargetProvider(Supplier<View> provider) { targetProvider = provider; }
    public boolean isPageDismissed() {
        return target == content && content != null && content.getTranslationX() >= getWidth() - 1;
    }
    public void resetPosition() {
        cancelSettle(); releaseVelocity();
        if (target != null) target.setTranslationX(0);
        if (content != null) content.setTranslationX(0);
        candidate = tracking = closing = aborted = false;
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0) content = getChildAt(0);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Collect DOWN/UP too, even when the child initially owns the touch stream.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !closing) begin(event);
        if (velocity != null) velocity.addMovement(event);
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            candidate = tracking = false; aborted = true;
            releaseVelocity();
            if (target != null && target.getTranslationX() > 0) animateTo(0, false, 0);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            candidate = false; releaseVelocity();
        }
        return handled;
    }

    private void begin(MotionEvent event) {
        cancelSettle(); releaseVelocity();
        View next = targetProvider == null ? content : targetProvider.get();
        if (target != null && target != next) target.setTranslationX(0);
        target = next;
        downX = event.getX(); downY = event.getY();
        startTranslation = target == null ? 0 : target.getTranslationX();
        candidate = target != null && target.getVisibility() == VISIBLE
                && (downX <= edgeSize || startTranslation > 0);
        tracking = aborted = false;
        if (candidate) velocity = VelocityTracker.obtain();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (closing || aborted) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) recognize(event);
        return tracking;
    }

    private void recognize(MotionEvent event) {
        if (candidate && !tracking) {
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx) || dx < -slop) candidate = false;
            else if (dx > slop && dx > Math.abs(dy) * 1.2f) {
                tracking = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
        }
        if (tracking) moveWith(event);
    }

    private void moveWith(MotionEvent event) {
        target.setTranslationX(Math.max(0, Math.min(getWidth(), startTranslation + event.getX() - downX)));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (closing || aborted) return true;
        if (target == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: return candidate;
            case MotionEvent.ACTION_MOVE:
                recognize(event);
                if (!tracking) return candidate;
                moveWith(event);
                return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) return false;
                moveWith(event);
                velocity.computeCurrentVelocity(1000, maxFling);
                float vx = velocity.getXVelocity(), distance = target.getTranslationX();
                boolean finish = distance > getWidth() * .35f && vx > -minFling
                        || vx > minFling && distance > slop;
                tracking = candidate = false;
                animateTo(finish ? getWidth() : 0, finish, vx);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!tracking && startTranslation == 0) return false;
                tracking = candidate = false; animateTo(0, false, 0); return true;
        }
        return candidate;
    }

    private void animateTo(float destination, boolean finish, float speed) {
        cancelSettle(); closing = finish;
        View surface = target;
        if (surface == null) return;
        float remaining = Math.abs(destination - surface.getTranslationX());
        if (!ValueAnimator.areAnimatorsEnabled() || remaining < 1) {
            surface.setTranslationX(destination); settled(finish); return;
        }
        settle = ValueAnimator.ofFloat(surface.getTranslationX(), destination);
        long duration = Math.abs(speed) > minFling ? (long) (remaining * 1000 / Math.abs(speed))
                : (long) (100 + 140 * remaining / Math.max(1, getWidth()));
        settle.setDuration(Math.max(80, Math.min(240, duration)));
        settle.setInterpolator(new DecelerateInterpolator(1.5f));
        settle.addUpdateListener(value -> surface.setTranslationX((float) value.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                settle = null; settled(finish);
            }
        });
        settle.start();
    }

    private void settled(boolean finish) {
        closing = false;
        if (finish && callback != null) callback.onSwipedFinish();
        else if (finish) resetPosition();
    }
    private void cancelSettle() {
        if (settle != null) { settle.cancel(); settle = null; }
    }
    private void releaseVelocity() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
    @Override protected void onDetachedFromWindow() {
        cancelSettle(); releaseVelocity(); super.onDetachedFromWindow();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
