package com.pox.colab

import android.app.Application
import com.pox.colab.data.AppPreferences
import com.pox.colab.data.VMRepository

class ColabApp : Application() {
    lateinit var preferences: AppPreferences
        private set
    lateinit var repository: VMRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = AppPreferences(this)
        repository = VMRepository(preferences, this)
    }

    companion object {
        lateinit var instance: ColabApp
            private set
    }
}
