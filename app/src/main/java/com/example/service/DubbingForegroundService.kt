package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class DubbingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "balaspeak_turbo_dubbing_channel"
        const val NOTIFICATION_ID = 9901

        const val ACTION_START = "com.example.action.START_DUBBING"
        const val ACTION_STOP = "com.example.action.STOP_DUBBING"
        const val ACTION_UPDATE = "com.example.action.UPDATE_PROGRESS"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_MAX = "extra_max"
        const val EXTRA_STATUS = "extra_status"

        fun startService(context: Context, title: String = "BalaSpeak Background Engine", status: String = "Turbo processing in background...") {
            val intent = Intent(context, DubbingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STATUS, status)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun updateProgress(context: Context, status: String, progress: Int, max: Int) {
            val intent = Intent(context, DubbingForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_MAX, max)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore if service not running
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DubbingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                sendBroadcast(Intent(StopProcessingReceiver.ACTION_STOP_PROCESSING))
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Processing video & audio..."
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val max = intent.getIntExtra(EXTRA_MAX, 100)
                val notification = buildNotification("BalaSpeak Studio", status, progress, max)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "BalaSpeak Studio"
                val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Background Turbo Processing Active"
                val notification = buildNotification(title, status, 0, 0)
                try {
                    if (Build.VERSION.SDK_INT >= 34) { // Android 14+
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BalaSpeak:TurboDubbingLock")
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour max
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Dubbing & Translation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CPU and audio/video processing active when app is minimized"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, status: String, progress: Int, max: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, DubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (max > 0) "$status (${(progress * 100f / max).toInt()}%)" else status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
