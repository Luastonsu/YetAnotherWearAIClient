package com.yawac.client.ui;

import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import com.yawac.client.R;

/** The window is transparent; its foreground surface owns the full opaque page background. */
public abstract class SwipeBackActivity extends AppCompatActivity {
    private SwipeBackLayout swipeBack;

    @Override public void setContentView(int layout) {
        super.setContentView(layout);
        View root = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        if (root instanceof SwipeBackLayout) {
            swipeBack = (SwipeBackLayout) root;
            swipeBack.getChildAt(0).setBackgroundColor(getColor(R.color.brand_bg));
            swipeBack.setOnSwipeFinish(this::finish);
        }
    }

    @Override public void finish() {
        boolean alreadySlidOut = swipeBack != null && swipeBack.isPageDismissed();
        super.finish();
        if (alreadySlidOut) overridePendingTransition(0, 0);
    }
}
