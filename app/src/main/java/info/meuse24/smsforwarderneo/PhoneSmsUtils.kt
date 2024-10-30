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
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber

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

    }

    companion object {
        private const val TAG = "PhoneSmsUtils"
        //private lateinit var logger: Logger

        fun initialize() {
            //this.logger = logger
        }

        /**
         * Sendet eine Test-SMS.
         * @param context Der Anwendungskontext
         * @param phoneNumber Die Zieltelefonnummer
         * @param testSmsText Der zu sendende Text
         * @return true, wenn die SMS erfolgreich gesendet wurde, sonst false
         */
        fun sendTestSms(context: Context, phoneNumber: String?, testSmsText: String, logger: Logger): Boolean {
            if (phoneNumber.isNullOrEmpty()) {
                logMsg("No contact selected for SMS", logger)
                InterfaceHolder.myInterface?.showToast("Kein Kontakt ausgewählt")
                return false
            }

            if (testSmsText.isEmpty()) {
                logMsg("Empty SMS text", logger)
                InterfaceHolder.myInterface?.showToast("Der SMS-Text ist leer")
                return false
            }

            if (!checkPermission(context, Manifest.permission.SEND_SMS)) {
                Log.e(TAG, "SEND_SMS permission not granted")
                logMsg("SEND_SMS permission not granted", logger)
                InterfaceHolder.myInterface?.showToast("Berechtigung zum Senden von SMS nicht erteilt")
                return false
            }

            return try {
                val (encodedText, smsLength) = Gsm7BitEncoder.encode(testSmsText)
                val maxLength = if (smsLength <= 160) 160 else 153 // 153 für Multipart-SMS

                if (smsLength > maxLength) {
                    InterfaceHolder.myInterface?.showToast("Warnung: SMS wird in mehrere Teile aufgeteilt")
                }

                sendSms(context, phoneNumber, encodedText,logger)
                logMsg("Test-SMS gesendet an $phoneNumber", logger)
                InterfaceHolder.myInterface?.showToast("Test-SMS gesendet an $phoneNumber")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error sending SMS", e)
                logMsg("Error sending SMS: ${e.message}", logger)
                handleSmsForwardingError(e, logger)
                false
            }
        }

        @SuppressLint("NewApi")
        fun sendSms(context: Context, phoneNumber: String, text: String, logger: Logger?) {
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
                    logMsg("Multipart SMS sent to $phoneNumber (${parts.size} parts)", logger)
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
                    logMsg("Single SMS sent to $phoneNumber", logger)
                }
            } catch (e: Exception) {

                logMsg("Error sending SMS: ${e.message}", logger)
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
        fun sendUssdCode(context: Context, ussdCode: String, logger: Logger): Boolean {
            if (ussdCode.isBlank()) {
                InterfaceHolder.myInterface?.showToast("USSD-Code darf nicht leer sein")
                logMsg("USSD code is blank", logger)
                return false
            }

            if (!checkPermission(context, Manifest.permission.CALL_PHONE)) {

                logMsg("CALL_PHONE permission not granted", logger)
                InterfaceHolder.myInterface?.showToast("Berechtigung für Telefonanrufe nicht erteilt")
                return false
            }

            return try {
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                if (telephonyManager == null) {
                    logMsg("TelephonyManager is null", logger)
                    InterfaceHolder.myInterface?.showToast("Telefondienst nicht verfügbar")
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
                            logMsg("USSD Response: $response", logger)
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
                            logMsg("USSD Response Failed: $failureReason", logger)
                            InterfaceHolder.myInterface?.showToast("USSD Fehler: $failureReason")
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                logMsg("USSD request sent: $ussdCode", logger)
                InterfaceHolder.myInterface?.showToast("USSD-Anfrage gesendet")
                true
            } catch (e: SecurityException) {
                logMsg("Security exception when sending USSD code: ${e.message}", logger)
                InterfaceHolder.myInterface?.showToast("Sicherheitsfehler beim Senden des USSD-Codes")
                false
            } catch (e: Exception) {
                InterfaceHolder.myInterface?.showToast("Fehler beim Senden des USSD-Codes: ${e.message}")
                logMsg("Error sending USSD code: ${e.message}", logger,e)
                false
            }
        }

        @SuppressLint("MissingPermission")
        fun getSimCardNumber(context: Context, logger: Logger): String {
            // Überprüfen der Berechtigungen
            if (!checkPermission(context, Manifest.permission.READ_PHONE_STATE) ||
                (!checkPermission(
                    context,
                    Manifest.permission.READ_PHONE_NUMBERS
                ))
            ) {
                logMsg("Fehlende Berechtigungen für das Lesen der Telefonnummer", logger)
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


                phoneNumber.ifEmpty {
                    Log.w(TAG, "Telefonnummer konnte nicht ermittelt werden")
                    ""
                }
            } catch (e: SecurityException) {
                logMsg("SecurityException beim Abrufen der Telefonnummer: ${e.message}", logger,e)
                InterfaceHolder.myInterface?.showToast("Sicherheitsfehler beim Abrufen der Telefonnummer")
                ""
            } catch (e: Exception) {
                logMsg("Fehler beim Abrufen der Telefonnummer: ${e.message}", logger,e)
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
        private fun handleSmsForwardingError(e: Exception, logger: Logger) {
            val errorMessage = when (e) {
                is SecurityException -> "Fehlende Berechtigung zum Senden von SMS"
                is IllegalArgumentException -> "Ungültige Telefonnummer oder leere Nachricht"
                else -> "SMS-Weiterleitung fehlgeschlagen: ${e.message}"
            }
            logMsg(errorMessage, logger,e)
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

        // Hilfsmethode für Logging
        private fun logMsg(message: String, logger: Logger?=null, e: Exception? = null) {
            Log.e(TAG, message, e)
            logger?.addLogEntry("Log: $message ${e?.message ?: ""}")
        }

        private val COUNTRY_CODES = mapOf(
            "43" to "Österreich",
            "93" to "Afghanistan",
            "355" to "Albanien",
            "213" to "Algerien",
            "1-684" to "Amerikanisch-Samoa",
            "376" to "Andorra",
            "244" to "Angola",
            "1-264" to "Anguilla",
            "672" to "Antarktis",
            "1-268" to "Antigua und Barbuda",
            "54" to "Argentinien",
            "374" to "Armenien",
            "297" to "Aruba",
            "994" to "Aserbaidschan",
            "251" to "Äthiopien",
            "61" to "Australien",
            "1-242" to "Bahamas",
            "973" to "Bahrain",
            "880" to "Bangladesch",
            "1-246" to "Barbados",
            "375" to "Belarus",
            "32" to "Belgien",
            "501" to "Belize",
            "229" to "Benin",
            "1-441" to "Bermuda",
            "975" to "Bhutan",
            "591" to "Bolivien",
            "387" to "Bosnien und Herzegowina",
            "267" to "Botswana",
            "55" to "Brasilien",
            "673" to "Brunei",
            "359" to "Bulgarien",
            "226" to "Burkina Faso",
            "257" to "Burundi",
            "238" to "Cabo Verde",
            "56" to "Chile",
            "86" to "China",
            "45" to "Dänemark",
            "49" to "Deutschland",
            "246" to "Diego Garcia",
            "253" to "Dschibuti",
            "1-767" to "Dominica",
            "1-809" to "Dominikanische Republik",
            "1-829" to "Dominikanische Republik",
            "1-849" to "Dominikanische Republik",
            "593" to "Ecuador",
            "503" to "El Salvador",
            "225" to "Elfenbeinküste",
            "291" to "Eritrea",
            "372" to "Estland",
            "268" to "Eswatini",
            "679" to "Fidschi",
            "358" to "Finnland",
            "33" to "Frankreich",
            "594" to "Französisch-Guayana",
            "689" to "Französisch-Polynesien",
            "241" to "Gabun",
            "220" to "Gambia",
            "995" to "Georgien",
            "233" to "Ghana",
            "350" to "Gibraltar",
            "1-473" to "Grenada",
            "30" to "Griechenland",
            "299" to "Grönland",
            "44" to "Großbritannien",
            "1-671" to "Guam",
            "502" to "Guatemala",
            "44-1481" to "Guernsey",
            "224" to "Guinea",
            "245" to "Guinea-Bissau",
            "592" to "Guyana",
            "509" to "Haiti",
            "504" to "Honduras",
            "852" to "Hongkong",
            "91" to "Indien",
            "62" to "Indonesien",
            "964" to "Irak",
            "98" to "Iran",
            "353" to "Irland",
            "354" to "Island",
            "972" to "Israel",
            "39" to "Italien",
            "1-876" to "Jamaika",
            "81" to "Japan",
            "967" to "Jemen",
            "44-1534" to "Jersey",
            "962" to "Jordanien",
            "1-345" to "Kaimaninseln",
            "855" to "Kambodscha",
            "237" to "Kamerun",
            "1" to "Kanada",
            "238" to "Kap Verde",
            "7" to "Kasachstan",
            "974" to "Katar",
            "254" to "Kenia",
            "996" to "Kirgisistan",
            "686" to "Kiribati",
            "57" to "Kolumbien",
            "269" to "Komoren",
            "242" to "Kongo",
            "243" to "Kongo, Demokratische Republik",
            "850" to "Korea, Nord",
            "82" to "Korea, Süd",
            "385" to "Kroatien",
            "53" to "Kuba",
            "965" to "Kuwait",
            "856" to "Laos",
            "266" to "Lesotho",
            "371" to "Lettland",
            "961" to "Libanon",
            "231" to "Liberia",
            "218" to "Libyen",
            "423" to "Liechtenstein",
            "370" to "Litauen",
            "352" to "Luxemburg",
            "853" to "Macau",
            "261" to "Madagaskar",
            "265" to "Malawi",
            "60" to "Malaysia",
            "960" to "Malediven",
            "223" to "Mali",
            "356" to "Malta",
            "212" to "Marokko",
            "692" to "Marshallinseln",
            "596" to "Martinique",
            "230" to "Mauritius",
            "262" to "Mayotte",
            "52" to "Mexiko",
            "691" to "Mikronesien",
            "373" to "Moldau",
            "377" to "Monaco",
            "976" to "Mongolei",
            "382" to "Montenegro",
            "1-664" to "Montserrat",
            "258" to "Mosambik",
            "95" to "Myanmar",
            "264" to "Namibia",
            "674" to "Nauru",
            "977" to "Nepal",
            "687" to "Neukaledonien",
            "64" to "Neuseeland",
            "505" to "Nicaragua",
            "31" to "Niederlande",
            "227" to "Niger",
            "234" to "Nigeria",
            "683" to "Niue",
            "1-670" to "Nördliche Marianen",
            "389" to "Nordmazedonien",
            "47" to "Norwegen",
            "968" to "Oman",

            "92" to "Pakistan",
            "680" to "Palau",
            "970" to "Palästina",
            "507" to "Panama",
            "675" to "Papua-Neuguinea",
            "595" to "Paraguay",
            "51" to "Peru",
            "63" to "Philippinen",
            "48" to "Polen",
            "351" to "Portugal",
            "1-787" to "Puerto Rico",
            "1-939" to "Puerto Rico",
            "240" to "Äquatorialguinea",
            "974" to "Katar",
            "262" to "Réunion",
            "250" to "Ruanda",
            "40" to "Rumänien",
            "7" to "Russland",
            "677" to "Salomonen",
            "260" to "Sambia",
            "685" to "Samoa",
            "378" to "San Marino",
            "239" to "São Tomé und Príncipe",
            "966" to "Saudi-Arabien",
            "46" to "Schweden",
            "41" to "Schweiz",
            "221" to "Senegal",
            "381" to "Serbien",
            "248" to "Seychellen",
            "232" to "Sierra Leone",
            "65" to "Singapur",
            "421" to "Slowakei",
            "386" to "Slowenien",
            "252" to "Somalia",
            "34" to "Spanien",
            "94" to "Sri Lanka",
            "1-869" to "St. Kitts und Nevis",
            "1-758" to "St. Lucia",
            "1-784" to "St. Vincent und die Grenadinen",
            "27" to "Südafrika",
            "249" to "Sudan",
            "211" to "Südsudan",
            "597" to "Suriname",
            "47" to "Svalbard und Jan Mayen",
            "963" to "Syrien",
            "992" to "Tadschikistan",
            "886" to "Taiwan",
            "255" to "Tansania",
            "66" to "Thailand",
            "228" to "Togo",
            "690" to "Tokelau",
            "676" to "Tonga",
            "1-868" to "Trinidad und Tobago",
            "235" to "Tschad",
            "420" to "Tschechien",
            "216" to "Tunesien",
            "90" to "Türkei",
            "993" to "Turkmenistan",
            "1-649" to "Turks- und Caicosinseln",
            "688" to "Tuvalu",
            "256" to "Uganda",
            "380" to "Ukraine",
            "36" to "Ungarn",
            "598" to "Uruguay",
            "1" to "USA",
            "998" to "Usbekistan",
            "678" to "Vanuatu",
            "39-379" to "Vatikanstadt",
            "58" to "Venezuela",
            "971" to "Vereinigte Arabische Emirate",
            "84" to "Vietnam",
            "681" to "Wallis und Futuna",
            "212" to "Westsahara",
            "236" to "Zentralafrikanische Republik",
            "357" to "Zypern"
        )

        /**
         * Gibt die formatierte Ländervorwahl mit Ländernamen zurück
         * @param countryCode Die Ländervorwahl im Format "+XX"
         * @return String Formatierte Ausgabe im Format "+XX Länername" oder nur "+XX" wenn Land unbekannt
         */
        fun getCountryNameForCode(countryCode: String): String {
            val code = countryCode.removePrefix("+")
            val countryName = COUNTRY_CODES[code]
            return if (countryName != null) {
                "$countryCode ($countryName)"
            } else {
                countryCode
            }
        }

        fun standardizePhoneNumber(phoneNumber: String, defaultCountryCode: String): String {
            // Entferne alle Leerzeichen, Schrägstriche und sonstige Formatierung
            val cleanNumber = phoneNumber.replace(Regex("[\\s()\\-/]"), "")

            val standardizedNumber = when {
                cleanNumber.startsWith("00") -> "+" + cleanNumber.substring(2)
                cleanNumber.startsWith("+") -> cleanNumber
                else -> defaultCountryCode + cleanNumber.removePrefix("0")
            }

            // Prüfe, ob die Nummer mit einer bekannten Ländervorwahl beginnt und füge ggf. ein Leerzeichen ein
            val codeWithoutPlus = standardizedNumber.substring(1)
            return COUNTRY_CODES.keys
                .find { codeWithoutPlus.startsWith(it) }
                ?.let { "+$it ${codeWithoutPlus.substring(it.length)}" }
                ?: standardizedNumber
        }

        /**
         * Ermittelt die Ländervorwahl der SIM-Karte
         * @param context Android Context
         * @return String Ländervorwahl im Format "+XX", Default "+43" wenn nicht ermittelbar
         */
// In PhoneSmsUtils.kt - ersetze die bestehende getSimCardCountryCode Methode:

        @SuppressLint("MissingPermission")
        fun getSimCardCountryCode(context: Context, logger: Logger): String {
            try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    ?: return ""  // Geändert von "+43" zu ""

                // Prüfe ob notwendige Berechtigungen vorhanden sind
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                    logMsg("READ_PHONE_STATE permission not granted", logger)
                    return ""  // Geändert von "+43" zu ""
                }

                // 1. Hauptstrategie: SIM Operator (am verlässlichsten)
                val simOperator = telephonyManager.simOperator
                if (simOperator.isNotEmpty() && simOperator.length >= 3) {
                    when (simOperator.substring(0, 3)) {
                        "232" -> return "+43" // Österreich
                        "228" -> return "+41" // Schweiz
                        "262" -> return "+49" // Deutschland
                    }
                }

                // 2. Alternative: Network Operator (falls SIM-Info nicht verfügbar)
                val networkOperator = telephonyManager.networkOperator
                if (networkOperator.isNotEmpty() && networkOperator.length >= 3) {
                    when (networkOperator.substring(0, 3)) {
                        "232" -> return "+43" // Österreich
                        "228" -> return "+41" // Schweiz
                        "262" -> return "+49" // Deutschland
                    }
                }

                // 3. Letzte Alternative: ISO Ländercode
                when (telephonyManager.simCountryIso?.uppercase()) {
                    "AT" -> return "+43"
                    "CH" -> return "+41"
                    "DE" -> return "+49"
                }

                // Keine Erkennung möglich
                logMsg("Keine Ländervorwahl erkannt, verwende Default", logger)
                return ""

            } catch (e: Exception) {
                logMsg("Fehler bei der Erkennung der Ländervorwahl: ${e.message}", logger, e)
                return "" // Signalisiert: Keine Erkennung möglich
            }
        }
    }


}



class PhoneNumberValidator {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    /**
     * Validiert und formatiert eine Telefonnummer.
     * @param phoneNumber Die zu validierende Telefonnummer
     * @param defaultRegion Der Default-Ländercode (z.B. "AT" für Österreich)
     * @return ValidatedPhoneNumber Objekt mit Validierungsergebnis
     */
    fun validatePhoneNumber(phoneNumber: String, defaultRegion: String = "AT"): ValidatedPhoneNumber {
        try {
            // Versuche die Nummer zu parsen
            val numberProto = phoneUtil.parse(phoneNumber, defaultRegion)

            // Prüfe ob es eine valide Nummer ist
            if (!phoneUtil.isValidNumber(numberProto)) {
                return ValidatedPhoneNumber(
                    isValid = false,
                    errorType = PhoneNumberError.INVALID_NUMBER,
                    errorMessage = "Keine gültige Telefonnummer"
                )
            }

            // Prüfe ob es eine Mobilnummer ist (optional)
            if (phoneUtil.getNumberType(numberProto) != PhoneNumberUtil.PhoneNumberType.MOBILE) {
                return ValidatedPhoneNumber(
                    isValid = false,
                    errorType = PhoneNumberError.NOT_MOBILE,
                    errorMessage = "Keine Mobiltelefonnummer"
                )
            }

            // Formatiere die Nummer in verschiedenen Formaten
            val e164Format = phoneUtil.format(numberProto, PhoneNumberUtil.PhoneNumberFormat.E164)
            val internationalFormat = phoneUtil.format(numberProto, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
            val nationalFormat = phoneUtil.format(numberProto, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)

            return ValidatedPhoneNumber(
                isValid = true,
                normalizedNumber = e164Format,
                formattedInternational = internationalFormat,
                formattedNational = nationalFormat,
                countryCode = numberProto.countryCode.toString(),
                numberType = phoneUtil.getNumberType(numberProto)
            )
        } catch (e: NumberParseException) {
            val errorMsg = when (e.errorType) {
                NumberParseException.ErrorType.INVALID_COUNTRY_CODE -> "Ungültiger Ländercode"
                NumberParseException.ErrorType.NOT_A_NUMBER -> "Enthält ungültige Zeichen"
                NumberParseException.ErrorType.TOO_SHORT_AFTER_IDD -> "Nummer zu kurz"
                NumberParseException.ErrorType.TOO_SHORT_NSN -> "Nummer zu kurz"
                else -> "Unbekannter Fehler bei der Validierung"
            }
            return ValidatedPhoneNumber(
                isValid = false,
                errorType = PhoneNumberError.PARSE_ERROR,
                errorMessage = errorMsg
            )
        }
    }

    /**
     * Prüft ob zwei Telefonnummern gleich sind (auch bei unterschiedlicher Formatierung)
     */
    fun areSameNumber(number1: String, number2: String, defaultRegion: String = "AT"): Boolean {
        try {
            val proto1 = phoneUtil.parse(number1, defaultRegion)
            val proto2 = phoneUtil.parse(number2, defaultRegion)
            return phoneUtil.isNumberMatch(proto1, proto2) == PhoneNumberUtil.MatchType.EXACT_MATCH
        } catch (e: NumberParseException) {
            return false
        }
    }

    /**
     * Extrahiert den Ländercode aus einer Telefonnummer
     */
    fun extractCountryCode(phoneNumber: String, defaultRegion: String = "AT"): String? {
        try {
            val numberProto = phoneUtil.parse(phoneNumber, defaultRegion)
            return "+" + numberProto.countryCode.toString()
        } catch (e: NumberParseException) {
            return null
        }
    }
}

data class ValidatedPhoneNumber(
    val isValid: Boolean,
    val normalizedNumber: String? = null,
    val formattedInternational: String? = null,
    val formattedNational: String? = null,
    val countryCode: String? = null,
    val numberType: PhoneNumberUtil.PhoneNumberType? = null,
    val errorType: PhoneNumberError? = null,
    val errorMessage: String? = null
)

enum class PhoneNumberError {
    PARSE_ERROR,
    INVALID_NUMBER,
    NOT_MOBILE,
    UNKNOWN
}

data class PhoneNumberResult(
    val formattedNumber: String?,
    val carrierInfo: String?
)

class CarrierNode {
    val children = Array<CarrierNode?>(10) { null }
    var carrier: String? = null
}

class CarrierTrie {
    private val root = CarrierNode()

    fun insert(prefix: String, carrier: String) {
        var current = root
        for (digit in prefix) {
            val index = digit - '0'
            if (current.children[index] == null) {
                current.children[index] = CarrierNode()
            }
            current = current.children[index]!!
        }
        current.carrier = carrier
    }

    fun findLongestPrefix(number: String): Pair<String?, String> {
        var current = root
        var lastCarrier: String? = null
        var prefixLength = 0
        var lastValidPrefix = 0

        for ((index, digit) in number.withIndex()) {
            val digitIndex = digit - '0'
            val next = current.children[digitIndex] ?: break
            current = next
            if (current.carrier != null) {
                lastCarrier = current.carrier
                lastValidPrefix = index + 1
            }
            prefixLength++
        }

        return if (lastCarrier != null) {
            lastCarrier to number.substring(0, lastValidPrefix)
        } else {
            null to number.substring(0, 3.coerceAtMost(number.length))
        }
    }
}

class PhoneNumberFormatter {
    private val phoneUtil = PhoneNumberUtil.getInstance()
    private val austrianMobileTrie = CarrierTrie()
    private val germanMobileTrie = CarrierTrie()
    private val swissMobileTrie = CarrierTrie()

    init {
        // Österreichische Mobilfunkanbieter
        initAustrianCarriers()
        // Deutsche Mobilfunkanbieter
        initGermanCarriers()
        // Schweizer Mobilfunkanbieter
        initSwissCarriers()
    }

    private fun initAustrianCarriers() {
        val carriers = listOf(
            // A1 Telekom Austria Gruppe
            "650" to "A1",            // A1 direkt
            "664" to "A1",            // A1 direkt
            "680" to "Bob",           // Bob (A1 Diskontmarke)
            "681" to "Yesss",         // Yesss! (A1 Diskontmarke)
            "699" to "A1",            // A1 direkt

            // Magenta (T-Mobile)
            "676" to "Magenta",       // Magenta Hauptnummer
            "660" to "Magenta",       // ehem. tele.ring
            "661" to "Magenta",       // ehem. tele.ring
            "663" to "Magenta",       // Magenta direkt
            "667" to "Magenta",       // Magenta direkt
            "669" to "Magenta",       // Magenta direkt

            // Drei (Hutchison)
            "670" to "Spusu",         // Spusu (auf Drei-Netz)
            "671" to "Drei",          // Drei direkt
            "673" to "Drei",          // Drei direkt
            "677" to "Drei",          // Drei direkt
            "678" to "Drei",          // Drei direkt
            "686" to "Drei",          // Drei direkt

            // Virtuelle Netze (MVNOs) und Spezialvorwahlen
            "59133" to "Festnetz/Polizei", // Messaging/Service
            "665" to "Lycamobile",      // MVNO auf A1-Netz
            "687" to "HOT",            // HOT (eigener Carrier)
            "688" to "HOT",            // HOT (zusätzliche Vorwahl)
            "668" to "MVNO",           // Diverse virtuelle Netze
            "689" to "MVNO",           // Diverse virtuelle Netze

            // Vorarlberg Festnetz - präzisiert nach Gemeinden/Regionen

            // Bregenzerwald
            "5510" to "Festnetz/Damüls",          // Damüls
            "5512" to "Festnetz/Au-Region",        // Au, Schoppernau, Schnepfau
            "5513" to "Festnetz/Kleinwalsertal",   // Mittelberg, Riezlern, Hirschegg, Baad
            "5514" to "Festnetz/Bezau-Region",     // Bezau, Bizau, Reuthe, Andelsbuch
            "5515" to "Festnetz/Mellau",           // Mellau, Schnepfau
            "5516" to "Festnetz/Vorder-Bregenzerwald", // Doren, Krumbach, Riefensberg, Sulzberg
            "5517" to "Festnetz/Sulzberg-Region",  // Sulzberg, Doren, Riefensberg
            "5518" to "Festnetz/Hittisau-Region",  // Hittisau, Sibratsgfäll, Balderschwang
            "5519" to "Festnetz/Lingenau-Region",  // Lingenau, Langenegg, Alberschwende, Egg

            // Rheintal-Nord
            "5574" to "Festnetz/Bregenz-Region",   // Bregenz, Hard, Lochau, Höchst, Fußach, Kennelbach
            "5573" to "Festnetz/Lustenau",         // Lustenau, Höchst (teilweise)

            // Rheintal-Mitte
            "5572" to "Festnetz/Dornbirn",         // Dornbirn, Schwarzach, Wolfurt, Bildstein
            "5575" to "Festnetz/Hohenems-Region",  // Hohenems, Götzis, Altach, Mäder

            // Rheintal-Süd/Vorderland
            "5576" to "Festnetz/Rankweil-Region",  // Rankweil, Sulz, Röthis, Klaus, Übersaxen, Zwischenwasser
            "5577" to "Festnetz/Vorderland-Nord",  // Altach, Götzis, Klaus
            "5578" to "Festnetz/Vorderland-Süd",   // Klaus, Weiler, Fraxern, Sulz, Röthis
            "5579" to "Festnetz/Koblach-Region",   // Koblach, Mäder, Klaus (teilweise)

            // Walgau/Feldkirch
            "5522" to "Festnetz/Feldkirch",        // Feldkirch, Göfis, Meiningen, Altenstadt, Gisingen, Tosters, Nofels
            "5523" to "Festnetz/Frastanz",         // Frastanz, Fellengatter
            "5524" to "Festnetz/Walgau-Süd",      // Satteins, Schlins, Schnifis, Düns, Dünserberg, Röns
            "5525" to "Festnetz/Nenzing",          // Nenzing, Gurtis, Beschling
            "5529" to "Festnetz/Walgau-Nord",     // Thüringen, Ludesch, Bludesch, Thüringerberg

            // Bludenz/Montafon/Klostertal
            "5526" to "Festnetz/Bludenz-Region",   // Bludenz, Bürs, Nüziders, Lorüns, Stallehr
            "5527" to "Festnetz/Montafon",         // Schruns, Tschagguns, St. Gallenkirch, Gaschurn, Silbertal, Bartholomäberg, St. Anton i.M.
            "5528" to "Festnetz/Klostertal",       // Innerbraz, Dalaas, Klösterle, Lech, Stuben, Zürs
        )
        carriers.forEach { (prefix, carrier) ->
            austrianMobileTrie.insert(prefix, carrier)
        }
    }

    private fun initGermanCarriers() {
        val carriers = listOf(
            "151" to "Telekom",
            "152" to "Telekom",
            "157" to "E-Plus",
            "159" to "O2",
            "160" to "O2",
            "170" to "O2",
            "171" to "O2",
            "175" to "Telekom",
            "176" to "O2",
            "177" to "Telekom",
            "178" to "O2",
            "179" to "O2"
        )
        carriers.forEach { (prefix, carrier) ->
            germanMobileTrie.insert(prefix, carrier)
        }
    }

    private fun initSwissCarriers() {
        val carriers = listOf(
            "76" to "Sunrise",
            "77" to "Swisscom",
            "78" to "Salt",
            "79" to "Swisscom"
        )
        carriers.forEach { (prefix, carrier) ->
            swissMobileTrie.insert(prefix, carrier)
        }
    }

    fun formatPhoneNumber(phoneNumber: String, defaultRegion: String = "AT"): PhoneNumberResult {
        try {
            val numberProto = phoneUtil.parse(phoneNumber, defaultRegion)
            val countryCode = numberProto.countryCode
            val nationalNumber = numberProto.nationalNumber.toString()
            val extension = numberProto.extension

            // Bestimme Carrier und Prefix mit Trie
            val (carrier, prefix) = when (countryCode) {
                43 -> getAustrianCarrierInfo(nationalNumber)
                49 -> getGermanCarrierInfo(nationalNumber)
                41 -> getSwissCarrierInfo(nationalNumber)
                else -> null to nationalNumber.take(3)
            }

            // Erstelle formatierte Nummer
            val mainNumber = nationalNumber.substring(prefix.length)
            val formattedNumber = buildString {
                append("+").append(countryCode)
                append(" ").append(prefix)
                append(" ").append(mainNumber)
                if (!extension.isNullOrEmpty()) {
                    append("-").append(extension)
                }
            }

            // Erstelle Carrier-Info-String
            val carrierInfo = when (countryCode) {
                43 -> carrier?.let { "AT/$it" }
                49 -> carrier?.let { "DE/$it" }
                41 -> carrier?.let { "CH/$it" }
                else -> null
            }

            return PhoneNumberResult(formattedNumber, carrierInfo)
        } catch (e: Exception) {
            return PhoneNumberResult(null, null)
        }
    }

    private fun getAustrianCarrierInfo(nationalNumber: String): Pair<String?, String> {
        // Prüfe zuerst auf Mobile Vorwahlen mit Trie
        val (carrier, prefix) = austrianMobileTrie.findLongestPrefix(nationalNumber)
        if (carrier != null) {
            return carrier to prefix
        }

        // Wenn keine Mobile Vorwahl gefunden wurde, prüfe auf Festnetz (Vorarlberg)
        if (nationalNumber.startsWith("5")) {
            return "Festnetz" to nationalNumber.take(4)
        }

        return null to nationalNumber.take(3)
    }

    private fun getGermanCarrierInfo(nationalNumber: String): Pair<String?, String> =
        germanMobileTrie.findLongestPrefix(nationalNumber)

    private fun getSwissCarrierInfo(nationalNumber: String): Pair<String?, String> =
        swissMobileTrie.findLongestPrefix(nationalNumber)

    fun isMobileNumber(phoneNumber: String, defaultRegion: String = "AT"): Boolean {
        return try {
            val numberProto = phoneUtil.parse(phoneNumber, defaultRegion)
            phoneUtil.getNumberType(numberProto) == PhoneNumberUtil.PhoneNumberType.MOBILE
        } catch (e: Exception) {
            false
        }
    }
}


class SimStatusChecker(private val context: Context) {

    /**
     * Datenklasse für das Ergebnis der SIM-Überprüfung
     * @property hasCellularSupport Gibt an, ob das Gerät grundsätzlich Mobilfunk unterstützt
     * @property hasSimCard Gibt an, ob eine SIM-Karte eingelegt ist
     * @property errorMessage Enthält ggf. eine Fehlermeldung
     */
    data class SimStatus(
        val hasCellularSupport: Boolean = false,
        val hasSimCard: Boolean = false,
        val errorMessage: String? = null
    )

    /**
     * Prüft den Status der SIM-Karte und Mobilfunkfähigkeit des Geräts
     * @return SimStatus Objekt mit den Ergebnissen
     */
    fun checkSimStatus(): SimStatus {
        try {
            // Hole TelephonyManager System Service
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return SimStatus(
                    errorMessage = "TelephonyManager konnte nicht initialisiert werden"
                )

            // Prüfe ob das Gerät grundsätzlich Mobilfunk unterstützt
            val hasCellularSupport = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

            // Wenn kein Mobilfunk unterstützt wird, können wir hier schon beenden
            if (!hasCellularSupport) {
                return SimStatus(
                    hasCellularSupport = false,
                    hasSimCard = false
                )
            }

            // Ab Android 10 unterschiedliche Methoden zur SIM-Erkennung
            val hasSimCard = when {
                // Ab Android 10
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    checkSimStatusApi29Plus(context)
                }
                // Für Android 10
                else -> {
                    telephonyManager.simState == TelephonyManager.SIM_STATE_READY
                }
            }

            return SimStatus(
                hasCellularSupport = true,
                hasSimCard = hasSimCard
            )

        } catch (e: Exception) {
            return SimStatus(
                errorMessage = "Fehler bei der SIM-Überprüfung: ${e.message}"
            )
        }
    }

    /**
     * Prüft den SIM-Status für Android API 29 (Android 10) und höher
     * @param context Der Application Context
     * @return Boolean true wenn eine aktive SIM erkannt wurde
     */
    private fun checkSimStatusApi29Plus(context: Context): Boolean {
        // Prüfe READ_PHONE_STATE Berechtigung
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return false

        // Prüfe ob aktive Subscriptions vorhanden sind
        return try {
            val activeSubscriptionInfoCount = subscriptionManager.activeSubscriptionInfoCount
            activeSubscriptionInfoCount > 0
        } catch (e: Exception) {
            false
        }
    }
}