package info.meuse24.smsforwarderneo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsForegroundService : Service() {
    private var isServiceRunning = false
    private var currentNotificationText = "App läuft im Hintergrund."

    companion object {
        private const val CHANNEL_ID = "SmsForwarderChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SmsForegroundService"

        fun startService(context: Context) {
            val intent = Intent(context, SmsForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SmsForegroundService::class.java)
            context.stopService(intent)
        }

        fun updateNotification(context: Context, contentText: String) {
            val intent = Intent(context, SmsForegroundService::class.java).apply {
                action = "UPDATE_NOTIFICATION"
                putExtra("contentText", contentText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_NOTIFICATION" -> {
                val contentText = intent.getStringExtra("contentText") ?: return START_NOT_STICKY
                updateNotification(contentText)
            }
            else -> {
                if (!isServiceRunning) {
                    isServiceRunning = true
                    startForeground(NOTIFICATION_ID, createNotification(currentNotificationText))
                    Log.d(TAG, "Service started with notification: $currentNotificationText")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS Forwarder Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "App läuft im Hintergrund."
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TEL/SMS Forwarder")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun updateNotification(contentText: String) {
        if (isServiceRunning) {
            currentNotificationText = contentText
            val notification = createNotification(contentText)
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Notification updated: $contentText")
        } else {
            Log.w(TAG, "Attempted to update notification, but service is not running")
        }
    }
}