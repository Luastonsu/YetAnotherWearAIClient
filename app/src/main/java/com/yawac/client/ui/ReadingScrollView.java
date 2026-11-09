package com.yawac.client.ui;

import android.content.Context;
import android.util.AttributeSet;
import androidx.core.widget.NestedScrollView;

/** The viewport owns vertical gestures; Markdown's TextView only handles link taps. */
public class ReadingScrollView extends NestedScrollView {
    public ReadingScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Some watch text widgets reserve the entire gesture for their movement method.
        // Let this single vertical viewport intercept a drag even when its text asks otherwise.
        super.requestDisallowInterceptTouchEvent(false);
    }
}
