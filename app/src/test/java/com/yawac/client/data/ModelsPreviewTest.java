package com.yawac.client.data;

import org.junit.Test;
import static org.junit.Assert.*;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;

public class ModelsPreviewTest {
    private String preview(String content) {
        Chat chat = new Chat("preview", "preview"); chat.messages.add(new Message("assistant", content, 0)); return chat.preview();
    }
    @Test public void stripsMarkupAndCollapsesWhitespace() {
        assertEquals("标题 第一项 code", preview("  ## 标题\n\t* 第一项 | `code`  "));
        assertEquals("", preview(" \n\t# | * `"));
        assertEquals("", preview(null));
    }
    @Test public void boundsLongPreviewsWithoutCuttingEmoji() {
        assertEquals("123456789012345678901234…", preview("12345678901234567890123456789"));
        String result = preview("😀".repeat(30));
        assertEquals(25, result.codePointCount(0, result.length()));
        assertEquals("😀".repeat(24) + "…", result);
    }
    @Test public void pendingMessagesKeepTheirExistingPreview() {
        Chat chat = new Chat("pending", "pending"); Message message = new Message("assistant", "", 0);
        message.streaming = true; chat.messages.add(message); assertEquals("深度思考中…", chat.preview());
        message.content = "部分内容"; assertEquals("正在生成…", chat.preview());
    }
}
