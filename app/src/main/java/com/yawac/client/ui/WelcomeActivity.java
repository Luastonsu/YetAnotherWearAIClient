package com.yawac.client.ui;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.drawable.ColorDrawable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.yawac.client.R;

/** First launch requires an explicit choice; the retired feature list is removed. */
public class WelcomeActivity extends AppCompatActivity {
    private Dialog agreement;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Onboarding.accepted(this)) { openHome(); return; }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        agreement = new Dialog(this);
        agreement.requestWindowFeature(Window.FEATURE_NO_TITLE);
        agreement.setContentView(R.layout.activity_welcome);
        agreement.setCancelable(false);
        NativeUi.shapeButton(agreement.findViewById(R.id.agreeTerms), getColor(R.color.brand_surface_high));
        NativeUi.shapeButton(agreement.findViewById(R.id.declineTerms), getColor(R.color.brand_surface));
        agreement.findViewById(R.id.agreeTerms).setOnClickListener(v -> { Onboarding.accept(this); openHome(); });
        agreement.findViewById(R.id.declineTerms).setOnClickListener(v -> finish());
        agreement.setOnKeyListener((dialog, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) finish();
            return true;
        });
        agreement.show();
        Window window = agreement.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowCompat.getInsetsController(window, window.getDecorView()).hide(WindowInsetsCompat.Type.systemBars());
    }
    private void openHome() { startActivity(new Intent(this, MainActivity.class)); finish(); }
    Dialog agreementDialog() { return agreement; }
    @Override protected void onDestroy() { if (agreement != null) agreement.dismiss(); super.onDestroy(); }
}
