package com.yawac.client.ui;

import android.content.Context;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.SpannableString;
import android.text.style.ReplacementSpan;
import android.util.LruCache;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

import com.yawac.client.R;

import io.noties.markwon.Markwon;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.tables.TableRowSpan;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.image.AsyncDrawableScheduler;
import org.commonmark.node.Node;

public final class Markdown {

    private static final LruCache<String, Markwon> renderers = new LruCache<>(12);
    private static final LruCache<Key, Spanned> rendered = new LruCache<Key, Spanned>(128 * 1024) {
        @Override protected int sizeOf(Key key, Spanned value) { return Math.max(1, key.text.length() + value.length()); }
    };
    private static final LruCache<Key, Node> parsed = new LruCache<Key, Node>(64 * 1024) {
        @Override protected int sizeOf(Key key, Node value) { return Math.max(1, key.text.length()); }
    };

    private static final class Key {
        final String style, text;
        final int hash;
        Key(String style, String text) { this.style = style; this.text = text; hash = 31 * style.hashCode() + text.hashCode(); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof Key && style.equals(((Key) other).style) && text.equals(((Key) other).text);
        }
    }

    private Markdown() {
    }

    public static void render(TextView textView, String text) {
        String source = text == null ? "" : text;
        AsyncDrawableScheduler.unschedule(textView);
        boolean math = source.indexOf('$') >= 0;
        String style = Float.floatToIntBits(textView.getTextSize()) + ":" + textView.getCurrentTextColor()
                + ":" + Float.floatToIntBits(textView.getResources().getDisplayMetrics().density) + ":" + math;
        Key key = new Key(style, source);
        Markwon renderer = get(textView.getContext(), textView.getTextSize(), textView.getCurrentTextColor(), math, style);
        Spanned markdown = rendered.get(key);
        if (markdown == null) {
            Node node = parsed.get(key);
            if (node == null) { node = renderer.parse(source); parsed.put(key, node); }
            markdown = renderer.render(node);
            // Tables and async math/images retain per-view layout/callback state. Re-render
            // those spans from cached syntax; only ordinary text spans may be shared.
            if (markdown.getSpans(0, markdown.length(), ReplacementSpan.class).length == 0
                    && markdown.getSpans(0, markdown.length(), TableRowSpan.class).length == 0) {
                markdown = new SpannedString(markdown);
                rendered.put(key, markdown);
            }
        }
        boolean hasTable = markdown.getSpans(0, markdown.length(), TableRowSpan.class).length > 0;
        // TableRowSpan measures its cells only after drawing. A wrap_content bubble
        // otherwise measures a table-only answer as empty and never gives it a width.
        textView.setMinWidth(hasTable ? textView.getResources()
                .getDimensionPixelSize(R.dimen.bubble_max_width) : 0);
        // CorePlugin adds a per-TextView span before binding. Never add that reference
        // to the cached immutable result, and keep code/list measurement hooks intact.
        renderer.setParsedMarkdown(textView, new SpannableString(markdown));
    }

    static void release(TextView textView) {
        AsyncDrawableScheduler.unschedule(textView);
        textView.setText(null);
    }

    public static void clearMemoryCache() { rendered.evictAll(); parsed.evictAll(); renderers.evictAll(); }

    private static synchronized Markwon get(Context context, float textSize, int textColor, boolean math, String key) {
        Markwon markwon = renderers.get(key);
        if (markwon == null) {
            Context app = context.getApplicationContext();
            float mathTextSize = textSize;
            final int mathColor = textColor;
            boolean lightText = android.graphics.Color.luminance(textColor) > 0.5f;
            int linkColor = ContextCompat.getColor(app, lightText ? R.color.brand_light_green : R.color.ai_link);
            TableTheme theme = TableTheme.buildWithDefaults(app)
                    .tableBorderColor(lightText ? 0xFF66686C : 0xFF888888)
                    .tableBorderWidth(2)
                    .tableHeaderRowBackgroundColor(lightText ? 0xFF202124 : 0xFFB8B8B8)
                    .tableOddRowBackgroundColor(lightText ? 0xFF111214 : 0xFFCCCCCC)
                    .tableEvenRowBackgroundColor(lightText ? 0xFF111214 : 0xFFCCCCCC)
                    .build();
            Markwon.Builder builder = Markwon.builder(app)
                    .usePlugin(new AbstractMarkwonPlugin() {
                        @Override public void configureTheme(MarkwonTheme.Builder builder) {
                            builder.linkColor(linkColor).codeTextColor(textColor)
                                    .headingTypeface(android.graphics.Typeface.create(Typography.regular(app), android.graphics.Typeface.BOLD))
                                    .codeTypeface(android.graphics.Typeface.MONOSPACE)
                                    .codeBlockTypeface(android.graphics.Typeface.MONOSPACE)
                                    .codeBackgroundColor(lightText ? 0xFF202124 : 0xFFB8B8B8);
                        }
                    })
                    .usePlugin(TablePlugin.create(theme))
                    .usePlugin(MarkwonInlineParserPlugin.create());
            if (math) builder.usePlugin(JLatexMathPlugin.create(mathTextSize,
                            new JLatexMathPlugin.BuilderConfigure() {
                                @Override
                                public void configureBuilder(
                                        JLatexMathPlugin.Builder builder) {
                                    builder.inlinesEnabled(true);
                                    builder.theme().textColor(mathColor);
                                }
                            }));
            markwon = builder.build();
            renderers.put(key, markwon);
        }
        return markwon;
    }
}
