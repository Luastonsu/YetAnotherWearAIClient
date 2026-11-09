package com.yawac.client.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import com.yawac.client.R;

final class ChatAppearance {
    static final String USER_COLOR = "user_bubble_color", AI_COLOR = "ai_bubble_color";
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("wearai_prefs", 0); }
    static int size(Context context) { return ChatStyle.clampSize(prefs(context).getInt("chat_text_sp", 13)); }
    static int color(Context context, boolean user) {
        return ChatStyle.color(prefs(context).getInt(user ? USER_COLOR : AI_COLOR,
                user ? ChatStyle.DEFAULT_USER : ChatStyle.DEFAULT_AI), user);
    }
    static void apply(TextView text, boolean user) {
        Context context = text.getContext();
        int color = color(context, user);
        int size = size(context);
        float px = size * context.getResources().getDisplayMetrics().scaledDensity;
        float density = context.getResources().getDisplayMetrics().density;
        Object tag = text.getTag(R.id.chatAppearanceStyle);
        if (tag instanceof Style) {
            Style style = (Style) tag;
            if (style.color == color && style.px == px && style.density == density) return;
        }
        Typography.body(text, false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(24 * context.getResources().getDisplayMetrics().density);
        text.setBackground(background);
        int foreground = ChatStyle.textColor(color);
        text.setTextColor(foreground);
        text.setLinkTextColor(foreground == 0xFFFFFFFF ? 0xFF3FD18A : 0xFF005B24);
        text.setTextSize(size);
        text.setTag(R.id.chatAppearanceStyle, new Style(color, px, density));
    }

    private static final class Style {
        final int color;
        final float px, density;
        Style(int color, float px, float density) { this.color = color; this.px = px; this.density = density; }
    }
}
