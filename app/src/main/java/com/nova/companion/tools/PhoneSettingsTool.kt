package com.nova.companion.tools

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat.getSystemService

object PhoneSettingsTool {
    fun register(registry: ToolRegistry, context: Context) {
        val tool = NovaTool(
            name = "phone_settings",
            description = "Controls phone settings like WiFi, Bluetooth, Do Not Disturb, flashlight, location, etc.",
            parameters = mapOf(
                "setting" to ToolParam(
                    type = "string",
                    description = "Setting to control: 'wifi', 'bluetooth', 'dnd' (do not disturb), 'flashlight', 'airplane', 'hotspot', 'location', 'nfc'",
                    required = true
                ),
                "action" to ToolParam(
                    type = "string",
                    description = "Action to perform: 'on', 'off', 'toggle', or 'open' (to open settings page)",
                    required = true
                )
            ),
            executor = { ctx, params ->
                executePhoneSettings(ctx, params)
            }
        )
        registry.registerTool(tool)
    }

    private suspend fun executePhoneSettings(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val setting = (params["setting"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "Setting parameter is required")

            val action = (params["action"] as? String)?.trim()?.lowercase()
                ?: return ToolResult(false, "Action parameter is required")

            if (!isValidAction(action)) {
                return ToolResult(false, "Invalid action: '$action'. Use 'on', 'off', 'toggle', or 'open'")
            }

            when (setting) {
                "wifi" -> handleWifiSetting(context, action)
                "bluetooth" -> handleBluetoothSetting(context, action)
                "dnd", "do not disturb" -> handleDndSetting(context, action)
                "flashlight", "torch" -> handleFlashlightSetting(context, action)
                "airplane", "airplane mode" -> handleAirplaneSetting(context, action)
                "hotspot", "mobile hotspot" -> handleHotspotSetting(context, action)
                "location" -> handleLocationSetting(context, action)
                "nfc" -> handleNfcSetting(context, action)
                else -> ToolResult(false, "Unsupported setting: '$setting'. Supported: wifi, bluetooth, dnd, flashlight, airplane, hotspot, location, nfc")
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to change settings: ${e.message}")
        }
    }

    private fun isValidAction(action: String): Boolean {
        return action in listOf("on", "off", "toggle", "open")
    }

    private fun handleWifiSetting(context: Context, action: String): ToolResult {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val currentState = wifiManager.isWifiEnabled

            when (action) {
                "on" -> {
                    if (!currentState) {
                        wifiManager.isWifiEnabled = true
                        ToolResult(true, "WiFi turned on")
                    } else {
                        ToolResult(true, "WiFi is already on")
                    }
                }
                "off" -> {
                    if (currentState) {
                        wifiManager.isWifiEnabled = false
                        ToolResult(true, "WiFi turned off")
                    } else {
                        ToolResult(true, "WiFi is already off")
                    }
                }
                "toggle" -> {
                    wifiManager.isWifiEnabled = !currentState
                    ToolResult(true, "WiFi turned ${if (!currentState) "on" else "off"}")
                }
                "open" -> {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolResult(true, "Opening WiFi settings")
                }
                else -> ToolResult(false, "Invalid action")
            }
        } catch (e: Exception) {
            ToolResult(false, "Unable to control WiFi: ${e.message}")
        }
    }

    private fun handleBluetoothSetting(context: Context, action: String): ToolResult {
        return try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                ?: return ToolResult(false, "Bluetooth is not available on this device")

            when (action) {
                "on" -> {
                    if (!bluetoothAdapter.isEnabled) {
                        bluetoothAdapter.enable()
                        ToolResult(true, "Bluetooth turned on")
                    } else {
                        ToolResult(true, "Bluetooth is already on")
                    }
                }
                "off" -> {
                    if (bluetoothAdapter.isEnabled) {
                        bluetoothAdapter.disable()
                        ToolResult(true, "Bluetooth turned off")
                    } else {
                        ToolResult(true, "Bluetooth is already off")
                    }
                }
                "toggle" -> {
                    val newState = !bluetoothAdapter.isEnabled
                    if (newState) {
                        bluetoothAdapter.enable()
                    } else {
                        bluetoothAdapter.disable()
                    }
                    ToolResult(true, "Bluetooth turned ${if (newState) "on" else "off"}")
                }
                "open" -> {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolResult(true, "Opening Bluetooth settings")
                }
                else -> ToolResult(false, "Invalid action")
            }
        } catch (e: Exception) {
            ToolResult(false, "Unable to control Bluetooth: ${e.message}")
        }
    }

    private fun handleDndSetting(context: Context, action: String): ToolResult {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return ToolResult(false, "Do Not Disturb control requires Android 6.0 or higher")
            }

            val currentState = notificationManager.isNotificationPolicyAccessGranted &&
                    notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

            when (action) {
                "on" -> {
                    if (!notificationManager.isNotificationPolicyAccessGranted) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        return ToolResult(true, "Please grant Do Not Disturb permission in settings")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                        ToolResult(true, "Do Not Disturb turned on")
                    } else {
                        ToolResult(false, "Unable to enable Do Not Disturb")
                    }
                }
                "off" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                        ToolResult(true, "Do Not Disturb turned off")
                    } else {
                        ToolResult(false, "Unable to disable Do Not Disturb")
                    }
                }
                "toggle" -> {
                    val newFilter = if (currentState) {
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    } else {
                        NotificationManager.INTERRUPTION_FILTER_NONE
                    }
                    notificationManager.setInterruptionFilter(newFilter)
                    ToolResult(true, "Do Not Disturb turned ${if (newFilter == NotificationManager.INTERRUPTION_FILTER_NONE) "on" else "off"}")
                }
                "open" -> {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolResult(true, "Opening Do Not Disturb settings")
                }
                else -> ToolResult(false, "Invalid action")
            }
        } catch (e: Exception) {
            ToolResult(false, "Unable to control Do Not Disturb: ${e.message}")
        }
    }

    private fun handleFlashlightSetting(context: Context, action: String): ToolResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ToolResult(false, "Camera service not available")

            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                return ToolResult(false, "No camera found for flashlight")
            }

            val flashCameraId = cameraIds.firstOrNull()
                ?: return ToolResult(false, "Cannot access flashlight camera")

            when (action) {
                "on" -> {
                    try {
                        cameraManager.setTorchMode(flashCameraId, true)
                        ToolResult(true, "Flashlight turned on")
                    } catch (e: Exception) {
                        ToolResult(false, "Failed to turn on flashlight: ${e.message}")
                    }
                }
                "off" -> {
                    try {
                        cameraManager.setTorchMode(flashCameraId, false)
                        ToolResult(true, "Flashlight turned off")
                    } catch (e: Exception) {
                        ToolResult(false, "Failed to turn off flashlight: ${e.message}")
                    }
                }
                "toggle" -> {
                    try {
                        cameraManager.setTorchMode(flashCameraId, true)
                        ToolResult(true, "Flashlight toggled on")
                    } catch (e: Exception) {
                        try {
                            cameraManager.setTorchMode(flashCameraId, false)
                            ToolResult(true, "Flashlight toggled off")
                        } catch (ex: Exception) {
                            ToolResult(false, "Failed to toggle flashlight: ${ex.message}")
                        }
                    }
                }
                "open" -> ToolResult(false, "Flashlight does not have a settings page")
                else -> ToolResult(false, "Invalid action")
            }
        } catch (e: Exception) {
            ToolResult(false, "Unable to control flashlight: ${e.message}")
        }
    }

    private fun handleAirplaneSetting(context: Context, action: String): ToolResult {
        return when (action) {
            "open" -> {
                val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToolResult(true, "Opening Airplane Mode settings")
            }
            else -> {
                ToolResult(false, "Airplane mode control requires system access. Please enable/disable manually in settings")
            }
        }
    }

    private fun handleHotspotSetting(context: Context, action: String): ToolResult {
        return when (action) {
            "open" -> {
                val intent = Intent("android.settings.TETHERING_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    ToolResult(true, "Opening Hotspot settings")
                } catch (e: Exception) {
                    val altIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(altIntent)
                    ToolResult(true, "Opening wireless settings")
                }
            }
            else -> {
                ToolResult(false, "Mobile Hotspot control requires system access. Please enable/disable manually in settings")
            }
        }
    }

    private fun handleLocationSetting(context: Context, action: String): ToolResult {
        return when (action) {
            "open" -> {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToolResult(true, "Opening Location settings")
            }
            else -> {
                ToolResult(false, "Location control requires system access. Please enable/disable manually in settings")
            }
        }
    }

    private fun handleNfcSetting(context: Context, action: String): ToolResult {
        return when (action) {
            "open" -> {
                val intent = Intent(Settings.ACTION_NFC_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                    ToolResult(true, "Opening NFC settings")
                } catch (e: Exception) {
                    ToolResult(false, "NFC settings not available on this device")
                }
            }
            else -> {
                ToolResult(false, "NFC control requires system access. Please enable/disable manually in settings")
            }
        }
    }
}
