package com.yawac.client.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.graphics.Path;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.yawac.client.R;
import java.io.File;
import java.io.FileOutputStream;

/** Uses real Android font metrics/layout, including the vendor-context bridge if installed. */
public class TypographyTest extends InstrumentationTestCase {
    private Context host() {
        return new ContextThemeWrapper(getInstrumentation().getTargetContext(), R.style.Theme_WearAIChat);
    }

    public void testHostScreensAndNativeButtonsUseTheBundledFamily() {
        onUi(() -> {
            Context host = host();
            for (int layout : new int[]{R.layout.activity_main, R.layout.activity_chat,
                    R.layout.activity_api_settings, R.layout.activity_chat_appearance,
                    R.layout.activity_reading, R.layout.activity_storage, R.layout.activity_welcome}) {
                View root = LayoutInflater.from(host).inflate(layout, new FrameLayout(host), false);
                assertFamily(root, host);
            }
            FrameLayout root = new FrameLayout(host);
            TextView button = new TextView(host); root.addView(button);
            NativeUi.shapeButton(button, host.getColor(R.color.brand_surface));
            assertFamily(root, host);
            View header = LayoutInflater.from(host).inflate(R.layout.item_chat_header, root, false);
            NativeUi.newChatButton(header.findViewById(R.id.newChatButton));
            assertFamily(header, host);
        });
    }

    public void testMultilingualParagraphsFitAtEverySupportedSize() {
        onUi(() -> {
            Context host = host();
            for (boolean reading : new boolean[]{false, true}) for (int size : new int[]{11, 13, 19}) {
                TextView text = new TextView(host);
                text.setTextSize(size); text.setTextColor(Color.WHITE); text.setPadding(16, 12, 16, 12);
                Typography.body(text, reading);
                text.setText("中文清晰可读，MiSans 0123456789。\nÅngström café gypq — 标点与上下伸部。\n"
                        + "مرحبا 👩‍🚀 与系统后备字形一起显示。\n" + (reading ? "阅读留白" : "聊天段落"));
                int width = Math.round(190 * host.getResources().getDisplayMetrics().density);
                text.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                text.layout(0, 0, text.getMeasuredWidth(), text.getMeasuredHeight());
                assertTrue(text.getLayout().getLineCount() >= 4);
                assertTrue(text.getLayout().getLineBottom(text.getLineCount() - 1)
                        <= text.getHeight() - text.getPaddingTop() - text.getPaddingBottom());
                assertTrue(text.isFallbackLineSpacing());
                assertFalse(text.getIncludeFontPadding());
                assertEquals(host.getResources().getDimension(reading ? R.dimen.reading_line_spacing
                        : R.dimen.message_line_spacing), text.getLineSpacingExtra(), .01f);
                Bitmap image = draw(text);
                try {
                    assertTrue("Top/bottom padding must keep glyphs away from clipping", clearRow(image, 0)
                            && clearRow(image, image.getHeight() - 1));
                    if (size == 13) save(image, reading ? "misans-reading.png" : "misans-paragraph.png");
                } finally { image.recycle(); }
            }
        });
    }

    public void testClockUsesSmallerDigitsAndAlignsToTheSafeTopEdge() {
        onUi(() -> {
            for (boolean round : new boolean[]{false, true}) {
                Configuration config = new Configuration(host().getResources().getConfiguration());
                config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_ROUND_MASK)
                        | (round ? Configuration.SCREENLAYOUT_ROUND_YES : Configuration.SCREENLAYOUT_ROUND_NO);
                Context context = new ContextThemeWrapper(host().createConfigurationContext(config), R.style.Theme_WearAIChat);
                ClockView clock = (ClockView) LayoutInflater.from(context).inflate(R.layout.view_clock, new FrameLayout(context), false);
                for (String time : new String[]{"22:59", "9:41"}) {
                    clock.setText(time);
                    clock.measure(View.MeasureSpec.makeMeasureSpec(466, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
                    clock.layout(0, 0, clock.getMeasuredWidth(), clock.getMeasuredHeight());
                    assertEquals(12f, clock.widget().getTextSize() / context.getResources().getDisplayMetrics().scaledDensity, .01f);
                    assertEquals("HH:mm", clock.getFormat24Hour().toString());
                    assertEquals("h:mm", clock.getFormat12Hour().toString());
                    Bitmap image = draw(clock);
                    try {
                        int[] ink = inkBounds(image);
                        float inset = context.getResources().getDimension(R.dimen.clock_edge_inset);
                        assertEquals("Visible glyphs should sit near the safe edge", inset, ink[0], 2f);
                        assertTrue("Digits must not touch the bottom clipping edge", ink[1] < image.getHeight() - 1);
                        assertTrue("Clock background remains transparent", clearRow(image, image.getHeight() - 1));
                        if (time.equals("22:59")) save(image, round ? "misans-clock-round.png" : "misans-clock-flat.png");
                    } finally { image.recycle(); }
                }
            }
        });
    }

    public void testInputUsesMiSansWithoutChangingComposerBounds() {
        onUi(() -> {
            Context host = host();
            View screen = LayoutInflater.from(host).inflate(R.layout.activity_chat, new FrameLayout(host), false);
            ComposerMorphLayout composer = screen.findViewById(R.id.composer);
            android.widget.EditText input = screen.findViewById(R.id.input);
            composer.bind(input, screen.findViewById(R.id.chatOptionsButton));
            composer.setWaiting(false, Color.WHITE, "选项", false);
            input.setText("MiSans 中文 gypq");
            float density = host.getResources().getDisplayMetrics().density;
            composer.measure(View.MeasureSpec.makeMeasureSpec(Math.round(145 * density), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(36 * density), View.MeasureSpec.EXACTLY));
            composer.layout(0, 0, composer.getMeasuredWidth(), composer.getMeasuredHeight());
            assertEquals(Math.round(104 * density), input.getWidth());
            assertEquals(Math.round(30 * density), input.getHeight());
            assertEquals(0, input.getPaddingTop()); assertEquals(0, input.getPaddingBottom());
            assertTrue(input.getPaint().getFontMetrics().bottom - input.getPaint().getFontMetrics().top < input.getHeight());
            assertFamily(input, host);
            Bitmap image = draw(composer);
            try { save(image, "misans-composer.png"); } finally { image.recycle(); }
        });
    }

    private void assertFamily(View view, Context host) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface actual = text.getTypeface();
            Typeface styled = Typeface.create(Typography.regular(host), actual.getStyle());
            Typeface weighted = Typeface.create(Typography.regular(host), actual.getWeight(), actual.isItalic());
            // Framework Resources and AppCompat can create distinct Typeface instances
            // for the same file. Compare actual outlines, not native-handle identity.
            assertTrue("Unexpected family in " + view.getClass().getName() + " id=" + view.getId(),
                    sameOutlines(text.getPaint(), styled) || sameOutlines(text.getPaint(), weighted));
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertFamily(((ViewGroup) view).getChildAt(i), host);
    }

    private boolean sameOutlines(Paint source, Typeface expected) {
        String sample = "中文Agyp Åé012345";
        Paint actual = new Paint(source), reference = new Paint(source);
        actual.setTextSize(30); reference.setTextSize(30); reference.setTypeface(expected);
        Path a = new Path(), b = new Path();
        actual.getTextPath(sample, 0, sample.length(), 0, 0, a);
        reference.getTextPath(sample, 0, sample.length(), 0, 0, b);
        return java.util.Arrays.equals(a.approximate(.1f), b.approximate(.1f));
    }

    private Bitmap draw(View view) {
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image)); return image;
    }
    private int[] inkBounds(Bitmap image) {
        int top = image.getHeight(), bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int color = image.getPixel(x, y);
            if (Color.alpha(color) > 128 && Color.red(color) > 160) { top = Math.min(top, y); bottom = Math.max(bottom, y); }
        }
        assertTrue("Clock digits must render", bottom > top); return new int[]{top, bottom};
    }
    private boolean clearRow(Bitmap image, int row) {
        for (int x = 0; x < image.getWidth(); x++) if (Color.alpha(image.getPixel(x, row)) > 0) return false;
        return true;
    }
    private void save(Bitmap image, String name) {
        try (FileOutputStream out = new FileOutputStream(new File(getInstrumentation().getTargetContext().getCacheDir(), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private void onUi(Runnable action) {
        Throwable[] failure = new Throwable[1];
        getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { failure[0] = t; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
