package com.yawac.client.data;

import org.junit.Test;
import org.json.JSONObject;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;

import java.util.List;

import static org.junit.Assert.*;

public class ChatTurnsTest {
    @Test public void retryReusesQuestionAndPlaceholderWithOriginalOptions() {
        Chat chat = new Chat("id", "test");
        Message user = new Message("user", "天气", 0);
        Message answer = new Message("assistant", "错误", 0);
        answer.failed = true;
        answer.webSearch = true;
        answer.deepThinking = false;
        answer.reasoningEffort = "max";
        chat.messages.add(user);
        chat.messages.add(answer);
        String id = answer.id;
        assertTrue(ChatTurns.retry(chat, answer));
        assertEquals(2, chat.messages.size());
        assertEquals(id, answer.id);
        assertEquals(user.id, answer.requestUserId);
        assertTrue(answer.webSearch);
        assertFalse(answer.deepThinking);
        assertEquals("max", answer.reasoningEffort);
        assertTrue(answer.streaming);
        assertFalse(answer.failed);
        assertEquals(1, ChatTurns.history(chat).size());
        assertSame(user, ChatTurns.history(chat).get(0));
        assertFalse(ChatTurns.retry(chat, answer));
    }

    @Test public void cannotRetryAnEarlierTurnOrWrongQuestion() {
        Chat chat = new Chat("id", "test");
        Message user = new Message("user", "问题", 0);
        Message failed = new Message("assistant", "错误", 0);
        failed.failed = true;
        chat.messages.add(user);
        chat.messages.add(failed);
        failed.requestUserId = "other";
        assertFalse(ChatTurns.retry(chat, failed));
        failed.requestUserId = user.id;
        chat.messages.add(new Message("user", "后来提问", 0));
        assertFalse(ChatTurns.retry(chat, failed));
    }

    @Test public void historyOmitsFailuresAndKeepsCompletedEarlierTurns() {
        Chat chat = new Chat("id", "test");
        chat.messages.add(new Message("user", "第一轮", 0));
        chat.messages.add(new Message("assistant", "回答", 0));
        chat.messages.add(new Message("user", "第二轮", 0));
        Message error = new Message("assistant", "错误", 0);
        error.failed = true;
        chat.messages.add(error);
        assertEquals(3, ChatTurns.history(chat).size());
    }

    @Test public void historyStartsAfterTheNewestContextNotice() {
        Chat chat = new Chat("id", "test");
        chat.messages.add(new Message("user", "旧问题", 0));
        chat.messages.add(new Message("assistant", "旧回答", 0));
        chat.messages.add(new Message(Message.ROLE_NOTICE, "重置", 0));
        Message question = new Message("user", "新问题", 0);
        Message answer = new Message("assistant", "新回答", 0);
        chat.messages.add(question);
        chat.messages.add(answer);
        List<Message> history = ChatTurns.history(chat);
        assertEquals(2, history.size());
        assertSame(question, history.get(0));
        assertSame(answer, history.get(1));
        chat.messages.add(new Message(Message.ROLE_NOTICE, "再次重置", 0));
        assertTrue(ChatTurns.history(chat).isEmpty());
    }

    @Test public void draftAndRetryOptionsSurvivePersistenceAndLegacyChatsLoad() throws Exception {
        Chat chat = new Chat("id", "test");
        chat.draft = "未发送的内容";
        Message answer = new Message("assistant", "失败", 0);
        answer.failed = true;
        answer.webSearch = true;
        answer.deepThinking = false;
        answer.requestUserId = "question-id";
        answer.reasoningEffort = "low";
        chat.messages.add(answer);
        Chat restored = Chat.fromJson(chat.toJson());
        assertEquals(chat.draft, restored.draft);
        assertTrue(restored.lastMessage().webSearch);
        assertFalse(restored.lastMessage().deepThinking);
        assertEquals("question-id", restored.lastMessage().requestUserId);
        assertEquals("low", restored.lastMessage().reasoningEffort);
        JSONObject old = new JSONObject("{\"id\":\"legacy\",\"messages\":[{\"id\":\"answer\",\"role\":\"assistant\",\"content\":\"回答\"}]}");
        assertEquals("", Chat.fromJson(old).draft);
        assertTrue(Chat.fromJson(old).lastMessage().deepThinking);
        assertEquals("high", Chat.fromJson(old).lastMessage().reasoningEffort);
        old.getJSONArray("messages").getJSONObject(0).put("reasoningEffort", "not-supported");
        assertEquals("high", Chat.fromJson(old).lastMessage().reasoningEffort);
    }
}
