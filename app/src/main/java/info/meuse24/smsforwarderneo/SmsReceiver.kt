package info.meuse24.smsforwarderneo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * SmsReceiver ist ein BroadcastReceiver, der eingehende SMS-Nachrichten empfängt und verarbeitet.
 * Er kümmert sich um die Weiterleitung von SMS, wenn diese Funktion aktiviert ist.
 */

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }
    // SharedPreferencesManager wird für den Zugriff auf gespeicherte Einstellungen verwendet
    private lateinit var prefsManager: SharedPreferencesManager


    /**
     * Diese Methode wird aufgerufen, wenn eine Broadcast-Nachricht empfangen wird.
     * Sie verarbeitet eingehende SMS und gesendete SMS-Bestätigungen.
     */
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")
        prefsManager = SharedPreferencesManager(context)

        // Überprüfen Sie die Berechtigung des Senders
//        if (context.checkCallingOrSelfPermission(android.Manifest.permission.BROADCAST_SMS) != PackageManager.PERMISSION_GRANTED) {
//            Log.w(TAG, "Received intent from sender without BROADCAST_SMS permission")
//            return
//        }

        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                if (isSmsIntentValid(intent)) {
                    handleSmsReceived(context, intent)
                } else {
                    Log.w(TAG, "Received invalid SMS intent")
                }
            }
            "SMS_SENT" -> handleSmsSent(context, intent)
            else -> Log.d(TAG, "Unbekannte Aktion empfangen: ${intent.action}")
        }
    }

    private fun isSmsIntentValid(intent: Intent): Boolean {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Received SMS intent with no messages")
            return false
        }

        for (smsMessage in messages) {
            val sender = smsMessage.originatingAddress
            val messageBody = smsMessage.messageBody

            if (sender.isNullOrEmpty() || messageBody.isNullOrEmpty()) {
                Log.w(TAG, "Received SMS with empty sender or body")
                return false
            }
        }
        return true
    }

    /**
     * Verarbeitet eingehende SMS-Nachrichten.
     * Wenn die Weiterleitung aktiviert ist, werden die Nachrichten zusammengeführt und weitergeleitet.
     */
    private fun handleSmsReceived(context: Context, intent: Intent) {
        // Startet den Vordergrund-Service für zuverlässige Verarbeitung
        startForegroundService(context)

        // Prüft, ob die SMS-Weiterleitung aktiviert ist
        if (prefsManager.isForwardingActive()) {
            // Extrahiert alle SMS-Nachrichten aus dem Intent
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            // Map zur Zusammenführung von SMS-Teilen pro Absender
            val smsMap = mutableMapOf<String, StringBuilder>()

            // Iteriert über alle empfangenen Nachrichten
            messages.forEach { smsMessage ->
                val sender = smsMessage.originatingAddress ?: return@forEach
                val messageBody = smsMessage.messageBody

                // Fügt den Nachrichtentext zum entsprechenden Absender hinzu oder erstellt einen neuen Eintrag
                smsMap.getOrPut(sender) { StringBuilder() }.append(messageBody)
            }

            // Verarbeitet jede zusammengeführte Nachricht
            smsMap.forEach { (sender, messageBody) ->
                // Holt die Weiterleitungsnummer aus den Einstellungen
                prefsManager.getSelectedPhoneNumber()?.let { forwardToNumber ->
                    // Erstellt die vollständige Nachricht mit Absenderinformation
                    val fullMessage = "Von: $sender\n$messageBody"
                    Log.d(TAG, "SMS Weiterleitung: $fullMessage")
                    // Leitet die Nachricht über den SmsWorker weiter
                    forwardSmsWithSmsWorker(context, forwardToNumber, fullMessage)
                }
            }
        }
    }

    /**
     * Verarbeitet Bestätigungen für gesendete SMS.
     */
    private fun handleSmsSent(context: Context, intent: Intent) {
        val resultMessage = when (resultCode) {
            Activity.RESULT_OK -> "SMS erfolgreich gesendet"
            else -> "SMS senden fehlgeschlagen"
        }

        SnackbarManager.showInfo("SMSReceiver: " + resultMessage)
    }

    /**
     * Startet den Vordergrund-Service für die zuverlässige Verarbeitung von SMS.
     * Berücksichtigt verschiedene Android-Versionen.
     */
    private fun startForegroundService(context: Context) {
        val serviceIntent = Intent(context, SmsForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Für Android 8.0 (API 26) und höher
            context.startForegroundService(serviceIntent)
        } else {
            // Für ältere Android-Versionen
            context.startService(serviceIntent)
        }
    }

    /**
     * Erstellt und plant einen SmsWorker zur asynchronen Weiterleitung der SMS.
     * @param context Der Anwendungskontext
     * @param phoneNumber Die Zieltelefonnummer für die Weiterleitung
     * @param message Der Nachrichtentext, der weitergeleitet werden soll
     */
    private fun forwardSmsWithSmsWorker(context: Context, phoneNumber: String, message: String) {
        // Erstellt die Eingabedaten für den Worker
        val data = Data.Builder()
            .putString("phoneNumber", phoneNumber)
            .putString("message", message)
            .build()

        // Konfiguriert den OneTimeWorkRequest für den SmsWorker
        val smsWorkRequest = OneTimeWorkRequest.Builder(SmsWorker::class.java)
            .setInputData(data)
            .build()

        // Plant die Ausführung des Workers
        WorkManager.getInstance(context).enqueue(smsWorkRequest)
    }
}



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



/**
 * SmsWorker-Klasse für das Versenden von SMS-Nachrichten.
 * Diese Klasse erbt von Worker und wird verwendet, um SMS-Nachrichten im Hintergrund zu versenden.
 *
 * @param context Der Kontext der Anwendung
 * @param params Die Parameter für den Worker
 */
class SmsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    /**
     * Führt die Hauptaufgabe des Workers aus.
     * @return Result.success() bei erfolgreichem Versand, Result.failure() bei Fehlern
     */
    override fun doWork(): Result {
        // Extrahiere Telefonnummer und Nachricht aus den Eingabedaten
        val phoneNumber = inputData.getString(PHONE_NUMBER_KEY) ?: return Result.failure()
        val message = inputData.getString(MESSAGE_KEY) ?: return Result.failure()

        return try {
            // Verwende PhoneSmsUtils.sendSms zum Versenden der SMS
            PhoneSmsUtils.sendSms(applicationContext, phoneNumber, message)
            logSuccess(message, phoneNumber)
            Result.success()
        } catch (e: Exception) {
            logError(e, message, phoneNumber)
            Result.failure()
        }
    }

    /**
     * Protokolliert eine erfolgreiche SMS-Übertragung.
     * @param message Die gesendete Nachricht
     * @param phoneNumber Die Zieltelefonnummer
     */
    private fun logSuccess(message: String, phoneNumber: String) {
        LoggingManager.logInfo(
            component = "SMSForwarder",
            action = "FORWARD_SMS",
            message = "SMS erfolgreich weitergeleitet",
            details = mapOf(
                "message" to message,
                "target_number" to phoneNumber
            )
        )
    }

    /**
     * Protokolliert einen Fehler bei der SMS-Übertragung.
     * @param e Die aufgetretene Exception
     * @param message Die zu sendende Nachricht
     * @param phoneNumber Die Zieltelefonnummer
     */
    private fun logError(e: Exception, message: String, phoneNumber: String) {
        LoggingManager.logError(
            component = "SMSForwarder",
            action = "FORWARD_SMS_ERROR",
            message = "Fehler bei der SMS-Weiterleitung",
            error = e,
            details = mapOf(
                "message" to message,
                "target_number" to phoneNumber
            )
        )
    }
    companion object {
        const val PHONE_NUMBER_KEY = "phoneNumber"
        const val MESSAGE_KEY = "message"
    }
}