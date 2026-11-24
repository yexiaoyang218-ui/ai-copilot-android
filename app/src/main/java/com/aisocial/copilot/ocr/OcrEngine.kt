package com.aisocial.copilot.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.aisocial.copilot.core.ChatTextCleaner
import com.aisocial.copilot.core.NetResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Fully on-device OCR (ML Kit, bundled Chinese + Latin model).
 * Nothing leaves the device at this stage.
 *
 * v1.2.1：不再只取 `result.text`，而是**逐行读取 boundingBox**，用气泡的水平位置
 * 标注说话人 —— 微信 / QQ / Telegram 里「我的」靠右、「对方」靠左，这是截图里最可靠
 * 的判据。标注后的文本以 `我：` / `对方：` 开头，模型不再靠猜。
 */
class OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): NetResult<String> = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val width = bitmap.width.coerceAtLeast(1)
        suspendCancellableCoroutine<NetResult<String>> { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = layoutAware(result.textBlocks, width)
                    if (text.isBlank()) {
                        continuation.resume(NetResult.Failure("没有识别到文字。可以放大截图或重新框选后再试。"))
                    } else {
                        continuation.resume(NetResult.Success(text))
                    }
                }
                .addOnFailureListener { error ->
                    continuation.resume(
                        NetResult.Failure("本地 OCR 失败：${error.message ?: "未知错误"}")
                    )
                }
        }
    }

    /** 逐行清洗 + 按水平位置标注说话人。 */
    private fun layoutAware(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>, width: Int): String {
        val kept = ArrayList<String>(48)
        for (block in blocks) {
            for (line in block.lines) {
                val raw = line.text
                if (ChatTextCleaner.isNoise(raw)) continue
                val text = raw.trim().replace('\u00A0', ' ')
                if (text.isEmpty()) continue
                if (kept.isNotEmpty() && kept.last().endsWith(text)) continue
                kept += speakerTag(line.boundingBox, width) + text
            }
        }
        if (kept.isEmpty()) return ""
        var joined = kept.joinToString("\n")
        if (joined.length > MAX_CHARS) {
            val dropped = joined.length - MAX_CHARS
            joined = "（前面约 $dropped 字是更早的聊天，已省略；以下是最近的部分）\n" + joined.takeLast(MAX_CHARS)
        }
        return joined
    }

    /**
     * 判定一行属于谁：
     *  - 右对齐（右边缘贴近画面右侧）→ 我
     *  - 左对齐（左边缘贴近画面左侧）→ 对方
     *  - 跨中或两侧都留白较多 → 系统/居中提示，用「·」保持中立
     */
    private fun speakerTag(box: Rect?, width: Int): String {
        if (box == null || box.width() <= 0) return "·"
        val left = box.left.toFloat() / width
        val right = box.right.toFloat() / width
        val center = (left + right) / 2f
        // 头像列会占掉两侧一部分，所以用较宽松的阈值
        val rightAligned = right >= 0.90f && left >= 0.20f
        val leftAligned = left <= 0.10f || (left <= 0.22f && center < 0.45f)
        return when {
            rightAligned -> "我："
            leftAligned -> "对方："
            center in 0.40f..0.60f && box.width() < width * 0.55f -> "·"
            right >= 0.72f -> "我："
            left <= 0.30f -> "对方："
            else -> "·"
        }
    }

    private companion object {
        const val MAX_CHARS = 3600
    }
}
