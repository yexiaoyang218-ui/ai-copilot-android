package com.aisocial.copilot.core

/**
 * Cleans up raw OCR output from a chat screenshot before it reaches the prompt.
 *
 * A full-conversation crop contains a lot that is not dialogue: timestamps, the
 * "思考 0 秒" placeholder, button labels, and repeated lines from overlapping
 * text blocks. None of that helps the model and some of it actively misleads it,
 * so the obvious noise is dropped here. Anything ambiguous is kept — the model is
 * told to ignore non-conversation text, and silently deleting real messages would
 * be worse than leaving a little noise in.
 */
object ChatTextCleaner {

    private val timestampOnly = Regex("""^\d{1,2}[:：]\d{2}(:\d{2})?$""")
    private val dateOnly = Regex("""^(\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?|\d{1,2}月\d{1,2}日)$""")
    private val thinkingPlaceholder = Regex("""[（(]?\s*思考\s*\d+\s*秒\s*[)）]?""")
    private val onlyDigitsOrSymbols = Regex("""^[\d\s\p{P}\p{S}]+$""")

    /** Labels of chat-app chrome that OCR loves to pick up. */
    private val uiLabels = setOf(
        "发送", "取消", "返回", "确定", "完成", "关闭", "更多", "表情", "图片", "拍照",
        "语音", "红包", "转账", "位置", "文件", "相册", "拍摄", "按住说话", "按住 说话",
        "长按说话", "语音通话", "视频通话", "已读", "未读", "消息", "撤回", "复制",
        "回复", "转发", "删除", "搜索", "在线", "离线", "对方正在输入...",
    )

    /** 这一行是不是纯噪声（时间戳 / 按钮 / 纯符号）。OcrEngine 逐行标注时复用。 */
    fun isNoise(line: String): Boolean {
        val text = line.trim().replace('\u00A0', ' ')
        if (text.isEmpty()) return true
        if (timestampOnly.matches(text)) return true
        if (dateOnly.matches(text)) return true
        if (text.length <= 14 && thinkingPlaceholder.containsMatchIn(text)) return true
        if (uiLabels.contains(text)) return true
        if (onlyDigitsOrSymbols.matches(text)) return true
        return false
    }

    fun clean(raw: String, maxChars: Int = MAX_CHARS): String {
        val kept = ArrayList<String>(32)

        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim().replace('\u00A0', ' ')
            if (line.isEmpty()) continue
            if (timestampOnly.matches(line)) continue
            if (dateOnly.matches(line)) continue
            if (line.length <= 14 && thinkingPlaceholder.containsMatchIn(line)) continue
            if (uiLabels.contains(line)) continue
            if (onlyDigitsOrSymbols.matches(line)) continue
            // OCR of overlapping blocks happily repeats the same line.
            if (kept.isNotEmpty() && kept.last() == line) continue
            kept += line
        }

        val joined = kept.joinToString("\n")
        if (joined.length <= maxChars) return joined

        // Keep the tail: the most recent messages are what we actually reply to.
        val dropped = joined.length - maxChars
        return "（前面约 $dropped 字是更早的聊天，已省略；以下是最近的部分）\n" +
            joined.takeLast(maxChars)
    }

    private const val MAX_CHARS = 3600
}
