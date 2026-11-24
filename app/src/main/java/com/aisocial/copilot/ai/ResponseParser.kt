package com.aisocial.copilot.ai

import com.aisocial.copilot.domain.model.AnalysisResult
import com.aisocial.copilot.domain.model.ReplySuggestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Defensive parser for model output. Models occasionally wrap JSON in code fences,
 * add prose, or fall back to plain text — none of that should crash the app.
 */
object ResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseAnalysis(raw: String): AnalysisResult {
        val obj = extractObject(raw)
        if (obj != null) {
            val replies = parseReplyArray(obj["replies"])
            if (replies.isNotEmpty()) {
                val stamp = System.nanoTime()
                return AnalysisResult(
                    analysis = obj.string("analysis"),
                    tone = obj.string("tone"),
                    strategy = obj.string("strategy"),
                    replyingTo = obj.string("replying_to").ifBlank {
                        obj.string("reply_to").ifBlank { obj.string("target") }
                    },
                    replies = replies.mapIndexed { index, pair ->
                        ReplySuggestion(id = "r$stamp-$index", label = pair.first, text = pair.second)
                    }
                )
            }
        }
        val stamp = System.nanoTime()
        val fallback = fallbackLines(raw)
        return AnalysisResult(
            replies = fallback.mapIndexed { index, text ->
                ReplySuggestion(id = "f$stamp-$index", label = defaultLabel(index), text = text)
            }
        )
    }

    fun parseReplyList(raw: String): List<ReplySuggestion> {
        val stamp = System.nanoTime()
        val obj = extractObject(raw)
        if (obj != null) {
            val replies = parseReplyArray(obj["replies"] ?: obj["data"])
            if (replies.isNotEmpty()) {
                return replies.mapIndexed { index, pair ->
                    ReplySuggestion(id = "t$stamp-$index", label = pair.first, text = pair.second)
                }
            }
        }
        return fallbackLines(raw).mapIndexed { index, text ->
            ReplySuggestion(id = "u$stamp-$index", label = "改写 ${index + 1}", text = text)
        }
    }

    /** Tolerant JSON extraction: strips code fences and any leading/trailing prose. */
    private fun extractObject(raw: String): JsonObject? {
        val cleaned = stripFences(raw)
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        val end = cleaned.lastIndexOf('}')
        if (end <= start) return null
        val candidate = cleaned.substring(start, end + 1)
        return runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull()
    }

    private fun stripFences(raw: String): String =
        raw.replace("```json", " ")
            .replace("```JSON", " ")
            .replace("```", " ")

    private fun parseReplyArray(element: JsonElement?): List<Pair<String, String>> {
        val array = element as? JsonArray ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        array.forEachIndexed { index, item ->
            val obj = item as? JsonObject ?: return@forEachIndexed
            val text = obj.string("text").ifBlank {
                obj.string("content").ifBlank { obj.string("reply").ifBlank { obj.string("value") } }
            }
            if (text.isNotBlank()) {
                val label = obj.string("label").ifBlank { obj.string("style").ifBlank { obj.string("name") } }
                out += (label.ifBlank { defaultLabel(index) }) to text
            }
        }
        return out
    }

    private fun JsonObject.string(key: String): String {
        val element = this[key] ?: return ""
        return when (element) {
            is JsonArray -> element
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .joinToString("")
                .trim()

            is JsonPrimitive -> element.contentOrNull.orEmpty().trim()
            else -> ""
        }
    }

    private fun fallbackLines(raw: String): List<String> {
        val cleaned = stripFences(raw).trim()
        if (cleaned.isEmpty()) return emptyList()
        val candidates = mutableListOf<String>()
        for (line in cleaned.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("{") || trimmed.startsWith("}") ||
                trimmed.startsWith("[") || trimmed.startsWith("]") ||
                trimmed.contains("\":")
            ) continue
            val stripped = trimmed
                .removePrefix("- ")
                .removePrefix("* ")
                .replace(Regex("^\\d+[.、)]\\s*"), "")
                .trim()
                .trim('"', '“', '”', '\'')
            if (stripped.length >= 2) candidates += stripped
            if (candidates.size >= 3) break
        }
        if (candidates.isNotEmpty()) return candidates
        return listOf(cleaned.replace(Regex("\\s+"), " ").take(160))
    }

    private fun defaultLabel(index: Int): String = when (index) {
        0 -> "自然日常"
        1 -> "情绪回应"
        2 -> "人设风格"
        else -> "方案 ${index + 1}"
    }
}
