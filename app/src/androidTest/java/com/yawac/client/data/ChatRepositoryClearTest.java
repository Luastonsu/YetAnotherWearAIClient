package com.yawac.client.data;

import android.content.ContextWrapper;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Private repository and directory: never clears the installed user's chats. */
public class ChatRepositoryClearTest extends InstrumentationTestCase {
    public void testQueuedSavesAndBackupsCannotResurrectClearedChats() throws Exception {
        File directory = new File(getInstrumentation().getTargetContext().getCacheDir(), "repo-test-" + java.util.UUID.randomUUID());
        assertTrue(directory.mkdir());
        ContextWrapper context = new ContextWrapper(getInstrumentation().getTargetContext()) {
            @Override public File getFilesDir() { return directory; }
        };
        Constructor<ChatRepository> constructor = ChatRepository.class.getDeclaredConstructor(android.content.Context.class);
        constructor.setAccessible(true);
        ChatRepository repo = constructor.newInstance(context);
        Field field = ChatRepository.class.getDeclaredField("io");
        field.setAccessible(true);
        ExecutorService io = (ExecutorService) field.get(repo);
        try {
            Models.Chat removed = repo.createChat();
            repo.updateDraft(removed.id, "private draft");
            repo.addMessage(removed.id, new Models.Message("user", "private message", 0));
            CountDownLatch done = new CountDownLatch(1);
            boolean[] success = new boolean[1];
            repo.clearAll(ok -> { success[0] = ok; done.countDown(); });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(success[0]);
            assertTrue(repo.getChats().isEmpty());
            assertTrue(new ChatStorage(directory).load().isEmpty());
            assertFalse(new File(directory, "chats.backup.json").exists());
            Models.Chat kept = repo.createChat();
            io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            java.util.List<Models.Chat> loaded = new ChatStorage(directory).load();
            assertEquals(1, loaded.size());
            assertEquals(kept.id, loaded.get(0).id);
        } finally {
            io.shutdown();
            io.awaitTermination(10, TimeUnit.SECONDS);
            new ChatStorage(directory).clear();
            directory.delete();
        }
    }
}
