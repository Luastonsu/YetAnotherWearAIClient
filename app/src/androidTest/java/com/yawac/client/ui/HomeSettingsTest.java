package com.yawac.client.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.yawac.client.R;

public class HomeSettingsTest extends InstrumentationTestCase {
    private Activity activity;
    private SharedPreferences prefs;
    private java.util.Map<String, ?> original;
    private static final String[] KEYS = {"welcome_shown", Onboarding.AGREEMENT, Onboarding.STEP, "chat_text_sp", "reading_text_sp", "user_bubble_color", "ai_bubble_color"};

    @Override protected void setUp() throws Exception {
        super.setUp();
        prefs = getInstrumentation().getTargetContext().getSharedPreferences("wearai_prefs", 0);
        original = new java.util.HashMap<>(prefs.getAll());
        prefs.edit().putBoolean("welcome_shown", true).putInt(Onboarding.AGREEMENT, Onboarding.VERSION).putInt(Onboarding.STEP, Onboarding.DONE).commit();
    }
    @Override protected void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(() -> { if (activity != null) activity.finish(); });
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : KEYS) {
            Object value = original.get(key);
            if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else editor.remove(key);
        }
        editor.commit();
        super.tearDown();
    }
    private void launch(Class<?> type) {
        if (activity != null) getInstrumentation().runOnMainSync(() -> activity.finish());
        activity = getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
    }
    private void swipe(HomePagerLayout pager, float fromX, float toX, float fromY, float toY) {
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            float t = i / 10f;
            MotionEvent event = MotionEvent.obtain(start, start + i * 20,
                    i == 0 ? MotionEvent.ACTION_DOWN : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, 0);
            pager.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    public void testRightSwipeOpensSettingsAndLeftSwipeReturnsWithoutFinishing() {
        launch(MainActivity.class);
        getInstrumentation().runOnMainSync(() -> {
            HomePagerLayout pager = activity.findViewById(R.id.homePager);
            assertFalse(pager.isSettingsPage());
            assertEquals(1f, ((PageIndicatorView) activity.findViewById(R.id.pageIndicator)).progress(), .01f);
            swipe(pager, pager.getWidth() * 0.25f, pager.getWidth() * 0.85f, pager.getHeight() / 2f, pager.getHeight() / 2f);
            assertTrue(pager.isSettingsPage());
        });
        SystemClock.sleep(250);
        getInstrumentation().runOnMainSync(() -> {
            HomePagerLayout pager = activity.findViewById(R.id.homePager);
            assertEquals(0f, activity.findViewById(R.id.homeSettingsPage).getTranslationX(), 1f);
            assertEquals(0f, ((PageIndicatorView) activity.findViewById(R.id.pageIndicator)).progress(), .01f);
            assertEquals("模型供应商设置", ((TextView) activity.findViewById(R.id.providerSettings)).getText().toString());
            swipe(pager, pager.getWidth() * 0.85f, pager.getWidth() * 0.25f, pager.getHeight() / 2f, pager.getHeight() / 2f);
            assertFalse(pager.isSettingsPage());
            assertFalse(activity.isFinishing());
        });
    }

    public void testVerticalListGestureDoesNotOpenSettings() {
        launch(MainActivity.class);
        getInstrumentation().runOnMainSync(() -> {
            HomePagerLayout pager = activity.findViewById(R.id.homePager);
            swipe(pager, pager.getWidth() / 2f, pager.getWidth() / 2f, pager.getHeight() * 0.8f, pager.getHeight() * 0.25f);
            assertFalse(pager.isSettingsPage());
        });
    }

    public void testAppearancePersistsAndLeavesReadingSizeIndependent() {
        prefs.edit().putInt("chat_text_sp", 13).putInt("reading_text_sp", 17)
                .putInt(ChatAppearance.USER_COLOR, ChatStyle.DEFAULT_USER).commit();
        launch(ChatAppearanceActivity.class);
        getInstrumentation().runOnMainSync(() -> {
            activity.findViewById(R.id.chatFontLarger).performClick();
            activity.findViewById(R.id.userBubbleColor).performClick();
            assertEquals(14, prefs.getInt("chat_text_sp", 0));
            assertEquals(17, prefs.getInt("reading_text_sp", 0));
            assertEquals(ChatStyle.nextColor(ChatStyle.DEFAULT_USER), prefs.getInt(ChatAppearance.USER_COLOR, 0));
            TextView preview = activity.findViewById(R.id.previewUserBubble);
            assertEquals(14f, preview.getTextSize() / preview.getResources().getDisplayMetrics().scaledDensity, 0.1f);
            activity.findViewById(R.id.resetChatAppearance).performClick();
            assertEquals(13, ChatAppearance.size(activity));
            assertEquals(17, prefs.getInt("reading_text_sp", 0));
        });
    }

    public void testSettingsRowsUseRealHeytapButtonsWhenAvailable() {
        launch(MainActivity.class);
        getInstrumentation().runOnMainSync(() -> {
            HomePagerLayout pager = activity.findViewById(R.id.homePager);
            pager.showSettings(true, false);
            View candidate = NativeUi.create(activity, "HeyShapeButton", "hey_state_shape_btn_view_stub");
            if (candidate != null) {
                for (int id : new int[]{R.id.providerSettings, R.id.chatAppearanceSettings, R.id.storageSettings}) {
                    assertEquals("com.heytap.wearable.support.widget.HeyShapeButton", activity.findViewById(id).getClass().getName());
                }
            }
            assertNotNull(activity.findViewById(R.id.storageSettings));
        });
    }

    public void testClearingHistoryRequiresConfirmationAndCancelKeepsRecords() {
        int before = com.yawac.client.data.ChatRepository.get(getInstrumentation().getTargetContext()).getChats().size();
        launch(StorageActivity.class);
        getInstrumentation().runOnMainSync(() -> {
            activity.findViewById(R.id.clearChats).performClick();
            assertEquals(View.VISIBLE, activity.findViewById(R.id.storageConfirm).getVisibility());
            activity.findViewById(R.id.cancelClearChats).performClick();
            assertEquals(View.GONE, activity.findViewById(R.id.storageConfirm).getVisibility());
            assertEquals(before, com.yawac.client.data.ChatRepository.get(activity).getChats().size());
        });
    }

    public void testCaptureSettingsScreensForVisualReview() {
        prefs.edit().putInt("chat_text_sp", 13).putInt(ChatAppearance.USER_COLOR, ChatStyle.DEFAULT_USER)
                .putInt(ChatAppearance.AI_COLOR, ChatStyle.DEFAULT_AI).commit();
        launch(MainActivity.class);
        getInstrumentation().runOnMainSync(() -> ((HomePagerLayout) activity.findViewById(R.id.homePager)).showSettings(true, false));
        capture("settings-home.png");
        launch(ChatAppearanceActivity.class);
        capture("appearance-top.png");
        getInstrumentation().runOnMainSync(() -> {
            android.widget.ScrollView scroll = (android.widget.ScrollView) activity.findViewById(R.id.previewAiBubble).getParent().getParent();
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        });
        getInstrumentation().waitForIdleSync();
        capture("appearance-preview.png");
        launch(StorageActivity.class);
        capture("storage.png");
        getInstrumentation().runOnMainSync(() -> activity.findViewById(R.id.clearChats).performClick());
        capture("storage-confirm.png");
        getInstrumentation().runOnMainSync(() -> activity.findViewById(R.id.cancelClearChats).performClick());
    }

    private void capture(String name) {
        SystemClock.sleep(300);
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            decor.draw(new android.graphics.Canvas(bitmap));
            java.io.File directory = new java.io.File(activity.getFilesDir(), "settings-qa");
            directory.mkdirs();
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name))) {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            } catch (java.io.IOException error) { throw new AssertionError(error); }
            finally { bitmap.recycle(); }
        });
    }
}
