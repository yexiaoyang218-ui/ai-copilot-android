package com.aisocial.copilot.data.repo

import com.aisocial.copilot.data.local.PersonaDao
import com.aisocial.copilot.data.local.PersonaEntity
import com.aisocial.copilot.domain.Presets
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.ReplyLength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed persona storage. */
class PersonaRepository(private val dao: PersonaDao) {

    val personas: Flow<List<Persona>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun ensurePresets() {
        if (dao.count() > 0) return
        Presets.all.forEach { preset -> dao.insert(preset.toEntity()) }
    }

    suspend fun resetPresets() {
        Presets.all.forEach { preset ->
            val key = preset.presetKey ?: return@forEach
            if (dao.findByPresetKey(key) == null) {
                dao.insert(preset.toEntity())
            }
        }
    }

    suspend fun byId(id: Long): Persona? = dao.findById(id)?.toDomain()

    /** Inserts when id == 0, otherwise updates. Returns the row id. */
    suspend fun save(persona: Persona): Long {
        val now = System.currentTimeMillis()
        return if (persona.id == 0L) {
            dao.insert(persona.copy(createdAt = now, updatedAt = now).toEntity())
        } else {
            dao.update(persona.copy(updatedAt = now).toEntity())
            persona.id
        }
    }

    suspend fun delete(persona: Persona) {
        dao.deleteById(persona.id)
    }

    suspend fun duplicate(persona: Persona): Long {
        val copy = persona.copy(
            id = 0L,
            name = "${persona.name} 副本",
            isPreset = false,
            presetKey = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
        return save(copy)
    }

    companion object {
        fun Persona.toEntity(): PersonaEntity = PersonaEntity(
            id = id,
            name = name,
            description = description,
            tone = tone,
            length = length.name,
            emotion = emotion,
            initiative = initiative,
            humor = humor,
            catchphrases = catchphrases,
            forbidden = forbidden,
            customInstruction = customInstruction,
            presetKey = presetKey,
            isPreset = isPreset,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        fun PersonaEntity.toDomain(): Persona = Persona(
            id = id,
            name = name,
            description = description,
            tone = tone,
            length = ReplyLength.fromName(length),
            emotion = emotion,
            initiative = initiative,
            humor = humor,
            catchphrases = catchphrases,
            forbidden = forbidden,
            customInstruction = customInstruction,
            presetKey = presetKey,
            isPreset = isPreset,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
