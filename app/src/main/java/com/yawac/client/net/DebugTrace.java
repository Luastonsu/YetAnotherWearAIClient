package com.yawac.client.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import okhttp3.HttpUrl;

/** Opt-in diagnostics. Never records HTTP authorization headers or sends diagnostics to a server. */
public final class DebugTrace {
    public interface Factory { DebugTrace begin(String chatId, String purpose); }
    public static final int MAX_DETAIL_CHARS = 512 * 1024;
    public final String id = UUID.randomUUID().toString();
    private final long startedAt = System.currentTimeMillis(), startedNanos = System.nanoTime();
    private final JSONObject document = new JSONObject();
    private final JSONArray events = new JSONArray();
    private final BooleanSupplier active;
    private final Consumer<DebugTrace> changed;
    private String secret = "";
    private int detailChars;
    private boolean ended;

    public DebugTrace(String chatId, String purpose, BooleanSupplier active, Consumer<DebugTrace> changed) {
        this.active = active;
        this.changed = changed;
        put(document, "schema", 1); put(document, "id", id); put(document, "chatId", chatId);
        put(document, "purpose", purpose); put(document, "startedAt", startedAt);
        put(document, "status", "running"); put(document, "events", events);
        put(document, "notice", "仅记录应用执行步骤及服务商实际返回的 reasoning_content；不是模型完整内部思考。请求不包含 Authorization。超出记录上限会明确标记。关闭模式或清除记录会停止当前采集。");
    }

    public synchronized void configure(ApiConfig config) {
        secret = config.apiKey == null ? "" : config.apiKey;
        event("config", "供应商配置", "model=" + config.model + "\nendpoint=" + safeUrl(config.completionUrl)
                + "\nsearchEndpoint=" + safeUrl(config.searchUrl));
    }

    public synchronized void describe(String question) {
        if (ended || !active.getAsBoolean()) return;
        put(document, "question", DeepSeekSearch.limit(redact(question == null ? "" : question), 80));
    }

    public synchronized void event(String kind, String label, String detail) {
        if (ended || !active.getAsBoolean()) return;
        String clean = redact(detail == null ? "" : detail);
        int available = Math.max(0, MAX_DETAIL_CHARS - detailChars);
        int kept = Math.min(clean.length(), available);
        if (kept > 0 && kept < clean.length() && Character.isHighSurrogate(clean.charAt(kept - 1))) kept--;
        JSONObject event = new JSONObject();
        put(event, "sequence", events.length() + 1); put(event, "atMs", elapsedMs());
        put(event, "kind", kind); put(event, "label", redact(label));
        put(event, "detail", clean.substring(0, kept));
        if (kept < clean.length()) {
            put(event, "omittedChars", clean.length() - kept);
            put(document, "truncated", true);
        }
        detailChars += kept;
        events.put(event);
        // A turn has a small fixed number of calls, so snapshots stay bounded even after truncation.
        changed.accept(this);
    }

    public synchronized void finish(String status, String detail) {
        if (ended) return;
        event("finish", status, detail);
        ended = true;
        put(document, "status", status); put(document, "elapsedMs", elapsedMs());
        if (active.getAsBoolean()) changed.accept(this);
    }

    public synchronized String snapshot() { return document.toString(); }
    private long elapsedMs() { return (System.nanoTime() - startedNanos) / 1_000_000L; }

    private String redact(String value) {
        String clean = secret.isEmpty() ? value : value.replace(secret, "[已隐藏]");
        if (!secret.isEmpty()) {
            String quoted = JSONObject.quote(secret);
            clean = clean.replace(quoted.substring(1, quoted.length() - 1), "[已隐藏]");
        }
        // Providers may echo credentials in errors or JSON. max_tokens / usage remain useful.
        return clean.replaceAll("(?i)(Bearer\\s+)[^\\s\"<>]+", "$1[已隐藏]")
                .replaceAll("(?i)(\"(?:api[_-]?key|authorization|access[_-]?token|secret)\"\\s*:\\s*\")[^\"]*(\")", "$1[已隐藏]$2");
    }

    public static String safeUrl(String raw) {
        HttpUrl parsed = raw == null ? null : HttpUrl.parse(raw);
        if (parsed == null) return "[无有效端点]";
        // No query values, embedded user info, or fragments belong in a diagnostic endpoint.
        return parsed.newBuilder().username("").password("").query(null).fragment(null).build().toString();
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
