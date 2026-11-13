package com.pox.colab.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pox.colab.MainActivity
import com.pox.colab.R

class SSHBackgroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Colab:SSHResilientWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 1 hour timeout
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vmName = intent?.getStringExtra(EXTRA_VM_NAME) ?: "VM"
        val notification = buildNotification(vmName)
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Colab Resilient SSH Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps persistent SSH & tmux connections active in background"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(vmName: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Active Session: $vmName")
            .setContentText("Resilient SSH tmux connection active in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "colab_ssh_service"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_VM_NAME = "extra_vm_name"

        fun start(context: Context, vmName: String) {
            try {
                val intent = Intent(context, SSHBackgroundService::class.java).apply {
                    putExtra(EXTRA_VM_NAME, vmName)
                }
                context.startForegroundService(intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, SSHBackgroundService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }
}
