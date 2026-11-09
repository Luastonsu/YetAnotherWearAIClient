package com.yawac.client.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CacheFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void clearsOnlyCacheAndKeepsCredentialsHistoryAndRoot() throws Exception {
        File cache = temporary.newFolder("cache"), nested = new File(cache, "nested");
        assertTrue(nested.mkdir());
        Files.write(new File(nested, "temp").toPath(), new byte[1234]);
        File history = temporary.newFile("chats.json"), key = temporary.newFile("api-config.enc");
        assertEquals(1234, CacheFiles.size(cache));
        CacheFiles.clear(cache);
        assertTrue(cache.isDirectory());
        assertEquals(0, CacheFiles.size(cache));
        assertTrue(history.exists());
        assertTrue(key.exists());
    }
    @Test public void missingCacheIsSafeAndZeroBytes() throws Exception {
        File absent = new File(temporary.getRoot(), "absent");
        CacheFiles.clear(absent);
        assertEquals(0, CacheFiles.size(absent));
        assertFalse(absent.exists());
    }
}
