package com.yawac.client.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.yawac.client.R;
import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.DirectChat;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;
import com.yawac.client.net.DeepSeekClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** Drives real Activities; the HTTP interceptor controls only remote service replies. */
public class ChatExperienceTest extends InstrumentationTestCase {
    private ChatRepository repo;
    private Chat chat;
    private Activity activity;
    private DeepSeekClient client, previousClient;
    private OkHttpClient http;
    private Field singleton;
    private Map<String, ?> originalSettings;
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<Reply> allocated = new ArrayList<>();
    private final List<JSONObject> requests = Collections.synchronizedList(new ArrayList<>());

    private static class Reply {
        final int code;
        final String json;
        final CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        Reply(int code, String json) { this.code = code; this.json = json; }
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        singleton = DeepSeekClient.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        previousClient = (DeepSeekClient) singleton.get(null);
        http = new OkHttpClient.Builder().addInterceptor(chain -> {
            try {
                Buffer buffer = new Buffer();
                chain.request().body().writeTo(buffer);
                requests.add(new JSONObject(buffer.readUtf8()));
                Reply reply = replies.poll(5, TimeUnit.SECONDS);
                if (reply == null) throw new java.io.IOException("No scripted response");
                reply.arrived.countDown();
                if (!reply.release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Gate timed out");
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(reply.code).message("test").body(ResponseBody.create(reply.json,
                                MediaType.get("application/json"))).build();
            } catch (Exception e) { throw new java.io.IOException(e); }
        }).build();
        Constructor<DeepSeekClient> constructor = DeepSeekClient.class.getDeclaredConstructor(
                OkHttpClient.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        client = constructor.newInstance(http, "https://test.invalid/chat", "https://test.invalid/search", "test-key");
        singleton.set(null, client);
        onUi(() -> {
            Context context = getInstrumentation().getTargetContext();
            originalSettings = new java.util.HashMap<>(context.getSharedPreferences("wearai_prefs", 0).getAll());
            context.getSharedPreferences("wearai_prefs", 0).edit().putBoolean("deep_thinking", false)
                    .putBoolean("web_search_enabled", false).putBoolean("welcome_shown", true)
                    .putInt(Onboarding.AGREEMENT, Onboarding.VERSION).putInt(Onboarding.STEP, Onboarding.DONE)
                    .putString("reasoning_effort", "high")
                    .putInt("message_text_sp", 13).putInt("chat_text_sp", 13).putInt("reading_text_sp", 13).commit();
            repo = ChatRepository.get(context);
            chat = repo.createChat();
            repo.updateTitle(chat.id, "体验验证");
            repo.addMessage(chat.id, new Message("user", "准备", System.currentTimeMillis()));
            repo.addMessage(chat.id, new Message("assistant", "可以开始了", System.currentTimeMillis()));
        });
    }

    @Override protected void tearDown() throws Exception {
        client.cancel(chat.id);
        for (Reply reply : allocated) reply.release.countDown();
        onUi(() -> {
            if (activity != null) activity.finish();
            repo.deleteChat(chat.id);
            android.content.SharedPreferences.Editor editor = getInstrumentation().getTargetContext()
                    .getSharedPreferences("wearai_prefs", 0).edit();
            for (String key : new String[]{"deep_thinking", "web_search_enabled", "message_text_sp",
                    "chat_text_sp", "reading_text_sp", "welcome_shown", Onboarding.AGREEMENT, Onboarding.STEP, "reasoning_effort"}) {
                Object old = originalSettings.get(key);
                if (old instanceof Boolean) editor.putBoolean(key, (Boolean) old);
                else if (old instanceof Integer) editor.putInt(key, (Integer) old);
                else if (old instanceof String) editor.putString(key, (String) old);
                else editor.remove(key);
            }
            editor.commit();
        });
        getInstrumentation().waitForIdleSync();
        Field ioField = ChatRepository.class.getDeclaredField("io");
        ioField.setAccessible(true);
        ((java.util.concurrent.ExecutorService) ioField.get(repo)).submit(() -> {}).get(10, TimeUnit.SECONDS);
        http.dispatcher().cancelAll();
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
        singleton.set(null, previousClient);
        super.tearDown();
    }

    private void launch() {
        activity = getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        SystemClock.sleep(150);
        onUi(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
    }

    private Reply enqueue(int code, String json) {
        Reply reply = new Reply(code, json);
        allocated.add(reply);
        replies.add(reply);
        return reply;
    }

    private String answer(String text) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", "stop")
                .put("message", new JSONObject().put("content", text)))).toString();
    }

    private void send(String text) {
        onUi(() -> {
            ((EditText) activity.findViewById(R.id.input)).setText(text);
            ((EditText) activity.findViewById(R.id.input)).onEditorAction(EditorInfo.IME_ACTION_DONE);
        });
    }

    public void testCancelRetryAndSecondTurnDoNotDuplicateQuestion() throws Exception {
        launch();
        Reply blocked = enqueue(200, answer("旧回答"));
        send("记住 37");
        assertTrue(blocked.arrived.await(3, TimeUnit.SECONDS));
        await(() -> !((ComposerMorphLayout) activity.findViewById(R.id.composer)).isMorphing());
        capture("experience-waiting.png");
        onUi(() -> {
            assertEquals(View.GONE, activity.findViewById(R.id.optionsPanel).getVisibility());
            assertTrue(activity.findViewById(R.id.chatOptionsButton).getContentDescription().toString().startsWith("停止本轮"));
            View stop = activity.findViewById(R.id.chatOptionsButton);
            android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(stop.getWidth(), stop.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            stop.draw(new android.graphics.Canvas(image));
            int red = 0;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixel(x, y);
                if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) < 150) red++;
            }
            image.recycle();
            assertTrue("The stop icon must visibly render inside the native button", red > 20);
            ((EditText) activity.findViewById(R.id.input)).setText("待发送的草稿");
            assertEquals(0, activity.getResources().getIdentifier("btnBack", "id", activity.getPackageName()));
            assertEquals(0, activity.getResources().getIdentifier("sendButton", "id", activity.getPackageName()));
            activity.findViewById(R.id.chatOptionsButton).performLongClick();
            assertEquals(View.VISIBLE, activity.findViewById(R.id.optionsPanel).getVisibility());
            activity.findViewById(R.id.closeOptions).performClick();
            activity.findViewById(R.id.chatOptionsButton).performClick();
        });
        await(() -> chat.lastMessage().failed && !chat.lastMessage().streaming);
        await(() -> activity.findViewById(R.id.optionsPanel).getVisibility() == View.GONE);
        onUi(() -> {
            assertEquals(View.GONE, activity.findViewById(R.id.optionsPanel).getVisibility());
            assertTrue(activity.findViewById(R.id.chatOptionsButton).getContentDescription().toString().startsWith("聊天选项"));
            assertEquals("待发送的草稿", ((EditText) activity.findViewById(R.id.input)).getText().toString());
        });
        String placeholderId = chat.lastMessage().id;
        blocked.release.countDown();
        Reply retry = enqueue(200, answer("收到 37"));
        onUi(() -> {
            // Change the next-message preference; retry must keep the original quick mode.
            activity.findViewById(R.id.chatOptionsButton).performClick();
            activity.findViewById(R.id.thinkingToggle).performClick();
            activity.findViewById(R.id.closeOptions).performClick();
            RecyclerView list = activity.findViewById(R.id.messageList);
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(chat.messages.size() - 1);
            assertNotNull(holder);
            holder.itemView.findViewById(R.id.messageAction).performClick();
        });
        assertTrue(retry.arrived.await(3, TimeUnit.SECONDS));
        assertEquals("disabled", requests.get(1).getJSONObject("thinking").getString("type"));
        retry.release.countDown();
        await(() -> "收到 37".equals(chat.lastMessage().content) && !chat.lastMessage().streaming);
        onUi(() -> assertTrue(activity.findViewById(R.id.chatOptionsButton).getContentDescription().toString().startsWith("聊天选项")));
        assertEquals(4, chat.messages.size());
        assertEquals(placeholderId, chat.lastMessage().id);
        Reply second = enqueue(200, answer("37"));
        send("刚才的数字？");
        assertTrue(second.arrived.await(3, TimeUnit.SECONDS));
        JSONArray history = requests.get(2).getJSONArray("messages");
        assertEquals("收到 37", history.getJSONObject(history.length() - 2).getString("content"));
        second.release.countDown();
        await(() -> "37".equals(chat.lastMessage().content) && !chat.lastMessage().streaming);
        assertEquals(6, chat.messages.size());
    }

    public void testServerErrorShowsCodeAdviceAndRetry() throws Exception {
        launch();
        Reply error = enqueue(503, "{\"error\":{\"code\":\"server_overloaded\",\"message\":\"Busy\"}}");
        send("问题");
        assertTrue(error.arrived.await(3, TimeUnit.SECONDS));
        error.release.countDown();
        await(() -> chat.lastMessage().failed && !chat.lastMessage().streaming);
        getInstrumentation().waitForIdleSync();
        capture("experience-error.png");
        onUi(() -> {
            assertTrue(chat.lastMessage().content.contains("HTTP 503"));
            assertTrue(activity.findViewById(R.id.chatOptionsButton).getContentDescription().toString().startsWith("聊天选项"));
            assertTrue(chat.lastMessage().content.contains("server_overloaded"));
            RecyclerView list = activity.findViewById(R.id.messageList);
            View row = list.findViewHolderForAdapterPosition(chat.messages.size() - 1).itemView;
            assertTrue(((TextView) row.findViewById(R.id.errorHint)).getText().toString().contains("暂时繁忙"));
            assertEquals("重试", ((TextView) row.findViewById(R.id.messageAction)).getText().toString());
        });
    }

    public void testComposerMorphAndDraftRestoration() throws Exception {
        launch();
        Reply blocked = enqueue(200, answer("完成"));
        captureApp(activity, "motion-idle.png");
        send("按给定条件独立分析");
        assertTrue(blocked.arrived.await(3, TimeUnit.SECONDS));
        SystemClock.sleep(80);
        captureApp(activity, "motion-morph.png");
        await(() -> !((ComposerMorphLayout) activity.findViewById(R.id.composer)).isMorphing());
        getInstrumentation().waitForIdleSync();
        captureApp(activity, "motion-waiting.png");
        onUi(() -> {
            EditText input = activity.findViewById(R.id.input);
            View stop = activity.findViewById(R.id.chatOptionsButton);
            View composer = activity.findViewById(R.id.composer);
            float density = activity.getResources().getDisplayMetrics().density;
            assertEquals(Math.round(36 * density), input.getWidth());
            assertEquals(Math.round(36 * density), input.getHeight());
            assertEquals(composer.getWidth() / 2f, stop.getX() + stop.getWidth() / 2f, 1f);
            assertFalse(input.isEnabled());
            input.setText("保留下一条草稿");
            stop.performLongClick();
            assertEquals(View.VISIBLE, activity.findViewById(R.id.optionsPanel).getVisibility());
            activity.findViewById(R.id.closeOptions).performClick();
        });
        await(() -> activity.findViewById(R.id.optionsPanel).getVisibility() == View.GONE);
        blocked.release.countDown();
        await(() -> "完成".equals(chat.lastMessage().content));
        await(() -> !((ComposerMorphLayout) activity.findViewById(R.id.composer)).isMorphing());
        onUi(() -> {
            EditText input = activity.findViewById(R.id.input);
            float density = activity.getResources().getDisplayMetrics().density;
            assertEquals(Math.round(104 * density), input.getWidth());
            assertEquals(Math.round(30 * density), input.getHeight());
            assertTrue(input.isEnabled());
            assertEquals("保留下一条草稿", input.getText().toString());
            assertEquals(1, requests.size());
        });
        captureApp(activity, "motion-complete.png");
    }

    public void testImmediateStopInterruptsTheMorphWithoutLeavingAStuckComposer() throws Exception {
        launch();
        Reply blocked = enqueue(200, answer("过期回复"));
        send("测试发送后立刻停止");
        onUi(() -> activity.findViewById(R.id.chatOptionsButton).performClick());
        await(() -> chat.lastMessage().failed && !chat.lastMessage().streaming);
        await(() -> ((ComposerMorphLayout) activity.findViewById(R.id.composer)).morphProgress() < 0.01f);
        onUi(() -> {
            assertTrue(((EditText) activity.findViewById(R.id.input)).isEnabled());
            assertEquals(1f, activity.findViewById(R.id.chatOptionsButton).getAlpha(), 0.01f);
            assertEquals(0f, activity.findViewById(R.id.chatOptionsButton).getRotation(), 0.01f);
            assertEquals(0f, activity.findViewById(R.id.input).getTranslationX(), 0.01f);
            assertFalse(client.isPending(chat.id));
        });
        blocked.release.countDown();
    }

    public void testComposerAnimatesEvenWhenAnotherWindowOwnsFocus() throws Exception {
        launch();
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        onUi(() -> { dialog[0] = new android.app.Dialog(activity); dialog[0].setContentView(new TextView(activity)); dialog[0].show(); });
        try {
            await(() -> !activity.hasWindowFocus());
            Reply blocked = enqueue(200, answer("焦点恢复"));
            send("模拟输入法窗口仍持有焦点时发送");
            onUi(() -> {
                ComposerMorphLayout composer = activity.findViewById(R.id.composer);
                assertTrue("Focus loss must not snap straight to stop", composer.morphProgress() < 1);
                assertTrue(composer.isMorphing());
            });
            onUi(dialog[0]::dismiss);
            await(() -> !((ComposerMorphLayout) activity.findViewById(R.id.composer)).isMorphing());
            blocked.release.countDown();
            await(() -> "焦点恢复".equals(chat.lastMessage().content));
        } finally { onUi(dialog[0]::dismiss); }
    }

    public void testComposerReversalKeepsPositionVelocityAndStableTextLayout() throws Exception {
        launch();
        Reply blocked = enqueue(200, answer("不应显示"));
        send("融合中取消");
        ComposerMorphLayout composer = activity.findViewById(R.id.composer);
        await(() -> composer.morphProgress() > .25f && composer.morphProgress() < .85f);
        captureApp(activity, "motion-joining.png");
        onUi(() -> {
            double position = composer.morphProgress(), velocity = composer.morphVelocity();
            float x = activity.findViewById(R.id.chatOptionsButton).getTranslationX();
            assertEquals(Math.round(104 * activity.getResources().getDisplayMetrics().density), activity.findViewById(R.id.input).getWidth());
            activity.findViewById(R.id.chatOptionsButton).performClick();
            assertEquals(position, composer.morphProgress(), .00001);
            assertEquals(velocity, composer.morphVelocity(), .00001);
            assertEquals(x, activity.findViewById(R.id.chatOptionsButton).getTranslationX(), .001f);
        });
        SystemClock.sleep(90);
        captureApp(activity, "motion-reversing.png");
        await(() -> !composer.isMorphing());
        onUi(() -> {
            assertEquals(0f, composer.morphProgress(), 0f);
            assertTrue(activity.findViewById(R.id.input).isEnabled());
        });
        blocked.release.countDown();
    }

    public void testPausedComposerFreezesAndResumesWithoutSnapping() throws Exception {
        launch();
        Reply blocked = enqueue(200, answer("已结束"));
        send("暂停期间保持形态");
        ComposerMorphLayout composer = activity.findViewById(R.id.composer);
        await(() -> composer.morphProgress() > .2f && composer.morphProgress() < .85f);
        double[] frozen = new double[2];
        onUi(() -> { composer.pauseMotion(); frozen[0] = composer.morphProgress(); frozen[1] = composer.morphVelocity(); });
        SystemClock.sleep(100);
        onUi(() -> {
            assertEquals(frozen[0], composer.morphProgress(), .00001);
            assertEquals(frozen[1], composer.morphVelocity(), .00001);
        });
        blocked.release.countDown();
        await(() -> "已结束".equals(chat.lastMessage().content));
        onUi(() -> {
            assertEquals(frozen[0], composer.morphProgress(), .00001);
            composer.resumeMotion();
            assertEquals(frozen[0], composer.morphProgress(), .00001);
        });
        await(() -> !composer.isMorphing());
        onUi(() -> assertEquals(0f, composer.morphProgress(), 0f));
    }

    private void chooseEffort(int level) {
        onUi(() -> {
            android.os.Bundle args = new android.os.Bundle();
            args.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, level);
            assertTrue(activity.findViewById(R.id.thinkingToggle).performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
        });
    }
    private void sliderTouch(ReasoningSlider slider, int action, float progress, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                12 * slider.getResources().getDisplayMetrics().density
                        + (slider.getWidth() - 24 * slider.getResources().getDisplayMetrics().density) * progress / 3,
                36 * slider.getResources().getDisplayMetrics().density, 0);
        onUi(() -> slider.dispatchTouchEvent(event)); event.recycle();
    }
    public void testEffortSliderFollowsFingerThenSnapsAndPersists() {
        launch(); onUi(() -> activity.findViewById(R.id.chatOptionsButton).performClick()); SystemClock.sleep(230);
        ReasoningSlider slider = activity.findViewById(R.id.thinkingToggle);
        long down = SystemClock.uptimeMillis(); sliderTouch(slider, MotionEvent.ACTION_DOWN, .5f, down);
        sliderTouch(slider, MotionEvent.ACTION_MOVE, 1.6f, down);
        onUi(() -> {
            assertEquals(1.6f, slider.visualPosition(), .02f);
            assertEquals(0, slider.level());
            assertFalse(activity.getSharedPreferences("wearai_prefs", 0).getBoolean("deep_thinking", true));
        });
        captureApp(activity, "effort-drag.png");
        sliderTouch(slider, MotionEvent.ACTION_MOVE, 2.7f, down);
        sliderTouch(slider, MotionEvent.ACTION_UP, 2.7f, down);
        await(() -> Math.abs(slider.visualPosition() - 3) < .01f);
        onUi(() -> {
            assertEquals(3, slider.level()); assertTrue(slider.isSelected());
            assertEquals("max", activity.getSharedPreferences("wearai_prefs", 0).getString("reasoning_effort", ""));
            int[] close = new int[2]; activity.findViewById(R.id.closeOptions).getLocationOnScreen(close);
            assertTrue("Done must be reachable without scrolling", close[1] + activity.findViewById(R.id.closeOptions).getHeight() <= activity.getWindow().getDecorView().getHeight());
        });
        captureApp(activity, "effort-max.png");
        onUi(activity::finish); getInstrumentation().waitForIdleSync(); launch();
        onUi(() -> assertEquals(3, ((ReasoningSlider) activity.findViewById(R.id.thinkingToggle)).level()));
    }
    public void testCancelledEffortDragKeepsCommittedSettingAndAccessibilityWorks() {
        launch(); chooseEffort(3);
        ReasoningSlider slider = activity.findViewById(R.id.thinkingToggle);
        long down = SystemClock.uptimeMillis(); sliderTouch(slider, MotionEvent.ACTION_DOWN, .5f, down);
        sliderTouch(slider, MotionEvent.ACTION_MOVE, 1.1f, down);
        sliderTouch(slider, MotionEvent.ACTION_CANCEL, 1.1f, down);
        await(() -> Math.abs(slider.visualPosition() - 3) < .01f);
        onUi(() -> {
            assertEquals(3, slider.level());
            assertEquals("max", activity.getSharedPreferences("wearai_prefs", 0).getString("reasoning_effort", ""));
            slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null);
            assertEquals(2, slider.level());
            slider.performClick(); assertEquals(0, slider.level());
            slider.performClick(); assertEquals(2, slider.level());
            slider.setEnabled(false);
            assertFalse(slider.performClick()); assertEquals(2, slider.level());
        });
    }
    public void testRetryKeepsOriginalEffortWhileNewTurnUsesChangedSlider() throws Exception {
        launch(); chooseEffort(1);
        Reply first = enqueue(200, answer("过期回答")); send("以低强度提问");
        assertTrue(first.arrived.await(3, TimeUnit.SECONDS));
        assertEquals("low", requests.get(0).getString("reasoning_effort"));
        chooseEffort(3);
        onUi(() -> activity.findViewById(R.id.chatOptionsButton).performClick());
        await(() -> chat.lastMessage().failed);
        Reply retried = enqueue(200, answer("重试低强度"));
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            list.findViewHolderForAdapterPosition(chat.messages.size() - 1).itemView.findViewById(R.id.messageAction).performClick();
        });
        assertTrue(retried.arrived.await(3, TimeUnit.SECONDS));
        assertEquals("low", requests.get(1).getString("reasoning_effort"));
        retried.release.countDown(); first.release.countDown();
        await(() -> "重试低强度".equals(chat.lastMessage().content));
        Reply next = enqueue(200, answer("新问题最高强度")); send("下一条问题");
        assertTrue(next.arrived.await(3, TimeUnit.SECONDS));
        assertEquals("max", requests.get(2).getString("reasoning_effort"));
        assertEquals("max", chat.lastMessage().reasoningEffort);
        next.release.countDown(); await(() -> "新问题最高强度".equals(chat.lastMessage().content));
    }
    public void testCustomProviderDoesNotOfferEffortSlider() throws Exception {
        Constructor<DeepSeekClient> constructor = DeepSeekClient.class.getDeclaredConstructor(OkHttpClient.class, DeepSeekClient.ConfigSource.class);
        constructor.setAccessible(true);
        singleton.set(null, constructor.newInstance(http, (DeepSeekClient.ConfigSource) () ->
                new com.yawac.client.net.ApiConfig("https://example.com/v1", "test-only-key", "custom")));
        launch();
        onUi(() -> {
            ReasoningSlider slider = activity.findViewById(R.id.thinkingToggle);
            assertFalse(slider.isEnabled()); assertFalse(slider.performClick());
            assertTrue(slider.getContentDescription().toString().contains("不支持"));
            assertEquals(0, slider.level());
        });
    }

    public void testDraftSurvivesLeavingAndReopeningChat() {
        launch();
        onUi(() -> {
            ((EditText) activity.findViewById(R.id.input)).setText("未发送的草稿");
            activity.finish();
        });
        getInstrumentation().waitForIdleSync();
        assertEquals("未发送的草稿", chat.draft);
        launch();
        onUi(() -> assertEquals("未发送的草稿", ((EditText) activity.findViewById(R.id.input)).getText().toString()));
        capture("experience-draft.png");
    }

    public void testStageChangesPreserveScrollInMultiTurnConversation() throws Exception {
        onUi(() -> {
            for (int i = 0; i < 12; i++) {
                repo.addMessage(chat.id, new Message("user", "历史问题 " + i, 0));
                repo.addMessage(chat.id, new Message("assistant", "历史回答 " + i + "\n\n已有回复正文。", 0));
            }
        });
        launch();
        Reply blocked = enqueue(200, answer("最终回答"));
        send("下一轮问题");
        assertTrue(blocked.arrived.await(3, TimeUnit.SECONDS));
        getInstrumentation().waitForIdleSync();
        final int[] resets = new int[1];
        RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { resets[0]++; }
        };
        onUi(() -> ((RecyclerView) activity.findViewById(R.id.messageList)).getAdapter().registerAdapterDataObserver(observer));
        for (boolean history : new boolean[]{false, true}) {
            if (history) {
                onUi(() -> ((LinearLayoutManager) ((RecyclerView) activity.findViewById(R.id.messageList))
                        .getLayoutManager()).scrollToPositionWithOffset(7, -11));
                getInstrumentation().waitForIdleSync();
            }
            int[] anchor = new int[2];
            onUi(() -> {
                RecyclerView list = activity.findViewById(R.id.messageList);
                LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
                anchor[0] = manager.findFirstVisibleItemPosition();
                anchor[1] = manager.findViewByPosition(anchor[0]).getTop();
                assertTrue("The starting viewport must already be past the conversation beginning", anchor[0] > 0);
            });
            for (String stage : new String[]{"联网检索中", "思考并生成中", "生成回答中", "思考并生成中"}) {
                onUi(() -> repo.updateProgress(chat.id, chat.lastMessage().id, stage));
                getInstrumentation().waitForIdleSync();
                onUi(() -> {
                    RecyclerView list = activity.findViewById(R.id.messageList);
                    LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
                    assertEquals("A stage update must not reset the whole message list", 0, resets[0]);
                    assertEquals("Stage changes must keep the same visible message", anchor[0], manager.findFirstVisibleItemPosition());
                    assertEquals("Stage changes must keep the same pixel offset", anchor[1], manager.findViewByPosition(anchor[0]).getTop());
                    if (!history) {
                        TextView status = list.findViewHolderForAdapterPosition(chat.messages.size() - 1)
                                .itemView.findViewById(R.id.status);
                        assertTrue(status.getText().toString().startsWith(stage));
                    }
                });
            }
        }
        onUi(() -> ((RecyclerView) activity.findViewById(R.id.messageList)).getAdapter().unregisterAdapterDataObserver(observer));
        blocked.release.countDown();
        await(() -> "最终回答".equals(chat.lastMessage().content));
    }

    public void testSendingFromHistoryReachesTheActualBottom() throws Exception {
        onUi(() -> {
            for (int i = 0; i < 12; i++) {
                chat.messages.add(new Message("user", "历史问题 " + i, 0));
                chat.messages.add(new Message("assistant", "历史回答 " + i, 0));
            }
            repo.persist();
        });
        launch();
        onUi(() -> layoutForTest().scrollToPositionWithOffset(0, 0));
        await(() -> activity.findViewById(R.id.messageList).canScrollVertically(1));
        Reply blocked = enqueue(200, answer("已完成"));
        send("从历史位置发送新问题");
        assertTrue(blocked.arrived.await(3, TimeUnit.SECONDS));
        await(() -> layoutForTest().findLastVisibleItemPosition() == chat.messages.size() - 1
                && !activity.findViewById(R.id.messageList).canScrollVertically(1));
        blocked.release.countDown();
        await(() -> "已完成".equals(chat.lastMessage().content));
    }

    private LinearLayoutManager layoutForTest() {
        return (LinearLayoutManager) ((RecyclerView) activity.findViewById(R.id.messageList)).getLayoutManager();
    }

    public void testReplyDoesNotMoveHistoryAndLatestButtonNavigates() {
        onUi(() -> {
            for (int i = 0; i < 12; i++) {
                repo.addMessage(chat.id, new Message("user", "历史问题 " + i, 0));
                repo.addMessage(chat.id, new Message("assistant", "历史回答 " + i, 0));
            }
            Message pending = new Message("assistant", "", System.currentTimeMillis());
            pending.streaming = true;
            repo.addMessage(chat.id, pending);
        });
        // Its interrupted placeholder is then replaced by a completed reply while viewing history.
        launch();
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(3, -7);
        });
        SystemClock.sleep(150);
        int[] anchor = new int[2];
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
            anchor[0] = layout.findFirstVisibleItemPosition();
            anchor[1] = layout.findViewByPosition(anchor[0]).getTop();
            repo.updateMessage(chat.id, chat.lastMessage().id, "新回复内容", null);
        });
        SystemClock.sleep(150);
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
            assertEquals(anchor[0], layout.findFirstVisibleItemPosition());
            assertEquals(anchor[1], layout.findViewByPosition(anchor[0]).getTop());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.latestButton).getVisibility());
            activity.findViewById(R.id.latestButton).performClick();
        });
        SystemClock.sleep(150);
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            assertTrue(((LinearLayoutManager) list.getLayoutManager()).findLastVisibleItemPosition() >= chat.messages.size() - 1);
        });
    }

    public void testReadingFontAndSwipeBack() throws Exception {
        onUi(() -> {
            // Model an upgrade from the old shared preference with a larger reading size.
            getInstrumentation().getTargetContext().getSharedPreferences("wearai_prefs", 0).edit()
                    .putInt("message_text_sp", 17).remove("reading_text_sp").commit();
            repo.addMessage(chat.id, new Message("assistant", "| 项目 | 值 |\n| --- | --- |\n| 数字 | 37 |\n\n独立阅读页。", 0));
        });
        launch();
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(ReadingActivity.class.getName(), null, false);
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            list.findViewHolderForAdapterPosition(chat.messages.size() - 1).itemView.findViewById(R.id.messageAction).performClick();
        });
        Activity reader = getInstrumentation().waitForMonitorWithTimeout(monitor, 3000);
        assertNotNull(reader);
        getInstrumentation().removeMonitor(monitor);
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            assertEquals("17", ((TextView) reader.findViewById(R.id.fontSize)).getText().toString());
            reader.findViewById(R.id.fontLarger).performClick();
            assertEquals("19", ((TextView) reader.findViewById(R.id.fontSize)).getText().toString());
            assertEquals(19, reader.getSharedPreferences("wearai_prefs", 0).getInt("reading_text_sp", 0));
            assertEquals(13, reader.getSharedPreferences("wearai_prefs", 0).getInt("chat_text_sp", 0));
            assertEquals(17, reader.getSharedPreferences("wearai_prefs", 0).getInt("message_text_sp", 0));
        });
        capture("experience-reading.png");
        onUi(reader::finish);
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            TextView bubble = list.findViewHolderForAdapterPosition(chat.messages.size() - 1)
                    .itemView.findViewById(R.id.content);
            assertEquals(13 * activity.getResources().getDisplayMetrics().scaledDensity, bubble.getTextSize(), 0.01f);
        });
        Activity reopened = getInstrumentation().startActivitySync(new Intent(activity, ReadingActivity.class)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id)
                .putExtra(ReadingActivity.EXTRA_MESSAGE_ID, chat.lastMessage().id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            assertEquals("19", ((TextView) reopened.findViewById(R.id.fontSize)).getText().toString());
            reopened.findViewById(R.id.fontSmaller).performClick();
            assertEquals(17, reopened.getSharedPreferences("wearai_prefs", 0).getInt("reading_text_sp", 0));
            reopened.finish();
        });
        getInstrumentation().waitForIdleSync();
        SystemClock.sleep(550);
        swipeRight(activity);
        await(activity::isFinishing);
    }

    public void testLongReadingPageScrollsBothDirections() throws Exception {
        StringBuilder longText = new StringBuilder("[资料链接](https://example.com)\n\n| 项目 | 值 |\n| --- | --- |\n| 链接 | [来源](https://example.com) |\n\n");
        for (int i = 0; i < 50; i++) longText.append("第 ").append(i).append(" 段：长回复应能上下滑动阅读。\n\n");
        onUi(() -> repo.addMessage(chat.id, new Message("assistant", longText.toString(), 0)));
        launch();
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(ReadingActivity.class.getName(), null, false);
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            list.findViewHolderForAdapterPosition(chat.messages.size() - 1).itemView.findViewById(R.id.content).performLongClick();
        });
        Activity reader = getInstrumentation().waitForMonitorWithTimeout(monitor, 3000);
        assertNotNull(reader);
        getInstrumentation().removeMonitor(monitor);
        getInstrumentation().waitForIdleSync();
        SystemClock.sleep(150);
        onUi(() -> reader.findViewById(R.id.readingContent).setOnTouchListener((v, event) -> {
            // Model watch text widgets that reserve link gestures before a vertical drag.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        }));
        swipeVertically(reader, true);
        int[] y = new int[1];
        onUi(() -> y[0] = ((ReadingScrollView) reader.findViewById(R.id.readingScroll)).getScrollY());
        assertTrue("Dragging the markdown text did not scroll the reading page", y[0] > 0);
        swipeVertically(reader, false);
        onUi(() -> assertTrue("Reverse drag did not scroll back", ((ReadingScrollView)
                reader.findViewById(R.id.readingScroll)).getScrollY() < y[0]));
        capture("experience-long-reading.png");
        onUi(() -> ((ReadingScrollView) reader.findViewById(R.id.readingScroll)).fullScroll(View.FOCUS_DOWN));
        SystemClock.sleep(300);
        onUi(() -> {
            ReadingScrollView viewport = reader.findViewById(R.id.readingScroll);
            assertTrue(viewport.getScrollY() > y[0]);
            assertTrue(reader.findViewById(R.id.readingContent).getBottom() - viewport.getScrollY()
                    <= viewport.getHeight() - viewport.getPaddingBottom() + 1);
        });
        capture("experience-reading-end.png");
        onUi(reader::finish);
    }

    public void testLongChatReplyScrollsPastComposerAndLastLineIsReadable() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 30; i++) source.append("第 ").append(i).append(" 段内容需要完整阅读。\n\n");
        source.append("最后一行：可以完整看到。");
        onUi(() -> repo.addMessage(chat.id, new Message("assistant", source.toString(), 0)));
        launch();
        // Opening now aligns the newest reply's end. Start this mid-bubble check at its top.
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(chat.messages.size() - 1, 0);
        });
        getInstrumentation().waitForIdleSync();
        swipeVertically(activity, true);
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            View row = list.findViewHolderForAdapterPosition(chat.messages.size() - 1).itemView;
            assertFalse(list.getClipToPadding());
            assertTrue("Long bubble should extend behind the floating input", row.getBottom() > list.getHeight());
        });
        capture("experience-immersive.png");
        captureApp(activity, "experience-immersive-app.png");
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            for (int i = 0; i < 50 && list.canScrollVertically(1); i++) list.scrollBy(0, list.getHeight());
        });
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.messageList);
            assertFalse(list.canScrollVertically(1));
            View row = list.findViewHolderForAdapterPosition(chat.messages.size() - 1).itemView;
            int[] inputAt = new int[2], rowAt = new int[2];
            row.getLocationOnScreen(rowAt);
            activity.findViewById(R.id.input).getLocationOnScreen(inputAt);
            assertTrue("End of the reply must be scrollable above the input", rowAt[1] + row.getHeight() <= inputAt[1]);
        });
        capture("experience-chat-end.png");
    }

    public void testCombinedOptionsAndImmersiveComposer() {
        launch();
        onUi(() -> {
            View input = activity.findViewById(R.id.input);
            View options = activity.findViewById(R.id.chatOptionsButton);
            float density = activity.getResources().getDisplayMetrics().density;
            assertEquals(Math.round(104 * density), input.getWidth());
            assertEquals(Math.round(30 * density), input.getHeight());
            assertEquals(options.getWidth(), options.getHeight());
            int[] inputAt = new int[2], optionsAt = new int[2];
            input.getLocationOnScreen(inputAt);
            options.getLocationOnScreen(optionsAt);
            assertTrue(optionsAt[0] + options.getWidth() < inputAt[0]);
            assertTrue(inputAt[1] > activity.getResources().getDisplayMetrics().heightPixels / 2);
            assertFalse(((RecyclerView) activity.findViewById(R.id.messageList)).getClipToPadding());
            assertEquals(0, activity.getResources().getIdentifier("inputBlur", "id", activity.getPackageName()));
            options.performClick();
            assertEquals(View.VISIBLE, activity.findViewById(R.id.optionsPanel).getVisibility());
            activity.findViewById(R.id.webSearchToggle).performClick();
            assertTrue(activity.getSharedPreferences("wearai_prefs", 0).getBoolean("web_search_enabled", false));
            assertFalse(activity.getSharedPreferences("wearai_prefs", 0).getBoolean("deep_thinking", true));
            activity.findViewById(R.id.thinkingToggle).performClick();
        });
        capture("experience-options.png");
        swipeRight(activity);
        await(() -> activity.findViewById(R.id.optionsPanel).getVisibility() == View.GONE);
        onUi(() -> {
            assertFalse(activity.isFinishing());
            assertEquals(0f, ((SwipeBackLayout) activity.findViewById(R.id.swipeBack)).getChildAt(0).getTranslationX());
            activity.finish();
        });
        getInstrumentation().waitForIdleSync();
        launch();
        onUi(() -> {
            assertTrue(activity.findViewById(R.id.chatOptionsButton).getContentDescription().toString().contains("联网开启"));
            activity.findViewById(R.id.chatOptionsButton).performClick();
            assertTrue(activity.findViewById(R.id.webSearchToggle).isSelected());
            assertTrue(activity.findViewById(R.id.thinkingToggle).isSelected());
            activity.findViewById(R.id.closeOptions).performClick();
        });
        capture("experience-composer.png");
    }

    public void testEmptyDirectChatSendsFromHomeWithoutOpeningRecord() throws Exception {
        onUi(() -> { if (repo.getChat(DirectChat.ID) != null) repo.deleteChat(DirectChat.ID); });
        Reply reply = enqueue(200, answer("首条回答"));
        Activity main = getInstrumentation().startActivitySync(new Intent(
                getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        try {
            onUi(() -> {
                EditText input = main.findViewById(R.id.input);
                assertNotNull(input);
                input.setText("首条问题");
                input.onEditorAction(EditorInfo.IME_ACTION_DONE);
            });
            assertTrue("First send from the empty direct chat must reach the service",
                    reply.arrived.await(5, TimeUnit.SECONDS));
            onUi(() -> {
                Chat direct = repo.getChat(DirectChat.ID);
                assertNotNull(direct);
                assertTrue(direct.messages.size() >= 2);
                assertEquals("user", direct.messages.get(0).role);
                assertEquals("首条问题", direct.messages.get(0).content);
                RecyclerView list = main.findViewById(R.id.messageList);
                assertNotNull(list);
                assertNotNull(list.getAdapter());
                assertEquals(2, list.getAdapter().getItemCount());
            });
            getInstrumentation().waitForIdleSync();
            SystemClock.sleep(200);
            onUi(() -> {
                RecyclerView list = main.findViewById(R.id.messageList);
                assertTrue("Sent rows must be laid out on the home page", list.getChildCount() >= 1);
            });
            reply.release.countDown();
            await(() -> {
                Chat direct = repo.getChat(DirectChat.ID);
                return direct != null && direct.messages.size() >= 2
                        && !direct.messages.get(direct.messages.size() - 1).streaming;
            });
        } finally {
            reply.release.countDown();
            onUi(main::finish);
            getInstrumentation().waitForIdleSync();
            onUi(() -> repo.deleteChat(DirectChat.ID));
            getInstrumentation().waitForIdleSync();
        }
    }

    public void testImeConfirmActionsSendOnceAndKeepWaitingDraft() throws Exception {
        launch();
        int[] actions = {EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO, EditorInfo.IME_NULL};
        for (int i = 0; i < actions.length; i++) {
            await(() -> activity.findViewById(R.id.input).isEnabled());
            int action = actions[i];
            String prompt = "确认消息 " + i;
            String response = "回答 " + i;
            Reply reply = enqueue(200, answer(response));
            onUi(() -> {
                EditText input = activity.findViewById(R.id.input);
                input.setText(prompt);
                if (action == EditorInfo.IME_NULL) {
                    input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                } else input.onEditorAction(action);
            });
            assertTrue(reply.arrived.await(3, TimeUnit.SECONDS));
            onUi(() -> {
                EditText input = activity.findViewById(R.id.input);
                input.setText("下一条草稿");
                input.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals("下一条草稿", input.getText().toString());
            });
            assertEquals(i + 1, requests.size());
            reply.release.countDown();
            await(() -> response.equals(chat.lastMessage().content) && !chat.lastMessage().streaming);
            assertEquals(2 + (i + 1) * 2, chat.messages.size());
            onUi(() -> assertEquals("下一条草稿", ((EditText) activity.findViewById(R.id.input)).getText().toString()));
        }
    }

    private void swipeRight(Activity target) {
        View decor = target.getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 6; i++) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), i == 0 ? MotionEvent.ACTION_DOWN
                    : i == 6 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, 5 + width * 0.75f * i / 6, height / 2f, 0);
            // Like vertical drags, exercise this Activity's view tree without injecting into system windows.
            onUi(() -> decor.dispatchTouchEvent(event));
            event.recycle();
            SystemClock.sleep(40);
        }
    }

    public void testSystemStyleClockAndReadableReceivingBubble() {
        launch();
        onUi(() -> {
            ClockView clock = activity.findViewById(R.id.clock);
            assertEquals("HH:mm", clock.getFormat24Hour().toString());
            assertEquals("h:mm", clock.getFormat12Hour().toString());
            int[] at = new int[2];
            clock.getLocationOnScreen(at);
            int[] contentAt = new int[2];
            ((View) clock.getParent()).getLocationOnScreen(contentAt);
            assertEquals("Clock stays at the top of the app's safe content",
                    contentAt[1] + ((View) clock.getParent()).getPaddingTop(), at[1]);
            clock.setText("10:08");
            android.graphics.Bitmap first = android.graphics.Bitmap.createBitmap(clock.getWidth(), clock.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Bitmap second = first.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            clock.draw(new android.graphics.Canvas(first));
            int lit = 0;
            for (int y = 0; y < first.getHeight(); y++) for (int x = 0; x < first.getWidth(); x++) {
                int pixel = first.getPixel(x, y);
                if (android.graphics.Color.red(pixel) > 150) lit++;
            }
            assertTrue("Clock glyphs must be visible inside the top viewport", lit > 80);
            assertEquals("The time overlay must not paint a bar over scrolled content", 0,
                    android.graphics.Color.alpha(first.getPixel(first.getWidth() / 4, first.getHeight() / 2)));
            android.graphics.Canvas repeated = new android.graphics.Canvas(second);
            for (int i = 0; i < 10; i++) {
                repeated.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                clock.draw(repeated);
            }
            assertTrue("Repeated arc draws should stay identical", first.sameAs(second));
            first.recycle(); second.recycle();
            int text = activity.getColor(R.color.ai_text), background = activity.getColor(R.color.ai_bubble);
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(text, background) > 7);
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(text, activity.getColor(R.color.brand_green)) > 4.5);
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(activity.getColor(R.color.ai_link), background) > 4.5);
        });
        capture("experience-system-chat.png");
    }

    public void testSystemStyleHomeActionAndNewConversationNavigation() {
        activity = getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.chatList);
            // Exercise the empty-state adapter without changing stored conversations.
            ((ChatListAdapter) list.getAdapter()).setChats(Collections.emptyList());
            ((ClockView) activity.findViewById(R.id.clock)).setText("22:59");
        });
        getInstrumentation().waitForIdleSync();
        capture("experience-system-home.png");
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(ChatActivity.class.getName(), null, false);
        onUi(() -> {
            View button = activity.findViewById(R.id.newChatButton);
            assertNotNull(button);
            int[] position = new int[2];
            button.getLocationOnScreen(position);
            assertTrue(position[1] > activity.findViewById(R.id.clock).getHeight());
            assertEquals("新建对话", button.getContentDescription().toString());
            try {
                activity.getPackageManager().getApplicationInfo("com.heytap.wearable.mooddiary2", 0);
                assertEquals("com.heytap.wearable.support.widget.HeyShapeButton", button.getClass().getName());
                assertEquals("com.heytap.wearable.support.widget.HeyShapeButton",
                        activity.findViewById(R.id.btnDeleteConfirm).getClass().getName());
            } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { }
            button.performClick();
        });
        Activity child = getInstrumentation().waitForMonitorWithTimeout(monitor, 3000);
        getInstrumentation().removeMonitor(monitor);
        assertNotNull(child);
        String createdId = child.getIntent().getStringExtra(ChatActivity.EXTRA_CHAT_ID);
        assertNotNull(createdId);
        onUi(() -> {
            assertEquals(0, repo.getChat(createdId).messages.size());
            child.finish();
            repo.deleteChat(createdId);
        });
    }

    public void testInstalledVendorWidgetsAndFallback() {
        launch();
        onUi(() -> activity.findViewById(R.id.chatOptionsButton).performClick());
        getInstrumentation().waitForIdleSync();
        final boolean[] original = new boolean[1];
        onUi(() -> {
            String source = "com.heytap.wearable.mooddiary2";
            try { activity.getPackageManager().getApplicationInfo(source, 0); }
            catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return; }
            assertEquals("com.heytap.wearable.support.widget.HeyArcTextClock",
                    ((ClockView) activity.findViewById(R.id.clock)).widget().getClass().getName());
            assertEquals("com.heytap.wearable.support.widget.HeyImageButton",
                    activity.findViewById(R.id.chatOptionsButton).getClass().getName());
            ModeToggleView search = activity.findViewById(R.id.webSearchToggle);
            assertEquals("com.heytap.wearable.support.widget.HeySwitch", search.widget().getClass().getName());
            boolean before = search.isSelected();
            original[0] = before;
            search.widget().performClick();
            assertEquals(!before, search.isSelected());
            assertEquals(search.isSelected(), search.widget().isChecked());
            assertEquals(search.isSelected(), activity.getSharedPreferences("wearai_prefs", 0)
                    .getBoolean("web_search_enabled", false));
            long now = SystemClock.uptimeMillis();
            View control = search.widget();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                    control.getWidth() / 2f, control.getHeight() / 2f, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP,
                    control.getWidth() / 2f, control.getHeight() / 2f, 0);
            control.dispatchTouchEvent(down);
            control.dispatchTouchEvent(up);
            down.recycle(); up.recycle();
            assertNull(NativeUi.create(activity, "HeySwitch", "hey_heyswitch_viewstub",
                    new String[]{"com.wearai.nonexistent"}));
            View alternative = NativeUi.create(activity, "HeySwitch", "hey_heyswitch_viewstub",
                    new String[]{"com.heytap.wearable.unitconverter2"});
            assertNotNull("The second installed vendor package must also work", alternative);
        });
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            ModeToggleView search = activity.findViewById(R.id.webSearchToggle);
            assertEquals("Physical touch should use the saved-state action", original[0], search.isSelected());
            assertEquals(search.isSelected(), search.widget().isChecked());
        });
        capture("experience-native-options.png");
    }

    public void testCompactHomeRowsKeepTitlePreviewAndNavigation() {
        activity = getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.chatList);
            list.scrollToPosition(1);
        });
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            RecyclerView list = activity.findViewById(R.id.chatList);
            RecyclerView.ViewHolder row = list.findViewHolderForAdapterPosition(1);
            assertNotNull(row);
            float density = activity.getResources().getDisplayMetrics().density;
            assertTrue("Rows should be compact", row.itemView.getHeight() <= 52 * density);
            assertTrue("Retain a usable tap target", row.itemView.getHeight() >= 48 * density);
            TextView title = row.itemView.findViewById(R.id.title);
            assertTrue("Date should not squeeze away the title", title.getWidth() > 70 * density);
            assertEquals(1, ((TextView) row.itemView.findViewById(R.id.preview)).getMaxLines());
        });
        capture("experience-native-home-list.png");
    }

    private void swipeVertically(Activity target, boolean upward) {
        int width = target.getResources().getDisplayMetrics().widthPixels;
        int height = target.getResources().getDisplayMetrics().heightPixels;
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            float progress = i / 10f;
            float y = height * (upward ? 0.78f - 0.43f * progress : 0.35f + 0.43f * progress);
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), i == 0 ? MotionEvent.ACTION_DOWN
                    : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, width / 2f, y, 0);
            // Exercise the full app touch hierarchy even when a watch USB dialog owns focus.
            onUi(() -> target.dispatchTouchEvent(event));
            event.recycle();
            SystemClock.sleep(40);
        }
        SystemClock.sleep(250);
    }

    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 4000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] done = new boolean[1];
            onUi(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            SystemClock.sleep(40);
        }
        fail("Expected UI state did not arrive");
    }

    private void captureApp(Activity target, String name) {
        onUi(() -> {
            View root = target.getWindow().getDecorView();
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(bitmap));
            try (FileOutputStream out = new FileOutputStream(new File(target.getCacheDir(), name))) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) { throw new AssertionError(e); }
            finally { bitmap.recycle(); }
        });
    }

    private void capture(String name) {
        getInstrumentation().waitForIdleSync();
        SystemClock.sleep(550);
        android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream out = new FileOutputStream(new File(
                getInstrumentation().getTargetContext().getCacheDir(), name))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) { throw new AssertionError(e); }
        screenshot.recycle();
    }

    private void onUi(Runnable action) {
        Throwable[] failure = new Throwable[1];
        getInstrumentation().runOnMainSync(() -> {
            try { action.run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
