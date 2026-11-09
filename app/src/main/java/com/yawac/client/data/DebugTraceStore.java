package com.yawac.client.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import com.yawac.client.net.DebugTrace;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Separate, bounded no-backup storage. File work never runs on the main thread. */
public final class DebugTraceStore implements DebugTrace.Factory {
    public static final String ENABLED = "debug_trace_enabled";
    public static final int MAX_TRACES = 20;
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    private static DebugTraceStore instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "yawac-debug-io"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean enabled;
    private volatile long generation;
    private volatile String storageError = "";
    // Android filesystems can report modification times with only second precision.
    // Keep a strict order on this serial worker; hydrate older files from their event timestamps.
    private final Map<String, Long> order = new HashMap<>();
    private long lastOrder;

    public static synchronized DebugTraceStore get(Context context) {
        if (instance == null) instance = new DebugTraceStore(context.getApplicationContext());
        return instance;
    }

    DebugTraceStore(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("wearai_prefs", 0);
        enabled = prefs.getBoolean(ENABLED, false);
        directory = new File(context.getNoBackupFilesDir(), "debug-traces");
    }

    public boolean enabled() { return enabled; }
    public String storageError() { return storageError; }
    public synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        generation++; // Invalidates running captures, including when the switch is turned back on.
        enabled = value;
        prefs.edit().putBoolean(ENABLED, value).apply();
    }

    @Override public synchronized DebugTrace begin(String chatId, String purpose) {
        if (!enabled) return null;
        long epoch = generation;
        java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean();
        return new DebugTrace(chatId, purpose, () -> enabled && generation == epoch, trace -> {
            if (!scheduled.compareAndSet(false, true)) return;
            worker.execute(() -> {
                    scheduled.set(false);
                    if (!enabled || generation != epoch) return;
                    AtomicFile file = new AtomicFile(file(trace.id));
                    FileOutputStream output = null;
                    try {
                        if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException();
                        output = file.startWrite();
                        output.write(trace.snapshot().getBytes(StandardCharsets.UTF_8));
                        file.finishWrite(output); output = null;
                        lastOrder = Math.max(System.currentTimeMillis(), lastOrder + 1);
                        order.put(file(trace.id).getName(), lastOrder);
                        prune(); storageError = "";
                    } catch (Exception e) {
                        if (output != null) file.failWrite(output);
                        storageError = "调试记录保存失败，请检查存储空间";
                    }
                });
        });
    }

    public void list(Consumer<List<JSONObject>> callback) {
        worker.execute(() -> {
            List<JSONObject> result = new ArrayList<>();
            for (File file : files()) {
                JSONObject trace = readFile(file);
                if (trace != null) {
                    // Do not retain all response bodies while showing the session list.
                    trace.remove("events"); result.add(trace);
                }
            }
            main.post(() -> callback.accept(result));
        });
    }

    public void read(String id, Consumer<JSONObject> callback) {
        worker.execute(() -> {
            JSONObject trace = validId(id) ? readFile(file(id)) : null;
            main.post(() -> callback.accept(trace));
        });
    }

    public synchronized void clear(Consumer<Boolean> callback) {
        generation++; // Queued and in-flight traces can never recreate records after this clear.
        worker.execute(() -> {
            boolean success = true;
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) success &= file.isFile() && file.delete();
            order.clear(); lastOrder = 0;
            storageError = success ? "" : "部分调试记录未能删除";
            boolean cleared = success;
            main.post(() -> callback.accept(cleared));
        });
    }

    public void export(String id, Uri destination, Consumer<Boolean> callback) {
        worker.execute(() -> {
            boolean success = false;
            try {
                JSONObject trace = validId(id) ? readFile(file(id)) : null;
                if (trace != null) try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                    if (output != null) { output.write(trace.toString(2).getBytes(StandardCharsets.UTF_8)); success = true; }
                }
            } catch (Exception ignored) { }
            boolean exported = success;
            main.post(() -> callback.accept(exported));
        });
    }

    private File file(String id) { return new File(directory, id + ".json"); }
    private static boolean validId(String id) { return id != null && id.matches("[a-f0-9-]{36}"); }
    private static JSONObject readFile(File file) {
        try (java.io.FileInputStream input = new AtomicFile(file).openRead()) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception e) { return null; }
    }
    private File[] files() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return new File[0];
        for (File file : files) if (!order.containsKey(file.getName())) {
            JSONObject doc = readFile(file);
            long timestamp = file.lastModified();
            if (doc != null) {
                long elapsed = doc.optLong("elapsedMs");
                org.json.JSONArray events = doc.optJSONArray("events");
                JSONObject last = events == null ? null : events.optJSONObject(events.length() - 1);
                if (last != null) elapsed = last.optLong("atMs");
                timestamp = doc.optLong("startedAt", timestamp) + elapsed;
            }
            order.put(file.getName(), timestamp); lastOrder = Math.max(lastOrder, timestamp);
        }
        Arrays.sort(files, Comparator.<File>comparingLong(file -> order.get(file.getName())).reversed());
        return files;
    }
    private void prune() throws java.io.IOException {
        File[] files = files(); long total = 0;
        for (int i = 0; i < files.length; i++) {
            total += files[i].length();
            if (i >= MAX_TRACES || total > MAX_BYTES) {
                if (!files[i].delete()) throw new java.io.IOException("Cannot prune trace");
                order.remove(files[i].getName());
            }
        }
    }
}
