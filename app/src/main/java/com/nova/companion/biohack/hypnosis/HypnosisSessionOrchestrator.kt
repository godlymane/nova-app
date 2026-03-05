package com.nova.companion.biohack.hypnosis

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HypnosisSessionState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val currentPhase: HypnosisPhase = HypnosisPhase.INDUCTION,
    val phaseIndex: Int = 0,
    val phaseProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val elapsedSeconds: Int = 0,
    val totalSeconds: Int = 0,
    val currentScript: String = "",
    val protocolId: String = "",
    val protocolName: String = "",
    val screenFlashActive: Boolean = false,
    val allDayStrobeActive: Boolean = false
)

/**
 * HypnosisSessionOrchestrator — Central controller for hypnosis sessions.
 *
 * Manages the 5-phase hypnosis arc:
 * 1. INDUCTION — progressive relaxation, alpha waves (10Hz)
 * 2. DEEPENING — theta descent (6Hz), deeper trance
 * 3. SUGGESTION — deep theta (4Hz), protocol-specific affirmations
 * 4. ANCHORING — physical gesture anchor with affirmation loop
 * 5. EMERGENCE — beta ramp-up (14Hz), gentle wake
 *
 * Coordinates audio engine (binaural beats), haptic engine (pulse vibrations),
 * and voice engine (TTS narration) across all phase transitions.
 */
class HypnosisSessionOrchestrator(private val context: Context) {
    companion object {
        private const val TAG = "HypnosisOrch"
        private const val FREQUENCY_TRANSITION_MS = 5000L  // 5s smooth frequency crossfade
    }

    private val _sessionState = MutableStateFlow(HypnosisSessionState())
    val sessionState: StateFlow<HypnosisSessionState> = _sessionState.asStateFlow()

    private val audioEngine = HypnosisAudioEngine()
    private val hapticEngine = HypnosisHapticEngine()
    private val voiceEngine = HypnosisVoiceEngine()

    private var sessionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentProtocol: HypnosisProtocol? = null
    private var isPaused = false
    private var screenFlashEnabled = false
    private var allDayStrobeEnabled = false

    /**
     * Start a hypnosis session with the given protocol.
     * @param screenFlash If true, starts subliminal screen strobe synced to binaural freq
     * @param allDayStrobe If true, the strobe keeps running after the session ends
     */
    fun startSession(
        protocol: HypnosisProtocol,
        silentMode: Boolean,
        screenFlash: Boolean = false,
        allDayStrobe: Boolean = false
    ) {
        if (sessionJob?.isActive == true) {
            Log.w(TAG, "Session already active, stopping first")
            stopSession()
        }

        currentProtocol = protocol
        voiceEngine.isSilentMode = silentMode
        screenFlashEnabled = screenFlash
        allDayStrobeEnabled = allDayStrobe
        isPaused = false

        val totalSeconds = protocol.totalDurationSeconds

        _sessionState.value = HypnosisSessionState(
            isActive = true,
            totalSeconds = totalSeconds,
            protocolId = protocol.id,
            protocolName = protocol.name,
            currentPhase = protocol.phases.first().phase,
            screenFlashActive = screenFlash,
            allDayStrobeActive = allDayStrobe
        )

        sessionJob = scope.launch {
            Log.i(TAG, "Session started: ${protocol.name} (${totalSeconds}s, silent=$silentMode, flash=$screenFlash, allDay=$allDayStrobe)")

            val firstPhase = protocol.phases.first()
            audioEngine.start(
                binauralHz = firstPhase.binauralHz,
                carrierHz = firstPhase.carrierHz,
                volume = 0.4f
            )
            hapticEngine.start(context, firstPhase.hapticHz, amp = 50)

            // Start subliminal screen strobe if enabled
            if (screenFlash) {
                val accentArgb = protocol.accentColor.let {
                    AndroidColor.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
                }
                SubliminalStrobeService.start(
                    context = context,
                    frequencyHz = firstPhase.binauralHz,
                    colorArgb = accentArgb,
                    protocolName = protocol.name
                )
            }

            var globalElapsed = 0

            for ((phaseIndex, phaseConfig) in protocol.phases.withIndex()) {
                if (!isActive) break

                Log.i(TAG, "Phase: ${phaseConfig.phase.displayName} " +
                    "(${phaseConfig.durationSeconds}s, binaural=${phaseConfig.binauralHz}Hz)")

                // Transition frequencies (skip for first phase — already set)
                if (phaseIndex > 0) {
                    audioEngine.transitionTo(
                        binauralHz = phaseConfig.binauralHz,
                        carrierHz = phaseConfig.carrierHz,
                        durationMs = FREQUENCY_TRANSITION_MS
                    )
                    hapticEngine.transitionTo(phaseConfig.hapticHz)
                    // Sync subliminal strobe to new binaural frequency
                    if (screenFlashEnabled) {
                        SubliminalStrobeService.updateFrequency(phaseConfig.binauralHz)
                    }
                }

                // Calculate script timing
                val scripts = phaseConfig.scripts
                val scriptInterval = if (scripts.isNotEmpty()) {
                    phaseConfig.durationSeconds / scripts.size
                } else 0
                var nextScriptIndex = 0
                var secondsInPhase = 0

                // Speak first script line at phase start
                if (scripts.isNotEmpty()) {
                    launch {
                        voiceEngine.speakScript(
                            text = scripts[0],
                            context = context,
                            onScriptText = { text ->
                                _sessionState.value = _sessionState.value.copy(currentScript = text)
                            }
                        )
                    }
                    nextScriptIndex = 1
                }

                // Tick through this phase
                while (isActive && secondsInPhase < phaseConfig.durationSeconds) {
                    // Handle pause
                    while (isPaused && isActive) {
                        delay(200)
                    }
                    if (!isActive) break

                    delay(1000)
                    secondsInPhase++
                    globalElapsed++

                    // Update state
                    _sessionState.value = _sessionState.value.copy(
                        currentPhase = phaseConfig.phase,
                        phaseIndex = phaseIndex,
                        phaseProgress = secondsInPhase.toFloat() / phaseConfig.durationSeconds,
                        overallProgress = globalElapsed.toFloat() / totalSeconds,
                        elapsedSeconds = globalElapsed
                    )

                    // Trigger next script line at interval
                    if (scriptInterval > 0 &&
                        nextScriptIndex < scripts.size &&
                        secondsInPhase >= nextScriptIndex * scriptInterval
                    ) {
                        val scriptText = scripts[nextScriptIndex]
                        nextScriptIndex++
                        launch {
                            voiceEngine.speakScript(
                                text = scriptText,
                                context = context,
                                onScriptText = { text ->
                                    _sessionState.value = _sessionState.value.copy(currentScript = text)
                                }
                            )
                        }
                    }
                }
            }

            // Session complete
            Log.i(TAG, "Session complete: ${protocol.name}")
            stopEngines()
            // If all-day strobe is NOT enabled, stop the strobe service on session end
            if (screenFlashEnabled && !allDayStrobeEnabled) {
                SubliminalStrobeService.stop(context)
            }
            _sessionState.value = _sessionState.value.copy(
                isActive = false,
                overallProgress = 1f,
                currentScript = "",
                allDayStrobeActive = allDayStrobeEnabled && screenFlashEnabled
            )
        }
    }

    fun pauseSession() {
        if (!(_sessionState.value.isActive)) return
        isPaused = true
        audioEngine.stop()
        hapticEngine.stop(context)
        voiceEngine.stopSpeaking()
        _sessionState.value = _sessionState.value.copy(isPaused = true)
        Log.i(TAG, "Session paused")
    }

    fun resumeSession() {
        if (!isPaused) return
        isPaused = false

        // Restart engines with current phase frequencies
        val protocol = currentProtocol ?: return
        val phaseIndex = _sessionState.value.phaseIndex
        val phaseConfig = protocol.phases.getOrNull(phaseIndex) ?: return

        audioEngine.start(
            binauralHz = phaseConfig.binauralHz,
            carrierHz = phaseConfig.carrierHz,
            volume = 0.4f
        )
        hapticEngine.start(context, phaseConfig.hapticHz, amp = 50)

        _sessionState.value = _sessionState.value.copy(isPaused = false)
        Log.i(TAG, "Session resumed")
    }

    fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        stopEngines()
        isPaused = false
        currentProtocol = null
        _sessionState.value = HypnosisSessionState()
        Log.i(TAG, "Session stopped")
    }

    fun cleanup() {
        stopSession()
        voiceEngine.cleanup()
        scope.cancel()
    }

    private fun stopEngines() {
        audioEngine.stop()
        hapticEngine.stop(context)
        voiceEngine.stopSpeaking()
    }
}
