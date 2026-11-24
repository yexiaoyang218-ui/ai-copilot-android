package com.aisocial.copilot.di

import android.content.Context
import androidx.room.Room
import com.aisocial.copilot.ai.CopilotEngine
import com.aisocial.copilot.data.local.AppDatabase
import com.aisocial.copilot.data.prefs.SettingsRepository
import com.aisocial.copilot.data.repo.HistoryRepository
import com.aisocial.copilot.data.repo.PersonaBookRepository
import com.aisocial.copilot.data.repo.PersonaRepository
import com.aisocial.copilot.data.secure.SecretVault
import com.aisocial.copilot.ocr.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency graph shared by the whole process.
 *
 * Layering: UI -> ViewModel -> (CopilotEngine | Repository) -> (Room | DataStore | Network | ML Kit).
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val secretVault: SecretVault = SecretVault()

    val settingsRepository: SettingsRepository = SettingsRepository(appContext, secretVault)

    private val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "ai_copilot.db"
    )
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()

    val personaRepository: PersonaRepository = PersonaRepository(database.personaDao())

    val personaBookRepository: PersonaBookRepository = PersonaBookRepository(database.personaBookDao())

    val historyRepository: HistoryRepository = HistoryRepository(database.historyDao())

    val copilotEngine: CopilotEngine = CopilotEngine(settingsRepository, historyRepository)
    val ocrEngine: OcrEngine = OcrEngine()

    init {
        // Seed the built-in persona presets the first time the app runs.
        appScope.launch {
            runCatching { personaRepository.ensurePresets() }
        }
    }
}
