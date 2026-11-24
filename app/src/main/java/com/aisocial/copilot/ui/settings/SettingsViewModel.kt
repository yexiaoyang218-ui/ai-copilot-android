package com.aisocial.copilot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aisocial.copilot.core.NetResult
import com.aisocial.copilot.data.prefs.AppPreferences
import com.aisocial.copilot.di.AppContainer
import com.aisocial.copilot.domain.model.ProviderConfig
import com.aisocial.copilot.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val prefs: AppPreferences = AppPreferences(),
    val testRunning: Boolean = false,
    val testMessage: String? = null,
    val testOk: Boolean = false,
    val message: String? = null,
    val modelsFetching: Boolean = false,
    val modelOptions: List<String> = emptyList(),
    val modelsForProviderId: String = "",
    val modelFetchMessage: String? = null,
    val modelFetchOk: Boolean = false,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.preferences.collect { prefs ->
                _state.update { it.copy(prefs = prefs) }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode) }
    }

    fun setHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setHistoryEnabled(enabled) }
    }

    fun setConfirmBeforeSend(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setConfirmBeforeSend(enabled) }
    }

    fun setActivePersona(id: Long?) {
        viewModelScope.launch { container.settingsRepository.setActivePersonaId(id) }
    }

    fun setActiveProvider(providerId: String) {
        viewModelScope.launch { container.settingsRepository.setActiveProvider(providerId) }
    }

    fun setFallbackProvider(providerId: String) {
        viewModelScope.launch { container.settingsRepository.setFallbackProvider(providerId) }
    }

    fun setGenerationParams(timeoutSeconds: Int, temperature: Double, maxTokens: Int) {
        viewModelScope.launch {
            container.settingsRepository.setGenerationParams(timeoutSeconds, temperature, maxTokens)
        }
    }

    suspend fun apiKeyFor(providerId: String): String = container.settingsRepository.apiKey(providerId)

    fun saveProvider(config: ProviderConfig, apiKey: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                container.settingsRepository.setApiKey(config.id, apiKey)
                container.settingsRepository.updateProvider(config)
            }
            withContext(Dispatchers.Main) { onSaved() }
        }
    }

    fun testConnection(config: ProviderConfig, apiKey: String) {
        viewModelScope.launch {
            _state.update { it.copy(testRunning = true, testMessage = null, testOk = false) }
            val outcome = container.copilotEngine.testConnection(config, apiKey)
            _state.update {
                it.copy(
                    testRunning = false,
                    testOk = outcome is NetResult.Success,
                    testMessage = when (outcome) {
                        is NetResult.Success -> outcome.value
                        is NetResult.Failure -> outcome.message
                    },
                )
            }
        }
    }

    fun setOverlayAlpha(alpha: Float) {
        viewModelScope.launch { container.settingsRepository.setOverlayAlpha(alpha) }
    }

    fun setAccessibilityFillEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAccessibilityFillEnabled(enabled) }
    }

    /** Pulls the real model list for this key and remembers it for the picker. */
    fun fetchModels(config: ProviderConfig, apiKey: String) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    modelsFetching = true,
                    modelFetchMessage = null,
                    modelFetchOk = false,
                    modelsForProviderId = config.id,
                )
            }
            val outcome = container.copilotEngine.fetchModels(
                config = config,
                apiKey = apiKey,
                baseUrlOverride = config.baseUrl,
            )
            _state.update { current ->
                when (outcome) {
                    is NetResult.Success -> current.copy(
                        modelsFetching = false,
                        modelOptions = outcome.value,
                        modelFetchOk = true,
                        modelFetchMessage = "已获取 ${outcome.value.size} 个可用模型",
                    )

                    is NetResult.Failure -> current.copy(
                        modelsFetching = false,
                        modelFetchOk = false,
                        modelFetchMessage = outcome.message,
                    )
                }
            }
        }
    }

    fun clearModelFetchState() {
        _state.update {
            it.copy(
                modelOptions = emptyList(),
                modelFetchMessage = null,
                modelFetchOk = false,
                modelsForProviderId = "",
            )
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setOverlayEnabled(enabled) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { container.historyRepository.clear() }
            _state.update { it.copy(message = "已清除生成历史") }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    container.appContext.cacheDir?.listFiles()?.forEach { file -> file.deleteRecursively() }
                }
            }
            _state.update { it.copy(message = "已清理本地缓存") }
        }
    }

    fun resetPresets(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.personaRepository.resetPresets() }
            _state.update { it.copy(message = "已恢复内置人设预设") }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun clearEverything(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                container.historyRepository.clear()
                container.personaRepository.personas.first().forEach { container.personaRepository.delete(it) }
                container.settingsRepository.clearEverything()
                container.personaRepository.ensurePresets()
            }
            _state.update { it.copy(message = "已清除全部本地数据") }
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
