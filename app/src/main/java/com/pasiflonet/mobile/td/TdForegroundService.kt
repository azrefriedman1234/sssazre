package com.pasiflonet.mobile.td

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build

class TdForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "pasiflonet_td"
        private const val CHANNEL_NAME = "Pasiflonet Live Updates"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, TdForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val title = "Pasiflonet פעיל"
        val text = "ממשיך לקבל הודעות בלייב (TDLib)"

        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .build()
        }
    }
}
