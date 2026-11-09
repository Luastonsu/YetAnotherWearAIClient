package com.yawac.client.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yawac.client.R;
import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.DirectChat;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.net.DeepSeekClient;

import java.util.List;

/** Three-page home: settings, direct chat (default), and the conversation records. */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_PAGE = "page";
    private static final String STATE_DIRECT = "direct_chat";
    public static final String EXTRA_SHOW_SETTINGS = "show_settings";

    private ChatRepository repo;
    private ChatListAdapter adapter;
    private RecyclerView recyclerView;
    private FrameLayout deleteOverlay;
    private Chat pendingDelete;
    private OnBackPressedCallback backCallback;
    private HomePagerLayout pager;
    private PageIndicatorView indicator;
    private ChatPageView directPage;
    private GuideOverlay guide;
    private boolean foreground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Onboarding.accepted(this)) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        NativeUi.shapeButton(findViewById(R.id.btnDeleteConfirm), getColor(R.color.brand_error));
        NativeUi.shapeButton(findViewById(R.id.btnDeleteCancel), getColor(R.color.brand_surface_high));

        pager = findViewById(R.id.homePager);
        indicator = findViewById(R.id.pageIndicator);
        indicator.setCount(3);
        pager.setListener(new HomePagerLayout.Listener() {
            @Override public void onProgress(float progress) { indicator.setProgress(progress); }
            @Override public void onUserPage(int page) {
                int step = Onboarding.step(MainActivity.this);
                if (Onboarding.active(MainActivity.this)) {
                    if (step == Onboarding.SETTINGS && page == 0) {
                        Onboarding.step(MainActivity.this, Onboarding.PROVIDER);
                    } else if (step == Onboarding.HOME && page == 1) {
                        Onboarding.step(MainActivity.this, Onboarding.NEW_CHAT);
                    }
                }
                if (page != 1 && directPage != null) directPage.clearInputFocus();
                updateGuide();
            }
        });

        SwipeBackLayout deleteSwipe = findViewById(R.id.deleteSwipe);
        deleteSwipe.setOnSwipeFinish(() -> {
            deleteOverlay.animate().cancel();
            deleteOverlay.setVisibility(View.GONE);
            deleteOverlay.setAlpha(1f);
            pendingDelete = null;
            pager.setPagingEnabled(true);
            deleteSwipe.resetPosition();
        });

        int[] rows = {R.id.providerSettings, R.id.chatAppearanceSettings, R.id.storageSettings, R.id.debugSettings};
        for (int id : rows) NativeUi.shapeButton(findViewById(id), getColor(R.color.brand_surface));
        findViewById(R.id.providerSettings).setOnClickListener(v -> {
            if (Onboarding.active(this) && Onboarding.step(this) == Onboarding.PROVIDER) {
                Onboarding.step(this, Onboarding.PROVIDER_BACK);
            }
            startActivity(new Intent(this, ApiSettingsActivity.class));
        });
        findViewById(R.id.chatAppearanceSettings).setOnClickListener(v -> startActivity(new Intent(this, ChatAppearanceActivity.class)));
        findViewById(R.id.storageSettings).setOnClickListener(v -> startActivity(new Intent(this, StorageActivity.class)));
        findViewById(R.id.debugSettings).setOnClickListener(v -> startActivity(new Intent(this, DebugSettingsActivity.class)));
        findViewById(R.id.backToChats).setOnClickListener(v -> {
            pager.showPage(1, true);
            if (Onboarding.active(this) && Onboarding.step(this) == Onboarding.HOME) {
                Onboarding.step(this, Onboarding.NEW_CHAT);
            }
            updateGuide();
        });

        repo = ChatRepository.get(this);
        Chat direct = DirectChat.ensure(repo);
        if (direct.title == null || direct.title.isEmpty() || Chat.DEFAULT_TITLE.equals(direct.title)) {
            repo.updateTitle(direct.id, getString(R.string.direct_chat_title));
        }
        DirectChat.resetForProcess(repo, direct, getString(R.string.direct_context_reset_notice));
        directPage = findViewById(R.id.directChatPage);
        directPage.bind(repo, direct, new ChatPageView.Host() {
            @Override public void openReading(String chatId, String messageId) {
                startActivity(new Intent(MainActivity.this, ReadingActivity.class)
                        .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                        .putExtra(ReadingActivity.EXTRA_MESSAGE_ID, messageId));
            }
            @Override public void openConfiguration() {
                pager.showPage(0, true);
            }
            @Override public void onOptionsChanged(boolean shown) {
                pager.setPagingEnabled(!shown);
                indicator.setVisibility(shown ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public boolean requestTitle() { return false; }
            @Override public void finishChat() { }
        }, null, true);
        if (savedInstanceState != null) {
            Bundle directState = savedInstanceState.getBundle(STATE_DIRECT);
            if (directState != null) directPage.restoreState(directState);
        }

        recyclerView = findViewById(R.id.chatList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatListAdapter(new ChatListAdapter.Listener() {
            @Override public void onNewChat() {
                if (Onboarding.active(MainActivity.this) && Onboarding.step(MainActivity.this) == Onboarding.NEW_CHAT) {
                    Chat practice = repo.createChat();
                    repo.updateTitle(practice.id, "操作练习");
                    Onboarding.prefs(MainActivity.this).edit().putString(Onboarding.DEMO, practice.id)
                            .putInt(Onboarding.STEP, Onboarding.OPTIONS).apply();
                    openChat(practice.id);
                    return;
                }
                String error = DeepSeekClient.get().configurationError();
                if (error != null) {
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                    pager.showPage(0, true);
                    return;
                }
                openChat(repo.createChat().id);
            }
            @Override public void onChatClick(Chat chat) { openChat(chat.id); }
            @Override public void onChatLongClick(Chat chat) {
                if (DirectChat.ID.equals(chat.id)) {
                    Toast.makeText(MainActivity.this, "直聊会话不能删除", Toast.LENGTH_SHORT).show();
                    return;
                }
                confirmDelete(chat);
            }
        });
        recyclerView.setAdapter(adapter);

        deleteOverlay = findViewById(R.id.deleteOverlay);
        findViewById(R.id.btnDeleteConfirm).setOnClickListener(v -> {
            if (pendingDelete != null) repo.deleteChat(pendingDelete.id);
            hideDeleteOverlay();
            refresh();
        });
        findViewById(R.id.btnDeleteCancel).setOnClickListener(v -> hideDeleteOverlay());

        backCallback = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (deleteOverlay.getVisibility() == View.VISIBLE) {
                    hideDeleteOverlay();
                } else if (directPage != null && directPage.handleBack()) {
                    // Options overlay consumed the back gesture.
                } else if (pager.getPage() != 1) {
                    if (Onboarding.active(MainActivity.this) && Onboarding.step(MainActivity.this) == Onboarding.HOME) {
                        Onboarding.step(MainActivity.this, Onboarding.NEW_CHAT);
                    }
                    pager.showPage(1, true);
                    updateGuide();
                } else {
                    finish();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        guide = GuideOverlay.attach(this, () -> { Onboarding.complete(this); guide.hide(); });

        int initialPage = 1;
        if (savedInstanceState != null) {
            initialPage = savedInstanceState.getInt(STATE_PAGE, 1);
        } else if (getIntent().getBooleanExtra(EXTRA_SHOW_SETTINGS, false)) {
            initialPage = 0;
        } else if (Onboarding.active(this)) {
            int step = Onboarding.step(this);
            if (step >= Onboarding.PROVIDER && step <= Onboarding.HOME) initialPage = 0;
        }
        pager.showPage(initialPage, false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pager != null && intent.getBooleanExtra(EXTRA_SHOW_SETTINGS, false)) {
            pager.showPage(0, false);
            updateGuide();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (pager != null) state.putInt(STATE_PAGE, pager.getPage());
        if (directPage != null) {
            Bundle directState = new Bundle();
            directPage.onSaveState(directState);
            state.putBundle(STATE_DIRECT, directState);
        }
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        foreground = true;
        if (repo != null && directPage != null) {
            if (repo.getChat(DirectChat.ID) == null) DirectChat.ensure(repo);
            directPage.onResume();
        }
        refresh();
        if (guide != null && Onboarding.active(this)) {
            int step = Onboarding.step(this);
            if (step == Onboarding.PROVIDER_BACK) {
                Onboarding.step(this, Onboarding.HOME);
                pager.showPage(0, false);
            } else if (step >= Onboarding.OPTIONS && step <= Onboarding.CHAT_BACK) {
                String id = Onboarding.prefs(this).getString(Onboarding.DEMO, "");
                Chat practice = repo.getChat(id);
                if (practice != null && practice.messages.isEmpty() && practice.draft.isEmpty()) repo.deleteChat(id);
                Onboarding.prefs(this).edit().remove(Onboarding.DEMO)
                        .putInt(Onboarding.STEP, Onboarding.NEW_CHAT).apply();
                refresh();
            }
        }
        updateGuide();
    }

    @Override
    protected void onPause() {
        foreground = false;
        if (guide != null) guide.hide();
        if (directPage != null) directPage.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (directPage != null) directPage.onDestroy();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideSystemBars();
    }

    private void updateGuide() {
        if (guide == null || !foreground || pager == null) return;
        if (!Onboarding.active(this)) {
            guide.hide();
            return;
        }
        switch (Onboarding.step(this)) {
            case Onboarding.SETTINGS:
                guide.show("向右滑动屏幕\n找到设置页面", null, GuideOverlay.RIGHT);
                break;
            case Onboarding.PROVIDER:
                if (pager.getPage() != 0) {
                    guide.show("向右滑动屏幕\n回到设置页面", null, GuideOverlay.RIGHT);
                    break;
                }
                guide.show("点按模型供应商设置\n认识 API 配置入口", findViewById(R.id.providerSettings), GuideOverlay.TAP);
                break;
            case Onboarding.HOME:
                guide.show("向左滑动屏幕\n回到直聊", null, GuideOverlay.LEFT);
                break;
            case Onboarding.NEW_CHAT:
                if (pager.getPage() != 2) {
                    guide.show("向左滑动屏幕\n打开对话记录", null, GuideOverlay.LEFT);
                    break;
                }
                View button = findViewById(R.id.newChatButton);
                if (button == null) {
                    recyclerView.scrollToPosition(0);
                    recyclerView.post(this::updateGuide);
                } else {
                    guide.show("点按新建对话\n这一步无需发送消息", button, GuideOverlay.TAP);
                }
                break;
            default:
                guide.hide();
                break;
        }
    }

    private void refresh() {
        if (repo == null || adapter == null) return;
        List<Chat> chats = repo.getChats();
        adapter.setChats(chats);
    }

    private void openChat(String chatId) {
        startActivity(new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId));
    }

    private void confirmDelete(final Chat chat) {
        pendingDelete = chat;
        pager.setPagingEnabled(false);
        showDeleteOverlay();
    }

    private void showDeleteOverlay() {
        deleteOverlay.setAlpha(0f);
        deleteOverlay.setVisibility(View.VISIBLE);
        deleteOverlay.animate().alpha(1f).setDuration(150).start();
        if (backCallback != null) backCallback.setEnabled(true);
    }

    private void hideDeleteOverlay() {
        if (deleteOverlay.getVisibility() != View.VISIBLE) return;
        deleteOverlay.animate().alpha(0f).setDuration(120).withEndAction(() -> deleteOverlay.setVisibility(View.GONE)).start();
        pendingDelete = null;
        pager.setPagingEnabled(true);
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());
    }
}
