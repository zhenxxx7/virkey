package com.virkey.app.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.virkey.app.MainActivity
import com.virkey.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the HID registration alive while the user pairs through Android system dialogs. */
class BluetoothInputService : Service() {
    lateinit var controller: BluetoothController
        private set
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: BluetoothInputService get() = this@BluetoothInputService
    }

    override fun onCreate() {
        super.onCreate()
        controller = BluetoothController(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Bluetooth keyboard session", NotificationManager.IMPORTANCE_LOW)
        )
        serviceScope.launch {
            controller.state.collect {
                if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endSession()
            return START_NOT_STICKY
        }
        controller.refreshEnvironment()
        if (!controller.state.value.permissionsReady || !controller.state.value.bluetoothEnabled) {
            controller.setMessage("Enable Bluetooth and allow Nearby devices before starting a session.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(NOTIFICATION_ID, notification())
            foreground = true
            controller.start()
        } catch (_: SecurityException) {
            controller.setMessage("Nearby devices permission is required to keep the keyboard connected.")
            endSession()
        } catch (_: IllegalStateException) {
            controller.setMessage("Open Virkey and tap Connect to start the keyboard session.")
            endSession()
        }
        return START_NOT_STICKY
    }

    fun endSession() {
        controller.stop()
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        endSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        foreground = false
        serviceScope.cancel()
        controller.close()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BluetoothInputService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val state = controller.state.value
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle(if (state.isConnected) "Virkey · ${state.connectedName}" else "Virkey keyboard is available")
            .setContentText(if (state.isConnected) "Tap to return to your keyboard and trackpad" else "Pair from Windows or select a paired PC in Virkey")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.virkey.app.START_SESSION"
        const val ACTION_STOP = "com.virkey.app.STOP_SESSION"
        private const val CHANNEL = "virkey_bluetooth"
        private const val NOTIFICATION_ID = 1
    }
}
