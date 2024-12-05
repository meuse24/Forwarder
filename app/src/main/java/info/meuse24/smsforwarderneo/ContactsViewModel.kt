package info.meuse24.smsforwarderneo

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.Manifest
import android.annotation.SuppressLint
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class ContactsState(
    val isLoading: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    val selectedContact: Contact? = null,
    val forwardingActive: Boolean = false,
    val selectedPhoneNumber: String = "",
    val emailForwardingEnabled: Boolean = false,
    val emailAddresses: List<String> = emptyList(),
    val error: String? = null
)

/**
 * ViewModel für die Verwaltung von Kontakten und SMS-Weiterleitungsfunktionen.
 * Implementiert DefaultLifecycleObserver für Lifecycle-bezogene Aktionen.
 */
@OptIn(FlowPreview::class)

class ContactsViewModel(
    private val application: Application,
    private val prefsManager: SharedPreferencesManager,
    private val logger: Logger
) : AndroidViewModel(application) {
    private val contactsMutex = Mutex()
    private val stateMutex = Mutex()
    // StateFlows with thread-safe access
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _selectedContact = MutableStateFlow<Contact?>(null)
    val selectedContact: StateFlow<Contact?> = _selectedContact.asStateFlow()

    private val contactsStore = ContactsStore()
    private val _state = MutableStateFlow(ContactsState())

    // StateFlows für verschiedene UI-Zustände
    private val _isLoading = MutableStateFlow(false)

    private val _forwardingActive = MutableStateFlow(false)
    val forwardingActive: StateFlow<Boolean> = _forwardingActive.asStateFlow()

    private val _forwardingPhoneNumber = MutableStateFlow("")

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

    private val _cleanupCompleted = MutableSharedFlow<Unit>()
    val cleanupCompleted = _cleanupCompleted.asSharedFlow()

    private val _showOwnNumberMissingDialog = MutableStateFlow(false)
    val showOwnNumberMissingDialog: StateFlow<Boolean> = _showOwnNumberMissingDialog.asStateFlow()

    private val _forwardSmsToEmail = MutableStateFlow(prefsManager.isForwardSmsToEmail())
    val forwardSmsToEmail: StateFlow<Boolean> = _forwardSmsToEmail.asStateFlow()

    private val _keepForwardingOnExit = MutableStateFlow(false)
    //val keepForwardingOnExit: StateFlow<Boolean> = _keepForwardingOnExit.asStateFlow()

    private val filterMutex = Mutex() // Verhindert parallele Filteroperationen

    private val _smtpHost = MutableStateFlow(prefsManager.getSmtpHost())
    val smtpHost: StateFlow<String> = _smtpHost.asStateFlow()

    private val _smtpPort = MutableStateFlow(prefsManager.getSmtpPort())
    val smtpPort: StateFlow<Int> = _smtpPort.asStateFlow()

    private val _smtpUsername = MutableStateFlow(prefsManager.getSmtpUsername())
    val smtpUsername: StateFlow<String> = _smtpUsername.asStateFlow()

    private val _smtpPassword = MutableStateFlow(prefsManager.getSmtpPassword())
    val smtpPassword: StateFlow<String> = _smtpPassword.asStateFlow()

    private val _testEmailText = MutableStateFlow("")
    val testEmailText: StateFlow<String> = _testEmailText.asStateFlow()

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ContactsViewModel::class.java)) {
                val app = AppContainer.getApplication()
                @Suppress("UNCHECKED_CAST")
                return ContactsViewModel(
                    application = app,
                    prefsManager = AppContainer.prefsManager,
                    logger = AppContainer.logger
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    /**
     * Status der Weiterleitung
     */
    sealed class ForwardingResult {
        data object Success : ForwardingResult()
        data class Error(val message: String, val technical: String? = null) : ForwardingResult()
    }

    enum class ForwardingAction {
        ACTIVATE, DEACTIVATE, TOGGLE
    }

    suspend fun updateContacts(newContacts: List<Contact>) {
        contactsMutex.withLock {
            _contacts.value = newContacts
        }
    }
    suspend fun selectContact(contact: Contact) {
        stateMutex.withLock {
            if (_selectedContact.value != contact) {
                _selectedContact.value = contact
                // Update other dependent state
                _forwardingActive.value = true
                _forwardingPhoneNumber.value = contact.phoneNumber
                prefsManager.saveSelectedPhoneNumber(contact.phoneNumber)
            }
        }
    }

    suspend fun applyFilter(filterText: String) {
        contactsMutex.withLock {
            val filteredContacts = contactsStore.filterContacts(filterText)
            _contacts.value = filteredContacts

            // Update selected contact if necessary
            stateMutex.withLock {
                _selectedContact.value?.let { currentSelected ->
                    _selectedContact.value = filteredContacts.find {
                        it.phoneNumber == currentSelected.phoneNumber
                    }
                }
            }
        }
    }

    // Thread-safe state updates
    suspend fun updateForwardingState(active: Boolean) {
        stateMutex.withLock {
            _forwardingActive.value = active
            prefsManager.saveForwardingStatus(active)
            if (!active) {
                _selectedContact.value = null
                _forwardingPhoneNumber.value = ""
            }
        }
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
        if (!PhoneSmsUtils.sendUssdCode(application, "*21*${contact.phoneNumber}#")) {
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

    private fun updateNotification(message: String) {
        viewModelScope.launch {
            val intent = Intent(AppContainer.getApplication(), SmsForegroundService::class.java)
            intent.action = "UPDATE_NOTIFICATION"
            intent.putExtra("contentText", message)
            AppContainer.getApplication().startService(intent)
        }
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

    private fun activateForwarding(contact: Contact, onResult: (ForwardingResult) -> Unit = {}) {
        manageForwardingStatus(ForwardingAction.ACTIVATE, contact, onResult)
    }

    private fun deactivateForwarding(onResult: (ForwardingResult) -> Unit = {}) {
        manageForwardingStatus(ForwardingAction.DEACTIVATE, onResult = onResult)
    }

    private fun toggleForwarding(contact: Contact? = null, onResult: (ForwardingResult) -> Unit = {}) {
        manageForwardingStatus(ForwardingAction.TOGGLE, contact, onResult)
    }

    fun onShowExitDialog() {
        _showExitDialog.value = true
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
                    LogLevel.INFO,
                    LogMetadata(
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
                LogLevel.INFO,
                LogMetadata(
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

    fun updateTestEmailText(newText: String) {
        _testEmailText.value = newText
        prefsManager.saveTestEmailText(newText)

        LoggingManager.log(
            LogLevel.DEBUG,
            LogMetadata(
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
                        LogLevel.WARNING,
                        LogMetadata(
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
                            LogLevel.INFO,
                            LogMetadata(
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
                            LogLevel.ERROR,
                            LogMetadata(
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
                    LogLevel.ERROR,
                    LogMetadata(
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
                    ForwardingResult.Success -> {
                        updateServiceNotification() // Hier hinzugefügt
                    }
                }
            }
        }
    }

    fun showOwnNumberMissingDialog() {
        _showOwnNumberMissingDialog.value = true
    }

    fun hideOwnNumberMissingDialog() {
        _showOwnNumberMissingDialog.value = false
    }

    sealed class ErrorDialogState {
        data class DeactivationError(val message: String) : ErrorDialogState()
        data object TimeoutError : ErrorDialogState()
        data class GeneralError(val error: Exception) : ErrorDialogState()
    }

    fun hideExitDialog() {
        _showExitDialog.value = false
    }

    fun clearErrorState() {
        _errorState.value = null
    }


    init {
        viewModelScope.launch {
            // Beobachte Filtertext-Änderungen
            _filterText
                .debounce(300) // Wartet 300ms nach der letzten Änderung
                .collect { filterText ->
                    applyCurrentFilter()
                    LoggingManager.logInfo(
                        component = "ContactsViewModel",
                        action = "FILTER_APPLIED",
                        message = "Kontaktfilter angewendet",
                        details = mapOf(
                            "filter_text" to filterText,
                            "results_count" to _contacts.value.size
                        )
                    )
                }
        }

        // Bestehende Initialisierung
        initialize()
    }

    fun updateFilterText(newFilter: String) {
        _filterText.value = newFilter
        viewModelScope.launch {
            applyCurrentFilter()  // Direkter Aufruf für sofortige Aktualisierung
        }
        prefsManager.saveFilterText(newFilter)
    }

    fun initialize() {
        viewModelScope.launch {
            try {
                stateMutex.withLock {
                    _isLoading.value = true
                }

                // Lade gespeicherte Einstellungen
                loadSavedState()

                // Wenn keine Telefonnummer vorhanden, versuche sie zu ermitteln
                if (_ownPhoneNumber.value.isEmpty()) {
                    loadOwnPhoneNumber(application)
                }

                // Initialisiere Ländercode zuerst
                initializeCountryCode()

                // Initialisiere ContactsStore
                contactsStore.initialize(
                    contentResolver = application.contentResolver,
                    countryCode = _countryCode.value
                )

                // Starte Collection in separatem Launch-Block
                viewModelScope.launch {
                    contactsStore.contacts.collect { contactsList ->
                        updateContacts(contactsList)
                    }
                }
                viewModelScope.launch {
                    contactsStore.contacts.collect { contactsList ->
                        updateContacts(contactsList)
                    }
                }
                LoggingManager.logInfo(
                    component = "ContactsViewModel",
                    action = "INIT",
                    message = "ViewModel erfolgreich initialisiert"
                )
            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "ContactsViewModel",
                    action = "INIT_ERROR",
                    message = "ViewModel Initialisierung fehlgeschlagen",
                    error = e
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadSavedState() {
        _state.update { currentState ->
            currentState.copy(
                forwardingActive = prefsManager.isForwardingActive(),
                selectedPhoneNumber = prefsManager.getSelectedPhoneNumber(),
                emailForwardingEnabled = prefsManager.isForwardSmsToEmail(),
                emailAddresses = prefsManager.getEmailAddresses()
            )
        }
        _filterText.value = prefsManager.getFilterText()
        _testSmsText.value = prefsManager.getTestSmsText()
        _testEmailText.value = prefsManager.getTestEmailText()
        _ownPhoneNumber.value = prefsManager.getOwnPhoneNumber()
        _topBarTitle.value = prefsManager.getTopBarTitle()
        _smtpHost.value = prefsManager.getSmtpHost()
        _smtpPort.value = prefsManager.getSmtpPort()
        _smtpUsername.value = prefsManager.getSmtpUsername()
        _smtpPassword.value = prefsManager.getSmtpPassword()
        _countryCode.value = prefsManager.getCountryCode()
        _forwardSmsToEmail.value = prefsManager.isForwardSmsToEmail()
        _emailAddresses.value = prefsManager.getEmailAddresses()
        _forwardingActive.value = prefsManager.isForwardingActive()
        val savedPhoneNumber = prefsManager.getSelectedPhoneNumber()
        _forwardingPhoneNumber.value = savedPhoneNumber

        // Starte einen Coroutine-Scope um auf die Kontaktliste zu warten
        viewModelScope.launch {
            contacts.first { it.isNotEmpty() }.find { contact ->
                PhoneSmsUtils.standardizePhoneNumber(contact.phoneNumber, _countryCode.value) ==
                        PhoneSmsUtils.standardizePhoneNumber(savedPhoneNumber, _countryCode.value)
            }?.let { foundContact ->
                _selectedContact.value = foundContact
                LoggingManager.logInfo(
                    component = "ContactsViewModel",
                    action = "RESTORE_CONTACT",
                    message = "Gespeicherter Kontakt wiederhergestellt",
                    details = mapOf(
                        "contact" to foundContact.name,
                        "number" to foundContact.phoneNumber,
                        "forwarding_active" to _forwardingActive.value
                    )
                )
            }

            // Validiere den wiederhergestellten Zustand
            validateRestoredState()
        }
    }

    private fun validateRestoredState() {
        viewModelScope.launch {
            val hasSelectedContact = _selectedContact.value != null
            val isForwarding = _forwardingActive.value

            when {
                isForwarding && !hasSelectedContact -> {
                    _forwardingActive.value = false
                    prefsManager.saveForwardingStatus(false)
                    LoggingManager.logWarning(
                        component = "ContactsViewModel",
                        action = "VALIDATE_STATE",
                        message = "Inkonsistenter Status korrigiert",
                        details = mapOf(
                            "reason" to "no_contact_but_active"
                        )
                    )
                }
                !isForwarding && hasSelectedContact -> {
                    LoggingManager.logInfo(
                        component = "ContactsViewModel",
                        action = "VALIDATE_STATE",
                        message = "Kontakt beibehalten, Weiterleitung inaktiv"
                    )
                }
            }
        }
    }

    private fun initializeCountryCode() {

        viewModelScope.launch {
            try {
                // 1. Erste Priorität: SIM-Karte
                val simCode = PhoneSmsUtils.getSimCardCountryCode(application)
                if (simCode.isNotEmpty()) {
                    updateCountryCode(simCode)
                    _countryCodeSource.value = "SIM-Karte"
                    LoggingManager.log(
                        LogLevel.INFO,
                        LogMetadata(
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
                                LogLevel.INFO,
                                LogMetadata(
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
                            LogLevel.WARNING,
                            LogMetadata(
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
                    LogLevel.INFO,
                    LogMetadata(
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
                    LogLevel.ERROR,
                    LogMetadata(
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
                LogLevel.INFO,
                LogMetadata(
                    component = "ContactsViewModel",
                    action = "VIEWMODEL_CLEARED",
                    details = mapOf("state" to "saved")
                ),
                "ViewModel wurde bereinigt"
            )
        }
    }

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
            LogLevel.INFO,
            LogMetadata(
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
                        "is_active" to true
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
                updateServiceNotification()
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

    // Optimierte applyCurrentFilter mit Mutex
    suspend fun applyCurrentFilter() {
        filterMutex.withLock {
            val startTime = System.currentTimeMillis()
            _isLoading.value = true
            try {
                val filteredContacts = contactsStore.filterContacts(_filterText.value)
                _contacts.value = filteredContacts

                LoggingManager.log(
                    LogLevel.DEBUG,
                    LogMetadata(
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
                            LogLevel.INFO,
                            LogMetadata(
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
                    LogLevel.ERROR,
                    LogMetadata(
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
                LogLevel.DEBUG,
                LogMetadata(
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

    fun updateForwardSmsToEmail(enabled: Boolean) {
        _forwardSmsToEmail.value = enabled
        prefsManager.setForwardSmsToEmail(enabled)
        updateServiceNotification()

        LoggingManager.logInfo(
            component = "ContactsViewModel",
            action = if (enabled) "ENABLE_EMAIL_FORWARDING" else "DISABLE_EMAIL_FORWARDING",
            message = "Email-Weiterleitung ${if (enabled) "aktiviert" else "deaktiviert"}",
            details = mapOf(
                "sms_forwarding_active" to _forwardingActive.value,
                "email_addresses_count" to _emailAddresses.value.size
            )
        )
    }

    private fun updateServiceNotification() {
        val status = buildString {
            val hasForwarding = _forwardingActive.value
            val hasEmail = _forwardSmsToEmail.value

            when {
                // Beide aktiv
                hasForwarding && hasEmail -> {
                    append("SMS-Weiterleitung aktiv")
                    _selectedContact.value?.let { contact ->
                        append(" zu ${contact.name}")
                    }
                    append("\nEmail-Weiterleitung aktiv")
                    val emailCount = _emailAddresses.value.size
                    append(" an $emailCount Email(s)")
                }
                // Nur SMS-Weiterleitung
                hasForwarding -> {
                    append("SMS-Weiterleitung aktiv")
                    _selectedContact.value?.let { contact ->
                        append(" zu ${contact.name}")
                    }
                }
                // Nur Email-Weiterleitung
                hasEmail -> {
                    append("Email-Weiterleitung aktiv")
                    val emailCount = _emailAddresses.value.size
                    append(" an $emailCount Email(s)")
                }
                // Keine Weiterleitung aktiv
                else -> {
                    append("TEL/SMS Forwarder läuft im Hintergrund.")
                }
            }
        }

        viewModelScope.launch {
            val intent = Intent(AppContainer.getApplication(), SmsForegroundService::class.java)
            intent.action = "UPDATE_NOTIFICATION"
            intent.putExtra("contentText", status)
            AppContainer.getApplication().startService(intent)
        }
    }

    /**
     * Sendet eine Test-SMS.
     */
    fun sendTestSms() {
        val contact = _selectedContact.value
        if (contact != null) {
            if (_ownPhoneNumber.value.isEmpty()) {
                LoggingManager.log(
                    LogLevel.WARNING,
                    LogMetadata(
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
                    LogLevel.INFO,
                    LogMetadata(
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
                    LogLevel.ERROR,
                    LogMetadata(
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
                LogLevel.INFO,
                LogMetadata(
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
                            LogLevel.INFO,
                            LogMetadata(
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
                                LogLevel.INFO,
                                LogMetadata(
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
                            LogLevel.ERROR,
                            LogMetadata(
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
                    LogLevel.INFO,
                    LogMetadata(
                        component = "ContactsStore",
                        action = "REGISTER_OBSERVER",
                        details = mapOf("status" to "success")
                    ),
                    "ContentObserver erfolgreich registriert"
                )
            }
        } catch (e: Exception) {
            LoggingManager.log(
                LogLevel.ERROR,
                LogMetadata(
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
                LoggingManager.logInfo(
                    component = "ContactsStore",
                    action = "LOAD_CONTACTS_START",
                    message = "Starte Laden der Kontakte"
                )

                val contacts = readContactsFromProvider(contentResolver)
                allContacts.clear()
                allContacts.addAll(contacts)
                rebuildSearchIndex()
                _contacts.value = allContacts.toList()

                LoggingManager.logInfo(
                    component = "ContactsStore",
                    action = "LOAD_CONTACTS",
                    details = mapOf(
                        "duration_ms" to (System.currentTimeMillis() - startTime),
                        "contacts_count" to contacts.size,
                        "index_size" to searchIndex.size
                    ),
                    message = "Kontakte erfolgreich geladen"
                )
            } catch (e: Exception) {
                LoggingManager.logError(
                    component = "ContactsStore",
                    action = "LOAD_CONTACTS_ERROR",
                    details = mapOf(
                        "error" to e.message,
                        "duration_ms" to (System.currentTimeMillis() - startTime)
                    ),
                    message = "Fehler beim Laden der Kontakte",
                    error = e
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
                LogLevel.DEBUG,
                LogMetadata(
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
                LogLevel.INFO,
                LogMetadata(
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
                LogLevel.ERROR,
                LogMetadata(
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
                LogLevel.INFO,
                LogMetadata(
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
                            LogLevel.INFO,
                            LogMetadata(
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
                        LogLevel.ERROR,
                        LogMetadata(
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
                    is List<*> -> putString(key, (value as List<*>).joinToString(","))
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
                "number" to phoneNumber,
                "forwarding_active" to isForwardingActive()
            )
        )
    }

    // Aktiviere Weiterleitung mit Telefonnummer
    private fun activateForwarding(phoneNumber: String) {
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
    private fun deactivateForwarding() {


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

    // Keep Forwarding on Exit Funktionen
    fun setKeepForwardingOnExit(keep: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_FORWARDING_ON_EXIT, keep).apply()
    }

    fun getKeepForwardingOnExit(): Boolean =
        prefs.getBoolean(KEY_KEEP_FORWARDING_ON_EXIT, false)

    // Validiere und repariere inkonsistente Zustände
    private fun validateForwardingState() {
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

    fun saveCountryCode(code: String) {
        if (isValidCountryCode(code)) {
            setPreference(KEY_COUNTRY_CODE, code)
        }
    }

    fun saveOwnPhoneNumber(number: String) =
        setPreference(KEY_OWN_PHONE_NUMBER, number)

    fun saveTopBarTitle(title: String) =
        setPreference(KEY_TOP_BAR_TITLE, title)

    fun saveFilterText(filterText: String) =
        setPreference(KEY_FILTER_TEXT, filterText)

    fun saveTestSmsText(text: String) =
        setPreference(KEY_TEST_SMS_TEXT, text)

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

    private fun isValidCountryCode(code: String): Boolean =
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
            LogLevel.ERROR,
            LogMetadata(
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
                    LogLevel.INFO,
                    LogMetadata(
                        component = "SharedPreferencesManager",
                        action = "DELETE_CORRUPTED",
                        details = emptyMap()
                    ),
                    "Beschädigte Preferences gelöscht"
                )
            }
        } catch (e: Exception) {
            LoggingManager.log(
                LogLevel.ERROR,
                LogMetadata(
                    component = "SharedPreferencesManager",
                    action = "DELETE_ERROR",
                    details = mapOf("error" to e.message)
                ),
                "Fehler beim Löschen der beschädigten Preferences"
            )
        }
    }


    fun getTopBarTitle(): String =
        getPreference(KEY_TOP_BAR_TITLE, DEFAULT_TOP_BAR_TITLE)

    fun getTestSmsText(): String =
        getPreference(KEY_TEST_SMS_TEXT, DEFAULT_TEST_SMS_TEXT)

    fun getTestEmailText(): String =
        getPreference(KEY_TEST_EMAIL_TEXT, DEFAULT_TEST_EMAIL_TEXT)

    fun getSmtpHost(): String =
        getPreference(KEY_SMTP_HOST, DEFAULT_SMTP_HOST)

    fun getSmtpPort(): Int =
        getPreference(KEY_SMTP_PORT, DEFAULT_SMTP_PORT)

    fun getFilterText(): String =
        getPreference(KEY_FILTER_TEXT, DEFAULT_FILTER_TEXT)

    fun getOwnPhoneNumber(): String =
        getPreference(KEY_OWN_PHONE_NUMBER, DEFAULT_OWN_PHONE)

    fun getCountryCode(defaultCode: String = DEFAULT_COUNTRY_CODE): String =
        getPreference(KEY_COUNTRY_CODE, defaultCode)

    // Neue Methoden hinzufügen:
    fun setLogPIN(pin: String) =
        setPreference(KEY_LOG_PIN, pin)

    fun getLogPIN(): String =
        getPreference(KEY_LOG_PIN, "0000") // Default PIN ist 0000

    companion object {
        private const val KEY_TEST_EMAIL_TEXT = "test_email_text"
        private const val KEY_FORWARD_SMS_TO_EMAIL = "forward_sms_to_email"
        private const val KEY_EMAIL_ADDRESSES = "email_addresses"
        private const val KEY_LOG_PIN = "log_pin"
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
        private const val DEFAULT_TEST_SMS_TEXT = "Das ist eine Test-SMS"
        private const val DEFAULT_TEST_EMAIL_TEXT = "Das ist eine Test-Email"
         private const val DEFAULT_FILTER_TEXT = ""
        private const val DEFAULT_OWN_PHONE = ""
        private const val DEFAULT_COUNTRY_CODE = "+43"
         private const val KEY_FORWARDING_ACTIVE = "forwarding_active"
        private const val KEY_SELECTED_PHONE = "selected_phone_number"
        private const val KEY_KEEP_FORWARDING_ON_EXIT = "keep_forwarding_on_exit"
    }
}

class PreferencesInitializationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class PermissionHandler(private val activity: MainActivity) {

    private val requiredPermissions: Array<String> = getRequiredPermissions()

    private val requestLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        permissionCallback?.invoke(allGranted)
        permissionCallback = null
    }

    private var permissionCallback: ((Boolean) -> Unit)? = null

    fun checkPermissions(
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        if (hasPermissions()) {
            onGranted()
        } else {
            permissionCallback = { granted ->
                if (granted) onGranted() else onDenied()
            }
            requestPermissions()
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        try {
            requestLauncher.launch(requiredPermissions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request permissions", e)
            permissionCallback?.invoke(false) // Falls etwas schiefgeht, verweigere Zugriff
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PERMISSIONS_TIRAMISU
        } else {
            PERMISSIONS_BASE
        }
    }

    companion object {
        private const val TAG = "PermissionHandler"

        private val PERMISSIONS_BASE = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        )

        @SuppressLint("InlinedApi")
        private val PERMISSIONS_TIRAMISU = PERMISSIONS_BASE + arrayOf(
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
}

class Logger(
    context: Context,
    private val maxFileSize: Long = 5 * 1024 * 1024 // 5MB
)
{
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logMutex = Mutex()
    private val baseLogDir: File = context.getExternalFilesDir("logs") ?: context.getExternalFilesDir(null)!!
    private val mainLogFile = File(baseLogDir, "app_log.xml")
    private val backupFile = File(baseLogDir, "app_log_backup.xml")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var logEntryCounter = 0
    init {
        require(baseLogDir.exists() || baseLogDir.mkdirs()) { "Log directory could not be created: $baseLogDir" }
        if (!mainLogFile.exists()) {
            createEmptyLogFile()
        }
        addInitialLogEntry()
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
        val logMessage = buildLogMessage(metadata, message)
        when (level) {
            LogLevel.INFO -> Log.i(TAG, logMessage)
            LogLevel.WARNING -> Log.w(TAG, logMessage)
            LogLevel.ERROR -> Log.e(TAG, logMessage, exception)
            LogLevel.DEBUG -> Log.d(TAG, logMessage)
        }

        val prefix = when (level) {
            LogLevel.INFO -> "ℹ️"
            LogLevel.WARNING -> "⚠️"
            LogLevel.ERROR -> "❌"
            LogLevel.DEBUG -> "🔍"
        }

        val logEntry = buildString {
            append(prefix)
            append(" ")
            append(logMessage)
            if (exception != null) {
                append(" | Exception: ${exception.message}")
            }
        }

        scope.launch {
            writeLogToFile(getCurrentTimestamp(), logEntry)
        }
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
    private suspend fun writeLogToFile(timestamp: String, entry: String) {
        logMutex.withLock {
            try {
                if (mainLogFile.length() > maxFileSize) {
                    rotateLogFiles()
                }
                logEntryCounter++
                appendToLogFile(timestamp, entry, logEntryCounter)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log", e)
            }
        }
    }

    private fun appendToLogFile(timestamp: String, entry: String, entryNumber: Int) {
        try {
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(mainLogFile)

            val root = document.documentElement
            val newEntry = document.createElement("logEntry").apply {
                appendChild(document.createElement("number").apply { textContent = entryNumber.toString() })
                appendChild(document.createElement("time").apply { textContent = timestamp })
                appendChild(document.createElement("text").apply { textContent = entry })
            }
            root.appendChild(newEntry)

            TransformerFactory.newInstance().newTransformer().transform(
                DOMSource(document),
                StreamResult(mainLogFile)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append log entry", e)
        }
    }



    private fun rotateLogFiles() {
        backupFile.delete()
        mainLogFile.renameTo(backupFile)
        createEmptyLogFile()
    }

    private fun createEmptyLogFile() {
        try {
            mainLogFile.writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
                   <logEntries>
                   </logEntries>
                """.trimIndent()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create empty log file", e)
        }
    }

    private fun addInitialLogEntry() {
        val initMessage = buildString {
            append("Logger initialized. ")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, ")
            append("Android: ${Build.VERSION.RELEASE} ")
            append("(API ${Build.VERSION.SDK_INT})")
        }
        log(LogLevel.INFO, LogMetadata("Logger", "INIT"), initMessage)
    }

    fun getLogEntries(): String = readLogEntries { entry ->
        val time = entry.getElementsByTagName("time").item(0).textContent
        val text = entry.getElementsByTagName("text").item(0).textContent

        // Prüfe ob einer der Patterns im Text vorkommt
        val shouldHighlight = highlightPatterns.any { pattern ->
            text.contains(pattern, ignoreCase = true)
        }

        // Füge einen Farbcode für roten Text hinzu wenn nötig
        val textWithColor = if (shouldHighlight) {
            "\u001B[31m$text\u001B[0m"  // Rot für hervorgehobenen Text
        } else {
            text
        }

        "$time - $textWithColor\n"
    }

    private fun readLogEntries(process: (Element) -> String): String {
        return try {
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(mainLogFile)
            val entries = document.getElementsByTagName("logEntry")
            buildString {
                for (i in entries.length - 1 downTo 0) {
                    val entry = entries.item(i) as Element
                    val number = entry.getElementsByTagName("number").item(0).textContent
                    append(process(entry))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log entries", e)
            ""
        }
    }

    private val highlightPatterns = listOf(
        "ACTIVATE_FORWARDING",
        "EXCEPTION",
        "FAILURE",
        "CRITICAL",
        "SMS_FORWARD_FAILED",
        "EMAIL_FORWARD_ERROR",
        "PERMISSION_DENIED",
        "WAKE_LOCK_ERROR",
        "CONNECTION_FAILED",
        "INVALID_NUMBER",
        "SECURITY_ERROR",
        "AUTHENTICATION_FAILED"
    )

    private fun readLogEntries(process: (Element, Boolean) -> String): String {
        return try {
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(mainLogFile)
            val entries = document.getElementsByTagName("logEntry")
            buildString {
                for (i in entries.length - 1 downTo 0) {
                    val entry = entries.item(i) as Element
                    val entryText = entry.getElementsByTagName("text").item(0).textContent
                    entry.getElementsByTagName("number").item(0)?.textContent?.toIntOrNull()

                    // Prüfe, ob einer der Patterns im Text vorkommt
                    val shouldHighlight = highlightPatterns.any { pattern ->
                        entryText.contains(pattern, ignoreCase = true)
                    }

                    // Füge die Nummer des Log-Eintrags hinzu, falls vorhanden
                    val processedEntry = process(entry, shouldHighlight)
                    append(processedEntry)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log entries", e)
            ""
        }
    }

    fun getLogEntriesHtml(): String = buildString {
        append(HTML_HEADER)
        append(readLogEntries { entry, shouldHighlight ->
            val timestamp = entry.getElementsByTagName("time").item(0).textContent
            val text = entry.getElementsByTagName("text").item(0).textContent
            val number = entry.getElementsByTagName("number").item(0)?.textContent ?: "N/A"

            // Timestamp umformatieren
            val formattedTimestamp = LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val date = formattedTimestamp.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            val time = formattedTimestamp.format(DateTimeFormatter.ofPattern("HHmmss"))
            val textClass = if (shouldHighlight) "text-red-600" else ""

            """
        <tr>
            <td class="time-column">
                <div class="time-cell">
                <span class="date">#$number</span>
                    <span class="date">$date</span>
                    <span class="time">$time</span>
                </div>
            </td>
            <td class="entry-column $textClass">$text</td>
        </tr>
        """.trimIndent()
        })
        append(HTML_FOOTER)
    }

    private fun filterNonAscii(text: String): String {
        val germanReplacements = mapOf(
            'ä' to "ae",
            'ö' to "oe",
            'ü' to "ue",
            'Ä' to "Ae",
            'Ö' to "Oe",
            'Ü' to "Ue",
            'ß' to "ss"
        )

        return text.map { char ->
            when {
                germanReplacements.containsKey(char) -> germanReplacements[char]
                char.code in 32..126 || char == '\n' || char == '\r' || char == '\t' -> char.toString()
                else -> ""
            }
        }.joinToString("")
    }

    fun getLogEntriesAsCsv(): String = buildString {
        append("Nr;Datum;Zeit;Eintrag\n")
        append(readLogEntries { entry ->
            val timestamp = entry.getElementsByTagName("time").item(0).textContent
            val text = filterNonAscii(entry.getElementsByTagName("text").item(0).textContent)
            val number = entry.getElementsByTagName("number").item(0)?.textContent ?: "N/A"

            val (date, time) = timestamp.split(" ", limit = 2)
            val replacedText = replaceSpecialCharacters(text)

            "$number;$date;$time;$replacedText\n"
        })
    }

    // Hilfsfunktion zum Ersetzen von Umlauten und ß
    private fun replaceSpecialCharacters(text: String): String {
        return text
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("Ä", "Ae")
            .replace("Ö", "Oe")
            .replace("Ü", "Ue")
            .replace("ß", "ss")
            .replace(";", " ")  // Semikolon durch Leerzeichen ersetzen
            .replace("\n", " ")  // Zeilenumbruch durch Leerzeichen ersetzen
            .replace("\r", " ")  // Wagenrücklauf durch Leerzeichen ersetzen
            .replaceFirst("]", ";")
    }

    fun clearLog() {
        createEmptyLogFile()
        addInitialLogEntry()
    }

    private fun getCurrentTimestamp(): String = LocalDateTime.now().format(dateFormat)

    companion object {
        private const val TAG = "Logger"
        private val HTML_HEADER = """
    <!DOCTYPE html>
    <html lang="de">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            body { 
                font-family: 'Courier New', Courier, monospace; /* Monospaced font */
                font-size: 12px; /* Kleinere Schriftgröße */
                margin: 0; 
                padding: 16px; 
                background: #f5f5f5; 
            }
            table { 
                width: 100%; 
                border-collapse: collapse; 
                background: white; 
                box-shadow: 0 1px 3px rgba(0,0,0,0.1); 
            }
            th { 
                background: #4CAF50; 
                color: white; 
                padding: 8px; 
                text-align: left; 
                font-size: 14px; 
            }
            td { 
                padding: 8px; 
                border-bottom: 1px solid #eee; 
            }
            .number-column {
                width: 60px;
                text-align: right;
                font-weight: bold;
            }

            .time-column { 
                width: 140px; 
                white-space: nowrap; 
            }
            .time-cell { 
                display: flex; 
                flex-direction: column; 
                font-family: 'Courier New', Courier, monospace; /* Monospaced font for timestamp */
            }
            .date { 
                color: #666; 
                font-size: 0.9em; 
            }
            .time { 
                color: #666; 
                font-size: 0.9em; 
            }
            .entry-column { 
                font-family: 'Courier New', Courier, monospace; /* Monospaced font for entries */
                font-size: 12px; /* Same smaller font size */
            }
            tr:hover { 
                background: #f8f8f8; 
            }
            .text-red-600 {
                color: #DC2626 !important;
            }
        </style>
    </head>
    <body>
        <table>
            <thead>
                <tr>
                    <th class="time-column">Zeit</th>
                    <th class="entry-column">Eintrag</th>
                </tr>
            </thead>
            <tbody>
""".trimIndent()
        private const val HTML_FOOTER = """
            </tbody>
        </table>
    </body>
    </html>
"""

    }
}

