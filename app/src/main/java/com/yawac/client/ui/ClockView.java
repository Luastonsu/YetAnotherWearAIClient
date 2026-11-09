package com.yawac.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextClock;
import java.lang.reflect.Field;
import com.yawac.client.R;

/** Hosts the vendor's real arc clock, with an independent Android fallback. */
public class ClockView extends FrameLayout {
    private final TextClock clock;
    private Path vendorPath;
    private int alignedWidth, alignedHeight;
    private float alignedSize;
    private Typeface alignedFont;

    public ClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
        View candidate = NativeUi.create(context, "HeyArcTextClock", "hey_textclock");
        clock = candidate instanceof TextClock ? (TextClock) candidate : new LegacyArcClock(context, attrs);
        clock.setId(View.NO_ID);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setIncludeFontPadding(false);
        Typography.apply(clock, context);
        clock.setTypeface(Typography.regular(context));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.clock_text_size));
        clock.setFontFeatureSettings("tnum");
        clock.setTextColor(context.getColor(R.color.text_primary));
        clock.setPadding(0, 0, 0, 0);
        clock.setMinimumHeight(0);
        clock.setMinimumWidth(0);
        setBackground(null);
        clock.setBackground(null);
        // Keep the floating time legible over light bubbles without covering the content.
        clock.setShadowLayer(1.5f * getResources().getDisplayMetrics().density, 0, 0, 0xC0000000);
        // Some OEM builds append to their Path every draw. Clear that cache before drawing.
        if (candidate instanceof TextClock) {
            for (Field field : candidate.getClass().getDeclaredFields()) {
                if (field.getType() != Path.class) continue;
                try { field.setAccessible(true); vendorPath = (Path) field.get(candidate); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
        setPadding(0, 0, 0, 0);
        // The OEM arc calculation requires a clock sized to its text, centered by its parent.
        addView(clock, new LayoutParams(candidate instanceof TextClock ? LayoutParams.WRAP_CONTENT
                : LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (vendorPath != null) vendorPath.reset();
        super.dispatchDraw(canvas);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (clock.getWidth() <= 0 || clock.getHeight() <= 0) return;
        if (alignedWidth == clock.getWidth() && alignedHeight == clock.getHeight()
                && alignedSize == clock.getTextSize() && alignedFont == clock.getTypeface()) return;
        alignedWidth = clock.getWidth(); alignedHeight = clock.getHeight();
        alignedSize = clock.getTextSize(); alignedFont = clock.getTypeface();
        // OEM arc baselines differ from TextView.getBaseline(). Align the actual glyphs,
        // once per size/font change, rather than moving unused font ascent toward the edge.
        int margin = Math.round(8 * getResources().getDisplayMetrics().density);
        Bitmap sample = Bitmap.createBitmap(alignedWidth + margin * 2,
                alignedHeight + margin * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sample);
        canvas.translate(margin, margin);
        if (vendorPath != null) vendorPath.reset();
        clock.draw(canvas);
        if (vendorPath != null) vendorPath.reset();
        int firstInk = -1;
        for (int y = 0; y < sample.getHeight() && firstInk < 0; y++) {
            for (int x = 0; x < sample.getWidth(); x++) {
                int pixel = sample.getPixel(x, y);
                // Ignore the black shadow; keep solid/antialiased white glyph edges.
                if (Color.alpha(pixel) > 128 && Color.red(pixel) > 160) { firstInk = y; break; }
            }
        }
        sample.recycle();
        if (firstInk >= 0) clock.setTranslationY(getResources().getDimension(R.dimen.clock_edge_inset)
                - (firstInk - margin));
    }

    public void setText(CharSequence text) { clock.setText(text); }
    public CharSequence getFormat12Hour() { return clock.getFormat12Hour(); }
    public CharSequence getFormat24Hour() { return clock.getFormat24Hour(); }
    TextClock widget() { return clock; }
}
