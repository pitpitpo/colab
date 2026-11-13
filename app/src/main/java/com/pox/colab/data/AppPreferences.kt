package com.pox.colab.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("colab_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _isDarkModeFlow = MutableStateFlow(isDarkMode())
    val isDarkModeFlow: StateFlow<Boolean> = _isDarkModeFlow.asStateFlow()

    private val _isPureBlackFlow = MutableStateFlow(isPureBlack())
    val isPureBlackFlow: StateFlow<Boolean> = _isPureBlackFlow.asStateFlow()

    private val _isDynamicColorFlow = MutableStateFlow(isDynamicColorEnabled())
    val isDynamicColorFlow: StateFlow<Boolean> = _isDynamicColorFlow.asStateFlow()

    private val _selectedPaletteFlow = MutableStateFlow(getSelectedPalette())
    val selectedPaletteFlow: StateFlow<String> = _selectedPaletteFlow.asStateFlow()

    private val _consoleBackgroundFlow = MutableStateFlow(getConsoleBackground())
    val consoleBackgroundFlow: StateFlow<String> = _consoleBackgroundFlow.asStateFlow()

    fun loadAccounts(): List<ColabAccount> {
        val raw = prefs.getString("accounts_json", null) ?: return emptyList()
        val json = com.pox.colab.util.CryptoUtil.decrypt(raw)
        return try {
            val type = object : TypeToken<List<ColabAccount>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(accounts: List<ColabAccount>) {
        val json = gson.toJson(accounts)
        val encrypted = com.pox.colab.util.CryptoUtil.encrypt(json)
        prefs.edit().putString("accounts_json", encrypted).apply()
    }

    fun getActiveAccountId(): String? = prefs.getString("active_account_id", null)

    fun setActiveAccountId(id: String?) {
        prefs.edit().putString("active_account_id", id).apply()
    }

    fun loadVMs(): List<ColabVM> {
        val raw = prefs.getString("vms_json", null) ?: return emptyList()
        val json = com.pox.colab.util.CryptoUtil.decrypt(raw)
        return try {
            val type = object : TypeToken<List<ColabVM>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveVMs(vms: List<ColabVM>) {
        val json = gson.toJson(vms)
        val encrypted = com.pox.colab.util.CryptoUtil.encrypt(json)
        prefs.edit().putString("vms_json", encrypted).apply()
    }

    fun isDarkMode(): Boolean = prefs.getBoolean("dark_mode", true)
    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode", enabled).apply()
        _isDarkModeFlow.value = enabled
    }

    fun isPureBlack(): Boolean = prefs.getBoolean("pure_black", true)
    fun setPureBlack(enabled: Boolean) {
        prefs.edit().putBoolean("pure_black", enabled).apply()
        _isPureBlackFlow.value = enabled
    }

    fun isDynamicColorEnabled(): Boolean = prefs.getBoolean("dynamic_color", false)
    fun setDynamicColorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _isDynamicColorFlow.value = enabled
    }

    fun getSelectedPalette(): String = prefs.getString("selected_palette", "colab_amber") ?: "colab_amber"
    fun setSelectedPalette(palette: String) {
        prefs.edit().putString("selected_palette", palette).apply()
        _selectedPaletteFlow.value = palette
    }

    fun getConsoleBackground(): String = prefs.getString("console_bg_global", "amoled_black") ?: "amoled_black"
    fun setConsoleBackground(bgKey: String) {
        prefs.edit().putString("console_bg_global", bgKey).apply()
        _consoleBackgroundFlow.value = bgKey
    }

    fun getConsoleBgForVM(vmId: String): String =
        prefs.getString("console_bg_vm_$vmId", getConsoleBackground()) ?: getConsoleBackground()

    fun setConsoleBgForVM(vmId: String, bgKey: String) {
        prefs.edit().putString("console_bg_vm_$vmId", bgKey).apply()
    }

    fun getConsoleBgForTerminal(vmId: String, terminalId: String): String =
        prefs.getString("console_bg_term_${vmId}_$terminalId", getConsoleBgForVM(vmId)) ?: getConsoleBgForVM(vmId)

    fun setConsoleBgForTerminal(vmId: String, terminalId: String, bgKey: String) {
        prefs.edit().putString("console_bg_term_${vmId}_$terminalId", bgKey).apply()
    }
}
