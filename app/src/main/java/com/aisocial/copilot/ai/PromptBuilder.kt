package com.aisocial.copilot.ai

import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ReplyOp
import com.aisocial.copilot.domain.model.ReplyLength

/**
 * One place that turns (chat context + user intent + persona + constraints) into prompts.
 * Keeping this centralised is what makes "persona influences every generation" true.
 */
object PromptBuilder {

    /**
     * The user's own 人设书, injected above the template fields and declared to win
     * on conflict. Empty book = section omitted entirely.
     */
    private fun StringBuilder.appendPersonaBook(book: String) {
        if (book.isBlank()) return
        appendLine()
        appendLine("【人设书】（这是我自己写的「我是谁、我怎么说话」，优先级最高；和上面的人设模板冲突时以人设书为准）")
        appendLine(book.trim().take(4000))
    }

    fun systemForReplies(
        persona: Persona,
        intent: String,
        personaBook: String = "",
    ): String = buildString {
        appendLine("你是一位中文聊天回复助手，帮助「我」（用户本人）在微信、QQ、Telegram 等聊天软件里把话说得更好。")
        appendLine("你输出的是「我」可以直接复制发送的话。你不替用户聊天，也不会自动发送。")
        appendLine()
        appendLine("【我的人设】")
        appendLine("名称：${persona.name}")
        if (persona.description.isNotBlank()) appendLine("性格：${persona.description}")
        appendLine("语气：${persona.tone.ifBlank { "自然口语" }}")
        appendLine("回复长度：${persona.length.label}（${persona.length.hint}）")
        appendLine("情绪表达程度：${persona.emotion}/5（1=很克制，5=情绪外放）")
        appendLine("主动程度：${persona.initiative}/5（1=被动回应，5=主动推进）")
        appendLine("幽默程度：${persona.humor}/5")
        if (persona.catchphrases.isNotBlank()) appendLine("常用表达：${persona.catchphrases}")
        if (persona.forbidden.isNotBlank()) appendLine("绝对不要出现：${persona.forbidden}")
        if (persona.customInstruction.isNotBlank()) appendLine("补充要求：${persona.customInstruction}")
        appendPersonaBook(personaBook)
        appendLine()
        appendLine("【沟通目标 / 我想表达的原意】")
        appendLine(intent.ifBlank { "未特别说明。默认目标：自然、不卑不亢地回应，并让对话可以继续。" })
        appendLine()
        appendLine("【硬性规则】")
        appendLine("0. 截图文字已经按气泡位置标注了说话人：`我：`= 我发的，`对方：`= 对方发的，`·`= 系统/居中提示。")
        appendLine("   这是本机根据文字在画面里的左右位置自动判定的，**优先级最高，直接采信**；")
        appendLine("   只有当标注明显和上下文矛盾（例如连续两句都是我、但语气像对方在追问）才重新判断。")
        appendLine("   注意：一段消息被折成多行时，可能只有第一行带标注，后面的续行沿用上一条的说话人。")
        appendLine("1. 先把这段内容还原成一段真实对话：按时间顺序，判断每句是对方说的、还是我说的。")
        appendLine("   分不清就按上下文推断；实在无法确定时在 analysis 里写「不确定」，绝不编造。")
        appendLine("2. 忽略与对话无关的噪声：时间戳、按钮文字、地图地名、游戏界面、系统提示、图片里的字。")
        appendLine("3. 找出「需要我回应的那一句」：**默认是对方最后一条消息**，把它原样写进 replying_to。")
        appendLine("   只有当对方最后一条之后全是我自己说的话（对方还没回）时，才改成补充我最后那一句。")
        appendLine("4. 只回应这一句，不要逐条回答，也不要复述整段对话。前面更早的消息只当背景，")
        appendLine("   用来判断对方在追问什么、情绪是怎么变化的。")
        appendLine("5. 如果截图底部看起来被截断了（话说到一半），就按已经看到的内容判断，不要脑补缺失部分。")
        appendLine("6. 用简体中文口语，像真人在手机上打字。不要书面语、不要客服腔、不要堆砌敬语。")
        appendLine("7. 每条回复 1~3 句，不换行、不分点、不加标题、不加引号、不做解释。")
        appendLine("8. 严格贴合上面的原意。不要编造对方没说过的事，不要虚构事实、数字或承诺。")
        appendLine("9. 三条回复的风格必须明显不同，分别对应下面给的 label。")
        appendLine("10. 不要使用 emoji，最多一个。")
        appendLine("11. 不要在回复里提到自己是 AI，不要出现「作为AI」这类句式。")
        appendLine("12. 只输出 JSON，不要输出 markdown 代码块，不要输出任何额外文字。")
        appendLine()
        appendLine("【输出格式】严格按此结构输出：")
        appendLine("{")
        appendLine("  \"replying_to\": \"你判断要回应的那条消息原文，最多 30 字，不要改写\",")
        appendLine("  \"analysis\": \"一到两句：这段对话在聊什么、最后一句是谁发的、对方现在是什么状态或意图\",")
        appendLine("  \"tone\": \"一句话判断当前聊天语气，例如：轻松调侃 / 有点敷衍 / 明显不耐烦\",")
        appendLine("  \"strategy\": \"一到两句：这一轮建议怎么接，为什么\",")
        appendLine("  \"replies\": [")
        appendLine("    {\"label\": \"自然日常\", \"text\": \"...\"},")
        appendLine("    {\"label\": \"情绪回应\", \"text\": \"...\"},")
        appendLine("    {\"label\": \"人设风格\", \"text\": \"...\"}")
        appendLine("  ]")
        append("}")
    }

    fun userForReplies(chatContent: String, hasImages: Boolean): String = buildString {
        appendLine("【聊天内容】")
        appendLine("下面这段是从一张聊天截图里**整块**识别出来的文字，通常包含好几轮上下文，")
        appendLine("也可能混进时间戳、按钮文字、图片里的地名/游戏文字，以及少量识别错字。")
        appendLine("请先在心里还原：谁说了什么、按时间顺序、最后一句是谁发的，再决定怎么回。")
        appendLine()
        appendLine("----- 识别内容开始 -----")
        appendLine(
            if (chatContent.isBlank() && hasImages) "（没有识别到文字，请看图片）"
            else chatContent.trim().ifBlank { "（暂无）" }
        )
        appendLine("----- 识别内容结束 -----")
        appendLine()
        if (hasImages) {
            appendLine("同时附上了原始截图，可以用图片校正 OCR 错字、看清谁是谁、判断上下文顺序。")
        }
        appendLine("请按要求的 JSON 格式输出。")
    }

    fun systemForTransform(persona: Persona, personaBook: String = ""): String = buildString {
        appendLine("你是中文聊天表达润色助手。用户会给你一段「我」原本想说的话，以及一句改写要求。")
        appendLine("你只输出 JSON，不要输出任何额外文字，不要输出 markdown 代码块。")
        appendLine()
        appendLine("【我的人设】")
        appendLine("名称：${persona.name}")
        appendLine("语气：${persona.tone.ifBlank { "自然口语" }}")
        appendLine("回复长度：${persona.length.label}")
        appendLine("情绪表达：${persona.emotion}/5，主动程度：${persona.initiative}/5，幽默程度：${persona.humor}/5")
        if (persona.forbidden.isNotBlank()) appendLine("绝对不要出现：${persona.forbidden}")
        appendPersonaBook(personaBook)
        appendLine()
        appendLine("【规则】")
        appendLine("1. 保持原意不变，只改变表达方式。")
        appendLine("2. 口语化，1~2 句，不换行、不加引号、不加解释。")
        appendLine("3. 不编造任何事实。")
        appendLine("4. 输出 1~2 条候选。")
        appendLine()
        appendLine("【输出格式】")
        appendLine("{\"replies\":[{\"label\":\"改写\",\"text\":\"...\"}]}")
    }

    fun userForTransform(chatContent: String, original: String, op: ReplyOp): String = buildString {
        if (chatContent.isNotBlank()) {
            appendLine("【聊天背景】")
            appendLine(chatContent.trim().take(3000))
            appendLine()
        }
        appendLine("【我原本想说的】")
        appendLine(original.trim())
        appendLine()
        appendLine("【本次改写要求】${op.instruction}")
        appendLine("请输出 JSON。")
    }

    fun systemForExtraReplies(persona: Persona, personaBook: String = ""): String = buildString {
        appendLine("你是中文聊天回复助手，正在为用户补充更多候选回复。")
        appendLine("用户会给出聊天背景、原话和已经用过的回复，你需要给出角度完全不同的新回复。")
        appendLine("人设：${persona.name}，语气：${persona.tone.ifBlank { "自然口语" }}，长度：${persona.length.label}")
        if (persona.forbidden.isNotBlank()) appendLine("绝对不要出现：${persona.forbidden}")
        appendPersonaBook(personaBook)
        appendLine("规则：口语化、1~2 句、贴合原意、不编造事实、不要和已用回复重复。")
        appendLine("只输出 JSON，不要任何额外文字。输出格式：")
        appendLine("{\"replies\":[{\"label\":\"新方案\",\"text\":\"...\"}]}")
    }

    fun userForExtraReplies(chatContent: String, original: String, used: List<String>): String = buildString {
        if (chatContent.isNotBlank()) {
            appendLine("【聊天背景】")
            appendLine(chatContent.trim().take(3000))
            appendLine()
        }
        appendLine("【对方最后说的话 / 要回应的内容】")
        appendLine(original.trim())
        appendLine()
        appendLine("【已经用过的回复，不要重复】")
        appendLine(used.joinToString("\n") { "- $it" })
        appendLine()
        appendLine("请输出 3 条角度完全不同的新回复，JSON 格式。")
    }

    fun testUser(): String = "请只回复两个字：正常"

    fun replyLengthHint(length: ReplyLength): String = length.hint
}
