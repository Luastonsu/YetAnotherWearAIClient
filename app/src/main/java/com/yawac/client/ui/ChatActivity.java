package com.yawac.client.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.yawac.client.R;
import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.DirectChat;
import com.yawac.client.data.Models.Chat;

/** Hosts the shared chat surface for a normal chat opened from the records page. */
public class ChatActivity extends SwipeBackActivity {
    public static final String EXTRA_CHAT_ID = "chat_id";
    private ChatPageView page;
    private GuideOverlay guide;
    private SwipeBackLayout swipe;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Onboarding.accepted(this)) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish(); return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemBars();
        setContentView(R.layout.activity_chat);
        swipe = findViewById(R.id.swipeBack);
        ChatRepository repo = ChatRepository.get(this);
        Chat chat = repo.getChat(getIntent().getStringExtra(EXTRA_CHAT_ID));
        if (chat == null) { finish(); return; }
        page = findViewById(R.id.chatPage);
        guide = GuideOverlay.attach(this, () -> {
            if (Onboarding.isDemo(this, chat.id)) repo.updateTitle(chat.id, Chat.DEFAULT_TITLE);
            Onboarding.complete(this);
            guide.hide();
        });
        page.bind(repo, chat, new ChatPageView.Host() {
            @Override public void openReading(String chatId, String messageId) {
                startActivity(new Intent(ChatActivity.this, ReadingActivity.class)
                        .putExtra(EXTRA_CHAT_ID, chatId)
                        .putExtra(ReadingActivity.EXTRA_MESSAGE_ID, messageId));
            }
            @Override public void openConfiguration() {
                startActivity(new Intent(ChatActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_SHOW_SETTINGS, true));
            }
            @Override public void onOptionsChanged(boolean shown) { }
            @Override public boolean requestTitle() { return !DirectChat.ID.equals(chat.id); }
            @Override public void finishChat() { finish(); }
        }, guide, false);
        if (savedInstanceState != null) page.restoreState(savedInstanceState);
        swipe.setOnSwipeFinish(() -> {
            if (page.handleSwipeBack()) {
                swipe.resetPosition();
                return;
            }
            page.finishPractice();
            overridePendingTransition(0, 0);
        });
        swipe.setSwipeTargetProvider(() -> page.isOptionsShown() ? page.optionsPanel() : page);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (page.handleBack()) return;
                page.finishPractice();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (page != null) page.onResume();
    }

    @Override protected void onPause() {
        if (page != null) page.onPause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (page != null) page.onSaveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (page != null) page.onDestroy();
        super.onDestroy();
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }
}
