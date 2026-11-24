package com.aisocial.copilot.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A single captured/cropped chat image waiting to be analysed. Never written to disk. */
data class PendingDraft(
    val bitmap: Bitmap,
    val source: String,
)

/**
 * In-memory hand-off between the capture pipeline, the overlay and the UI.
 *
 * Deliberately a plain in-process singleton: screenshots must never reach storage
 * unless the user explicitly copies something out.
 */
object CaptureBus {

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _draft = MutableStateFlow<PendingDraft?>(null)
    val draft: StateFlow<PendingDraft?> = _draft

    /** Command channel for MainActivity: "pick_image" / "text_input" / "open_settings". */
    private val _command = MutableStateFlow<String?>(null)
    val command: StateFlow<String?> = _command

    fun publishFrame(bitmap: Bitmap) {
        _error.value = null
        _frame.value = bitmap
    }

    fun publishError(message: String) {
        _frame.value = null
        _error.value = message
    }

    fun consumeFrame() {
        _frame.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    fun publishDraft(draft: PendingDraft) {
        _draft.value = draft
    }

    fun consumeDraft() {
        _draft.value = null
    }

    fun sendCommand(command: String) {
        _command.value = command
    }

    fun consumeCommand() {
        _command.value = null
    }

    fun reset() {
        _frame.value = null
        _error.value = null
    }
}
