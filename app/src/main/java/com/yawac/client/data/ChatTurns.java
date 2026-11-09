package com.yawac.client.data;

import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;
import java.util.ArrayList;
import java.util.List;

public final class ChatTurns {
    private ChatTurns() { }

    /** Retry the latest answer in place, retaining its original question and options. */
    public static boolean retry(Chat chat, Message answer) {
        if (chat.messages.isEmpty() || chat.lastMessage() != answer || !answer.failed) return false;
        int index = chat.messages.size() - 2;
        if (index < 0 || !"user".equals(chat.messages.get(index).role)) return false;
        Message user = chat.messages.get(index);
        if (!answer.requestUserId.isEmpty() && !answer.requestUserId.equals(user.id)) return false;
        answer.requestUserId = user.id;
        answer.content = "";
        answer.reasoning = "";
        answer.failed = false;
        answer.streaming = true;
        answer.requestStartedAt = System.currentTimeMillis();
        answer.stage = answer.webSearch ? "联网检索中" : (answer.deepThinking ? "思考并生成中" : "生成回答中");
        return true;
    }

    public static List<Message> history(Chat chat) {
        List<Message> result = new ArrayList<>();
        // The direct chat inserts a notice at each process start; only messages after
        // the newest notice belong to the current AI context.
        int start = 0;
        for (int i = chat.messages.size() - 1; i >= 0; i--) {
            if (Message.ROLE_NOTICE.equals(chat.messages.get(i).role)) {
                start = i + 1;
                break;
            }
        }
        for (int i = start; i < chat.messages.size(); i++) {
            Message message = chat.messages.get(i);
            if (!message.streaming && !message.failed && message.content != null
                    && !message.content.trim().isEmpty()
                    && ("user".equals(message.role) || "assistant".equals(message.role))) result.add(message);
        }
        return result;
    }
}
