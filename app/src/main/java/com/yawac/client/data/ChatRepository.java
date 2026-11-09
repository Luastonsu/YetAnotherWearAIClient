package com.yawac.client.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;

public class ChatRepository {

    public interface Listener {
        void onChatUpdated(String chatId);
        default void onProgressUpdated(String chatId, String messageId) { onChatUpdated(chatId); }
    }

    private static ChatRepository instance;

    private final ChatStorage storage;
    private final List<Chat> chats = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Read history while themes/native widgets are inflating; get() still publishes it atomically. */
    public static void preload(Context context) {
        Context app = context.getApplicationContext();
        Thread loader = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            get(app);
        }, "yawac-history-loader");
        loader.start();
    }

    public static synchronized ChatRepository get(Context context) {
        if (instance == null) {
            instance = new ChatRepository(context.getApplicationContext());
        }
        return instance;
    }

    private ChatRepository(Context context) {
        storage = new ChatStorage(context.getFilesDir());
        load();
    }

    private void load() {
        chats.addAll(storage.load());
        cleanupIncomplete();
        sortChats();
    }

    private void cleanupIncomplete() {
        for (Chat c : chats) {
            for (Message m : c.messages) {
                if ("assistant".equals(m.role)) {
                    if (m.streaming || m.content == null || m.content.trim().isEmpty()) {
                        m.content = "⚠️ 上次请求已中断，可点击重试\n错误码：REQUEST_INTERRUPTED";
                        m.failed = true;
                    }
                    m.streaming = false;
                }
            }
        }
    }

    private void sortChats() {
        Collections.sort(chats, new Comparator<Chat>() {
            @Override
            public int compare(Chat a, Chat b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
    }

    public synchronized List<Chat> getChats() {
        sortChats();
        return new ArrayList<>(chats);
    }

    public synchronized Chat getChat(String id) {
        for (Chat c : chats) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    public synchronized void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized Chat createChat() {
        Chat c = new Chat(UUID.randomUUID().toString(), Chat.DEFAULT_TITLE);
        chats.add(c);
        sortChats();
        persist();
        return c;
    }

    /** Return the chat with this stable id, creating it once if necessary. */
    public synchronized Chat ensureChat(String id, String title) {
        Chat c = getChat(id);
        if (c == null) {
            c = new Chat(id, title);
            chats.add(c);
            sortChats();
            persist();
        }
        return c;
    }

    public synchronized void deleteChat(String id) {
        for (int i = chats.size() - 1; i >= 0; i--) {
            if (chats.get(i).id.equals(id)) {
                chats.remove(i);
            }
        }
        persist();
    }

    public interface ClearListener { void onCleared(boolean success); }

    /** Queue deletion after earlier saves, so an old snapshot cannot restore deleted history. */
    public synchronized void clearAll(ClearListener listener) {
        List<Chat> deleted = new ArrayList<>(chats);
        for (Chat chat : deleted) {
            com.yawac.client.net.DeepSeekClient.get().cancel(chat.id);
            com.yawac.client.net.DeepSeekClient.get().cancel("title:" + chat.id);
        }
        chats.clear();
        for (Chat chat : deleted) notifyChatUpdated(chat.id);
        io.execute(() -> {
            boolean success;
            try { storage.clear(); success = true; }
            catch (IOException e) { success = false; }
            final boolean result = success;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> listener.onCleared(result));
        });
    }

    public synchronized void addMessage(String chatId, Message m) {
        Chat c = getChat(chatId);
        if (c == null) return;
        c.messages.add(m);
        c.updatedAt = System.currentTimeMillis();
        sortChats();
        persist();
    }

    public synchronized void updateDraft(String chatId, String draft) {
        Chat c = getChat(chatId);
        if (c == null || draft.equals(c.draft)) return;
        c.draft = draft;
        persist();
    }

    public synchronized void updateProgress(String chatId, String messageId, String stage) {
        Chat c = getChat(chatId);
        if (c == null) return;
        for (Message m : c.messages) {
            if (m.id.equals(messageId) && m.streaming) {
                m.stage = stage;
                persist();
                for (Listener listener : new ArrayList<>(listeners)) {
                    listener.onProgressUpdated(chatId, messageId);
                }
                return;
            }
        }
    }

    public synchronized void updateTitle(String chatId, String title) {
        Chat c = getChat(chatId);
        if (c == null) return;
        c.title = title;
        persist();
    }

    public synchronized void updateMessage(String chatId, String messageId,
                                           String content, String reasoning) {
        updateMessage(chatId, messageId, content, reasoning, false);
    }

    public synchronized void updateMessage(String chatId, String messageId,
                                           String content, String reasoning, boolean failed) {
        Chat c = getChat(chatId);
        if (c == null) return;
        boolean updated = false;
        for (Message m : c.messages) {
            if (m.id.equals(messageId)) {
                m.content = content;
                m.reasoning = reasoning;
                m.streaming = false;
                m.failed = failed;
                updated = true;
                break;
            }
        }
        if (!updated) return;
        c.updatedAt = System.currentTimeMillis();
        sortChats();
        persist();
        notifyChatUpdated(chatId);
    }

    public synchronized void removeMessage(String chatId, String messageId) {
        Chat c = getChat(chatId);
        if (c == null) return;
        boolean removed = false;
        for (int i = c.messages.size() - 1; i >= 0; i--) {
            if (c.messages.get(i).id.equals(messageId)) {
                c.messages.remove(i);
                removed = true;
                break;
            }
        }
        if (!removed) return;
        c.updatedAt = System.currentTimeMillis();
        sortChats();
        persist();
        notifyChatUpdated(chatId);
    }

    public synchronized void persist() {
        final String json = toJson();
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    storage.save(json);
                } catch (IOException ignored) {
                }
            }
        });
    }

    private String toJson() {
        JSONArray arr = new JSONArray();
        for (Chat c : chats) {
            try {
                arr.put(c.toJson());
            } catch (JSONException ignored) {
            }
        }
        return arr.toString();
    }

    private void notifyChatUpdated(String chatId) {
        List<Listener> snapshot = new ArrayList<>(listeners);
        for (Listener listener : snapshot) {
            listener.onChatUpdated(chatId);
        }
    }
}
