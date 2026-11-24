package com.aisocial.copilot.domain.model

import kotlinx.serialization.Serializable

/** Preferred reply length for a persona. */
enum class ReplyLength(val label: String, val hint: String) {
    SHORT("简短", "一句话，10~25 字"),
    MEDIUM("适中", "一到两句，20~50 字"),
    LONG("详细", "两三句，50~90 字");

    companion object {
        fun fromName(name: String?): ReplyLength =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/**
 * A reusable "persona" (人设) that shapes every future generation, not just one message.
 */
data class Persona(
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val tone: String = "",
    val length: ReplyLength = ReplyLength.MEDIUM,
    val emotion: Int = 3,
    val initiative: Int = 3,
    val humor: Int = 3,
    val catchphrases: String = "",
    val forbidden: String = "",
    val customInstruction: String = "",
    val presetKey: String? = null,
    val isPreset: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val summary: String
        get() = listOf(
            tone.ifBlank { "自然" },
            length.label,
            "情绪${emotion}",
            "主动${initiative}",
            "幽默${humor}"
        ).joinToString(" · ")
}

/** A single reply suggestion produced by the AI. */
data class ReplySuggestion(
    val id: String,
    val label: String,
    val text: String,
)

/**
 * 人设书 — a free-form persona document the user writes in their own words.
 * It sits on top of the structured [Persona] and is injected into every prompt.
 */
data class PersonaBook(
    val id: Long = 0L,
    val title: String,
    val content: String,
    val isActive: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val snippet: String
        get() = content.replace('\n', ' ').trim().take(60)
}

/** Structured AI output: analysis is kept visually separate from the sendable replies. */
data class AnalysisResult(
    val analysis: String = "",
    val tone: String = "",
    val strategy: String = "",
    val replies: List<ReplySuggestion> = emptyList(),
    /** The message the model decided it is answering — makes context parsing auditable. */
    val replyingTo: String = "",
) {
    val hasAnalysis: Boolean
        get() = analysis.isNotBlank() || tone.isNotBlank() || strategy.isNotBlank() ||
            replyingTo.isNotBlank()
}

/** Operations the user can run against an existing reply. */
enum class ReplyOp(val label: String, val instruction: String) {
    REWRITE("改写", "换一种说法，意思完全不变，语气自然"),
    SHORTEN("更短", "压到最短，像随手回一句，但不能丢信息"),
    EXPAND("更详细", "多补充一点内容和情绪，仍然口语化，最多三句"),
    NATURAL("更自然", "去掉任何书面腔和 AI 味，像真人在手机上打字"),
    HUMOR("更幽默", "加一点轻松的调侃，不要油腻，不要硬玩梗"),
    PROACTIVE("更主动", "更主动地推进对话，给出明确态度或轻微邀请"),
    SUBTLE("更含蓄", "更含蓄克制，留余地，不把话说满"),
    CALM("更冷静", "更冷静稳定，不带情绪，不和对方争执"),
    MORE("再生成一条", "生成一条和原句完全不同、角度不一样的新回复"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** Transport flavour of a provider — decides the wire protocol, not the brand. */
enum class ProviderKind {
    OPENAI,
    GEMINI,
    CLAUDE,
}

@Serializable
data class ProviderConfig(
    val id: String,
    val kind: ProviderKind,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val visionModel: String = "",
    val supportsVision: Boolean = false,
    val enabled: Boolean = false,
    /** Models returned by the provider's /models endpoint on a previous "获取模型列表". */
    val fetchedModels: List<String> = emptyList(),
) {
    /** Recommended + previously fetched models, de-duplicated, current selection first. */
    fun modelChoices(): List<String> {
        val curated = ModelCatalog.curated(id)
        return buildList {
            if (model.isNotBlank()) add(model)
            if (visionModel.isNotBlank() && supportsVision) add(visionModel)
            addAll(curated)
            addAll(fetchedModels)
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }
}

@Serializable
data class AiSettings(
    val providers: List<ProviderConfig> = DefaultProviders.list,
    val activeProviderId: String = DefaultProviders.DEFAULT_ID,
    val fallbackProviderId: String = "",
    val timeoutSeconds: Int = 90,
    val temperature: Double = 0.9,
    val maxTokens: Int = 1600,
) {
    fun providerById(id: String): ProviderConfig? = providers.firstOrNull { it.id == id }
    fun activeProvider(): ProviderConfig? =
        providerById(activeProviderId) ?: providers.firstOrNull { it.enabled }

    fun fallbackProvider(): ProviderConfig? =
        providerById(fallbackProviderId)?.takeIf { it.enabled && it.id != activeProviderId }
}

object DefaultProviders {
    const val DEFAULT_ID = "deepseek"

    val deepSeek = ProviderConfig(
        id = "deepseek",
        kind = ProviderKind.OPENAI,
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        visionModel = "",
        supportsVision = false,
        enabled = true,
    )

    val openAi = ProviderConfig(
        id = "openai",
        kind = ProviderKind.OPENAI,
        displayName = "GPT (OpenAI)",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        visionModel = "gpt-4o-mini",
        supportsVision = true,
        enabled = true,
    )

    val claude = ProviderConfig(
        id = "claude",
        kind = ProviderKind.CLAUDE,
        displayName = "Claude (Anthropic)",
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-4-5",
        visionModel = "claude-sonnet-4-5",
        supportsVision = true,
        enabled = true,
    )

    val gemini = ProviderConfig(
        id = "gemini",
        kind = ProviderKind.GEMINI,
        displayName = "Gemini (Google)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        model = "gemini-2.5-flash",
        visionModel = "gemini-2.5-flash",
        supportsVision = true,
        enabled = true,
    )

    val kimi = ProviderConfig(
        id = "kimi",
        kind = ProviderKind.OPENAI,
        displayName = "Kimi (Moonshot)",
        baseUrl = "https://api.moonshot.cn/v1",
        model = "kimi-k2-0905-preview",
        visionModel = "moonshot-v1-8k-vision-preview",
        supportsVision = true,
        enabled = true,
    )

    val qwen = ProviderConfig(
        id = "qwen",
        kind = ProviderKind.OPENAI,
        displayName = "通义千问 (Qwen)",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-plus",
        visionModel = "qwen-vl-max",
        supportsVision = true,
        enabled = true,
    )

    val glm = ProviderConfig(
        id = "glm",
        kind = ProviderKind.OPENAI,
        displayName = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-4.5",
        visionModel = "glm-4v-plus",
        supportsVision = true,
        enabled = true,
    )

    /** Order matches the product spec: DeepSeek, GPT, Claude, Gemini, Kimi, Qwen, GLM. */
    val list: List<ProviderConfig> = listOf(deepSeek, openAi, claude, gemini, kimi, qwen, glm)

    val ids: List<String> = list.map { it.id }

    fun byId(id: String): ProviderConfig =
        list.firstOrNull { it.id == id } ?: deepSeek
}

/**
 * Suggested model IDs per provider, used to populate the picker before (or instead of)
 * querying the provider's own /models endpoint. Providers ship new model IDs constantly,
 * so this is only a starting point — the list button always wins.
 */
object ModelCatalog {

    private val catalog: Map<String, List<String>> = mapOf(
        "deepseek" to listOf(
            "deepseek-chat",
            "deepseek-reasoner",
        ),
        "openai" to listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "gpt-4.1",
            "gpt-4.1-mini",
            "o4-mini",
            "o3-mini",
        ),
        "claude" to listOf(
            "claude-sonnet-4-5",
            "claude-opus-4-1",
            "claude-haiku-4-5",
            "claude-3-7-sonnet-latest",
            "claude-3-5-haiku-latest",
        ),
        "gemini" to listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
        ),
        "kimi" to listOf(
            "kimi-k2-0905-preview",
            "kimi-k2-turbo-preview",
            "moonshot-v1-8k",
            "moonshot-v1-32k",
            "moonshot-v1-128k",
            "moonshot-v1-8k-vision-preview",
        ),
        "qwen" to listOf(
            "qwen-max",
            "qwen-plus",
            "qwen-turbo",
            "qwen3-max",
            "qwen-vl-max",
            "qwen-vl-plus",
        ),
        "glm" to listOf(
            "glm-4.5",
            "glm-4.5-air",
            "glm-4-plus",
            "glm-4-flash",
            "glm-4v-plus",
            "glm-4v-flash",
        ),
    )

    fun curated(providerId: String): List<String> = catalog[providerId] ?: emptyList()
}

/** An image that may be attached to an AI request (already downscaled + JPEG encoded). */
data class ImagePayload(
    val base64: String,
    val mimeType: String = "image/jpeg",
)

/** Everything the engine needs to produce a suggestion set. */
data class GenerateInput(
    val chatContent: String,
    val intent: String,
    val persona: Persona,
    val images: List<ImagePayload> = emptyList(),
    val ocrText: String = "",
    /** Free-form "人设书" written by the user. Takes precedence over the template fields. */
    val personaBook: String = "",
)

data class GenerationResult(
    val result: AnalysisResult,
    val providerId: String,
    val providerLabel: String,
    val model: String,
    val usedFallback: Boolean = false,
    val usedVision: Boolean = false,
    val imagesDropped: Boolean = false,
)
