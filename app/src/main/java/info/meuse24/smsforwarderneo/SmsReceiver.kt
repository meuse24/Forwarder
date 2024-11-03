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
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AtomicReference
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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


        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                if (isSmsIntentValid(intent)) {
                    handleSmsReceived(context, intent)
                } else {
                    LoggingManager.logWarning(
                        component = "SmsReceiver",
                        action = "INVALID_SMS",
                        message = "Ungültige SMS empfangen"
                    )
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
        //startForegroundService(context)

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
            // SMS-zu-SMS Weiterleitung (unabhängig)
            if (prefsManager.isForwardingActive()) {
                prefsManager.getSelectedPhoneNumber()?.let { forwardToNumber ->
                    val fullMessage = "Von: $sender\n$messageBody"
                    forwardSmsWithSmsWorker(context, forwardToNumber, fullMessage)
                }
            }

            // SMS-zu-Email Weiterleitung (unabhängig)
            if (prefsManager.isForwardSmsToEmail()) {
                handleEmailForwarding(context, sender, messageBody.toString())
            }
        }
    }

    private fun handleEmailForwarding(context: Context, sender: String, messageBody: String) {
        val prefsManager = SharedPreferencesManager(context)
        val emailAddresses = prefsManager.getEmailAddresses()

        if (emailAddresses.isEmpty()) {
            LoggingManager.logWarning(
                component = "SmsReceiver",
                action = "EMAIL_FORWARD",
                message = "Keine Email-Adressen konfiguriert"
            )
            return
        }

        // Hole SMTP-Einstellungen konsistent aus SharedPreferences
        val host = prefsManager.getSmtpHost()
        val port = prefsManager.getSmtpPort()
        val username = prefsManager.getSmtpUsername()
        val password = prefsManager.getSmtpPassword()

        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
            LoggingManager.logWarning(
                component = "SmsReceiver",
                action = "EMAIL_FORWARD",
                message = "Unvollständige SMTP-Konfiguration",
                details = mapOf(
                    "has_host" to host.isNotEmpty(),
                    "has_username" to username.isNotEmpty(),
                    "has_credentials" to password.isNotEmpty()
                )
            )
            return
        }

        val emailSender = EmailSender(host, port, username, password)
        val subject = "Neue SMS von $sender"
        val body = buildString {
            append("SMS Weiterleitung\n\n")
            append("Absender: $sender\n")
            append("Zeitpunkt: ${getCurrentTimestamp()}\n\n")
            append("Nachricht:\n")
            append(messageBody)
            append("\n\nDiese E-Mail wurde automatisch durch den SMS Forwarder generiert.")
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (val result = emailSender.sendEmail(emailAddresses, subject, body)) {
                    is EmailResult.Success -> {
                        LoggingManager.logInfo(
                            component = "SmsReceiver",
                            action = "EMAIL_FORWARD",
                            message = "SMS erfolgreich per Email weitergeleitet",
                            details = mapOf(
                                "sender" to sender,
                                "recipients" to emailAddresses.size,
                                "smtp_host" to host,
                                "message_length" to messageBody.length
                            )
                        )
                    }
                    is EmailResult.Error -> {
                        LoggingManager.logError(
                            component = "SmsReceiver",
                            action = "EMAIL_FORWARD",
                            message = "Email-Weiterleitung fehlgeschlagen",
                            details = mapOf(
                                "error" to result.message,
                                "smtp_host" to host,
                                "has_credentials" to (username.isNotEmpty() && password.isNotEmpty())
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "SmsReceiver",
                    action = "EMAIL_FORWARD",
                    message = "Unerwarteter Fehler bei Email-Weiterleitung",
                    error = e,
                    details = mapOf(
                        "smtp_host" to host,
                        "error_type" to e.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val restartHandler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { restartService() }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val TAG = "SmsForegroundService"
        private const val CHANNEL_ID = "SmsForwarderChannel"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_DELAY = 1000L // 1 Sekunde
        private var isRunning = false

        // Atomic-Referenz für Prozess-übergreifende Konsistenz
        private val serviceInstance = AtomicReference<SmsForegroundService>()

        fun isServiceActive() = isRunning

        fun startService(context: Context) {
            if (!isRunning) {
                val intent = Intent(context, SmsForegroundService::class.java).apply {
                    action = "START_SERVICE"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stopService(context: Context) {
            if (isRunning) {
                val intent = Intent(context, SmsForegroundService::class.java)
                context.stopService(intent)
            }
        }

        fun updateNotification(context: Context, contentText: String) {
            val intent = Intent(context, SmsForegroundService::class.java).apply {
                action = "UPDATE_NOTIFICATION"
                putExtra("contentText", contentText)
            }
            if (isRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            serviceInstance.set(this)
            createNotificationChannel()
            acquireWakeLock()
            isRunning = true
            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "CREATE",
                message = "Foreground Service wurde erstellt"
            )
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "CREATE_ERROR",
                message = "Fehler beim Erstellen des Services",
                error = e
            )
            stopSelf()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsForwarder:ForegroundService"
            ).apply {
                setReferenceCounted(false)
                acquire(10*60*1000L /*10 minutes*/)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                "START_SERVICE" -> {
                    if (!isServiceRunning) {
                        startForegroundService()
                    }
                }
                "UPDATE_NOTIFICATION" -> {
                    val newText = intent.getStringExtra("contentText")
                    if (newText != null && newText != currentNotificationText) {
                        currentNotificationText = newText
                        updateNotification(newText)
                    }
                }
                else -> {
                    if (!isServiceRunning) {
                        startForegroundService()
                    }
                }
            }

            // Starte Heartbeat-Monitoring
            scheduleHeartbeat()

            return START_STICKY
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "START_COMMAND_ERROR",
                message = "Fehler im onStartCommand",
                error = e
            )
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keine Neuerstellung der Notification notwendig
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Prüfe ob der Channel bereits existiert
                val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
                if (existingChannel != null) {
                    LoggingManager.logInfo(
                        component = "SmsForegroundService",
                        action = "NOTIFICATION_CHANNEL",
                        message = "Notification Channel existiert bereits",
                        details = mapOf(
                            "channel_id" to CHANNEL_ID,
                            "importance" to existingChannel.importance
                        )
                    )
                    return
                }

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "SMS Forwarder Service",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Zeigt den Status der SMS/Anruf-Weiterleitung an"
                    setShowBadge(true)
                    enableLights(true)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }

                notificationManager.createNotificationChannel(channel)

                LoggingManager.logInfo(
                    component = "SmsForegroundService",
                    action = "NOTIFICATION_CHANNEL",
                    message = "Neuer Notification Channel erstellt",
                    details = mapOf(
                        "channel_id" to CHANNEL_ID,
                        "importance" to channel.importance
                    )
                )
            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "NOTIFICATION_CHANNEL_ERROR",
                    message = "Fehler beim Erstellen des Notification Channels",
                    error = e
                )
            }
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TEL/SMS Forwarder")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Geändert von HIGH zu DEFAULT
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true) // Verhindert wiederholte Benachrichtigungen
            .build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    fun updateNotification(contentText: String) {
        try {
            if (isServiceRunning) {
                currentNotificationText = contentText
                val notification = createNotification(contentText)
                notificationManager.notify(NOTIFICATION_ID, notification)
                LoggingManager.logInfo(
                    component = "SmsForegroundService",
                    action = "UPDATE_NOTIFICATION",
                    message = "Notification aktualisiert: $contentText"
                )
            }
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "UPDATE_ERROR",
                message = "Fehler beim Aktualisieren der Notification",
                error = e
            )
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "Notification permission: ${
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            }")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        isServiceRunning = true
        val prefs = SharedPreferencesManager(this)
        val initialStatus = buildServiceStatus(prefs)
        currentNotificationText = initialStatus
        val notification = createNotification(initialStatus)
        startForeground(NOTIFICATION_ID, notification)

        // Starte Monitoring im ServiceScope
        serviceScope.launch {
            monitorService()
        }
    }

    private suspend fun monitorService() {
        while (isServiceRunning) {
            try {
                // Prüfe Service-Status
                ensureServiceRunning()
                delay(30000) // Alle 30 Sekunden
            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "MONITOR_ERROR",
                    message = "Fehler beim Service-Monitoring",
                    error = e
                )
            }
        }
    }

    private fun ensureServiceRunning() {
        if (!isServiceRunning || !isRunning) {
            restartService()
        }
    }

    private fun scheduleHeartbeat() {
        restartHandler.removeCallbacks(restartRunnable)
        restartHandler.postDelayed(restartRunnable, RESTART_DELAY)
    }

    private fun restartService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            startForegroundService()
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "RESTART_ERROR",
                message = "Fehler beim Neustart des Services",
                error = e
            )
        }
    }























    private fun buildServiceStatus(prefs: SharedPreferencesManager): String {
        val smsForwardingActive = prefs.isForwardingActive()
        val emailForwardingActive = prefs.isForwardSmsToEmail()
        return buildString {
            if (!smsForwardingActive && !emailForwardingActive) {
                append("Keine Weiterleitung aktiv")
            } else {
                if (smsForwardingActive) {
                    append("SMS-Weiterleitung aktiv")
                    prefs.getSelectedPhoneNumber()?.let { number ->
                        append(" zu $number")
                    }
                }
                if (emailForwardingActive) {
                    if (smsForwardingActive) append("\n")
                    append("Email-Weiterleitung aktiv")
                    val emailCount = prefs.getEmailAddresses().size
                    append(" an $emailCount Email(s)")
                }
            }
        }
    }


    override fun onDestroy() {
        try {
            serviceScope.cancel()
            restartHandler.removeCallbacks(restartRunnable)
            releaseWakeLock()
            isServiceRunning = false
            isRunning = false
            serviceInstance.set(null)

            // Versuche Neustart wenn nicht explizit beendet
            if (SharedPreferencesManager(this).getKeepForwardingOnExit()) {
                startService(this)
            }

            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "DESTROY",
                message = "Foreground Service wurde beendet"
            )
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "DESTROY_ERROR",
                message = "Fehler beim Beenden des Services",
                error = e
            )
        } finally {
            super.onDestroy()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Prüfe ob Service weiterlaufen soll
        if (SharedPreferencesManager(this).getKeepForwardingOnExit()) {
            restartService()
        } else {
            stopSelf()
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