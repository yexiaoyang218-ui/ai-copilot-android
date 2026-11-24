package com.aisocial.copilot

import android.app.Application
import com.aisocial.copilot.di.AppContainer

/**
 * Application entry point. Owns the single [AppContainer] used as a lightweight,
 * manual dependency-injection graph. No business logic lives here.
 */
class AiCopilotApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
