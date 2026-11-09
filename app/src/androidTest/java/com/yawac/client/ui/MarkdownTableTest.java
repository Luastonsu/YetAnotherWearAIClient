package com.yawac.client.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.test.InstrumentationTestCase;
import android.text.Layout;
import android.text.Spanned;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.yawac.client.R;
import com.yawac.client.data.Models.Message;

import java.util.Collections;
import java.io.File;
import java.io.FileOutputStream;

import io.noties.markwon.ext.tables.TableAwareMovementMethod;
import io.noties.markwon.ext.tables.TableRowSpan;

/** Runs the real Android table measurement and drawing at watch dimensions. */
public class MarkdownTableTest extends InstrumentationTestCase {
    private static final String TABLE = "| 项目 | 结果 |\n| --- | --- |\n"
            + "| 天气 | 晴天 |\n| 来源 | [官网](https://example.com/weather) |";

    public void testTableOnlyReplyHasUsableWidthAndVisibleRows() {
        onUi(() -> {
            View row = bind(TABLE);
            TextView content = row.findViewById(R.id.content);
            drawAtWidth(row, 240, "table-two-columns.png");
            assertVisibleTable(content, 3);
            assertTrue(content.getMovementMethod() instanceof TableAwareMovementMethod);
        });
    }

    public void testThreeColumnsWithLongSourceAndSurroundingText() {
        onUi(() -> {
            View row = bind("检索结果：\n\n| 名称 | 特点 | 来源 |\n| :--- | :---: | ---: |\n"
                    + "| A | 适合小屏幕阅读的较长说明 | [较长的官方网站标题](https://example.com/a) |\n"
                    + "| B | 简短 | [官网](https://example.com/b) |\n\n来源：[官方资料](https://example.com)");
            drawAtWidth(row, 240, "table-three-columns.png");
            assertVisibleTable(row.findViewById(R.id.content), 3);
        });
    }

    public void testTableFitsNarrowWatch() {
        onUi(() -> {
            View row = bind(TABLE);
            drawAtWidth(row, 140, null);
            TextView content = row.findViewById(R.id.content);
            assertVisibleTable(content, 3);
            assertTrue(content.getWidth() <= row.getWidth() - row.getPaddingLeft() - row.getPaddingRight());
        });
    }

    public void testRecycledTableBubbleShrinksForTextAndErrors() {
        onUi(() -> {
            FrameLayout parent = new FrameLayout(getInstrumentation().getTargetContext());
            MessageAdapter adapter = new MessageAdapter();
            adapter.setMessages(Collections.singletonList(new Message("assistant", TABLE, 0)));
            androidx.recyclerview.widget.RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(parent, 0);
            adapter.onBindViewHolder(holder, 0);
            View row = holder.itemView;
            TextView content = row.findViewById(R.id.content);
            drawAtWidth(row, 240, null);
            int tableWidth = content.getWidth();
            for (boolean failed : new boolean[]{false, true}) {
                // Rebind a table before each ordinary/error message to cover both paths.
                adapter.setMessages(Collections.singletonList(new Message("assistant", TABLE, 0)));
                adapter.onBindViewHolder(holder, 0);
                Message shortReply = new Message("assistant", "收到", 0);
                shortReply.failed = failed;
                adapter.setMessages(Collections.singletonList(shortReply));
                adapter.onBindViewHolder(holder, 0);
                drawAtWidth(row, 240, null);
                assertEquals(0, content.getMinWidth());
                assertTrue(content.getWidth() < tableWidth);
            }
        });
    }

    private View bind(String text) {
        FrameLayout parent = new FrameLayout(getInstrumentation().getTargetContext());
        MessageAdapter adapter = new MessageAdapter();
        adapter.setMessages(Collections.singletonList(new Message("assistant", text, 0)));
        androidx.recyclerview.widget.RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);
        return holder.itemView;
    }

    public void testFormulaColorChangesBetweenBubbleAndReadingSurfaces() {
        onUi(() -> {
            for (boolean lightText : new boolean[]{true, false, true}) {
                TextView text = new TextView(getInstrumentation().getTargetContext());
                text.setTextSize(15);
                text.setTextColor(lightText ? 0xFFFFFFFF : 0xFF111111);
                text.setBackgroundColor(lightText ? 0xFF000000 : 0xFFCCCCCC);
                Markdown.render(text, "$$x^2 + 37$$");
                int width = Math.round(190 * text.getResources().getDisplayMetrics().density);
                text.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                text.layout(0, 0, width, text.getMeasuredHeight());
                Bitmap bitmap = Bitmap.createBitmap(width, text.getHeight(), Bitmap.Config.ARGB_8888);
                text.draw(new Canvas(bitmap));
                int glyphs = 0;
                for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < width; x++) {
                    int red = android.graphics.Color.red(bitmap.getPixel(x, y));
                    if (lightText ? red > 220 : red < 80) glyphs++;
                }
                assertTrue("Formula must use a readable color on each surface", glyphs > 20);
                bitmap.recycle();
            }
        });
    }

    private void assertVisibleTable(TextView content, int rows) {
        assertTrue("Table bubble collapsed: " + content.getWidth(), content.getWidth() > 100);
        TableRowSpan[] spans = ((Spanned) content.getText()).getSpans(0, content.length(), TableRowSpan.class);
        assertEquals(rows, spans.length);
        int totalHeight = 0;
        for (TableRowSpan span : spans) {
            assertTrue("A table column has no drawable width", span.cellWidth() > 20);
            int height = 0;
            for (int x = 0; x < content.getWidth() - content.getPaddingLeft() - content.getPaddingRight();
                 x += span.cellWidth()) {
                Layout layout = span.findLayoutForHorizontalOffset(x);
                if (layout != null) height = Math.max(height, layout.getHeight());
            }
            totalHeight += height;
        }
        assertTrue("Table rows are clipped", content.getLayout().getHeight() >= totalHeight);
    }

    private void onUi(Runnable action) {
        Throwable[] failure = new Throwable[1];
        getInstrumentation().runOnMainSync(() -> {
            try { action.run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void drawAtWidth(View row, int dp, String imageName) {
        int width = (int) (dp * row.getResources().getDisplayMetrics().density);
        TextView content = row.findViewById(R.id.content);
        // Detached views do not run TableRowsScheduler's posted remeasurement.
        // Repeat it explicitly after the first draw establishes the cell layouts.
        for (int pass = 0; pass < 3; pass++) {
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, row.getHeight()), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(0xFF000000);
            row.draw(canvas);
            if (pass == 2 && imageName != null) {
                try (FileOutputStream out = new FileOutputStream(new File(
                        getInstrumentation().getTargetContext().getCacheDir(), imageName))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Exception e) { throw new AssertionError(e); }
            }
            bitmap.recycle();
            if (pass < 2) content.setText(content.getText());
        }
    }
}
