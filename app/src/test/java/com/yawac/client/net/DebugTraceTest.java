package com.yawac.client.net;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DebugTraceTest {
    @Test public void credentialsAreRedactedAndEndpointDropsCredentialsAndQuery() throws Exception {
        DebugTrace trace = new DebugTrace("chat", "chat", () -> true, ignored -> {});
        trace.configure(new ApiConfig("https://example.com/v1", "test-secret", "model"));
        trace.event("response", "原始响应", "test-secret Bearer other-key {\"api_key\":\"another-secret\",\"max_tokens\":12}");
        trace.finish("success", "完成");
        String stored = trace.snapshot();
        assertFalse(stored.contains("test-secret")); assertFalse(stored.contains("other-key"));
        assertFalse(stored.contains("another-secret")); assertTrue(stored.contains("max_tokens"));
        assertEquals("https://example.com/path", DebugTrace.safeUrl("https://name:pass@example.com/path?key=secret#secret"));
    }

    @Test public void jsonEscapedConfiguredKeyIsHiddenToo() throws Exception {
        String key = "a-secret-\"-with-slash\\x";
        DebugTrace trace = new DebugTrace("chat", "chat", () -> true, ignored -> {});
        trace.configure(new ApiConfig("https://example.com", key, "model"));
        trace.event("response", "响应", new JSONObject().put("content", key).toString());
        assertFalse(trace.snapshot().contains("a-secret"));
    }

    @Test public void fullDetailsAreRetainedUntilExplicitBoundAndTerminalStatusSurvives() throws Exception {
        DebugTrace trace = new DebugTrace("chat", "chat", () -> true, ignored -> {});
        trace.event("response", "接口响应", "x".repeat(DebugTrace.MAX_DETAIL_CHARS + 50));
        trace.finish("error", "HTTP 503");
        JSONObject saved = new JSONObject(trace.snapshot());
        assertTrue(saved.getBoolean("truncated")); assertEquals("error", saved.getString("status"));
        JSONArray events = saved.getJSONArray("events");
        assertEquals(50, events.getJSONObject(0).getInt("omittedChars"));
        assertEquals(DebugTrace.MAX_DETAIL_CHARS, events.getJSONObject(0).getString("detail").length());
        assertEquals("finish", events.getJSONObject(1).getString("kind"));
        assertTrue(saved.getLong("elapsedMs") >= 0);
    }

    @Test public void inactiveCaptureDoesNotWriteAndFinishedCaptureCannotAppend() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true); AtomicInteger writes = new AtomicInteger();
        DebugTrace trace = new DebugTrace("chat", "chat", active::get, ignored -> writes.incrementAndGet());
        trace.event("stage", "阶段", "分析"); assertEquals(1, writes.get());
        active.set(false); trace.event("response", "响应", "不能写入"); trace.finish("cancelled", "停止");
        assertEquals(1, writes.get());
        active.set(true); trace.event("response", "迟到响应", "不能写入"); assertEquals(1, writes.get());
        assertEquals(1, new JSONObject(trace.snapshot()).getJSONArray("events").length());
    }
}
