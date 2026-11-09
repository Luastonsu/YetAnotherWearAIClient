package com.yawac.client.ui;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.content.res.Configuration;
import android.util.Log;
import android.util.Xml;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import org.xmlpull.v1.XmlPullParser;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/** Uses the installed vendor widget code and its resources, without copying OEM APKs. */
final class NativeUi {
    private static final String PREFIX = "com.heytap.wearable.support.widget.";
    private static final String[] PACKAGES = {
            "com.heytap.wearable.mooddiary2", "com.heytap.wearable.unitconverter2",
            "com.heytap.wearable.soundrecorder"
    };
    private static final Map<String, Source> SOURCES = new HashMap<>();
    private static Configuration configuration;

    /** Cached objects own application/package contexts only, never an Activity or View. */
    private static synchronized Source source(Context host, String name) {
        Configuration current = host.getResources().getConfiguration();
        if (configuration == null || !configuration.equals(current)) {
            SOURCES.clear(); configuration = new Configuration(current);
        }
        if (SOURCES.containsKey(name)) return SOURCES.get(name);
        Source source = null;
        try { source = new Source(host.getApplicationContext(), name); }
        catch (Exception | LinkageError error) { Log.w("WearNativeUi", "Source unavailable: " + name); }
        SOURCES.put(name, source);
        return source;
    }

    private static final class Recipe {
        final int layout;
        final Constructor<?> constructor;
        Recipe(int layout, Constructor<?> constructor) { this.layout = layout; this.constructor = constructor; }
    }

    private static final class Source {
        final Context context, styled;
        final String name;
        final Map<String, Recipe> recipes = new HashMap<>();
        Source(Context host, String name) throws Exception {
            this.name = name;
            context = host.createPackageContext(name, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            int theme = context.getResources().getIdentifier("HeytapCompatTheme", "style", name);
            styled = new ContextThemeWrapper(context, theme);
        }
        synchronized Recipe recipe(String widget, String layout) throws Exception {
            String key = widget + ':' + layout;
            if (recipes.containsKey(key)) return recipes.get(key);
            int resource = context.getResources().getIdentifier(layout, "layout", name);
            Recipe recipe = resource == 0 ? null : new Recipe(resource, context.getClassLoader().loadClass(PREFIX + widget)
                    .getConstructor(Context.class, android.util.AttributeSet.class));
            recipes.put(key, recipe);
            return recipe;
        }
    }

    static View create(Context host, String widget, String layout) {
        return create(host, widget, layout, PACKAGES);
    }

    static View create(Context host, String widget, String layout, String[] packages) {
        for (String name : packages) {
            try {
                Source source = source(host, name);
                if (source == null) continue;
                Recipe recipe = source.recipe(widget, layout);
                if (recipe == null) continue;
                try (XmlResourceParser parser = source.context.getResources().getLayout(recipe.layout)) {
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.getEventType() != XmlPullParser.START_TAG
                                || !parser.getName().equals(PREFIX + widget)) continue;
                        View view = (View) recipe.constructor.newInstance(source.styled, Xml.asAttributeSet(parser));
                        // Source resource IDs must never participate in the host's view lookup.
                        view.setId(View.NO_ID);
                        return view;
                    }
                }
            } catch (Exception | LinkageError error) {
                Log.w("WearNativeUi", widget + " unavailable in " + name + ": "
                        + error.getClass().getSimpleName());
            }
        }
        return null;
    }

    static ImageButton imageButton(ImageButton original) {
        View candidate = create(original.getContext(), "HeyImageButton", "hey_state_image_btn_view_stub");
        if (!(candidate instanceof ImageButton)) return original;
        ImageButton button = (ImageButton) candidate;
        button.setImageDrawable(original.getDrawable());
        button.setBackground(original.getBackground());
        button.setPadding(original.getPaddingLeft(), original.getPaddingTop(),
                original.getPaddingRight(), original.getPaddingBottom());
        button.setScaleType(original.getScaleType());
        replace(original, button);
        return button;
    }

    static void shapeButton(TextView original, int color) {
        original.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        View candidate = create(original.getContext(), "HeyShapeButton", "hey_state_shape_btn_view_stub");
        if (!(candidate instanceof TextView)) return;
        TextView button = (TextView) candidate;
        try {
            button.getClass().getMethod("setHeytapBg", int.class).invoke(button, color);
        } catch (Exception error) { return; }
        button.setText(original.getText());
        button.setTextColor(original.getCurrentTextColor());
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, original.getTextSize());
        button.setGravity(original.getGravity());
        button.setMaxLines(original.getMaxLines());
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setTypeface(original.getTypeface());
        Typography.apply(button, original.getContext());
        replace(original, button);
    }

    static void newChatButton(View original) {
        View candidate = create(original.getContext(), "HeyShapeButton", "hey_state_shape_btn_view_stub");
        if (!(candidate instanceof TextView)) return;
        TextView button = (TextView) candidate;
        try {
            button.getClass().getMethod("setHeytapBg", int.class)
                    .invoke(button, original.getContext().getColor(com.yawac.client.R.color.brand_surface));
        } catch (Exception error) { return; }
        Context host = original.getContext();
        android.graphics.drawable.Drawable plus = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(host, com.yawac.client.R.drawable.ic_add).mutate();
        plus.setTint(host.getColor(com.yawac.client.R.color.brand_green));
        int iconSize = Math.round(32 * host.getResources().getDisplayMetrics().density);
        plus.setBounds(0, 0, iconSize, iconSize);
        android.text.SpannableString label = new android.text.SpannableString("\uFFFC  "
                + host.getString(com.yawac.client.R.string.new_chat));
        label.setSpan(new android.text.style.ImageSpan(plus, android.text.style.DynamicDrawableSpan.ALIGN_CENTER) {
            @Override public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end,
                                         android.graphics.Paint.FontMetricsInt metrics) {
                if (metrics != null) {
                    android.graphics.Paint.FontMetricsInt font = paint.getFontMetricsInt();
                    int center = (font.ascent + font.descent) / 2;
                    metrics.top = metrics.ascent = center - iconSize / 2;
                    metrics.bottom = metrics.descent = metrics.ascent + iconSize;
                }
                return iconSize;
            }
        },
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(label);
        button.setTextColor(original.getContext().getColor(com.yawac.client.R.color.brand_green));
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        Typography.apply(button, host);
        replace(original, button);
    }

    /** Real OEM press surface behind our two-line history content. */
    static View historyCard(android.widget.FrameLayout card) {
        Context host = card.getContext();
        View surface = create(host, "HeyShapeButton", "hey_state_shape_btn_view_stub");
        if (surface instanceof TextView) {
            try {
                surface.getClass().getMethod("setHeytapBg", int.class).invoke(surface, host.getColor(com.yawac.client.R.color.brand_surface));
            } catch (Exception error) { surface = null; }
        } else surface = null;
        if (surface == null) {
            surface = new android.widget.Button(host);
            // Framework button elevation otherwise paints this background over the labels.
            surface.setStateListAnimator(null);
            surface.setElevation(0);
            surface.setTranslationZ(0);
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(host.getColor(com.yawac.client.R.color.brand_surface));
            background.setCornerRadius(18 * host.getResources().getDisplayMetrics().density);
            surface.setBackground(background);
        }
        TextView button = (TextView) surface;
        button.setText(""); button.setMinHeight(0); button.setMinimumHeight(0); button.setPadding(0, 0, 0, 0);
        Typography.apply(button, host);
        button.setId(com.yawac.client.R.id.historyCardButton);
        android.graphics.drawable.GradientDrawable stroke = new android.graphics.drawable.GradientDrawable();
        stroke.setColor(android.graphics.Color.TRANSPARENT);
        float radius = 18 * host.getResources().getDisplayMetrics().density;
        int radiusId = button.getResources().getIdentifier("hey_button_default_radius", "dimen", button.getContext().getPackageName());
        if (radiusId != 0) radius = button.getResources().getDimension(radiusId);
        stroke.setCornerRadius(radius);
        stroke.setStroke(Math.max(1, Math.round(host.getResources().getDisplayMetrics().density)), 0xFF55585E);
        button.setForeground(stroke);
        card.addView(button, 0, new android.widget.FrameLayout.LayoutParams(-1, -1));
        return button;
    }

    private static void replace(View original, View replacement) {
        ViewGroup parent = (ViewGroup) original.getParent();
        int index = parent.indexOfChild(original);
        replacement.setId(original.getId());
        replacement.setContentDescription(original.getContentDescription());
        replacement.setVisibility(original.getVisibility());
        replacement.setFocusable(original.isFocusable());
        replacement.setClickable(original.isClickable());
        replacement.setEnabled(original.isEnabled());
        ViewGroup.LayoutParams params = original.getLayoutParams();
        parent.removeViewAt(index);
        parent.addView(replacement, index, params);
    }
}
