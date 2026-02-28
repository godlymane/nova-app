package com.nova.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nova.companion.brain.context.ContextEngine
import com.nova.companion.databinding.ActivityMainBinding
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.ui.chat.ChatViewModel

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ChatViewModel by viewModels()

    // ─────────────────────────────────────────────
    // Permission launcher (handles all runtime permissions)
    // ─────────────────────────────────────────────
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            results.forEach { (permission, granted) ->
                Log.d(TAG, "Permission $permission: ${if (granted) "GRANTED" else "DENIED"}")
            }
            // Start ContextEngine regardless — it degrades gracefully
            startContextEngine()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        requestBrainPermissions()
    }

    // ─────────────────────────────────────────────
    // Brain permissions + ContextEngine startup
    // ─────────────────────────────────────────────

    private fun requestBrainPermissions() {
        val permissionsNeeded = ToolPermissionHelper.BRAIN_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsNeeded.isEmpty()) {
            // All already granted
            startContextEngine()
        } else {
            Log.d(TAG, "Requesting brain permissions: ${permissionsNeeded.joinToString()}")
            permissionLauncher.launch(permissionsNeeded)
        }
    }

    private fun startContextEngine() {
        try {
            ContextEngine.start(this)
            Log.i(TAG, "ContextEngine started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ContextEngine", e)
        }
    }

    // ─────────────────────────────────────────────
    // UI setup (unchanged from original)
    // ─────────────────────────────────────────────

    private fun setupUI() {
        // Existing UI setup code here
        // ... (unchanged)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Note: ContextEngine is a foreground service, it keeps running
        // Only stop it if you want it to stop when app is closed
        // ContextEngine.stop(this)
    }
}
