package com.yawac.client.data;

import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Only accessed by the repository's serial IO executor after initial loading. */
final class ChatStorage {
    private final AtomicFile primary;
    private final AtomicFile backup;

    ChatStorage(File directory) {
        primary = new AtomicFile(new File(directory, "chats.json"));
        backup = new AtomicFile(new File(directory, "chats.backup.json"));
    }

    List<Models.Chat> load() {
        for (AtomicFile file : new AtomicFile[]{primary, backup}) {
            try {
                JSONArray array = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
                List<Models.Chat> loaded = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) loaded.add(Models.Chat.fromJson(array.getJSONObject(i)));
                // Publish only after the entire file has been validated.
                return loaded;
            } catch (IOException | JSONException ignored) { }
        }
        return new ArrayList<>();
    }

    void save(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        write(primary, bytes);
        write(backup, bytes);
    }

    void clear() throws IOException {
        primary.delete();
        backup.delete();
        for (AtomicFile file : new AtomicFile[]{primary, backup}) {
            String path = file.getBaseFile().getPath();
            for (String suffix : new String[]{"", ".bak", ".new"}) {
                if (new File(path + suffix).exists()) throw new IOException("Could not clear chat storage");
            }
        }
    }

    private void write(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }
}
