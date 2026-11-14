package com.yawac.client.ui;

import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.widget.ImageView;
import android.widget.TextView;

import com.yawac.client.R;
import com.yawac.client.data.ApiConfigStore;
import com.yawac.client.net.ApiConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Drives the real setup page over the emulator's own Wi-Fi stack. */
public class PhoneSetupTest extends InstrumentationTestCase {
    private PhoneSetupActivity activity;
    private File configFile;
    private byte[] configBefore;

    @Override protected void setUp() throws Exception {
        super.setUp();
        configFile = new File(getInstrumentation().getTargetContext().getNoBackupFilesDir(), "api-config.enc");
        configBefore = configFile.exists() ? readFile(configFile) : null;
        if (configFile.exists()) assertTrue(configFile.delete());
        PhoneSetupActivity.addressForTest = (Inet4Address) InetAddress.getByName("127.0.0.1");
        activity = (PhoneSetupActivity) getInstrumentation().startActivitySync(
                new Intent(getInstrumentation().getTargetContext(), PhoneSetupActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
    }

    @Override protected void tearDown() throws Exception {
        PhoneSetupActivity.addressForTest = null;
        if (activity != null && !activity.isFinishing()) {
            getInstrumentation().runOnMainSync(activity::finish);
        }
        getInstrumentation().waitForIdleSync();
        if (configBefore == null) {
            if (configFile.exists()) configFile.delete();
        } else {
            try (FileOutputStream output = new FileOutputStream(configFile)) {
                output.write(configBefore);
            }
        }
        super.tearDown();
    }

    public void testPhonePageSavesConfigAndShowsQr() throws Exception {
        String url = waitForSetupUrl();
        assertNotNull("The emulator must expose a Wi-Fi IPv4 address for this test", url);
        assertTrue(url.startsWith("http://"));
        getInstrumentation().runOnMainSync(() -> assertNotNull(
                ((ImageView) activity.findViewById(R.id.setupQr)).getDrawable()));

        HttpURLConnection page = open(url);
        assertEquals(200, page.getResponseCode());
        String html = read(page);
        assertTrue(html.contains(ApiConfig.DEEPSEEK_URL));
        assertTrue(html.contains(ApiConfig.DEFAULT_MODEL));

        String token = queryParam(new URL(url).getQuery(), "t");
        String body = "t=" + encode(token)
                + "&url=" + encode(ApiConfig.DEEPSEEK_URL)
                + "&key=" + encode("sk-phone-test-key")
                + "&model=" + encode(ApiConfig.DEFAULT_MODEL);
        HttpURLConnection save = open(url.substring(0, url.indexOf("/setup")) + "/save");
        save.setRequestMethod("POST");
        save.setDoOutput(true);
        save.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream output = save.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, save.getResponseCode());
        assertTrue(read(save).contains("已保存到手表"));

        long deadline = System.currentTimeMillis() + 5000;
        String saved = null;
        while (System.currentTimeMillis() < deadline) {
            try { saved = new ApiConfigStore(getInstrumentation().getTargetContext()).load().apiKey; }
            catch (Exception ignored) { }
            if ("sk-phone-test-key".equals(saved)) break;
            Thread.sleep(100);
        }
        assertEquals("sk-phone-test-key", saved);
    }

    private String waitForSetupUrl() {
        TextView view = activity.findViewById(R.id.setupUrl);
        String[] value = new String[1];
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(() -> value[0] = view.getText().toString());
            if (value[0] != null && !value[0].isEmpty()) return value[0];
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
        }
        return null;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        return connection;
    }

    private static String read(HttpURLConnection connection) throws Exception {
        InputStream input = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            input.close();
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String queryParam(String query, String name) throws Exception {
        for (String pair : query.split("&")) {
            int equal = pair.indexOf('=');
            if (equal > 0 && name.equals(pair.substring(0, equal))) {
                return java.net.URLDecoder.decode(pair.substring(equal + 1), "UTF-8");
            }
        }
        return "";
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static byte[] readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
