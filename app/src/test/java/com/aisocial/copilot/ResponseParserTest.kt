package com.aisocial.copilot

import com.aisocial.copilot.ai.ResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the one place where a badly behaved model can crash the app,
 * so it gets the most defensive coverage of anything in the project.
 */
class ResponseParserTest {

    @Test
    fun `parses a clean structured response`() {
        val raw = """
            {
              "analysis": "对方有点敷衍，可能刚下班累了",
              "tone": "轻松但略疲惫",
              "strategy": "先顺着气氛，不要追问",
              "replies": [
                {"label": "自然日常", "text": "那先歇着吧，等你有空再聊"},
                {"label": "情绪回应", "text": "听起来今天挺累的，早点休息"},
                {"label": "人设风格", "text": "行，那我先不吵你了"}
              ]
            }
        """.trimIndent()

        val result = ResponseParser.parseAnalysis(raw)

        assertEquals("对方有点敷衍，可能刚下班累了", result.analysis)
        assertEquals("轻松但略疲惫", result.tone)
        assertEquals("先顺着气氛，不要追问", result.strategy)
        assertEquals(3, result.replies.size)
        assertEquals("自然日常", result.replies[0].label)
        assertEquals("那先歇着吧，等你有空再聊", result.replies[0].text)
        assertTrue(result.hasAnalysis)
    }

    @Test
    fun `strips markdown fences and surrounding prose`() {
        val raw = """
            好的，下面是我的建议：
            ```json
            {"analysis":"a","tone":"b","strategy":"c","replies":[{"label":"x","text":"一"},{"label":"y","text":"二"}]}
            ```
            希望对你有帮助。
        """.trimIndent()

        val result = ResponseParser.parseAnalysis(raw)

        assertEquals(2, result.replies.size)
        assertEquals("一", result.replies[0].text)
        assertEquals("二", result.replies[1].text)
    }

    @Test
    fun `missing optional fields do not throw`() {
        val raw = """{"replies":[{"text":"只有文字没有标签"}]}"""

        val result = ResponseParser.parseAnalysis(raw)

        assertEquals(1, result.replies.size)
        assertEquals("只有文字没有标签", result.replies[0].text)
        assertTrue(result.replies[0].label.isNotBlank())
        assertFalse(result.hasAnalysis)
    }

    @Test
    fun `plain text falls back to line extraction`() {
        val raw = """
            1. 今天有点忙，晚点回你
            2. 我看到消息了，别急
            3. 明天再说吧
        """.trimIndent()

        val result = ResponseParser.parseAnalysis(raw)

        assertEquals(3, result.replies.size)
        assertEquals("今天有点忙，晚点回你", result.replies[0].text)
        assertEquals("明天再说吧", result.replies[2].text)
    }

    @Test
    fun `empty input yields no suggestions and does not throw`() {
        val result = ResponseParser.parseAnalysis("")

        assertTrue(result.replies.isEmpty())
    }

    @Test
    fun `reply list parser handles the transform shape`() {
        val raw = """{"replies":[{"label":"改写","text":"换个说法"}]}"""

        val replies = ResponseParser.parseReplyList(raw)

        assertEquals(1, replies.size)
        assertEquals("换个说法", replies[0].text)
    }

    @Test
    fun `reply list parser tolerates a bare array object`() {
        val raw = """{"data":[{"text":"A"},{"text":"B"},{"text":"C"}]}"""

        val replies = ResponseParser.parseReplyList(raw)

        assertEquals(3, replies.size)
        assertEquals("C", replies[2].text)
    }

    @Test
    fun `parses the replying_to field so the user can audit the context decision`() {
        val raw = """
            {"replying_to":"才醒？","analysis":"对方在问你是不是刚醒","tone":"随口一问",
             "strategy":"顺着答一句","replies":[{"label":"自然日常","text":"刚醒"}]}
        """.trimIndent()

        val result = ResponseParser.parseAnalysis(raw)

        assertEquals("才醒？", result.replyingTo)
        assertEquals("刚醒", result.replies[0].text)
        assertTrue(result.hasAnalysis)
    }

    @Test
    fun `accepts reply_to as an alias for replying_to`() {
        val raw = """{"reply_to":"你这是？","replies":[{"label":"x","text":"发错了"}]}"""

        assertEquals("你这是？", ResponseParser.parseAnalysis(raw).replyingTo)
    }

    @Test
    fun `missing replying_to is simply empty`() {
        val raw = """{"replies":[{"label":"x","text":"嗯"}]}"""

        assertEquals("", ResponseParser.parseAnalysis(raw).replyingTo)
    }
}
