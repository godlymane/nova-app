package com.nova.companion.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nova.companion.ui.MemoryDebugScreen
import com.nova.companion.ui.chat.ChatScreen
import com.nova.companion.ui.chat.ChatViewModel
import com.nova.companion.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Chat : Screen("chat")
    object Settings : Screen("settings")
    object MemoryDebug : Screen("memory_debug")
}

@Composable
fun NovaNavigation() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Screen.Chat.route
    ) {
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
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToMemoryDebug = {
                    navController.navigate(Screen.MemoryDebug.route)
                }
            )
        }

        composable(Screen.MemoryDebug.route) {
            MemoryDebugScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
