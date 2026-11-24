package com.aisocial.copilot.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aisocial.copilot.data.secure.SecretVault
import com.aisocial.copilot.domain.model.AiSettings
import com.aisocial.copilot.domain.model.DefaultProviders
import com.aisocial.copilot.domain.model.ProviderConfig
import com.aisocial.copilot.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_copilot_settings")

/** Everything the UI needs to render, with API keys excluded (only presence is exposed). */
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val historyEnabled: Boolean = true,
    val confirmBeforeSend: Boolean = true,
    val activePersonaId: Long? = null,
    val ai: AiSettings = AiSettings(),
    val providersWithKey: Set<String> = emptySet(),
    val overlayEnabled: Boolean = false,
    val overlayAlpha: Float = 0.92f,
    val overlayX: Int = -1,
    val overlayY: Int = -1,
    /** 结果面板透明度（1 = 不透明） */
    val overlayResultAlpha: Float = 1f,
    /** 结果面板缩放（0.8 – 1.4） */
    val overlayResultScale: Float = 1f,
    val accessibilityFillEnabled: Boolean = false,
)

/**
 * Single source of truth for user settings. Backed by DataStore; API keys are
 * encrypted with the Android Keystore before they touch disk.
 */
class SettingsRepository(
    context: Context,
    private val vault: SecretVault,
) {

    private val dataStore = context.aiDataStore
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it.toAppPreferences() }

    private fun Preferences.toAppPreferences(): AppPreferences {
        val aiRaw = this[Keys.AI_SETTINGS]
        val ai = runCatching { json.decodeFromString(AiSettings.serializer(), aiRaw ?: "") }
            .getOrElse { AiSettings() }
            .reconcile()
        return AppPreferences(
            themeMode = runCatching { ThemeMode.valueOf(this[Keys.THEME] ?: ThemeMode.SYSTEM.name) }
                .getOrDefault(ThemeMode.SYSTEM),
            historyEnabled = this[Keys.HISTORY_ENABLED] ?: true,
            confirmBeforeSend = this[Keys.CONFIRM_BEFORE_SEND] ?: true,
            activePersonaId = this[Keys.ACTIVE_PERSONA_ID]?.takeIf { it > 0L },
            ai = ai,
            providersWithKey = asMap()
                .filterKeys { it.name.startsWith(KEY_PREFIX) }
                .mapNotNull { (key, value) ->
                    (value as? String)?.takeIf { it.isNotBlank() }?.let { key.name.removePrefix(KEY_PREFIX) }
                }
                .toSet(),
            overlayEnabled = this[Keys.OVERLAY_ENABLED] ?: false,
            overlayAlpha = this[Keys.OVERLAY_ALPHA] ?: 0.92f,
            overlayX = this[Keys.OVERLAY_X] ?: -1,
            overlayY = this[Keys.OVERLAY_Y] ?: -1,
            overlayResultAlpha = this[Keys.OVERLAY_RESULT_ALPHA] ?: 1f,
            overlayResultScale = this[Keys.OVERLAY_RESULT_SCALE] ?: 1f,
            accessibilityFillEnabled = this[Keys.ACCESSIBILITY_FILL] ?: false,
        )
    }

    private fun AiSettings.reconcile(): AiSettings {
        // Keep the product-spec provider set: drop anything unknown (e.g. the v1.0
        // "自定义兼容接口"), add providers introduced by an update, keep stored config.
        val merged = DefaultProviders.list.map { default ->
            providers.firstOrNull { it.id == default.id } ?: default
        }
        val activeOk = merged.any { it.id == activeProviderId }
        val fallbackOk = fallbackProviderId.isNotBlank() && merged.any { it.id == fallbackProviderId }
        return copy(
            providers = merged,
            activeProviderId = if (activeOk) activeProviderId else DefaultProviders.DEFAULT_ID,
            fallbackProviderId = if (fallbackOk) fallbackProviderId else "",
        )
    }

    suspend fun snapshot(): AppPreferences = preferences.first()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }

    suspend fun setHistoryEnabled(enabled: Boolean) = edit { it[Keys.HISTORY_ENABLED] = enabled }

    suspend fun setConfirmBeforeSend(enabled: Boolean) = edit { it[Keys.CONFIRM_BEFORE_SEND] = enabled }

    suspend fun setOverlayResultAlpha(alpha: Float) = edit {
        it[Keys.OVERLAY_RESULT_ALPHA] = alpha.coerceIn(0.35f, 1f)
    }

    suspend fun setOverlayResultScale(scale: Float) = edit {
        it[Keys.OVERLAY_RESULT_SCALE] = scale.coerceIn(0.80f, 1.40f)
    }

    suspend fun setActivePersonaId(id: Long?) = edit {        if (id == null || id <= 0L) it.remove(Keys.ACTIVE_PERSONA_ID) else it[Keys.ACTIVE_PERSONA_ID] = id
    }

    suspend fun saveAiSettings(settings: AiSettings) = edit {
        it[Keys.AI_SETTINGS] = json.encodeToString(AiSettings.serializer(), settings)
    }

    suspend fun updateProvider(provider: ProviderConfig) {
        val current = snapshot().ai
        val updated = current.providers.map { if (it.id == provider.id) provider else it }
        saveAiSettings(current.copy(providers = updated))
    }

    suspend fun setActiveProvider(providerId: String) {
        val current = snapshot().ai
        saveAiSettings(current.copy(activeProviderId = providerId))
    }

    suspend fun setFallbackProvider(providerId: String) {
        val current = snapshot().ai
        saveAiSettings(current.copy(fallbackProviderId = providerId))
    }

    suspend fun setGenerationParams(timeoutSeconds: Int, temperature: Double, maxTokens: Int) {
        val current = snapshot().ai
        saveAiSettings(
            current.copy(
                timeoutSeconds = timeoutSeconds.coerceIn(10, 300),
                temperature = temperature.coerceIn(0.0, 1.5),
                maxTokens = maxTokens.coerceIn(256, 8192),
            )
        )
    }

    /** Stores an API key encrypted. Passing a blank string removes it. */
    suspend fun setApiKey(providerId: String, rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            edit { it.remove(stringPreferencesKey(KEY_PREFIX + providerId)) }
            return
        }
        val encrypted = vault.encrypt(trimmed)
        edit { it[stringPreferencesKey(KEY_PREFIX + providerId)] = encrypted }
    }

    /** Decrypted key for one provider, or an empty string when absent. */
    suspend fun apiKey(providerId: String): String {
        val stored = dataStore.data
            .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
            .map { it[stringPreferencesKey(KEY_PREFIX + providerId)] }
            .first()
        return if (stored.isNullOrBlank()) "" else vault.decrypt(stored)
    }

    suspend fun setOverlayEnabled(enabled: Boolean) = edit { it[Keys.OVERLAY_ENABLED] = enabled }

    suspend fun setAccessibilityFillEnabled(enabled: Boolean) =
        edit { it[Keys.ACCESSIBILITY_FILL] = enabled }

    suspend fun setOverlayAlpha(alpha: Float) = edit { it[Keys.OVERLAY_ALPHA] = alpha.coerceIn(0.25f, 1f) }

    suspend fun setOverlayPosition(x: Int, y: Int) = edit {
        it[Keys.OVERLAY_X] = x
        it[Keys.OVERLAY_Y] = y
    }

    /** Wipes every stored preference, including encrypted keys. */
    suspend fun clearEverything() {
        dataStore.edit { preferences -> preferences.clear() }
    }

    private suspend fun edit(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val CONFIRM_BEFORE_SEND = booleanPreferencesKey("confirm_before_send")
        val ACTIVE_PERSONA_ID = longPreferencesKey("active_persona_id")
        val AI_SETTINGS = stringPreferencesKey("ai_settings")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val OVERLAY_ALPHA = floatPreferencesKey("overlay_alpha")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        val OVERLAY_RESULT_ALPHA = floatPreferencesKey("overlay_result_alpha")
        val OVERLAY_RESULT_SCALE = floatPreferencesKey("overlay_result_scale")
        val ACCESSIBILITY_FILL = booleanPreferencesKey("accessibility_fill_enabled")
    }

    private companion object {
        const val KEY_PREFIX = "api_key_"
    }
}
