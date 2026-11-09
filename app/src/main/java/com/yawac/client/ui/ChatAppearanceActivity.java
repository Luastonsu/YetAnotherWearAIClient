package com.yawac.client.ui;

import android.os.Bundle;
import android.widget.TextView;

import com.yawac.client.R;

public class ChatAppearanceActivity extends SettingsScreenActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        openSettingsLayout(R.layout.activity_chat_appearance);
        nativeButtons(R.id.chatFontSmaller, R.id.chatFontLarger, R.id.userBubbleColor, R.id.aiBubbleColor, R.id.resetChatAppearance);
        findViewById(R.id.chatFontSmaller).setOnClickListener(v -> changeSize(-1));
        findViewById(R.id.chatFontLarger).setOnClickListener(v -> changeSize(1));
        findViewById(R.id.userBubbleColor).setOnClickListener(v -> changeColor(true));
        findViewById(R.id.aiBubbleColor).setOnClickListener(v -> changeColor(false));
        findViewById(R.id.resetChatAppearance).setOnClickListener(v -> {
            ChatAppearance.prefs(this).edit().remove("chat_text_sp").remove(ChatAppearance.USER_COLOR)
                    .remove(ChatAppearance.AI_COLOR).apply();
            render();
        });
        render();
    }

    private void changeSize(int delta) {
        ChatAppearance.prefs(this).edit().putInt("chat_text_sp", ChatStyle.clampSize(ChatAppearance.size(this) + delta)).apply();
        render();
    }

    private void changeColor(boolean user) {
        ChatAppearance.prefs(this).edit().putInt(user ? ChatAppearance.USER_COLOR : ChatAppearance.AI_COLOR,
                ChatStyle.nextColor(ChatAppearance.color(this, user))).apply();
        render();
    }

    private void render() {
        int size = ChatAppearance.size(this);
        ((TextView) findViewById(R.id.chatFontValue)).setText("聊天字号 · " + size + " sp");
        findViewById(R.id.chatFontSmaller).setEnabled(size > 11);
        findViewById(R.id.chatFontSmaller).setAlpha(size > 11 ? 1f : 0.4f);
        findViewById(R.id.chatFontLarger).setEnabled(size < 19);
        findViewById(R.id.chatFontLarger).setAlpha(size < 19 ? 1f : 0.4f);
        ((TextView) findViewById(R.id.userBubbleColor)).setText("我的气泡 · " + ChatStyle.colorName(ChatAppearance.color(this, true)));
        ((TextView) findViewById(R.id.aiBubbleColor)).setText("AI 气泡 · " + ChatStyle.colorName(ChatAppearance.color(this, false)));
        ChatAppearance.apply(findViewById(R.id.previewUserBubble), true);
        ChatAppearance.apply(findViewById(R.id.previewAiBubble), false);
    }
}
