package com.pox.colab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pox.colab.theme.ColabTheme
import com.pox.colab.ui.home.HomeScreen
import com.pox.colab.ui.navigation.Screen
import com.pox.colab.ui.settings.SettingsScreen
import com.pox.colab.ui.vmdetails.VMDetailsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ColabApp

        setContent {
            val isDynamic = app.preferences.isDynamicColorEnabled()
            val isDark = app.preferences.isDarkMode()

            ColabTheme(darkTheme = isDark, dynamicColor = isDynamic) {
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

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
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
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
