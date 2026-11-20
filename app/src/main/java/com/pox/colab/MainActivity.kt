package com.pox.colab

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pox.colab.theme.ColabTheme
import com.pox.colab.ui.component.expressive.ExpressiveFloatingNavBar
import com.pox.colab.ui.component.expressive.FloatingNavDestination
import com.pox.colab.ui.home.HomeScreen
import com.pox.colab.ui.navigation.Screen
import com.pox.colab.ui.settings.SettingsScreen
import com.pox.colab.ui.vmdetails.VMDetailsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ColabApp

        val initialDark = app.preferences.isDarkMode()
        val initialBackground = if (initialDark) {
            if (app.preferences.isPureBlack()) AndroidColor.BLACK else 0xFF0E1014.toInt()
        } else {
            0xFFFFF8F2.toInt()
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
        window.setBackgroundDrawable(ColorDrawable(initialBackground))

        setContent {
            val isDynamic by app.preferences.isDynamicColorFlow.collectAsStateWithLifecycle()
            val isDark by app.preferences.isDarkModeFlow.collectAsStateWithLifecycle()
            val isPureBlack by app.preferences.isPureBlackFlow.collectAsStateWithLifecycle()
            val palette by app.preferences.selectedPaletteFlow.collectAsStateWithLifecycle()

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

    val destinations = remember {
        listOf(
            FloatingNavDestination(
                route = Screen.Home.route,
                label = "Instances",
                selectedIcon = Icons.Filled.Home,
                unselectedIcon = Icons.Outlined.Home,
                testTag = "nav_home"
            ),
            FloatingNavDestination(
                route = Screen.Settings.route,
                label = "Settings",
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                testTag = "nav_settings"
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            enterTransition = { fadeIn(animationSpec = tween(220)) },
            exitTransition = { fadeOut(animationSpec = tween(160)) },
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    repository = repository,
                    onNavigateToVM = { vmId, tabIndex ->
                        navController.navigate(Screen.VMDetails.createRoute(vmId, tabIndex))
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    repository = repository,
                    preferences = preferences,
                    onNavigateBack = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
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

        AnimatedVisibility(
            visible = isTopLevelDestination,
            enter = fadeIn(tween(180)) + slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                initialOffsetY = { it / 2 }
            ),
            exit = fadeOut(tween(140)) + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ExpressiveFloatingNavBar(
                destinations = destinations,
                currentRoute = currentRoute,
                onSelect = { dest ->
                    if (currentRoute != dest.route) {
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}
