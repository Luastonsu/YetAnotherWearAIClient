package com.yawac.client.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import com.yawac.client.R;

/** A full-row target with the actual HeySwitch as its trailing control. */
public class ModeToggleView extends LinearLayout {
    private final TextView label;
    private final CompoundButton toggle;

    public ModeToggleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(8), 0, dp(6), 0);
        setBackgroundResource(R.drawable.bg_web_search_off);
        label = new TextView(context);
        label.setTextColor(context.getColor(R.color.text_primary));
        label.setTextSize(12);
        Typography.apply(label, context);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        View candidate = NativeUi.create(context, "HeySwitch", "hey_heyswitch_viewstub");
        toggle = candidate instanceof CompoundButton ? (CompoundButton) candidate : new Switch(context);
        Typography.apply(toggle, context);
        toggle.setContentDescription(getContentDescription());
        // The row is one accessibility target; taps on the switch use the same saved-state action.
        toggle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        toggle.setOnClickListener(v -> performClick());
        addView(toggle, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setText(CharSequence text) { label.setText(text); }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (toggle != null) toggle.setEnabled(enabled);
    }
    @Override public void setSelected(boolean selected) {
        super.setSelected(selected);
        if (toggle != null) toggle.setChecked(selected);
    }
    @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(isSelected());
    }
    CompoundButton widget() { return toggle; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
