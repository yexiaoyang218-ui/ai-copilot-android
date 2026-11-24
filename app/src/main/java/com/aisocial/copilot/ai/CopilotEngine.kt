package com.aisocial.copilot.ai

import com.aisocial.copilot.core.NetResult
import com.aisocial.copilot.data.prefs.AppPreferences
import com.aisocial.copilot.data.prefs.SettingsRepository
import com.aisocial.copilot.data.repo.HistoryRepository
import com.aisocial.copilot.domain.model.AiSettings
import com.aisocial.copilot.domain.model.GenerateInput
import com.aisocial.copilot.domain.model.GenerationResult
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ProviderConfig
import com.aisocial.copilot.domain.model.ReplyOp
import com.aisocial.copilot.domain.model.ReplySuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use-case layer above the providers.
 *
 * Responsibilities:
 *  - pick the right model (vision vs text) for the task
 *  - run the primary provider, then the configured fallback
 *  - keep failures user-readable instead of throwing
 *  - record optional local history
 */
class CopilotEngine(
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
) {

    private val client = ProviderClient(HttpCore())

    suspend fun generate(input: GenerateInput): NetResult<GenerationResult> = withContext(Dispatchers.IO) {
        if (input.chatContent.isBlank() && input.images.isEmpty()) {
            return@withContext NetResult.Failure("先粘贴或输入聊天内容，或者附一张聊天截图。")
        }

        val prefs = settingsRepository.snapshot()
        val ai = prefs.ai

        val primary = ai.providers.firstOrNull { it.id == ai.activeProviderId && it.enabled }
            ?: ai.providers.firstOrNull { it.enabled }
            ?: return@withContext NetResult.Failure(
                "还没有可用的模型服务。请到「设置 → 模型服务」里启用一个并填写 API Key。"
            )

        val primaryKey = settingsRepository.apiKey(primary.id)
        if (primaryKey.isBlank()) {
            return@withContext NetResult.Failure(
                "${primary.displayName} 还没有配置 API Key。请到「设置 → 模型服务」里填写。"
            )
        }

        return@withContext when (val first = attempt(primary, primaryKey, input, ai)) {
            is NetResult.Success -> {
                record(prefs, input, first.value)
                first
            }

            is NetResult.Failure -> {
                val fallback = ai.fallbackProvider()
                val fallbackKey = if (fallback != null) {
                    settingsRepository.apiKey(fallback.id)
                } else {
                    ""
                }
                if (fallback == null || fallbackKey.isBlank()) {
                    first
                } else {
                    when (val second = attempt(fallback, fallbackKey, input, ai)) {
                        is NetResult.Success -> {
                            val value = second.value.copy(usedFallback = true)
                            record(prefs, input, value)
                            NetResult.Success(value)
                        }

                        is NetResult.Failure -> NetResult.Failure(
                            "${primary.displayName} 调用失败：${first.message}\n" +
                                "备用模型 ${fallback.displayName} 也失败了：${second.message}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun attempt(
        config: ProviderConfig,
        apiKey: String,
        input: GenerateInput,
        ai: AiSettings,
    ): NetResult<GenerationResult> {
        val wantsVision = input.images.isNotEmpty()
        val useVision = wantsVision && config.supportsVision
        val imagesDropped = wantsVision && !config.supportsVision
        val model = if (useVision) config.visionModel.ifBlank { config.model } else config.model
        if (model.isBlank()) {
            return NetResult.Failure("${config.displayName} 还没有填写模型 ID。")
        }

        val request = AiRequest(
            system = PromptBuilder.systemForReplies(input.persona, input.intent, input.personaBook),
            user = PromptBuilder.userForReplies(input.chatContent, useVision),
            images = if (useVision) input.images else emptyList(),
            temperature = ai.temperature,
            maxTokens = ai.maxTokens,
        )

        return when (val raw = client.complete(config, model, apiKey, request, ai.timeoutSeconds)) {
            is NetResult.Failure -> raw
            is NetResult.Success -> {
                val parsed = ResponseParser.parseAnalysis(raw.value)
                if (parsed.replies.isEmpty()) {
                    NetResult.Failure(
                        "模型返回的内容没能解析成回复，请重试或换一个模型。",
                        raw.value.take(500)
                    )
                } else {
                    NetResult.Success(
                        GenerationResult(
                            result = parsed,
                            providerId = config.id,
                            providerLabel = config.displayName,
                            model = model,
                            usedVision = useVision,
                            imagesDropped = imagesDropped,
                        )
                    )
                }
            }
        }
    }

    /** Rewrite / shorten / expand / tone-adjust an existing reply. */
    suspend fun transform(
        chatContent: String,
        persona: Persona,
        original: String,
        op: ReplyOp,
        personaBook: String = "",
    ): NetResult<List<ReplySuggestion>> = withContext(Dispatchers.IO) {
        val prefs = settingsRepository.snapshot()
        val ai = prefs.ai
        val system = PromptBuilder.systemForTransform(persona, personaBook)
        val user = PromptBuilder.userForTransform(chatContent, original, op)
        callList(prefs, ai, system, user)
    }

    /** Ask for brand-new replies that avoid the ones already shown. */
    suspend fun moreReplies(
        chatContent: String,
        persona: Persona,
        original: String,
        used: List<String>,
        personaBook: String = "",
    ): NetResult<List<ReplySuggestion>> = withContext(Dispatchers.IO) {
        val prefs = settingsRepository.snapshot()
        val ai = prefs.ai
        val system = PromptBuilder.systemForExtraReplies(persona, personaBook)
        val user = PromptBuilder.userForExtraReplies(chatContent, original, used)
        callList(prefs, ai, system, user)
    }

    private suspend fun callList(
        prefs: AppPreferences,
        ai: AiSettings,
        system: String,
        user: String,
    ): NetResult<List<ReplySuggestion>> {
        val primary = ai.providers.firstOrNull { it.id == ai.activeProviderId && it.enabled }
            ?: ai.providers.firstOrNull { it.enabled }
            ?: return NetResult.Failure("还没有可用的模型服务，请先到设置里配置。")

        val key = settingsRepository.apiKey(primary.id)
        if (key.isBlank()) {
            return NetResult.Failure("${primary.displayName} 还没有配置 API Key。")
        }

        when (val first = rawCall(primary, key, ai, system, user)) {
            is NetResult.Success -> {
                val replies = ResponseParser.parseReplyList(first.value)
                return if (replies.isEmpty()) {
                    NetResult.Failure("模型没有返回可用的改写结果，请重试。")
                } else {
                    NetResult.Success(replies)
                }
            }

            is NetResult.Failure -> {
                val fallback = ai.fallbackProvider() ?: return first
                val fallbackKey = settingsRepository.apiKey(fallback.id)
                if (fallbackKey.isBlank()) return first
                return when (val second = rawCall(fallback, fallbackKey, ai, system, user)) {
                    is NetResult.Success -> {
                        val replies = ResponseParser.parseReplyList(second.value)
                        if (replies.isEmpty()) first
                        else NetResult.Success(replies)
                    }

                    is NetResult.Failure -> NetResult.Failure(
                        "${primary.displayName} 调用失败：${first.message}\n" +
                            "备用模型 ${fallback.displayName} 也失败了：${second.message}"
                    )
                }
            }
        }
    }

    private fun rawCall(
        config: ProviderConfig,
        apiKey: String,
        ai: AiSettings,
        system: String,
        user: String,
    ): NetResult<String> {
        val model = config.model.ifBlank {
            if (config.supportsVision) config.visionModel else ""
        }
        if (model.isBlank()) return NetResult.Failure("${config.displayName} 还没有填写模型 ID。")
        val request = AiRequest(
            system = system,
            user = user,
            temperature = ai.temperature,
            maxTokens = ai.maxTokens,
        )
        return client.complete(config, model, apiKey, request, ai.timeoutSeconds)
    }

    /**
     * Fetches the model IDs this API key can use, so the picker offers what the
     * account really has instead of a stale hardcoded list.
     */
    suspend fun fetchModels(
        config: ProviderConfig,
        apiKey: String,
        baseUrlOverride: String? = null,
    ): NetResult<List<String>> = withContext(Dispatchers.IO) {
        val timeout = runCatching { settingsRepository.snapshot().ai.timeoutSeconds }
            .getOrDefault(90)
            .coerceIn(15, 60)
        try {
            client.listModels(config, apiKey, timeout, baseUrlOverride)
        } catch (error: Throwable) {
            NetResult.Failure("获取模型列表失败：${error.message ?: "未知错误"}")
        }
    }

    /** Validates an (unsaved) provider configuration by sending a tiny request. */
    suspend fun testConnection(
        config: ProviderConfig,
        apiKey: String,
        baseUrlOverride: String? = null,
        modelOverride: String? = null,
    ): NetResult<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext NetResult.Failure("请先填写 API Key 再测试。")
        }
        val model = (modelOverride ?: config.model).ifBlank { config.visionModel }
        if (model.isBlank()) {
            return@withContext NetResult.Failure("请先填写模型 ID 再测试。")
        }
        val request = AiRequest(
            system = "你是一个连通性测试助手。",
            user = PromptBuilder.testUser(),
            temperature = 0.2,
            maxTokens = 32,
        )
        val prefs = runCatching { settingsRepository.snapshot() }.getOrNull()
        val timeout = prefs?.ai?.timeoutSeconds ?: 60
        when (val raw = client.complete(config, model, apiKey, request, timeout, baseUrlOverride)) {
            is NetResult.Failure -> raw
            is NetResult.Success -> NetResult.Success("连接成功，模型回复：${raw.value.trim().take(60)}")
        }
    }

    private suspend fun record(
        prefs: AppPreferences,
        input: GenerateInput,
        result: GenerationResult,
    ) {
        if (!prefs.historyEnabled) return
        runCatching {
            historyRepository.save(
                inputText = input.chatContent,
                intentText = input.intent,
                personaName = input.persona.name,
                analysis = result.result.analysis,
                tone = result.result.tone,
                strategy = result.result.strategy,
                replies = result.result.replies,
                modelLabel = "${result.providerLabel} · ${result.model}",
                replyingTo = result.result.replyingTo,
            )
        }
    }
}
