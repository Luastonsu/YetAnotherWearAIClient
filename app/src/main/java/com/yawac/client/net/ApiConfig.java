package com.yawac.client.net;

import okhttp3.HttpUrl;

/** Immutable settings snapshot: a request never changes server or credentials midway. */
public final class ApiConfig {
    public static final String DEEPSEEK_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-flash";
    public static final String[] DEEPSEEK_MODELS = {"deepseek-flash", "deepseek-v4-pro"};

    public final String url;
    public final String apiKey;
    public final String model;
    public final String completionUrl;
    public final String searchUrl;
    public final boolean deepSeek;

    public ApiConfig(String url, String apiKey, String model) {
        this.url = trim(url);
        this.apiKey = trim(apiKey);
        this.model = trim(model);
        this.completionUrl = completionUrl(this.url);
        HttpUrl endpoint = HttpUrl.parse(completionUrl);
        deepSeek = endpoint != null && endpoint.isHttps() && endpoint.port() == 443
                && "api.deepseek.com".equals(endpoint.host())
                && ("/chat/completions".equals(endpoint.encodedPath())
                    || "/v1/chat/completions".equals(endpoint.encodedPath()));
        // Never send a custom provider's credentials to a second provider.
        searchUrl = deepSeek ? DeepSeekSearch.URL : null;
    }

    // Explicit endpoints keep the controlled HTTP tests independent of real providers.
    ApiConfig(String completionUrl, String searchUrl, String apiKey, String model) {
        this.url = completionUrl;
        this.completionUrl = completionUrl;
        this.searchUrl = searchUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.deepSeek = true;
    }

    public static ApiConfig defaults() { return new ApiConfig(DEEPSEEK_URL, "", DEFAULT_MODEL); }

    /** Fresh-install fallback: the build may embed a key, but a saved config still wins. */
    public static ApiConfig initial() {
        String embedded = com.yawac.client.BuildConfig.EMBEDDED_API_KEY;
        return embedded == null || embedded.isEmpty()
                ? defaults() : new ApiConfig(DEEPSEEK_URL, embedded, DEFAULT_MODEL);
    }

    public String validationError() {
        HttpUrl endpoint = HttpUrl.parse(url);
        if (endpoint == null || !endpoint.isHttps()) return "API URL 必须是有效的 HTTPS 地址";
        if (url.contains(" ") || hasControlCharacters(url) || !endpoint.username().isEmpty() || !endpoint.password().isEmpty()
                || endpoint.query() != null || endpoint.fragment() != null) {
            return "API URL 不能包含账号、密码、空格、查询参数或锚点";
        }
        if (model.isEmpty() || hasControlCharacters(model)) return "请填写有效的模型 ID";
        if (apiKey.isEmpty()) return "请填写自己的 API Key";
        for (int i = 0; i < apiKey.length(); i++) {
            if (apiKey.charAt(i) <= 32 || apiKey.charAt(i) >= 127) return "API Key 不能包含空白或非 ASCII 字符";
        }
        return null;
    }

    public static String completionUrl(String input) {
        String base = trim(input);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private static boolean hasControlCharacters(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return true;
        return false;
    }

    private static String trim(String text) { return text == null ? "" : text.trim(); }
}
