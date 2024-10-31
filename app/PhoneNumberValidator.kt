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

// Beispiel für Unit Tests:
class PhoneNumberValidatorTest {
    private val validator = PhoneNumberValidator()

    @Test
    fun `test valid Austrian mobile number`() {
        val result = validator.validatePhoneNumber("0664 1234567", "AT")
        assertTrue(result.isValid)
        assertEquals("+436641234567", result.normalizedNumber)
    }

    @Test
    fun `test invalid number`() {
        val result = validator.validatePhoneNumber("123", "AT")
        assertFalse(result.isValid)
        assertEquals(PhoneNumberError.INVALID_NUMBER, result.errorType)
    }

    @Test
    fun `test same number different format`() {
        assertTrue(validator.areSameNumber("+436641234567", "0664 1234567", "AT"))
    }
}