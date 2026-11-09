package com.yawac.client.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatStyleTest {
    @Test public void paletteTextIsReadableOnEveryBackground() {
        for (int color : ChatStyle.COLORS) {
            double background = ChatStyle.luminance(color), text = ChatStyle.luminance(ChatStyle.textColor(color));
            double contrast = (Math.max(background, text) + 0.05) / (Math.min(background, text) + 0.05);
            assertTrue("Unreadable palette entry", contrast >= 4.5);
        }
    }
    @Test public void corruptValuesFallBackToTheOriginalLookAndSizeIsBounded() {
        assertEquals(0xFF33B054, ChatStyle.color(0, true));
        assertEquals(0xFFCCCCCC, ChatStyle.color(0, false));
        assertEquals(11, ChatStyle.clampSize(1));
        assertEquals(19, ChatStyle.clampSize(100));
        assertEquals(13, ChatStyle.clampSize(13));
    }
    @Test public void colorChoicesWrapAndHaveDistinctNames() {
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        int start = ChatStyle.DEFAULT_USER, color = start;
        do { colors.add(color); names.add(ChatStyle.colorName(color)); color = ChatStyle.nextColor(color); } while (color != start);
        assertEquals(ChatStyle.COLORS.length, colors.size());
        assertEquals(ChatStyle.COLORS.length, names.size());
    }
}
