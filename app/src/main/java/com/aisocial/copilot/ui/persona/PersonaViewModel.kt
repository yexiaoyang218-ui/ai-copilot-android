package com.aisocial.copilot.ui.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aisocial.copilot.di.AppContainer
import com.aisocial.copilot.domain.model.Persona
import com.aisocial.copilot.domain.model.PersonaBook
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class PersonaViewModel(private val container: AppContainer) : ViewModel() {

    val personas: StateFlow<List<Persona>> = container.personaRepository.personas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val books: StateFlow<List<PersonaBook>> = container.personaBookRepository.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun load(id: Long): Persona? = container.personaRepository.byId(id)

    suspend fun save(persona: Persona): Long = container.personaRepository.save(persona)

    suspend fun delete(persona: Persona) = container.personaRepository.delete(persona)

    suspend fun duplicate(persona: Persona): Long = container.personaRepository.duplicate(persona)

    suspend fun resetPresets() = container.personaRepository.resetPresets()

    // ------------------------------------------------------------- 人设书

    suspend fun loadBook(id: Long): PersonaBook? = container.personaBookRepository.byId(id)

    /** Saving a book activates it, so the very next recognition uses the new text. */
    suspend fun saveBook(book: PersonaBook): Long = container.personaBookRepository.save(book)

    suspend fun deleteBook(id: Long) = container.personaBookRepository.delete(id)

    suspend fun activateBook(id: Long) = container.personaBookRepository.setActive(id)

    suspend fun deactivateBooks() = container.personaBookRepository.deactivateAll()

    suspend fun activeBook(): PersonaBook? = container.personaBookRepository.active()
}
