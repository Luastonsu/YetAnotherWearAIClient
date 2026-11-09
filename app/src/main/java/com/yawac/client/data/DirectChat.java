package com.yawac.client.data;

import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;

/** The chat opened at launch; its model context restarts once per app process. */
public final class DirectChat {
    public static final String ID = "yawac-direct-chat";
    public static final String TITLE = "直聊";
    private static boolean resetDone;

    private DirectChat() { }

    public static Chat ensure(ChatRepository repo) {
        return repo.ensureChat(ID, TITLE);
    }

    /** Add one persisted boundary notice per process. Empty history and an already
     *  trailing notice need no new entry. */
    public static void resetForProcess(ChatRepository repo, Chat chat, String noticeText) {
        if (resetDone) return;
        resetDone = true;
        if (chat == null || chat.messages.isEmpty()) return;
        Message last = chat.messages.get(chat.messages.size() - 1);
        if (Message.ROLE_NOTICE.equals(last.role)) return;
        repo.addMessage(chat.id, new Message(Message.ROLE_NOTICE, noticeText, System.currentTimeMillis()));
    }
}
