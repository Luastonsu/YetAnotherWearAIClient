package com.yawac.client.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import com.yawac.client.R;
import com.yawac.client.data.DebugTraceStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local trace browser: sessions -> ordered events -> paged, selectable plain text. */
public class DebugSettingsActivity extends SettingsScreenActivity {
    private static final int EXPORT = 81, CONFIRM_CLEAR = 82, PAGE_CHARS = 4000;
    private static final String TRACE = "trace", EVENT = "event", REASONING = "reasoning", CLEAR = "clear_confirmation";
    private DebugTraceStore store;
    private LinearLayout content;
    private ScrollView scroll;
    private String traceId, exportingId, eventDetail = "", eventLabel = "";
    private JSONObject trace;
    private int eventIndex = -1, page, requestVersion;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        openSettingsLayout(R.layout.activity_debug);
        store = DebugTraceStore.get(this);
        content = findViewById(R.id.debugContent); scroll = findViewById(R.id.debugScroll);
        traceId = getIntent().getStringExtra(TRACE);
        eventIndex = getIntent().getIntExtra(EVENT, -1);
        if (saved != null) { exportingId = saved.getString("exporting"); page = saved.getInt("page"); }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        if (getIntent().getBooleanExtra(CLEAR, false)) showClearConfirmation();
        else if (eventIndex >= 0) loadEvent();
        else if (traceId == null) showSessions();
        else showTrace(traceId);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("trace", traceId); out.putString("exporting", exportingId);
        out.putInt("page", page);
        super.onSaveInstanceState(out);
    }

    private void back() {
        finish();
    }

    private void openTrace(String id) {
        startActivity(new Intent(this, DebugSettingsActivity.class).putExtra(TRACE, id));
    }

    private void openEvent(String id, int index, boolean reasoning) {
        startActivity(new Intent(this, DebugSettingsActivity.class).putExtra(TRACE, id)
                .putExtra(EVENT, index).putExtra(REASONING, reasoning));
    }

    private void loadEvent() {
        reset("正在读取…");
        int version = ++requestVersion;
        store.read(traceId, loaded -> {
            if (isDestroyed() || version != requestVersion) return;
            JSONArray events = loaded == null ? null : loaded.optJSONArray("events");
            JSONObject event = events == null ? null : events.optJSONObject(eventIndex);
            if (event == null) {
                reset("记录不可读取"); text("记录可能已清除或被轮换。", 12, false); button("返回步骤", this::finish); return;
            }
            eventLabel = event.optString("label"); eventDetail = readable(event.optString("detail"));
            if (event.has("omittedChars")) eventDetail += "\n\n[记录上限：省略 " + event.optInt("omittedChars") + " 个字符]";
            if (getIntent().getBooleanExtra(REASONING, false)) {
                eventLabel = "服务商思考内容";
                eventDetail = "以下为该响应实际返回的 reasoning_content，不代表完整内部思考。\n\n" + providerReasoning(event.optString("detail"));
            }
            page = Math.min(page, Math.max(0, (eventDetail.length() - 1) / PAGE_CHARS));
            showEvent();
        });
    }

    private void showClearConfirmation() {
        reset("清除调试记录？");
        text("仅删除调试记录，保留聊天和 API 配置。正在采集的记录也会停止。", 12, false);
        button("确认清除", () -> store.clear(ok -> {
            if (!isDestroyed()) {
                toast(ok ? "调试记录已清除" : "部分记录未能清除");
                setResult(ok ? RESULT_OK : RESULT_CANCELED); finish();
            }
        }));
        button("取消", this::finish);
    }

    private void reset(String title) {
        content.removeAllViews(); scroll.scrollTo(0, 0);
        TextView heading = text(title, 17, true); heading.setGravity(Gravity.CENTER);
    }

    private void showSessions() {
        traceId = null; eventIndex = -1; trace = null;
        int version = ++requestVersion;
        reset("调试模式");
        ModeToggleView toggle = new ModeToggleView(this, null);
        toggle.setId(R.id.debugRecordingToggle);
        toggle.setText("记录对话过程"); toggle.setSelected(store.enabled());
        toggle.setContentDescription("记录对话过程，" + (store.enabled() ? "已开启" : "已关闭"));
        content.addView(toggle, params(48));
        toggle.setOnClickListener(v -> {
            store.setEnabled(!store.enabled()); toggle.setSelected(store.enabled());
            toggle.setContentDescription("记录对话过程，" + (store.enabled() ? "已开启" : "已关闭"));
        });
        text("开启后记录新请求的完整输入、搜索决策、关键词、资料与接口响应，包括服务商返回的思考内容（如有）。不代表模型完整内部思考。", 11, false);
        text("记录含聊天内容，仅本机保存，不自动上传。最多保留 20 条 / 8 MB；单条详情上限 512K 字符，超限会标记。关闭后停止当前采集。", 11, false);
        button("刷新记录", this::showSessions);
        button("清除调试记录", () -> startActivityForResult(new Intent(this, DebugSettingsActivity.class).putExtra(CLEAR, true), CONFIRM_CLEAR));
        TextView loading = text("正在读取…", 11, false);
        store.list(items -> {
            if (isDestroyed() || version != requestVersion) return;
            loading.setText(items.isEmpty() ? "还没有调试记录。开启后发送一条新消息，再回到此页刷新。" : "最近记录（含自动标题）");
            if (!store.storageError().isEmpty()) text(store.storageError(), 11, false);
            for (JSONObject item : items) {
                String question = item.optString("question", "");
                String label = time(item.optLong("startedAt")) + " · " + purpose(item) + "\n" + status(item)
                        + (question.isEmpty() ? "" : "\n" + question);
                button(label, () -> openTrace(item.optString("id")));
            }
        });
    }

    private void showTrace(String id) {
        traceId = id; eventIndex = -1;
        int version = ++requestVersion;
        reset("对话过程"); text("正在读取…", 12, false);
        store.read(id, loaded -> {
            if (isDestroyed() || version != requestVersion) return;
            trace = loaded; reset("对话过程");
            if (loaded == null) {
                text("记录已清除、被轮换或不可读取。", 12, false); button("返回记录列表", this::finish); return;
            }
            text(time(loaded.optLong("startedAt")) + " · " + purpose(loaded) + "\n" + status(loaded)
                    + "\n总耗时 " + loaded.optLong("elapsedMs", lastTime(loaded)) + " ms", 12, false);
            text("点按步骤查看实际数据。非流式响应：接口返回前无法显示思考内容；服务未返回时也不会补写。", 11, false);
            if (loaded.optBoolean("truncated")) text("本轮详情达到上限，部分内容已截断。", 12, false);
            button("刷新本轮", () -> showTrace(id));
            button("导出本轮 JSON", () -> export(id));
            JSONArray events = loaded.optJSONArray("events");
            if (events != null) for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i); if (event == null) continue;
                final int index = i;
                button("+" + event.optLong("atMs") + " ms\n" + event.optString("label"), () -> openEvent(id, index, false));
                if ("response".equals(event.optString("kind"))) {
                    String reasoning = providerReasoning(event.optString("detail"));
                    if (!reasoning.isEmpty()) button("查看服务商思考内容", () -> openEvent(id, index, true));
                }
            }
            button("返回记录列表", this::finish);
        });
    }

    private void showEvent() {
        requestVersion++;
        reset(eventLabel);
        int pages = Math.max(1, (eventDetail.length() + PAGE_CHARS - 1) / PAGE_CHARS);
        text("第 " + (page + 1) + " / " + pages + " 段", 11, false);
        if (page > 0) button("上一段", () -> { page--; showEvent(); });
        if (page + 1 < pages) button("下一段", () -> { page++; showEvent(); });
        int start = boundary(page * PAGE_CHARS), end = boundary(Math.min(eventDetail.length(), (page + 1) * PAGE_CHARS));
        TextView detail = text(eventDetail.isEmpty() ? "（空）" : eventDetail.substring(start, end), 12, false);
        detail.setId(R.id.debugEventDetail); detail.setTextIsSelectable(true);
        if (page + 1 < pages) button("下一段", () -> { page++; showEvent(); });
        button("返回步骤", this::finish);
    }

    private int boundary(int value) {
        return value > 0 && value < eventDetail.length() && Character.isLowSurrogate(eventDetail.charAt(value)) ? value - 1 : value;
    }

    private static String readable(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return raw;
        try { return raw.substring(0, start) + new JSONObject(raw.substring(start)).toString(2); }
        catch (Exception ignored) { return raw; }
    }

    private static String providerReasoning(String raw) {
        int start = raw.indexOf('{'); if (start < 0) return "";
        try {
            JSONObject message = new JSONObject(raw.substring(start)).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            Object reasoning = message.opt("reasoning_content");
            return reasoning instanceof String ? (String) reasoning : "";
        } catch (Exception ignored) { return ""; }
    }

    private void export(String id) {
        exportingId = id;
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "YAWAC-debug-" + id + ".json"), EXPORT);
        } catch (ActivityNotFoundException e) { toast("系统未提供文件保存界面，可通过 ADB 导出，见项目调试文档"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CONFIRM_CLEAR && result == RESULT_OK) showSessions();
        if (request == EXPORT && result == Activity.RESULT_OK && data != null && data.getData() != null && exportingId != null) {
            store.export(exportingId, data.getData(), ok -> { if (!isDestroyed()) toast(ok ? "调试记录已导出" : "导出失败，记录可能已被清理"); });
        }
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(getColor(bold ? R.color.text_primary : R.color.text_secondary));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        Typography.body(view, true); content.addView(view, params(-2)); return view;
    }

    private void button(String title, Runnable action) {
        TextView original = new TextView(this); original.setText(title); original.setTextSize(13);
        original.setGravity(Gravity.CENTER); original.setMinHeight(dp(42)); original.setPadding(dp(8), dp(8), dp(8), dp(8));
        original.setTextColor(getColor(R.color.white)); content.addView(original, params(-2));
        original.setBackgroundResource(R.drawable.bg_surface_card);
        original.setId(View.generateViewId());
        int id = original.getId();
        NativeUi.shapeButton(original, getColor(R.color.brand_surface));
        TextView button = content.findViewById(id);
        Typography.apply(button, this);
        button.setMinHeight(dp(42)); button.setPadding(dp(8), dp(8), dp(8), dp(8)); button.setFocusable(true);
        button.setTextColor(getColor(R.color.white)); button.setTextSize(13);
        button.setOnClickListener(v -> action.run());
    }

    private LinearLayout.LayoutParams params(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height > 0 ? dp(height) : height);
        params.topMargin = dp(10); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private static String time(long value) { return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(value)); }
    private static String purpose(JSONObject item) { return "title".equals(item.optString("purpose")) ? "自动标题" : "对话"; }
    private static String status(JSONObject item) {
        switch (item.optString("status")) {
            case "success": return "已完成";
            case "error": return "失败";
            case "cancelled": return "已停止";
            case "truncated": return "回答被服务截断";
            default: return "进行中 / 中途停止采集";
        }
    }
    private static long lastTime(JSONObject doc) {
        JSONArray events = doc.optJSONArray("events");
        JSONObject last = events == null ? null : events.optJSONObject(events.length() - 1);
        return last == null ? 0 : last.optLong("atMs");
    }
}
