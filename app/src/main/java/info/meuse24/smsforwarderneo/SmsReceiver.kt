package info.meuse24.smsforwarderneo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AtomicReference
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            "SMS_SENT" -> handleSmsSent()
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
        val serviceIntent = Intent(context, SmsForegroundService::class.java).apply {
            action = "PROCESS_SMS"
            // Kopiere alle SMS-relevanten Extras
            if (intent.extras != null) {
                putExtras(intent.extras!!)
            }
            // Füge die Original-Action hinzu
            putExtra("original_action", intent.action)
            flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
        }

        LoggingManager.logInfo(
            component = "SmsReceiver",
            action = "FORWARD_TO_SERVICE",
            message = "SMS-Daten an Service übergeben",
            details = mapOf(
                "has_extras" to (intent.extras != null),
                "extras_count" to (intent.extras?.size() ?: 0)
            )
        )

        context.startForegroundService(serviceIntent)
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
    private fun handleSmsSent() {
        val resultMessage = when (resultCode) {
            Activity.RESULT_OK -> "SMS erfolgreich gesendet"
            else -> "SMS senden fehlgeschlagen"
        }

        SnackbarManager.showInfo("SMSReceiver: $resultMessage")
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
    private val prefsManager: SharedPreferencesManager by lazy {
        SharedPreferencesManager(applicationContext)}
        private var isServiceRunning = false
    private var currentNotificationText = "App läuft im Hintergrund."
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val restartHandler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { restartService() }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockTimeout = 5 * 60 * 1000L // 5 Minuten Standard-Timeout
    private val wakeLockTag = "SmsForwarder:ForegroundService"

    // Mutex für thread-safe Wake Lock Zugriff
    private val wakeLockMutex = Mutex()
    private var restartAttempts = 0
    private var lastRestartTime = 0L
    private  val MAX_RESTART_ATTEMPTS = 3
    private  val RESTART_COOLDOWN_MS = 5000L  // 5 Sekunden Abkühlzeit
    private  val RESTART_RESET_MS = 60000L    // Reset Zähler nach 1 Minute

    companion object {
        private const val CHANNEL_ID = "SmsForwarderChannel"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_DELAY = 1000L // 1 Sekunde
        private var isRunning = false

            private const val MIN_WAKE_LOCK_TIMEOUT = 1 * 60 * 1000L // 1 Minute
            private const val MAX_WAKE_LOCK_TIMEOUT = 30 * 60 * 1000L // 30 Minuten

        // Atomic-Referenz für Prozess-übergreifende Konsistenz
        private val serviceInstance = AtomicReference<SmsForegroundService>()


        fun startService(context: Context) {
            if (!isRunning) {
                val intent = Intent(context, SmsForegroundService::class.java).apply {
                    action = "START_SERVICE"
                }
                    context.startForegroundService(intent)
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
                    context.startForegroundService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            serviceInstance.set(this)
            createNotificationChannel()
            acquireServiceWakeLock()
            resetRestartAttempts() // Reset beim Start
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








    private suspend fun withWakeLock(timeout: Long = wakeLockTimeout, block: suspend () -> Unit) {
        wakeLockMutex.withLock {
            try {
                acquireWakeLock(timeout)
                block()
            } finally {
                releaseWakeLock()
            }
        }
    }

    /**
     * Verbesserte Wake Lock Acquisition mit Validierung
     */
    private fun acquireWakeLock(timeout: Long) {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    try {
                        lock.release()
                    } catch (e: Exception) {
                        LoggingManager.logError(component = "SmsForegroundService",
                            action = "WAKE_LOCK",
                            message = "Wake Lock bereits aktiv",
                            details = mapOf(
                                "timeout" to timeout,
                                "tag" to wakeLockTag
                            )
                        )
                    }
                }
                return
            }

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                wakeLockTag
            ).apply {
                setReferenceCounted(false)

                // Prüfe ob Timeout sinnvoll ist
                val validTimeout = when {
                    timeout <= 0 -> wakeLockTimeout
                    timeout > 30 * 60 * 1000L -> 30 * 60 * 1000L // Max 30 Minuten
                    else -> timeout
                }

                acquire(validTimeout)
            }

            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "WAKE_LOCK",
                message = "Wake Lock erfolgreich aktiviert",
                details = mapOf(
                    "timeout" to timeout,
                    "tag" to wakeLockTag
                )
            )
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "WAKE_LOCK_ERROR",
                message = "Fehler beim Aktivieren des Wake Locks",
                error = e
            )
            wakeLock = null
        }
    }

    private fun acquireServiceWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                LoggingManager.logInfo(
                    component = "SmsForegroundService",
                    action = "WAKE_LOCK",
                    message = "Wake Lock bereits aktiv"
                )
                return
            }

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                wakeLockTag
            ).apply {
                setReferenceCounted(false)
                // Kein Timeout, da der Service dauerhaft laufen soll
                acquire()
            }

            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "WAKE_LOCK",
                message = "Service Wake Lock aktiviert",
                details = mapOf(
                    "tag" to wakeLockTag,
                    "type" to "PARTIAL_WAKE_LOCK"
                )
            )
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "WAKE_LOCK_ERROR",
                message = "Fehler beim Aktivieren des Service Wake Locks",
                error = e
            )
            wakeLock = null
        }
    }

    /**
     * Sichere Wake Lock Freigabe
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    LoggingManager.logInfo(
                        component = "SmsForegroundService",
                        action = "WAKE_LOCK",
                        message = "Wake Lock erfolgreich freigegeben"
                    )
                }
            }
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "WAKE_LOCK_ERROR",
                message = "Fehler beim Freigeben des Wake Locks",
                error = e
            )
        } finally {
            wakeLock = null
        }
    }

    /**
     * Verbesserte onDestroy mit sicherer Wake Lock Freigabe
     */


    /**
     * Timeout-Management für längere Operationen
     */
    private suspend fun processLongRunningTask() {
        // Berechne benötigte Zeit basierend auf Aufgabe
        val estimatedProcessingTime = calculateProcessingTime()

        withWakeLock(timeout = estimatedProcessingTime) {
            // Lange laufende Operation hier
            // performLongRunningTask()
        }
    }

    private fun calculateProcessingTime(): Long {
        // Implementiere Logik zur Berechnung der geschätzten Verarbeitungszeit
        return 10 * 60 * 1000L // Beispiel: 10 Minuten
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                "START_SERVICE" -> {
                    if (!isServiceRunning) {
                        startForegroundService()
                    }
                }
                "PROCESS_SMS" -> {
                    if (!isServiceRunning) {
                        startForegroundService()
                    }

                    // SMS-Daten aus Intent extrahieren und verarbeiten
                    intent.extras?.let { extras ->
                        processSmsData(extras)
                    }
                }
                "UPDATE_NOTIFICATION" -> {
                    val newText = intent.getStringExtra("contentText")
                    if (newText != null && newText != currentNotificationText) {
                        currentNotificationText = newText
                        updateNotification(newText)
                    }
                }
                null -> {
                    // Service wurde nach System-Kill neugestartet
                    if (!isServiceRunning) {
                        startForegroundService()
                        LoggingManager.logInfo(
                            component = "SmsForegroundService",
                            action = "RESTART",
                            message = "Service nach System-Kill neugestartet"
                        )
                    }
                }
                else -> {
                    LoggingManager.logWarning(
                        component = "SmsForegroundService",
                        action = "UNKNOWN_COMMAND",
                        message = "Unbekannte Action empfangen",
                        details = mapOf("action" to (intent.action ?: "null"))
                    )
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


    private fun processSmsData(extras: Bundle) {
        serviceScope.launch {
            withWakeLock(timeout = 2 * 60 * 1000L) { // 2 Minuten Timeout für SMS-Verarbeitung
                try {
                    // Hier erstellen wir einen neuen Intent mit den Extras
                    val smsIntent = Intent().apply {
                        action = Telephony.Sms.Intents.SMS_RECEIVED_ACTION  // Wichtig!
                        putExtras(extras)
                    }

                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(smsIntent)



                    if (messages.isNullOrEmpty()) {
                        LoggingManager.logWarning(
                            component = "SmsForegroundService",
                            action = "PROCESS_SMS",
                            message = "Keine SMS-Nachrichten gefunden"
                        )
                        return@withWakeLock
                    }

                    // Map zur Zusammenführung von SMS-Teilen pro Absender
                    val smsMap = mutableMapOf<String, StringBuilder>()

                    // Nachrichten nach Absender gruppieren
                    messages.forEach { smsMessage ->
                        val sender = smsMessage.originatingAddress ?: return@forEach
                        val messageBody = smsMessage.messageBody

                        smsMap.getOrPut(sender) { StringBuilder() }
                            .append(messageBody)
                    }

                    // Verarbeite jede zusammengeführte Nachricht
                    smsMap.forEach { (sender, messageBody) ->
                        // SMS-zu-SMS Weiterleitung
                        if (prefsManager.isForwardingActive()) {
                            prefsManager.getSelectedPhoneNumber()?.let { forwardToNumber ->
                                val fullMessage = buildForwardedSmsMessage(sender, messageBody.toString())
                                forwardSms(forwardToNumber, fullMessage)
                            }
                        }

                        // SMS-zu-Email Weiterleitung
                        if (prefsManager.isForwardSmsToEmail()) {
                            handleEmailForwarding(sender, messageBody.toString())
                        }
                    }

                    LoggingManager.logInfo(
                        component = "SmsForegroundService",
                        action = "PROCESS_SMS",
                        message = "SMS-Verarbeitung abgeschlossen",
                        details = mapOf(
                            "messages_count" to messages.size,
                            "unique_senders" to smsMap.size
                        )
                    )

                } catch (e: Exception) {
                    LoggingManager.logError(
                        component = "SmsForegroundService",
                        action = "PROCESS_SMS_ERROR",
                        message = "Fehler bei SMS-Verarbeitung",
                        error = e
                    )
                    SnackbarManager.showError("Fehler bei der SMS-Verarbeitung")
                }
            }
        }
    }

    private fun handleEmailForwarding(sender: String, messageBody: String) {
        serviceScope.launch {
            try {
                val emailAddresses = prefsManager.getEmailAddresses()

                if (emailAddresses.isEmpty()) {
                    LoggingManager.logWarning(
                        component = "SmsForegroundService",
                        action = "EMAIL_FORWARD",
                        message = "Keine Email-Adressen konfiguriert"
                    )
                    return@launch
                }

                // Hole SMTP-Einstellungen aus SharedPreferences
                val host = prefsManager.getSmtpHost()
                val port = prefsManager.getSmtpPort()
                val username = prefsManager.getSmtpUsername()
                val password = prefsManager.getSmtpPassword()

                if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    LoggingManager.logWarning(
                        component = "SmsForegroundService",
                        action = "EMAIL_FORWARD",
                        message = "Unvollständige SMTP-Konfiguration",
                        details = mapOf(
                            "has_host" to host.isNotEmpty(),
                            "has_username" to username.isNotEmpty(),
                            "has_credentials" to password.isNotEmpty()
                        )
                    )
                    return@launch
                }

                val emailSender = EmailSender(host, port, username, password)
                val subject = "Neue SMS von $sender"
                val body = buildEmailBody(sender, messageBody)

                when (val result = emailSender.sendEmail(emailAddresses, subject, body)) {
                    is EmailResult.Success -> {
                        LoggingManager.logInfo(
                            component = "SmsForegroundService",
                            action = "EMAIL_FORWARD",
                            message = "SMS erfolgreich per Email weitergeleitet",
                            details = mapOf(
                                "sender" to sender,
                                "recipients" to emailAddresses.size,
                                "smtp_host" to host
                            )
                        )
                        SnackbarManager.showSuccess("SMS per Email weitergeleitet")
                        updateServiceStatus() // Aktualisiere Notification
                    }
                    is EmailResult.Error -> {
                        LoggingManager.logError(
                            component = "SmsForegroundService",
                            action = "EMAIL_FORWARD",
                            message = "Email-Weiterleitung fehlgeschlagen",
                            details = mapOf(
                                "error" to result.message,
                                "smtp_host" to host,
                                "has_credentials" to (username.isNotEmpty() && password.isNotEmpty())
                            )
                        )
                        SnackbarManager.showError("Email-Weiterleitung fehlgeschlagen: ${result.message}")
                    }
                }

            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "EMAIL_FORWARD",
                    message = "Unerwarteter Fehler bei Email-Weiterleitung",
                    error = e,
                    details = mapOf(
                        "sender" to sender,
                        "message_length" to messageBody.length
                    )
                )
                SnackbarManager.showError("Fehler bei der Email-Weiterleitung")
            }
        }
    }

    private fun buildEmailBody(sender: String, messageBody: String): String {
        return buildString {
            append("SMS Weiterleitung\n\n")
            append("Absender: $sender\n")
            append("Zeitpunkt: ${getCurrentTimestamp()}\n\n")
            append("Nachricht:\n")
            append(messageBody)
            append("\n\nDiese E-Mail wurde automatisch durch den SMS Forwarder generiert.")
        }
    }

    private fun buildForwardedSmsMessage(sender: String, message: String): String {
        return buildString {
            append("Von: ").append(sender).append("\n")
            append("Zeit: ").append(getCurrentTimestamp()).append("\n")
            append("Nachricht:\n").append(message)
        }
    }

    private fun forwardSms(targetNumber: String, message: String) {
        try {
            PhoneSmsUtils.sendSms(applicationContext, targetNumber, message)

            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "FORWARD_SMS",
                message = "SMS erfolgreich weitergeleitet",
                details = mapOf(
                    "target" to targetNumber,
                    "length" to message.length
                )
            )

            // Aktualisiere Notification mit Weiterleitungsstatus
            updateServiceStatus()

        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "FORWARD_SMS_ERROR",
                message = "Fehler bei SMS-Weiterleitung",
                error = e,
                details = mapOf("target" to targetNumber)
            )
            SnackbarManager.showError("SMS-Weiterleitung fehlgeschlagen")
        }
    }

    private fun updateServiceStatus() {
        val status = buildServiceStatus(prefsManager)
        updateNotification(status)
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date())
    }


    private fun createNotificationChannel() {

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
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Zeigt den Status der SMS/Anruf-Weiterleitung an"
                    setShowBadge(true)
                    enableLights(false)
                    enableVibration(false)  // Hier auf false gesetzt
                    vibrationPattern = null // Optional: Vibrationsmuster explizit auf null setzen
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

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntentFlags =             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE


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
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true) // Verhindert wiederholte Benachrichtigungen
            .build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    private fun updateNotification(contentText: String) {
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
            val currentTime = System.currentTimeMillis()

            // Prüfe ob Service kürzlich neugestartet wurde
            if (currentTime - lastRestartTime < RESTART_COOLDOWN_MS) {
                LoggingManager.logWarning(
                    component = "SmsForegroundService",
                    action = "RESTART_COOLDOWN",
                    message = "Service-Neustart wird verzögert (Cooldown)",
                    details = mapOf(
                        "cooldown_remaining_ms" to (RESTART_COOLDOWN_MS - (currentTime - lastRestartTime))
                    )
                )
                return
            }

            restartService()
        }
    }
    private fun resetRestartAttempts() {
        restartAttempts = 0
        lastRestartTime = 0L
        LoggingManager.logInfo(
            component = "SmsForegroundService",
            action = "RESTART_COUNTER_RESET",
            message = "Neustart-Zähler zurückgesetzt"
        )
    }
    private fun scheduleHeartbeat() {
        restartHandler.removeCallbacks(restartRunnable)
        restartHandler.postDelayed(restartRunnable, RESTART_DELAY)
    }

    private fun restartService() {
        try {
            val currentTime = System.currentTimeMillis()

            // Reset Zähler wenn genug Zeit vergangen ist
            if (currentTime - lastRestartTime > RESTART_RESET_MS) {
                restartAttempts = 0
            }

            if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "RESTART_FAILED",
                    message = "Maximale Neustartversuche erreicht",
                    details = mapOf(
                        "attempts" to restartAttempts,
                        "cooldown_remaining_ms" to (RESTART_RESET_MS - (currentTime - lastRestartTime))
                    )
                )
                return
            }

            restartAttempts++
            lastRestartTime = currentTime

            serviceScope.launch {
                try {
                    // Stoppe aktuellen Service
                    stopForeground(STOP_FOREGROUND_REMOVE)

                    // Warte kurz
                    delay(RESTART_COOLDOWN_MS)

                    // Starte neu
                    startForegroundService()

                    LoggingManager.logInfo(
                        component = "SmsForegroundService",
                        action = "RESTART_SUCCESS",
                        message = "Service erfolgreich neugestartet",
                        details = mapOf(
                            "attempt" to restartAttempts,
                            "total_restarts" to restartAttempts
                        )
                    )
                } catch (e: Exception) {
                    LoggingManager.logError(
                        component = "SmsForegroundService",
                        action = "RESTART_ERROR",
                        message = "Fehler beim Neustart",
                        error = e,
                        details = mapOf(
                            "attempt" to restartAttempts,
                            "error_type" to e.javaClass.simpleName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "RESTART_CRITICAL_ERROR",
                message = "Kritischer Fehler beim Service-Neustart",
                error = e
            )
            stopSelf() // Beende Service bei kritischem Fehler
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
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    LoggingManager.logInfo(
                        component = "SmsForegroundService",
                        action = "WAKE_LOCK",
                        message = "Service Wake Lock freigegeben"
                    )
                }
            }
            wakeLock = null
            serviceScope.cancel()
            restartHandler.removeCallbacks(restartRunnable)

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