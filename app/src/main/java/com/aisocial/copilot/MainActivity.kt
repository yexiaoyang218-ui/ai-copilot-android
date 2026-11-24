package com.aisocial.copilot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisocial.copilot.overlay.OverlayService
import com.aisocial.copilot.ui.AppRoot
import com.aisocial.copilot.ui.GenericViewModelFactory
import com.aisocial.copilot.ui.nav.AppNavViewModel
import com.aisocial.copilot.ui.persona.PersonaViewModel
import com.aisocial.copilot.ui.session.SessionViewModel
import com.aisocial.copilot.ui.settings.SettingsViewModel
import com.aisocial.copilot.ui.theme.AiCopilotTheme
import kotlinx.coroutines.launch

/**
 * Single activity host. All UI is Compose; no business logic lives here.
 */
class MainActivity : ComponentActivity() {

    private var composerRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.getBooleanExtra(EXTRA_OPEN_COMPOSER, false) == true) {
            composerRequest += 1
        }

        val container = (application as AiCopilotApp).container
        restoreOverlayIfEnabled()

        setContent {
            val navVm: AppNavViewModel = viewModel(
                factory = remember { GenericViewModelFactory { AppNavViewModel() } }
            )
            val settingsVm: SettingsViewModel = viewModel(
                factory = remember { GenericViewModelFactory { SettingsViewModel(container) } }
            )
            val personaVm: PersonaViewModel = viewModel(
                factory = remember { GenericViewModelFactory { PersonaViewModel(container) } }
            )
            val sessionVm: SessionViewModel = viewModel(
                factory = remember { GenericViewModelFactory { SessionViewModel(container) } }
            )

            val settingsState by settingsVm.state.collectAsStateWithLifecycle()

            AiCopilotTheme(themeMode = settingsState.prefs.themeMode) {
                AppRoot(
                    container = container,
                    navVm = navVm,
                    settingsVm = settingsVm,
                    personaVm = personaVm,
                    sessionVm = sessionVm,
                    composerRequest = composerRequest,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_COMPOSER, false)) {
            composerRequest += 1
        }
    }

    /**
     * Android kills foreground services on reboot / reinstall. If the user had the floating
     * ball on, bring it back as soon as the app is opened again — otherwise the settings
     * switch would claim "on" while nothing is actually on screen.
     */
    private fun restoreOverlayIfEnabled() {
        val container = (application as AiCopilotApp).container
        lifecycleScope.launch {
            val prefs = runCatching { container.settingsRepository.snapshot() }.getOrNull()
                ?: return@launch
            if (!prefs.overlayEnabled) return@launch
            val canDraw = runCatching { Settings.canDrawOverlays(this@MainActivity) }
                .getOrDefault(false)
            if (!canDraw) {
                // Permission was revoked (e.g. reinstall) — keep the switch honest.
                runCatching { container.settingsRepository.setOverlayEnabled(false) }
                return@launch
            }
            if (!OverlayService.isRunning()) {
                runCatching { OverlayService.start(this@MainActivity) }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_COMPOSER = "extra_open_composer"
    }
}
