package com.yawac.client.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Layout;
import android.widget.TextView;
import com.yawac.client.R;

/** One bundled font shared by host views, vendor widgets and canvas labels. */
public final class Typography {
    private static Typeface regular;

    private Typography() { }

    public static synchronized Typeface regular(Context host) {
        if (regular == null) regular = host.getResources().getFont(R.font.misans_regular);
        return regular;
    }

    static void apply(TextView view, Context host) {
        int style = view.getTypeface() == null ? Typeface.NORMAL : view.getTypeface().getStyle();
        // Vendor-context resource IDs belong to another APK; always load with the host.
        view.setTypeface(regular(host), style);
        view.setIncludeFontPadding(false);
        view.setFallbackLineSpacing(true);
        view.getPaint().setAntiAlias(true);
        view.getPaint().setSubpixelText(true);
    }

    static void body(TextView view, boolean reading) {
        apply(view, view.getContext());
        view.setLineSpacing(view.getResources().getDimension(reading
                ? R.dimen.reading_line_spacing : R.dimen.message_line_spacing), 1f);
        // Global paragraph balancing is costly when recycled bubbles are measured during a fling.
        view.setBreakStrategy(reading ? Layout.BREAK_STRATEGY_HIGH_QUALITY : Layout.BREAK_STRATEGY_SIMPLE);
        view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    }
}
