package com.nova.companion.tools.tier2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object ToggleBluetoothToolExecutor {

    private const val TAG = "ToggleBluetoothTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "toggleBluetooth",
            description = "Turn Bluetooth on or off on the device.",
            parameters = mapOf(
                "enabled" to ToolParam(
                    type = "boolean",
                    description = "Whether to turn Bluetooth on (true) or off (false)",
                    required = true
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val enabled = params["enabled"] as? Boolean
                ?: return ToolResult(false, "The enabled parameter is required (true or false)")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ — cannot toggle programmatically, open settings
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.i(TAG, "Opened Bluetooth settings (API 33+)")
                ToolResult(true, "Opening Bluetooth settings — please toggle Bluetooth ${if (enabled) "on" else "off"}")
            } else {
                // Android 12 and below — toggle directly
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (!ToolPermissionHelper.hasPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)) {
                        return ToolResult(false, "Bluetooth permission not granted. Please enable it in Settings.")
                    }
                }

                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter
                    ?: return ToolResult(false, "This device does not support Bluetooth")

                @Suppress("DEPRECATION")
                val result = if (enabled) adapter.enable() else adapter.disable()

                if (result) {
                    Log.i(TAG, "Bluetooth turned ${if (enabled) "on" else "off"}")
                    ToolResult(true, "Bluetooth turned ${if (enabled) "on" else "off"}")
                } else {
                    ToolResult(false, "Failed to toggle Bluetooth — it may already be ${if (enabled) "on" else "off"}")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied", e)
            ToolResult(false, "Bluetooth permission not granted. Please enable it in Settings.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth", e)
            ToolResult(false, "Failed to toggle Bluetooth: ${e.message}")
        }
    }
}
