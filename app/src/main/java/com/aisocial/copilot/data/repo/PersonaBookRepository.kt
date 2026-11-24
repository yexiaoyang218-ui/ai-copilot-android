package com.aisocial.copilot.data.repo

import com.aisocial.copilot.data.local.PersonaBookDao
import com.aisocial.copilot.data.local.PersonaBookEntity
import com.aisocial.copilot.domain.model.PersonaBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 人设书 storage. Exactly one book can be active at a time; the active book is
 * read fresh on every generation, so edits take effect immediately.
 */
class PersonaBookRepository(private val dao: PersonaBookDao) {

    val books: Flow<List<PersonaBook>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun active(): PersonaBook? = dao.findActive()?.toDomain()

    suspend fun byId(id: Long): PersonaBook? = dao.findById(id)?.toDomain()

    /**
     * Saves the book and (by default) makes it the active one, so the next
     * recognition/generation already uses the new text.
     */
    suspend fun save(book: PersonaBook, activate: Boolean = true): Long {
        val now = System.currentTimeMillis()
        val id = if (book.id == 0L) {
            dao.insert(
                book.copy(createdAt = now, updatedAt = now, isActive = false).toEntity()
            )
        } else {
            dao.update(book.copy(updatedAt = now).toEntity())
            book.id
        }
        if (activate) {
            dao.clearActive()
            dao.activate(id, now)
        }
        return id
    }

    suspend fun setActive(id: Long) {
        dao.clearActive()
        dao.activate(id, System.currentTimeMillis())
    }

    suspend fun deactivateAll() = dao.clearActive()

    suspend fun delete(id: Long) = dao.deleteById(id)

    companion object {

        fun PersonaBook.toEntity(): PersonaBookEntity = PersonaBookEntity(
            id = id,
            title = title,
            content = content,
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        fun PersonaBookEntity.toDomain(): PersonaBook = PersonaBook(
            id = id,
            title = title,
            content = content,
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
