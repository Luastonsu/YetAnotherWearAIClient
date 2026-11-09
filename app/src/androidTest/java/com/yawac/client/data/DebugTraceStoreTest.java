package com.yawac.client.data;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.yawac.client.R;
import com.yawac.client.net.ApiConfig;
import com.yawac.client.net.DebugTrace;
import com.yawac.client.ui.DebugSettingsActivity;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** All deletions and preferences belong to an isolated fixture, never the user's traces/chats/key. */
public class DebugTraceStoreTest extends InstrumentationTestCase {
    private DebugTraceStore store, previous;
    private File root;
    private String preferences;
    private Context target;
    private Activity activity;
    private final java.util.List<Activity> screens = new java.util.ArrayList<>();
    private Field singleton;
    private ExecutorService worker;

    @Override protected void setUp() throws Exception {
        super.setUp(); target = getInstrumentation().getTargetContext();
        preferences = "test_debug_" + UUID.randomUUID();
        root = new File(target.getCacheDir(), preferences);
        assertTrue(root.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return root; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return target.getSharedPreferences(preferences, mode); }
        };
        store = new DebugTraceStore(isolated);
        singleton = DebugTraceStore.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (DebugTraceStore) singleton.get(null); singleton.set(null, store);
        Field field = DebugTraceStore.class.getDeclaredField("worker"); field.setAccessible(true);
        worker = (ExecutorService) field.get(store);
    }

    @Override protected void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(() -> {
            if (activity != null && !screens.contains(activity)) activity.finish();
            for (int i = screens.size() - 1; i >= 0; i--) screens.get(i).finish();
        });
        store.setEnabled(false); store.clear(ignored -> {}); flush(); worker.shutdown();
        singleton.set(null, previous); target.deleteSharedPreferences(preferences);
        new File(root, "debug-traces").delete(); root.delete();
        super.tearDown();
    }
    private void flush() throws Exception { worker.submit(() -> {}).get(10, TimeUnit.SECONDS); }
    private List<JSONObject> records() throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<List<JSONObject>> result = new AtomicReference<>();
        store.list(items -> { result.set(items); done.countDown(); });
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result.get();
    }
    private DebugTrace seed() {
        DebugTrace trace = store.begin("test-chat", "chat");
        trace.configure(new ApiConfig("https://example.com/v1", "fixture-key", "fixture-model"));
        trace.describe("调试测试问题"); trace.event("decision", "联网搜索决策", "search=false\nreason=可独立计算");
        trace.event("response", "最终回答 · HTTP 响应", "HTTP 200\n{\"choices\":[{\"message\":{\"content\":\"结果\",\"reasoning_content\":\"服务返回的内容\"}}]}");
        trace.finish("success", "完成"); return trace;
    }

    public void testOptInDisableAndClearNeverResurrectInFlightRecords() throws Exception {
        assertFalse(store.enabled()); assertNull(store.begin("chat", "chat"));
        store.setEnabled(true); DebugTrace old = store.begin("chat", "chat");
        old.event("stage", "阶段", "开始"); flush(); assertEquals(1, records().size());
        CountDownLatch cleared = new CountDownLatch(1); store.clear(ok -> { assertTrue(ok); cleared.countDown(); });
        old.event("response", "迟到响应", "不允许复活"); old.finish("success", "完成");
        assertTrue(cleared.await(5, TimeUnit.SECONDS)); flush(); assertEquals(0, records().size());
        DebugTrace disabled = store.begin("chat", "chat"); disabled.event("stage", "阶段", "开始"); flush();
        store.setEnabled(false); store.setEnabled(true);
        disabled.event("response", "关闭后响应", "不允许继续"); disabled.finish("success", "结束"); flush();
        AtomicReference<JSONObject> loaded = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        store.read(disabled.id, doc -> { loaded.set(doc); done.countDown(); }); assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, loaded.get().getJSONArray("events").length());
    }

    public void testRetentionLimitAndSecrets() throws Exception {
        store.setEnabled(true);
        for (int i = 0; i < DebugTraceStore.MAX_TRACES + 3; i++) seed();
        flush(); assertEquals(DebugTraceStore.MAX_TRACES, records().size());
        long bytes = 0; for (File file : new File(root, "debug-traces").listFiles()) {
            bytes += file.length(); assertFalse(new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("fixture-key"));
        }
        assertTrue(bytes <= DebugTraceStore.MAX_BYTES);
        String large = new String(new char[DebugTrace.MAX_DETAIL_CHARS]).replace('\0', 'x');
        for (int i = 0; i < 18; i++) {
            DebugTrace trace = store.begin("large-test", "chat"); trace.event("response", "长响应", large); trace.finish("success", "结束");
            flush();
        }
        bytes = 0; for (File file : new File(root, "debug-traces").listFiles()) bytes += file.length();
        assertEquals("", store.storageError());
        assertTrue("Retained bytes=" + bytes, bytes <= DebugTraceStore.MAX_BYTES);
        int retained = records().size();
        assertTrue("Retained records=" + retained + ", bytes=" + bytes, retained < DebugTraceStore.MAX_TRACES);
    }

    public void testExportPreservesUtf8AndRawEvents() throws Exception {
        store.setEnabled(true); DebugTrace trace = seed(); flush();
        File exported = new File(root, "export.json"); CountDownLatch done = new CountDownLatch(1); boolean[] success = {false};
        store.export(trace.id, android.net.Uri.fromFile(exported), ok -> { success[0] = ok; done.countDown(); });
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(success[0]);
        String raw = new String(java.nio.file.Files.readAllBytes(exported.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(raw.contains("调试测试问题")); assertTrue(raw.contains("reasoning_content")); assertFalse(raw.contains("fixture-key"));
        assertEquals(trace.id, new JSONObject(raw).getString("id")); assertTrue(exported.delete());
    }

    public void testDebugScreenOpensTraceAndShowsActualProviderReasoning() throws Exception {
        store.setEnabled(true); seed(); flush();
        activity = getInstrumentation().startActivitySync(new Intent(target, DebugSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        screens.add(activity);
        awaitText("调试测试问题");
        capture("debug-sessions.png");
        getInstrumentation().runOnMainSync(() -> assertTrue(activity.findViewById(R.id.debugRecordingToggle).isSelected()));
        openText("调试测试问题");
        awaitText("最终回答 · HTTP 响应");
        capture("debug-steps.png");
        Activity steps = activity;
        getInstrumentation().runOnMainSync(() -> ((android.widget.ScrollView) steps.findViewById(R.id.debugScroll)).scrollTo(0, 100));
        int[] parentScroll = {0};
        getInstrumentation().runOnMainSync(() -> parentScroll[0] = steps.findViewById(R.id.debugScroll).getScrollY());
        openText("最终回答 · HTTP 响应");
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(() -> {
            TextView detail = activity.findViewById(R.id.debugEventDetail);
            assertTrue(detail.getText().toString().contains("reasoning_content"));
            assertTrue(detail.getText().toString().contains("服务返回的内容"));
        });
        getInstrumentation().runOnMainSync(() -> ((DebugSettingsActivity) activity).getOnBackPressedDispatcher().onBackPressed());
        activity = steps;
        getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(230);
        getInstrumentation().runOnMainSync(() -> assertEquals(parentScroll[0], steps.findViewById(R.id.debugScroll).getScrollY()));
        awaitText("查看服务商思考内容");
        openText("查看服务商思考内容");
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(() -> assertTrue(((TextView) activity.findViewById(R.id.debugEventDetail))
                .getText().toString().contains("服务返回的内容")));
        capture("debug-reasoning.png");
    }
    private void openText(String text) {
        android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(DebugSettingsActivity.class.getName(), null, false);
        getInstrumentation().runOnMainSync(() -> findText((ViewGroup) activity.getWindow().getDecorView(), text).performClick());
        activity = getInstrumentation().waitForMonitorWithTimeout(monitor, 4000);
        getInstrumentation().removeMonitor(monitor); assertNotNull(activity); screens.add(activity);
        getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(230);
    }
    private void capture(String name) throws Exception {
        getInstrumentation().waitForIdleSync();
        android.graphics.Bitmap[] frame = new android.graphics.Bitmap[1];
        getInstrumentation().runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            frame[0] = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(frame[0]));
        });
        android.graphics.Bitmap bitmap = frame[0];
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(target.getFilesDir(), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
    private void awaitText(String text) throws Exception {
        for (int i = 0; i < 60; i++) {
            boolean[] found = {false};
            getInstrumentation().runOnMainSync(() -> found[0] = findText((ViewGroup) activity.getWindow().getDecorView(), text) != null);
            if (found[0]) return;
            Thread.sleep(50);
        }
        fail("Missing text: " + text);
    }
    private TextView findText(ViewGroup parent, String text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().contains(text)) return (TextView) child;
            if (child instanceof ViewGroup) { TextView found = findText((ViewGroup) child, text); if (found != null) return found; }
        }
        return null;
    }
}
