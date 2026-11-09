package com.yawac.client.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yawac.client.R;
import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.ChatTurns;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;
import com.yawac.client.net.DeepSeekClient;
import com.yawac.client.net.ReasoningEffort;

import java.util.ArrayList;

/**
 * Reusable chat surface. ChatActivity hosts it for normal chats; the home pager hosts
 * a second instance for the fixed direct chat.
 */
public class ChatPageView extends FrameLayout {
    public interface Host {
        void openReading(String chatId, String messageId);
        void openConfiguration();
        void onOptionsChanged(boolean shown);
        boolean requestTitle();
        void finishChat();
    }

    private ChatRepository repo;
    private Chat chat;
    private Host host;
    private GuideOverlay guide;
    private boolean ownOptionsSwipe, bound;

    private MessageAdapter adapter;
    private RecyclerView recyclerView;
    private LinearLayoutManager layout;
    private EditText input;
    private ModeToggleView webSearchToggle;
    private ReasoningSlider thinkingToggle;
    private String reasoningEffort = ReasoningEffort.DEFAULT;
    private ImageButton latestButton, chatOptionsButton;
    private ComposerMorphLayout composer;
    private View optionsPanel, stopRequest;
    private boolean optionsShown;
    private Boolean latestShown;
    private boolean webSearchEnabled, deepThinking, waiting, firstRefresh = true;
    private boolean deepSeekOptionsSupported;
    private Parcelable restoredScroll;
    private boolean latestScrollRequested;
    private boolean sentScrollPending;
    private final int optionSwipeSlop;
    private final ViewTreeObserver.OnPreDrawListener latestScroll = this::finishLatestScroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveDraft = () -> {
        if (repo != null && chat != null && input != null) repo.updateDraft(chat.id, input.getText().toString());
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (adapter != null) adapter.refreshWaitingTime();
            handler.postDelayed(this, 1000);
        }
    };
    private final ChatRepository.Listener repoListener = new ChatRepository.Listener() {
        @Override public void onChatUpdated(String id) {
            if (chat != null && chat.id.equals(id)) activity().runOnUiThread(ChatPageView.this::refreshMessages);
        }
        @Override public void onProgressUpdated(String id, String messageId) {
            if (chat != null && chat.id.equals(id)) activity().runOnUiThread(() -> {
                if (adapter != null && !activity().isFinishing() && !activity().isDestroyed())
                    adapter.notifyProgressChanged(messageId);
            });
        }
    };

    public ChatPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(context.getColor(R.color.brand_bg));
        optionSwipeSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        LayoutInflater.from(context).inflate(R.layout.view_chat_page, this, true);

        recyclerView = findViewById(R.id.messageList);
        input = findViewById(R.id.input);
        webSearchToggle = findViewById(R.id.webSearchToggle);
        thinkingToggle = findViewById(R.id.thinkingToggle);
        latestButton = NativeUi.imageButton(findViewById(R.id.latestButton));
        latestButton.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.brand_light_green)));
        chatOptionsButton = NativeUi.imageButton(findViewById(R.id.chatOptionsButton));
        composer = findViewById(R.id.composer);
        composer.bind(input, chatOptionsButton);
        optionsPanel = findViewById(R.id.optionsPanel);
        stopRequest = findViewById(R.id.stopRequest);

        chatOptionsButton.setOnClickListener(v -> {
            if (chat != null && waiting) DeepSeekClient.get().cancel(chat.id);
            else showOptions(true);
        });
        chatOptionsButton.setOnLongClickListener(v -> {
            if (!waiting) return false;
            showOptions(true);
            return true;
        });
        optionsPanel.setOnClickListener(v -> showOptions(false));
        findViewById(R.id.closeOptions).setOnClickListener(v -> showOptions(false));
        stopRequest.setOnClickListener(v -> {
            showOptions(false);
            if (chat != null && waiting) DeepSeekClient.get().cancel(chat.id);
        });

        webSearchEnabled = context.getSharedPreferences("wearai_prefs", Context.MODE_PRIVATE)
                .getBoolean("web_search_enabled", false);
        deepThinking = context.getSharedPreferences("wearai_prefs", Context.MODE_PRIVATE)
                .getBoolean("deep_thinking", false);
        reasoningEffort = ReasoningEffort.normalize(context.getSharedPreferences("wearai_prefs", Context.MODE_PRIVATE)
                .getString("reasoning_effort", ReasoningEffort.DEFAULT));
        webSearchToggle.setOnClickListener(v -> {
            if (!deepSeekOptionsSupported) return;
            webSearchEnabled = !webSearchEnabled;
            context.getSharedPreferences("wearai_prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("web_search_enabled", webSearchEnabled).apply();
            updateToggles();
        });
        thinkingToggle.setListener(level -> {
            if (!DeepSeekClient.get().supportsDeepSeekOptions()) return;
            deepThinking = level > 0;
            if (deepThinking) reasoningEffort = ReasoningEffort.fromLevel(level);
            context.getSharedPreferences("wearai_prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("deep_thinking", deepThinking)
                    .putString("reasoning_effort", reasoningEffort).apply();
            updateToggles();
        });

        layout = new LinearLayoutManager(context);
        recyclerView.setLayoutManager(layout);
        recyclerView.setItemAnimator(null);
        recyclerView.setItemViewCacheSize(4);
        adapter = new MessageAdapter(new MessageAdapter.Listener() {
            @Override public void onRetry(Message message) { retry(message); }
            @Override public void onRead(Message message) {
                if (host != null && chat != null) host.openReading(chat.id, message.id);
            }
        });
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    sentScrollPending = false;
                    cancelLatestScroll();
                    updateLatestButton();
                }
            }
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                updateLatestButton();
            }
        });
        latestButton.setOnClickListener(v -> scrollToLatest());

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(saveDraft);
                handler.postDelayed(saveDraft, 400);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    public void bind(ChatRepository repo, Chat chat, Host host, GuideOverlay guide, boolean ownOptionsSwipe) {
        if (bound) return;
        bound = true;
        this.repo = repo;
        this.chat = chat;
        this.host = host;
        this.guide = guide;
        this.ownOptionsSwipe = ownOptionsSwipe;
        repo.addListener(repoListener);
        input.setText(chat.draft == null ? "" : chat.draft);
        input.setSelection(input.length());
        installOptionsSwipe();
    }

    private Activity activity() { return (Activity) getContext(); }

    public void onResume() {
        if (composer != null) composer.resumeMotion();
        if (chat == null || repo == null) return;
        Chat current = repo.getChat(chat.id);
        if (current == null) {
            if (host != null) host.finishChat();
            return;
        }
        chat = current;        // A cleared or recreated chat may have a different stored draft; never
        // overwrite text while the user is actively editing.
        String draft = chat.draft == null ? "" : chat.draft;
        if (input != null && !input.hasFocus() && !draft.contentEquals(input.getText())) {
            input.setText(draft);
            input.setSelection(input.length());
        }
        deepSeekOptionsSupported = DeepSeekClient.get().supportsDeepSeekOptions();
        if (!deepSeekOptionsSupported) {
            webSearchEnabled = false;
            deepThinking = false;
        }
        updateToggles();
        if (!DeepSeekClient.get().isPending(chat.id)) {
            for (Message message : new ArrayList<>(chat.messages)) {
                if (message.streaming) repo.updateMessage(chat.id, message.id,
                        "⚠️ 上次请求已中断，可点击重试\n错误码：REQUEST_INTERRUPTED", null, true);
            }
        }
        refreshMessages();
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (guide != null && Onboarding.active(activity()) && Onboarding.isDemo(activity(), chat.id)
                && Onboarding.step(activity()) == Onboarding.CLOSE_OPTIONS) showOptions(true);
        updateGuide();
    }

    public void onPause() {
        sentScrollPending = false;
        if (thinkingToggle != null) thinkingToggle.settle();
        if (guide != null) guide.hide();
        if (composer != null) composer.pauseMotion();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(saveDraft);
        saveDraft.run();
    }

    public void onSaveState(Bundle outState) {
        if (layout != null && !latestScrollRequested) outState.putParcelable("scroll", layout.onSaveInstanceState());
        saveDraft.run();
    }

    public void restoreState(Bundle state) {
        if (state != null) restoredScroll = state.getParcelable("scroll");
    }

    public void onDestroy() {
        cancelLatestScroll();
        handler.removeCallbacksAndMessages(null);
        if (repo != null) repo.removeListener(repoListener);
    }

    /** Returns true when the back gesture was consumed by the options overlay. */
    public boolean handleBack() {
        return closeOptions(true);
    }

    /** Swipe dismissal hides the overlay immediately, as the page is already leaving. */
    public boolean handleSwipeBack() {
        return closeOptions(false);
    }

    private boolean closeOptions(boolean animate) {
        if (optionsShown) {
            showOptions(false, animate);
            return true;
        }
        return false;
    }

    public boolean isOptionsShown() { return optionsShown; }
    public View optionsPanel() { return optionsPanel; }

    public void clearInputFocus() {
        if (input != null) input.clearFocus();
        WindowCompat.getInsetsController(activity().getWindow(), activity().getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.ime());
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || waiting || chat == null || DeepSeekClient.get().isPending(chat.id)) return;
        if (Onboarding.active(activity()) && Onboarding.isDemo(activity(), chat.id)) {
            Onboarding.complete(activity());
            if (guide != null) guide.hide();
        }
        if (!ensureConfigured()) return;
        latestScrollRequested = true;
        restoredScroll = null;
        boolean newConversation = chat.messages.isEmpty();
        Message user = new Message("user", text, System.currentTimeMillis());
        repo.addMessage(chat.id, user);
        input.setText("");
        repo.updateDraft(chat.id, "");
        Message answer = new Message("assistant", "", System.currentTimeMillis());
        answer.streaming = true;
        answer.webSearch = webSearchEnabled;
        answer.deepThinking = deepThinking;
        answer.reasoningEffort = reasoningEffort;
        answer.requestUserId = user.id;
        answer.requestStartedAt = System.currentTimeMillis();
        answer.stage = webSearchEnabled ? "分析参考需求" : (deepThinking ? "思考并生成中" : "生成回答中");
        repo.addMessage(chat.id, answer);
        WindowCompat.getInsetsController(activity().getWindow(), activity().getWindow().getDecorView())
                .hide(WindowInsetsCompat.Type.ime());
        input.clearFocus();
        sentScrollPending = true;
        refreshMessages();
        scrollToLatest();
        if (newConversation && host != null && host.requestTitle()) requestTitle(chat.id, text);
        requestAnswer(answer);
    }

    private void retry(Message answer) {
        if (waiting || DeepSeekClient.get().isPending(chat.id) || !ensureConfigured()) return;
        if (!DeepSeekClient.get().supportsDeepSeekOptions() && (answer.webSearch || answer.deepThinking)) {
            Toast.makeText(getContext(), "原请求使用 DeepSeek 专属模式，请切回 DeepSeek 后重试", Toast.LENGTH_LONG).show();
            return;
        }
        if (!ChatTurns.retry(chat, answer)) return;
        latestScrollRequested = true;
        restoredScroll = null;
        repo.persist();
        refreshMessages();
        scrollToLatest();
        requestAnswer(answer);
    }

    private void requestAnswer(Message answer) {
        final String chatId = chat.id;
        DeepSeekClient.get().chat(chatId, ChatTurns.history(chat), answer.webSearch, answer.deepThinking,
                answer.reasoningEffort, new DeepSeekClient.CompletionListener() {
                    @Override public void onStageChanged(String stage) {
                        activity().runOnUiThread(() -> repo.updateProgress(chatId, answer.id, stage));
                    }
                    @Override public void onFinished(String content, String reasoning, boolean truncated) {
                        completeMessage(chatId, answer.id, truncated
                                ? "⚠️ 回复过长被截断，请拆分问题后重试\n错误码：length" : content, reasoning, truncated);
                    }
                    @Override public void onError(String error) {
                        completeMessage(chatId, answer.id, "⚠️ " + error, null, true);
                    }
                    @Override public void onCancelled() {
                        completeMessage(chatId, answer.id, "⚠️ 请求已取消，可点击重试\n错误码：CANCELLED", null, true);
                    }
                });
    }

    private void completeMessage(String chatId, String id, String content, String reasoning, boolean failed) {
        activity().runOnUiThread(() -> repo.updateMessage(chatId, id, content, reasoning, failed));
    }

    private boolean ensureConfigured() {
        String error = DeepSeekClient.get().configurationError();
        if (error == null) return true;
        Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
        if (host != null) host.openConfiguration();
        return false;
    }

    private void updateToggles() {
        boolean supported = deepSeekOptionsSupported;
        webSearchToggle.setEnabled(supported);
        thinkingToggle.setEnabled(supported);
        webSearchToggle.setAlpha(supported ? 1f : 0.45f);
        thinkingToggle.setAlpha(supported ? 1f : 0.45f);
        webSearchToggle.setSelected(webSearchEnabled);
        webSearchToggle.setText(supported ? "联网辅助" : "联网（仅 DeepSeek）");
        webSearchToggle.setContentDescription(getContext().getString(
                webSearchEnabled ? R.string.web_search_on : R.string.web_search_off));
        thinkingToggle.setLevel(ReasoningEffort.level(deepThinking, reasoningEffort),
                ReasoningEffort.level(true, reasoningEffort),
                !firstRefresh && optionsShown && hasWindowFocus());
        updateComposerButton();
    }

    private void updateComposerButton() {
        if (composer == null) return;
        if (waiting && input.hasFocus()) {
            WindowCompat.getInsetsController(activity().getWindow(), activity().getWindow().getDecorView())
                    .hide(WindowInsetsCompat.Type.ime());
            input.clearFocus();
        }
        composer.setWaiting(waiting, getContext().getColor(
                        webSearchEnabled || deepThinking ? R.color.brand_light_green : R.color.text_secondary),
                waiting ? getContext().getString(R.string.stop_request_options)
                        : "聊天选项，联网" + (webSearchEnabled ? "开启" : "关闭")
                        + "，" + (deepThinking ? "深度思考" : "快速回答"), !firstRefresh);
    }

    private void showOptions(boolean show) {
        showOptions(show, true);
    }

    private void showOptions(boolean show, boolean animate) {
        stopRequest.setVisibility(waiting ? View.VISIBLE : View.GONE);
        optionsShown = show;
        if (animate) ChatMotion.panel(optionsPanel, findViewById(R.id.optionsCard), show);
        else {
            optionsPanel.animate().cancel();
            findViewById(R.id.optionsCard).animate().cancel();
            optionsPanel.setTag(show);
            optionsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            optionsPanel.setAlpha(1f);
        }
        recyclerView.setImportantForAccessibility(show ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (show) webSearchToggle.requestFocus();
        if (host != null) host.onOptionsChanged(show);
        if (guide != null && chat != null && Onboarding.active(activity()) && Onboarding.isDemo(activity(), chat.id)) {
            int step = Onboarding.step(activity());
            if (show && step == Onboarding.OPTIONS) Onboarding.step(activity(), Onboarding.CLOSE_OPTIONS);
            else if (!show && step == Onboarding.CLOSE_OPTIONS) Onboarding.step(activity(), Onboarding.CHAT_BACK);
            updateGuide();
        }
    }

    private void installOptionsSwipe() {
        if (!ownOptionsSwipe) {
            optionsPanel.setOnTouchListener(null);
            return;
        }
        optionsPanel.setOnTouchListener(new OnTouchListener() {
            private float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        break;
                    case MotionEvent.ACTION_UP:
                        float dx = event.getX() - downX, dy = event.getY() - downY;
                        if (Math.abs(dx) > optionSwipeSlop && Math.abs(dx) > Math.abs(dy)) {
                            showOptions(false);
                            return true;
                        }
                        break;
                }
                return false;
            }
        });
    }

    private void updateGuide() {
        if (guide == null || chat == null || !Onboarding.active(activity())
                || !Onboarding.isDemo(activity(), chat.id)) return;
        int step = Onboarding.step(activity());
        if (step == Onboarding.OPTIONS) {
            guide.show("点按左下角的聊天选项\n旁边输入框用来写消息", chatOptionsButton, GuideOverlay.TAP);
        } else if (step == Onboarding.CLOSE_OPTIONS) {
            guide.show("联网辅助与思考在这里\n点按完成关闭选项", findViewById(R.id.closeOptions), GuideOverlay.TAP);
        } else if (step == Onboarding.CHAT_BACK) {
            guide.show("从左侧向右滑回主页\n输入法确认键用于发送", null, GuideOverlay.RIGHT);
        }
    }

    public void finishPractice() {
        if (chat != null && Onboarding.active(activity()) && Onboarding.isDemo(activity(), chat.id)
                && Onboarding.step(activity()) == Onboarding.CHAT_BACK) {
            if (chat.messages.isEmpty() && input.getText().toString().isEmpty()) repo.deleteChat(chat.id);
            Onboarding.complete(activity());
        }
        if (host != null) host.finishChat();
    }

    private void refreshMessages() {
        if (activity().isDestroyed() || activity().isFinishing() || adapter == null) return;
        boolean follow = latestScrollRequested || firstRefresh || !recyclerView.canScrollVertically(1);
        Parcelable previous = latestScrollRequested ? null : firstRefresh ? restoredScroll : layout.onSaveInstanceState();
        chat = repo.getChat(chat.id);
        if (chat == null) return;
        waiting = DeepSeekClient.get().isPending(chat.id);
        for (Message m : chat.messages) waiting |= m.streaming;
        adapter.setMessages(chat.messages);
        stopRequest.setVisibility(waiting ? View.VISIBLE : View.GONE);
        updateComposerButton();
        if (previous != null && (!follow || firstRefresh && restoredScroll != null)) layout.onRestoreInstanceState(previous);
        else if (follow) scrollToLatest();
        firstRefresh = false;
        recyclerView.post(this::updateLatestButton);
    }

    private void scrollToLatest() {
        if (activity().isDestroyed() || activity().isFinishing() || adapter.getItemCount() == 0) return;
        latestScrollRequested = true;
        restoredScroll = null;
        recyclerView.stopScroll();
        layout.scrollToPositionWithOffset(adapter.getItemCount() - 1, 0);
        ViewTreeObserver observer = recyclerView.getViewTreeObserver();
        observer.removeOnPreDrawListener(latestScroll);
        observer.addOnPreDrawListener(latestScroll);
    }

    private boolean finishLatestScroll() {
        if (!latestScrollRequested || activity().isDestroyed() || activity().isFinishing()
                || adapter.getItemCount() == 0) {
            cancelLatestScroll();
            return true;
        }
        if (recyclerView.getHeight() == 0) return true;
        int lastPosition = adapter.getItemCount() - 1;
        View last = layout.findViewByPosition(lastPosition);
        if (recyclerView.hasPendingAdapterUpdates() || last == null) {
            layout.scrollToPositionWithOffset(lastPosition, 0);
            return false;
        }
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) last.getLayoutParams();
        int bottom = layout.getDecoratedBottom(last) + params.bottomMargin;
        recyclerView.scrollBy(0, bottom - (recyclerView.getHeight() - recyclerView.getPaddingBottom()));
        cancelLatestScroll();
        updateLatestButton();
        return true;
    }

    private void cancelLatestScroll() {
        latestScrollRequested = false;
        if (recyclerView != null && recyclerView.getViewTreeObserver().isAlive())
            recyclerView.getViewTreeObserver().removeOnPreDrawListener(latestScroll);
    }

    private void updateLatestButton() {
        boolean olderMessages = !latestScrollRequested && adapter.getItemCount() > 0
                && recyclerView.canScrollVertically(1);
        if (latestShown == null || latestShown != olderMessages) {
            latestShown = olderMessages;
            ChatMotion.latest(latestButton, olderMessages);
        }
    }

    private void requestTitle(String chatId, String text) {
        DeepSeekClient.get().generateTitle(chatId, text, new DeepSeekClient.TitleListener() {
            @Override public void onTitle(String title) {
                activity().runOnUiThread(() -> repo.updateTitle(chatId, title));
            }
            @Override public void onError(String error) {
                activity().runOnUiThread(() -> {
                    Chat current = repo.getChat(chatId);
                    if (current != null && Chat.DEFAULT_TITLE.equals(current.title)) {
                        String shortTitle = text.replace('\n', ' ').trim();
                        repo.updateTitle(chatId, shortTitle.length() > 10 ? shortTitle.substring(0, 10) + "…" : shortTitle);
                    }
                });
            }
        });
    }

    @Override protected void onDetachedFromWindow() {
        cancelLatestScroll();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The IME opening/closing resizes the page; realign the just-sent turn once
        // the final height is known, otherwise the first empty-chat message can stay
        // off-screen on the watch.
        if (sentScrollPending) {
            sentScrollPending = false;
            post(this::scrollToLatest);
        }
    }
}
