package com.yawac.client.net;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ProvisioningServerTest {
    private ProvisioningServer server;
    private final AtomicReference<String> savedUrl = new AtomicReference<>();
    private final AtomicReference<String> savedKey = new AtomicReference<>();
    private final AtomicReference<String> savedModel = new AtomicReference<>();
    private final AtomicReference<String> saveError = new AtomicReference<>();
    private final CountDownLatch savedSignal = new CountDownLatch(1);

    @Before public void setUp() throws Exception {
        server = new ProvisioningServer(InetAddress.getByName("127.0.0.1"),
                (url, key, model) -> {
                    savedUrl.set(url);
                    savedKey.set(key);
                    savedModel.set(model);
                    return saveError.get();
                }, ApiConfig.DEEPSEEK_URL, ApiConfig.DEFAULT_MODEL, savedSignal::countDown);
        server.start();
    }

    @After public void tearDown() { server.stop(); }

    @Test public void setupPageRequiresTokenAndShowsDefaults() throws Exception {
        HttpURLConnection page = request("/setup?t=" + server.token(), "GET", null);
        assertEquals(200, page.getResponseCode());
        String html = read(page);
        assertTrue(html.contains(ApiConfig.DEEPSEEK_URL));
        assertTrue(html.contains(ApiConfig.DEFAULT_MODEL));
        assertTrue(html.contains("name=\"key\""));
        assertFalse(html.contains("sk-"));

        HttpURLConnection denied = request("/setup?t=wrong", "GET", null);
        assertEquals(403, denied.getResponseCode());
        assertFalse(read(denied).contains(server.token()));
    }

    @Test public void validSubmissionSavesOnceAndAValidTokenIsSingleUse() throws Exception {
        String body = form(server.token(), "https://api.deepseek.com", "sk-test-key", "deepseek-flash");
        HttpURLConnection saved = request("/save", "POST", body);
        assertEquals(200, saved.getResponseCode());
        assertTrue(read(saved).contains("已保存到手表"));
        assertTrue(savedSignal.await(2, TimeUnit.SECONDS));
        assertEquals("https://api.deepseek.com", savedUrl.get());
        assertEquals("sk-test-key", savedKey.get());
        assertEquals("deepseek-flash", savedModel.get());

        HttpURLConnection again = request("/save", "POST", body);
        assertEquals(410, again.getResponseCode());
        assertFalse(read(again).contains("sk-test-key"));
    }

    @Test public void validationErrorIsShownWithoutEchoingTheKeyAndServerStaysOpen() throws Exception {
        saveError.set("请填写自己的 API Key");
        HttpURLConnection response = request("/save", "POST",
                form(server.token(), "https://api.deepseek.com", "sk-secret-key", "deepseek-flash"));
        assertEquals(200, response.getResponseCode());
        String html = read(response);
        assertTrue(html.contains("请填写自己的 API Key"));
        assertFalse(html.contains("sk-secret-key"));
        assertEquals(1, savedSignal.getCount());
        assertFalse(server.isSaved());

        HttpURLConnection page = request("/setup?t=" + server.token(), "GET", null);
        assertEquals(200, page.getResponseCode());
    }

    @Test public void nonPostMethodAndUnknownPathAreRejected() throws Exception {
        HttpURLConnection get = request("/save?t=" + server.token(), "GET", null);
        assertEquals(404, get.getResponseCode());
        HttpURLConnection unknown = request("/nope", "GET", null);
        assertEquals(404, unknown.getResponseCode());
    }

    private HttpURLConnection request(String path, String method, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + server.port() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return connection;
    }

    private static String form(String token, String url, String key, String model) throws Exception {
        return "t=" + encode(token) + "&url=" + encode(url) + "&key=" + encode(key) + "&model=" + encode(model);
    }

    private static String read(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            input.close();
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }
}
