package com.yawac.client.net;

/** DeepSeek's distinct supported efforts; aliases must not become fictitious UI levels. */
public final class ReasoningEffort {
    public static final String DEFAULT = "high";
    private ReasoningEffort() { }
    public static String normalize(String value) {
        return "low".equals(value) || "max".equals(value) ? value : DEFAULT;
    }
    public static int level(boolean enabled, String effort) {
        if (!enabled) return 0;
        return "low".equals(effort) ? 1 : "max".equals(effort) ? 3 : 2;
    }
    public static String fromLevel(int level) { return level == 1 ? "low" : level == 3 ? "max" : DEFAULT; }
}
