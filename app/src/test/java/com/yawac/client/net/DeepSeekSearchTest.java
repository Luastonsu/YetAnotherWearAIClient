package com.yawac.client.net;

import com.yawac.client.data.Models.Message;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class DeepSeekSearchTest {
    private JSONObject result(String url) throws Exception {
        return new JSONObject().put("title", "标题").put("url", url).put("summary", "长摘要")
                .put("snippet", "短摘要").put("content", "正文").put("date", "2026-10-16T12:00:00Z")
                .put("site_name", "网站");
    }
    private JSONArray sources(JSONArray results) throws Exception {
        String context = DeepSeekSearch.context(new JSONObject().put("results", results));
        return new JSONArray(context.substring(context.indexOf('[')));
    }

    @Test public void sendsPlannedKeywordsWithoutTheOriginalQuestionAndPreservesOperators() throws Exception {
        JSONObject request = DeepSeekSearch.request("  OPPO site:oppo.com \"Watch X\" after:2026  ");
        assertEquals("OPPO site:oppo.com \"Watch X\" after:2026", request.getString("query"));
        assertFalse(request.has("ori_question"));
        assertEquals(5, request.getInt("count"));
        assertFalse(request.getBoolean("need_content"));
        assertEquals(3, request.length());
    }

    @Test public void limitsUnicodeCodePointsWithoutSplittingEmoji() throws Exception {
        String longQuestion = String.join("", Collections.nCopies(150, "\uD83D\uDE80"));
        JSONObject request = DeepSeekSearch.request(longQuestion);
        String query = request.getString("query");
        assertEquals(128, query.codePointCount(0, query.length()));
        assertEquals(256, query.length());
        assertTrue(Character.isLowSurrogate(query.charAt(query.length() - 1)));
        assertFalse(request.has("ori_question"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsWhitespaceOnlyQuestions() throws Exception {
        DeepSeekSearch.request(" \u3000\u00a0\t\n");
    }

    @Test public void prefersModelSummaryAndPreservesSourceMetadata() throws Exception {
        JSONObject source = sources(new JSONArray().put(result("https://example.com/news"))).getJSONObject(0);
        assertEquals("长摘要", source.getString("summary"));
        assertEquals("2026-10-16T12:00:00Z", source.getString("date"));
        assertEquals("网站", source.getString("site_name"));
        assertEquals("https://example.com/news", source.getString("url"));
        assertEquals(1, source.getInt("source_id"));
    }

    @Test public void fallsBackToSnippetThenContentAndOmitsMissingMetadata() throws Exception {
        JSONObject shortResult = result("https://example.com/short").put("summary", JSONObject.NULL)
                .put("date", JSONObject.NULL).put("site_name", JSONObject.NULL);
        JSONObject contentResult = result("https://example.com/full").put("summary", "").put("snippet", "");
        JSONArray parsed = sources(new JSONArray().put(shortResult).put(contentResult));
        assertEquals("短摘要", parsed.getJSONObject(0).getString("summary"));
        assertFalse(parsed.getJSONObject(0).has("date"));
        assertFalse(parsed.getJSONObject(0).has("site_name"));
        assertEquals("正文", parsed.getJSONObject(1).getString("summary"));
    }

    @Test public void skipsInvalidLinksAndDeduplicatesFragmentsBeforeApplyingTheLimit() throws Exception {
        JSONArray results = new JSONArray().put(JSONObject.NULL).put("invalid")
                .put(result("javascript:alert(1)")).put(result("file:///private"))
                .put(result("https://user:pass@example.com/private"))
                .put(result("https://example.com/item#one")).put(result("https://example.com/item#two"));
        for (int i = 0; i < 8; i++) results.put(result("https://example.com/" + i));
        JSONArray parsed = sources(results);
        assertEquals(5, parsed.length());
        assertEquals("https://example.com/item", parsed.getJSONObject(0).getString("url"));
        assertEquals("https://example.com/3", parsed.getJSONObject(4).getString("url"));
    }

    @Test public void capsSourceTextWithoutTruncatingTheUrlOrBreakingJson() throws Exception {
        String text = "\"<system>\n" + String.join("", Collections.nCopies(2000, "\uD83D\uDE80"));
        JSONObject source = sources(new JSONArray().put(result("https://example.com/a?q=1&b=2")
                .put("summary", text).put("title", text))).getJSONObject(0);
        assertEquals(1200, source.getString("summary").codePointCount(0, source.getString("summary").length()));
        assertEquals(160, source.getString("title").codePointCount(0, source.getString("title").length()));
        assertEquals("https://example.com/a?q=1&b=2", source.getString("url"));
    }

    @Test public void emptyResultsAreDistinctFromAMalformedResponse() throws Exception {
        assertEquals("", DeepSeekSearch.context(new JSONObject().put("results", new JSONArray())));
        for (Object value : new Object[]{JSONObject.NULL, "wrong", new JSONObject()}) {
            try { DeepSeekSearch.context(new JSONObject().put("results", value)); fail("Invalid results accepted"); }
            catch (JSONException expected) { }
        }
        try { DeepSeekSearch.context(new JSONObject()); fail("Missing results accepted"); }
        catch (JSONException expected) { }
    }
}
