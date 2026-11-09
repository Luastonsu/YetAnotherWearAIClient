package com.yawac.client.ui;

import android.os.Bundle;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.yawac.client.R;
import com.yawac.client.data.ApiConfigStore;
import com.yawac.client.net.ApiConfig;

public class ApiSettingsActivity extends SettingsScreenActivity {
    private EditText url, key;
    private AutoCompleteTextView model;
    private TextView status;
    private ApiConfigStore store;
    private GuideOverlay guide;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        openSettingsLayout(R.layout.activity_api_settings);
        nativeButtons(R.id.deepSeekPreset, R.id.chooseModel, R.id.saveApiConfig, R.id.clearApiConfig);
        store = new ApiConfigStore(this);
        url = findViewById(R.id.apiUrl);
        key = findViewById(R.id.apiKey);
        model = findViewById(R.id.apiModel);
        status = findViewById(R.id.apiStatus);
        model.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line,
                ApiConfig.DEEPSEEK_MODELS));
        model.setThreshold(0);
        findViewById(R.id.chooseModel).setOnClickListener(v -> model.showDropDown());
        findViewById(R.id.deepSeekPreset).setOnClickListener(v -> {
            url.setText(ApiConfig.DEEPSEEK_URL);
            model.setText(ApiConfig.DEFAULT_MODEL, false);
            status.setText("已填入 DeepSeek 预设，请填写自己的密钥后保存");
        });
        findViewById(R.id.saveApiConfig).setOnClickListener(v -> save());
        findViewById(R.id.clearApiConfig).setOnClickListener(v -> {
            store.clear();
            key.setText("");
            Toast.makeText(this, "已清除 API 配置", Toast.LENGTH_SHORT).show();
            finish();
        });
        model.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save();
            return true;
        });
        try {
            ApiConfig config = store.load();
            url.setText(config.url);
            key.setText(config.apiKey);
            model.setText(config.model, false);
        } catch (Exception e) {
            url.setText(ApiConfig.DEEPSEEK_URL);
            model.setText(ApiConfig.DEFAULT_MODEL, false);
            status.setText("原配置无法解密，请重新填写并保存");
        }
    }

    private void save() {
        ApiConfig config = new ApiConfig(url.getText().toString(), key.getText().toString(),
                model.getText().toString());
        String error = config.validationError();
        if (error != null) { status.setText(error); return; }
        try {
            store.save(config);
            Toast.makeText(this, "已保存，将用于下一次请求", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            status.setText("保存失败，请重试；配置未生效");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (Onboarding.active(this) && Onboarding.step(this) == Onboarding.PROVIDER_BACK) {
            if (guide == null) guide = GuideOverlay.attach(this, () -> { Onboarding.complete(this); guide.hide(); });
            guide.show("这里填写地址、Key 和模型\n从左侧向右滑动返回", null, GuideOverlay.RIGHT);
        }
    }
    @Override protected void onPause() { if (guide != null) guide.hide(); super.onPause(); }
}
