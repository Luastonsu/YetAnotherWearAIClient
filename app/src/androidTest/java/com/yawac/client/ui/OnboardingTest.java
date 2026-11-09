package com.yawac.client.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.yawac.client.R;
import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.DirectChat;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Exercise real screens while isolating practice chats and restoring consent/configuration. */
public class OnboardingTest extends InstrumentationTestCase {
    private SharedPreferences prefs;
    private Map<String, ?> original;
    private final ArrayList<Activity> activities = new ArrayList<>();
    private ChatRepository repo, originalRepo;
    private Field singleton;
    private File directory, configFile;
    private byte[] configBefore;
    private final String[] keys = {Onboarding.AGREEMENT, Onboarding.STEP, Onboarding.DEMO, "welcome_shown"};
    @Override protected void setUp() throws Exception {
        super.setUp();
        Context context = getInstrumentation().getTargetContext();
        prefs = Onboarding.prefs(context); original = new java.util.HashMap<>(prefs.getAll());
        prefs.edit().remove(Onboarding.AGREEMENT).remove(Onboarding.STEP).remove(Onboarding.DEMO).commit();
        configFile = new File(context.getNoBackupFilesDir(), "api-config.enc");
        configBefore = configFile.exists() ? Files.readAllBytes(configFile.toPath()) : null;
        directory = new File(context.getCacheDir(), "onboarding-test-" + java.util.UUID.randomUUID());
        assertTrue(directory.mkdir());
        Constructor<ChatRepository> constructor = ChatRepository.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        repo = constructor.newInstance(new ContextWrapper(context) { @Override public File getFilesDir() { return directory; } });
        singleton = ChatRepository.class.getDeclaredField("instance"); singleton.setAccessible(true);
        originalRepo = (ChatRepository) singleton.get(null); singleton.set(null, repo);
    }
    @Override protected void tearDown() throws Exception {
        ui(() -> { for (Activity activity : activities) if (!activity.isDestroyed()) activity.finish(); });
        getInstrumentation().waitForIdleSync();
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : keys) {
            Object value = original.get(key);
            if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else edit.remove(key);
        }
        edit.commit(); singleton.set(null, originalRepo);
        Field field = ChatRepository.class.getDeclaredField("io"); field.setAccessible(true);
        ExecutorService io = (ExecutorService) field.get(repo); io.shutdown(); io.awaitTermination(5, TimeUnit.SECONDS);
        for (File file : directory.listFiles()) assertTrue(file.isFile() && file.delete());
        assertTrue(directory.delete());
        assertTrue("User API configuration changed during onboarding", configBefore == null ? !configFile.exists()
                : configFile.exists() && Arrays.equals(configBefore, Files.readAllBytes(configFile.toPath())));
        super.tearDown();
    }
    private void ui(Runnable runnable) { getInstrumentation().runOnMainSync(runnable); }
    private void drawn(View root) {
        java.util.concurrent.CountDownLatch frames = new java.util.concurrent.CountDownLatch(1);
        ui(() -> root.postOnAnimation(() -> root.postOnAnimation(frames::countDown)));
        try { assertTrue("No rendered frame within 3 seconds", frames.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private <T extends Activity> T launch(Class<T> type) {
        Activity activity = getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activities.add(activity); drawn(activity.getWindow().getDecorView()); return type.cast(activity);
    }
    private Activity follow(Class<?> type, Runnable trigger) {
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(type.getName(), null, false);
        ui(trigger);
        Activity result = getInstrumentation().waitForMonitorWithTimeout(monitor, 4000);
        getInstrumentation().removeMonitor(monitor); assertNotNull(result); activities.add(result);
        drawn(result.getWindow().getDecorView()); return result;
    }
    private void swipe(Activity activity, boolean right, boolean edge) {
        View root = activity.getWindow().getDecorView(); long start = SystemClock.uptimeMillis();
        for (int i = 0; i <= 8; i++) {
            float from = edge ? 5 : root.getWidth() * .18f, to = root.getWidth() * .85f;
            float x = right ? from + (to - from) * i / 8 : to - (to - from) * i / 8;
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), i == 0 ? MotionEvent.ACTION_DOWN
                    : i == 8 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, x, root.getHeight() * .5f, 0);
            ui(() -> root.dispatchTouchEvent(event)); event.recycle(); SystemClock.sleep(30);
        }
        SystemClock.sleep(300);
        if (!activity.isFinishing()) drawn(root);
    }
    private void seed(int step) { prefs.edit().putInt(Onboarding.AGREEMENT, Onboarding.VERSION).putInt(Onboarding.STEP, step).commit(); }
    private void capture(View root, String name) {
        SystemClock.sleep(600);
        drawn(root);
        // Capture the composed display: software View.draw omits OEM clip-to-outline.
        // No supplier settings screen or user history is captured by these isolated tests.
        android.graphics.Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(getInstrumentation().getTargetContext().getCacheDir(), name))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } catch (Exception e) { throw new AssertionError(e); } finally { bitmap.recycle(); }
    }
    public void testDeclineDoesNotSaveConsentOrTouchConfiguration() {
        WelcomeActivity welcome = launch(WelcomeActivity.class);
        assertFalse(Onboarding.accepted(welcome));
        capture(welcome.agreementDialog().getWindow().getDecorView(), "onboarding-agreement.png");
        ui(() -> welcome.agreementDialog().findViewById(R.id.declineTerms).performClick());
        getInstrumentation().waitForIdleSync();
        assertTrue(welcome.isFinishing()); assertFalse(Onboarding.accepted(welcome));
    }
    public void testLegacyWelcomeFlagCannotBypassAgreement() {
        prefs.edit().putBoolean("welcome_shown", true).commit();
        Activity welcome = follow(WelcomeActivity.class, () -> getInstrumentation().getTargetContext().startActivity(
                new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        assertFalse(Onboarding.accepted(welcome));
        assertNotNull(((WelcomeActivity) welcome).agreementDialog());
    }
    public void testHandsOnActionsCompleteGuideAndRemoveOnlyEmptyPractice() {
        WelcomeActivity welcome = launch(WelcomeActivity.class);
        Activity main = follow(MainActivity.class, () -> welcome.agreementDialog().findViewById(R.id.agreeTerms).performClick());
        assertTrue(Onboarding.accepted(main));
        capture(main.getWindow().getDecorView(), "onboarding-swipe.png");
        ui(() -> { HomePagerLayout pager = main.findViewById(R.id.homePager); pager.showSettings(true, false);
            assertEquals(Onboarding.SETTINGS, Onboarding.step(main)); pager.showSettings(false, false); });
        swipe(main, true, false); assertEquals(Onboarding.PROVIDER, Onboarding.step(main));
        capture(main.getWindow().getDecorView(), "onboarding-provider.png");
        Activity provider = follow(ApiSettingsActivity.class, () -> main.findViewById(R.id.providerSettings).performClick());
        assertEquals(Onboarding.PROVIDER_BACK, Onboarding.step(provider));
        swipe(provider, true, true); assertEquals(Onboarding.HOME, Onboarding.step(main));
        swipe(main, false, false); assertEquals(Onboarding.NEW_CHAT, Onboarding.step(main));
        Activity chat = follow(ChatActivity.class, () -> main.findViewById(R.id.newChatButton).performClick());
        assertEquals(Onboarding.OPTIONS, Onboarding.step(chat));
        ui(() -> chat.findViewById(R.id.chatOptionsButton).performClick());
        assertEquals(Onboarding.CLOSE_OPTIONS, Onboarding.step(chat));
        capture(chat.getWindow().getDecorView(), "onboarding-options.png");
        ui(() -> chat.findViewById(R.id.closeOptions).performClick());
        assertEquals(Onboarding.CHAT_BACK, Onboarding.step(chat));
        SystemClock.sleep(200); swipe(chat, true, true);
        assertEquals(Onboarding.DONE, Onboarding.step(main));
        assertEquals(1, repo.getChats().size());
        assertEquals(DirectChat.ID, repo.getChats().get(0).id);
    }
    public void testSkipAndRelaunchDoNotRestartCompletedGuide() {
        seed(Onboarding.SETTINGS); Activity main = launch(MainActivity.class);
        ui(() -> main.findViewById(R.id.skipGuide).performClick());
        assertEquals(Onboarding.DONE, Onboarding.step(main)); ui(main::finish);
        Activity reopened = launch(MainActivity.class);
        ui(() -> assertEquals(View.GONE, reopened.findViewById(R.id.guideOverlay).getVisibility()));
    }
    public void testInterruptedGuideResumesOnCorrectSettingsPage() {
        seed(Onboarding.HOME); Activity main = launch(MainActivity.class);
        ui(() -> assertTrue(((HomePagerLayout) main.findViewById(R.id.homePager)).isSettingsPage()));
        swipe(main, false, false); assertEquals(Onboarding.NEW_CHAT, Onboarding.step(main));
        ui(main::finish); Activity reopened = launch(MainActivity.class);
        ui(() -> assertFalse(((HomePagerLayout) reopened.findViewById(R.id.homePager)).isSettingsPage()));
        assertEquals(Onboarding.NEW_CHAT, Onboarding.step(reopened));
    }
    public void testOutlinedNativeHistoryCardOpensAndLongPresses() {
        seed(Onboarding.DONE); Chat record = repo.createChat(); repo.updateTitle(record.id, "历史卡片示例");
        repo.addMessage(record.id, new Message("assistant", "独立轮廓，点击继续对话", 0));
        Activity main = launch(MainActivity.class);
        capture(main.getWindow().getDecorView(), "onboarding-history-card.png");
        View[] surface = new View[1];
        final int[] position = {-1};
        ui(() -> {
            RecyclerView list = main.findViewById(R.id.chatList);
            position[0] = ((ChatListAdapter) list.getAdapter()).positionOf(record.id);
            assertTrue(position[0] >= 1);
            list.scrollToPosition(position[0]);
        });
        getInstrumentation().waitForIdleSync();
        ui(() -> {
            RecyclerView list = main.findViewById(R.id.chatList);
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position[0]);
            assertNotNull(holder);
            surface[0] = holder.itemView.findViewById(R.id.historyCardButton);
            assertNotNull(surface[0].getForeground());
            if (NativeUi.create(main, "HeyShapeButton", "hey_state_shape_btn_view_stub") != null)
                assertEquals("com.heytap.wearable.support.widget.HeyShapeButton", surface[0].getClass().getName());
        });
        Activity chat = follow(ChatActivity.class, () -> surface[0].performClick());
        ui(chat::finish); getInstrumentation().waitForIdleSync();
        ui(() -> {
            RecyclerView list = main.findViewById(R.id.chatList);
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position[0]);
            assertNotNull(holder);
            holder.itemView.findViewById(R.id.historyCardButton).performLongClick();
            assertEquals(View.VISIBLE, main.findViewById(R.id.deleteOverlay).getVisibility());
            main.findViewById(R.id.btnDeleteCancel).performClick();
        });
        assertNotNull(repo.getChat(record.id));
    }
    public void testInterruptedChatRestoresOptionsAndKeepsPracticeDraft() {
        seed(Onboarding.CLOSE_OPTIONS);
        Chat practice = repo.createChat();
        prefs.edit().putString(Onboarding.DEMO, practice.id).commit();
        Activity chat = getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, practice.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activities.add(chat); getInstrumentation().waitForIdleSync();
        ui(() -> {
            assertEquals(View.VISIBLE, chat.findViewById(R.id.optionsPanel).getVisibility());
            chat.findViewById(R.id.closeOptions).performClick();
            ((android.widget.EditText) chat.findViewById(R.id.input)).setText("练习中保留的草稿");
        });
        SystemClock.sleep(250); swipe(chat, true, true);
        assertEquals(Onboarding.DONE, Onboarding.step(chat));
        assertNotNull(repo.getChat(practice.id));
        assertEquals("练习中保留的草稿", repo.getChat(practice.id).draft);
    }
}
