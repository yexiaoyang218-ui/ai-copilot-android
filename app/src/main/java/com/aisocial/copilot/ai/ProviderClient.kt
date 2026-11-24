package com.aisocial.copilot.ai

import com.aisocial.copilot.core.NetResult
import com.aisocial.copilot.domain.model.ProviderConfig
import com.aisocial.copilot.domain.model.ProviderKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/**
 * Translates one [AiRequest] into each vendor's wire format.
 *
 * Three protocols cover every provider in the MVP:
 *  - OPENAI  -> POST {base}/chat/completions   (OpenAI, DeepSeek, any OpenAI-compatible endpoint)
 *  - GEMINI  -> POST {base}/models/{m}:generateContent
 *  - CLAUDE  -> POST {base}/messages
 */
internal class ProviderClient(private val http: HttpCore) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun complete(
        config: ProviderConfig,
        model: String,
        apiKey: String,
        request: AiRequest,
        timeoutSeconds: Int,
        baseUrlOverride: String? = null,
    ): NetResult<String> {
        val baseUrl = (baseUrlOverride ?: config.baseUrl).trim()
        if (baseUrl.isBlank()) {
            return NetResult.Failure("${config.displayName} 还没有填写 Base URL。")
        }
        if (apiKey.isBlank()) {
            return NetResult.Failure("${config.displayName} 还没有配置 API Key。")
        }
        val call = try {
            when (config.kind) {
                ProviderKind.OPENAI -> openAiCall(baseUrl, config, model, apiKey, request)
                ProviderKind.GEMINI -> geminiCall(baseUrl, model, apiKey, request)
                ProviderKind.CLAUDE -> claudeCall(baseUrl, model, apiKey, request)
            }
        } catch (error: Throwable) {
            return NetResult.Failure("构建请求失败：${error.message ?: "未知错误"}", error.toString())
        }

        return try {
            when (val response = http.post(call, timeoutSeconds)) {
                is NetResult.Failure -> response
                is NetResult.Success -> extractText(config.kind, response.value)
            }
        } catch (error: Throwable) {
            NetResult.Failure("调用模型时出错：${error.message ?: "未知错误"}", error.toString())
        }
    }

    // ---------------------------------------------------------------- OpenAI

    private fun openAiCall(
        baseUrl: String,
        config: ProviderConfig,
        model: String,
        apiKey: String,
        request: AiRequest,
    ): WireCall {
        val userContent: JsonElement = if (request.images.isEmpty()) {
            JsonPrimitive(request.user)
        } else {
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", request.user)
                })
                request.images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:${image.mimeType};base64,${image.base64}")
                        })
                    })
                }
            }
        }

        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            put("stream", false)
        }

        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $apiKey",
        )

        return WireCall(
            url = endpoint(baseUrl, "chat/completions"),
            headers = headers,
            body = payload.toString(),
        )
    }

    // ---------------------------------------------------------------- Gemini

    private fun geminiCall(
        baseUrl: String,
        model: String,
        apiKey: String,
        request: AiRequest,
    ): WireCall {
        val parts = buildJsonArray {
            if (request.user.isNotBlank()) {
                add(buildJsonObject { put("text", request.user) })
            }
            request.images.forEach { image ->
                add(buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", image.mimeType)
                        put("data", image.base64)
                    })
                })
            }
        }

        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", request.system) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", parts)
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", request.temperature)
                put("maxOutputTokens", request.maxTokens)
            })
        }

        val url = "${baseUrl.trimEnd('/')}/models/$model:generateContent"

        return WireCall(
            // The key travels in a header so it never lands in a URL or a log line.
            url = url,
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-goog-api-key" to apiKey,
            ),
            body = payload.toString(),
        )
    }

    // ---------------------------------------------------------------- Claude

    private fun claudeCall(
        baseUrl: String,
        model: String,
        apiKey: String,
        request: AiRequest,
    ): WireCall {
        val content = buildJsonArray {
            request.images.forEach { image ->
                add(buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", image.mimeType)
                        put("data", image.base64)
                    })
                })
            }
            if (request.user.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", request.user)
                })
            }
        }

        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            put("system", request.system)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", content)
                })
            })
        }

        return WireCall(
            url = endpoint(baseUrl, "messages"),
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            body = payload.toString(),
        )
    }

    /**
     * Asks the provider which models this API key can actually use.
     * This is what lets the picker show real model IDs (e.g. a brand new
     * `deepseek-v4.1-flash`) instead of a hardcoded list.
     */
    fun listModels(
        config: ProviderConfig,
        apiKey: String,
        timeoutSeconds: Int,
        baseUrlOverride: String? = null,
    ): NetResult<List<String>> {
        val baseUrl = (baseUrlOverride ?: config.baseUrl).trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return NetResult.Failure("${config.displayName} 还没有填写 Base URL。")
        }
        if (apiKey.isBlank()) {
            return NetResult.Failure("请先填写 API Key，再获取模型列表。")
        }

        val headers = mapOf(
            "Content-Type" to "application/json",
        ) + when (config.kind) {
            ProviderKind.OPENAI -> mapOf("Authorization" to "Bearer $apiKey")
            ProviderKind.CLAUDE -> mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            )

            // Header, not query parameter, so the key never lands in a URL or a log.
            ProviderKind.GEMINI -> mapOf("x-goog-api-key" to apiKey)
        }

        val url = when (config.kind) {
            ProviderKind.GEMINI -> "$baseUrl/models"
            else -> endpoint(baseUrl, "models")
        }

        return when (val raw = http.get(url, headers, timeoutSeconds)) {
            is NetResult.Failure -> raw
            is NetResult.Success -> {
                val ids = parseModelIds(config.kind, raw.value)
                if (ids.isEmpty()) {
                    NetResult.Failure(
                        "接口没有返回可用的聊天模型列表。",
                        raw.value.take(300)
                    )
                } else {
                    NetResult.Success(ids)
                }
            }
        }
    }

    private fun parseModelIds(kind: ProviderKind, body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()

        val ids: List<String?> = when (kind) {
            ProviderKind.GEMINI -> {
                val array = root["models"] as? JsonArray ?: return emptyList()
                array.map { item ->
                    val obj = item as? JsonObject ?: return@map null
                    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@map null
                    val methods = obj["supportedGenerationMethods"] as? JsonArray
                    val canChat = methods == null || methods.any {
                        (it as? JsonPrimitive)?.contentOrNull == "generateContent"
                    }
                    if (canChat) name.removePrefix("models/") else null
                }
            }

            else -> {
                val array = (root["data"] as? JsonArray)
                    ?: (root["models"] as? JsonArray)
                    ?: return emptyList()
                array.map { item ->
                    ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                }
            }
        }

        return ids
            .mapNotNull { it?.trim() }
            .filter { it.isNotBlank() && isChatModel(kind, it) }
            .distinct()
            .take(80)
    }

    /** Drops embedding / TTS / image endpoints that a chat picker should not offer. */
    private fun isChatModel(kind: ProviderKind, id: String): Boolean {
        val lower = id.lowercase()
        if (kind == ProviderKind.GEMINI) {
            return lower.startsWith("gemini") || lower.startsWith("gemma")
        }
        val blocked = listOf(
            "embedding", "embed", "tts", "whisper", "dall-e", "moderation",
            "audio", "realtime", "transcribe", "image", "sora", "rerank",
            "text-embedding", "aqa", "imagen", "veo", "bge-",
        )
        return blocked.none { lower.contains(it) }
    }

    // -------------------------------------------------------------- response

    private fun extractText(kind: ProviderKind, body: String): NetResult<String> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return NetResult.Failure("模型返回的内容不是合法 JSON。", body.take(400))

        val text = runCatching {
            when (kind) {
                ProviderKind.OPENAI -> openAiText(root)
                ProviderKind.GEMINI -> geminiText(root)
                ProviderKind.CLAUDE -> claudeText(root)
            }
        }.getOrNull()

        return if (text.isNullOrBlank()) {
            NetResult.Failure(
                "模型没有返回可用文本（可能被安全策略拦截或额度耗尽）。",
                root.toString().take(500)
            )
        } else {
            NetResult.Success(text)
        }
    }

    private fun openAiText(root: JsonObject): String? {
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val message = choice["message"] as? JsonObject
        val fromMessage = readContent(message?.get("content"))
        if (!fromMessage.isNullOrBlank()) return fromMessage
        // Reasoning models (e.g. deepseek-reasoner) may only fill reasoning_content.
        val reasoning = (message?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull
        if (!reasoning.isNullOrBlank()) return reasoning
        return (choice["text"] as? JsonPrimitive)?.contentOrNull
    }

    private fun readContent(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonArray -> element.mapNotNull { part ->
            when (part) {
                is JsonObject -> (part["text"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> part.contentOrNull
                else -> null
            }
        }.joinToString("")

        is JsonPrimitive -> element.contentOrNull
        else -> null
    }

    private fun geminiText(root: JsonObject): String? {
        val blocked = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)
            ?.contentOrNull
        if (!blocked.isNullOrBlank()) return null
        val candidates = root["candidates"] as? JsonArray ?: return null
        val parts = (((candidates.firstOrNull() as? JsonObject)
            ?.get("content") as? JsonObject)
            ?.get("parts")) as? JsonArray ?: return null
        return parts.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("")
    }

    private fun claudeText(root: JsonObject): String? {
        val content = root["content"] as? JsonArray ?: return null
        return content.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            } else null
        }.joinToString("")
    }

    private fun endpoint(base: String, path: String): String {
        val trimmed = base.trim().trimEnd('/')
        val suffix = path.trimStart('/')
        return if (trimmed.endsWith("/$suffix")) trimmed else "$trimmed/$suffix"
    }
}
