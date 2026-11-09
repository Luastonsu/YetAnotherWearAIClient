package com.yawac.client.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** Horizontal pager: settings (left), direct chat (middle), records (right). */
public class HomePagerLayout extends FrameLayout {
    private final int slop;
    private float downX, downY, progress, start;
    private int page = 1;
    private boolean candidate, dragging, pagingEnabled = true;
    private VelocityTracker velocity;
    private ValueAnimator animator;
    interface Listener { void onProgress(float progress); void onUserPage(int page); }
    private Listener listener;

    public HomePagerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0 && page >= getChildCount()) page = getChildCount() - 1;
        updatePages();
    }

    public int getPage() { return page; }
    public float getProgress() { return progress; }
    public boolean isSettingsPage() { return page == 0; }

    public void setPagingEnabled(boolean enabled) {
        pagingEnabled = enabled;
        if (!enabled) {
            dragging = candidate = false;
            recycleVelocity();
            showPage(page, false);
        }
    }

    void setListener(Listener value) {
        listener = value;
        updatePages();
    }

    /** Compatibility with the old two-page home; settings stays page 0. */
    public void showSettings(boolean show, boolean animate) {
        showPage(show ? 0 : 1, animate);
    }

    public void showPage(int index, boolean animate) {
        if (getChildCount() == 0) return;
        int target = Math.max(0, Math.min(getChildCount() - 1, index));
        page = target;
        if (animator != null) animator.cancel();
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || getWidth() == 0) {
            progress = target;
            updatePages();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            updatePages();
        });
        animator.start();
    }

    private void updatePages() {
        int count = getChildCount();
        if (count == 0) return;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            child.setTranslationX((i - progress) * getWidth());
            child.setImportantForAccessibility(i == page ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        setContentDescription(pageDescription(page));
        if (listener != null) listener.onProgress(progress);
    }

    private String pageDescription(int index) {
        switch (index) {
            case 0: return "设置页面，右滑回到直聊";
            case 2: return "对话记录页面，左滑回到直聊";
            default: return "直聊页面，右滑设置，左滑对话记录";
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        showPage(page, false);
    }

    @Override public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Clickable settings rows or the message input may ask their parent not to
        // intercept; horizontal page drags still need to work. Vertical scrolling
        // remains with the child because onIntercept only takes horizontal drags.
        super.requestDisallowInterceptTouchEvent(false);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!pagingEnabled) return false;        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (animator != null) {
                    animator.cancel();
                    page = Math.round(progress);
                }
                downX = event.getX(); downY = event.getY(); start = progress;
                candidate = true; dragging = false;
                recycleVelocity();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (candidate && Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) candidate = false;
                if (candidate && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    boolean direction = dx < 0 ? page < getChildCount() - 1 : page > 0;
                    candidate = false;
                    if (direction) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                candidate = false;
                recycleVelocity();
                if (progress != page) showPage(page, true);
                break;
        }
        return dragging;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!pagingEnabled || !dragging) return super.onTouchEvent(event);
        if (velocity != null) velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float width = Math.max(1, getWidth());
                progress = Math.max(0f, Math.min(getChildCount() - 1f,
                        start - (event.getX() - downX) / width));
                updatePages();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                if (velocity != null) velocity.computeCurrentVelocity(1000);
                float speed = velocity == null ? 0 : velocity.getXVelocity();
                boolean complete = Math.abs(dx) > getWidth() * 0.3f
                        || (Math.abs(dx) > slop && Math.abs(speed) > 900 && Math.signum(speed) == Math.signum(dx));
                dragging = false;
                recycleVelocity();
                int previous = page;
                if (complete) page = Math.max(0, Math.min(getChildCount() - 1, dx < 0 ? page + 1 : page - 1));
                showPage(page, true);
                if (listener != null && previous != page) listener.onUserPage(page);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                recycleVelocity();
                showPage(page, true);
                return true;
        }
        return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(pagingEnabled && getChildCount() > 1);
        if (page > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (page < getChildCount() - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (pagingEnabled && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            int previous = page;
            showPage(page + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1), true);
            if (listener != null && previous != page) listener.onUserPage(page);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        recycleVelocity();
        super.onDetachedFromWindow();
    }

    private void recycleVelocity() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
}
