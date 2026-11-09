package com.yawac.client.net;

import com.yawac.client.data.Models.Message;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

public class DeepSeekClientTest {
    private MockWebServer server;
    private OkHttpClient http;
    private DeepSeekClient client;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build();
        client = newClient(http);
    }

    private DeepSeekClient newClient(OkHttpClient http) {
        return new DeepSeekClient(http, server.url("/chat/completions").toString(),
                server.url("/v1/tools/search").toString(), "test-api-key");
    }

    @After public void tearDown() throws Exception {
        client.cancel("chat");
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
        server.shutdown();
    }

    private List<Message> history(String text) {
        return Collections.singletonList(new Message("user", text, 0));
    }

    private MockResponse reply(String text) throws Exception {
        JSONObject message = new JSONObject().put("role", "assistant").put("content", text);
        return new MockResponse().setBody(new JSONObject().put("choices", new JSONArray()
                .put(new JSONObject().put("message", message).put("finish_reason", "stop"))).toString());
    }

    private MockResponse searchReply() throws Exception {
        return new MockResponse().setBody(new JSONObject().put("results", new JSONArray().put(new JSONObject()
                .put("title", "Source").put("url", "https://example.com")
                .put("summary", "供回答使用的长摘要").put("snippet", "短摘要")
                .put("content", "").put("date", "2026-10-16T00:00:00Z").put("site_name", "Example"))).toString());
    }

    private MockResponse planReply(String query) throws Exception {
        return reply(new JSONObject().put("search", !query.isEmpty()).put("query", query).toString());
    }

    @Test public void secondTurnIncludesFirstAnswerAndCompletes() throws Exception {
        server.enqueue(reply("收到"));
        Outcome first = new Outcome();
        client.chat("chat", history("记住 37"), false, first);
        first.await();
        assertEquals("收到", first.content);
        assertFalse(client.isPending("chat"));
        server.takeRequest(1, TimeUnit.SECONDS);

        List<Message> history = new ArrayList<>(history("记住 37"));
        history.add(new Message("assistant", first.content, 0));
        history.add(new Message("user", "刚才的数字？", 0));
        Message pending = new Message("assistant", "", 0);
        pending.streaming = true;
        history.add(pending);
        server.enqueue(reply("37"));
        Outcome second = new Outcome();
        client.chat("chat", history, false, second);
        second.await();
        assertEquals("37", second.content);
        assertFalse(client.isPending("chat"));
        JSONArray messages = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS)
                .getBody().readUtf8()).getJSONArray("messages");
        assertEquals(4, messages.length());
        assertEquals("收到", messages.getJSONObject(2).getString("content"));
        assertEquals("刚才的数字？", messages.getJSONObject(3).getString("content"));
    }

    @Test public void malformedSecondReplyEndsWaitingAndNextRequestSucceeds() throws Exception {
        server.enqueue(reply("第一轮成功"));
        Outcome first = new Outcome();
        client.chat("chat", history("第一轮"), false, first);
        first.await();
        // Previously choices[0].optJSONObject("message") threw a NullPointerException.
        server.enqueue(new MockResponse().setBody("{\"choices\":[null]}"));
        Outcome second = new Outcome();
        client.chat("chat", history("第二轮"), false, second);
        second.await();
        assertTrue(second.error.contains("INVALID_RESPONSE"));
        assertFalse(client.isPending("chat"));
        server.enqueue(reply("恢复成功"));
        Outcome third = new Outcome();
        client.chat("chat", history("重试"), false, third);
        third.await();
        assertEquals("恢复成功", third.content);
    }

    @Test public void serverErrorShowsHttpCodeProviderCodeAndMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody(
                "{\"error\":{\"code\":\"server_overloaded\",\"message\":\"Server is busy\"}}"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("你好"), false, outcome);
        outcome.await();
        assertTrue(outcome.error.contains("HTTP 503"));
        assertTrue(outcome.error.contains("server_overloaded"));
        assertTrue(outcome.error.contains("Server is busy"));
        assertFalse(client.isPending("chat"));
    }

    @Test public void errorTypeAndNumericCodeAreSupported() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(422).setBody(
                "{\"error\":{\"code\":null,\"type\":\"invalid_request_error\",\"message\":\"bad input\"}}"));
        Outcome first = new Outcome();
        client.chat("chat", history("你好"), false, first);
        first.await();
        assertTrue(first.error.contains("invalid_request_error"));
        server.enqueue(new MockResponse().setResponseCode(400).setBody(
                "{\"error\":{\"code\":1234,\"message\":\"bad input\"}}"));
        Outcome second = new Outcome();
        client.chat("chat", history("你好"), false, second);
        second.await();
        assertTrue(second.error.contains("错误码：1234"));
    }

    @Test public void nonJsonServerErrorStillShowsHttpCode() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("你好"), false, outcome);
        outcome.await();
        assertTrue(outcome.error.contains("HTTP 502"));
        assertTrue(outcome.error.contains("HTTP_502"));
        assertFalse(outcome.error.contains("<html>"));
    }

    @Test public void interruptedErrorBodyStillShowsKnownHttpCode() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"error\":{\"code\":\"server_error\",\"message\":\"failure\"}}")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        Outcome outcome = new Outcome();
        client.chat("chat", history("你好"), false, outcome);
        outcome.await();
        assertTrue(outcome.error, outcome.error.contains("HTTP 500"));
    }

    @Test public void errorEnvelopeWithHttp200IsNotTreatedAsSuccess() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"error\":{\"code\":\"upstream_failure\",\"message\":\"Unavailable\"}}"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("你好"), false, outcome);
        outcome.await();
        assertTrue(outcome.error.contains("upstream_failure"));
    }

    @Test public void incompleteAndInvalidRepliesReportErrors() throws Exception {
        String[] bodies = {"not json", "{}", "{\"choices\":[{}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"reasoning_content\":\"thinking only\"}}]}"};
        String[] codes = {"INVALID_JSON", "INVALID_RESPONSE", "INVALID_RESPONSE", "EMPTY_CONTENT"};
        for (int i = 0; i < bodies.length; i++) {
            server.enqueue(new MockResponse().setBody(bodies[i]));
            Outcome outcome = new Outcome();
            client.chat("chat", history("你好"), false, outcome);
            outcome.await();
            assertTrue(outcome.error, outcome.error.contains(codes[i]));
            assertFalse(client.isPending("chat"));
        }
    }

    @Test public void serverInterruptionCodesAreVisibleEvenWithHttp200() throws Exception {
        for (String reason : new String[]{"insufficient_system_resource", "aborted", "content_filter"}) {
            server.enqueue(new MockResponse().setBody("{\"choices\":[{\"finish_reason\":\""
                    + reason + "\",\"message\":{\"content\":\"\"}}]}"));
            Outcome outcome = new Outcome();
            client.chat("chat", history("你好"), false, outcome);
            outcome.await();
            assertTrue(outcome.error.contains(reason));
        }
    }

    @Test public void totalTimeoutStopsKeepAliveDataAndReportsTimeoutNotCancellation() throws Exception {
        OkHttpClient shortTimeout = http.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(2, TimeUnit.SECONDS).build();
        client = newClient(shortTimeout);
        server.enqueue(new MockResponse().setBody(String.join("", Collections.nCopies(50, "\n")))
                .throttleBody(1, 50, TimeUnit.MILLISECONDS));
        Outcome outcome = new Outcome();
        client.chat("chat", history("你好"), false, outcome);
        outcome.await();
        assertNotNull(outcome.error);
        assertTrue(outcome.error, outcome.error.contains("TIMEOUT"));
        assertFalse(outcome.cancelled);
        assertFalse(client.isPending("chat"));
    }

    @Test public void replacementCancelsOnlyOldRequestAndReportsEachOutcomeOnce() throws Exception {
        server.enqueue(reply("旧回复").setBodyDelay(2, TimeUnit.SECONDS));
        Outcome old = new Outcome();
        client.chat("chat", history("旧问题"), false, old);
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        server.enqueue(reply("新回复"));
        Outcome current = new Outcome();
        client.chat("chat", history("新问题"), false, current);
        old.await();
        current.await();
        assertTrue(old.cancelled);
        assertEquals("新回复", current.content);
        assertEquals(1, old.callbacks.get());
        assertFalse(client.isPending("chat"));
    }

    @Test public void failedMessagesAreNeverSentAsAssistantHistory() throws Exception {
        List<Message> history = new ArrayList<>(history("问题"));
        Message failed = new Message("assistant", "⚠️ HTTP 503", 0);
        failed.failed = true;
        history.add(failed);
        history.add(new Message("user", "重试", 0));
        server.enqueue(reply("回答"));
        Outcome outcome = new Outcome();
        client.chat("chat", history, false, outcome);
        outcome.await();
        JSONArray messages = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS)
                .getBody().readUtf8()).getJSONArray("messages");
        assertEquals(3, messages.length());
        assertEquals("重试", messages.getJSONObject(2).getString("content"));
    }

    @Test public void plannerRetriesOnceAfterTransientNetworkFailure() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        OkHttpClient flaky = http.newBuilder().addInterceptor(chain -> {
            if (chain.request().url().encodedPath().equals("/chat/completions")
                    && attempts.getAndIncrement() == 0) {
                throw new java.io.IOException("simulated cold start");
            }
            return chain.proceed(chain.request());
        }).build();
        client = newClient(flaky);
        server.enqueue(planReply(""));
        server.enqueue(reply("独立回答"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("今天 新闻"), true, outcome);
        outcome.await();
        assertEquals("独立回答", outcome.content);
        assertEquals(3, attempts.get());
        assertEquals(2, server.getRequestCount());
    }

    @Test public void searchAndCompletionHaveOneSharedPendingState() throws Exception {
        server.enqueue(planReply("联网主题 背景 事实"));
        server.enqueue(searchReply());
        server.enqueue(reply("检索回答"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("联网问题"), true, outcome);
        outcome.await();
        assertEquals("检索回答", outcome.content);
        RecordedRequest plan = server.takeRequest(1, TimeUnit.SECONDS);
        JSONObject planBody = new JSONObject(plan.getBody().readUtf8());
        assertEquals("json_object", planBody.getJSONObject("response_format").getString("type"));
        assertEquals("disabled", planBody.getJSONObject("thinking").getString("type"));
        RecordedRequest search = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest completion = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v1/tools/search", search.getPath());
        assertEquals("Bearer test-api-key", search.getHeader("Authorization"));
        assertNull(search.getHeader("x-api-key"));
        assertNull(search.getHeader("anthropic-version"));
        JSONObject searchBody = new JSONObject(search.getBody().readUtf8());
        assertEquals("联网主题 背景 事实", searchBody.getString("query"));
        assertFalse(searchBody.has("ori_question"));
        assertEquals(5, searchBody.getInt("count"));
        assertFalse(searchBody.getBoolean("need_content"));
        assertFalse(searchBody.has("model"));
        assertFalse(searchBody.has("messages"));
        assertFalse(searchBody.has("tools"));
        assertEquals("/chat/completions", completion.getPath());
        JSONObject completionBody = new JSONObject(completion.getBody().readUtf8());
        String context = completionBody.getJSONArray("messages").getJSONObject(1).getString("content");
        assertTrue(context.contains("https://example.com"));
        assertTrue(context.contains("供回答使用的长摘要"));
        assertTrue(context.contains("独立分析"));
        assertTrue(context.contains("同题的现成答案不能代替"));
        assertFalse(client.isPending("chat"));
    }

    @Test public void quickReplyDisablesThinkingAndReportsGenerationStage() throws Exception {
        server.enqueue(reply("快速回答"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("你好"), false, false, outcome);
        outcome.await();
        JSONObject body = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertFalse(body.has("reasoning_effort"));
        assertEquals(Collections.singletonList("生成回答中"), outcome.stages);
    }

    @Test public void searchedQuickReplyKeepsModeAndChangesStages() throws Exception {
        server.enqueue(planReply("天气 预报"));
        server.enqueue(searchReply());
        server.enqueue(reply("联网快答"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("天气"), true, false, outcome);
        outcome.await();
        server.takeRequest(1, TimeUnit.SECONDS);
        server.takeRequest(1, TimeUnit.SECONDS);
        JSONObject body = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertEquals(java.util.Arrays.asList("分析参考需求", "联网检索中", "生成回答中"), outcome.stages);
    }

    @Test public void deepReplyEnablesThinkingAndEffort() throws Exception {
        server.enqueue(reply("深入回答"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("复杂问题"), false, true, outcome);
        outcome.await();
        JSONObject body = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
        assertEquals("high", body.getString("reasoning_effort"));
        assertEquals(Collections.singletonList("思考并生成中"), outcome.stages);
    }

    @Test public void selectedEffortIsSentAndInvalidValuesFallBackToLegacyHigh() throws Exception {
        for (String effort : new String[]{"low", "high", "max", "unknown"}) {
            server.enqueue(reply("回答")); Outcome outcome = new Outcome();
            client.chat("chat", history("问题"), false, true, effort, outcome); outcome.await();
            JSONObject body = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"));
            assertEquals("unknown".equals(effort) ? "high" : effort, body.getString("reasoning_effort"));
        }
        server.enqueue(reply("快速")); Outcome quick = new Outcome();
        client.chat("chat", history("问题"), false, false, "max", quick); quick.await();
        JSONObject body = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertFalse(body.has("reasoning_effort"));
    }

    @Test public void planningAndSearchKeepTheSelectedAnswerEffort() throws Exception {
        server.enqueue(planReply("原理 参考").setBodyDelay(100, TimeUnit.MILLISECONDS));
        server.enqueue(searchReply()); server.enqueue(reply("独立答案")); Outcome outcome = new Outcome();
        client.chat("chat", history("参考资料"), true, true, "max", outcome); outcome.await();
        JSONObject plan = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("disabled", plan.getJSONObject("thinking").getString("type"));
        assertFalse(plan.has("reasoning_effort"));
        assertEquals("/v1/tools/search", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        JSONObject answer = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("max", answer.getString("reasoning_effort"));
    }

    @Test public void independentlyAnsweredSearchRoundRetainsLowEffort() throws Exception {
        server.enqueue(planReply("")); server.enqueue(reply("独立分析")); Outcome outcome = new Outcome();
        client.chat("chat", history("计算"), true, true, "low", outcome); outcome.await();
        server.takeRequest(1, TimeUnit.SECONDS);
        JSONObject answer = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("low", answer.getString("reasoning_effort"));
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }

    @Test public void searchHttpAndErrorEnvelopesKeepTheirCodes() throws Exception {
        server.enqueue(planReply("参考"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody(
                "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Slow down\"}}"));
        Outcome first = new Outcome();
        client.chat("chat", history("联网问题"), true, first);
        first.await();
        assertTrue(first.error.contains("HTTP 429"));
        assertTrue(first.error.contains("rate_limit_error"));
        server.enqueue(planReply("参考"));
        server.enqueue(new MockResponse().setBody("{\"error\":{\"type\":\"invalid_request_error\","
                + "\"code\":\"invalid_request_error\",\"message\":\"query is too long\"}}"));
        Outcome second = new Outcome();
        client.chat("chat", history("联网问题"), true, second);
        second.await();
        assertTrue(second.error.contains("invalid_request_error"));
    }

    @Test public void failedFlagSurvivesReloadAndLegacyWarningsAreRecognized() throws Exception {
        Message message = new Message("assistant", "⚠️ 错误", 0);
        message.failed = true;
        assertTrue(Message.fromJson(message.toJson()).failed);
        JSONObject legacy = message.toJson();
        legacy.remove("failed");
        assertTrue(Message.fromJson(legacy).failed);
    }

    @Test public void errorDetailsDoNotExposeConfiguredKey() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody(
                "{\"error\":{\"message\":\"Invalid key: test-api-key\"}}"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("你好"), false, outcome);
        outcome.await();
        assertFalse(outcome.error.contains("test-api-key"));
        assertTrue(outcome.error.contains("[已隐藏]"));
    }

    private DeepSeekClient configured(DeepSeekClient.ConfigSource source) {
        OkHttpClient transport = http.newBuilder().addInterceptor(chain -> {
            // Validation uses the HTTPS user configuration; only the test transport is redirected.
            return chain.proceed(chain.request().newBuilder()
                    .url(server.url(chain.request().url().encodedPath())).build());
        }).build();
        return new DeepSeekClient(transport, source);
    }

    @Test public void customProviderUsesSavedEndpointKeyAndModelWithoutDeepSeekFields() throws Exception {
        client = configured(() -> new ApiConfig("https://custom.example/proxy/v1/", "user-key", "my-model"));
        server.enqueue(reply("自定义回答"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("你好"), false, false, outcome);
        outcome.await();
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/proxy/v1/chat/completions", request.getPath());
        assertEquals("Bearer user-key", request.getHeader("Authorization"));
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("my-model", body.getString("model"));
        assertFalse(body.getBoolean("stream"));
        assertFalse(body.has("thinking"));
        assertFalse(body.has("reasoning_effort"));
        assertFalse(body.has("tools"));
        assertFalse(body.has("max_tokens"));
        assertEquals("自定义回答", outcome.content);
    }

    @Test public void titlesUseTheSameCustomProviderAndSelectedModel() throws Exception {
        client = configured(() -> new ApiConfig("https://custom.example/v1/chat/completions", "own-key", "title-model"));
        server.enqueue(reply("新的主题"));
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        client.generateTitle("chat", "问题", new DeepSeekClient.TitleListener() {
            @Override public void onTitle(String title) { result[0] = title; done.countDown(); }
            @Override public void onError(String error) { result[0] = error; done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("新的主题", result[0]);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v1/chat/completions", request.getPath());
        assertEquals("Bearer own-key", request.getHeader("Authorization"));
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("title-model", body.getString("model"));
        assertFalse(body.has("thinking"));
        assertFalse(body.has("temperature"));
    }

    @Test public void missingConfigFailsWithoutSendingAnHttpRequest() throws Exception {
        client = configured(ApiConfig::defaults);
        Outcome outcome = new Outcome();
        client.chat("chat", history("问题"), false, false, outcome);
        outcome.await();
        assertTrue(outcome.error.contains("API_CONFIG_REQUIRED"));
        assertFalse(client.isPending("chat"));
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }

    @Test public void unreadableSettingsEndTheRequestWithoutExposingTheException() throws Exception {
        client = configured(() -> { throw new Exception("sensitive-key"); });
        Outcome outcome = new Outcome();
        client.chat("chat", history("问题"), false, false, outcome);
        outcome.await();
        assertTrue(outcome.error.contains("CONFIG_STORAGE_ERROR"));
        assertFalse(outcome.error.contains("sensitive-key"));
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }

    @Test public void customSearchAndThinkingFailLocallyWithoutSendingCredentialsElsewhere() throws Exception {
        client = configured(() -> new ApiConfig("https://custom.example/v1", "custom-key", "model"));
        for (boolean search : new boolean[]{true, false}) {
            Outcome outcome = new Outcome();
            client.chat("chat", history("问题"), search, !search, outcome);
            outcome.await();
            assertTrue(outcome.error.contains("UNSUPPORTED_CAPABILITY"));
        }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }

    @Test public void changedSettingsApplyOnNextRequestAndErrorRedactionUsesItsOwnKey() throws Exception {
        java.util.concurrent.atomic.AtomicReference<ApiConfig> saved = new java.util.concurrent.atomic.AtomicReference<>(
                new ApiConfig("https://first.example/v1", "first-key", "first-model"));
        client = configured(saved::get);
        server.enqueue(new MockResponse().setResponseCode(401).setBody(
                "{\"error\":{\"message\":\"bad first-key\"}}").setBodyDelay(200, TimeUnit.MILLISECONDS));
        Outcome first = new Outcome();
        client.chat("chat", history("问题"), false, false, first);
        RecordedRequest firstRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("Bearer first-key", firstRequest.getHeader("Authorization"));
        saved.set(new ApiConfig("https://second.example/other", "second-key", "second-model"));
        first.await();
        assertFalse(first.error.contains("first-key"));
        server.enqueue(reply("新配置生效"));
        Outcome next = new Outcome();
        client.chat("chat", history("问题"), false, false, next);
        next.await();
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/other/chat/completions", request.getPath());
        assertEquals("Bearer second-key", request.getHeader("Authorization"));
        assertEquals("second-model", new JSONObject(request.getBody().readUtf8()).getString("model"));
    }

    @Test public void deepSeekSearchAndAnswerKeepTheOriginalConfigWhenSettingsChange() throws Exception {
        java.util.concurrent.atomic.AtomicReference<ApiConfig> saved = new java.util.concurrent.atomic.AtomicReference<>(
                new ApiConfig(ApiConfig.DEEPSEEK_URL, "original-key", "deepseek-v4-pro"));
        client = configured(saved::get);
        server.enqueue(planReply("背景 文档").setBodyDelay(200, TimeUnit.MILLISECONDS));
        server.enqueue(searchReply().setBodyDelay(200, TimeUnit.MILLISECONDS));
        server.enqueue(reply("保持原配置"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("搜索"), true, false, outcome);
        RecordedRequest plan = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("Bearer original-key", plan.getHeader("Authorization"));
        assertEquals("deepseek-v4-pro", new JSONObject(plan.getBody().readUtf8()).getString("model"));
        saved.set(new ApiConfig("https://different.example/v1", "other-key", "other-model"));
        outcome.await();
        RecordedRequest search = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v1/tools/search", search.getPath());
        assertEquals("Bearer original-key", search.getHeader("Authorization"));
        assertEquals("背景 文档", new JSONObject(search.getBody().readUtf8()).getString("query"));
        RecordedRequest answer = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/chat/completions", answer.getPath());
        assertEquals("Bearer original-key", answer.getHeader("Authorization"));
        assertEquals("deepseek-v4-pro", new JSONObject(answer.getBody().readUtf8()).getString("model"));
        assertEquals("保持原配置", outcome.content);
    }

    @Test public void redirectDoesNotForwardTheUserKey() throws Exception {
        client = configured(() -> new ApiConfig("https://custom.example/v1", "secret-key", "model"));
        server.enqueue(new MockResponse().setResponseCode(307)
                .setHeader("Location", server.url("/untrusted")));
        Outcome outcome = new Outcome();
        client.chat("chat", history("问题"), false, false, outcome);
        outcome.await();
        assertTrue(outcome.error.contains("HTTP 307"));
        server.takeRequest(1, TimeUnit.SECONDS);
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }

    @Test public void malformedSearchResultsRemainVisibleErrorsRatherThanClaimingSearchSuccess() throws Exception {
        String[] payloads = {"{}", "{\"results\":null}", "{\"results\":{}}"};
        for (int i = 0; i < payloads.length; i++) {
            server.enqueue(planReply("关键词"));
            server.enqueue(new MockResponse().setBody(payloads[i]));
            Outcome outcome = new Outcome();
            client.chat("chat", history("问题"), true, false, outcome);
            outcome.await();
            assertTrue(outcome.error, outcome.error.contains("INVALID_SEARCH_RESPONSE"));
            assertNull(outcome.content);
            assertFalse(client.isPending("chat"));
            assertEquals("/chat/completions", server.takeRequest(1, TimeUnit.SECONDS).getPath());
            assertEquals("/v1/tools/search", server.takeRequest(1, TimeUnit.SECONDS).getPath());
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test public void missingSearchQuestionFailsLocallyWithoutSendingARequest() throws Exception {
        Outcome outcome = new Outcome();
        client.chat("chat", history("\u3000\u00a0"), true, false, outcome);
        outcome.await();
        assertTrue(outcome.error.contains("EMPTY_SEARCH_QUERY"));
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }

    @Test public void cancellationDuringSearchNeverStartsTheAnswerCall() throws Exception {
        server.enqueue(planReply("关键词"));
        server.enqueue(searchReply().setBodyDelay(1, TimeUnit.SECONDS));
        Outcome outcome = new Outcome();
        client.chat("chat", history("搜索"), true, false, outcome);
        server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v1/tools/search", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        client.cancel("chat");
        outcome.await();
        assertTrue(outcome.cancelled);
        assertFalse(client.isPending("chat"));
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
    }

    @Test public void cancellationAfterSearchCancelsTheAnswerCallToo() throws Exception {
        server.enqueue(planReply("关键词"));
        server.enqueue(searchReply());
        server.enqueue(reply("过期回答").setBodyDelay(1, TimeUnit.SECONDS));
        Outcome outcome = new Outcome();
        client.chat("chat", history("搜索"), true, true, outcome);
        server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v1/tools/search", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        RecordedRequest answer = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/chat/completions", answer.getPath());
        assertEquals("enabled", new JSONObject(answer.getBody().readUtf8()).getJSONObject("thinking").getString("type"));
        client.cancel("chat");
        outcome.await();
        assertTrue(outcome.cancelled);
        assertFalse(client.isPending("chat"));
    }

    @Test public void searchServiceErrorsKeepCodesRedactTheKeyAndDoNotAutoRetry() throws Exception {
        for (int status : new int[]{400, 402, 429, 504}) {
            server.enqueue(planReply("关键词"));
            server.enqueue(new MockResponse().setResponseCode(status).setBody(
                    "{\"error\":{\"code\":\"search_failure\",\"message\":\"failed test-api-key\",\"param\":null}}"));
            Outcome outcome = new Outcome();
            client.chat("chat", history("搜索"), true, false, outcome);
            outcome.await();
            assertTrue(outcome.error.contains("HTTP " + status));
            assertTrue(outcome.error.contains("search_failure"));
            assertFalse(outcome.error.contains("test-api-key"));
            assertTrue(outcome.error.contains("[已隐藏]"));
            assertEquals("/chat/completions", server.takeRequest(1, TimeUnit.SECONDS).getPath());
            assertEquals("/v1/tools/search", server.takeRequest(1, TimeUnit.SECONDS).getPath());
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test public void selfContainedProblemsCanSkipSearchAndStillAnswerInTheSelectedMode() throws Exception {
        server.enqueue(planReply(""));
        server.enqueue(reply("由给定条件可独立推得答案"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("求解 2x+3=11"), true, true, outcome);
        outcome.await();
        assertEquals("由给定条件可独立推得答案", outcome.content);
        JSONObject planner = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("disabled", planner.getJSONObject("thinking").getString("type"));
        JSONObject answer = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("enabled", answer.getJSONObject("thinking").getString("type"));
        assertTrue(answer.getJSONArray("messages").getJSONObject(1).getString("content").contains("本轮未执行网页搜索"));
        assertEquals(java.util.Arrays.asList("分析参考需求", "独立分析中"), outcome.stages);
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }

    @Test public void noReferencesCanStillProduceAnHonestIndependentAnswer() throws Exception {
        for (String results : new String[]{"[]", "[{\"url\":\"javascript:alert(1)\"}]"}) {
            server.enqueue(planReply("背景关键词"));
            server.enqueue(new MockResponse().setBody("{\"results\":" + results + "}"));
            server.enqueue(reply("未查到可用资料，下面按题意分析"));
            Outcome outcome = new Outcome();
            client.chat("chat", history("问题"), true, false, outcome);
            outcome.await();
            assertNull(outcome.error);
            assertNotNull(outcome.content);
            server.takeRequest(1, TimeUnit.SECONDS);
            server.takeRequest(1, TimeUnit.SECONDS);
            JSONObject answer = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
            assertTrue(answer.getJSONArray("messages").getJSONObject(1).getString("content").contains("没有找到可用参考资料"));
        }
    }

    @Test public void malformedReferencePlansDoNotTurnIntoFullQuestionSearches() throws Exception {
        for (String content : new String[]{"", "not JSON", "{\"search\":\"true\"}", "{\"search\":true,\"query\":\"\"}"}) {
            server.enqueue(reply(content));
            Outcome outcome = new Outcome();
            client.chat("chat", history("一道完整的数学题"), true, false, outcome);
            outcome.await();
            assertTrue(outcome.error.contains("SEARCH_PLAN_ERROR"));
            assertEquals("/chat/completions", server.takeRequest(1, TimeUnit.SECONDS).getPath());
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test public void cancellationDuringPlanningPreventsSearchAndAnswer() throws Exception {
        server.enqueue(planReply("关键词").setBodyDelay(1, TimeUnit.SECONDS));
        Outcome outcome = new Outcome();
        client.chat("chat", history("问题"), true, false, outcome);
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        client.cancel("chat");
        outcome.await();
        assertTrue(outcome.cancelled);
        assertFalse(client.isPending("chat"));
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
    }

    @Test public void debugCapturesWholeSearchRoundIncludingActualReasoningAndInjectedReferences() throws Exception {
        java.util.concurrent.atomic.AtomicReference<DebugTrace> capture = captureTrace();
        server.enqueue(reply("{\"search\":true,\"query\":\"材料 参数 官方\",\"reason\":\"需要核实材料参数\"}"));
        server.enqueue(searchReply());
        server.enqueue(new MockResponse().setBody("{\"id\":\"response-id\",\"usage\":{\"total_tokens\":123},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"最终结论\",\"reasoning_content\":\"服务商公开返回的思考内容\"}}]}"));
        Outcome outcome = new Outcome();
        client.chat("chat", history("请核实材料参数"), true, true, outcome); outcome.await();
        JSONObject trace = new JSONObject(capture.get().snapshot());
        assertEquals("success", trace.getString("status"));
        String serialized = trace.toString();
        assertTrue(serialized.contains("reasoning_content")); assertTrue(serialized.contains("需要核实材料参数"));
        assertTrue(serialized.contains("材料 参数 官方")); assertTrue(serialized.contains("供回答使用的长摘要"));
        assertTrue(serialized.contains("注入回答的参考资料")); assertTrue(serialized.contains("total_tokens"));
        assertTrue(serialized.contains("你是一个运行在智能手表上的中文 AI 助手"));
        assertFalse(serialized.contains("test-api-key")); assertFalse(serialized.contains("Authorization" + ":"));
        assertEquals(3, server.getRequestCount());
        JSONArray events = trace.getJSONArray("events"); int requests = 0, responses = 0;
        for (int i = 0; i < events.length(); i++) {
            if ("request".equals(events.getJSONObject(i).getString("kind"))) requests++;
            if ("response".equals(events.getJSONObject(i).getString("kind"))) responses++;
            assertEquals(i + 1, events.getJSONObject(i).getInt("sequence"));
        }
        assertEquals(3, requests); assertEquals(3, responses);
    }

    @Test public void debugShowsSkippedSearchWithoutInventingReasoning() throws Exception {
        java.util.concurrent.atomic.AtomicReference<DebugTrace> capture = captureTrace();
        server.enqueue(planReply("")); server.enqueue(reply("2"));
        Outcome outcome = new Outcome(); client.chat("chat", history("1+1"), true, false, outcome); outcome.await();
        String trace = capture.get().snapshot();
        assertTrue(trace.contains("search=false")); assertTrue(trace.contains("服务未返回决策说明"));
        assertFalse(new JSONObject(trace).getJSONArray("events").toString().contains("reasoning_content"));
        assertFalse(trace.contains("网页搜索 · HTTP 请求"));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void debugRetainsRawInvalidResponseAndHttpErrorButHidesKey() throws Exception {
        java.util.concurrent.atomic.AtomicReference<DebugTrace> capture = captureTrace();
        server.enqueue(new MockResponse().setBody("not-json test-api-key"));
        Outcome invalid = new Outcome(); client.chat("chat", history("问题"), false, invalid); invalid.await();
        assertTrue(capture.get().snapshot().contains("not-json"));
        assertFalse(capture.get().snapshot().contains("test-api-key"));
        assertEquals("error", new JSONObject(capture.get().snapshot()).getString("status"));
        server.enqueue(new MockResponse().setResponseCode(402).setBody("{\"error\":{\"message\":\"test-api-key\",\"code\":\"no_balance\"}}"));
        Outcome httpError = new Outcome(); client.chat("chat", history("问题"), false, httpError); httpError.await();
        assertTrue(capture.get().snapshot().contains("HTTP 402")); assertTrue(capture.get().snapshot().contains("no_balance"));
        assertFalse(capture.get().snapshot().contains("test-api-key"));
    }

    @Test public void debugCancellationEndsOnceAndLateResponsesCannotAppend() throws Exception {
        java.util.concurrent.atomic.AtomicReference<DebugTrace> capture = captureTrace();
        server.enqueue(reply("迟到回复").setBodyDelay(1, TimeUnit.SECONDS));
        Outcome outcome = new Outcome(); client.chat("chat", history("问题"), false, outcome);
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        client.cancel("chat"); outcome.await(); String saved = capture.get().snapshot();
        assertEquals("cancelled", new JSONObject(saved).getString("status"));
        Thread.sleep(100); assertEquals(saved, capture.get().snapshot());
    }

    @Test public void failedDebugSinkCannotBreakChatAndDefaultFactoryDoesNothing() throws Exception {
        client.traceFactory = (chat, purpose) -> new DebugTrace(chat, purpose, () -> true, ignored -> { throw new IllegalStateException("disk full"); });
        server.enqueue(reply("完成")); Outcome outcome = new Outcome();
        client.chat("chat", history("问题"), false, outcome); outcome.await(); assertEquals("完成", outcome.content);
        client.traceFactory = (chat, purpose) -> null;
        server.enqueue(reply("继续完成")); Outcome disabled = new Outcome();
        client.chat("chat", history("问题"), false, disabled); disabled.await(); assertEquals("继续完成", disabled.content);
    }

    private java.util.concurrent.atomic.AtomicReference<DebugTrace> captureTrace() {
        java.util.concurrent.atomic.AtomicReference<DebugTrace> result = new java.util.concurrent.atomic.AtomicReference<>();
        client.traceFactory = (chat, purpose) -> {
            DebugTrace trace = new DebugTrace(chat, purpose, () -> true, ignored -> {}); result.set(trace); return trace;
        };
        return result;
    }

    private static class Outcome implements DeepSeekClient.CompletionListener {
        final List<String> stages = Collections.synchronizedList(new ArrayList<>());
        @Override public void onStageChanged(String stage) { stages.add(stage); }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger callbacks = new AtomicInteger();
        volatile String content;
        volatile String error;
        volatile boolean cancelled;

        @Override public void onFinished(String content, String reasoning, boolean truncated) {
            this.content = content;
            complete();
        }
        @Override public void onError(String error) {
            this.error = error;
            complete();
        }
        @Override public void onCancelled() {
            cancelled = true;
            complete();
        }
        private void complete() {
            callbacks.incrementAndGet();
            done.countDown();
        }
        void await() throws Exception {
            assertTrue("Callback did not complete; the UI would remain waiting", done.await(5, TimeUnit.SECONDS));
            assertEquals("A request must have exactly one terminal callback", 1, callbacks.get());
        }
    }
}
