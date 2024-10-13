package info.meuse24.smsforwarderneo

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

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
        InterfaceHolder.myInterface?.addLogEntry("SMS-Weiterleitung OK: '$message' an $phoneNumber")
    }

    /**
     * Protokolliert einen Fehler bei der SMS-Übertragung.
     * @param e Die aufgetretene Exception
     * @param message Die zu sendende Nachricht
     * @param phoneNumber Die Zieltelefonnummer
     */
    private fun logError(e: Exception, message: String, phoneNumber: String) {
        InterfaceHolder.myInterface?.addLogEntry("SMS-Weiterleitungsfehler: ${e.message}, '$message' an $phoneNumber")
    }

    companion object {
        const val PHONE_NUMBER_KEY = "phoneNumber"
        const val MESSAGE_KEY = "message"
    }
}