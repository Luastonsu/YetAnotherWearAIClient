package com.yawac.client.net;

import com.yawac.client.data.Models.Message;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ReferencePlannerTest {
    private JSONObject envelope(String content) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", "stop")
                .put("message", new JSONObject().put("content", content))));
    }
    @Test public void planningCarriesConversationContextAndUsesBoundedNonThinkingJson() throws Exception {
        JSONObject body = ReferencePlanner.body(new ApiConfig(ApiConfig.DEEPSEEK_URL, "key", "deepseek-v4-pro"),
                Arrays.asList(new Message("user", "OPPO Watch X", 0), new Message("assistant", "讨论这款手表", 0), new Message("user", "它的官方参数呢？", 0)));
        assertEquals("deepseek-v4-pro", body.getString("model"));
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"));
        assertEquals(384, body.getInt("max_tokens"));
        String input = body.getJSONArray("messages").getJSONObject(1).getString("content");
        assertTrue(input.contains("OPPO Watch X"));
        assertTrue(input.contains("它的官方参数"));
    }
    @Test public void policyDistinguishesIndependentReasoningFromExternalFactsAndExplicitSourceRequests() throws Exception {
        JSONObject body = ReferencePlanner.body(ApiConfig.defaults(), Arrays.asList(new Message("user", "解题", 0)));
        String policy = body.getJSONArray("messages").getJSONObject(0).getString("content");
        assertTrue(policy.contains("search=false"));
        assertTrue(policy.contains("不要粘贴整道题"));
        assertTrue(policy.contains("除非用户明确要求寻找原题"));
        assertTrue(ReferencePlanner.ANSWER_POLICY.contains("独立分析"));
    }
    @Test public void parsesOptionalSearchAndCapsThePlannedQuery() throws Exception {
        assertFalse(ReferencePlanner.parse(envelope("{\"search\":false,\"query\":\"\"}")).search);
        String query = String.join("", java.util.Collections.nCopies(200, "原理"));
        ReferencePlanner.Plan plan = ReferencePlanner.parse(envelope(new JSONObject().put("search", true).put("query", query).toString()));
        assertTrue(plan.search);
        assertEquals(128, plan.query.codePointCount(0, plan.query.length()));
    }
    @Test(expected = JSONException.class) public void truncatedPlanIsNotExecuted() throws Exception {
        JSONObject response = envelope("{\"search\":true,\"query\":\"背景\"}");
        response.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length");
        ReferencePlanner.parse(response);
    }
}
