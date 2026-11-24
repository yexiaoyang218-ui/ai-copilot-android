package com.aisocial.copilot.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

/** Screens of the single-activity app. */
sealed interface Screen {
    data object Home : Screen
    data object Composer : Screen
    data object Result : Screen
    data object Personas : Screen
    data class PersonaEdit(val personaId: Long) : Screen
    data object PersonaBooks : Screen
    data class PersonaBookEdit(val bookId: Long) : Screen
    data object Settings : Screen
    data class ProviderEdit(val providerId: String) : Screen
}

/** Tiny back-stack so navigation survives configuration changes without extra deps. */
class AppNavViewModel : ViewModel() {

    private val stack = mutableStateListOf<Screen>(Screen.Home)

    val current: Screen get() = stack.lastOrNull() ?: Screen.Home
    val canGoBack: Boolean get() = stack.size > 1
    val depth: Int get() = stack.size

    fun go(screen: Screen) {
        if (stack.lastOrNull() == screen) return
        stack.add(screen)
    }

    fun replace(screen: Screen) {
        if (stack.isEmpty()) stack.add(screen) else stack[stack.lastIndex] = screen
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun resetTo(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }
}
