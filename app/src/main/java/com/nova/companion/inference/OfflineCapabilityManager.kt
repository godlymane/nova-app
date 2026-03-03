package com.nova.companion.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * OfflineCapabilityManager — tracks network state and determines what Nova
 * can do at any given moment.
 *
 * Capabilities are tiered:
 * - FULL: Online + local model → everything works
 * - CLOUD_ONLY: Online + no local model → cloud works, instant responses slow
 * - LOCAL_ONLY: Offline + local model → chat, basic tools, no web/live data
 * - DEGRADED: Offline + no local model → only cached responses, pre-baked messages
 *
 * Components observe [capabilityLevel] to adapt their behavior:
 * - ChatViewModel: Routes messages through HybridInferenceRouter
 * - VoicePipeline: Chooses online vs offline voice path
 * - ToolRegistry: Filters available tools based on connectivity
 * - ProactiveEngine: Limits to local-only checks when offline
 */
object OfflineCapabilityManager {

    private const val TAG = "OfflineCap"

    enum class CapabilityLevel {
        FULL,          // Online + local model
        CLOUD_ONLY,    // Online + no local model
        LOCAL_ONLY,    // Offline + local model
        DEGRADED       // Offline + no local model
    }

    // ── State ────────────────────────────────────────────────────────

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isLocalModelReady = MutableStateFlow(false)
    val isLocalModelReady: StateFlow<Boolean> = _isLocalModelReady.asStateFlow()

    private val _capabilityLevel = MutableStateFlow(CapabilityLevel.DEGRADED)
    val capabilityLevel: StateFlow<CapabilityLevel> = _capabilityLevel.asStateFlow()

    private val _networkType = MutableStateFlow("none")
    val networkType: StateFlow<String> = _networkType.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── Initialization ───────────────────────────────────────────────

    /**
     * Initialize network monitoring. Call once from Application.onCreate() or
     * ChatViewModel init.
     */
    fun initialize(context: Context) {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Check initial state
        updateNetworkState(context)
        updateLocalModelState()
        recalculateCapability()

        // Register callback for ongoing monitoring
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                _isOnline.value = true
                updateNetworkType(network)
                recalculateCapability()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                _isOnline.value = false
                _networkType.value = "none"
                recalculateCapability()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _isOnline.value = hasInternet && validated

                // Detect network type
                _networkType.value = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
                recalculateCapability()
            }
        }

        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, callback)

        Log.i(TAG, "Initialized: online=${_isOnline.value}, localModel=${_isLocalModelReady.value}, " +
                "capability=${_capabilityLevel.value}")
    }

    /**
     * Call when local model state changes (loaded/unloaded).
     */
    fun updateLocalModelState() {
        val wasReady = _isLocalModelReady.value
        _isLocalModelReady.value = NovaInference.isReady()
        if (wasReady != _isLocalModelReady.value) {
            Log.d(TAG, "Local model state changed: ${_isLocalModelReady.value}")
            recalculateCapability()
        }
    }

    /**
     * Unregister network callback. Call from onCleared().
     */
    fun shutdown() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback", e)
            }
        }
        networkCallback = null
    }

    // ── Capability Queries ───────────────────────────────────────────

    /**
     * Check if a specific tool can be used right now.
     */
    fun isToolAvailable(toolName: String): Boolean {
        if (_isOnline.value) return true // All tools available online
        return toolName in HybridInferenceRouter.OFFLINE_TOOLS
    }

    /**
     * Get the set of currently available tools.
     */
    fun getAvailableTools(): Set<String> {
        return if (_isOnline.value) {
            com.nova.companion.tools.ToolRegistry.getAllTools().keys
        } else {
            HybridInferenceRouter.OFFLINE_TOOLS
        }
    }

    /**
     * Check if chat is available in any form.
     */
    fun isChatAvailable(): Boolean {
        return _isOnline.value || _isLocalModelReady.value
    }

    /**
     * Check if voice is available.
     */
    fun isVoiceAvailable(): Boolean {
        // Online: full Whisper + ElevenLabs pipeline
        // Offline: Android STT + local LLM + Android TTS (if model loaded)
        return _isOnline.value || _isLocalModelReady.value
    }

    /**
     * Get a user-friendly status string for the current capability level.
     */
    fun getStatusMessage(): String {
        return when (_capabilityLevel.value) {
            CapabilityLevel.FULL -> "All systems go"
            CapabilityLevel.CLOUD_ONLY -> "Cloud ready (load local model for instant responses)"
            CapabilityLevel.LOCAL_ONLY -> "Offline mode — basic chat and device controls available"
            CapabilityLevel.DEGRADED -> "Offline — load a model in Settings to chat offline"
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun updateNetworkState(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        _isOnline.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun updateNetworkType(network: Network) {
        val caps = connectivityManager?.getNetworkCapabilities(network)
        _networkType.value = when {
            caps == null -> "unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun recalculateCapability() {
        val online = _isOnline.value
        val localReady = _isLocalModelReady.value

        val newLevel = when {
            online && localReady -> CapabilityLevel.FULL
            online && !localReady -> CapabilityLevel.CLOUD_ONLY
            !online && localReady -> CapabilityLevel.LOCAL_ONLY
            else -> CapabilityLevel.DEGRADED
        }

        if (_capabilityLevel.value != newLevel) {
            Log.i(TAG, "Capability level changed: ${_capabilityLevel.value} → $newLevel")
            _capabilityLevel.value = newLevel
        }
    }
}
