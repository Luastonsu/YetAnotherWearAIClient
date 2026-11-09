package com.yawac.client;

import android.app.Application;

import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.ApiConfigStore;
import com.yawac.client.net.DeepSeekClient;
import com.yawac.client.ui.Markdown;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ChatRepository.preload(this);
        DeepSeekClient.initialize(new ApiConfigStore(this)::load);
        DeepSeekClient.initializeTracing((chatId, purpose) -> com.yawac.client.data.DebugTraceStore.get(this).begin(chatId, purpose));
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW
                || level == TRIM_MEMORY_RUNNING_CRITICAL) Markdown.clearMemoryCache();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        Markdown.clearMemoryCache();
    }
}
