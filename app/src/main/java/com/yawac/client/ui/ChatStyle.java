package com.yawac.client.ui;

/** Small, readable palette for watch screens; independent of reading-page preferences. */
public final class ChatStyle {
    public static final int DEFAULT_USER = 0xFF33B054, DEFAULT_AI = 0xFFCCCCCC;
    public static final int[] COLORS = {DEFAULT_USER, DEFAULT_AI, 0xFFF4F4F4, 0xFFB8DDF5, 0xFFD8C4EF, 0xFF303134};
    private static final String[] NAMES = {"绿色", "浅灰", "米白", "浅蓝", "浅紫", "深灰"};
    private ChatStyle() { }
    public static int clampSize(int size) { return Math.max(11, Math.min(19, size)); }
    public static int color(int value, boolean user) {
        for (int candidate : COLORS) if (candidate == value) return value;
        return user ? DEFAULT_USER : DEFAULT_AI;
    }
    public static int nextColor(int value) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == value) return COLORS[(i + 1) % COLORS.length];
        return COLORS[0];
    }
    public static String colorName(int value) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == value) return NAMES[i];
        return "默认";
    }
    public static int textColor(int background) {
        double l = luminance(background);
        return (l + 0.05) / (luminance(0xFF111111) + 0.05) >= 1.05 / (l + 0.05) ? 0xFF111111 : 0xFFFFFFFF;
    }
    public static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 255) + 0.7152 * channel((color >> 8) & 255) + 0.0722 * channel(color & 255);
    }
    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
