package info.meuse24.smsforwarderneo

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.coroutines.flow.first
/**
 * ViewModel für die Verwaltung von Kontakten und SMS-Weiterleitungsfunktionen.
 * Implementiert DefaultLifecycleObserver für Lifecycle-bezogene Aktionen.
 */
@OptIn(FlowPreview::class)
class ContactsViewModel(
    private val application: Application,
    private val prefsManager: SharedPreferencesManager,
    private val logger: Logger
) : AndroidViewModel(application), DefaultLifecycleObserver {

    private val contactsStore = ContactsStore(logger)
    // StateFlows für verschiedene UI-Zustände
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedContact = MutableStateFlow<Contact?>(null)
    val selectedContact: StateFlow<Contact?> = _selectedContact

    private val _forwardingActive = MutableStateFlow(prefsManager.isForwardingActive())
    val forwardingActive: StateFlow<Boolean> = _forwardingActive

    private val _filterText = MutableStateFlow(prefsManager.getFilterText())
    val filterText: StateFlow<String> = _filterText

    private val _logEntriesHtml = MutableStateFlow("")
    val logEntriesHtml: StateFlow<String> = _logEntriesHtml

    private val _logEntries = MutableStateFlow("")
    val logEntries: StateFlow<String> = _logEntries

    private val _testSmsText = MutableStateFlow(prefsManager.getTestSmsText())
    val testSmsText: StateFlow<String> = _testSmsText

    private val _ownPhoneNumber = MutableStateFlow("")
    val ownPhoneNumber: StateFlow<String> = _ownPhoneNumber

    private val _topBarTitle = MutableStateFlow(prefsManager.getTopBarTitle())
    val topBarTitle: StateFlow<String> = _topBarTitle

    private val _navigationTarget = MutableStateFlow<String?>(null)
    val navigationTarget: StateFlow<String?> = _navigationTarget.asStateFlow()

    private val _countryCode = MutableStateFlow("")
    val countryCode: StateFlow<String> = _countryCode.asStateFlow()

    private val _countryCodeSource = MutableStateFlow<String>("")
    val countryCodeSource: StateFlow<String> = _countryCodeSource.asStateFlow()

    private val _showExitDialog = MutableStateFlow(false)
    val showExitDialog: StateFlow<Boolean> = _showExitDialog.asStateFlow()

    private val _showProgressDialog = MutableStateFlow(false)
    val showProgressDialog: StateFlow<Boolean> = _showProgressDialog.asStateFlow()

    private val _emailAddresses = MutableStateFlow<List<String>>(emptyList())
    val emailAddresses: StateFlow<List<String>> = _emailAddresses.asStateFlow()

    private val _newEmailAddress = MutableStateFlow("")
    val newEmailAddress: StateFlow<String> = _newEmailAddress.asStateFlow()

    private val _errorState = MutableStateFlow<ErrorDialogState?>(null)
    val errorState: StateFlow<ErrorDialogState?> = _errorState.asStateFlow()

    private val _isCleaningUp = MutableStateFlow(false)

    // Einmaliges Event für Cleanup-Abschluss
    private val _cleanupCompleted = MutableSharedFlow<Unit>()
    val cleanupCompleted = _cleanupCompleted.asSharedFlow()

    private val _showOwnNumberMissingDialog = MutableStateFlow(false)
    val showOwnNumberMissingDialog: StateFlow<Boolean> = _showOwnNumberMissingDialog.asStateFlow()

    private val _forwardSmsToEmail = MutableStateFlow(prefsManager.isForwardSmsToEmail())
    val forwardSmsToEmail: StateFlow<Boolean> = _forwardSmsToEmail.asStateFlow()

    private val _keepForwardingOnExit = MutableStateFlow(prefsManager.getKeepForwardingOnExit())
    //val keepForwardingOnExit: StateFlow<Boolean> = _keepForwardingOnExit.asStateFlow()
    private var filterJob: Job? = null
    private var initializationJob: Job? = null
    private val filterMutex = Mutex() // Verhindert parallele Filteroperationen

    private val _smtpHost = MutableStateFlow(prefsManager.getSmtpHost())
    val smtpHost: StateFlow<String> = _smtpHost.asStateFlow()

    private val _smtpPort = MutableStateFlow(prefsManager.getSmtpPort())
    val smtpPort: StateFlow<Int> = _smtpPort.asStateFlow()

    private val _smtpUsername = MutableStateFlow(prefsManager.getSmtpUsername())
    val smtpUsername: StateFlow<String> = _smtpUsername.asStateFlow()

    private val _smtpPassword = MutableStateFlow(prefsManager.getSmtpPassword())
    val smtpPassword: StateFlow<String> = _smtpPassword.asStateFlow()

    // Neue StateFlow für Test-Email-Text
    private val _testEmailText = MutableStateFlow(prefsManager.getTestEmailText())
    val testEmailText: StateFlow<String> = _testEmailText.asStateFlow()


    fun updateNewEmailAddress(email: String) {
        _newEmailAddress.value = email
    }

fun addEmailAddress() {
        val email = _newEmailAddress.value.trim()
        if (email.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            viewModelScope.launch {
                val currentList = _emailAddresses.value.toMutableList()
                if (!currentList.contains(email)) {
                    currentList.add(email)
                    _emailAddresses.value = currentList
                    prefsManager.saveEmailAddresses(currentList)
                    _newEmailAddress.value = "" // Reset input field
                    SnackbarManager.showSuccess("E-Mail-Adresse hinzugefügt")
                } else {
                    SnackbarManager.showWarning("E-Mail-Adresse existiert bereits")
                }
            }
        } else {
            SnackbarManager.showError("Ungültige E-Mail-Adresse")
        }
    }

    fun removeEmailAddress(email: String) {
        viewModelScope.launch {
            val currentList = _emailAddresses.value.toMutableList()
            currentList.remove(email)
            _emailAddresses.value = currentList
            prefsManager.saveEmailAddresses(currentList)

            // Wenn die Liste leer ist, deaktiviere die SMS-Email-Weiterleitung
            if (currentList.isEmpty() && _forwardSmsToEmail.value) {
                _forwardSmsToEmail.value = false
                prefsManager.setForwardSmsToEmail(false)
                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "SMS_EMAIL_FORWARD_AUTO_DISABLE",
                        details = mapOf(
                            "reason" to "no_email_addresses"
                        )
                    ),
                    "SMS-E-Mail-Weiterleitung automatisch deaktiviert (keine E-Mail-Adressen vorhanden)"
                )
                SnackbarManager.showInfo("SMS-E-Mail-Weiterleitung wurde deaktiviert, da keine E-Mail-Adressen mehr vorhanden sind")
            }

            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "REMOVE_EMAIL",
                    details = mapOf(
                        "remaining_emails" to currentList.size,
                        "forwarding_status" to _forwardSmsToEmail.value
                    )
                ),
                "E-Mail-Adresse entfernt"
            )
            SnackbarManager.showSuccess("E-Mail-Adresse entfernt")
        }
    }

    // Neue Methode zum Aktualisieren des Test-Email-Texts
    fun updateTestEmailText(newText: String) {
        _testEmailText.value = newText
        prefsManager.saveTestEmailText(newText)

        LoggingManager.log(
            LoggingHelper.LogLevel.DEBUG,
            LoggingHelper.LogMetadata(
                component = "ContactsViewModel",
                action = "UPDATE_TEST_EMAIL",
                details = mapOf(
                    "old_length" to _testEmailText.value.length,
                    "new_length" to newText.length,
                    "is_empty" to newText.isEmpty()
                )
            ),
            "Test-Email Text aktualisiert"
        )
    }


fun updateSmtpSettings(
        host: String,
        port: Int,
        username: String,
        password: String
    ) {
        _smtpHost.value = host
        _smtpPort.value = port
        _smtpUsername.value = username
        _smtpPassword.value = password
        prefsManager.saveSmtpSettings(host, port, username, password)
    }

    // In ContactsViewModel.kt, neue Funktion hinzufügen:
    // In ContactsViewModel.kt

    fun sendTestEmail(mailrecipent: String) {
        viewModelScope.launch {
            try {
                // Hole SMTP-Einstellungen aus SharedPreferences
                val host = prefsManager.getSmtpHost()
                val port = prefsManager.getSmtpPort()
                val username = prefsManager.getSmtpUsername()
                val password = prefsManager.getSmtpPassword()
                val testEmailText = prefsManager.getTestEmailText()

                // Prüfe ob alle erforderlichen SMTP-Einstellungen vorhanden sind
                if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    LoggingManager.log(
                        LoggingHelper.LogLevel.WARNING,
                        LoggingHelper.LogMetadata(
                            component = "ContactsViewModel",
                            action = "TEST_EMAIL",
                            details = mapOf(
                                "error" to "incomplete_smtp_settings",
                                "has_host" to host.isNotEmpty(),
                                "has_username" to username.isNotEmpty()
                            )
                        ),
                        "Unvollständige SMTP-Einstellungen"
                    )
                    SnackbarManager.showError("SMTP-Einstellungen sind unvollständig")
                    return@launch
                }

                val emailSender = EmailSender(host, port, username, password)

                // Erstelle formatierten Email-Text mit Timestamp
                val emailBody = buildString {
                    append("Test-Email von SMS Forwarder\n\n")
                    append("Zeitpunkt: ${getCurrentTimestamp()}\n\n")
                    append("Nachricht:\n")
                    append(testEmailText)
                    append("\n\nDies ist eine Test-Email zur Überprüfung der Email-Weiterleitungsfunktion.")
                }

                when (val result = emailSender.sendEmail(
                    to = listOf(mailrecipent),
                    subject = "SMS Forwarder Test E-Mail",
                    body = emailBody
                )) {
                    is EmailResult.Success -> {
                        LoggingManager.log(
                            LoggingHelper.LogLevel.INFO,
                            LoggingHelper.LogMetadata(
                                component = "ContactsViewModel",
                                action = "TEST_EMAIL_SENT",
                                details = mapOf(
                                    "recipient" to mailrecipent,
                                    "smtp_host" to host,
                                    "text_length" to testEmailText.length
                                )
                            ),
                            "Test-E-Mail wurde versendet"
                        )
                        SnackbarManager.showSuccess("Test-E-Mail wurde an $mailrecipent versendet")
                    }
                    is EmailResult.Error -> {
                        LoggingManager.log(
                            LoggingHelper.LogLevel.ERROR,
                            LoggingHelper.LogMetadata(
                                component = "ContactsViewModel",
                                action = "TEST_EMAIL_FAILED",
                                details = mapOf(
                                    "error" to result.message,
                                    "smtp_host" to host,
                                    "recipient" to mailrecipent
                                )
                            ),
                            "Fehler beim Versenden der Test-E-Mail"
                        )
                        SnackbarManager.showError("E-Mail-Versand fehlgeschlagen: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "TEST_EMAIL_ERROR",
                        details = mapOf(
                            "error" to e.message,
                            "recipient" to mailrecipent
                        )
                    ),
                    "Unerwarteter Fehler beim E-Mail-Versand"
                )
                SnackbarManager.showError("E-Mail-Versand fehlgeschlagen: ${e.message}")
            }
        }
    }

    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
    }
    @VisibleForTesting
    fun setTestContacts(contacts: List<Contact>) = viewModelScope.launch {
        // Warten bis der Store initialisiert ist
        contactsStore.setTestContacts(contacts)
        // Warten bis die Änderungen übernommen wurden
        delay(500)
        withContext(Dispatchers.IO) {
            // Verify contacts are set
            val contactsList = contactsStore.contacts.first()
            if (contactsList.isEmpty()) {
                throw AssertionError("Failed to initialize test contacts")
            }
        }
    }
    private fun initializeContactsStore() {
        initializationJob?.cancel()
        initializationJob = viewModelScope.launch {
            contactsStore.initialize(
                contentResolver = application.contentResolver,
                countryCode = _countryCode.value
            )
            // Keine direkte Filterung hier - wird durch _filterText Flow getriggert
        }
    }

    fun updateKeepForwardingOnExit(keep: Boolean) {
        _keepForwardingOnExit.value = keep
        prefsManager.setKeepForwardingOnExit(keep)
    }


    fun startCleanup(keepForwarding: Boolean) {
        if (_isCleaningUp.value) return
        viewModelScope.launch {
            try {
                _isCleaningUp.value = true
                _showProgressDialog.value = true

                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "CLEANUP",
                        details = mapOf(
                            "state" to "started",
                            "keep_forwarding" to keepForwarding
                        )
                    ),
                    "App-Cleanup gestartet"
                )

                if (!keepForwarding) {
                    _selectedContact.value = null
                    prefsManager.clearSelection()

                    if (PhoneSmsUtils.sendUssdCode(application, "##21#")) {
                        _forwardingActive.value = false
                        prefsManager.saveForwardingStatus(false)
                        LoggingManager.log(
                            LoggingHelper.LogLevel.INFO,
                            LoggingHelper.LogMetadata(
                                component = "ContactsViewModel",
                                action = "CLEANUP_FORWARDING",
                                details = mapOf("status" to "deactivated")
                            ),
                            "Weiterleitung wurde beim Beenden deaktiviert"
                        )
                    } else {
                        _errorState.value = ErrorDialogState.DeactivationError(
                            "Die Weiterleitung konnte nicht deaktiviert werden."
                        )
                        return@launch
                    }
                } else {
                    LoggingManager.log(
                        LoggingHelper.LogLevel.INFO,
                        LoggingHelper.LogMetadata(
                            component = "ContactsViewModel",
                            action = "CLEANUP_FORWARDING",
                            details = mapOf("status" to "kept_active")
                        ),
                        "Weiterleitung bleibt beim Beenden aktiv"
                    )
                }

                saveCurrentState()

                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "CLEANUP",
                        details = mapOf("state" to "completed")
                    ),
                    "App-Cleanup abgeschlossen"
                )

                _cleanupCompleted.emit(Unit)

            } catch (e: Exception) {
                _errorState.value = ErrorDialogState.GeneralError(e)
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "CLEANUP",
                        details = mapOf(
                            "error" to e.message,
                            "error_type" to e.javaClass.simpleName
                        )
                    ),
                    "Fehler beim App-Cleanup",
                    e
                )
            } finally {
                _isCleaningUp.value = false
                _showProgressDialog.value = false
            }
        }
    }

    // Diese Methode wird beim normalen Beenden aufgerufen

    fun deactivateForwarding() {
        val keepForwarding = _keepForwardingOnExit.value
        if (!keepForwarding) {
            val prevContact = _selectedContact.value  // Speichern für Logging
            _selectedContact.value = null
            prefsManager.clearSelection()
            _forwardingActive.value = false
            prefsManager.saveForwardingStatus(false)

            if (PhoneSmsUtils.sendUssdCode(application, "##21#")) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "DEACTIVATE_FORWARDING",
                        details = mapOf(
                            "success" to true,
                            "previous_contact" to (prevContact?.phoneNumber ?: "none")
                        )
                    ),
                    "Weiterleitung wurde deaktiviert"
                )
                updateNotification("keine Weiterleitung aktiv")
                updateForwardingStatus(false)
            } else {
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "DEACTIVATE_FORWARDING",
                        details = mapOf(
                            "success" to false,
                            "previous_contact" to (prevContact?.phoneNumber ?: "none"),
                            "error" to "USSD_CODE_FAILED"
                        )
                    ),
                    "Fehler beim Deaktivieren der Weiterleitung"
                )
            }
        }
    }


    // Aktualisierte Methoden für Dialog-Handling
    fun showOwnNumberMissingDialog() {
        _showOwnNumberMissingDialog.value = true
    }

    fun hideOwnNumberMissingDialog() {
        _showOwnNumberMissingDialog.value = false
    }

    // Error States als sealed class
    sealed class ErrorDialogState {
        data class DeactivationError(val message: String) : ErrorDialogState()
        object TimeoutError : ErrorDialogState()
        data class GeneralError(val error: Exception) : ErrorDialogState()
    }

    // Dialog Control Functions
    fun showExitDialog() {
        _showExitDialog.value = true
    }

    fun hideExitDialog() {
        _showExitDialog.value = false
    }

    fun setErrorState(error: ErrorDialogState?) {
        _errorState.value = error
    }

    fun clearErrorState() {
        _errorState.value = null
    }

    init {
        loadSavedState()
        initializeCountryCode()
        initializeContactsStore()
        loadSavedContact()
        checkForwardingStatus()
        _ownPhoneNumber.value = prefsManager.getOwnPhoneNumber()
        updateKeepForwardingOnExit(false)

        // Zentraler Filter-Flow
        viewModelScope.launch {
            _filterText
                .debounce(300)
                .collect { filterText ->
                    applyCurrentFilter()
                }
            _emailAddresses.value = prefsManager.getEmailAddresses()
        }
    }


    // In der initializeCountryCode Methode
    private fun initializeCountryCode() {
        viewModelScope.launch {
            try {
                // 1. Erste Priorität: SIM-Karte
                val simCode = PhoneSmsUtils.getSimCardCountryCode(application)
                if (simCode.isNotEmpty()) {
                    updateCountryCode(simCode)
                    _countryCodeSource.value = "SIM-Karte"
                    LoggingManager.log(
                        LoggingHelper.LogLevel.INFO,
                        LoggingHelper.LogMetadata(
                            component = "ContactsViewModel",
                            action = "COUNTRY_CODE_INIT",
                            details = mapOf(
                                "source" to "sim",
                                "code" to simCode
                            )
                        ),
                        "Ländercode von SIM-Karte ermittelt"
                    )
                    return@launch
                }

                // 2. Zweite Priorität: Eigene Telefonnummer
                val ownNumber = prefsManager.getOwnPhoneNumber()
                if (ownNumber.isNotEmpty()) {
                    try {
                        val phoneUtil = PhoneNumberUtil.getInstance()
                        val number = phoneUtil.parse(ownNumber, "")
                        val detectedCode = "+${number.countryCode}"
                        if (isValidCountryCode(detectedCode)) {
                            updateCountryCode(detectedCode)
                            _countryCodeSource.value = "Eigene Telefonnummer"
                            LoggingManager.log(
                                LoggingHelper.LogLevel.INFO,
                                LoggingHelper.LogMetadata(
                                    component = "ContactsViewModel",
                                    action = "COUNTRY_CODE_INIT",
                                    details = mapOf(
                                        "source" to "own_number",
                                        "code" to detectedCode
                                    )
                                ),
                                "Ländercode aus eigener Nummer ermittelt"
                            )
                            return@launch
                        }
                    } catch (e: Exception) {
                        LoggingManager.log(
                            LoggingHelper.LogLevel.WARNING,
                            LoggingHelper.LogMetadata(
                                component = "ContactsViewModel",
                                action = "COUNTRY_CODE_DETECTION_FAILED",
                                details = mapOf(
                                    "number" to ownNumber,
                                    "error" to e.message
                                )
                            ),
                            "Fehler bei der Erkennung des Ländercodes aus eigener Nummer"
                        )
                    }
                }

                // 3. Fallback auf Österreich
                updateCountryCode("+43")
                _countryCodeSource.value = "Standard (Österreich)"
                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "COUNTRY_CODE_INIT",
                        details = mapOf(
                            "source" to "default",
                            "code" to "+43"
                        )
                    ),
                    "Verwende Default-Ländercode: Österreich"
                )

            } catch (e: Exception) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "COUNTRY_CODE_INIT_ERROR",
                        details = mapOf(
                            "error" to e.message,
                            "error_type" to e.javaClass.simpleName
                        )
                    ),
                    "Fehler bei der Ländercode-Initialisierung",
                    e
                )
                updateCountryCode("+43")
                _countryCodeSource.value = "Standard (Österreich) nach Fehler"
            }
        }
    }

    private fun isValidCountryCode(code: String): Boolean {
        return when (code) {
            "+43", // Österreich
            "+49", // Deutschland
            "+41"  // Schweiz
                -> true
            else -> false
        }
    }

    private fun updateCountryCode(code: String) {
        _countryCode.value = code
        prefsManager.saveCountryCode(code)

        // Aktualisiere auch den ContactsStore
        contactsStore.updateCountryCode(code)
    }

    // Anpassung der Telefonnummer-Standardisierung
    private fun standardizePhoneNumber(number: String): String {
        return PhoneSmsUtils.standardizePhoneNumber(number, _countryCode.value)
    }

    fun navigateToSettings() {
        _navigationTarget.value = "setup"
    }

    fun onNavigated() {
        _navigationTarget.value = null
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        initializeApp()
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
       checkForwardingStatus()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        saveCurrentState()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
    }

    // Lifecycle-Methoden
    override fun onStop(owner: LifecycleOwner) {
        saveCurrentState()
        LoggingManager.log(
            LoggingHelper.LogLevel.INFO,
            LoggingHelper.LogMetadata(
                component = "ContactsViewModel",
                action = "LIFECYCLE_STOP",
                details = mapOf("state" to "saved")
            ),
            "App-Zustand beim Stoppen gespeichert"
        )
    }

    override fun onCleared() {
        super.onCleared()
        contactsStore.cleanup()
        saveCurrentState()
        LoggingManager.log(
            LoggingHelper.LogLevel.INFO,
            LoggingHelper.LogMetadata(
                component = "ContactsViewModel",
                action = "VIEWMODEL_CLEARED",
                details = mapOf("state" to "saved")
            ),
            "ViewModel wurde bereinigt"
        )
    }

    /**
     * Lädt die Log-Einträge neu.
     */
    fun reloadLogs() {
        viewModelScope.launch {
            _logEntriesHtml.value = logger.getLogEntriesHtml()
            _logEntries.value = logger.getLogEntries()
        }
    }

    fun updateTopBarTitle(title: String) {
        _topBarTitle.value = title
        prefsManager.saveTopBarTitle(title)
    }

    fun updateOwnPhoneNumber(number: String) {
        _ownPhoneNumber.value = number
        prefsManager.saveOwnPhoneNumber(number)
        LoggingManager.log(
            LoggingHelper.LogLevel.INFO,
            LoggingHelper.LogMetadata(
                component = "ContactsViewModel",
                action = "UPDATE_PHONE_NUMBER",
                details = mapOf(
                    "number_length" to number.length,
                    "has_number" to number.isNotEmpty()
                )
            ),
            "Eigene Telefonnummer aktualisiert"
        )
    }

    fun loadOwnPhoneNumberIfEmpty(context: Context) {
        if (_ownPhoneNumber.value.isEmpty()) {
            loadOwnPhoneNumber(context)
        }
    }

    fun loadOwnPhoneNumber(context: Context) {
        viewModelScope.launch {
            val number = PhoneSmsUtils.getSimCardNumber(context)
            if (number.isNotEmpty() && number != "Nummer konnte nicht ermittelt werden") {
                updateOwnPhoneNumber(number)
            } else {
                SnackbarManager.showError("Telefonnummer konnte nicht ermittelt werden")
            }
        }
    }

    /**
     * Lädt den gespeicherten Zustand der App.
     */
    fun loadSavedState() {
        viewModelScope.launch {
            val savedPhoneNumber = prefsManager.getSelectedPhoneNumber()
            if (!savedPhoneNumber.isNullOrEmpty()) {
                _selectedContact.value = Contact("", savedPhoneNumber,"")
                // Keine direkte Filterung hier - wird durch Flow gesteuert
            }
            _forwardingActive.value = prefsManager.isForwardingActive()
            updateForwardingStatus(prefsManager.isForwardingActive())
            _emailAddresses.value = prefsManager.getEmailAddresses()
            _forwardSmsToEmail.value = prefsManager.isForwardSmsToEmail()


            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "LOAD_STATE",
                    details = mapOf(
                        "has_saved_number" to (savedPhoneNumber != null),
                        "forwarding_active" to prefsManager.isForwardingActive(),
                        "email_addresses_count" to _emailAddresses.value.size,
                        "sms_to_email_active" to _forwardSmsToEmail.value
                    )
                ),
                "Gespeicherter App-Zustand geladen"
            )
        }
    }

    /**
     * Speichert den aktuellen Zustand der App.
     */
    fun saveCurrentState() {
        viewModelScope.launch {
            _selectedContact.value?.let { contact ->
                prefsManager.saveSelectedPhoneNumber(selectedContact.value?.phoneNumber)
                prefsManager.saveForwardingStatus(forwardingActive.value)
                prefsManager.saveFilterText(filterText.value)
            } ?: run {
                prefsManager.clearSelection()
                prefsManager.saveForwardingStatus(false)
            }
            prefsManager.saveFilterText(_filterText.value)
            prefsManager.saveTestSmsText(_testSmsText.value)
            prefsManager.saveEmailAddresses(_emailAddresses.value)
            prefsManager.setForwardSmsToEmail(_forwardSmsToEmail.value)

            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "SAVE_STATE",
                    details = mapOf(
                        "has_selected_contact" to (_selectedContact.value != null),
                        "forwarding_active" to forwardingActive.value,
                        "filter_text_length" to filterText.value.length,
                        "email_addresses_count" to _emailAddresses.value.size,
                        "sms_to_email_active" to _forwardSmsToEmail.value
                    )
                ),
                "App-Zustand gespeichert"
            )
        }
    }


    fun initializeApp() {
        viewModelScope.launch {
            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "INIT_APP",
                    details = mapOf("timestamp" to System.currentTimeMillis())
                ),
                "App-Initialisierung gestartet"
            )
            reloadLogs()
            updateForwardingStatus(prefsManager.isForwardingActive())
        }
    }



    /**
     * Wechselt die Auswahl eines Kontakts mit Prüfung auf Eigenweiterleitung.
     */
    fun toggleContactSelection(contact: Contact) {
        val currentSelected = _selectedContact.value
        if (contact == currentSelected) {
            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "DESELECT_CONTACT",
                    details = mapOf(
                        "contact_name" to contact.name,
                        "contact_number" to contact.phoneNumber
                    )
                ),
                "Kontakt wurde abgewählt"
            )
            deactivateForwarding()
            _selectedContact.value = null
        } else {
            val ownNumber = _ownPhoneNumber.value
            // Standardisiere beide Nummern für den Vergleich
            val standardizedContactNumber = standardizePhoneNumber(contact.phoneNumber)
            val standardizedOwnNumber = standardizePhoneNumber(ownNumber)

            if (standardizedContactNumber == standardizedOwnNumber) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.WARNING,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "PREVENT_SELF_FORWARDING",
                        details = mapOf("number" to contact.phoneNumber)
                    ),
                    "Eigenweiterleitung verhindert"
                )
                SnackbarManager.showError("Weiterleitung an eigene Nummer nicht möglich")
                if (_forwardingActive.value) {
                    deactivateForwarding()
                    _selectedContact.value = null
                }
                return
            }

            if (ownNumber.isBlank()) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.WARNING,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "MISSING_OWN_NUMBER",
                        details = mapOf(
                            "attempted_contact_name" to contact.name,
                            "attempted_contact_number" to contact.phoneNumber
                        )
                    ),
                    "Kontaktauswahl nicht möglich - eigene Nummer fehlt"
                )
                showOwnNumberMissingDialog()
            } else {
                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "SELECT_CONTACT",
                        details = mapOf(
                            "contact_name" to contact.name,
                            "contact_number" to contact.phoneNumber
                        )
                    ),
                    "Kontakt wurde ausgewählt"
                )
                _selectedContact.value = contact
                selectContact(contact)
            }
        }
    }

    /**
     * Aktualisiert den Filtertext für Kontakte.
     */
    fun updateFilterText(newFilter: String) {
        val oldFilter = _filterText.value
        _filterText.value = newFilter
        prefsManager.saveFilterText(newFilter)

        if (oldFilter != newFilter) {
            LoggingManager.log(
                LoggingHelper.LogLevel.DEBUG,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "UPDATE_FILTER",
                    details = mapOf(
                        "old_length" to oldFilter.length,
                        "new_length" to newFilter.length,
                        "is_clearing" to newFilter.isEmpty()
                    )
                ),
                "Kontaktfilter aktualisiert"
            )
        }
    }

    // Optimierte applyCurrentFilter mit Mutex

    private suspend fun applyCurrentFilter() {
        filterMutex.withLock {
            val startTime = System.currentTimeMillis()
            _isLoading.value = true
            try {
                val filteredContacts = contactsStore.filterContacts(_filterText.value)
                _contacts.value = filteredContacts

                LoggingManager.log(
                    LoggingHelper.LogLevel.DEBUG,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "APPLY_FILTER",
                        details = mapOf(
                            "duration_ms" to (System.currentTimeMillis() - startTime),
                            "filter_text" to _filterText.value,
                            "results_count" to filteredContacts.size,
                            "total_contacts" to contactsStore.contacts.value.size
                        )
                    ),
                    "Kontaktfilter angewendet"
                )

                // Update selected contact if necessary
                _selectedContact.value?.let { tempContact ->
                    _selectedContact.value = filteredContacts.find {
                        it.phoneNumber == tempContact.phoneNumber
                    }
                    if (_selectedContact.value == null) {
                        LoggingManager.log(
                            LoggingHelper.LogLevel.INFO,
                            LoggingHelper.LogMetadata(
                                component = "ContactsViewModel",
                                action = "SELECTED_CONTACT_FILTERED",
                                details = mapOf(
                                    "contact_number" to tempContact.phoneNumber
                                )
                            ),
                            "Ausgewählter Kontakt nicht mehr in gefilterter Liste"
                        )
                    }
                }
            } catch (e: Exception) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "FILTER_ERROR",
                        details = mapOf(
                            "error" to e.message,
                            "filter_text" to _filterText.value
                        )
                    ),
                    "Fehler bei Anwendung des Kontaktfilters",
                    e
                )
            } finally {
                _isLoading.value = false
            }
        }
    }


    /**
     * Aktualisiert den Text für Test-SMS.
     */

    fun updateTestSmsText(newText: String) {
        val oldText = _testSmsText.value
        _testSmsText.value = newText
        prefsManager.saveTestSmsText(newText)

        if (oldText != newText) {
            LoggingManager.log(
                LoggingHelper.LogLevel.DEBUG,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "UPDATE_TEST_SMS",
                    details = mapOf(
                        "old_length" to oldText.length,
                        "new_length" to newText.length,
                        "is_empty" to newText.isEmpty()
                    )
                ),
                "Test-SMS Text aktualisiert"
            )
        }
    }

    /**
     * Wählt einen Kontakt aus und aktiviert die Weiterleitung mit Sicherheitsprüfung.
     */
    private fun selectContact(contact: Contact?) {
        if (contact == null) {
            deactivateForwarding()
            updateForwardingStatus(false)
            return
        }

        val ownNumber = _ownPhoneNumber.value
        // Standardisiere beide Nummern für den Vergleich
        val standardizedContactNumber = standardizePhoneNumber(contact.phoneNumber)
        val standardizedOwnNumber = standardizePhoneNumber(ownNumber)

        if (standardizedContactNumber == standardizedOwnNumber) {
            // Verhindere Weiterleitung an eigene Nummer
            LoggingManager.log(
                LoggingHelper.LogLevel.WARNING,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "PREVENT_SELF_FORWARDING",
                    details = mapOf("number" to contact.phoneNumber)
                ),
                "Eigenweiterleitung verhindert"
            )
SnackbarManager.showError("Eigenweiterleitung nicht möglich")
            _selectedContact.value = null
            return
        }

        _selectedContact.value = contact
        prefsManager.saveSelectedPhoneNumber(contact.phoneNumber)
        _forwardingActive.value = true
        prefsManager.saveForwardingStatus(true)
        updateForwardingStatus(true)

        if (PhoneSmsUtils.sendUssdCode(application, "*21*${contact.phoneNumber}#")) {
            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "ACTIVATE_FORWARDING",
                    details = mapOf(
                        "contact_name" to contact.name,
                        "contact_number" to contact.phoneNumber
                    )
                ),
                "Weiterleitung wurde aktiviert"
            )
            updateNotification("Weiterleitung aktiv zu ${contact.name} (${contact.phoneNumber})")
        }else {
            LoggingManager.log(
                LoggingHelper.LogLevel.ERROR,
                LoggingHelper.LogMetadata(
                    component = "ContactsViewModel",
                    action = "ACTIVATE_FORWARDING_FAILED",
                    details = mapOf(
                        "contact_name" to contact.name,
                        "contact_number" to contact.phoneNumber
                    )
                ),
                "Weiterleitung konnte nicht aktiviert werden"
            )
        }
    }

    // Hilfsfunktion für USSD-Deaktivierung mit Timeout
    private suspend fun deactivateForwardingEnd(): Boolean {
        return try {
            withTimeout(10000) {
                // USSD-Code senden und auf Antwort warten
                PhoneSmsUtils.sendUssdCode(getApplication(), "##21#")
            }
        } catch (e: TimeoutCancellationException) {
            _errorState.value = ErrorDialogState.TimeoutError
            false
        }
    }

    // Bei Änderungen am Weiterleitungsstatus
    private fun updateForwardingStatus(active: Boolean) {
        _forwardingActive.value = active
        prefsManager.saveForwardingStatus(active)
        updateServiceNotification()
    }

    // Bei Änderungen am Email-Weiterleitungsstatus
    fun updateForwardSmsToEmail(enabled: Boolean) {
        _forwardSmsToEmail.value = enabled
        prefsManager.setForwardSmsToEmail(enabled)
        updateServiceNotification()
    }

    private fun updateServiceNotification() {
        val context = getApplication<Application>()
        val status = buildString {
            if (forwardingActive.value) {
                append("SMS-Weiterleitung aktiv")
                selectedContact.value?.let { contact ->
                    append(" zu ${contact.name}")
                }
            }
            if (forwardSmsToEmail.value) {
                if (forwardingActive.value) append("\n")
                append("Email-Weiterleitung aktiv")
                val emailCount = _emailAddresses.value.size
                append(" an $emailCount Email(s)")
            }
            if (!forwardingActive.value && !forwardSmsToEmail.value) {
                append("Keine Weiterleitung aktiv")
            }
        }
        SmsForegroundService.updateNotification(context, status)
    }

    /**
     * Sendet eine Test-SMS.
     */


    fun sendTestSms() {
        val contact = _selectedContact.value
        if (contact != null) {
            if (_ownPhoneNumber.value.isEmpty()) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.WARNING,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "TEST_SMS_FAILED",
                        details = mapOf("reason" to "missing_own_number")
                    ),
                    "Test-SMS konnte nicht gesendet werden - eigene Nummer fehlt"
                )
                _showOwnNumberMissingDialog.value = true
                return
            }

            val receiver = _ownPhoneNumber.value
            if (PhoneSmsUtils.sendTestSms(
                    application,
                    receiver,
                    prefsManager.getTestSmsText()
                )
            ) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "TEST_SMS_SENT",
                        details = mapOf(
                            "receiver" to receiver,
                            "text" to prefsManager.getTestSmsText()
                        )
                    ),
                    "Test-SMS wurde versendet"
                )
            } else {
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "TEST_SMS_FAILED",
                        details = mapOf(
                            "receiver" to receiver,
                            "text" to prefsManager.getTestSmsText()
                        )
                    ),
                    "Fehler beim Versenden der Test-SMS"
                )
            }
        }
    }

    /**
     * Lädt den gespeicherten Kontakt.
     */
    private fun loadSavedContact() {
        val savedPhoneNumber = prefsManager.getSelectedPhoneNumber()
        _selectedContact.value = _contacts.value.find { it.phoneNumber == savedPhoneNumber }
    }

    /**
     * Überprüft den Status der Weiterleitung.
     */
    private fun checkForwardingStatus() {
        _forwardingActive.value = prefsManager.isForwardingActive()
    }

    // Optional: Methode zum Neuladen der Kontakte (z.B. wenn sich das Adressbuch ändert)
    // Methode zum kompletten Neuladen der Kontakte

    fun reloadContacts() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                contactsStore.loadContacts(application.contentResolver)
                applyCurrentFilter()
                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "RELOAD_CONTACTS",
                        details = mapOf(
                            "contacts_count" to contacts.value.size,
                            "filter_active" to filterText.value.isNotEmpty()
                        )
                    ),
                    "Kontakte neu geladen"
                )
            } catch (e: Exception) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ContactsViewModel",
                        action = "RELOAD_CONTACTS_ERROR",
                        details = mapOf(
                            "error" to e.message,
                            "error_type" to e.javaClass.simpleName
                        )
                    ),
                    "Fehler beim Neuladen der Kontakte",
                    e
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Bereinigt eine Telefonnummer.
     */
    private fun cleanPhoneNumber(phoneNumber: String): String {
        return phoneNumber.filter { it.isDigit() || it == '+' }
    }

    private fun updateNotification(message: String) {
        SmsForegroundService.updateNotification(application, message)
    }
}

class ContactsStore(private val logger: Logger) {

    private var contentObserver: ContentObserver? = null
    private var contentResolver: ContentResolver? = null
    private var updateJob: Job? = null
    //private val loggingHelper = LoggingHelper(logger)


    // Koroutinen-Scope für dieses Objekt statt statisch
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Mutex für thread-safe Zugriff auf die Listen
    private val contactsMutex = Mutex()

    // MutableStateFlow für die Kontaktliste
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val allContacts = mutableListOf<Contact>()
    private val searchIndex = HashMap<String, MutableSet<Contact>>()
    private var currentCountryCode: String = "+43"

    private var isUpdating = AtomicBoolean(false)

    fun initialize(contentResolver: ContentResolver, countryCode: String) {
        this.currentCountryCode = countryCode
        this.contentResolver = contentResolver
        setupContentObserver(contentResolver)

        // Initial load
        storeScope.launch {
            loadContacts(contentResolver)
        }
    }

    @VisibleForTesting
    suspend fun setTestContacts(contacts: List<Contact>) {
        contactsMutex.withLock {
            _contacts.value = contacts
            allContacts.clear()
            allContacts.addAll(contacts)
            rebuildSearchIndex()
        }
    }


    private fun setupContentObserver(contentResolver: ContentResolver) {
        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsStore",
                    action = "UNREGISTER_OBSERVER",
                    details = emptyMap()
                ),
                "Alter ContentObserver wurde entfernt"
            )
        }

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)

                if (!isUpdating.compareAndSet(false, true)) {
                    LoggingManager.log(
                        LoggingHelper.LogLevel.INFO,
                        LoggingHelper.LogMetadata(
                            component = "ContactsStore",
                            action = "CONTACTS_CHANGED_SKIPPED",
                            details = mapOf("reason" to "update_in_progress")
                        ),
                        "Update übersprungen - bereits in Bearbeitung"
                    )
                    return
                }

                updateJob?.cancel()
                updateJob = storeScope.launch {
                    try {
                        delay(500) // Debouncing
                        val startTime = System.currentTimeMillis()

                        contentResolver.let { resolver ->
                            withContext(Dispatchers.IO) {
                                loadContacts(resolver)
                            }

                            val duration = System.currentTimeMillis() - startTime
                            LoggingManager.log(
                                LoggingHelper.LogLevel.INFO,
                                LoggingHelper.LogMetadata(
                                    component = "ContactsStore",
                                    action = "CONTACTS_RELOAD",
                                    details = mapOf(
                                        "duration_ms" to duration,
                                        "contacts_count" to allContacts.size
                                    )
                                ),
                                "Kontakte erfolgreich neu geladen"
                            )
                        }
                    } catch (e: Exception) {
                        LoggingManager.log(
                            LoggingHelper.LogLevel.ERROR,
                            LoggingHelper.LogMetadata(
                                component = "ContactsStore",
                                action = "CONTACTS_RELOAD_ERROR",
                                details = mapOf(
                                    "error" to e.message,
                                    "error_type" to e.javaClass.simpleName
                                )
                            ),
                            "Fehler beim Neuladen der Kontakte",
                            e
                        )
                    } finally {
                        isUpdating.set(false)
                    }
                }
            }
        }

        try {
            contentObserver?.let { observer ->
                contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI,
                    true,
                    observer
                )
                contentResolver.registerContentObserver(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    true,
                    observer
                )
                contentResolver.registerContentObserver(
                    ContactsContract.Groups.CONTENT_URI,
                    true,
                    observer
                )

                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsStore",
                        action = "REGISTER_OBSERVER",
                        details = mapOf("status" to "success")
                    ),
                    "ContentObserver erfolgreich registriert"
                )
            }
        } catch (e: Exception) {
            LoggingManager.log(
                LoggingHelper.LogLevel.ERROR,
                LoggingHelper.LogMetadata(
                    component = "ContactsStore",
                    action = "OBSERVER_REGISTRATION_ERROR",
                    details = mapOf(
                        "error" to e.message,
                        "error_type" to e.javaClass.simpleName
                    )
                ),
                "Fehler bei der Observer-Registrierung",
                e
            )
        }
    }

    suspend fun loadContacts(contentResolver: ContentResolver) {
        contactsMutex.withLock {
            val startTime = System.currentTimeMillis()
            try {
                val contacts = readContactsFromProvider(contentResolver)
                allContacts.clear()
                allContacts.addAll(contacts)
                rebuildSearchIndex()
                _contacts.value = allContacts.toList()

                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ContactsStore",
                        action = "LOAD_CONTACTS",
                        details = mapOf(
                            "duration_ms" to (System.currentTimeMillis() - startTime),
                            "contacts_count" to contacts.size,
                            "index_size" to searchIndex.size
                        )
                    ),
                    "Kontakte erfolgreich geladen"
                )
            } catch (e: Exception) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ContactsStore",
                        action = "LOAD_CONTACTS_ERROR",
                        details = mapOf(
                            "error" to e.message,
                            "duration_ms" to (System.currentTimeMillis() - startTime)
                        )
                    ),
                    "Fehler beim Laden der Kontakte",
                    e
                )
                throw e
            }
        }
    }

    suspend fun filterContacts(query: String): List<Contact> {
        return contactsMutex.withLock {
            val startTime = System.currentTimeMillis()
            val results = if (query.isBlank()) {
                allContacts.toList()
            } else {
                val searchTerms = query.lowercase().split(" ")
                var filteredResults = mutableSetOf<Contact>()

                // Für den ersten Suchbegriff
                searchTerms.firstOrNull()?.let { firstTerm ->
                    filteredResults = searchIndex.entries
                        .filter { (key, _) -> key.contains(firstTerm) }
                        .flatMap { it.value }
                        .toMutableSet()
                }

                // Für weitere Suchbegriffe (AND-Verknüpfung)
                searchTerms.drop(1).forEach { term ->
                    val termResults = searchIndex.entries
                        .filter { (key, _) -> key.contains(term) }
                        .flatMap { it.value }
                        .toSet()
                    filteredResults.retainAll(termResults)
                }

                filteredResults.sortedBy { it.name }
            }

            val duration = System.currentTimeMillis() - startTime
            LoggingManager.log(
                LoggingHelper.LogLevel.DEBUG,
                LoggingHelper.LogMetadata(
                    component = "ContactsStore",
                    action = "FILTER_CONTACTS",
                    details = mapOf(
                        "query" to query,
                        "duration_ms" to duration,
                        "results_count" to results.size,
                        "total_contacts" to allContacts.size
                    )
                ),
                "Kontakte gefiltert (${duration}ms)"
            )

            results
        }
    }


    fun cleanup() {
        val startTime = System.currentTimeMillis()
        try {
            updateJob?.cancel()
            contentObserver?.let { observer ->
                contentResolver?.unregisterContentObserver(observer)
            }
            storeScope.cancel()

            contentObserver = null
            contentResolver = null

            storeScope.launch {
                contactsMutex.withLock {
                    allContacts.clear()
                    searchIndex.clear()
                    _contacts.value = emptyList()
                }
            }

            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsStore",
                    action = "CLEANUP",
                    details = mapOf(
                        "duration_ms" to (System.currentTimeMillis() - startTime)
                    )
                ),
                "ContactsStore erfolgreich bereinigt"
            )
        } catch (e: Exception) {
            LoggingManager.log(
                LoggingHelper.LogLevel.ERROR,
                LoggingHelper.LogMetadata(
                    component = "ContactsStore",
                    action = "CLEANUP_ERROR",
                    details = mapOf(
                        "error" to e.message,
                        "duration_ms" to (System.currentTimeMillis() - startTime)
                    )
                ),
                "Fehler bei der Bereinigung des ContactsStore",
                e
            )
        }
    }



    fun updateCountryCode(newCode: String) {
        if (currentCountryCode != newCode) {
            LoggingManager.log(
                LoggingHelper.LogLevel.INFO,
                LoggingHelper.LogMetadata(
                    component = "ContactsStore",
                    action = "UPDATE_COUNTRY_CODE",
                    details = mapOf(
                        "old_code" to currentCountryCode,
                        "new_code" to newCode
                    )
                ),
                "Ländervorwahl wird aktualisiert"
            )

            currentCountryCode = newCode

            storeScope.launch {
                try {
                    contentResolver?.let {
                        loadContacts(it)
                        LoggingManager.log(
                            LoggingHelper.LogLevel.INFO,
                            LoggingHelper.LogMetadata(
                                component = "ContactsStore",
                                action = "COUNTRY_CODE_UPDATE_COMPLETE",
                                details = mapOf(
                                    "contacts_count" to allContacts.size
                                )
                            ),
                            "Kontakte mit neuer Ländervorwahl geladen"
                        )
                    }
                } catch (e: Exception) {
                    LoggingManager.log(
                        LoggingHelper.LogLevel.ERROR,
                        LoggingHelper.LogMetadata(
                            component = "ContactsStore",
                            action = "COUNTRY_CODE_UPDATE_ERROR",
                            details = mapOf(
                                "error" to e.message,
                                "error_type" to e.javaClass.simpleName
                            )
                        ),
                        "Fehler beim Aktualisieren der Kontakte mit neuer Ländervorwahl",
                        e
                    )
                }
            }
        }
    }


    companion object {
        private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var countryCode: String = "+43"
    }

    private fun rebuildSearchIndex() {
        searchIndex.clear()
        allContacts.forEach { contact ->
            // Indexiere nach Namen
            contact.name.lowercase().split(" ").forEach { term ->
                searchIndex.getOrPut(term) { mutableSetOf() }.add(contact)
            }
            // Indexiere nach Telefonnummer
            contact.phoneNumber.filter { it.isDigit() }.windowed(3, 1).forEach { numberPart ->
                searchIndex.getOrPut(numberPart) { mutableSetOf() }.add(contact)
            }
        }
    }

    private fun readContactsFromProvider(contentResolver: ContentResolver): List<Contact> {
        // HashMap zur Gruppierung von Kontakten mit gleicher Nummer
        val contactGroups = mutableMapOf<String, MutableList<Contact>>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
        )

        val phoneFormatter = PhoneNumberFormatter()

        val defaultRegion = when (currentCountryCode) {
            "+49" -> "DE"
            "+41" -> "CH"
            else -> "AT"
        }

        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    val phoneNumber = cursor.getString(numberIndex).orEmpty()
                    val type = cursor.getInt(typeIndex)

                    // Formatiert die Telefonnummer
                    val numberResult = phoneFormatter.formatPhoneNumber(phoneNumber, defaultRegion)

                    if (name.isNotBlank() && numberResult.formattedNumber != null) {
                        val cleanNumber = numberResult.formattedNumber.replace(" ", "")
                        // Normalisierte Nummer für Map-Key
                        val normalizedNumber = cleanNumber.filter { it.isDigit() }

                        // Erstellt die beschreibende Information
                        val description = buildString {
                            append(numberResult.formattedNumber)
                            numberResult.carrierInfo?.let { carrier ->
                                append(" | ")
                                append(carrier)
                            }
                            // Optional: Typ der Telefonnummer hinzufügen
                            append(" | ")
                            append(getPhoneTypeLabel(type))
                        }

                        val contact = Contact(
                            name = name,
                            phoneNumber = cleanNumber,
                            description = description
                        )

                        // Füge den Kontakt zur entsprechenden Gruppe hinzu
                        contactGroups.getOrPut(normalizedNumber) { mutableListOf() }.add(contact)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactsStore", "Error reading contacts", e)
        }

        // Wähle aus jeder Gruppe den "besten" Kontakt aus
        val finalContacts = mutableListOf<Contact>()
        for (contacts in contactGroups.values) {
            if (contacts.size == 1) {
                // Wenn nur ein Kontakt, füge ihn direkt hinzu
                finalContacts.add(contacts.first())
            } else {
                // Bei mehreren Kontakten mit der gleichen Nummer, wähle den besten aus
                val bestContact = contacts.maxWithOrNull(::compareContacts)
                bestContact?.let { finalContacts.add(it) }
            }
        }

        return finalContacts.sortedBy { it.name }
    }

    // Hilfsfunktion zum Vergleichen von Kontakten
    private fun compareContacts(a: Contact, b: Contact): Int {
        // Längere Namen bevorzugen (oft enthalten diese mehr Informationen)
        val lengthComparison = a.name.length.compareTo(b.name.length)
        if (lengthComparison != 0) return lengthComparison

        // Bei gleicher Länge alphabetisch sortieren
        return a.name.compareTo(b.name)
    }

    private fun getPhoneTypeLabel(type: Int): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Privat"
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobil"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Geschäftlich"
            else -> "Sonstige"
        }
    }

}

/**
 * Factory für die Erstellung des ContactsViewModel.
 */
class ContactsViewModelFactory(
    private val application: Application,
    private val prefsManager: SharedPreferencesManager,
    private val logger: Logger
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ContactsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ContactsViewModel(application, prefsManager, logger) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

data class Contact(
    val name: String,
    val phoneNumber: String,
    val description: String
) {
    // Normalisierte Telefonnummer für Vergleiche
    private val normalizedNumber = phoneNumber.filter { it.isDigit() }

    // Der Name sollte bei equals/hashCode NICHT berücksichtigt werden
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        return normalizedNumber == other.normalizedNumber
    }

    override fun hashCode(): Int {
        return normalizedNumber.hashCode()
    }
}

class SharedPreferencesManager(private val context: Context) {
    private var prefs: SharedPreferences

    init {
        prefs = initializePreferences()
    }

    fun saveTestEmailText(text: String) {
        safePreferencesOperation {
            prefs.edit().putString(KEY_TEST_EMAIL_TEXT, text).apply()
        }
    }

    fun getTestEmailText(): String {
        return try {
            prefs.getString(KEY_TEST_EMAIL_TEXT, "Das ist eine Test-Email.") ?: "Das ist eine Test-Email."
        } catch (e: Exception) {
            handlePreferencesError(e)
            prefs = initializePreferences()
            prefs.getString(KEY_TEST_EMAIL_TEXT, "Das ist eine Test-Email.") ?: "Das ist eine Test-Email."
        }
    }

    fun setForwardSmsToEmail(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FORWARD_SMS_TO_EMAIL, enabled).apply()
    }

    fun isForwardSmsToEmail(): Boolean {
        return prefs.getBoolean(KEY_FORWARD_SMS_TO_EMAIL, false)
    }

    fun getEmailAddresses(): List<String> {
        val emailsString = prefs.getString(KEY_EMAIL_ADDRESSES, "")
        return if (emailsString.isNullOrEmpty()) {
            emptyList()
        } else {
            emailsString.split(",").filter { it.isNotEmpty() }
        }
    }

    fun saveEmailAddresses(emails: List<String>) {
        val emailsString = emails.joinToString(",")
        prefs.edit().putString(KEY_EMAIL_ADDRESSES, emailsString).apply()
    }

    private fun initializePreferences(): SharedPreferences {
        return try {
            // Versuche zuerst, die verschlüsselten SharedPreferences zu initialisieren
            createEncryptedPreferences()
        } catch (e: Exception) {
            handlePreferencesError(e)
            // Fallback zu unverschlüsselten Preferences und Migration der Daten
            createUnencryptedPreferences()
        }
    }

    private fun createEncryptedPreferences(): SharedPreferences {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            throw PreferencesInitializationException("Failed to create encrypted preferences", e)
        }
    }

    private fun createUnencryptedPreferences(): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)
    }

    private fun handlePreferencesError(error: Exception) {
        LoggingManager.log(
            LoggingHelper.LogLevel.ERROR,
            LoggingHelper.LogMetadata(
                component = "SharedPreferencesManager",
                action = "INIT_ERROR",
                details = mapOf(
                    "error_type" to error.javaClass.simpleName,
                    "error_message" to (error.message ?: "Unknown error")
                )
            ),
            "SharedPreferences Initialisierungsfehler"
        )

        // Lösche die möglicherweise beschädigten Preferences
        try {
            val prefsFile = File(context.applicationInfo.dataDir + "/shared_prefs/" + PREFS_NAME + ".xml")
            if (prefsFile.exists()) {
                prefsFile.delete()
                LoggingManager.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "SharedPreferencesManager",
                        action = "DELETE_CORRUPTED",
                        details = emptyMap()
                    ),
                    "Beschädigte Preferences gelöscht"
                )
            }
        } catch (e: Exception) {
            LoggingManager.log(
                LoggingHelper.LogLevel.ERROR,
                LoggingHelper.LogMetadata(
                    component = "SharedPreferencesManager",
                    action = "DELETE_ERROR",
                    details = mapOf("error" to e.message)
                ),
                "Fehler beim Löschen der beschädigten Preferences"
            )
        }
    }

    private fun safePreferencesOperation(operation: () -> Unit) {
        try {
            operation()
        } catch (e: Exception) {
            handlePreferencesError(e)
            // Versuche die Operation erneut mit neu initialisierten Preferences
            prefs = initializePreferences()
            try {
                operation()
            } catch (e: Exception) {
                LoggingManager.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "SharedPreferencesManager",
                        action = "RETRY_FAILED",
                        details = mapOf("error" to e.message)
                    ),
                    "Preferences Operation fehlgeschlagen auch nach Neuinitialisierung"
                )
            }
        }
    }

    // Getter und Setter mit Fehlerbehandlung
    fun setKeepForwardingOnExit(keep: Boolean) {
        safePreferencesOperation {
            prefs.edit().putBoolean(KEY_KEEP_FORWARDING_ON_EXIT, keep).apply()
        }
    }

    fun getKeepForwardingOnExit(): Boolean {
        return try {
            prefs.getBoolean(KEY_KEEP_FORWARDING_ON_EXIT, false)
        } catch (e: Exception) {
            handlePreferencesError(e)
            prefs = initializePreferences()
            prefs.getBoolean(KEY_KEEP_FORWARDING_ON_EXIT, false)
        }
    }


    fun saveCountryCode(code: String) {
        if (isValidCountryCode(code)) {
            safePreferencesOperation {
                prefs.edit().putString(KEY_COUNTRY_CODE, code).apply()
            }
        }
    }

    fun saveOwnPhoneNumber(number: String) {
        safePreferencesOperation {
            prefs.edit().putString(KEY_OWN_PHONE_NUMBER, number).apply()
        }
    }

    fun getOwnPhoneNumber(): String {
        return try {
            prefs.getString(KEY_OWN_PHONE_NUMBER, "") ?: ""
        } catch (e: Exception) {
            handlePreferencesError(e)
            prefs = initializePreferences()
            prefs.getString(KEY_OWN_PHONE_NUMBER, "") ?: ""
        }
    }

    fun saveTopBarTitle(title: String) {
        safePreferencesOperation {
            prefs.edit().putString(KEY_TOP_BAR_TITLE, title).apply()
        }
    }

    fun getTopBarTitle(): String {
        return try {
            prefs.getString(KEY_TOP_BAR_TITLE, DEFAULT_TOP_BAR_TITLE) ?: DEFAULT_TOP_BAR_TITLE
        } catch (e: Exception) {
            handlePreferencesError(e)
            prefs = initializePreferences()
            prefs.getString(KEY_TOP_BAR_TITLE, DEFAULT_TOP_BAR_TITLE) ?: DEFAULT_TOP_BAR_TITLE
        }
    }

    fun saveSelectedPhoneNumber(phoneNumber: String?) {
        safePreferencesOperation {
            prefs.edit().apply {
                putString(KEY_SELECTED_PHONE, phoneNumber)
                putBoolean(KEY_FORWARDING_ACTIVE, phoneNumber != null)
                apply()
            }
        }
    }

    fun getSelectedPhoneNumber(): String? {
        return try {
            prefs.getString(KEY_SELECTED_PHONE, null)
        } catch (e: Exception) {
            handlePreferencesError(e)
            prefs = initializePreferences()
            prefs.getString(KEY_SELECTED_PHONE, null)
        }
    }

    fun isForwardingActive(): Boolean {
        return try {
            prefs.getBoolean(KEY_FORWARDING_ACTIVE, false)
        } catch (e: Exception) {
            handlePreferencesError(e)
            prefs = initializePreferences()
            prefs.getBoolean(KEY_FORWARDING_ACTIVE, false)
        }
    }

    fun clearSelection() {
        safePreferencesOperation {
            prefs.edit().apply {
                remove(KEY_SELECTED_PHONE)
                putBoolean(KEY_FORWARDING_ACTIVE, false)
                apply()
            }
        }
    }

    fun saveFilterText(filterText: String) {
        safePreferencesOperation {
            prefs.edit().putString(KEY_FILTER_TEXT, filterText).apply()
        }
    }

    fun getFilterText(): String {
        return try {
            prefs.getString(KEY_FILTER_TEXT, "") ?: ""
        } catch (e: Exception) {
            handlePreferencesError(e)
            prefs = initializePreferences()
            prefs.getString(KEY_FILTER_TEXT, "") ?: ""
        }
    }

    fun saveForwardingStatus(isActive: Boolean) {
        safePreferencesOperation {
            prefs.edit().putBoolean(KEY_FORWARDING_ACTIVE, isActive).apply()
        }
    }

    fun saveTestSmsText(text: String) {
        safePreferencesOperation {
            prefs.edit().putString(KEY_TEST_SMS_TEXT, text).apply()
        }
    }

    fun getTestSmsText(): String {
        return try {
            prefs.getString(KEY_TEST_SMS_TEXT, "Das ist eine Test-SMS.") ?: "Das ist eine Test-SMS."
        } catch (e: Exception) {
            handlePreferencesError(e)
            prefs = initializePreferences()
            prefs.getString(KEY_TEST_SMS_TEXT, "Das ist eine Test-SMS.") ?: "Das ist eine Test-SMS."
        }
    }

    private fun isValidCountryCode(code: String): Boolean {
        return when (code) {
            "+43", // Österreich
            "+49", // Deutschland
            "+41"  // Schweiz
                -> true
            else -> false
        }
    }

    fun saveSmtpSettings(host: String, port: Int, username: String, password: String) {
        safePreferencesOperation {
            prefs.edit().apply {
                putString(KEY_SMTP_HOST, host)
                putInt(KEY_SMTP_PORT, port)
                putString(KEY_SMTP_USERNAME, username)
                putString(KEY_SMTP_PASSWORD, password)
                apply()
            }
        }
    }

    fun getSmtpHost(): String = prefs.getString(KEY_SMTP_HOST, DEFAULT_SMTP_HOST) ?: DEFAULT_SMTP_HOST
    fun getSmtpPort(): Int = prefs.getInt(KEY_SMTP_PORT, DEFAULT_SMTP_PORT)
    fun getSmtpUsername(): String = prefs.getString(KEY_SMTP_USERNAME, "") ?: ""
    fun getSmtpPassword(): String = prefs.getString(KEY_SMTP_PASSWORD, "") ?: ""


    companion object {
        private const val KEY_TEST_EMAIL_TEXT = "test_email_text"
        private const val KEY_FORWARD_SMS_TO_EMAIL = "forward_sms_to_email"
        private const val KEY_EMAIL_ADDRESSES = "email_addresses"
        private const val PREFS_NAME = "SMSForwarderEncryptedPrefs"
        private const val PREFS_NAME_FALLBACK = "SMSForwarderPrefs"
        private const val KEY_SELECTED_PHONE = "selected_phone_number"
        private const val KEY_FORWARDING_ACTIVE = "forwarding_active"
        private const val KEY_FILTER_TEXT = "filter_text"
        private const val KEY_TEST_SMS_TEXT = "test_sms_text"
        private const val KEY_OWN_PHONE_NUMBER = "own_phone_number"
        private const val KEY_TOP_BAR_TITLE = "top_bar_title"
        private const val DEFAULT_TOP_BAR_TITLE = "TEL/SMS-Weiterleitung"
        private const val KEY_COUNTRY_CODE = "country_code"
        private const val DEFAULT_COUNTRY_CODE = "+43" // Österreich als Default
        private const val KEY_KEEP_FORWARDING_ON_EXIT = "keep_forwarding_on_exit"
        private const val KEY_SMTP_HOST = "smtp_host"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_SMTP_USERNAME = "smtp_username"
        private const val KEY_SMTP_PASSWORD = "smtp_password"

        // Default-Werte
        private const val DEFAULT_SMTP_HOST = "smtp.gmail.com"
        private const val DEFAULT_SMTP_PORT = 587
    }
}

class PreferencesInitializationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class PermissionHandler(private val activity: Activity) {

    // Erforderliche Berechtigungen für die App
    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_NUMBERS,
            android.Manifest.permission.POST_NOTIFICATIONS  // Neue Berechtigung für Benachrichtigungen
        )
    } else {
        arrayOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_NUMBERS
        )
    }
    //private lateinit var permissionHandler: PermissionHandler

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var isBatteryOptimizationRequested = false
    private var onPermissionsResult: ((Boolean) -> Unit)? = null

    fun initialize(launcher: ActivityResultLauncher<Array<String>>) {
        requestPermissionLauncher = launcher
    }

    fun checkAndRequestPermissions(onResult: (Boolean) -> Unit) {
        onPermissionsResult = onResult

        // Zuerst prüfen wir die normalen Berechtigungen
        if (allPermissionsGranted()) {
            requestBatteryOptimization()
            onResult(true)
        } else {
            LoggingManager.logInfo(
                component = "PermissionHandler",
                action = "REQUEST_PERMISSIONS",
                message = "Fordere fehlende Berechtigungen an",
                details = mapOf(
                    "missing_permissions" to getMissingPermissions().joinToString()
                )
            )
            requestPermissions()
        }
    }

    private fun getMissingPermissions(): List<String> {
        return requiredPermissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(requiredPermissions)
    }

    fun onRequestPermissionsResult(granted: Boolean) {
        if (granted) {
            requestBatteryOptimization()
            LoggingManager.logInfo(
                component = "PermissionHandler",
                action = "PERMISSIONS_GRANTED",
                message = "Alle Berechtigungen wurden erteilt"
            )
        } else {
            LoggingManager.logWarning(
                component = "PermissionHandler",
                action = "PERMISSIONS_DENIED",
                message = "Nicht alle Berechtigungen wurden erteilt",
                details = mapOf(
                    "missing_permissions" to getMissingPermissions().joinToString()
                )
            )
            showPermissionRequiredDialog()
        }
        onPermissionsResult?.invoke(granted)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        if (!isBatteryOptimizationRequested) {
            val packageName = activity.packageName
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    isBatteryOptimizationRequested = true
                    activity.startActivity(intent)

                    LoggingManager.logInfo(
                        component = "PermissionHandler",
                        action = "BATTERY_OPTIMIZATION",
                        message = "Batterie-Optimierung-Dialog angezeigt"
                    )
                } catch (e: Exception) {
                    LoggingManager.logError(
                        component = "PermissionHandler",
                        action = "BATTERY_OPTIMIZATION_ERROR",
                        message = "Fehler beim Anzeigen des Batterie-Optimierung-Dialogs",
                        error = e
                    )
                }
            }
        }
    }




    private fun showPermissionRequiredDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Berechtigungen erforderlich")
            .setMessage(
                "Folgende Berechtigungen sind für die App erforderlich:\n\n" +
                        "• Kontakte - Für die Auswahl des Weiterleitungsziels\n" +
                        "• SMS - Zum Empfangen und Weiterleiten von Nachrichten\n" +
                        "• Telefon - Für die Anrufweiterleitung\n" +
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            "• Benachrichtigungen - Für Status-Updates\n"
                        else "") +
                        "\nOhne diese Berechtigungen kann die App nicht funktionieren."
            )
            .setCancelable(false)
            .setPositiveButton("Zu den Einstellungen") { dialog, _ ->
                dialog.dismiss()
                openAppSettings()
            }
            .setNegativeButton("Beenden") { dialog, _ ->
                dialog.dismiss()
                activity.finish()
            }
            .create()
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivity(intent)
    }
}

class Logger(private val context: Context, private val maxEntries: Int = 1000) {
    companion object {
        private const val TAG = "SmsLogger"  // Maximal 23 Zeichen für Android-Logging
    }
    private val logFile: File = File(context.getExternalFilesDir(null), "app_log.xml")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    private val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
    }

    private val logBuffer = mutableListOf<LogEntry>()
    private var lastSaveTime = System.currentTimeMillis()
    private val saveInterval = 60000 // 1 minute in milliseconds

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastError: LoggerException? = null

    init {
        try {
            if (!logFile.exists() || !loadExistingLogs()) {
                createNewLogFile()
            }
        } catch (e: Exception) {
            handleException(e, "Initialization failed")
            createNewLogFile()
        }
    }

    private fun loadExistingLogs(): Boolean {
        return try {
            val doc = documentBuilder.parse(logFile)
            val entries = doc.documentElement.getElementsByTagName("logEntry")
            logBuffer.clear() // Clear existing buffer before loading
            for (i in 0 until entries.length) {
                val entry = entries.item(i) as Element
                val time = entry.getElementsByTagName("time").item(0).textContent
                val text = entry.getElementsByTagName("text").item(0).textContent
                logBuffer.add(LogEntry(time, text))
            }
            true // Successfully loaded
        } catch (e: Exception) {
            handleException(e, "Failed to load existing logs, creating new file")
            false // Failed to load
        }
    }

    private fun createNewLogFile() {
        try {
            logBuffer.clear()
            val doc = documentBuilder.newDocument()
            doc.appendChild(doc.createElement("logEntries"))
            saveDocumentToFile(doc)

            // Erstelle einen aussagekräftigen Initialisierungs-Log-Eintrag
            //val timestamp = dateFormat.format(Date())
            val initMessage = buildString {
                append("Neue Log-Datei erstellt: ")
                append(logFile.absolutePath)
                append(" (Grund: ")
                append(
                    if (logFile.exists()) "Fehler beim Laden der existierenden Datei"
                    else "Datei existierte nicht"
                )
                append(")")
            }

            addLogEntry(initMessage)

        } catch (e: Exception) {
            val errorMsg = buildString {
                append("Kritischer Fehler beim Erstellen der Log-Datei: ")
                append(e.message)
                append(" (")
                append(e.javaClass.simpleName)
                append(")")
                if (logFile.exists()) {
                    append(" - Datei existiert bereits: ")
                    append(logFile.absolutePath)
                }
            }

            Log.e(TAG, errorMsg, e)
            handleException(e, "Failed to create new log file")
        }
    }

    fun addLogEntry(entry: String) {
        try {
            val timestamp = getCurrentTimestamp()
            logBuffer.add(LogEntry(timestamp, entry))
            if (logBuffer.size > maxEntries) {
                logBuffer.removeAt(0)
            }

            if (System.currentTimeMillis() - lastSaveTime > saveInterval) {
                coroutineScope.launch { saveLogsToFile() }
            }
        } catch (e: Exception) {
            handleException(e, "Failed to add log entry")
        }
    }

    fun clearLog() {
        try {
            logBuffer.clear()
            createNewLogFile()
        } catch (e: Exception) {
            handleException(e, "Failed to clear log")
        }
    }

    fun getLogEntries(): String = try {
        buildString {
            logBuffer.forEach { (time, text) ->
                append("$time - $text\n")
            }
        }
    } catch (e: Exception) {
        handleException(e, "Failed to get log entries")
        "Error: Unable to retrieve log entries"
    }

    fun getLogEntriesHtml(): String = buildString {
        append("""
    <!DOCTYPE html>
    <html lang="de">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <title>Log-Einträge</title>
        <style>
            body {
                font-family: Arial, sans-serif;
                font-size: 16px;
                line-height: 1.6;
                margin: 0;
                padding: 0;
                background-color: #f0f0f0;
            }
            .container {
                width: 100%;
                background-color: white;
                box-shadow: 0 0 10px rgba(0,0,0,0.1);
            }
            table {
                width: 100%;
                table-layout: fixed;
                border-collapse: collapse;
                margin-bottom: 20px;
            }
            th, td {
                padding: 12px;
                text-align: left;
                border-bottom: 1px solid #ddd;
                font-size: 12px;
                overflow-wrap: break-word;
                word-wrap: break-word;
            }
            th {
                background-color: #4CAF50;
                color: white;
                font-weight: bold;
                position: sticky;
                top: 0;
            }
            tr:nth-child(even) {
                background-color: #f2f2f2;
            }
            tr:hover {
                background-color: #ddd;
            }
            .time-column {
                white-space: nowrap;
                width: 100px;
            }
            .entry-column {
                width: calc(100% - 100px);
            }
            .time-cell {
                display: flex;
                flex-direction: column;
                gap: 2px;
            }
            .date {
                font-weight: bold;
            }
            .time {
                color: #666;
            }
            .sms-forward {
                color: #8B0000;
                font-weight: 500;
            }
            @media screen and (max-width: 600px) {
                th, td {
                    padding: 8px;
                    font-size: 10px;
                }
                .time-column {
                    width: 80px;
                }
                .entry-column {
                    width: calc(100% - 80px);
                }
            }
        </style>
    </head>
    <body>
        <div class="container">
            <table>
                <thead>
                    <tr>
                        <th class="time-column">Zeitpunkt</th>
                        <th class="entry-column">Eintrag</th>
                    </tr>
                </thead>
                <tbody>
    """.trimIndent())

        logBuffer.asReversed().forEach { (timeStamp, text) ->
            val parts = timeStamp.split(" ")
            val isSmsForward = text.contains("Weiterleitung") || text.contains("] SEND_SMS")

            val entryClass = if (isSmsForward) "entry-column sms-forward" else "entry-column"

            if (parts.size == 2) {
                val (date, time) = parts
                append("""
                <tr>
                    <td class="time-column">
                        <div class="time-cell">
                            <span class="date">$date</span>
                            <span class="time">$time</span>
                        </div>
                    </td>
                    <td class="$entryClass">$text</td>
                </tr>
            """)
            } else {
                append("""
                <tr>
                    <td class="time-column">$timeStamp</td>
                    <td class="$entryClass">$text</td>
                </tr>
            """)
            }
        }

        append("""
                </tbody>
            </table>
        </div>
    </body>
    </html>
    """.trimIndent())
    }

    fun saveLogsToFile() {
        try {
            val doc = documentBuilder.newDocument()
            val rootElement = doc.createElement("logEntries")
            doc.appendChild(rootElement)

            logBuffer.forEach { (time, text) ->
                val logEntryElement = doc.createElement("logEntry")
                logEntryElement.appendChild(createElementWithText(doc, "time", time))
                logEntryElement.appendChild(createElementWithText(doc, "text", text))
                rootElement.appendChild(logEntryElement)
            }

            saveDocumentToFile(doc)
            lastSaveTime = System.currentTimeMillis()
        } catch (e: Exception) {
            handleException(e, "Failed to save logs to file")
        }
    }

    private fun saveDocumentToFile(doc: Document) {
        try {
            FileOutputStream(logFile).use { fos ->
                transformer.transform(DOMSource(doc), StreamResult(fos))
            }
        } catch (e: Exception) {
            handleException(e, "Failed to save document to file")
        }
    }

    private fun createElementWithText(doc: Document, tagName: String, textContent: String): Element {
        return doc.createElement(tagName).apply {
            appendChild(doc.createTextNode(textContent))
        }
    }

    private fun getCurrentTimestamp(): String = dateFormat.format(Date())

    private data class LogEntry(val time: String, val text: String)

    fun onDestroy() {
        coroutineScope.launch {
            try {
                saveLogsToFile()
            } catch (e: Exception) {
                handleException(e, "Failed to save logs during onDestroy")
            } finally {
                coroutineScope.cancel()
            }
        }
    }

    private fun handleException(e: Exception, message: String) {
        val loggerException = LoggerException(message, e)
        lastError = loggerException
        Log.e("Logger", message, e)
        Log.w("Logger", "Logger Error: $message - ${e.message}")
    }

    fun getLastError(): LoggerException? = lastError

    class LoggerException(message: String, cause: Throwable?) : Exception(message, cause)
}

class LoggingHelper(private val logger: Logger) {
    companion object {
        private const val TAG = "AppLogger"
    }

    enum class LogLevel {
        INFO, WARNING, ERROR, DEBUG
    }

    data class LogMetadata(
        val component: String,
        val action: String,
        val details: Map<String, Any?> = emptyMap()
    )

    fun log(
        level: LogLevel,
        metadata: LogMetadata,
        message: String,
        exception: Throwable? = null
    ) {
        val formattedMessage = buildLogMessage(metadata, message)

        // Log ins System-Log
        when (level) {
            LogLevel.INFO -> Log.i(TAG, formattedMessage)
            LogLevel.WARNING -> Log.w(TAG, formattedMessage)
            LogLevel.ERROR -> Log.e(TAG, formattedMessage, exception)
            LogLevel.DEBUG -> Log.d(TAG, formattedMessage)
        }

        // Log in die App
        val prefix = when (level) {
            LogLevel.INFO -> "ℹ️"
            LogLevel.WARNING -> "⚠️"
            LogLevel.ERROR -> "❌"
            LogLevel.DEBUG -> "🔍"
        }

        val logEntry = buildString {
            append(prefix)
            append(" ")
            append(formattedMessage)
            if (exception != null) {
                append(" | Exception: ${exception.message}")
            }
        }

        logger.addLogEntry(logEntry)
    }

    private fun buildLogMessage(metadata: LogMetadata, message: String): String {
        return buildString {
            append("[${metadata.component}]")
            append(" ${metadata.action}")
            if (metadata.details.isNotEmpty()) {
                append(" | ")
                append(metadata.details.entries.joinToString(", ") { "${it.key}=${it.value}" })
            }
            append(" | ")
            append(message)
        }
    }

    // Hilfsmethoden für häufige Logging-Szenarien
    fun logPhoneNumberUpdate(component: String, number: String, source: String) {
        val metadata = LogMetadata(
            component = component,
            action = "PHONE_NUMBER_UPDATE",
            details = mapOf(
                "number" to maskPhoneNumber(number),
                "source" to source
            )
        )
        log(LogLevel.INFO, metadata, "Telefonnummer aktualisiert")
    }

    fun logForwardingStatus(component: String, active: Boolean, target: String?) {
        val metadata = LogMetadata(
            component = component,
            action = "FORWARDING_STATUS",
            details = mapOf(
                "active" to active,
                "target" to (target?.let { maskPhoneNumber(it) } ?: "none")
            )
        )
        log(LogLevel.INFO, metadata, "Weiterleitungsstatus geändert")
    }

    fun logPermissionStatus(component: String, permission: String, granted: Boolean) {
        val metadata = LogMetadata(
            component = component,
            action = "PERMISSION_STATUS",
            details = mapOf(
                "permission" to permission,
                "granted" to granted
            )
        )
        log(LogLevel.INFO, metadata, "Berechtigungsstatus geändert")
    }

    fun logSmsEvent(component: String, type: String, status: String, target: String?) {
        val metadata = LogMetadata(
            component = component,
            action = "SMS_EVENT",
            details = mapOf(
                "type" to type,
                "status" to status,
                "target" to (target?.let { maskPhoneNumber(it) } ?: "none")
            )
        )
        log(LogLevel.INFO, metadata, "SMS-Event verarbeitet")
    }

    fun logError(component: String, action: String, error: Throwable) {
        val metadata = LogMetadata(
            component = component,
            action = action
        )
        log(LogLevel.ERROR, metadata, "Fehler aufgetreten: ${error.message}", error)
    }

    // Hilfsfunktion zum Maskieren von Telefonnummern im Log
    private fun maskPhoneNumber(number: String): String {
        if (number.length <= 4) return "****"
        return "${number.take(4)}${"*".repeat(number.length - 4)}"
    }
}