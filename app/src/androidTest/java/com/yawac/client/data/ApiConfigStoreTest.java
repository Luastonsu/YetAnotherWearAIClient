package com.yawac.client.data;

import android.content.ContextWrapper;
import android.test.InstrumentationTestCase;

import com.yawac.client.net.ApiConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Uses an isolated test directory and never replaces the user's API settings. */
public class ApiConfigStoreTest extends InstrumentationTestCase {
    private File directory;
    private ContextWrapper context;
    private ApiConfigStore store;

    @Override protected void setUp() throws Exception {
        super.setUp();
        directory = new File(getInstrumentation().getTargetContext().getNoBackupFilesDir(),
                "config-test-" + java.util.UUID.randomUUID());
        assertTrue(directory.mkdirs());
        context = new ContextWrapper(getInstrumentation().getTargetContext()) {
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        store = new ApiConfigStore(context);
    }

    @Override protected void tearDown() throws Exception {
        store.clear();
        directory.delete();
        super.tearDown();
    }

    public void testEncryptedSettingsSurviveReopeningStore() throws Exception {
        store.save(new ApiConfig("https://example.com/v1", "private-test-key-123", "chosen-model"));
        String raw = new String(Files.readAllBytes(new File(directory, "api-config.enc").toPath()), StandardCharsets.UTF_8);
        assertFalse(raw.contains("private-test-key-123"));
        assertFalse(raw.contains("chosen-model"));
        ApiConfig restored = new ApiConfigStore(context).load();
        assertEquals("https://example.com/v1", restored.url);
        assertEquals("private-test-key-123", restored.apiKey);
        assertEquals("chosen-model", restored.model);
    }

    public void testClearRequiresUserToConfigureAgain() throws Exception {
        store.save(new ApiConfig(ApiConfig.DEEPSEEK_URL, "key", ApiConfig.DEFAULT_MODEL));
        store.clear();
        String embedded = com.yawac.client.BuildConfig.EMBEDDED_API_KEY;
        if (embedded == null || embedded.isEmpty()) {
            assertEquals("", store.load().apiKey);
            assertNotNull(store.load().validationError());
        } else {
            assertEquals(embedded, store.load().apiKey);
            assertNull(store.load().validationError());
        }
    }

    public void testCorruptionDoesNotSilentlyUseADefaultService() throws Exception {
        store.save(new ApiConfig(ApiConfig.DEEPSEEK_URL, "key", ApiConfig.DEFAULT_MODEL));
        Files.write(new File(directory, "api-config.enc").toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        try { store.load(); fail("Corruption must be visible to the caller"); }
        catch (org.json.JSONException expected) { }
    }

    public void testInvalidSaveKeepsTheExistingConfig() throws Exception {
        store.save(new ApiConfig(ApiConfig.DEEPSEEK_URL, "valid-key", ApiConfig.DEFAULT_MODEL));
        try { store.save(ApiConfig.defaults()); fail("An empty key must not replace a valid config"); }
        catch (IllegalArgumentException expected) { }
        assertEquals("valid-key", store.load().apiKey);
    }
}
