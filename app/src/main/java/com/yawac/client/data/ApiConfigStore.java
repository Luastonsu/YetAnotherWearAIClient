package com.yawac.client.data;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import com.yawac.client.net.ApiConfig;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** API settings are encrypted with an installation-local Android Keystore key, outside backup. */
public final class ApiConfigStore {
    private static final String ALIAS = "yawac.api-config.v1";
    private final AtomicFile file;

    public ApiConfigStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "api-config.enc"));
    }

    public synchronized ApiConfig load() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) {
            return ApiConfig.initial();
        }
        JSONObject envelope = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        JSONObject value = new JSONObject(new String(cipher.doFinal(
                Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
        return new ApiConfig(value.getString("url"), value.getString("key"), value.getString("model"));
    }

    public synchronized void save(ApiConfig config) throws Exception {
        String error = config.validationError();
        if (error != null) throw new IllegalArgumentException(error);
        JSONObject value = new JSONObject().put("url", config.url).put("key", config.apiKey).put("model", config.model);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject().put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw e;
        }
    }

    public synchronized void clear() { file.delete(); }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
