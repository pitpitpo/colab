package com.pox.colab.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pox.colab.model.ColabAccount
import com.pox.colab.model.ColabVM

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("colab_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveAccounts(accounts: List<ColabAccount>) {
        val json = gson.toJson(accounts)
        prefs.edit().putString(KEY_ACCOUNTS, json).apply()
    }

    fun loadAccounts(): List<ColabAccount> {
        val json = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<ColabAccount>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveVMs(vms: List<ColabVM>) {
        val json = gson.toJson(vms)
        prefs.edit().putString(KEY_VMS, json).apply()
    }

    fun loadVMs(): List<ColabVM> {
        val json = prefs.getString(KEY_VMS, null) ?: return emptyList()
        val type = object : TypeToken<List<ColabVM>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getActiveAccountId(): String? = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, null)

    fun setActiveAccountId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, id).apply()
    }

    fun isDynamicColorEnabled(): Boolean = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
    fun setDynamicColorEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()

    fun isDarkMode(): Boolean = prefs.getBoolean(KEY_DARK_MODE, true)
    fun setDarkMode(dark: Boolean) = prefs.edit().putBoolean(KEY_DARK_MODE, dark).apply()

    companion object {
        private const val KEY_ACCOUNTS = "key_accounts"
        private const val KEY_VMS = "key_vms"
        private const val KEY_ACTIVE_ACCOUNT_ID = "key_active_account_id"
        private const val KEY_DYNAMIC_COLOR = "key_dynamic_color"
        private const val KEY_DARK_MODE = "key_dark_mode"
    }
}
