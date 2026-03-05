package com.nova.companion.ui.navigation

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nova.companion.biohack.GodModeScreen
import com.nova.companion.biohack.hypnosis.HypnosisScreen
import com.nova.companion.ui.MemoryDebugScreen
import com.nova.companion.ui.aura.NovaAuraEffect
import com.nova.companion.ui.chat.ChatScreen
import com.nova.companion.ui.chat.ChatViewModel
import com.nova.companion.ui.onboarding.OnboardingScreen
import com.nova.companion.ui.settings.SettingsScreen

// ─────────────────────────────────────────────────────────────
//  Screen routes
// ─────────────────────────────────────────────────────────────

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Chat       : Screen("chat")
    object Settings   : Screen("settings")
    object MemoryDebug : Screen("memory_debug")
    object GodMode    : Screen("god_mode")
    object Hypnosis   : Screen("hypnosis")
}

// ─────────────────────────────────────────────────────────────
//  Transition specs
// ─────────────────────────────────────────────────────────────

private const val NAV_ANIM_DURATION = 350
private val navEasing = FastOutSlowInEasing

// Forward navigation: slide in from right + fade in
private val forwardEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(
        initialOffsetX = { it / 4 },
        animationSpec  = tween(NAV_ANIM_DURATION, easing = navEasing)
    ) + fadeIn(tween(NAV_ANIM_DURATION))
}

// Forward navigation: current screen fades out + slight slide left
private val forwardExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(
        targetOffsetX = { -it / 6 },
        animationSpec = tween(NAV_ANIM_DURATION, easing = navEasing)
    ) + fadeOut(tween(NAV_ANIM_DURATION / 2))
}

// Pop (back): slide in from left + fade in
private val popEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(
        initialOffsetX = { -it / 4 },
        animationSpec  = tween(NAV_ANIM_DURATION, easing = navEasing)
    ) + fadeIn(tween(NAV_ANIM_DURATION))
}

// Pop (back): slide out to right + fade out
private val popExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(
        targetOffsetX = { it / 4 },
        animationSpec = tween(NAV_ANIM_DURATION, easing = navEasing)
    ) + fadeOut(tween(NAV_ANIM_DURATION / 2))
}

// Special: crossfade only (for onboarding → chat)
private val crossfadeEnter: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    fadeIn(tween(500))
}
private val crossfadeExit: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    fadeOut(tween(400))
}

// ─────────────────────────────────────────────────────────────
//  Main navigation host
// ─────────────────────────────────────────────────────────────

@Composable
fun NovaNavigation() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()
    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)
    }
    val onboardingComplete = remember {
        prefs.getBoolean("onboarding_complete", false)
    }
    val startDest = if (onboardingComplete) Screen.Chat.route else Screen.Onboarding.route

    // Aura state persists across screens
    val auraState by chatViewModel.auraState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController    = navController,
            startDestination = startDest,
            enterTransition  = forwardEnter,
            exitTransition   = forwardExit,
            popEnterTransition = popEnter,
            popExitTransition  = popExit
        ) {
            // ── Onboarding ───────────────────────────────
            composable(
                route              = Screen.Onboarding.route,
                enterTransition    = crossfadeEnter,
                exitTransition     = crossfadeExit,
                popEnterTransition = crossfadeEnter,
                popExitTransition  = crossfadeExit
            ) {
                OnboardingScreen(
                    onFinished = {
                        prefs.edit().putBoolean("onboarding_complete", true).apply()
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }

            // ── Chat (main) ─────────────────────────────
            composable(Screen.Chat.route) {
                ChatScreen(
                    viewModel = chatViewModel,
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            // ── Settings ─────────────────────────────────
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = chatViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToMemoryDebug = {
                        navController.navigate(Screen.MemoryDebug.route)
                    },
                    onNavigateToGodMode = {
                        navController.navigate(Screen.GodMode.route)
                    }
                )
            }

            // ── Memory Debug ─────────────────────────────
            composable(Screen.MemoryDebug.route) {
                MemoryDebugScreen(onBack = { navController.popBackStack() })
            }

            // ── God Mode ─────────────────────────────────
            composable(Screen.GodMode.route) {
                GodModeScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToHypnosis = {
                        navController.navigate(Screen.Hypnosis.route)
                    }
                )
            }

            // ── Hypnosis ─────────────────────────────────
            composable(Screen.Hypnosis.route) {
                HypnosisScreen(onBack = { navController.popBackStack() })
            }
        }

        // Persistent aura overlay — always visible on top
        NovaAuraEffect(
            auraState = auraState,
            modifier  = Modifier.fillMaxSize()
        )
    }
}
