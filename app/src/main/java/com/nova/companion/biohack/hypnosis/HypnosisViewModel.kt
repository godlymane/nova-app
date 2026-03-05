package com.nova.companion.biohack.hypnosis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class HypnosisViewModel(application: Application) : AndroidViewModel(application) {

    private val orchestrator = HypnosisSessionOrchestrator(application.applicationContext)

    val sessionState: StateFlow<HypnosisSessionState> = orchestrator.sessionState

    fun startSession(
        protocolId: String,
        silentMode: Boolean,
        screenFlash: Boolean = false,
        allDayStrobe: Boolean = false
    ) {
        val protocol = HypnosisProtocols.getById(protocolId) ?: return
        orchestrator.startSession(protocol, silentMode, screenFlash, allDayStrobe)
    }

    fun pauseSession() = orchestrator.pauseSession()

    fun resumeSession() = orchestrator.resumeSession()

    fun stopSession() = orchestrator.stopSession()

    override fun onCleared() {
        super.onCleared()
        orchestrator.cleanup()
    }
}
