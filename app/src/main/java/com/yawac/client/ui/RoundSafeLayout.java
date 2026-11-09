package com.yawac.client.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

public class RoundSafeLayout extends FrameLayout {

    private final Rect insets = new Rect();

    public RoundSafeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnApplyWindowInsetsListener(new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets windowInsets) {
                android.graphics.Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insets.set(
                        bars.left, bars.top, bars.right, bars.bottom);
                requestLayout();
                return windowInsets;
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setPadding(insets.left, insets.top, insets.right, insets.bottom);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
