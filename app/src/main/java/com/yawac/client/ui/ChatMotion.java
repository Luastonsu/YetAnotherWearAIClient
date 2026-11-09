package com.yawac.client.ui;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class ChatMotion {
    private ChatMotion() { }
    static void reset(View view) {
        view.animate().cancel(); view.setAlpha(1f); view.setTranslationY(0f);
    }
    static void enter(View view) {
        reset(view);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0f); view.setTranslationY(dp(view, 8));
        view.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
    }
    static void panel(View panel, View card, boolean show) {
        panel.animate().cancel(); card.animate().cancel();
        panel.setTag(show);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            panel.setVisibility(show ? View.VISIBLE : View.GONE); panel.setAlpha(1f);
            card.setScaleX(1f); card.setScaleY(1f); card.setTranslationY(0);
            return;
        }
        if (show) {
            if (panel.getVisibility() != View.VISIBLE) {
                panel.setAlpha(0f); card.setScaleX(0.94f); card.setScaleY(0.94f); card.setTranslationY(dp(card, 8));
            }
            panel.setVisibility(View.VISIBLE);
            panel.animate().alpha(1f).setDuration(160).start();
            card.animate().scaleX(1f).scaleY(1f).translationY(0).setDuration(180).start();
        } else {
            panel.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (Boolean.FALSE.equals(panel.getTag())) { panel.setVisibility(View.GONE); panel.setAlpha(1f); }
            }).start();
            card.animate().scaleX(0.96f).scaleY(0.96f).translationY(dp(card, 6)).setDuration(140).start();
        }
    }
    static void latest(View view, boolean show) {
        view.animate().cancel(); view.setTag(show);
        if (!ValueAnimator.areAnimatorsEnabled()) { view.setVisibility(show ? View.VISIBLE : View.INVISIBLE); reset(view); return; }
        if (show) {
            view.setVisibility(View.VISIBLE); view.setAlpha(0); view.setTranslationY(-dp(view, 6));
            view.animate().alpha(1f).translationY(0).setDuration(160).start();
        } else {
            view.animate().alpha(0).translationY(-dp(view, 6)).setDuration(120).withEndAction(() -> {
                if (Boolean.FALSE.equals(view.getTag())) { view.setVisibility(View.INVISIBLE); reset(view); }
            }).start();
        }
    }
    private static float dp(View view, int value) { return value * view.getResources().getDisplayMetrics().density; }
}
