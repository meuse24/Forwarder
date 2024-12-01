package info.meuse24.smsforwarderneo

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val wakeLockMutex = Mutex()
    private var restartAttempts = 0
    private var lastRestartTime = 0L
    private  val maxRestartAttemps = 3
    private  val restartCoolDownMS = 5000L  // 5 Sekunden Abkühlzeit
    private  val restartResetMS = 60000L    // Reset Zähler nach 1 Minute

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "SmsForwarderChannel"

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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(currentNotificationText))
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
            withWakeLock(timeout = 2 * 60 * 1000L) {
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

                    // Konvertiere SmsMessages in SmsMessageParts
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

                    // Gruppiere nach Sender und Referenznummer
                    val messageGroups = messageParts.groupBy {
                        "${it.sender}_${it.referenceNumber}"
                    }

                    // Verarbeite jede Gruppe
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
    }

    private suspend fun processMessageGroup(sender: String, parts: List<SmsMessagePart>) {
        try {
            // Sortiere Teile nach Position und Timestamp
            val orderedParts = parts.sortedWith(
                compareBy<SmsMessagePart> { it.sequencePosition }
                    .thenBy { it.timestamp }
            )

            // Setze Nachricht zusammen
            val fullMessage = orderedParts.joinToString("") { it.body }

            // Protokolliere Details für Debugging
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

            // SMS-zu-SMS Weiterleitung
            if (prefsManager.isForwardingActive()) {
                prefsManager.getSelectedPhoneNumber().let { forwardToNumber ->
                    val forwardedMessage = buildForwardedSmsMessage(sender, fullMessage)
                    withContext(Dispatchers.IO) {
                        forwardSms(forwardToNumber, forwardedMessage)
                    }
                }
            }

            // SMS-zu-Email Weiterleitung
            if (prefsManager.isForwardSmsToEmail()) {
                withContext(Dispatchers.IO) {
                    handleEmailForwarding(sender, fullMessage)
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
            throw e // Weitergabe an übergeordnete Fehlerbehandlung
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

    private fun forwardSms(targetNumber: String, message: String) {
        try {
            // Prüfe maximale SMS-Länge
            if (message.length > 1600) { // Ungefähr 10 SMS
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
            throw e // Weitergabe an übergeordnete Fehlerbehandlung
        }
    }

    // Hilfsmethode zum Extrahieren der Message Reference
    private val SmsMessage.messageRef: Int
        get() = try {
            val field = SmsMessage::class.java.getDeclaredField("mMessageRef")
            field.isAccessible = true
            field.getInt(this)
        } catch (e: Exception) {
            0 // Fallback wenn nicht verfügbar
        }

    // Hilfsmethode zum Extrahieren des Index
    private val SmsMessage.indexOnIcc: Int
        get() = try {
            val field = SmsMessage::class.java.getDeclaredField("mIndexOnIcc")
            field.isAccessible = true
            field.getInt(this)
        } catch (e: Exception) {
            0 // Fallback wenn nicht verfügbar
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
                ensureServiceRunning()
                delay(30000)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // Normaler Shutdown - kein Logging nötig
                    break
                }
                // Nur noch echte Fehler loggen
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
            if (currentTime - lastRestartTime < restartCoolDownMS) {
                LoggingManager.logWarning(
                    component = "SmsForegroundService",
                    action = "RESTART_COOLDOWN",
                    message = "Service-Neustart wird verzögert (Cooldown)",
                    details = mapOf(
                        "cooldown_remaining_ms" to (restartCoolDownMS - (currentTime - lastRestartTime))
                    )
                )
                return
            }

            restartService()
        }
    }

    private fun scheduleHeartbeat() {
        restartHandler.removeCallbacks(restartRunnable)
        restartHandler.postDelayed(restartRunnable, 1000)
    }

    private fun restartService() {
        try {
            val currentTime = System.currentTimeMillis()

            // Reset Zähler wenn genug Zeit vergangen ist
            if (currentTime - lastRestartTime > restartResetMS) {
                restartAttempts = 0
            }

            if (restartAttempts >= maxRestartAttemps) {
                LoggingManager.logError(
                    component = "SmsForegroundService",
                    action = "RESTART_FAILED",
                    message = "Maximale Neustartversuche erreicht",
                    details = mapOf(
                        "attempts" to restartAttempts,
                        "cooldown_remaining_ms" to (restartResetMS - (currentTime - lastRestartTime))
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
        val smsForwardingActive = prefs.isForwardingActive()
        val emailForwardingActive = prefs.isForwardSmsToEmail()
        return buildString {
            if (!smsForwardingActive && !emailForwardingActive) {
                append("Keine Weiterleitung aktiv")
            } else {
                if (smsForwardingActive) {
                    append("SMS-Weiterleitung aktiv")
                    append(" zu ${prefs.getSelectedPhoneNumber()}")
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
            //serviceInstance.set(null)

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