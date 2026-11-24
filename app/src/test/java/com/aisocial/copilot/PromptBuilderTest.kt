package com.aisocial.copilot

import com.aisocial.copilot.ai.PromptBuilder
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ReplyLength
import com.aisocial.copilot.domain.model.ReplyOp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompt is what makes "persona actually changes the output" true. */
class PromptBuilderTest {

    private val persona = Persona(
        id = 7L,
        name = "直球老哥",
        description = "坦率直接，不绕弯子",
        tone = "干脆利落",
        length = ReplyLength.SHORT,
        emotion = 1,
        initiative = 5,
        humor = 2,
        catchphrases = "直说吧；我想",
        forbidden = "绝对不要用感叹号",
        customInstruction = "每条不超过两句",
    )

    @Test
    fun `system prompt carries every persona field`() {
        val prompt = PromptBuilder.systemForReplies(persona, "想约她周末看电影")

        assertTrue(prompt.contains("直球老哥"))
        assertTrue(prompt.contains("坦率直接，不绕弯子"))
        assertTrue(prompt.contains("干脆利落"))
        assertTrue(prompt.contains("简短"))
        assertTrue(prompt.contains("情绪表达程度：1/5"))
        assertTrue(prompt.contains("主动程度：5/5"))
        assertTrue(prompt.contains("幽默程度：2/5"))
        assertTrue(prompt.contains("直说吧；我想"))
        assertTrue(prompt.contains("绝对不要用感叹号"))
        assertTrue(prompt.contains("每条不超过两句"))
        assertTrue(prompt.contains("想约她周末看电影"))
    }

    @Test
    fun `system prompt always demands three distinct JSON replies`() {
        val prompt = PromptBuilder.systemForReplies(persona, "")

        assertTrue(prompt.contains("\"analysis\""))
        assertTrue(prompt.contains("\"tone\""))
        assertTrue(prompt.contains("\"strategy\""))
        assertTrue(prompt.contains("\"replies\""))
        assertTrue(prompt.contains("自然日常"))
        assertTrue(prompt.contains("情绪回应"))
        assertTrue(prompt.contains("人设风格"))
        assertTrue(prompt.contains("只输出 JSON"))
    }

    @Test
    fun `blank intent falls back to a default goal`() {
        val prompt = PromptBuilder.systemForReplies(persona, "   ")

        assertTrue(prompt.contains("未特别说明"))
    }

    @Test
    fun `user prompt embeds the chat content and flags images`() {
        val withImages = PromptBuilder.userForReplies("她说：在吗", hasImages = true)
        assertTrue(withImages.contains("她说：在吗"))
        assertTrue(withImages.contains("截图"))

        val imageOnly = PromptBuilder.userForReplies("", hasImages = true)
        assertTrue(imageOnly.contains("截图"))

        val textOnly = PromptBuilder.userForReplies("她说：在吗", hasImages = false)
        assertFalse(textOnly.contains("附带的图片"))
    }

    @Test
    fun `transform prompt keeps the original meaning and carries the op instruction`() {
        val system = PromptBuilder.systemForTransform(persona)
        val user = PromptBuilder.userForTransform("聊天背景", "我原本这句", ReplyOp.HUMOR)

        assertTrue(system.contains("直球老哥"))
        assertTrue(system.contains("\"replies\""))
        assertTrue(user.contains("我原本这句"))
        assertTrue(user.contains(ReplyOp.HUMOR.instruction))
        assertTrue(user.contains("聊天背景"))
    }

    @Test
    fun `extra replies prompt lists the replies that must not be reused`() {
        val user = PromptBuilder.userForExtraReplies(
            chatContent = "背景",
            original = "要回应的那句话",
            used = listOf("已用一", "已用二"),
        )

        assertTrue(user.contains("已用一"))
        assertTrue(user.contains("已用二"))
        assertTrue(user.contains("不要重复"))
    }

    // ------------------------------------------------------------ 人设书 (v1.1)

    @Test
    fun `persona book section is injected and declared to win`() {
        val book = "我叫小林，26 岁做设计。说话偏短句，不爱用表情包，跟女朋友聊天会故意装傻。"
        val prompt = PromptBuilder.systemForReplies(persona, "想约她周末看电影", book)

        assertTrue(prompt.contains("【人设书】"))
        assertTrue(prompt.contains("我叫小林，26 岁做设计"))
        assertTrue(prompt.contains("优先级最高"))
        assertTrue(prompt.contains("想约她周末看电影"))
    }

    @Test
    fun `no persona book means no book section at all`() {
        val prompt = PromptBuilder.systemForReplies(persona, "")

        assertFalse(prompt.contains("【人设书】"))
        assertFalse(prompt.contains("优先级最高"))
    }

    @Test
    fun `blank persona book is treated as absent`() {
        val prompt = PromptBuilder.systemForReplies(persona, "", "   \n  ")

        assertFalse(prompt.contains("【人设书】"))
    }

    @Test
    fun `persona book is carried into transform and extra-reply prompts`() {
        val book = "我说话很直接，不喜欢绕弯子"

        assertTrue(PromptBuilder.systemForTransform(persona, book).contains(book))
        assertTrue(PromptBuilder.systemForExtraReplies(persona, book).contains(book))
        assertFalse(PromptBuilder.systemForTransform(persona).contains("【人设书】"))
    }

    @Test
    fun `very long persona books are truncated instead of blowing the prompt up`() {
        val huge = "啊".repeat(9000)
        val prompt = PromptBuilder.systemForReplies(persona, "", huge)

        assertTrue(prompt.contains("【人设书】"))
        // section body is capped at 4000 chars
        assertTrue(prompt.length < 12000)
    }

    // ------------------------------------------------- 整段上下文理解 (v1.2)

    @Test
    fun `system prompt tells the model to rebuild the conversation first`() {
        val prompt = PromptBuilder.systemForReplies(persona, "")

        assertTrue(prompt.contains("还原成一段真实对话"))
        assertTrue(prompt.contains("判断每句是对方说的、还是我说的"))
        assertTrue(prompt.contains("默认是对方最后一条消息"))
        assertTrue(prompt.contains("前面更早的消息只当背景"))
    }

    @Test
    fun `system prompt prefers the partner's last message over my own`() {
        val prompt = PromptBuilder.systemForReplies(persona, "")

        assertTrue(prompt.contains("找出「需要我回应的那一句」"))
        assertTrue(prompt.contains("只有当对方最后一条之后全是我自己说的话"))
        assertTrue(prompt.contains("才改成补充我最后那一句"))
    }

    @Test
    fun `system prompt handles a screenshot cut off mid-sentence`() {
        val prompt = PromptBuilder.systemForReplies(persona, "")

        assertTrue(prompt.contains("截图底部看起来被截断了"))
        assertTrue(prompt.contains("不要脑补缺失部分"))
    }

    @Test
    fun `system prompt tells the model to ignore OCR noise`() {
        val prompt = PromptBuilder.systemForReplies(persona, "")

        assertTrue(prompt.contains("忽略与对话无关的噪声"))
        assertTrue(prompt.contains("时间戳"))
        assertTrue(prompt.contains("地图地名"))
    }

    @Test
    fun `output contract asks for replying_to`() {
        val prompt = PromptBuilder.systemForReplies(persona, "")

        assertTrue(prompt.contains("\"replying_to\""))
        assertTrue(prompt.contains("你判断要回应的那条消息原文"))
    }

    @Test
    fun `user prompt marks the OCR block and warns about noise`() {
        val prompt = PromptBuilder.userForReplies("你这是？\n才醒？", hasImages = false)

        assertTrue(prompt.contains("----- 识别内容开始 -----"))
        assertTrue(prompt.contains("----- 识别内容结束 -----"))
        assertTrue(prompt.contains("整块"))
        assertTrue(prompt.contains("好几轮上下文"))
        assertTrue(prompt.contains("你这是？\n才醒？"))
    }

    @Test
    fun `user prompt mentions the screenshot can correct OCR mistakes`() {
        val prompt = PromptBuilder.userForReplies("才醒？", hasImages = true)

        assertTrue(prompt.contains("校正 OCR 错字"))
        assertTrue(prompt.contains("谁是谁"))
    }
}
