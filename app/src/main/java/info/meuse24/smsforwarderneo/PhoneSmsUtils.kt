package info.meuse24.smsforwarderneo

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * PhoneSmsUtils ist eine Utility-Klasse für SMS- und Telefonie-bezogene Funktionen.
 * Sie bietet Methoden zum Senden von SMS, USSD-Codes und zum Abrufen von SIM-Karteninformationen.
 */
class PhoneSmsUtils private constructor() {

    /**
     * Gsm7BitEncoder ist ein Objekt zur Kodierung von Texten in GSM 7-Bit-Format.
     * Dies ist wichtig für die effiziente Übertragung von SMS.
     */
    object Gsm7BitEncoder {
        // GSM 7-Bit Alphabet: Enthält alle Standard-Zeichen, die in einer SMS verwendet werden können
        private val gsm7BitAlphabet = charArrayOf(
            '@', '£', '$', '¥', 'è', 'é', 'ù', 'ì', 'ò', 'Ç', '\n', 'Ø', 'ø', '\r', 'Å', 'å',
            'Δ', '_', 'Φ', 'Γ', 'Λ', 'Ω', 'Π', 'Ψ', 'Σ', 'Θ', 'Ξ', '\u001B', 'Æ', 'æ', 'ß', 'É',
            ' ', '!', '"', '#', '¤', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?',
            '¡', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
            'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'Ä', 'Ö', 'Ñ', 'Ü', '§',
            '¿', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o',
            'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'ä', 'ö', 'ñ', 'ü', 'à'
        )

        // Erweiterte Zeichen: Benötigen 2 Bytes in der GSM 7-Bit-Kodierung
        private val gsm7BitExtendedChars = mapOf(
            '|' to 40, '^' to 20, '€' to 101, '{' to 40, '}' to 41,
            '[' to 60, '~' to 20, ']' to 61, '\\' to 47
        )

        /**
         * Kodiert einen String in GSM 7-Bit-Format.
         * @param input Der zu kodierende Eingabestring
         * @return Ein Paar bestehend aus dem kodierten String und der Länge der resultierenden SMS
         */
        fun encode(input: String): Pair<String, Int> {
            val sb = StringBuilder()
            var smsLength = 0

            input.forEach { char ->
                when {
                    gsm7BitAlphabet.contains(char) -> {
                        sb.append(char)
                        smsLength++
                    }

                    gsm7BitExtendedChars.containsKey(char) -> {
                        sb.append('\u001B') // Escape-Zeichen
                        sb.append(gsm7BitAlphabet[gsm7BitExtendedChars[char] ?: 0])
                        smsLength += 2 // Erweiterte Zeichen zählen als 2
                    }

                    else -> {
                        // Ersetze nicht unterstützte Zeichen durch ein Leerzeichen
                        sb.append('_')
                        smsLength++
                    }
                }
            }

            return Pair(sb.toString(), smsLength)
        }

        /**
         * Berechnet die maximale Länge einer SMS basierend auf dem Eingabetext.
         * @param text Der zu prüfende Text
         * @return Die maximale Länge der SMS (160 für einzelne SMS, 153 für verkettete SMS)
         */
        fun getMaxSmsLength(text: String): Int {
            val (_, length) = encode(text)
            return when {
                length <= 160 -> 160
                else -> 153 // Für verkettete SMS kann jeder Teil 153 7-Bit-Zeichen haben
            }
        }
    }

    companion object {
        private const val TAG = "PhoneSmsUtils"
        private lateinit var logger: Logger

        fun initialize(logger: Logger) {
            this.logger = logger
        }

        /**
         * Sendet eine Test-SMS.
         * @param context Der Anwendungskontext
         * @param phoneNumber Die Zieltelefonnummer
         * @param testSmsText Der zu sendende Text
         * @return true, wenn die SMS erfolgreich gesendet wurde, sonst false
         */
        fun sendTestSms(context: Context, phoneNumber: String?, testSmsText: String): Boolean {
            if (phoneNumber.isNullOrEmpty()) {
                Log.e(TAG, "No contact selected for SMS")
                logMsg("No contact selected for SMS")
                InterfaceHolder.myInterface?.showToast("Kein Kontakt ausgewählt")
                return false
            }

            if (testSmsText.isEmpty()) {
                Log.e(TAG, "Empty SMS text")
                logMsg("Empty SMS text")
                InterfaceHolder.myInterface?.showToast("Der SMS-Text ist leer")
                return false
            }

            if (!checkPermission(context, Manifest.permission.SEND_SMS)) {
                Log.e(TAG, "SEND_SMS permission not granted")
                logMsg("SEND_SMS permission not granted")
                InterfaceHolder.myInterface?.showToast("Berechtigung zum Senden von SMS nicht erteilt")
                return false
            }

            return try {
                val (encodedText, smsLength) = Gsm7BitEncoder.encode(testSmsText)
                val maxLength = if (smsLength <= 160) 160 else 153 // 153 für Multipart-SMS

                if (smsLength > maxLength) {
                    InterfaceHolder.myInterface?.showToast("Warnung: SMS wird in mehrere Teile aufgeteilt")
                }

                sendSms(context, phoneNumber, encodedText)
                Log.i(TAG, "Test-SMS gesendet an $phoneNumber")
                logMsg("Test-SMS gesendet an $phoneNumber")
                InterfaceHolder.myInterface?.showToast("Test-SMS gesendet an $phoneNumber")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error sending SMS", e)
                logMsg("Error sending SMS: ${e.message}")
                handleSmsForwardingError(e)
                false
            }
        }

        @SuppressLint("NewApi")
        fun sendSms(context: Context, phoneNumber: String, text: String) {
            if (!checkPermission(context, Manifest.permission.SEND_SMS)) {
                throw SecurityException("SMS permission not granted")
            }

            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val (encodedText, smsLength) = Gsm7BitEncoder.encode(text)
            val maxLength = if (smsLength <= 160) 160 else 153

            try {
                if (encodedText.length > maxLength) {
                    val parts = smsManager.divideMessage(encodedText)
                    val sentIntents = ArrayList<PendingIntent>()
                    val deliveredIntents = ArrayList<PendingIntent>()

                    for (i in parts.indices) {
                        val sentIntent = PendingIntent.getBroadcast(
                            context,
                            0,
                            Intent("SMS_SENT"),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        val deliveredIntent = PendingIntent.getBroadcast(
                            context,
                            0,
                            Intent("SMS_DELIVERED"),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        sentIntents.add(sentIntent)
                        deliveredIntents.add(deliveredIntent)
                    }

                    smsManager.sendMultipartTextMessage(
                        phoneNumber,
                        null,
                        parts,
                        sentIntents,
                        deliveredIntents
                    )
                    Log.i(TAG, "Multipart SMS sent to $phoneNumber (${parts.size} parts)")
                    logMsg("Multipart SMS sent to $phoneNumber (${parts.size} parts)")
                } else {
                    val sentIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent("SMS_SENT"),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    val deliveredIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent("SMS_DELIVERED"),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    smsManager.sendTextMessage(
                        phoneNumber,
                        null,
                        encodedText,
                        sentIntent,
                        deliveredIntent
                    )
                    Log.i(TAG, "Single SMS sent to $phoneNumber")
                    logMsg("Single SMS sent to $phoneNumber")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending SMS", e)
                logMsg("Error sending SMS: ${e.message}")
                throw e
            }
        }

        /**
         * Sendet einen USSD-Code.
         * @param context Der Anwendungskontext
         * @param ussdCode Der zu sendende USSD-Code
         * @return true, wenn der Code erfolgreich gesendet wurde, sonst false
         */
        @SuppressLint("MissingPermission")
        fun sendUssdCode(context: Context, ussdCode: String): Boolean {
            if (ussdCode.isBlank()) {
                Log.e(TAG, "USSD code is blank")
                InterfaceHolder.myInterface?.showToast("USSD-Code darf nicht leer sein")
                logMsg("USSD code is blank")
                return false
            }

            if (!checkPermission(context, Manifest.permission.CALL_PHONE)) {
                Log.e(TAG, "CALL_PHONE permission not granted")
                logMsg("CALL_PHONE permission not granted")
                InterfaceHolder.myInterface?.showToast("Berechtigung für Telefonanrufe nicht erteilt")
                return false
            }

            return try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                if (telephonyManager == null) {
                    Log.e(TAG, "TelephonyManager is null")
                    logMsg("TelephonyManager is null")
                    InterfaceHolder.myInterface?.showToast("Telefondienst nicht verfügbar")
                    return false
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    Log.e(TAG, "USSD API not available for this Android version")
                    logMsg("USSD API not available for this Android version")
                    InterfaceHolder.myInterface?.showToast("USSD-Funktion nicht verfügbar auf diesem Gerät")
                    return false
                }

                telephonyManager.sendUssdRequest(
                    ussdCode,
                    object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(
                            telephonyManager: TelephonyManager,
                            request: String,
                            response: CharSequence
                        ) {
                            Log.i(TAG, "USSD Response: $response")
                            logMsg("USSD Response: $response")
                            InterfaceHolder.myInterface?.showToast("USSD Antwort: $response")
                        }

                        override fun onReceiveUssdResponseFailed(
                            telephonyManager: TelephonyManager,
                            request: String,
                            failureCode: Int
                        ) {
                            val failureReason = when (failureCode) {
                                TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "Dienst nicht verfügbar"
                                else -> "Unbekannter Fehler (Code: $failureCode)"
                            }
                            Log.e(TAG, "USSD Response Failed: $failureReason")
                            logMsg("USSD Response Failed: $failureReason")
                            InterfaceHolder.myInterface?.showToast("USSD Fehler: $failureReason")
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                Log.i(TAG, "USSD request sent: $ussdCode")
                logMsg("USSD request sent: $ussdCode")
                InterfaceHolder.myInterface?.showToast("USSD-Anfrage gesendet")
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception when sending USSD code", e)
                logMsg("Security exception when sending USSD code: ${e.message}")
                InterfaceHolder.myInterface?.showToast("Sicherheitsfehler beim Senden des USSD-Codes")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error sending USSD code", e)
                logMsg("Error sending USSD code: ${e.message}")
                InterfaceHolder.myInterface?.showToast("Fehler beim Senden des USSD-Codes: ${e.message}")
                false
            }
        }

        @SuppressLint("MissingPermission")
        fun getSimCardNumber(context: Context): String {
            // Überprüfen der Berechtigungen
            if (!checkPermission(context, Manifest.permission.READ_PHONE_STATE) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !checkPermission(
                    context,
                    Manifest.permission.READ_PHONE_NUMBERS
                ))
            ) {
                Log.w(TAG, "Fehlende Berechtigungen für das Lesen der Telefonnummer")
                logMsg("Fehlende Berechtigungen für das Lesen der Telefonnummer")
                InterfaceHolder.myInterface?.showToast("Berechtigungen für das Lesen der Telefonnummer fehlen")

                return ""
            }

            return try {
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val subscriptionManager =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                    } else null

                var phoneNumber = ""

                // Versuch 1: TelephonyManager.getLine1Number()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    phoneNumber = telephonyManager?.line1Number.orEmpty()
                }

                // Versuch 2: SubscriptionManager (für Dual-SIM-Geräte)
                if (phoneNumber.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val activeSubscriptionInfoList = subscriptionManager?.activeSubscriptionInfoList
                    for (subscriptionInfo in activeSubscriptionInfoList.orEmpty()) {
                        val number = subscriptionInfo.number
                        if (!number.isNullOrEmpty()) {
                            phoneNumber = number
                            break
                        }
                    }
                }

                // Versuch 3: TelephonyManager.getSimSerialNumber() (möglicherweise nicht die Telefonnummer, aber eine eindeutige Kennung)
                if (phoneNumber.isEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    val simSerial = telephonyManager?.simSerialNumber
                    if (!simSerial.isNullOrEmpty()) {
                        phoneNumber = simSerial
                    }
                }

                phoneNumber.ifEmpty {
                    Log.w(TAG, "Telefonnummer konnte nicht ermittelt werden")
                    ""
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException beim Abrufen der Telefonnummer", e)
                logMsg("SecurityException beim Abrufen der Telefonnummer: ${e.message}")
                InterfaceHolder.myInterface?.showToast("Sicherheitsfehler beim Abrufen der Telefonnummer")
                ""
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Abrufen der Telefonnummer", e)
                logMsg("Fehler beim Abrufen der Telefonnummer: ${e.message}")
                InterfaceHolder.myInterface?.showToast("Fehler beim Abrufen der Telefonnummer: ${e.message}")

                ""
            }
        }

        /**
         * Ruft die passende SmsManager-Instanz ab, abhängig von der Android-Version.
         * @param context Der Anwendungskontext
         * @return Die SmsManager-Instanz
         */
        private fun getSmsManager(context: Context): SmsManager {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        }

        /**
         * Behandelt Fehler beim Weiterleiten von SMS.
         * @param e Die aufgetretene Exception
         */
        private fun handleSmsForwardingError(e: Exception) {
            val errorMessage = when (e) {
                is SecurityException -> "Fehlende Berechtigung zum Senden von SMS"
                is IllegalArgumentException -> "Ungültige Telefonnummer oder leere Nachricht"
                else -> "SMS-Weiterleitung fehlgeschlagen: ${e.message}"
            }
            Log.e(TAG, errorMessage, e)
            logMsg(errorMessage, e)
            InterfaceHolder.myInterface?.showToast(errorMessage)
        }

        /**
         * Prüft, ob eine bestimmte Berechtigung erteilt wurde.
         * @param context Der Anwendungskontext
         * @param permission Die zu prüfende Berechtigung
         * @return true, wenn die Berechtigung erteilt wurde, sonst false
         */
        private fun checkPermission(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun logMsg(message: String, e: Exception? = null) {
            Log.e(TAG, message, e)
            logger.addLogEntry("Log: $message ${e?.message ?: ""}")
        }
    }
}