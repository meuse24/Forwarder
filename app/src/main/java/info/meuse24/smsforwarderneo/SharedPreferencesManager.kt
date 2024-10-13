package info.meuse24.smsforwarderneo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SharedPreferencesManager(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val PREFS_NAME = "SMSForwarderEncryptedPrefs"
        private const val KEY_SELECTED_PHONE = "selected_phone_number"
        private const val KEY_FORWARDING_ACTIVE = "forwarding_active"
        private const val KEY_FILTER_TEXT = "filter_text"
        private const val KEY_TEST_SMS_TEXT = "test_sms_text"
        private const val KEY_OWN_PHONE_NUMBER = "own_phone_number"
        private const val KEY_TOP_BAR_TITLE = "top_bar_title"
        private const val DEFAULT_TOP_BAR_TITLE = "TEL/SMS-Weiterleitung"
    }

    fun saveOwnPhoneNumber(number: String) {
        prefs.edit().putString(KEY_OWN_PHONE_NUMBER, number).apply()
    }

    fun getOwnPhoneNumber(): String = prefs.getString(KEY_OWN_PHONE_NUMBER, "") ?: ""

    fun saveTopBarTitle(title: String) {
        prefs.edit().putString(KEY_TOP_BAR_TITLE, title).apply()
    }

    fun getTopBarTitle(): String {
        return prefs.getString(KEY_TOP_BAR_TITLE, DEFAULT_TOP_BAR_TITLE) ?: DEFAULT_TOP_BAR_TITLE
    }

    fun saveSelectedPhoneNumber(phoneNumber: String?) {
        prefs.edit().apply {
            putString(KEY_SELECTED_PHONE, phoneNumber)
            putBoolean(KEY_FORWARDING_ACTIVE, phoneNumber != null)
            apply()
        }
    }

    fun getSelectedPhoneNumber(): String? = prefs.getString(KEY_SELECTED_PHONE, null)

    fun isForwardingActive(): Boolean = prefs.getBoolean(KEY_FORWARDING_ACTIVE, false)

    fun clearSelection() {
        prefs.edit().apply {
            remove(KEY_SELECTED_PHONE)
            putBoolean(KEY_FORWARDING_ACTIVE, false)
            apply()
        }
    }

    fun saveFilterText(filterText: String) {
        prefs.edit().putString(KEY_FILTER_TEXT, filterText).apply()
    }

    fun getFilterText(): String = prefs.getString(KEY_FILTER_TEXT, "") ?: ""

    fun saveForwardingStatus(isActive: Boolean) {
        prefs.edit().putBoolean(KEY_FORWARDING_ACTIVE, isActive).apply()
    }

    fun saveTestSmsText(text: String) {
        prefs.edit().putString(KEY_TEST_SMS_TEXT, text).apply()
    }

    fun getTestSmsText(): String =
        prefs.getString(KEY_TEST_SMS_TEXT, "Das ist eine Test-SMS.") ?: "Das ist eine Test-SMS."
}