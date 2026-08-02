package com.meshtalk.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.meshtalk.app.MeshTalkApp

/**
 * Keeps Bluetooth/WiFi advertising + discovery (and the internet socket) alive while
 * the app is backgrounded, so this device keeps working as a relay hop for other
 * people's messages even if the user isn't actively chatting.
 *
 * The notification is intentionally plain and honest about what the app is doing —
 * Android requires a visible foreground-service notification, and it should not be
 * disguised as something else (see design notes on why icon/identity masking is out
 * of scope for this project).
 */
class MeshRelayService : Service() {

    companion object {
        private const val CHANNEL_ID = "meshtalk_relay"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())
        (application as MeshTalkApp).meshRouter.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        (application as MeshTalkApp).meshRouter.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshTalk is active")
            .setContentText("Relaying messages over Bluetooth/WiFi/Internet")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun createChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mesh relay", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
