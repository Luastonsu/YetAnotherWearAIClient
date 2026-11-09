package com.yawac.client.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.test.InstrumentationTestCase;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.yawac.client.R;
import com.yawac.client.data.Models.Message;
import io.noties.markwon.core.spans.TextViewSpan;
import io.noties.markwon.ext.tables.TableRowSpan;
import java.util.Arrays;

public class PerformanceSafetyTest extends InstrumentationTestCase {
    public void testMutableMessagesUpdateLocallyWithoutResettingTheList() {
        onUi(() -> {
            Message question = new Message("user", "问题", 0);
            Message answer = new Message("assistant", "旧内容", 0);
            MessageAdapter adapter = new MessageAdapter();
            adapter.setMessages(Arrays.asList(question, answer));
            int[] resets = {0}, changed = {0}; Object[] payload = {null};
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { resets[0]++; }
                @Override public void onItemRangeChanged(int start, int count, Object value) { changed[0] += count; payload[0] = value; }
            });
            adapter.setMessages(Arrays.asList(question, answer));
            assertEquals(0, changed[0]);
            answer.content = "更新后的内容";
            adapter.setMessages(Arrays.asList(question, answer));
            assertEquals(1, changed[0]); assertNull(payload[0]);
            FrameLayout parent = new FrameLayout(getInstrumentation().getTargetContext());
            RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(parent, 0);
            adapter.onBindViewHolder(holder, 1);
            assertEquals("更新后的内容", ((TextView) holder.itemView.findViewById(R.id.content)).getText().toString());
            answer.stage = "思考并生成中";
            adapter.setMessages(Arrays.asList(question, answer));
            assertEquals(2, changed[0]); assertNotNull(payload[0]); assertEquals(0, resets[0]);
        });
    }

    public void testCachedMarkdownKeepsViewReferencesAndComplexSpansIndependent() {
        onUi(() -> {
            TextView first = text(), second = text();
            String code = "## 标题\n\n1. 列表\n2. 第二项\n\n```java\nint answer = 42;\n```";
            Markdown.render(first, code); Markdown.render(second, code);
            assertSame(first, TextViewSpan.textViewOf(first.getText()));
            assertSame(second, TextViewSpan.textViewOf(second.getText()));
            String table = "| 名称 | 内容 |\n| --- | --- |\n| A | 不同宽度 |";
            Markdown.render(first, table); Markdown.render(second, table);
            TableRowSpan[] a = ((Spanned) first.getText()).getSpans(0, first.length(), TableRowSpan.class);
            TableRowSpan[] b = ((Spanned) second.getText()).getSpans(0, second.length(), TableRowSpan.class);
            assertTrue(a.length > 0); assertEquals(a.length, b.length); assertNotSame(a[0], b[0]);
            draw(first, 380); draw(second, 280);
            Markdown.render(first, "$$x^2 + 7$$"); Markdown.render(second, "$$x^2 + 7$$");
            ReplacementSpan[] x = ((Spanned) first.getText()).getSpans(0, first.length(), ReplacementSpan.class);
            ReplacementSpan[] y = ((Spanned) second.getText()).getSpans(0, second.length(), ReplacementSpan.class);
            assertTrue(x.length > 0); assertEquals(x.length, y.length); assertNotSame(x[0], y[0]);
            Markdown.render(first, "普通文本"); assertEquals("普通文本", first.getText().toString());
            Markdown.release(first); Markdown.release(second);
        });
    }

    public void testReusedBubbleStyleStillRespondsToAppearanceChanges() {
        onUi(() -> {
            android.content.SharedPreferences prefs = ChatAppearance.prefs(getInstrumentation().getTargetContext());
            boolean had = prefs.contains("chat_text_sp"); int old = prefs.getInt("chat_text_sp", 13);
            try {
                prefs.edit().putInt("chat_text_sp", 13).commit();
                TextView view = text(); ChatAppearance.apply(view, false);
                android.graphics.drawable.Drawable background = view.getBackground();
                ChatAppearance.apply(view, false); assertSame(background, view.getBackground());
                MessageAdapter adapter = new MessageAdapter();
                Message message = new Message("assistant", "外观检查", 0);
                adapter.setMessages(Arrays.asList(message));
                RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(view.getContext()), 0);
                adapter.onBindViewHolder(holder, 0);
                int[] changed = {0};
                adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                    @Override public void onItemRangeChanged(int position, int count, Object payload) { assertNull(payload); changed[0] += count; }
                });
                prefs.edit().putInt("chat_text_sp", 17).commit(); ChatAppearance.apply(view, false);
                adapter.setMessages(Arrays.asList(message));
                assertEquals("Appearance changes must refresh otherwise unchanged messages", 1, changed[0]);
                assertEquals(17f, view.getTextSize() / view.getResources().getDisplayMetrics().scaledDensity, .01f);
            } finally {
                android.content.SharedPreferences.Editor edit = prefs.edit();
                if (had) edit.putInt("chat_text_sp", old); else edit.remove("chat_text_sp"); edit.commit();
            }
        });
    }
    private TextView text() {
        TextView view = new TextView(new ContextThemeWrapper(getInstrumentation().getTargetContext(), R.style.Theme_WearAIChat));
        view.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
        view.setTextSize(13); view.setTextColor(Color.WHITE); Typography.body(view, false); return view;
    }
    private void draw(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        Bitmap image = Bitmap.createBitmap(width, view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image)); image.recycle();
    }
    private void onUi(Runnable action) {
        Throwable[] failure = {null}; getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { failure[0] = t; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
