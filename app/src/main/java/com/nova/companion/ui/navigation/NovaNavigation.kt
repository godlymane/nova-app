package com.nova.companion.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nova.companion.ui.MemoryDebugScreen
import com.nova.companion.ui.chat.ChatScreen
import com.nova.companion.ui.chat.ChatViewModel
import com.nova.companion.ui.onboarding.OnboardingScreen
import com.nova.companion.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Chat : Screen("chat")
    object Settings : Screen("settings")
    object MemoryDebug : Screen("memory_debug")
}

@Composable
fun NovaNavigation() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()
    val context = LocalContext.current

    val prefs = remember { context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE) }
    val onboardingComplete = remember { prefs.getBoolean("onboarding_complete", false) }
    val startDest = if (onboardingComplete) Screen.Chat.route else Screen.Onboarding.route

    NavHost(
        navController = navController,
        startDestination = startDest
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onFinished = {
                    prefs.edit().putBoolean("onboarding_complete", true).apply()
                    navController.navigate(Screen.Chat.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Chat.route) {
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMemoryDebug = {
                    navController.navigate(Screen.MemoryDebug.route)
                }
            )
        }

        composable(Screen.MemoryDebug.route) {
            MemoryDebugScreen(onBack = { navController.popBackStack() })
        }
    }
}
