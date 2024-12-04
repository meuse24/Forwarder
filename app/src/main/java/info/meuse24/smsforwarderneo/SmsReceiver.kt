package info.meuse24.smsforwarderneo

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ActivityScenario.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.mail.MessagingException

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

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
}

data class SmsMessagePart(
    val body: String,
    val timestamp: Long,
    val referenceNumber: Int,
    val sequencePosition: Int,
    val totalParts: Int,
    val sender: String
)

class SmsForegroundService : Service() {
    private val prefsManager = AppContainer.prefsManager
    private var currentNotificationText = "App läuft im Hintergrund."
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockMutex = Mutex()
    private val wakeLockTimeout = 5 * 60 * 1000L // 5 Minuten Maximum
    private val wakeLockTag = "${BuildConfig.APPLICATION_ID}:ForegroundService"
    private var restartAttempts = 0
    private var lastRestartTime = 0L
    private  val maxRestartAttemps = 3
    private  val restartCoolDownMS = 5000L  // 5 Sekunden Abkühlzeit
    private  val restartResetMS = 60000L    // Reset Zähler nach 1 Minute

    private val serviceHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = Runnable {
        serviceScope.launch {
            try {
                if (isRunning) {
                    LoggingManager.logDebug(
                        component = "SmsForegroundService",
                        action = "HEARTBEAT",
                        message = "Service Heartbeat"
                    )
                    // Überprüfe Service-Status
                    ensureServiceRunning()
                    // Plane nächsten Heartbeat
                    scheduleHeartbeat()
                }
            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "HEARTBEAT_ERROR",
                    message = "Fehler beim Heartbeat",
                    error = e
                )
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "MY_CHANNEL_ID"
        private const val DEFAULT_NOTIFICATION_TEXT = "TEL/SMS Forwarder läuft im Hintergrund."

        @Volatile
        private var isRunning = false

        fun startService(context: Context) {
            if (!isRunning) {
                val intent = Intent(context, SmsForegroundService::class.java)
                context.startForegroundService(intent)
            }
        }

        fun stopService(context: Context) {
            if (isRunning) {
                context.stopService(Intent(context, SmsForegroundService::class.java))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        try {
            setupService()
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "CREATE",
                message = "Service creation failed",
                error = e
            )
            stopSelf()
        }
    }

    private fun setupService() {
        val notification = createNotification(DEFAULT_NOTIFICATION_TEXT)
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true

        LoggingManager.logInfo(
            component = "SmsForegroundService",
            action = "START",
            message = "Service started successfully"
        )
    }

    private suspend fun withWakeLock(timeout: Long = wakeLockTimeout, block: suspend () -> Unit) {
        wakeLockMutex.withLock {
            try {
                acquireWakeLock(timeout)
                withTimeout(timeout) {
                    block()
                }
            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "WAKE_LOCK_ERROR",
                    message = "Fehler während WakeLock-Operation",
                    error = e
                )
                throw e
            } finally {
                releaseWakeLock()
            }
        }
    }

    private fun acquireWakeLock(timeout: Long) {
        try {
            releaseWakeLock() // Bestehenden WakeLock freigeben

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                wakeLockTag
            ).apply {
                setReferenceCounted(false) // WakeLock wird nicht referenzgezählt
                acquire(minOf(timeout, wakeLockTimeout))
            }

            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "WAKE_LOCK_ACQUIRED",
                message = "WakeLock aktiviert",
                details = mapOf("timeout_ms" to timeout)
            )
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "WAKE_LOCK_ERROR",
                message = "WakeLock konnte nicht aktiviert werden",
                error = e
            )
            wakeLock = null
            throw e
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            try {
                if (lock.isHeld) {
                    lock.release()
                    LoggingManager.logInfo(
                        component = "SmsForegroundService",
                        action = "WAKE_LOCK_RELEASED",
                        message = "WakeLock freigegeben"
                    )
                }
            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "WAKE_LOCK_RELEASE_ERROR",
                    message = "Fehler beim Freigeben des WakeLock",
                    error = e
                )
            } finally {
                wakeLock = null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                "START_SERVICE" -> {
                    if (!isRunning) {
                        startForegroundService()
                    }
                }
                "PROCESS_SMS" -> {
                    if (!isRunning) {
                        startForegroundService()
                    }

                    intent.extras?.let { extras ->
                        serviceScope.launch {
                            processSmsData(extras)
                        }
                    }
                }

                "UPDATE_NOTIFICATION" -> {
                    val newText = intent.getStringExtra("contentText")
                    if (newText != null && newText != currentNotificationText) {
                        updateNotification(newText)
                    }
                }

                null -> {
                    // Service wurde nach System-Kill neugestartet
                    if (!isRunning) {
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

    private suspend fun processSmsData(extras: Bundle) {
        withWakeLock(2 * 60 * 1000L) { // 2 Minuten für SMS-Verarbeitung
            try {
                val smsIntent = Intent().apply {
                    action = Telephony.Sms.Intents.SMS_RECEIVED_ACTION
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

                val messageParts = messages.mapNotNull { smsMessage ->
                    val sender = smsMessage.originatingAddress ?: return@mapNotNull null
                    SmsMessagePart(
                        body = smsMessage.messageBody,
                        timestamp = smsMessage.timestampMillis,
                        referenceNumber = smsMessage.messageRef,
                        sequencePosition = smsMessage.indexOnIcc,
                        totalParts = messages.size,
                        sender = sender
                    )
                }

                val messageGroups = messageParts.groupBy {
                    "${it.sender}_${it.referenceNumber}"
                }

                messageGroups.forEach { (key, parts) ->
                    val sender = key.substringBefore('_')
                    processMessageGroup(sender, parts)
                }

                LoggingManager.logInfo(
                    component = "SmsForegroundService",
                    action = "PROCESS_SMS",
                    message = "SMS-Verarbeitung abgeschlossen",
                    details = mapOf(
                        "messages_count" to messages.size,
                        "groups_count" to messageGroups.size
                    )
                )

            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "PROCESS_SMS_ERROR",
                    message = "Fehler bei SMS-Verarbeitung",
                    error = e
                )
                SnackbarManager.showError("Fehler bei der SMS-Verarbeitung: ${e.message}")
            }
        }
    }

    private suspend fun processMessageGroup(sender: String, parts: List<SmsMessagePart>) {
        try {
            val orderedParts = parts.sortedWith(
                compareBy<SmsMessagePart> { it.sequencePosition }
                    .thenBy { it.timestamp }
            )

            val fullMessage = orderedParts.joinToString("") { it.body }

            LoggingManager.logDebug(
                component = "SmsForegroundService",
                action = "PROCESS_MESSAGE_GROUP",
                message = "Verarbeite SMS-Gruppe",
                details = mapOf(
                    "sender" to sender,
                    "parts_count" to parts.size,
                    "total_length" to fullMessage.length,
                    "is_multipart" to (parts.size > 1)
                )
            )

            // Parallele Ausführung von SMS- und Email-Weiterleitung
            coroutineScope {
                // SMS Weiterleitung
                if (prefsManager.isForwardingActive()) {
                    launch {
                        prefsManager.getSelectedPhoneNumber().let { forwardToNumber ->
                            val forwardedMessage = buildForwardedSmsMessage(sender, fullMessage)
                            withContext(Dispatchers.IO) {
                                forwardSms(forwardToNumber, forwardedMessage)
                            }
                        }
                    }
                }

                // Email Weiterleitung
                if (prefsManager.isForwardSmsToEmail()) {
                    launch {
                        withContext(Dispatchers.IO) {
                            handleEmailForwarding(sender, fullMessage)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "PROCESS_GROUP_ERROR",
                message = "Fehler bei der Verarbeitung einer SMS-Gruppe",
                error = e,
                details = mapOf(
                    "sender" to sender,
                    "parts_count" to parts.size
                )
            )
            handleProcessingError(sender, parts, e)
        }
    }

    private fun handleProcessingError(sender: String, parts: List<SmsMessagePart>, error: Exception) {
        try {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "HANDLE_ERROR",
                message = "SMS-Verarbeitung wird wiederholt",
                error = error,
                details = mapOf(
                    "sender" to sender,
                    "parts_count" to parts.size,
                    "error_type" to error.javaClass.simpleName
                )
            )

            SnackbarManager.showError(
                "Fehler bei SMS von $sender: ${error.message}"
            )

            if (shouldRetry(error)) {
                scheduleRetry(sender, parts)
            }

        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "ERROR_HANDLER_FAILED",
                message = "Fehlerbehandlung fehlgeschlagen",
                error = e
            )
        }
    }

    private fun shouldRetry(error: Exception): Boolean {
        return when (error) {
            is IOException,
            is SocketException,
            is MessagingException -> true
            else -> false
        }
    }

    private fun scheduleRetry(sender: String, parts: List<SmsMessagePart>) {
        serviceScope.launch {
            delay(5000) // 5 Sekunden Wartezeit
            processMessageGroup(sender, parts)
        }
    }

    private fun buildForwardedSmsMessage(sender: String, message: String): String {
        return buildString {
            append("Von: ").append(sender).append("\n")
            append("Zeit: ").append(getCurrentTimestamp()).append("\n")
            append("Nachricht:\n").append(message)
            if (message.length > 160) {
                append("\n\n(Lange Nachricht, ${message.length} Zeichen)")
            }
        }
    }

    private suspend fun forwardSms(targetNumber: String, message: String) {
        try {
            if (message.length > 1600) {
                LoggingManager.logWarning(
                    component = "SmsForegroundService",
                    action = "FORWARD_SMS",
                    message = "Nachricht zu lang für Weiterleitung",
                    details = mapOf(
                        "length" to message.length,
                        "max_length" to 1600
                    )
                )
                SnackbarManager.showWarning("Nachricht zu lang für SMS-Weiterleitung")
                return
            }

            PhoneSmsUtils.sendSms(applicationContext, targetNumber, message)

            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "FORWARD_SMS",
                message = "SMS erfolgreich weitergeleitet",
                details = mapOf(
                    "target" to targetNumber,
                    "length" to message.length,
                    "is_multipart" to (message.length > 160)
                )
            )

            updateServiceStatus()

        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "FORWARD_SMS_ERROR",
                message = "Fehler bei SMS-Weiterleitung",
                error = e,
                details = mapOf(
                    "target" to targetNumber,
                    "message_length" to message.length
                )
            )
            throw e
        }
    }

    private val SmsMessage.messageRef: Int
        get() = try {
            val field = SmsMessage::class.java.getDeclaredField("mMessageRef")
            field.isAccessible = true
            field.getInt(this)
        } catch (e: Exception) {
            0 // Fallback wenn nicht verfügbar
        }

    private fun handleEmailForwarding(sender: String, messageBody: String) {
        serviceScope.launch {
            withWakeLock(30 * 1000L) { // 30 Sekunden für Email-Versand
                try {
                    val emailAddresses = prefsManager.getEmailAddresses()
                    if (emailAddresses.isEmpty()) {
                        LoggingManager.logWarning(
                            component = "SmsForegroundService",
                            action = "EMAIL_FORWARD",
                            message = "Keine Email-Adressen konfiguriert"
                        )
                        return@withWakeLock
                    }

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
                        return@withWakeLock
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
                            updateServiceStatus()
                        }
                        is EmailResult.Error -> handleEmailError(result.message, sender, emailAddresses, host)
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
    }

    private fun handleEmailError(errorMessage: String, sender: String, recipients: List<String>, host: String) {
        LoggingManager.logError(
            component = "SmsForegroundService",
            action = "EMAIL_FORWARD",
            message = "Email-Weiterleitung fehlgeschlagen",
            details = mapOf(
                "error" to errorMessage,
                "smtp_host" to host,
                "recipients" to recipients.size,
                "sender" to sender
            )
        )
        SnackbarManager.showError("Email-Weiterleitung fehlgeschlagen: $errorMessage")
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

    private fun updateServiceStatus() {
        val status = buildServiceStatus(prefsManager)
        updateNotification(status)
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date())
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TEL/SMS Forwarder")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.logofwd2)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        if (!isRunning) return

        try {
            currentNotificationText = message
            val notification = createNotification(message)
            notificationManager.notify(NOTIFICATION_ID, notification)

            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "UPDATE_NOTIFICATION",
                message = "Notification aktualisiert",
                details = mapOf(
                    "text" to message,
                    "success" to true
                )
            )
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SmsForegroundService",
                action = "UPDATE_NOTIFICATION",
                message = "Fehler beim Aktualisieren der Notification",
                error = e
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        isRunning = true
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

    private fun scheduleHeartbeat() {
        serviceHandler.removeCallbacks(heartbeatRunnable) // Entferne vorherige Callbacks
        serviceHandler.postDelayed(heartbeatRunnable, 60_000) // Plane nächsten Heartbeat in 1 Minute
    }

    private suspend fun monitorService() {
        while (isRunning) {
            try {
                ensureServiceRunning()
                delay(60_000) // Verzögerung von 1 Minute zwischen den Checks
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // Normaler Shutdown - kein Logging nötig
                    break
                }
                // Nur ernsthafte Fehler protokollieren
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
        // Einfacher Check, ob der Service noch läuft und wieder neu gestartet werden muss
        if (!isRunning) {
            val currentTime = System.currentTimeMillis()

            // Prüfe, ob der Neustartkühldown eingehalten wurde
            if (currentTime - lastRestartTime < restartCoolDownMS) {
                LoggingManager.logWarning(
                    component = "SmsForegroundService",
                    action = "RESTART_COOLDOWN",
                    message = "Service-Neustart wird verzögert (Cooldown läuft)",
                    details = mapOf(
                        "cooldown_remaining_ms" to (restartCoolDownMS - (currentTime - lastRestartTime))
                    )
                )
                return
            }

            // Neustart des Service, wenn notwendig
            restartService()
        }
    }

    private fun restartService() {
        try {
            val currentTime = System.currentTimeMillis()

            // Reset Zähler wenn genug Zeit vergangen ist
            if (currentTime - lastRestartTime > restartResetMS) {
                restartAttempts = 0
            }

            // Prüfe, ob die maximale Anzahl an Neustartversuchen erreicht wurde
            if (restartAttempts >= maxRestartAttemps) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "RESTART_FAILED",
                    message = "Maximale Neustartversuche erreicht. Service wird gestoppt.",
                    details = mapOf(
                        "attempts" to restartAttempts,
                        "cooldown_remaining_ms" to (restartResetMS - (currentTime - lastRestartTime))
                    )
                )
                stopSelf() // Service wird gestoppt, da max. Neustarts erreicht sind
                return
            }

            // Erhöhe die Anzahl der Neustartversuche
            restartAttempts++
            lastRestartTime = currentTime

            serviceScope.launch {
                try {
                    // Stoppe aktuellen Service
                    stopForeground(STOP_FOREGROUND_REMOVE)

                    // Warte kurz (Cooldown)
                    delay(restartCoolDownMS)

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
        return buildString {
            if (!prefs.isForwardingActive() && !prefs.isForwardSmsToEmail()) {
                append(DEFAULT_NOTIFICATION_TEXT)  // Statt "Keine Weiterleitung aktiv"
            } else {
                if (prefs.isForwardingActive()) {
                    append("SMS-Weiterleitung aktiv")
                    prefs.getSelectedPhoneNumber().let { number ->
                        if (number.isNotEmpty()) {
                            append(" zu $number")
                        }
                    }
                }
                if (prefs.isForwardSmsToEmail()) {
                    if (prefs.isForwardingActive()) append("\n")
                    append("Email-Weiterleitung aktiv")
                    val emailCount = prefs.getEmailAddresses().size
                    append(" an $emailCount Email(s)")
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            serviceHandler.removeCallbacks(heartbeatRunnable)
            // Beende alle laufenden Coroutines und warte auf deren Beendigung
            runBlocking {
                serviceScope.coroutineContext[Job]?.let { job ->
                    job.children.forEach { it.join() }
                }
            }

            // Jetzt den Scope selbst abbrechen
            serviceScope.cancel()

            LoggingManager.logInfo(
                component = "SmsForegroundService",
                action = "DESTROY",
                message = "Foreground Service wurde beendet und alle laufenden Coroutines wurden ordnungsgemäß gestoppt"
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