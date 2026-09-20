package com.meetdheeran.prism.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meetdheeran.prism.ai.Attachment

object Routes {
    const val HOME = "home"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
    const val KEYS = "keys"
    const val PERMISSIONS = "permissions"
    const val TILES = "tiles"
    const val ONBOARDING = "onboarding"
}

/** What an incoming Intent asked the home screen to do. Consumed once. */
data class LaunchRequest(
    val conversationId: Long? = null,
    val prompt: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val openAssistant: Boolean = false,
    val stamp: Long = 0L,
)

@Composable
fun PrismNav(startRoute: String, launch: LaunchRequest?, onLaunchConsumed: () -> Unit) {
    val nav = rememberNavController()
    // A deep link (island long-press, share sheet, writing tools, assistant tile) always lands on the chat.
    LaunchedEffect(launch?.stamp) {
        if (launch != null && nav.currentDestination?.route != Routes.HOME && nav.currentDestination?.route != Routes.ONBOARDING) {
            nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = false }; launchSingleTop = true }
        }
    }
    NavHost(nav, startDestination = startRoute) {
        composable(Routes.HOME) { HomeScreen(nav, launch, onLaunchConsumed) }
        composable(Routes.MEMORY) { MemoryScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.KEYS) { KeysScreen(nav) }
        composable(Routes.PERMISSIONS) { PermissionsScreen(nav) }
        composable(Routes.TILES) { TilesEditorScreen(nav) }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(nav) {
                nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
    }
}

fun NavController.up() { if (!popBackStack()) navigate(Routes.HOME) }
