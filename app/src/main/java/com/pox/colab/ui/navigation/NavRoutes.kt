package com.pox.colab.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object VMDetails : Screen("vm_details/{vmId}?tab={tab}") {
        fun createRoute(vmId: String, tabIndex: Int = 0): String {
            return "vm_details/$vmId?tab=$tabIndex"
        }
    }
}
