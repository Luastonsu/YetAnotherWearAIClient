package com.yawac.client.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Models {

    public static class Chat {
        public static final String DEFAULT_TITLE = "新对话";

        public String id;
        public String title;
        public long createdAt;
        public long updatedAt;
        public String draft = "";
        public List<Message> messages = new ArrayList<>();

        public Chat() {
        }

        public Chat(String id, String title) {
            this.id = id;
            this.title = title;
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = this.createdAt;
        }

        public Message lastMessage() {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }

        /** Last real message, ignoring context-boundary notices used by the direct chat. */
        public Message lastConversationMessage() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (!Message.ROLE_NOTICE.equals(messages.get(i).role)) return messages.get(i);
            }
            return null;
        }

        public String preview() {
            Message m = lastConversationMessage();
            if (m == null) {
                return "";
            }
            if (m.streaming) {
                if (m.content == null || m.content.trim().isEmpty()) {
                    return "深度思考中…";
                }
                return "正在生成…";
            }
            String text = m.content == null ? "" : m.content;
            StringBuilder preview = new StringBuilder(32);
            boolean space = false;
            int count = 0;
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset); offset += Character.charCount(cp);
                if (cp == '|' || cp == '#' || cp == '*' || cp == '`' || Character.isWhitespace(cp)) {
                    if (count > 0) space = true;
                    continue;
                }
                if (space) {
                    if (count == 24) return preview.append('…').toString();
                    preview.append(' '); count++; space = false;
                }
                if (count == 24) return preview.append('…').toString();
                preview.appendCodePoint(cp); count++;
            }
            return preview.toString();
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            o.put("draft", draft);
            JSONArray arr = new JSONArray();
            for (Message m : messages) {
                arr.put(m.toJson());
            }
            o.put("messages", arr);
            return o;
        }

        public static Chat fromJson(JSONObject o) throws JSONException {
            Chat c = new Chat();
            c.id = o.getString("id");
            c.title = o.optString("title", DEFAULT_TITLE);
            c.createdAt = o.optLong("createdAt", 0L);
            c.updatedAt = o.optLong("updatedAt", 0L);
            c.draft = o.optString("draft", "");
            JSONArray arr = o.optJSONArray("messages");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Message m = Message.fromJson(arr.getJSONObject(i));
                    c.messages.add(m);
                }
            }
            return c;
        }
    }

    public static class Message {
        public static final String ROLE_NOTICE = "notice";
        public String id;
        public String role;
        public String content;
        public String reasoning;
        public long timestamp;
        public boolean streaming;
        public boolean failed;
        public boolean webSearch;
        public boolean deepThinking = true;
        public String reasoningEffort = com.yawac.client.net.ReasoningEffort.DEFAULT;
        public String requestUserId = "";
        public String stage = "";
        public long requestStartedAt;

        public Message() {
        }

        public Message(String role, String content, long timestamp) {
            this.id = UUID.randomUUID().toString();
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("role", role);
            o.put("content", content == null ? "" : content);
            o.put("reasoning", reasoning == null ? "" : reasoning);
            o.put("timestamp", timestamp);
            o.put("streaming", streaming);
            o.put("failed", failed);
            o.put("webSearch", webSearch);
            o.put("deepThinking", deepThinking);
            o.put("reasoningEffort", com.yawac.client.net.ReasoningEffort.normalize(reasoningEffort));
            o.put("requestUserId", requestUserId);
            o.put("stage", stage);
            o.put("requestStartedAt", requestStartedAt);
            return o;
        }

        public static Message fromJson(JSONObject o) throws JSONException {
            Message m = new Message();
            m.id = o.getString("id");
            m.role = o.optString("role", "user");
            m.content = o.optString("content", "");
            m.reasoning = o.optString("reasoning", "");
            m.timestamp = o.optLong("timestamp", 0L);
            m.streaming = o.optBoolean("streaming", false);
            m.failed = o.optBoolean("failed", "assistant".equals(m.role)
                    && m.content.startsWith("⚠️ "));
            m.webSearch = o.optBoolean("webSearch", false);
            m.deepThinking = o.optBoolean("deepThinking", true);
            m.reasoningEffort = com.yawac.client.net.ReasoningEffort.normalize(o.optString("reasoningEffort", "high"));
            m.requestUserId = o.optString("requestUserId", "");
            m.stage = o.optString("stage", "");
            m.requestStartedAt = o.optLong("requestStartedAt", m.timestamp);
            return m;
        }
    }
}
