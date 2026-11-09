package com.yawac.client.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;

import com.yawac.client.R;
import com.yawac.client.data.CacheFiles;
import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.Models.Chat;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StorageActivity extends SettingsScreenActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ChatRepository repo;
    private View confirm;
    private TextView info, status;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        openSettingsLayout(R.layout.activity_storage);
        nativeButtons(R.id.clearCache, R.id.cancelClearChats);
        NativeUi.shapeButton(findViewById(R.id.clearChats), getColor(R.color.brand_error));
        NativeUi.shapeButton(findViewById(R.id.confirmClearChats), getColor(R.color.brand_error));
        ((TextView) findViewById(R.id.clearChats)).setTextColor(getColor(R.color.ai_text));
        ((TextView) findViewById(R.id.confirmClearChats)).setTextColor(getColor(R.color.ai_text));
        repo = ChatRepository.get(this);
        info = findViewById(R.id.storageInfo);
        status = findViewById(R.id.storageStatus);
        confirm = findViewById(R.id.storageConfirm);
        ((SwipeBackLayout) findViewById(R.id.settingsSwipe)).setSwipeTargetProvider(() -> confirm.getVisibility() == View.VISIBLE
                ? confirm : ((SwipeBackLayout) findViewById(R.id.settingsSwipe)).getChildAt(0));
        findViewById(R.id.clearCache).setOnClickListener(v -> clearCache());
        findViewById(R.id.clearChats).setOnClickListener(v -> confirm.setVisibility(View.VISIBLE));
        findViewById(R.id.cancelClearChats).setOnClickListener(v -> confirm.setVisibility(View.GONE));
        findViewById(R.id.confirmClearChats).setOnClickListener(v -> clearChats());
        ((SwipeBackLayout) findViewById(R.id.settingsSwipe)).setOnSwipeFinish(() -> {
            if (confirm.getVisibility() == View.VISIBLE) {
                confirm.setVisibility(View.GONE);
                ((SwipeBackLayout) findViewById(R.id.settingsSwipe)).resetPosition();
            } else finish();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (confirm.getVisibility() == View.VISIBLE) confirm.setVisibility(View.GONE);
                else finish();
            }
        });
        refresh();
    }

    private void refresh() {
        List<Chat> chats = repo.getChats();
        int messages = 0;
        for (Chat chat : chats) messages += chat.messages.size();
        final String counts = chats.size() + " 个对话 · " + messages + " 条消息";
        worker.execute(() -> {
            long history = 0;
            for (String name : new String[]{"chats.json", "chats.backup.json"}) {
                for (String suffix : new String[]{"", ".bak", ".new"}) history += new File(getFilesDir(), name + suffix).length();
            }
            String cache;
            try { cache = formatSize(CacheFiles.size(getCacheDir())); }
            catch (Exception e) { cache = "暂不可读"; }
            final String detail = counts + "\n聊天记录及备份：" + formatSize(history) + "\n缓存：" + cache;
            runOnUiThread(() -> { if (!isDestroyed()) info.setText(detail); });
        });
    }

    private void clearCache() {
        if (busy) return;
        setBusy(true);
        status.setText("正在清理缓存…");
        worker.execute(() -> {
            boolean success;
            try { CacheFiles.clear(getCacheDir()); success = true; }
            catch (Exception e) { success = false; }
            final boolean cleared = success;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setBusy(false);
                status.setText(cleared ? "缓存已清理，聊天记录未删除" : "部分缓存未能清理，请稍后重试");
                refresh();
            });
        });
    }

    private void clearChats() {
        if (busy) return;
        confirm.setVisibility(View.GONE);
        setBusy(true);
        status.setText("正在清空聊天记录…");
        repo.clearAll(success -> {
            if (isDestroyed()) return;
            setBusy(false);
            status.setText(success ? "聊天记录和草稿已清空，供应商与外观设置已保留" : "清理未完成，请重试");
            refresh();
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        findViewById(R.id.clearCache).setEnabled(!value);
        findViewById(R.id.clearChats).setEnabled(!value);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
