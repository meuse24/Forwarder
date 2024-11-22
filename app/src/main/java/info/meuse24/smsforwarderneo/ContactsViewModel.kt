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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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


    private val contactsStore = ContactsStore()

    // StateFlows für verschiedene UI-Zustände
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedContact = MutableStateFlow<Contact?>(null)
    val selectedContact: StateFlow<Contact?> = _selectedContact

    private val _forwardingActive = MutableStateFlow(false)
    val forwardingActive: StateFlow<Boolean> = _forwardingActive.asStateFlow()

    private val _forwardingPhoneNumber = MutableStateFlow("")
    val forwardingPhoneNumber: StateFlow<String> = _forwardingPhoneNumber


    private val _filterText = MutableStateFlow("")
    val filterText: StateFlow<String> = _filterText

    private val _logEntriesHtml = MutableStateFlow("")
    val logEntriesHtml: StateFlow<String> = _logEntriesHtml

    private val _logEntries = MutableStateFlow("")
    val logEntries: StateFlow<String> = _logEntries

    private val _testSmsText = MutableStateFlow("")
    val testSmsText: StateFlow<String> = _testSmsText

    private val _ownPhoneNumber = MutableStateFlow("")
    val ownPhoneNumber: StateFlow<String> = _ownPhoneNumber

    private val _topBarTitle = MutableStateFlow("")
    val topBarTitle: StateFlow<String> = _topBarTitle

    private val _navigationTarget = MutableStateFlow<String?>(null)
    val navigationTarget: StateFlow<String?> = _navigationTarget.asStateFlow()

    private val _countryCode = MutableStateFlow("")
    val countryCode: StateFlow<String> = _countryCode.asStateFlow()

    private val _countryCodeSource = MutableStateFlow("")
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

    private val _keepForwardingOnExit = MutableStateFlow(false)
    val keepForwardingOnExit: StateFlow<Boolean> = _keepForwardingOnExit.asStateFlow()

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
    private val _testEmailText = MutableStateFlow("")
    val testEmailText: StateFlow<String> = _testEmailText.asStateFlow()





    /**
     * Status der Weiterleitung
     */
    sealed class ForwardingResult {
        object Success : ForwardingResult()
        data class Error(val message: String, val technical: String? = null) : ForwardingResult()
    }

    enum class ForwardingAction {
        ACTIVATE, DEACTIVATE, TOGGLE
    }

    /**
     * Zentrale Funktion zum Verwalten des Weiterleitungsstatus
     * @param action Die gewünschte Aktion (ACTIVATE, DEACTIVATE, TOGGLE)
     * @param contact Optional: Der Kontakt für die Aktivierung
     * @param onResult Callback für das Ergebnis der Operation
     */
    private fun manageForwardingStatus(
        action: ForwardingAction,
        contact: Contact? = null,
        onResult: (ForwardingResult) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val result = when (action) {
                    ForwardingAction.ACTIVATE -> {
                        if (contact == null) {
                            ForwardingResult.Error("Kein Kontakt für Aktivierung ausgewählt")
                        } else {
                            // Prüfe auf Eigenweiterleitung
                            val standardizedContactNumber = PhoneSmsUtils.standardizePhoneNumber(
                                contact.phoneNumber,
                                _countryCode.value
                            )
                            val standardizedOwnNumber = PhoneSmsUtils.standardizePhoneNumber(
                                _ownPhoneNumber.value,
                                _countryCode.value
                            )

                            if (standardizedContactNumber == standardizedOwnNumber) {
                                ForwardingResult.Error("Weiterleitung an eigene Nummer nicht möglich")
                            } else {
                                withContext(Dispatchers.IO) {
                                    activateForwardingInternal(contact)
                                }
                            }
                        }
                    }

                    ForwardingAction.DEACTIVATE -> {
                        withContext(Dispatchers.IO) {
                            deactivateForwardingInternal()
                        }
                    }

                    ForwardingAction.TOGGLE -> {
                        if (_forwardingActive.value) {
                            withContext(Dispatchers.IO) {
                                deactivateForwardingInternal()
                            }
                        } else if (contact != null) {
                            withContext(Dispatchers.IO) {
                                activateForwardingInternal(contact)
                            }
                        } else {
                            ForwardingResult.Error("Kein Kontakt für Toggle-Aktivierung ausgewählt")
                        }
                    }
                }

                onResult(result)

            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "ContactsViewModel",
                    action = "FORWARDING_ERROR",
                    message = "Fehler beim ${action.name.lowercase()} der Weiterleitung",
                    error = e,
                    details = mapOf(
                        "action" to action.name,
                        "contact" to (contact?.name ?: "none"),
                        "error_type" to e.javaClass.simpleName
                    )
                )
                onResult(
                    ForwardingResult.Error(
                        "Fehler bei der Weiterleitung: ${e.message}",
                        e.stackTraceToString()
                    )
                )
            }
        }
    }

    /**
     * Interne Hilfsfunktion für die Aktivierung der Weiterleitung
     */
    private suspend fun activateForwardingInternal(contact: Contact): ForwardingResult {
        // Aktiviere Weiterleitung via USSD
        if (!PhoneSmsUtils.sendUssdCode(getApplication(), "*21*${contact.phoneNumber}#")) {
            return ForwardingResult.Error("USSD-Code konnte nicht gesendet werden")
        }

        // Setze Status und speichere Kontakt
        withContext(Dispatchers.Main) {
            _selectedContact.value = contact
            _forwardingPhoneNumber.value = contact.phoneNumber
            _forwardingActive.value = true
        }

        prefsManager.saveSelectedPhoneNumber(contact.phoneNumber)
        prefsManager.saveForwardingStatus(true)

        // Aktualisiere Service-Notification
        updateNotification("Weiterleitung aktiv zu ${contact.name} (${contact.phoneNumber})")

        LoggingManager.logInfo(
            component = "ContactsViewModel",
            action = "ACTIVATE_FORWARDING",
            message = "Weiterleitung aktiviert",
            details = mapOf(
                "contact" to contact.name,
                "number" to contact.phoneNumber
            )
        )

        withContext(Dispatchers.Main) {
            SnackbarManager.showSuccess("Weiterleitung zu ${contact.name} aktiviert")
        }

        return ForwardingResult.Success
    }

    /**
     * Interne Hilfsfunktion für die Deaktivierung der Weiterleitung
     */
    private suspend fun deactivateForwardingInternal(): ForwardingResult {
        val prevContact = _selectedContact.value

        // Deaktiviere Weiterleitung via USSD
        if (!PhoneSmsUtils.sendUssdCode(getApplication(), "##21#")) {
            return ForwardingResult.Error("USSD-Code konnte nicht gesendet werden")
        }

        // Setze Status zurück
        withContext(Dispatchers.Main) {
            _selectedContact.value = null
            _forwardingActive.value = false
            _forwardingPhoneNumber.value = ""
        }

        prefsManager.clearSelection()

        // Aktualisiere Service-Notification
        updateNotification("Keine Weiterleitung aktiv")

        LoggingManager.logInfo(
            component = "ContactsViewModel",
            action = "DEACTIVATE_FORWARDING",
            message = "Weiterleitung deaktiviert",
            details = mapOf(
                "previous_contact" to (prevContact?.name ?: "none"),
                "previous_number" to (prevContact?.phoneNumber ?: "none")
            )
        )

        withContext(Dispatchers.Main) {
            SnackbarManager.showSuccess("Weiterleitung deaktiviert")
        }

        return ForwardingResult.Success
    }

    // Öffentliche Funktionen für den Zugriff von außen
    fun activateForwarding(contact: Contact, onResult: (ForwardingResult) -> Unit = {}) {
        manageForwardingStatus(ForwardingAction.ACTIVATE, contact, onResult)
    }

    fun deactivateForwarding(onResult: (ForwardingResult) -> Unit = {}) {
        manageForwardingStatus(ForwardingAction.DEACTIVATE, onResult = onResult)
    }

    fun toggleForwarding(contact: Contact? = null, onResult: (ForwardingResult) -> Unit = {}) {
        manageForwardingStatus(ForwardingAction.TOGGLE, contact, onResult)
    }


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
        viewModelScope.launch {
            try {
                _isCleaningUp.value = true
                _showProgressDialog.value = true

                if (!keepForwarding) {
                    deactivateForwarding { result ->
                        when (result) {
                            is ForwardingResult.Error -> {
                                _errorState.value =
                                    ErrorDialogState.DeactivationError(result.message)
                            }

                            ForwardingResult.Success -> {
                                prefsManager.setKeepForwardingOnExit(false)
                            }
                        }
                    }
                } else {
                    _selectedContact.value?.let { contact ->
                        activateForwarding(contact) { result ->
                            when (result) {
                                is ForwardingResult.Error -> {
                                    _errorState.value =
                                        ErrorDialogState.DeactivationError(result.message)
                                }

                                ForwardingResult.Success -> {
                                    prefsManager.setKeepForwardingOnExit(true)
                                }
                            }
                        }
                    }
                }

                _cleanupCompleted.emit(Unit)
            } catch (e: Exception) {
                _errorState.value = ErrorDialogState.GeneralError(e)
            } finally {
                _isCleaningUp.value = false
                _showProgressDialog.value = false
            }
        }
    }

    // Diese Methode wird beim normalen Beenden aufgerufen

    fun deactivateForwarding() {
        if (!_keepForwardingOnExit.value) {
            deactivateForwarding { result ->
                when (result) {
                    is ForwardingResult.Error -> {
                        LoggingManager.logError(
                            component = "ContactsViewModel",
                            action = "DEACTIVATE_FORWARDING",
                            message = "Fehler beim Deaktivieren der Weiterleitung",
                            details = mapOf("error" to result.message)
                        )
                    }

                    ForwardingResult.Success -> { /* bereits behandelt */
                    }
                }
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
        data object TimeoutError : ErrorDialogState()
        data class GeneralError(val error: Exception) : ErrorDialogState()
    }

    // Dialog Control Functions
    fun showExitDialog() {
        _showExitDialog.value = true
    }

    fun hideExitDialog() {
        _showExitDialog.value = false
    }

    fun clearErrorState() {
        _errorState.value = null
    }


    init {
        viewModelScope.launch {
            // Initialisierung aus init-Block
            initializeCountryCode()
            initializeContactsStore()
            _forwardingActive.value = prefsManager.isForwardingActive()
            _forwardingPhoneNumber.value = prefsManager.getSelectedPhoneNumber()
            _testEmailText.value = prefsManager.getTestEmailText()
            _testSmsText.value = prefsManager.getTestSmsText()
            _topBarTitle.value = prefsManager.getTopBarTitle()
            _filterText.value = prefsManager.getFilterText()
            _emailAddresses.value = prefsManager.getEmailAddresses()
            _ownPhoneNumber.value = prefsManager.getOwnPhoneNumber()
            updateForwardingStatus(prefsManager.isForwardingActive())

            contactsStore.contacts.first { it.isNotEmpty() }

            val (savedPhoneNumber, isActive) = prefsManager.getSelectedContact()
            if (savedPhoneNumber != null && isActive) {
                // Finde den zugehörigen Kontakt
                val contact = contacts.value.find { it.phoneNumber == savedPhoneNumber }
                if (contact != null) {
                    _selectedContact.value = contact
                    _forwardingActive.value = true
                    // Aktiviere die Weiterleitung auch auf Telefonebene
                    if (PhoneSmsUtils.sendUssdCode(application, "*21*${contact.phoneNumber}#")) {
                        updateNotification("Weiterleitung aktiv zu ${contact.name} (${contact.phoneNumber})")
                    }
                    LoggingManager.logInfo(
                        component = "ContactsViewModel",
                        action = "RESTORE_STATE",
                        message = "Weiterleitungszustand wiederhergestellt",
                        details = mapOf(
                            "contact" to contact.name,
                            "number" to savedPhoneNumber
                        )
                    )
                } else {
                    // Falls der Kontakt nicht mehr existiert, setze alles zurück
                    prefsManager.clearSelection()
                    _selectedContact.value = null
                    _forwardingActive.value = false
                    LoggingManager.logWarning(
                        component = "ContactsViewModel",
                        action = "RESTORE_STATE",
                        message = "Gespeicherter Kontakt nicht mehr verfügbar",
                        details = mapOf("number" to savedPhoneNumber)
                    )
                }
            }
            //checkForwardingStatus()

            updateKeepForwardingOnExit(prefsManager.getKeepForwardingOnExit())

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


            // Zentraler Filter-Flow
            _filterText
                .debounce(300)
                .collect { _ ->
                    applyCurrentFilter()
                }
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


    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
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

    private fun loadOwnPhoneNumber(context: Context) {
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
     * Speichert den aktuellen Zustand der App.
     */
    fun saveCurrentState() {
        viewModelScope.launch {
            val currentContact = _selectedContact.value
            val isActive = _forwardingActive.value

            // Speichere Status und Nummer zusammen
            if (currentContact != null && isActive) {
                LoggingManager.logInfo(
                    component = "ContactsViewModel",
                    action = "SAVE_STATE",
                    message = "Speichere aktiven Weiterleitungskontakt",
                    details = mapOf(
                        "contact" to currentContact.name,
                        "number" to currentContact.phoneNumber,
                        "is_active" to isActive
                    )
                )
                prefsManager.saveSelectedPhoneNumber(currentContact.phoneNumber)
                prefsManager.saveForwardingStatus(true)
            } else {
                LoggingManager.logInfo(
                    component = "ContactsViewModel",
                    action = "SAVE_STATE",
                    message = "Keine aktive Weiterleitung zu speichern",
                    details = mapOf(
                        "has_contact" to (currentContact != null),
                        "is_active" to isActive
                    )
                )
                prefsManager.clearSelection()
                prefsManager.saveForwardingStatus(false)
            }

            // Rest der Einstellungen speichern
            prefsManager.saveFilterText(_filterText.value)
            prefsManager.saveTestSmsText(_testSmsText.value)
            prefsManager.saveEmailAddresses(_emailAddresses.value)
            prefsManager.setForwardSmsToEmail(_forwardSmsToEmail.value)
        }
    }

    /**
     * Wechselt die Auswahl eines Kontakts mit Prüfung auf Eigenweiterleitung.
     */
    fun toggleContactSelection(contact: Contact) {
        if (_ownPhoneNumber.value.isBlank()) {
            showOwnNumberMissingDialog()
            return
        }

        val currentSelected = _selectedContact.value

        viewModelScope.launch {
            // Vergleich der normalisierten Nummern statt der Kontaktobjekte
            if (currentSelected != null &&
                contact.phoneNumber.filter { it.isDigit() } ==
                currentSelected.phoneNumber.filter { it.isDigit() }
            ) {

                LoggingManager.logInfo(
                    component = "ContactsViewModel",
                    action = "TOGGLE_CONTACT",
                    message = "Toggle bestehende Weiterleitung",
                    details = mapOf(
                        "contact" to contact.name,
                        "number" to contact.phoneNumber,
                        "current_state" to _forwardingActive.value
                    )
                )

                // Nutze toggleForwarding für konsistente Status-Verwaltung
                toggleForwarding(contact) { result ->
                    when (result) {
                        is ForwardingResult.Success -> {
                            LoggingManager.logInfo(
                                component = "ContactsViewModel",
                                action = "TOGGLE_SUCCESS",
                                message = "Weiterleitung erfolgreich umgeschaltet",
                                details = mapOf(
                                    "new_state" to _forwardingActive.value
                                )
                            )
                        }

                        is ForwardingResult.Error -> {
                            LoggingManager.logError(
                                component = "ContactsViewModel",
                                action = "TOGGLE_ERROR",
                                message = "Fehler beim Umschalten der Weiterleitung",
                                details = mapOf(
                                    "error" to result.message,
                                    "contact" to contact.name
                                )
                            )
                            SnackbarManager.showError(result.message)
                        }
                    }
                }
                return@launch
            }

            // Neuen Kontakt aktivieren
            activateForwarding(contact) { result ->
                when (result) {
                    is ForwardingResult.Success -> {
                        LoggingManager.logInfo(
                            component = "ContactsViewModel",
                            action = "SWITCH_CONTACT",
                            message = "Weiterleitung erfolgreich umgeschaltet",
                            details = mapOf(
                                "previous_contact" to (currentSelected?.name ?: "none"),
                                "new_contact" to contact.name
                            )
                        )
                        if (currentSelected != null) {
                            SnackbarManager.showSuccess(
                                "Weiterleitung von ${currentSelected.name} zu ${contact.name} umgeschaltet"
                            )
                        } else {
                            SnackbarManager.showSuccess(
                                "Weiterleitung zu ${contact.name} aktiviert"
                            )
                        }
                    }

                    is ForwardingResult.Error -> {
                        LoggingManager.logError(
                            component = "ContactsViewModel",
                            action = "SWITCH_CONTACT",
                            message = "Fehler beim Umschalten der Weiterleitung",
                            details = mapOf(
                                "previous_contact" to (currentSelected?.name ?: "none"),
                                "new_contact" to contact.name,
                                "error" to result.message
                            )
                        )
                        SnackbarManager.showError(
                            "Fehler beim ${if (currentSelected != null) "Umschalten" else "Aktivieren"} " +
                                    "der Weiterleitung: ${result.message}"
                        )
                    }
                }
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
            return
        }
        activateForwarding(contact)
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
//    private fun checkForwardingStatus() {
//        _forwardingActive.value = prefsManager.isForwardingActive()
//    }

    private fun updateNotification(message: String) {
        SmsForegroundService.updateNotification(application, message)
    }
}

class ContactsStore {

    private var contentObserver: ContentObserver? = null
    private var contentResolver: ContentResolver? = null
    private var updateJob: Job? = null
    private val scope = CoroutineScope(Job() + Dispatchers.IO)


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
        scope.launch { setupContentObserver(contentResolver) }

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
                    scope.launch {
                        LoggingManager.log(
                            LoggingHelper.LogLevel.INFO,
                            LoggingHelper.LogMetadata(
                                component = "ContactsStore",
                                action = "CONTACTS_CHANGED_SKIPPED",
                                details = mapOf("reason" to "update_in_progress")
                            ),
                            "Update übersprungen - bereits in Bearbeitung"
                        )
                    }
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
                val nameIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)

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
    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        initializePreferences()
    }

    private fun <T> getPreference(key: String, defaultValue: T): T {
        return try {
            @Suppress("UNCHECKED_CAST")
            when (defaultValue) {
                is String -> (prefs.getString(key, defaultValue) ?: defaultValue) as T
                is Boolean -> (prefs.getBoolean(key, defaultValue)) as T
                is Int -> (prefs.getInt(key, defaultValue)) as T
                is List<*> -> {
                    val value = prefs.getString(key, "")
                    if (value.isNullOrEmpty()) emptyList<String>() as T
                    else value.split(",").filter { it.isNotEmpty() } as T
                }

                else -> defaultValue
            }
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SharedPreferencesManager",
                action = "GET_PREFERENCE",
                message = "Fehler beim Lesen: $key",
                error = e
            )
            defaultValue
        }
    }

    private fun <T> setPreference(key: String, value: T) {
        try {
            prefs.edit().apply {
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is List<*> -> putString(key, (value as List<String>).joinToString(","))
                    null -> remove(key)
                }
                apply()
            }
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SharedPreferencesManager",
                action = "SET_PREFERENCE",
                message = "Fehler beim Speichern: $key",
                error = e
            )
        }
    }

    fun saveSelectedPhoneNumber(phoneNumber: String) {
        prefs.edit().apply {

                putString(KEY_SELECTED_PHONE, phoneNumber)
                putBoolean(KEY_FORWARDING_ACTIVE, true)

            apply()
        }

        LoggingManager.logInfo(
            component = "SharedPreferencesManager",
            action = "SAVE_PHONE_NUMBER",
            message = "Zielrufnummer aktualisiert",
            details = mapOf(
                "number" to (phoneNumber ?: "null"),
                "forwarding_active" to isForwardingActive()
            )
        )
    }

    // Aktiviere Weiterleitung mit Telefonnummer
    fun activateForwarding(phoneNumber: String) {
        require(phoneNumber.isNotEmpty()) { "Telefonnummer darf nicht leer sein" }
        prefs.edit().apply {
            putBoolean(KEY_FORWARDING_ACTIVE, true)
            putString(KEY_SELECTED_PHONE, phoneNumber)
            apply()
        }
        LoggingManager.logInfo(
            component = "SharedPreferencesManager",
            action = "ACTIVATE_FORWARDING",
            message = "Weiterleitung aktiviert",
            details = mapOf("number" to phoneNumber)
        )
    }

    // Deaktiviere Weiterleitung
    fun deactivateForwarding() {
        prefs.edit().apply {
            putBoolean(KEY_FORWARDING_ACTIVE, false)
            putString(KEY_SELECTED_PHONE, "")
            apply()
        }
        LoggingManager.logInfo(
            component = "SharedPreferencesManager",
            action = "DEACTIVATE_FORWARDING",
            message = "Weiterleitung deaktiviert"
        )
    }

    init {
        validateForwardingState()
        migrateOldPreferences()
    }

    // Prüfe ob Weiterleitung aktiv ist
    fun isForwardingActive(): Boolean =
        prefs.getBoolean(KEY_FORWARDING_ACTIVE, false)

    // Hole aktuelle Weiterleitungsnummer (leer wenn inaktiv)
    fun getForwardingNumber(): String =
        if (isForwardingActive()) {
            prefs.getString(KEY_SELECTED_PHONE, "") ?: ""
        } else ""

    // Keep Forwarding on Exit Funktionen
    fun setKeepForwardingOnExit(keep: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_FORWARDING_ON_EXIT, keep).apply()
    }

    fun getKeepForwardingOnExit(): Boolean =
        prefs.getBoolean(KEY_KEEP_FORWARDING_ON_EXIT, false)

    // Validiere und repariere inkonsistente Zustände
    fun validateForwardingState() {
        val isActive = prefs.getBoolean(KEY_FORWARDING_ACTIVE, false)
        val number = prefs.getString(KEY_SELECTED_PHONE, "") ?: ""

        when {
            // Aktiv aber keine Nummer
            isActive && number.isEmpty() -> {
                deactivateForwarding()
                LoggingManager.logWarning(
                    component = "SharedPreferencesManager",
                    action = "VALIDATE_STATE",
                    message = "Inkonsistenter Status korrigiert: Aktiv ohne Nummer"
                )
            }
            // Inaktiv aber Nummer vorhanden
            !isActive && number.isNotEmpty() -> {
                deactivateForwarding()
                LoggingManager.logWarning(
                    component = "SharedPreferencesManager",
                    action = "VALIDATE_STATE",
                    message = "Inkonsistenter Status korrigiert: Inaktiv mit Nummer"
                )
            }
        }
    }

    // Migriere alte Präferenzen falls nötig
    private fun migrateOldPreferences() {
        try {
            // Beispiel für Migration von alten Keys
            if (prefs.contains("old_forwarding_number")) {
                val oldNumber = prefs.getString("old_forwarding_number", "") ?: ""
                val oldActive = prefs.getBoolean("old_forwarding_status", false)

                if (oldActive && oldNumber.isNotEmpty()) {
                    activateForwarding(oldNumber)
                } else {
                    deactivateForwarding()
                }

                // Lösche alte Keys
                prefs.edit().apply {
                    remove("old_forwarding_number")
                    remove("old_forwarding_status")
                    apply()
                }

                LoggingManager.logInfo(
                    component = "SharedPreferencesManager",
                    action = "MIGRATE_PREFS",
                    message = "Alte Präferenzen migriert"
                )
            }
        } catch (e: Exception) {
            LoggingManager.logError(
                component = "SharedPreferencesManager",
                action = "MIGRATE_ERROR",
                message = "Fehler bei der Migration",
                error = e
            )
            // Bei Fehler sicheren Zustand herstellen
            deactivateForwarding()
        }
    }




































    fun saveForwardingStatus(isActive: Boolean) =
        setPreference(KEY_FORWARDING_ACTIVE, isActive)

    fun getSelectedPhoneNumber(): String =
        getPreference(KEY_SELECTED_PHONE, "")

    fun clearSelection() {
        prefs.edit().apply {
            setPreference(KEY_SELECTED_PHONE, "")
            putBoolean(KEY_FORWARDING_ACTIVE, false)
            apply()
        }

        LoggingManager.logInfo(
            component = "SharedPreferencesManager",
            action = "CLEAR_SELECTION",
            message = "Weiterleitung und Zielrufnummer zurückgesetzt"
        )
    }

    fun getSelectedContact(): Pair<String, Boolean> {
        val number = getSelectedPhoneNumber()
        val isActive = isForwardingActive()
        return Pair(number, isActive)
    }

    fun getTestEmailText(): String =
        getPreference(KEY_TEST_EMAIL_TEXT, "Das ist eine Test-Email.")

    fun saveTestEmailText(text: String) =
        setPreference(KEY_TEST_EMAIL_TEXT, text)

    fun isForwardSmsToEmail(): Boolean =
        getPreference(KEY_FORWARD_SMS_TO_EMAIL, false)

    fun setForwardSmsToEmail(enabled: Boolean) =
        setPreference(KEY_FORWARD_SMS_TO_EMAIL, enabled)

    fun getEmailAddresses(): List<String> =
        getPreference(KEY_EMAIL_ADDRESSES, emptyList())

    fun saveEmailAddresses(emails: List<String>) =
        setPreference(KEY_EMAIL_ADDRESSES, emails)

    fun getCountryCode(defaultCode: String = "+43"): String =
        getPreference(KEY_COUNTRY_CODE, defaultCode)

    fun saveCountryCode(code: String) {
        if (isValidCountryCode(code)) {
            setPreference(KEY_COUNTRY_CODE, code)
        }
    }

    fun getOwnPhoneNumber(): String =
        getPreference(KEY_OWN_PHONE_NUMBER, "")

    fun saveOwnPhoneNumber(number: String) =
        setPreference(KEY_OWN_PHONE_NUMBER, number)

    fun getTopBarTitle(): String =
        getPreference(KEY_TOP_BAR_TITLE, DEFAULT_TOP_BAR_TITLE)

    fun saveTopBarTitle(title: String) =
        setPreference(KEY_TOP_BAR_TITLE, title)

    fun getFilterText(): String =
        getPreference(KEY_FILTER_TEXT, "")

    fun saveFilterText(filterText: String) =
        setPreference(KEY_FILTER_TEXT, filterText)

    fun getTestSmsText(): String =
        getPreference(KEY_TEST_SMS_TEXT, "Das ist eine Test-SMS.")

    fun saveTestSmsText(text: String) =
        setPreference(KEY_TEST_SMS_TEXT, text)

    fun getSmtpHost(): String =
        getPreference(KEY_SMTP_HOST, DEFAULT_SMTP_HOST)

    fun getSmtpPort(): Int =
        getPreference(KEY_SMTP_PORT, DEFAULT_SMTP_PORT)

    fun getSmtpUsername(): String =
        getPreference(KEY_SMTP_USERNAME, "")

    fun getSmtpPassword(): String =
        getPreference(KEY_SMTP_PASSWORD, "")

    fun saveSmtpSettings(host: String, port: Int, username: String, password: String) {
        prefs.edit().apply {
            putString(KEY_SMTP_HOST, host)
            putInt(KEY_SMTP_PORT, port)
            putString(KEY_SMTP_USERNAME, username)
            putString(KEY_SMTP_PASSWORD, password)
            apply()
        }
    }

    fun isValidCountryCode(code: String): Boolean =
        code in setOf("+43", "+49", "+41")

    private fun initializePreferences(): SharedPreferences {
        return try {
            createEncryptedPreferences()
        } catch (e: Exception) {
            handlePreferencesError(e)
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

    private fun createUnencryptedPreferences(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)

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

        try {
            val prefsFile =
                File(context.applicationInfo.dataDir + "/shared_prefs/" + PREFS_NAME + ".xml")
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

    companion object {
        private const val KEY_TEST_EMAIL_TEXT = "test_email_text"
        private const val KEY_FORWARD_SMS_TO_EMAIL = "forward_sms_to_email"
        private const val KEY_EMAIL_ADDRESSES = "email_addresses"

        private const val KEY_FILTER_TEXT = "filter_text"
        private const val KEY_TEST_SMS_TEXT = "test_sms_text"
        private const val KEY_OWN_PHONE_NUMBER = "own_phone_number"
        private const val KEY_TOP_BAR_TITLE = "top_bar_title"
        private const val DEFAULT_TOP_BAR_TITLE = "TEL/SMS-Weiterleitung"
        private const val KEY_COUNTRY_CODE = "country_code"
        private const val KEY_SMTP_HOST = "smtp_host"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_SMTP_USERNAME = "smtp_username"
        private const val KEY_SMTP_PASSWORD = "smtp_password"
        private const val DEFAULT_SMTP_HOST = "smtp.gmail.com"
        private const val DEFAULT_SMTP_PORT = 587
        private const val PREFS_NAME = "SMSForwarderEncryptedPrefs"
        private const val PREFS_NAME_FALLBACK = "SMSForwarderPrefs"

        // Schlüssel für Weiterleitungsstatus
        private const val KEY_FORWARDING_ACTIVE = "forwarding_active"
        private const val KEY_SELECTED_PHONE = "selected_phone_number"
        private const val KEY_KEEP_FORWARDING_ON_EXIT = "keep_forwarding_on_exit"
    }
}

class PreferencesInitializationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class PermissionHandler(private val activity: Activity) {

    // Erforderliche Berechtigungen für die App
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
}

class Logger(
    context: Context,
    private val maxEntries: Int = 1000,
    private val rotationSize: Int = 750,  // Neue Variable für Rotationsgröße
    private val maxFileSize: Long = 5 * 1024 * 1024  // Max 5MB pro Log-Datei
) {
    companion object {
        private const val TAG = "SmsLogger"  // Maximal 23 Zeichen für Android-Logging
    }

    private val logMutex = Mutex()  // Mutex für thread-sichere Operationen
    private val filePattern = "app_log_%d.xml"
    private val maxLogFiles = 5  // Maximale Anzahl von Log-Dateien
    private val baseLogDir: File =
        context.getExternalFilesDir("logs") ?: context.getExternalFilesDir(null)!!
    private val mainLogFile: File = File(baseLogDir, "app_log.xml")

    private val logFile: File = File(context.getExternalFilesDir(null), "app_log.xml")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    private val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
    }

    private val logBuffer = mutableListOf<LogEntry>()
    private var lastSaveTime = System.currentTimeMillis()
    private val saveInterval = 60000 // 1 minute in milliseconds

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastError: LoggerException? = null

    init {
        baseLogDir.mkdirs()
        startPeriodicCleanup()

        scope.launch {
            try {
                LoggingManager.logInfo(
                    component = "Logger",
                    action = "INIT_START",
                    message = "Logger-Initialisierung gestartet"
                )

                cleanupOldLogFiles()
                if (!mainLogFile.exists() || !loadExistingLogs()) {
                    createNewLogFile()
                }

                LoggingManager.logInfo(
                    component = "Logger",
                    action = "INIT_COMPLETE",
                    message = "Logger-Initialisierung abgeschlossen"
                )
            } catch (e: Exception) {
                handleException(e, "Initialization failed")
                createNewLogFile()
            }
        }
    }

    private fun startPeriodicCleanup() {
        scope.launch {
            while (true) {
                delay(30 * 60 * 1000) // Alle 30 Minuten
                try {
                    performMaintenance()
                } catch (e: Exception) {
                    handleException(e, "Periodic cleanup failed")
                }
            }
        }
    }

    private suspend fun performMaintenance() {
        logMutex.withLock {
            try {
                rotateLogsIfNeeded()
                cleanupOldLogFiles()
                trimLogBuffer()
            } catch (e: Exception) {
                handleException(e, "Maintenance failed")
            }
        }
    }

    private fun rotateLogsIfNeeded() {
        if (mainLogFile.length() > maxFileSize) {
            val timestamp = System.currentTimeMillis()
            val rotatedFile = File(
                baseLogDir,
                String.format(Locale.US, filePattern, timestamp)
            )

            try {
                mainLogFile.renameTo(rotatedFile)
                createNewLogFile()

                LoggingManager.logInfo(
                    component = "Logger",
                    action = "LOG_ROTATION",
                    message = "Log-Datei rotiert",
                    details = mapOf(
                        "old_size" to mainLogFile.length(),
                        "new_file" to rotatedFile.name
                    )
                )
            } catch (e: Exception) {
                handleException(e, "Log rotation failed")
            }
        }
    }

    private fun cleanupOldLogFiles() {
        try {
            val logFiles = baseLogDir.listFiles { file ->
                file.name.matches(Regex("app_log_\\d+\\.xml"))
            }?.sortedByDescending { it.lastModified() }

            logFiles?.drop(maxLogFiles)?.forEach { file ->
                file.delete()
                LoggingManager.logInfo(
                    component = "Logger",
                    action = "CLEANUP",
                    message = "Alte Log-Datei gelöscht",
                    details = mapOf("file" to file.name)
                )
            }
        } catch (e: Exception) {
            handleException(e, "Cleanup of old log files failed")
        }
    }

    private fun trimLogBuffer() {
        if (logBuffer.size > maxEntries) {
            val trimCount = logBuffer.size - rotationSize
            if (trimCount > 0) {
                try {
                    // Sichere zuerst die zu entfernenden Einträge
                    val entriesToSave = logBuffer.take(trimCount)
                    appendToArchiveFile(entriesToSave)

                    // Dann entferne sie aus dem Buffer
                    logBuffer.subList(0, trimCount).clear()

                    LoggingManager.logInfo(
                        component = "Logger",
                        action = "BUFFER_TRIM",
                        message = "Log-Buffer gekürzt",
                        details = mapOf(
                            "removed_entries" to trimCount,
                            "current_size" to logBuffer.size
                        )
                    )
                } catch (e: Exception) {
                    handleException(e, "Buffer trimming failed")
                }
            }
        }
    }

    private fun appendToArchiveFile(entries: List<LogEntry>) {
        val archiveFile = File(baseLogDir, "archive_${getCurrentTimestamp().replace(":", "-")}.xml")
        try {
            val doc = documentBuilder.newDocument()
            val rootElement = doc.createElement("logEntries")
            doc.appendChild(rootElement)

            entries.forEach { (time, text) ->
                val logEntryElement = doc.createElement("logEntry")
                logEntryElement.appendChild(createElementWithText(doc, "time", time))
                logEntryElement.appendChild(createElementWithText(doc, "text", text))
                rootElement.appendChild(logEntryElement)
            }

            FileOutputStream(archiveFile).use { fos ->
                transformer.transform(DOMSource(doc), StreamResult(fos))
            }
        } catch (e: Exception) {
            handleException(e, "Failed to archive log entries")
        }
    }

    // Überschreibe die bestehende addLogEntry Funktion
    fun addLogEntry(entry: String) {
        scope.launch {
            logMutex.withLock {
                try {
                    val timestamp = getCurrentTimestamp()
                    logBuffer.add(LogEntry(timestamp, entry))

                    if (System.currentTimeMillis() - lastSaveTime > saveInterval) {
                        saveLogsToFile()
                        performMaintenance()
                    }
                } catch (e: Exception) {
                    handleException(e, "Failed to add log entry")
                }
            }
        }
    }

    // Modifiziere die bestehende saveLogsToFile Funktion
    private suspend fun saveLogsToFile() {
        logMutex.withLock {
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
            scope.launch {
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
            }

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

    fun clearLog() {
        try {
            logBuffer.clear()

            createNewLogFile()

            LoggingManager.logInfo(
                component = "Logger",
                action = "CLEAR_LOG",
                message = "Log wurde gelöscht"
            )
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
        append(
            """
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
    """.trimIndent()
        )

        logBuffer.asReversed().forEach { (timeStamp, text) ->
            val parts = timeStamp.split(" ")
            val isSmsForward = text.contains("Weiterleitung") || text.contains("] SEND_SMS")

            val entryClass = if (isSmsForward) "entry-column sms-forward" else "entry-column"

            if (parts.size == 2) {
                val (date, time) = parts
                append(
                    """
                <tr>
                    <td class="time-column">
                        <div class="time-cell">
                            <span class="date">$date</span>
                            <span class="time">$time</span>
                        </div>
                    </td>
                    <td class="$entryClass">$text</td>
                </tr>
            """
                )
            } else {
                append(
                    """
                <tr>
                    <td class="time-column">$timeStamp</td>
                    <td class="$entryClass">$text</td>
                </tr>
            """
                )
            }
        }

        append(
            """
                </tbody>
            </table>
        </div>
    </body>
    </html>
    """.trimIndent()
        )
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

    private fun createElementWithText(
        doc: Document,
        tagName: String,
        textContent: String
    ): Element {
        return doc.createElement(tagName).apply {
            appendChild(doc.createTextNode(textContent))
        }
    }

    private fun getCurrentTimestamp(): String = dateFormat.format(Date())

    private data class LogEntry(val time: String, val text: String)


    private fun handleException(e: Exception, message: String) {
        val loggerException = LoggerException(message, e)
        lastError = loggerException
        Log.e("Logger", message, e)
        Log.w("Logger", "Logger Error: $message - ${e.message}")
    }

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
}