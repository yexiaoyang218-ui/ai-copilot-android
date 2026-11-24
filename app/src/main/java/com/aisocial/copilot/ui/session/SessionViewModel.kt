package com.aisocial.copilot.ui.session

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aisocial.copilot.core.BitmapUtils
import com.aisocial.copilot.core.NetResult
import com.aisocial.copilot.capture.PendingDraft
import com.aisocial.copilot.data.repo.HistoryRecord
import com.aisocial.copilot.di.AppContainer
import com.aisocial.copilot.domain.model.AnalysisResult
import com.aisocial.copilot.domain.model.GenerateInput
import com.aisocial.copilot.domain.model.ImagePayload
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ReplyOp
import com.aisocial.copilot.domain.model.ReplySuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An image attached to the current draft. Lives in memory only. */
data class AttachedImage(val id: String, val bitmap: Bitmap)

data class SessionState(
    val chatText: String = "",
    val intent: String = "",
    val images: List<AttachedImage> = emptyList(),
    val ocrRunning: Boolean = false,
    val ocrNotice: String? = null,
    val generating: Boolean = false,
    val transformBusy: Boolean = false,
    val error: String? = null,
    val result: AnalysisResult? = null,
    val providerLabel: String = "",
    val modelLabel: String = "",
    val usedFallback: Boolean = false,
    val imagesDropped: Boolean = false,
    val originals: Map<String, String> = emptyMap(),
    val history: List<HistoryRecord> = emptyList(),
    val activeBookTitle: String = "",
    val activeBookId: Long = 0L,
) {
    val canGenerate: Boolean get() = !generating && (chatText.isNotBlank() || images.isNotEmpty())
}

sealed interface SessionEvent {
    data object Generated : SessionEvent
    data class Message(val text: String) : SessionEvent
}

/**
 * Drives the input -> analyse -> suggestions loop.
 * One VM keeps the draft, the result and the reply operations coherent across screens.
 */
class SessionViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SessionState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            container.historyRepository.recent(20).collect { records ->
                _state.update { it.copy(history = records) }
            }
        }
        // Keeps the "生效中的人设书" label in sync the moment a book is saved or switched.
        viewModelScope.launch {
            container.personaBookRepository.books.collect { books ->
                val active = books.firstOrNull { it.isActive }
                _state.update {
                    it.copy(
                        activeBookTitle = active?.title.orEmpty(),
                        activeBookId = active?.id ?: 0L,
                    )
                }
            }
        }
    }

    /** Read fresh every time, so a just-saved 人设书 is used by the very next call. */
    private suspend fun currentBookText(): String =
        runCatching { container.personaBookRepository.active()?.content.orEmpty() }
            .getOrDefault("")

    // --------------------------------------------------------------- editing

    fun setChatText(text: String) {
        _state.update { it.copy(chatText = text) }
    }

    fun setIntent(text: String) {
        _state.update { it.copy(intent = text) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissOcrNotice() {
        _state.update { it.copy(ocrNotice = null) }
    }

    fun clearDraft() {
        _state.update {
            it.copy(
                chatText = "",
                intent = "",
                images = emptyList(),
                ocrNotice = null,
                error = null,
                result = null,
                originals = emptyMap(),
            )
        }
    }

    fun clearResult() {
        _state.update { it.copy(result = null, originals = emptyMap()) }
    }

    fun removeImage(id: String) {
        _state.update { state -> state.copy(images = state.images.filterNot { it.id == id }) }
    }

    /** Accepts a cropped screenshot or a picked gallery image, then runs local OCR. */
    fun acceptDraft(draft: PendingDraft) {
        val image = AttachedImage(id = "img-${System.nanoTime()}", bitmap = draft.bitmap)
        val merged = (_state.value.images + image).takeLast(MAX_IMAGES)
        _state.update {
            it.copy(
                images = merged,
                ocrRunning = true,
                ocrNotice = null,
                error = null,
            )
        }
        viewModelScope.launch {
            when (val outcome = container.ocrEngine.recognize(draft.bitmap)) {
                is NetResult.Success -> {
                    _state.update { current ->
                        val text = if (current.chatText.isBlank()) {
                            outcome.value
                        } else {
                            current.chatText.trimEnd() + "\n\n" + outcome.value
                        }
                        current.copy(
                            chatText = text,
                            ocrRunning = false,
                            ocrNotice = "已识别截图里的文字，请核对后再生成。",
                        )
                    }
                }

                is NetResult.Failure -> {
                    _state.update {
                        it.copy(
                            ocrRunning = false,
                            ocrNotice = outcome.message + " 也可以手动输入聊天内容。",
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ generation

    fun generate(persona: Persona) {
        val snapshotState = _state.value
        if (snapshotState.generating) return
        if (snapshotState.chatText.isBlank() && snapshotState.images.isEmpty()) {
            _state.update { it.copy(error = "先输入或粘贴聊天内容，或者附一张截图。") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(generating = true, error = null) }
            val payloads = withContext(Dispatchers.Default) {
                snapshotState.images.mapNotNull { attached ->
                    runCatching { ImagePayload(BitmapUtils.toJpegBase64(attached.bitmap)) }.getOrNull()
                }
            }
            val input = GenerateInput(
                chatContent = snapshotState.chatText.trim(),
                intent = snapshotState.intent.trim(),
                persona = persona,
                images = payloads,
                personaBook = currentBookText(),
            )
            val outcome = runCatching { container.copilotEngine.generate(input) }
                .getOrElse { error ->
                    NetResult.Failure("生成失败：${error.message ?: "未知错误"}")
                }
            when (outcome) {
                is NetResult.Success -> {
                    _state.update {
                        it.copy(
                            generating = false,
                            result = outcome.value.result,
                            providerLabel = outcome.value.providerLabel,
                            modelLabel = outcome.value.model,
                            usedFallback = outcome.value.usedFallback,
                            imagesDropped = outcome.value.imagesDropped,
                            originals = emptyMap(),
                        )
                    }
                    _events.emit(SessionEvent.Generated)
                }

                is NetResult.Failure -> {
                    _state.update { it.copy(generating = false, error = outcome.message) }
                }
            }
        }
    }

    // ------------------------------------------------------ reply operations

    fun applyOperation(index: Int, op: ReplyOp, persona: Persona) {
        val current = _state.value
        val result = current.result ?: return
        val reply = result.replies.getOrNull(index) ?: return
        if (current.transformBusy) return

        viewModelScope.launch {
            _state.update { it.copy(transformBusy = true, error = null) }
            val outcome = runCatching {
                container.copilotEngine.transform(
                    chatContent = current.chatText,
                    persona = persona,
                    original = reply.text,
                    op = op,
                    personaBook = currentBookText(),
                )
            }.getOrElse { NetResult.Failure("改写失败：${it.message ?: "未知错误"}") }
            when (outcome) {
                is NetResult.Success -> {
                    val replacement = outcome.value.firstOrNull()
                    if (replacement == null) {
                        _state.update { it.copy(transformBusy = false, error = "没有拿到改写结果，请重试。") }
                        return@launch
                    }
                    _state.update { state ->
                        val activeResult = state.result ?: return@update state
                        val replies = activeResult.replies.toMutableList()
                        if (index in replies.indices) {
                            replies[index] = replacement.copy(id = reply.id, label = reply.label)
                        }
                        state.copy(
                            transformBusy = false,
                            result = activeResult.copy(replies = replies),
                            originals = state.originals + (reply.id to reply.text),
                        )
                    }
                }

                is NetResult.Failure -> {
                    _state.update { it.copy(transformBusy = false, error = outcome.message) }
                }
            }
        }
    }

    fun restoreReply(reply: ReplySuggestion) {
        val original = _state.value.originals[reply.id] ?: return
        _state.update { state ->
            val activeResult = state.result ?: return@update state
            val replies = activeResult.replies.map { if (it.id == reply.id) it.copy(text = original) else it }
            state.copy(result = activeResult.copy(replies = replies))
        }
    }

    fun updateReplyText(reply: ReplySuggestion, text: String) {
        _state.update { state ->
            val activeResult = state.result ?: return@update state
            val replies = activeResult.replies.map { if (it.id == reply.id) it.copy(text = text) else it }
            state.copy(result = activeResult.copy(replies = replies))
        }
    }

    fun requestMoreReplies(persona: Persona) {
        val current = _state.value
        val result = current.result ?: return
        if (current.transformBusy) return
        val used = result.replies.map { it.text }
        val anchor = used.lastOrNull() ?: current.chatText

        viewModelScope.launch {
            _state.update { it.copy(transformBusy = true, error = null) }
            val outcome = runCatching {
                container.copilotEngine.moreReplies(
                    chatContent = current.chatText,
                    persona = persona,
                    original = anchor,
                    used = used,
                    personaBook = currentBookText(),
                )
            }.getOrElse { NetResult.Failure("生成失败：${it.message ?: "未知错误"}") }
            when (outcome) {
                is NetResult.Success -> {
                    _state.update { state ->
                        val activeResult = state.result ?: return@update state
                        val stamp = System.nanoTime()
                        val additions = outcome.value.mapIndexed { index, suggestion ->
                            suggestion.copy(id = "extra-$stamp-$index")
                        }
                        val merged = (activeResult.replies + additions).take(MAX_REPLIES)
                        state.copy(
                            transformBusy = false,
                            result = activeResult.copy(replies = merged),
                        )
                    }
                }

                is NetResult.Failure -> {
                    _state.update { it.copy(transformBusy = false, error = outcome.message) }
                }
            }
        }
    }

    // -------------------------------------------------------------- history

    fun openHistory(record: HistoryRecord) {
        _state.update {
            it.copy(
                chatText = record.inputText,
                intent = record.intentText,
                result = AnalysisResult(
                    analysis = record.analysis,
                    tone = record.tone,
                    strategy = record.strategy,
                    replies = record.replies,
                    replyingTo = record.replyingTo,
                ),
                providerLabel = record.modelLabel.substringBefore(" · "),
                modelLabel = record.modelLabel.substringAfter(" · ", ""),
                originals = emptyMap(),
            )
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch { runCatching { container.historyRepository.delete(id) } }
    }

    private companion object {
        const val MAX_IMAGES = 3
        const val MAX_REPLIES = 6
    }
}
