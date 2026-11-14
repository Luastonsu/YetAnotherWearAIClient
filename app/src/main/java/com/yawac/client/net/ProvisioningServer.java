package com.yawac.client.net;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot local setup page for entering a provider URL, key and model.
 * Deliberately pure Java so the HTTP behavior can be unit tested on the JVM.
 * The Activity binds it to the watch's current Wi-Fi address and stops it on pause.
 */
public final class ProvisioningServer implements Closeable {
    public interface Saver {
        /** @return null on success, or a user-facing validation/storage error. */
        String save(String url, String key, String model);
    }

    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final String TOKEN_PARAM = "t";

    private final InetAddress bindAddress;
    private final Saver saver;
    private final String defaultUrl;
    private final String defaultModel;
    private final Runnable onSaved;
    private final String token;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean saved = new AtomicBoolean();

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService handlers;

    public ProvisioningServer(InetAddress bindAddress, Saver saver, String defaultUrl,
                              String defaultModel, Runnable onSaved) {
        if (bindAddress == null || saver == null) throw new IllegalArgumentException("address and saver required");
        this.bindAddress = bindAddress;
        this.saver = saver;
        this.defaultUrl = defaultUrl == null ? "" : defaultUrl;
        this.defaultModel = defaultModel == null ? "" : defaultModel;
        this.onSaved = onSaved;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bindAddress, 0));
        serverSocket = socket;
        handlers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "yawac-provision");
            thread.setDaemon(true);
            return thread;
        });
        Thread accept = new Thread(this::acceptLoop, "yawac-provision-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public int port() {
        ServerSocket socket = serverSocket;
        return socket == null || socket.isClosed() ? -1 : socket.getLocalPort();
    }

    public String token() { return token; }

    public String setupUrl(String host) {
        return "http://" + host + ":" + port() + "/setup?" + TOKEN_PARAM + "=" + token;
    }

    public boolean isSaved() { return saved.get(); }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        ExecutorService pool = handlers;
        if (pool != null) pool.shutdownNow();
    }

    @Override public void close() { stop(); }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException error) {
                if (!running.get()) return;
                continue;
            }
            ExecutorService pool = handlers;
            if (pool == null) {
                closeQuietly(socket);
                return;
            }
            try {
                pool.execute(() -> handle(socket));
            } catch (RejectedExecutionException ignored) {
                closeQuietly(socket);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(10_000);
            InputStream input = client.getInputStream();
            String headerBlock = readHeaders(input);
            if (headerBlock == null) return;
            String[] lines = headerBlock.split("\\r?\\n");
            if (lines.length == 0) return;
            String[] requestLine = lines[0].trim().split("\\s+");
            if (requestLine.length < 2) {
                respond(client, 400, "Bad Request", simplePage("请求格式错误"));
                return;
            }
            String method = requestLine[0];
            String target = requestLine[1];
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(),
                            lines[i].substring(colon + 1).trim());
                }
            }
            int queryAt = target.indexOf('?');
            String path = queryAt < 0 ? target : target.substring(0, queryAt);
            Map<String, String> query = parseForm(queryAt < 0 ? "" : target.substring(queryAt + 1));

            if (saved.get()) {
                respond(client, 410, "Gone", simplePage("配置已经保存，此页面已失效。"));
                return;
            }

            if ("GET".equals(method) && ("/setup".equals(path) || "/".equals(path))) {
                if (!validToken(query.get(TOKEN_PARAM))) {
                    respond(client, 403, "Forbidden", simplePage("链接无效或已过期。"));
                    return;
                }
                respond(client, 200, "OK", formPage(defaultUrl, defaultModel, null));
                return;
            }

            if ("POST".equals(method) && "/save".equals(path)) {
                int length;
                try {
                    length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                } catch (NumberFormatException error) {
                    respond(client, 400, "Bad Request", simplePage("请求格式错误。"));
                    return;
                }
                if (length <= 0 || length > MAX_BODY_BYTES) {
                    respond(client, 413, "Payload Too Large", simplePage("提交内容过大。"));
                    return;
                }
                byte[] body = readBody(input, length);
                if (body == null) {
                    respond(client, 400, "Bad Request", simplePage("提交内容不完整。"));
                    return;
                }
                Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
                if (!validToken(form.get(TOKEN_PARAM))) {
                    respond(client, 403, "Forbidden", simplePage("链接无效或已过期。"));
                    return;
                }
                String url = form.getOrDefault("url", "").trim();
                String key = form.getOrDefault("key", "").trim();
                String model = form.getOrDefault("model", "").trim();
                String error;
                try {
                    error = saver.save(url, key, model);
                } catch (RuntimeException failure) {
                    error = "保存失败，请重试";
                }
                if (error != null) {
                    respond(client, 200, "OK", formPage(url, model, error));
                    return;
                }
                if (!saved.compareAndSet(false, true)) {
                    respond(client, 410, "Gone", simplePage("配置已经保存，此页面已失效。"));
                    return;
                }
                respond(client, 200, "OK", successPage());
                if (onSaved != null) onSaved.run();
                return;
            }

            respond(client, 404, "Not Found", simplePage("页面不存在。"));
        } catch (SocketException ignored) {
            // Normal when the Activity closes the server while a request is in flight.
        } catch (IOException | RuntimeException error) {
            try {
                respond(socket, 500, "Internal Server Error", simplePage("服务出错，请返回手表重试。"));
            } catch (IOException ignored) { }
        }
    }

    private String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            buffer.write(value);
            if (buffer.size() > MAX_HEADER_BYTES) throw new IOException("Header too large");
            int size = buffer.size();
            if (size >= 4) {
                byte[] bytes = buffer.toByteArray();
                if (bytes[size - 4] == '\r' && bytes[size - 3] == '\n'
                        && bytes[size - 2] == '\r' && bytes[size - 1] == '\n') break;
            }
        }
        return buffer.size() == 0 ? null : new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private byte[] readBody(InputStream input, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) return null;
            offset += read;
        }
        return body;
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return values;
        for (String pair : body.split("&")) {
            int equal = pair.indexOf('=');
            String name = equal < 0 ? pair : pair.substring(0, equal);
            String value = equal < 0 ? "" : pair.substring(equal + 1);
            try {
                values.put(URLDecoder.decode(name, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (IOException | IllegalArgumentException ignored) { }
        }
        return values;
    }

    private boolean validToken(String provided) {
        return provided != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(Socket socket, int code, String reason, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(header.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private String formPage(String url, String model, String error) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>YAWAC 手表配置</title><style>")
                .append("body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:24px;max-width:560px}")
                .append("h1{font-size:22px;margin:0 0 8px}p{line-height:1.5;color:#bbb}")
                .append("label{display:block;margin:18px 0 6px;color:#ddd}input{box-sizing:border-box;width:100%;padding:12px;font-size:16px;border-radius:8px;border:1px solid #555;background:#1d1d1d;color:#fff}")
                .append("button{width:100%;margin-top:24px;padding:14px;font-size:17px;border:0;border-radius:8px;background:#3fd18a;color:#082014;font-weight:600}")
                .append(".error{color:#ff8f8f;background:#3a1b1b;padding:10px 12px;border-radius:8px}")
                .append(".note{font-size:13px;color:#888;margin-top:18px}</style></head><body>")
                .append("<h1>YAWAC 手表配置</h1>")
                .append("<p>此页面只对手表所在局域网开放；提交后立即失效。密钥只写入手表并加密保存。</p>");
        if (error != null && !error.isEmpty()) {
            html.append("<p class=\"error\">").append(escape(error)).append("</p>");
        }
        html.append("<form method=\"post\" action=\"/save\">")
                .append("<input type=\"hidden\" name=\"").append(TOKEN_PARAM).append("\" value=\"")
                .append(escape(token)).append("\">")
                .append("<label for=\"url\">API URL</label>")
                .append("<input id=\"url\" name=\"url\" type=\"url\" required autocapitalize=\"off\" spellcheck=\"false\" value=\"")
                .append(escape(url)).append("\">")
                .append("<label for=\"key\">API Key</label>")
                .append("<input id=\"key\" name=\"key\" type=\"password\" required autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">")
                .append("<label for=\"model\">模型 ID</label>")
                .append("<input id=\"model\" name=\"model\" type=\"text\" required autocapitalize=\"off\" spellcheck=\"false\" value=\"")
                .append(escape(model)).append("\">")
                .append("<button type=\"submit\">保存到手表</button></form>")
                .append("<p class=\"note\">DeepSeek 用户保持默认地址与模型即可，只需填写自己的 API Key。</p>")
                .append("</body></html>");
        return html.toString();
    }

    private String successPage() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>已保存</title><style>body{font-family:system-ui,sans-serif;background:#111;color:#eee;"
                + "margin:0;padding:32px;max-width:560px}h1{font-size:23px;color:#3fd18a}p{line-height:1.6;color:#bbb}"
                + "</style></head><body><h1>已保存到手表</h1>"
                + "<p>API 配置已写入手表并加密保存。可以关闭此页面，返回手表继续使用。</p>"
                + "</body></html>";
    }

    private String simplePage(String message) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>YAWAC</title>"
                + "<style>body{font-family:system-ui,sans-serif;background:#111;color:#eee;padding:32px;line-height:1.6}</style>"
                + "</head><body><p>" + escape(message) + "</p></body></html>";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
