package com.yawac.client.ui;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.yawac.client.R;
import com.yawac.client.data.ChatRepository;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;

import io.noties.markwon.ext.tables.TableAwareMovementMethod;

public class ReadingActivity extends SwipeBackActivity {
    public static final String EXTRA_MESSAGE_ID = "message_id";
    private TextView content, fontSize, smaller, larger;
    private String text;
    private int size;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
        setContentView(R.layout.activity_reading);
        ((SwipeBackLayout) findViewById(R.id.swipeBack)).setOnSwipeFinish(this::finish);
        Chat chat = ChatRepository.get(this).getChat(getIntent().getStringExtra(ChatActivity.EXTRA_CHAT_ID));
        String id = getIntent().getStringExtra(EXTRA_MESSAGE_ID);
        Message answer = null;
        if (chat != null) for (Message message : chat.messages) if (message.id.equals(id)) answer = message;
        if (answer == null) { finish(); return; }
        text = answer.content;
        content = findViewById(R.id.readingContent);
        Typography.body(content, true);
        content.setMovementMethod(TableAwareMovementMethod.create());
        fontSize = findViewById(R.id.fontSize);
        smaller = findViewById(R.id.fontSmaller);
        larger = findViewById(R.id.fontLarger);
        android.content.SharedPreferences preferences = getSharedPreferences("wearai_prefs", MODE_PRIVATE);
        // Preserve the previous reading size on upgrade, without carrying it into chat bubbles.
        size = Math.max(11, Math.min(19, preferences.getInt("reading_text_sp",
                preferences.getInt("message_text_sp", 13))));
        if (!preferences.contains("reading_text_sp")) preferences.edit().putInt("reading_text_sp", size).apply();
        smaller.setOnClickListener(v -> changeSize(-2));
        larger.setOnClickListener(v -> changeSize(2));
        render();
    }

    private void changeSize(int delta) {
        size = Math.max(11, Math.min(19, size + delta));
        getSharedPreferences("wearai_prefs", MODE_PRIVATE).edit().putInt("reading_text_sp", size).apply();
        render();
    }

    private void render() {
        content.setTextSize(size);
        Markdown.render(content, text);
        fontSize.setText(String.valueOf(size));
        smaller.setEnabled(size > 11);
        larger.setEnabled(size < 19);
        smaller.setAlpha(size > 11 ? 1f : 0.4f);
        larger.setAlpha(size < 19 ? 1f : 0.4f);
    }
}
