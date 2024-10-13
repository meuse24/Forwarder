package info.meuse24.smsforwarderneo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager

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

        // Überprüfen Sie die Berechtigung des Senders
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.BROADCAST_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Received intent from sender without BROADCAST_SMS permission")
            return
        }

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
                    // Leitet die Nachricht über den SmsWorker weiter
                    forwardSmsWithSmsWorker(context, forwardToNumber, fullMessage)
                }
            }
        }
    }

    /**
     * Verarbeitet Bestätigungen für gesendete SMS.
     * Zeigt eine Toast-Nachricht an, ob das Senden erfolgreich war oder nicht.
     */
    private fun handleSmsSent(context: Context, intent: Intent) {
        val resultMessage = when (resultCode) {
            Activity.RESULT_OK -> "SMS erfolgreich gesendet"
            else -> "SMS senden fehlgeschlagen"
        }
        // Zeigt das Ergebnis als Toast-Nachricht an
        InterfaceHolder.myInterface?.showToast(resultMessage)
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