package com.pox.colab

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pox.colab.theme.ColabTheme
import com.pox.colab.ui.home.HomeScreen
import com.pox.colab.ui.navigation.Screen
import com.pox.colab.ui.settings.SettingsScreen
import com.pox.colab.ui.vmdetails.VMDetailsScreen
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ColabApp

        // System bar icon appearance must follow the *app* theme preference, not the system
        // night mode, otherwise forced light/dark themes render invisible status bar icons.
        val initialDark = app.preferences.isDarkMode()
        val initialBackground = if (initialDark) {
            if (app.preferences.isPureBlack()) AndroidColor.BLACK else 0xFF0F0F0F.toInt()
        } else {
            AndroidColor.WHITE
        }
        enableEdgeToEdge(
            statusBarStyle = if (initialDark) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            },
            navigationBarStyle = if (initialDark) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            }
        )
        // No first-frame flash in the opposite theme while Compose composes.
        window.setBackgroundDrawable(ColorDrawable(initialBackground))

        setContent {
            val isDynamic by app.preferences.isDynamicColorFlow.collectAsState()
            val isDark by app.preferences.isDarkModeFlow.collectAsState()
            val isPureBlack by app.preferences.isPureBlackFlow.collectAsState()
            val palette by app.preferences.selectedPaletteFlow.collectAsState()

            ColabTheme(
                darkTheme = isDark,
                dynamicColor = isDynamic,
                selectedPalette = palette,
                pureBlack = isPureBlack
            ) {
                val view = LocalView.current
                val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    // Keep the window (behind Compose) in sync with the active theme so
                    // activity transitions never flash the opposite background.
                    window.setBackgroundDrawable(ColorDrawable(backgroundArgb))
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ColabAppNavHost(app)
                }
            }
        }
    }
}

@Composable
fun ColabAppNavHost(app: ColabApp) {
    val navController = rememberNavController()
    val repository = app.repository
    val preferences = app.preferences

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isTopLevelDestination = currentRoute == Screen.Home.route || currentRoute == Screen.Settings.route

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isTopLevelDestination) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    NavigationBarItem(
                        selected = currentRoute == Screen.Home.route,
                        onClick = {
                            if (currentRoute != Screen.Home.route) {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )

                    NavigationBarItem(
                        selected = currentRoute == Screen.Settings.route,
                        onClick = {
                            if (currentRoute != Screen.Settings.route) {
                                navController.navigate(Screen.Settings.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    repository = repository,
                    onNavigateToVM = { vmId, tabIndex ->
                        navController.navigate(Screen.VMDetails.createRoute(vmId, tabIndex))
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    repository = repository,
                    preferences = preferences,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Screen.VMDetails.route,
                arguments = listOf(
                    navArgument("vmId") { type = NavType.StringType },
                    navArgument("tab") {
                        type = NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { backStackEntry ->
                val vmId = backStackEntry.arguments?.getString("vmId") ?: ""
                val tab = backStackEntry.arguments?.getInt("tab") ?: 0

                VMDetailsScreen(
                    vmId = vmId,
                    initialTab = tab,
                    repository = repository,
                    preferences = preferences,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
