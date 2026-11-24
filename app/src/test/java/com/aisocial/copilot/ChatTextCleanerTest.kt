package com.aisocial.copilot

import com.aisocial.copilot.core.ChatTextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full-conversation crop is full of chrome. The cleaner must drop the noise
 * without ever eating a real message.
 */
class ChatTextCleanerTest {

    @Test
    fun `drops timestamps thinking placeholders and button labels`() {
        val raw = """
            10:52
            你这是？
            (思考0秒
            才醒？
            发送
            嗯呢
            取消
        """.trimIndent()

        val cleaned = ChatTextCleaner.clean(raw)

        assertEquals("你这是？\n才醒？\n嗯呢", cleaned)
    }

    @Test
    fun `keeps short but meaningful messages`() {
        val raw = "嗯\n哦\n好\n哈哈哈哈"

        val cleaned = ChatTextCleaner.clean(raw)

        assertTrue(cleaned.contains("嗯"))
        assertTrue(cleaned.contains("哦"))
        assertTrue(cleaned.contains("哈哈哈哈"))
    }

    @Test
    fun `drops lines that are only digits or punctuation`() {
        val raw = "1\n。。。\n---\n？？\n你在吗"

        val cleaned = ChatTextCleaner.clean(raw)

        assertEquals("你在吗", cleaned)
    }

    @Test
    fun `collapses duplicated lines from overlapping OCR blocks`() {
        val raw = "才醒？\n才醒？\n才醒？\n嗯呢"

        val cleaned = ChatTextCleaner.clean(raw)

        assertEquals("才醒？\n嗯呢", cleaned)
    }

    @Test
    fun `keeps non-dialogue body text so the model can decide`() {
        // Map labels look like chat lines; we keep them and let the prompt say "ignore noise".
        val cleaned = ChatTextCleaner.clean("香港国际机场\n大榄郊野公园")

        assertTrue(cleaned.contains("香港国际机场"))
    }

    @Test
    fun `long conversations keep the tail and say what was dropped`() {
        val long = (1..400).joinToString("\n") { "第${it}句：这是一段比较长的聊天内容" }

        val cleaned = ChatTextCleaner.clean(long, maxChars = 500)

        assertTrue(cleaned.startsWith("（前面约"))
        assertTrue(cleaned.contains("已省略"))
        assertTrue(cleaned.length < 700)
        assertTrue(cleaned.contains("第400句"))
        assertFalse(cleaned.contains("第1句"))
    }

    @Test
    fun `empty and whitespace input produce empty output`() {
        assertEquals("", ChatTextCleaner.clean(""))
        assertEquals("", ChatTextCleaner.clean("   \n\n \t "))
    }
}
