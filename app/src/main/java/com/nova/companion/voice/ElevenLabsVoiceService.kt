package com.nova.companion.voice

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import ai.elevenlabs.sdk.ElevenLabsClient
import ai.elevenlabs.sdk.ElevenLabsConfig
import ai.elevenlabs.sdk.ConversationSession
import ai.elevenlabs.sdk.SessionConfig
import ai.elevenlabs.sdk.AudioConfig
import ai.elevenlabs.sdk.SessionCallbacks
import com.nova.companion.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages ElevenLabs conversational AI voice sessions.
 * Handles session lifecycle, audio routing, and brain context injection.
 */
class ElevenLabsVoiceService(private val context: Context) {

    companion object {
        private const val TAG = "ElevenLabsVoiceService"
        private const val AGENT_ID = BuildConfig.ELEVENLABS_AGENT_ID
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentSession: ConversationSession? = null
    private var sessionCallbacks: VoiceSessionCallbacks? = null

    interface VoiceSessionCallbacks {
        fun onSessionStarted()
        fun onSessionEnded()
        fun onUserTranscript(text: String)
        fun onAgentResponse(text: String)
        fun onError(error: String)
    }

    fun setCallbacks(callbacks: VoiceSessionCallbacks) {
        this.sessionCallbacks = callbacks
    }

    // ─────────────────────────────────────────────
    // Session management
    // ─────────────────────────────────────────────

    fun startSession() {
        if (!hasAudioPermission()) {
            Log.e(TAG, "Missing RECORD_AUDIO permission")
            sessionCallbacks?.onError("Microphone permission required")
            return
        }

        serviceScope.launch {
            try {
                val client = ElevenLabsClient(
                    ElevenLabsConfig(apiKey = BuildConfig.ELEVENLABS_API_KEY)
                )

                val config = SessionConfig(
                    agentId = AGENT_ID,
                    audioConfig = AudioConfig(
                        inputSampleRate = 16000,
                        outputSampleRate = 24000
                    )
                )

                val callbacks = object : SessionCallbacks {
                    override fun onOpen() {
                        Log.i(TAG, "ElevenLabs session opened")
                        sessionCallbacks?.onSessionStarted()
                    }

                    override fun onClose(code: Int, reason: String) {
                        Log.i(TAG, "ElevenLabs session closed: $code $reason")
                        currentSession = null
                        sessionCallbacks?.onSessionEnded()
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "ElevenLabs session error: $error")
                        sessionCallbacks?.onError(error)
                    }

                    override fun onUserTranscript(transcript: String) {
                        Log.d(TAG, "User: $transcript")
                        sessionCallbacks?.onUserTranscript(transcript)
                    }

                    override fun onAgentResponse(response: String) {
                        Log.d(TAG, "Agent: $response")
                        sessionCallbacks?.onAgentResponse(response)
                    }
                }

                currentSession = client.startConversation(config, callbacks)
                Log.i(TAG, "ElevenLabs session started")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ElevenLabs session", e)
                sessionCallbacks?.onError("Failed to start voice session: ${e.message}")
            }
        }
    }

    fun endSession() {
        serviceScope.launch {
            try {
                currentSession?.end()
                currentSession = null
                Log.i(TAG, "ElevenLabs session ended")
            } catch (e: Exception) {
                Log.e(TAG, "Error ending session", e)
            }
        }
    }

    fun isSessionActive(): Boolean = currentSession != null

    // ─────────────────────────────────────────────
    // Brain context injection
    // ─────────────────────────────────────────────

    /**
     * Send a contextual update to the ElevenLabs agent.
     * Called at the start of each voice session with Nova Brain context.
     *
     * This injects the brain's contextual addendum (time, situation, memory, tone)
     * into the ongoing conversation so the agent can use it immediately.
     *
     * Should be called after onSessionStarted() callback fires.
     */
    fun sendContextualUpdate(contextualPrompt: String) {
        val session = currentSession
        if (session == null) {
            Log.w(TAG, "sendContextualUpdate called but no active session")
            return
        }

        serviceScope.launch {
            try {
                // Send as a system-level context injection
                // ElevenLabs SDK supports sending context via the session's override mechanism
                session.updateContext(contextualPrompt)
                Log.d(TAG, "Brain context injected into ElevenLabs session (${contextualPrompt.length} chars)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject brain context (non-fatal): ${e.message}")
                // Non-fatal — session continues without brain context
            }
        }
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun destroy() {
        endSession()
        serviceScope.cancel()
    }
}
