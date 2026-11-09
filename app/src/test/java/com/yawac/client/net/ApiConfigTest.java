package com.yawac.client.net;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiConfigTest {
    @Test public void acceptsBaseVersionedAndFullEndpointsWithoutDuplicatingPath() {
        assertEquals("https://example.com/chat/completions", ApiConfig.completionUrl(" https://example.com/ "));
        assertEquals("https://example.com/v1/chat/completions", ApiConfig.completionUrl("https://example.com/v1/"));
        assertEquals("https://example.com/proxy/v1/chat/completions",
                ApiConfig.completionUrl("https://example.com/proxy/v1/chat/completions/"));
    }

    @Test public void rejectsUnsafeUrlsAndHeaderInjection() {
        for (String url : new String[]{"http://example.com", "invalid", "https://user:pass@example.com",
                "https://example.com?key=secret", "https://example.com#secret", "https://example.com/a b"}) {
            assertNotNull(url, new ApiConfig(url, "key", "model").validationError());
        }
        assertNotNull(new ApiConfig("https://example.com", "key\r\nInjected: yes", "model").validationError());
        assertNotNull(new ApiConfig("https://example.com", "", "model").validationError());
        assertNotNull(new ApiConfig("https://example.com", "key", " ").validationError());
        assertNull(new ApiConfig("https://example.com/proxy/v1", "my-key", "provider/model").validationError());
    }

    @Test public void deepSeekExtensionsRequireTheExactOfficialEndpoint() {
        assertTrue(new ApiConfig(ApiConfig.DEEPSEEK_URL, "key", "model").deepSeek);
        assertTrue(new ApiConfig("https://api.deepseek.com/v1", "key", "model").deepSeek);
        assertEquals("https://api.deepseek.com/v1/tools/search",
                new ApiConfig(ApiConfig.DEEPSEEK_URL, "key", "model").searchUrl);
        assertEquals("https://api.deepseek.com/v1/tools/search",
                new ApiConfig("https://api.deepseek.com/v1/chat/completions", "key", "model").searchUrl);
        for (String url : new String[]{"https://api.deepseek.com.evil.example/v1",
                "https://example.com/v1", "https://api.deepseek.com/proxy", "https://api.deepseek.com:8443/v1"}) {
            ApiConfig config = new ApiConfig(url, "key", "model");
            assertFalse(config.deepSeek);
            assertNull(config.searchUrl);
        }
    }

    @Test public void presetNeverSuppliesADeveloperKey() {
        assertEquals("", ApiConfig.defaults().apiKey);
        assertEquals("deepseek-flash", ApiConfig.defaults().model);
        assertArrayEquals(new String[]{"deepseek-flash", "deepseek-v4-pro"}, ApiConfig.DEEPSEEK_MODELS);
    }
}
