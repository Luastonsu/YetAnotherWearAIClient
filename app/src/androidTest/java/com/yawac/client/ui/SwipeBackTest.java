package com.yawac.client.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.yawac.client.R;
import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Uses real windows and pixel assertions to verify the live Activity underneath a held drag. */
public class SwipeBackTest extends InstrumentationTestCase {
    private MainActivity home;
    private final List<Activity> activities = new ArrayList<>();
    private View marker;
    private ChatRepository repo;
    private Chat chat;
    private Message answer;
    private SharedPreferences prefs;
    private Map<String, ?> original;
    private long down;

    @Override protected void setUp() throws Exception {
        super.setUp();
        prefs = getInstrumentation().getTargetContext().getSharedPreferences("wearai_prefs", 0);
        original = new java.util.HashMap<>(prefs.getAll());
        prefs.edit().putInt(Onboarding.AGREEMENT, Onboarding.VERSION).putInt(Onboarding.STEP, Onboarding.DONE).commit();
        onUi(() -> {
            repo = ChatRepository.get(getInstrumentation().getTargetContext());
            chat = repo.createChat();
            answer = new Message("assistant", "手势回归测试\n\n这是需要完整阅读的回答。", System.currentTimeMillis());
            repo.addMessage(chat.id, new Message("user", "手势测试", System.currentTimeMillis()));
            repo.addMessage(chat.id, answer);
        });
        home = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(home);
        getInstrumentation().waitForIdleSync();
        onUi(() -> {
            home.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            marker = new View(home); marker.setBackgroundColor(Color.rgb(28, 108, 68));
            ((FrameLayout) home.findViewById(android.R.id.content)).addView(marker, new FrameLayout.LayoutParams(-1, -1));
        });
        getInstrumentation().waitForIdleSync();
    }

    @Override protected void tearDown() throws Exception {
        onUi(() -> {
            for (int i = activities.size() - 1; i >= 0; i--) if (!activities.get(i).isFinishing()) activities.get(i).finish();
            repo.deleteChat(chat.id);
        });
        getInstrumentation().waitForIdleSync();
        java.lang.reflect.Field io = ChatRepository.class.getDeclaredField("io"); io.setAccessible(true);
        ((java.util.concurrent.ExecutorService) io.get(repo)).submit(() -> {}).get(10, TimeUnit.SECONDS);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : new String[]{Onboarding.AGREEMENT, Onboarding.STEP, "reading_text_sp"}) {
            if (original.get(key) instanceof Integer) editor.putInt(key, (Integer) original.get(key)); else editor.remove(key);
        }
        editor.commit(); super.tearDown();
    }

    private Activity open(Activity parent, Class<?> type, Intent extra) {
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(type.getName(), null, false);
        onUi(() -> parent.startActivity(extra == null ? new Intent(parent, type) : extra));
        Activity child = getInstrumentation().waitForMonitorWithTimeout(monitor, 4000);
        getInstrumentation().removeMonitor(monitor); assertNotNull(child); activities.add(child);
        getInstrumentation().waitForIdleSync(); SystemClock.sleep(250); return child;
    }
    private SwipeBackLayout swipe(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content); return (SwipeBackLayout) root.getChildAt(0);
    }
    private void send(SwipeBackLayout layout, int action, float x, float y, int ms) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down + ms, action, x, y, 0);
        onUi(() -> layout.dispatchTouchEvent(event)); event.recycle();
    }
    private void hold(SwipeBackLayout layout, float fraction) {
        send(layout, MotionEvent.ACTION_DOWN, 4, layout.getHeight() / 2f, 0);
        send(layout, MotionEvent.ACTION_MOVE, 4 + layout.getWidth() * fraction, layout.getHeight() / 2f, 120);
        send(layout, MotionEvent.ACTION_MOVE, 4 + layout.getWidth() * fraction, layout.getHeight() / 2f, 500);
        drawn(layout); SystemClock.sleep(80);
    }
    private void release(SwipeBackLayout layout, float fraction, int action) {
        send(layout, action, 4 + layout.getWidth() * fraction, layout.getHeight() / 2f, 600);
        SystemClock.sleep(280); onUi(() -> {});
    }
    private void onUi(Runnable action) { getInstrumentation().runOnMainSync(action); }
    private void drawn(View root) {
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        onUi(() -> root.postOnAnimation(() -> root.postOnAnimation(frame::countDown)));
        try { assertTrue("No rendered frame within 3 seconds", frame.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    public void testHeldDragRevealsLiveParentWithoutFadingEitherPage() throws Exception {
        Activity child = open(home, StorageActivity.class, null); SwipeBackLayout layout = swipe(child);
        hold(layout, .5f);
        onUi(() -> {
            assertEquals(layout.getWidth() * .5f, layout.getChildAt(0).getTranslationX(), 1);
            assertEquals(1f, layout.getChildAt(0).getAlpha());
            assertFalse(child.isFinishing()); assertEquals(0f, marker.getTranslationX());
        });
        Bitmap first = getInstrumentation().getUiAutomation().takeScreenshot();
        int[] origin = new int[2]; onUi(() -> layout.getLocationOnScreen(origin));
        int x = origin[0] + layout.getWidth() / 4, y = origin[1] + layout.getHeight() / 2;
        assertEquals("Held drag must reveal the previous Activity", Color.rgb(28, 108, 68), first.getPixel(x, y));
        first.recycle();
        onUi(() -> marker.setBackgroundColor(Color.rgb(80, 58, 132)));
        getInstrumentation().waitForIdleSync(); SystemClock.sleep(80);
        Bitmap changed = getInstrumentation().getUiAutomation().takeScreenshot();
        assertEquals("The parent must stay live, not be a cached screenshot", Color.rgb(80, 58, 132), changed.getPixel(x, y));
        try (FileOutputStream output = new FileOutputStream(new File(child.getFilesDir(), "swipe-live-parent.png"))) {
            changed.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { changed.recycle(); }
        onUi(() -> marker.setVisibility(View.GONE));
        getInstrumentation().waitForIdleSync(); SystemClock.sleep(80);
        Bitmap actualPages = getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream output = new FileOutputStream(new File(child.getFilesDir(), "swipe-real-pages.png"))) {
            actualPages.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { actualPages.recycle(); }
        release(layout, .5f, MotionEvent.ACTION_CANCEL);
        onUi(() -> { assertEquals(0f, layout.getChildAt(0).getTranslationX()); assertFalse(child.isFinishing()); });
    }

    public void testAllSecondaryPagesHaveOpaqueSurfaceAndSameTrackingAndCancel() {
        for (Class<?> type : new Class<?>[]{ApiSettingsActivity.class, ChatAppearanceActivity.class,
                StorageActivity.class, DebugSettingsActivity.class, ChatActivity.class, ReadingActivity.class}) {
            Intent intent = new Intent(home, type).putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id).putExtra(ReadingActivity.EXTRA_MESSAGE_ID, answer.id);
            Activity child = open(home, type, intent); SwipeBackLayout layout = swipe(child);
            onUi(() -> {
                android.content.res.TypedArray window = child.obtainStyledAttributes(new int[]{android.R.attr.windowIsTranslucent});
                assertTrue(type.getSimpleName(), window.getBoolean(0, false)); window.recycle();
                assertNotNull(layout.getChildAt(0).getBackground());
            });
            hold(layout, .25f); release(layout, .25f, MotionEvent.ACTION_CANCEL);
            onUi(() -> { assertFalse(type.getSimpleName(), child.isFinishing()); assertEquals(0f, layout.getChildAt(0).getTranslationX()); child.finish(); });
            getInstrumentation().waitForIdleSync(); SystemClock.sleep(230);
        }
    }

    public void testShortReleaseReturnsToOriginAndCompletedDragClosesOnlyChild() {
        Activity child = open(home, StorageActivity.class, null); SwipeBackLayout layout = swipe(child);
        hold(layout, .2f); release(layout, .2f, MotionEvent.ACTION_UP);
        onUi(() -> { assertFalse(child.isFinishing()); assertEquals(0f, layout.getChildAt(0).getTranslationX()); });
        hold(layout, .55f); release(layout, .55f, MotionEvent.ACTION_UP);
        assertTrue(child.isFinishing()); assertFalse(home.isFinishing());
    }

    public void testReversingAndVerticalDragsDoNotClosePage() {
        Activity child = open(home, StorageActivity.class, null); SwipeBackLayout layout = swipe(child);
        hold(layout, .5f);
        send(layout, MotionEvent.ACTION_MOVE, 4 + layout.getWidth() * .12f, layout.getHeight() / 2f, 570);
        send(layout, MotionEvent.ACTION_UP, 4 + layout.getWidth() * .1f, layout.getHeight() / 2f, 590);
        SystemClock.sleep(280); assertFalse(child.isFinishing());
        send(layout, MotionEvent.ACTION_DOWN, 4, layout.getHeight() * .7f, 0);
        send(layout, MotionEvent.ACTION_MOVE, 10, layout.getHeight() * .3f, 200);
        send(layout, MotionEvent.ACTION_UP, 10, layout.getHeight() * .3f, 300);
        onUi(() -> assertEquals(0f, layout.getChildAt(0).getTranslationX()));
    }

    public void testFastSwipeCanFinishBeforeDistanceThreshold() {
        Activity child = open(home, StorageActivity.class, null); SwipeBackLayout layout = swipe(child);
        send(layout, MotionEvent.ACTION_DOWN, 4, layout.getHeight() / 2f, 0);
        send(layout, MotionEvent.ACTION_MOVE, layout.getWidth() * .1f, layout.getHeight() / 2f, 10);
        send(layout, MotionEvent.ACTION_UP, layout.getWidth() * .24f, layout.getHeight() / 2f, 25);
        SystemClock.sleep(300); assertTrue(child.isFinishing()); assertFalse(home.isFinishing());
    }

    public void testStorageConfirmationSlidesOverStationaryStoragePage() {
        Activity child = open(home, StorageActivity.class, null); SwipeBackLayout layout = swipe(child);
        onUi(() -> child.findViewById(R.id.clearChats).performClick());
        hold(layout, .5f);
        onUi(() -> { assertEquals(0f, layout.getChildAt(0).getTranslationX());
            assertEquals(layout.getWidth() * .5f, child.findViewById(R.id.storageConfirm).getTranslationX(), 1); });
        release(layout, .5f, MotionEvent.ACTION_UP);
        onUi(() -> { assertFalse(child.isFinishing()); assertEquals(View.GONE, child.findViewById(R.id.storageConfirm).getVisibility());
            assertNotNull(repo.getChat(chat.id)); });
    }

    public void testReturnAnimationCanBeCaughtAgainWithoutJumping() {
        Activity child = open(home, StorageActivity.class, null); SwipeBackLayout layout = swipe(child);
        hold(layout, .25f);
        send(layout, MotionEvent.ACTION_UP, 4 + layout.getWidth() * .25f, layout.getHeight() / 2f, 600);
        SystemClock.sleep(25);
        float[] held = {0};
        onUi(() -> held[0] = layout.getChildAt(0).getTranslationX()); assertTrue(held[0] > 0);
        send(layout, MotionEvent.ACTION_DOWN, 4, layout.getHeight() / 2f, 0);
        onUi(() -> held[0] = layout.getChildAt(0).getTranslationX());
        send(layout, MotionEvent.ACTION_MOVE, 4 + layout.getWidth() * .2f, layout.getHeight() / 2f, 200);
        onUi(() -> assertEquals(held[0] + layout.getWidth() * .2f, layout.getChildAt(0).getTranslationX(), 1));
        release(layout, .2f, MotionEvent.ACTION_CANCEL); assertFalse(child.isFinishing());
    }

    public void testSecondFingerCancelsAndNextGestureStillWorks() {
        Activity child = open(home, StorageActivity.class, null); SwipeBackLayout layout = swipe(child); hold(layout, .45f);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] points = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i;
            points[i] = new MotionEvent.PointerCoords(); points[i].x = 4 + layout.getWidth() * .45f + i * 10;
            points[i].y = layout.getHeight() / 2f;
        }
        MotionEvent second = MotionEvent.obtain(down, down + 550, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, points, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        onUi(() -> layout.dispatchTouchEvent(second)); second.recycle();
        release(layout, .45f, MotionEvent.ACTION_UP);
        onUi(() -> { assertFalse(child.isFinishing()); assertEquals(0f, layout.getChildAt(0).getTranslationX()); });
        hold(layout, .5f); release(layout, .5f, MotionEvent.ACTION_UP); assertTrue(child.isFinishing());
    }

    public void testHomeDeleteConfirmationUsesTheSameFollowFingerDismissal() throws Exception {
        onUi(() -> {
            try {
                java.lang.reflect.Method confirm = MainActivity.class.getDeclaredMethod("confirmDelete", Chat.class);
                confirm.setAccessible(true); confirm.invoke(home, chat);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        SystemClock.sleep(180);
        SwipeBackLayout layout = home.findViewById(R.id.deleteSwipe); hold(layout, .5f);
        onUi(() -> assertEquals(layout.getWidth() * .5f, home.findViewById(R.id.deleteOverlay).getTranslationX(), 1));
        release(layout, .5f, MotionEvent.ACTION_UP);
        onUi(() -> { assertEquals(View.GONE, home.findViewById(R.id.deleteOverlay).getVisibility());
            assertFalse(home.isFinishing()); assertNotNull(repo.getChat(chat.id)); });
    }

    public void testChatOptionsMoveWithoutMovingChatOrTriggeringSlider() {
        Activity child = open(home, ChatActivity.class, new Intent(home, ChatActivity.class).putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id));
        SwipeBackLayout layout = swipe(child);
        onUi(() -> child.findViewById(R.id.chatOptionsButton).performClick()); SystemClock.sleep(200);
        hold(layout, .5f);
        onUi(() -> { assertEquals(0f, layout.getChildAt(0).getTranslationX());
            assertEquals(layout.getWidth() * .5f, child.findViewById(R.id.optionsPanel).getTranslationX(), 1); });
        release(layout, .5f, MotionEvent.ACTION_UP);
        onUi(() -> { assertFalse(child.isFinishing()); assertEquals(View.GONE, child.findViewById(R.id.optionsPanel).getVisibility()); });
    }

    public void testOnboardingOverlayStaysOnMovingForeground() {
        Activity child = open(home, StorageActivity.class, null); SwipeBackLayout layout = swipe(child);
        GuideOverlay[] guide = new GuideOverlay[1];
        onUi(() -> { guide[0] = GuideOverlay.attach(child, () -> {}); guide[0].show("右滑返回", null, GuideOverlay.RIGHT); });
        int[] before = new int[2], after = new int[2];
        onUi(() -> { assertSame(layout.getChildAt(0), guide[0].getParent()); guide[0].getLocationOnScreen(before); });
        hold(layout, .5f);
        onUi(() -> { guide[0].getLocationOnScreen(after); assertEquals(layout.getWidth() * .5f, after[0] - before[0], 1); });
        release(layout, .5f, MotionEvent.ACTION_CANCEL);
    }
}
