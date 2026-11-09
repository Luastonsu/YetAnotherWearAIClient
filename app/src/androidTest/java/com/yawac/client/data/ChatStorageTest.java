package com.yawac.client.data;

import android.test.InstrumentationTestCase;
import android.util.AtomicFile;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class ChatStorageTest extends InstrumentationTestCase {
    private File directory;
    private ChatStorage storage;
    private String snapshot;

    @Override protected void setUp() throws Exception {
        super.setUp();
        directory = new File(getInstrumentation().getTargetContext().getCacheDir(), "storage-test-" + System.nanoTime());
        assertTrue(directory.mkdirs());
        storage = new ChatStorage(directory);
        Models.Chat chat = new Models.Chat("saved", "历史记录");
        chat.draft = "我的草稿";
        chat.messages.add(new Models.Message("user", "问题", 0));
        snapshot = new JSONArray().put(chat.toJson()).toString();
        storage.save(snapshot);
    }

    @Override protected void tearDown() throws Exception {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
        super.tearDown();
    }

    public void testCorruptPrimaryRestoresMessagesAndDraftFromBackup() throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(directory, "chats.json"))) {
            out.write("[{broken".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("saved", storage.load().get(0).id);
        assertEquals("我的草稿", storage.load().get(0).draft);
        assertEquals("问题", storage.load().get(0).lastMessage().content);
    }

    public void testIncompleteAtomicWriteKeepsPreviousHistory() throws Exception {
        AtomicFile primary = new AtomicFile(new File(directory, "chats.json"));
        FileOutputStream unfinished = primary.startWrite();
        unfinished.write("[{unfinished".getBytes(StandardCharsets.UTF_8));
        unfinished.close(); // Simulate a killed process before finishWrite.
        assertEquals("saved", storage.load().get(0).id);
    }

    public void testInvalidChatDoesNotPublishPartialHistory() throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(directory, "chats.json"))) {
            out.write("[{\"id\":\"partial\"},{}]".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(1, storage.load().size());
        assertEquals("saved", storage.load().get(0).id);
    }

    public void testSuccessfulSaveUpdatesBothCopies() throws Exception {
        Models.Chat next = new Models.Chat("new", "新记录");
        storage.save(new JSONArray().put(next.toJson()).toString());
        assertEquals("new", storage.load().get(0).id);
        assertTrue(new File(directory, "chats.json").delete());
        assertEquals("new", storage.load().get(0).id);
    }
}
