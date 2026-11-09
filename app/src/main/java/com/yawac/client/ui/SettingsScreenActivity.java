package com.yawac.client.ui;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.yawac.client.R;

/** Shared round-screen navigation; the clock and controls use the existing OEM bridge. */
abstract class SettingsScreenActivity extends SwipeBackActivity {
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override protected void onResume() {
        super.onResume();
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).hide(WindowInsetsCompat.Type.systemBars());
    }

    protected void openSettingsLayout(int layout) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(layout);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());
        ((SwipeBackLayout) findViewById(R.id.settingsSwipe)).setOnSwipeFinish(this::finish);
    }

    protected void nativeButtons(int... ids) {
        for (int id : ids) NativeUi.shapeButton(findViewById(id), getColor(R.color.brand_surface));
    }
}
