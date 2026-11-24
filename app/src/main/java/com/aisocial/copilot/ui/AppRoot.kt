package com.aisocial.copilot.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisocial.copilot.capture.CaptureBus
import com.aisocial.copilot.capture.PendingDraft
import com.aisocial.copilot.core.BitmapUtils
import com.aisocial.copilot.di.AppContainer
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.overlay.OverlayService
import com.aisocial.copilot.ui.nav.AppNavViewModel
import com.aisocial.copilot.ui.nav.Screen
import com.aisocial.copilot.ui.persona.PersonaViewModel
import com.aisocial.copilot.ui.screens.ComposerScreen
import com.aisocial.copilot.ui.screens.HomeScreen
import com.aisocial.copilot.ui.screens.PersonaBookEditScreen
import com.aisocial.copilot.ui.screens.PersonaBooksScreen
import com.aisocial.copilot.ui.screens.PersonaEditScreen
import com.aisocial.copilot.ui.screens.PersonasScreen
import com.aisocial.copilot.ui.screens.ProviderEditScreen
import com.aisocial.copilot.ui.screens.ResultScreen
import com.aisocial.copilot.ui.screens.SettingsScreen
import com.aisocial.copilot.ui.session.SessionEvent
import com.aisocial.copilot.ui.session.SessionViewModel
import com.aisocial.copilot.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppRoot(
    container: AppContainer,
    navVm: AppNavViewModel,
    settingsVm: SettingsViewModel,
    personaVm: PersonaViewModel,
    sessionVm: SessionViewModel,
    composerRequest: Int,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    val personas by personaVm.personas.collectAsStateWithLifecycle()
    val books by personaVm.books.collectAsStateWithLifecycle()
    val sessionState by sessionVm.state.collectAsStateWithLifecycle()

    val activeBook = remember(books) { books.firstOrNull { it.isActive } }

    val prefs = settingsState.prefs
    val activePersona: Persona = remember(personas, prefs.activePersonaId) {
        personas.firstOrNull { it.id == prefs.activePersonaId }
            ?: personas.firstOrNull { it.presetKey == "natural" }
            ?: personas.firstOrNull()
            ?: Persona(name = "默认")
    }
    val activeProvider = prefs.ai.providerById(prefs.ai.activeProviderId)

    // ----------------------------------------------------------- image input

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { BitmapUtils.decodeFromUri(context, uri, 2048) }.getOrNull()
            }
            if (bitmap == null) {
                snackbarHostState.showSnackbar("这张图片读取失败，换一张试试")
            } else {
                sessionVm.acceptDraft(PendingDraft(bitmap = bitmap, source = "gallery"))
                navVm.go(Screen.Composer)
            }
        }
    }

    fun pickImage() {
        runCatching {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("无法打开相册：${it.message ?: "未知错误"}") }
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            scope.launch { snackbarHostState.showSnackbar("当前设备不支持剪贴板") }
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("AI 回复", text))
        scope.launch { snackbarHostState.showSnackbar("已复制，去粘贴吧") }
    }

    // ------------------------------------------------------------- signals

    val pendingDraft by CaptureBus.draft.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDraft) {
        val draft = pendingDraft ?: return@LaunchedEffect
        CaptureBus.consumeDraft()
        sessionVm.acceptDraft(draft)
        navVm.go(Screen.Composer)
    }

    val command by CaptureBus.command.collectAsStateWithLifecycle()
    LaunchedEffect(command) {
        when (command) {
            OverlayService.COMMAND_PICK_IMAGE -> {
                CaptureBus.consumeCommand()
                pickImage()
            }

            OverlayService.COMMAND_TEXT_INPUT -> {
                CaptureBus.consumeCommand()
                navVm.go(Screen.Composer)
            }

            OverlayService.COMMAND_OPEN_SETTINGS -> {
                CaptureBus.consumeCommand()
                navVm.go(Screen.Settings)
            }

            else -> Unit
        }
    }

    LaunchedEffect(composerRequest) {
        if (composerRequest > 0) navVm.go(Screen.Composer)
    }

    LaunchedEffect(Unit) {
        sessionVm.events.collect { event ->
            when (event) {
                is SessionEvent.Generated -> navVm.go(Screen.Result)
                is SessionEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    LaunchedEffect(settingsState.message) {
        val message = settingsState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        settingsVm.dismissMessage()
    }

    BackHandler(enabled = navVm.canGoBack) { navVm.back() }

    Box(Modifier.fillMaxSize()) {
        when (val screen = navVm.current) {
            Screen.Home -> HomeScreen(
                prefs = prefs,
                personas = personas,
                history = sessionState.history,
                activePersona = activePersona,
                activeBookTitle = activeBook?.title.orEmpty(),
                onCapture = { launchCapture(context) },
                onPickImage = { pickImage() },
                onTextInput = { navVm.go(Screen.Composer) },
                onManagePersonas = { navVm.go(Screen.Personas) },
                onOpenPersonaBooks = { navVm.go(Screen.PersonaBooks) },
                onSelectPersona = { id -> settingsVm.setActivePersona(id) },
                onOpenSettings = { navVm.go(Screen.Settings) },
                onOpenHistory = { record ->
                    sessionVm.openHistory(record)
                    navVm.go(Screen.Result)
                },
                snackbarHostState = snackbarHostState,
            )

            Screen.Composer -> ComposerScreen(
                state = sessionState,
                personas = personas,
                activePersona = activePersona,
                prefs = prefs,
                provider = activeProvider,
                onBack = { navVm.back() },
                onTextChange = sessionVm::setChatText,
                onIntentChange = sessionVm::setIntent,
                onRemoveImage = sessionVm::removeImage,
                onPickImage = { pickImage() },
                onCapture = { launchCapture(context) },
                onSelectPersona = { id -> settingsVm.setActivePersona(id) },
                onSelectProvider = { id -> settingsVm.setActiveProvider(id) },
                onManagePersonas = { navVm.go(Screen.Personas) },
                onOpenSettings = { navVm.go(Screen.Settings) },
                onClear = sessionVm::clearDraft,
                onGenerate = { sessionVm.generate(activePersona) },
                snackbarHostState = snackbarHostState,
            )

            Screen.Result -> ResultScreen(
                state = sessionState,
                activePersona = activePersona,
                onBack = { navVm.back() },
                onCopy = { text -> copyToClipboard(text) },
                onOperation = { index, op -> sessionVm.applyOperation(index, op, activePersona) },
                onRestore = sessionVm::restoreReply,
                onEditReply = { reply, text -> sessionVm.updateReplyText(reply, text) },
                onMore = { sessionVm.requestMoreReplies(activePersona) },
                onRegenerate = { sessionVm.generate(activePersona) },
                onEditInputs = { navVm.go(Screen.Composer) },
                snackbarHostState = snackbarHostState,
            )

            Screen.Personas -> PersonasScreen(
                personas = personas,
                activePersonaId = prefs.activePersonaId,
                onBack = { navVm.back() },
                onAdd = { navVm.go(Screen.PersonaEdit(0L)) },
                onEdit = { persona -> navVm.go(Screen.PersonaEdit(persona.id)) },
                onSelect = { persona -> settingsVm.setActivePersona(persona.id) },
                onDuplicate = { persona ->
                    scope.launch {
                        runCatching { personaVm.duplicate(persona) }
                        snackbarHostState.showSnackbar("已复制一份人设")
                    }
                },
                onDelete = { persona ->
                    scope.launch {
                        runCatching { personaVm.delete(persona) }
                        if (prefs.activePersonaId == persona.id) settingsVm.setActivePersona(null)
                        snackbarHostState.showSnackbar("已删除")
                    }
                },
                onResetPresets = {
                    scope.launch {
                        runCatching { personaVm.resetPresets() }
                        snackbarHostState.showSnackbar("已恢复内置预设")
                    }
                },
                onOpenPersonaBooks = { navVm.go(Screen.PersonaBooks) },
                snackbarHostState = snackbarHostState,
            )

            Screen.PersonaBooks -> PersonaBooksScreen(
                books = books,
                onBack = { navVm.back() },
                onAdd = { navVm.go(Screen.PersonaBookEdit(0L)) },
                onEdit = { book -> navVm.go(Screen.PersonaBookEdit(book.id)) },
                onActivate = { book ->
                    scope.launch {
                        runCatching { personaVm.activateBook(book.id) }
                        snackbarHostState.showSnackbar("已启用「${book.title}」，下次识别立即生效")
                    }
                },
                onDeactivate = {
                    scope.launch {
                        runCatching { personaVm.deactivateBooks() }
                        snackbarHostState.showSnackbar("已停止使用人设书")
                    }
                },
                onDelete = { book ->
                    scope.launch {
                        runCatching { personaVm.deleteBook(book.id) }
                        snackbarHostState.showSnackbar("已删除")
                    }
                },
                snackbarHostState = snackbarHostState,
            )

            is Screen.PersonaBookEdit -> PersonaBookEditScreen(
                bookId = screen.bookId,
                onBack = { navVm.back() },
                load = { id -> personaVm.loadBook(id) },
                onSave = { book ->
                    scope.launch {
                        runCatching { personaVm.saveBook(book) }
                        snackbarHostState.showSnackbar("已保存并启用，下一次识别就会用它")
                        navVm.back()
                    }
                },
                onDelete = { book ->
                    scope.launch {
                        runCatching { personaVm.deleteBook(book.id) }
                        navVm.back()
                    }
                },
                snackbarHostState = snackbarHostState,
            )

            is Screen.PersonaEdit -> PersonaEditScreen(
                personaId = screen.personaId,
                onBack = { navVm.back() },
                load = { id -> personaVm.load(id) },
                onSave = { persona ->
                    scope.launch {
                        val id = runCatching { personaVm.save(persona) }.getOrNull()
                        if (prefs.activePersonaId == null && id != null) settingsVm.setActivePersona(id)
                        navVm.back()
                    }
                },
                onDelete = { persona ->
                    scope.launch {
                        runCatching { personaVm.delete(persona) }
                        if (prefs.activePersonaId == persona.id) settingsVm.setActivePersona(null)
                        navVm.back()
                    }
                },
                snackbarHostState = snackbarHostState,
            )

            Screen.Settings -> SettingsScreen(
                prefs = prefs,
                onBack = { navVm.back() },
                onOpenProvider = { id -> navVm.go(Screen.ProviderEdit(id)) },
                onSetTheme = settingsVm::setThemeMode,
                onSetActiveProvider = settingsVm::setActiveProvider,
                onSetFallbackProvider = settingsVm::setFallbackProvider,
                onSetParams = settingsVm::setGenerationParams,
                onSetHistoryEnabled = settingsVm::setHistoryEnabled,
                onSetConfirmBeforeSend = settingsVm::setConfirmBeforeSend,
                onSetOverlayEnabled = settingsVm::setOverlayEnabled,
                onSetOverlayAlpha = settingsVm::setOverlayAlpha,
                onSetAccessibilityFill = settingsVm::setAccessibilityFillEnabled,
                onClearHistory = settingsVm::clearHistory,
                onClearCache = settingsVm::clearCache,
                onClearEverything = {
                    settingsVm.clearEverything {
                        sessionVm.clearDraft()
                        navVm.resetTo(Screen.Home)
                    }
                },
                onResetPresets = { settingsVm.resetPresets { } },
                snackbarHostState = snackbarHostState,
            )

            is Screen.ProviderEdit -> ProviderEditScreen(
                providerId = screen.providerId,
                prefs = prefs,
                onBack = {
                    settingsVm.clearModelFetchState()
                    navVm.back()
                },
                loadApiKey = { id -> settingsVm.apiKeyFor(id) },
                onSave = { config, key ->
                    settingsVm.saveProvider(config, key) { navVm.back() }
                },
                onTest = { config, key -> settingsVm.testConnection(config, key) },
                onFetchModels = { config, key -> settingsVm.fetchModels(config, key) },
                onClearModelFetch = { settingsVm.clearModelFetchState() },
                testRunning = settingsState.testRunning,
                testMessage = settingsState.testMessage,
                testOk = settingsState.testOk,
                modelsFetching = settingsState.modelsFetching,
                modelOptions = settingsState.modelOptions,
                modelFetchMessage = settingsState.modelFetchMessage,
                modelFetchOk = settingsState.modelFetchOk,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

/** Starts the MediaProjection consent flow from the app itself. */
private fun launchCapture(context: android.content.Context) {
    val intent = android.content.Intent(
        context,
        com.aisocial.copilot.capture.ScreenCaptureActivity::class.java
    ).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
