package com.aisocial.copilot.data.repo

import com.aisocial.copilot.data.local.HistoryDao
import com.aisocial.copilot.data.local.HistoryEntity
import com.aisocial.copilot.domain.model.ReplySuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StoredReply(val label: String, val text: String)

data class HistoryRecord(
    val id: Long,
    val createdAt: Long,
    val inputText: String,
    val intentText: String,
    val personaName: String,
    val analysis: String,
    val tone: String,
    val strategy: String,
    val replies: List<ReplySuggestion>,
    val modelLabel: String,
    val replyingTo: String = "",
)

/** Optional, user-disableable local history. Never stores screenshots. */
class HistoryRepository(private val dao: HistoryDao) {

    private val json = Json { ignoreUnknownKeys = true }

    fun recent(limit: Int = 30): Flow<List<HistoryRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    suspend fun save(
        inputText: String,
        intentText: String,
        personaName: String,
        analysis: String,
        tone: String,
        strategy: String,
        replies: List<ReplySuggestion>,
        modelLabel: String,
        replyingTo: String = "",
    ): Long {
        val payload = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(StoredReply.serializer()),
            replies.map { StoredReply(it.label, it.text) }
        )
        return dao.insert(
            HistoryEntity(
                createdAt = System.currentTimeMillis(),
                inputText = inputText.take(4000),
                intentText = intentText.take(1000),
                personaName = personaName,
                analysis = analysis,
                toneJudge = tone,
                strategy = strategy,
                repliesJson = payload,
                modelLabel = modelLabel,
                replyingTo = replyingTo.take(200),
            )
        )
    }

    suspend fun clear() = dao.clearAll()

    suspend fun delete(id: Long) = dao.deleteById(id)

    private fun HistoryEntity.toDomain(): HistoryRecord {
        val replies = runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(StoredReply.serializer()),
                repliesJson
            )
        }.getOrElse { emptyList() }
        return HistoryRecord(
            id = id,
            createdAt = createdAt,
            inputText = inputText,
            intentText = intentText,
            personaName = personaName,
            analysis = analysis,
            tone = toneJudge,
            strategy = strategy,
            replies = replies.mapIndexed { index, stored ->
                ReplySuggestion(id = "$id-$index", label = stored.label, text = stored.text)
            },
            modelLabel = modelLabel,
            replyingTo = replyingTo,
        )
    }
}
