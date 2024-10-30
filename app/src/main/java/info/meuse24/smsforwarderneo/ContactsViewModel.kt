package info.meuse24.smsforwarderneo

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ViewModel für die Verwaltung von Kontakten und SMS-Weiterleitungsfunktionen.
 * Implementiert DefaultLifecycleObserver für Lifecycle-bezogene Aktionen.
 */
class ContactsViewModel(
    private val application: Application,
    private val prefsManager: SharedPreferencesManager,
    private val logger: Logger
) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val loggingHelper = LoggingHelper(logger)
    private val context get() = getApplication<Application>()
    private val contactsStore = ContactsStore()
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

    private val _logEntriesHtml = MutableStateFlow<String>("")
    val logEntriesHtml: StateFlow<String> = _logEntriesHtml

    private val _logEntries = MutableStateFlow<String>("")
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

    private val _errorState = MutableStateFlow<ErrorDialogState?>(null)
    val errorState: StateFlow<ErrorDialogState?> = _errorState.asStateFlow()

    private val _isCleaningUp = MutableStateFlow(false)
    val isCleaningUp: StateFlow<Boolean> = _isCleaningUp.asStateFlow()
    // Einmaliges Event für Cleanup-Abschluss
    private val _cleanupCompleted = MutableSharedFlow<Unit>()
    val cleanupCompleted = _cleanupCompleted.asSharedFlow()

    private val _showOwnNumberMissingDialog = MutableStateFlow(false)
    val showOwnNumberMissingDialog: StateFlow<Boolean> = _showOwnNumberMissingDialog.asStateFlow()

    private val _keepForwardingOnExit = MutableStateFlow(prefsManager.getKeepForwardingOnExit())
    val keepForwardingOnExit: StateFlow<Boolean> = _keepForwardingOnExit.asStateFlow()


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

                loggingHelper.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ViewModel",
                        action = "CLEANUP",
                        details = mapOf(
                            "state" to "started",
                            "keepForwarding" to keepForwarding
                        )
                    ),
                    "App-Cleanup gestartet"
                )

                // Wenn keepForwarding false ist, Weiterleitung deaktivieren
                if (!keepForwarding) {
                    // Deaktiviere SMS-Weiterleitung
                    _selectedContact.value = null
                    prefsManager.clearSelection()

                    // Deaktiviere Anrufweiterleitung
                    if (PhoneSmsUtils.sendUssdCode(application, "##21#", logger)) {
                        _forwardingActive.value = false
                        prefsManager.saveForwardingStatus(false)
                        logger.addLogEntry("Weiterleitung wurde beim Beenden deaktiviert")
                    } else {
                        _errorState.value = ErrorDialogState.DeactivationError(
                            "Die Weiterleitung konnte nicht deaktiviert werden."
                        )
                        return@launch
                    }
                } else {
                    // Wenn keepForwarding true ist, aktuellen Status speichern
                    logger.addLogEntry("Weiterleitung bleibt beim Beenden aktiv")
                }

                // Speichere finalen Status
                saveCurrentState()

                // Cleanup erfolgreich
                loggingHelper.log(
                    LoggingHelper.LogLevel.INFO,
                    LoggingHelper.LogMetadata(
                        component = "ViewModel",
                        action = "CLEANUP",
                        details = mapOf(
                            "state" to "completed",
                            "keepForwarding" to keepForwarding
                        )
                    ),
                    "App-Cleanup abgeschlossen"
                )

                // Signal für Activity zum Beenden
                _cleanupCompleted.emit(Unit)

            } catch (e: Exception) {
                _errorState.value = ErrorDialogState.GeneralError(e)
                loggingHelper.log(
                    LoggingHelper.LogLevel.ERROR,
                    LoggingHelper.LogMetadata(
                        component = "ViewModel",
                        action = "CLEANUP",
                        details = mapOf("error" to e.message)
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
            _selectedContact.value = null
            prefsManager.clearSelection()
            _forwardingActive.value = false
            prefsManager.saveForwardingStatus(false)
            if (PhoneSmsUtils.sendUssdCode(application, "##21#", logger)) {
                logger.addLogEntry("Weiterleitung wurde deaktiviert.")
                updateNotification("keine Weiterleitung aktiv")
                updateForwardingStatus(false)
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
    }

    private fun initializeContactsStore() {
        viewModelScope.launch {
            contactsStore.initialize(
                contentResolver = application.contentResolver,
                countryCode = _countryCode.value
            )
            applyCurrentFilter()
        }
    }

    // Die einzige loadContacts Methode, die wir behalten
    fun applyCurrentFilter() {
        viewModelScope.launch {
            _isLoading.value = true
            _contacts.value = contactsStore.filterContacts(_filterText.value)
            _isLoading.value = false

            // Aktualisiere ausgewählten Kontakt falls nötig
            _selectedContact.value?.let { tempContact ->
                _selectedContact.value = _contacts.value.find {
                    it.phoneNumber == tempContact.phoneNumber
                }
            }
        }
    }

    private fun initializeCountryCode() {
        viewModelScope.launch {
            try {
                // 1. Erste Priorität: SIM-Karte
                val simCode = PhoneSmsUtils.getSimCardCountryCode(application, logger)
                if (simCode.isNotEmpty()) {
                    updateCountryCode(simCode)
                    _countryCodeSource.value = "SIM-Karte"
                    logger.addLogEntry("Ländercode von SIM-Karte ermittelt: $simCode")
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
                            logger.addLogEntry("Ländercode aus eigener Nummer ermittelt: $detectedCode")
                            return@launch
                        }
                    } catch (e: Exception) {
                        logger.addLogEntry("Fehler bei der Erkennung des Ländercodes aus eigener Nummer: ${e.message}")
                    }
                }

                // 3. Fallback auf Österreich
                updateCountryCode("+43")
                _countryCodeSource.value = "Standard (Österreich)"
                logger.addLogEntry("Verwende Default-Ländercode: +43 (Österreich)")
            } catch (e: Exception) {
                logger.addLogEntry("Fehler bei der Ländercode-Initialisierung: ${e.message}")
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
        logger.addLogEntry("ViewModel: onCreate")
        initializeApp()
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        logger.addLogEntry("ViewModel: onStart")
        // Alt: loadContacts(filterText.value)
        applyCurrentFilter()  // Neue Version
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        logger.addLogEntry("ViewModel: onResume")
        // Hier könnten Sie z.B. den Weiterleitungsstatus überprüfen
        checkForwardingStatus()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        logger.addLogEntry("ViewModel: onPause")
        // Hier könnten Sie z.B. den aktuellen Zustand speichern
        saveCurrentState()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        logger.addLogEntry("ViewModel: onDestroy")
        // Hier könnten Sie Ressourcen freigeben oder finale Cleanup-Operationen durchführen
    }

    // Lifecycle-Methoden
    override fun onStop(owner: LifecycleOwner) {
        saveCurrentState()
        logger.addLogEntry("App wurde in den Hintergrund versetzt. Zustand gespeichert.")
    }

    override fun onCleared() {
        super.onCleared()
        contactsStore.cleanup()
        saveCurrentState()
        logger.addLogEntry("ViewModel wurde zerstört. Zustand gespeichert.")
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
        logger.addLogEntry("Eigene Telefonnummer aktualisiert: $number")
    }

    fun loadOwnPhoneNumberIfEmpty(context: Context) {
        if (_ownPhoneNumber.value.isEmpty()) {
            loadOwnPhoneNumber(context)
        }
    }

    fun loadOwnPhoneNumber(context: Context) {
        viewModelScope.launch {
            val number = PhoneSmsUtils.getSimCardNumber(context, logger)
            if (number.isNotEmpty() && number != "Nummer konnte nicht ermittelt werden") {
                updateOwnPhoneNumber(number)
            } else {
                Toast.makeText(
                    context,
                    "Telefonnummer konnte nicht ermittelt werden",
                    Toast.LENGTH_SHORT
                ).show()
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
                _selectedContact.value = Contact("", savedPhoneNumber,"") // Temporärer Kontakt
                // Alt: loadContacts(_filterText.value)
                applyCurrentFilter()  // Neue Version
            }
            _forwardingActive.value = prefsManager.isForwardingActive()
            updateForwardingStatus(prefsManager.isForwardingActive())
            logger.addLogEntry("Gespeicherter Zustand geladen. Weiterleitung aktiv: ${_forwardingActive.value}")
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
            logger.addLogEntry("Aktueller App-Zustand wurde gespeichert.")
        }
    }

    fun initializeApp() {
            viewModelScope.launch {
                logger.addLogEntry("App wurde gestartet.")
                // Alt: loadContacts(_filterText.value)
                applyCurrentFilter()  // Neue Version
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
            deactivateForwarding()
            _selectedContact.value = null
        } else {
            val ownNumber = _ownPhoneNumber.value
            // Standardisiere beide Nummern für den Vergleich
            val standardizedContactNumber = standardizePhoneNumber(contact.phoneNumber)
            val standardizedOwnNumber = standardizePhoneNumber(ownNumber)

            if (standardizedContactNumber == standardizedOwnNumber) {
                // Verhindere Weiterleitung an eigene Nummer
                logger.addLogEntry("Weiterleitung an eigene Nummer (${contact.phoneNumber}) verhindert")
                InterfaceHolder.myInterface?.showToast("Eigenweiterleitung nicht möglich")
                return
            }

            if (ownNumber.isBlank()) {
                showOwnNumberMissingDialog()
            } else {
                _selectedContact.value = contact
                selectContact(contact)
            }
        }
    }

    /**
     * Aktualisiert den Filtertext für Kontakte.
     */
    // Update der Filtermethode
    fun updateFilterText(newFilter: String) {
        _filterText.value = newFilter
        prefsManager.saveFilterText(newFilter)
        applyCurrentFilter() // Verwende die neue Filtermethode
        logger.addLogEntry("Kontakt '$newFilter' wurde gesucht.")
    }

    /**
     * Aktualisiert den Text für Test-SMS.
     */
    fun updateTestSmsText(newText: String) {
        _testSmsText.value = newText
        prefsManager.saveTestSmsText(newText)
        logger.addLogEntry("Test-SMS Text wurde aktualisiert: $newText")
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
            logger.addLogEntry("Weiterleitung an eigene Nummer (${contact.phoneNumber}) verhindert")
            InterfaceHolder.myInterface?.showToast("Eigenweiterleitung nicht möglich")
            _selectedContact.value = null
            return
        }

        _selectedContact.value = contact
        prefsManager.saveSelectedPhoneNumber(contact.phoneNumber)
        _forwardingActive.value = true
        prefsManager.saveForwardingStatus(true)
        updateForwardingStatus(true)

        if (PhoneSmsUtils.sendUssdCode(application, "*21*${contact.phoneNumber}#", logger)) {
            logger.addLogEntry("Umleitung an ${contact.name} (${contact.phoneNumber}) wurde aktiviert.")
            updateNotification("Weiterleitung aktiv zu ${contact.name} (${contact.phoneNumber})")
        }
    }



    // Hilfsfunktion für USSD-Deaktivierung mit Timeout
    private suspend fun deactivateForwardingEnd(): Boolean {
        return try {
            withTimeout(10000) {
                // USSD-Code senden und auf Antwort warten
                PhoneSmsUtils.sendUssdCode(getApplication(), "##21#", logger)
            }
        } catch (e: TimeoutCancellationException) {
            _errorState.value = ErrorDialogState.TimeoutError
            false
        }
    }

    /**
     * Aktualisiert den Status der Weiterleitung.
     */
    fun updateForwardingStatus(active: Boolean) {
        _forwardingActive.value = active
        prefsManager.saveForwardingStatus(active)
        logger.addLogEntry("Forwarding status updated: $active")
    }

    /**
     * Sendet eine Test-SMS.
     */

    fun sendTestSms() {
        val contact = _selectedContact.value
        if (contact != null) {
            if (_ownPhoneNumber.value.isEmpty()) {
                _showOwnNumberMissingDialog.value = true
                return
            }

            val receiver = _ownPhoneNumber.value
            if (PhoneSmsUtils.sendTestSms(
                    application,
                    receiver,
                    prefsManager.getTestSmsText(),
                    logger
                )
            ) {
                logger.addLogEntry("Test-SMS '${prefsManager.getTestSmsText()}' an ${contact.name} (${receiver}) wurde versandt.")
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
            contactsStore.loadContacts(application.contentResolver)
            applyCurrentFilter()
            _isLoading.value = false
            logger.addLogEntry("Kontakte wurden neu geladen")
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




class ContactsStore {
    // Hauptspeicher für alle Kontakte
    private val allContacts = mutableListOf<Contact>()

    // Suchindex für schnellen Zugriff
    private val searchIndex = HashMap<String, MutableSet<Contact>>()

    private var contentObserver: ContentObserver? = null
    private var contentResolver: ContentResolver? = null
    private var updateJob: Job? = null  // Für Debouncing

    private var currentCountryCode: String = "+43"

    fun initialize(contentResolver: ContentResolver, countryCode: String) {
        this.currentCountryCode = countryCode
        this.contentResolver = contentResolver
        setupContentObserver(contentResolver)
        loadContacts(contentResolver)
    }

    fun updateCountryCode(newCode: String) {
        if (currentCountryCode != newCode) {
            currentCountryCode = newCode
            contentResolver?.let { loadContacts(it) }
        }
    }

    private fun setupContentObserver(contentResolver: ContentResolver) {
        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                // Debouncing implementieren
                updateJob?.cancel() // Vorherigen Job abbrechen
                updateJob = coroutineScope.launch {
                    delay(500) // 500ms Verzögerung
                    contentResolver.let { resolver ->
                        loadContacts(resolver)
                        //logger.addLogEntry("Kontakte nach Änderung neu geladen")
                    }
                }
            }
        }

        // Registriere Observer für verschiedene Kontakt-URIs
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
        }
    }

    fun cleanup() {
        updateJob?.cancel() // Job beim Cleanup abbrechen
        contentObserver?.let { observer ->
            contentResolver?.unregisterContentObserver(observer)
        }
        contentObserver = null
        contentResolver = null
        allContacts.clear()
        searchIndex.clear()
    }

    companion object {
        private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var countryCode: String = "+43"
    }

    fun loadContacts(contentResolver: ContentResolver) {
        val contacts = readContactsFromProvider(contentResolver)
        allContacts.clear()
        allContacts.addAll(contacts)
        rebuildSearchIndex()
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

    fun filterContacts(query: String): List<Contact> {
        if (query.isBlank()) return allContacts

        val searchTerms = query.lowercase().split(" ")
        var results = mutableSetOf<Contact>()

        // Für den ersten Suchbegriff
        searchTerms.firstOrNull()?.let { firstTerm ->
            results = searchIndex.entries
                .filter { it.key.contains(firstTerm) }
                .flatMap { it.value }
                .toMutableSet()
        }

        // Für weitere Suchbegriffe (AND-Verknüpfung)
        searchTerms.drop(1).forEach { term ->
            val termResults = searchIndex.entries
                .filter { it.key.contains(term) }
                .flatMap { it.value }
                .toSet()
            results.retainAll(termResults)
        }

        return results.sortedBy { it.name }
    }


    private fun readContactsFromProvider(contentResolver: ContentResolver, countryCode: String = "+43"): List<Contact> {
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
        private const val KEY_COUNTRY_CODE = "country_code"
        private const val DEFAULT_COUNTRY_CODE = "+43" // Österreich als Default
        private const val KEY_KEEP_FORWARDING_ON_EXIT = "keep_forwarding_on_exit"
    }

    fun setKeepForwardingOnExit(keep: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_FORWARDING_ON_EXIT, keep).apply()
    }

    fun getKeepForwardingOnExit(): Boolean {
        return prefs.getBoolean(KEY_KEEP_FORWARDING_ON_EXIT, false)
    }
    fun saveCountryCode(code: String) {
        if (isValidCountryCode(code)) {
            prefs.edit().putString(KEY_COUNTRY_CODE, code).apply()
        }
    }

    fun getCountryCode(): String {
        return prefs.getString(KEY_COUNTRY_CODE, DEFAULT_COUNTRY_CODE) ?: DEFAULT_COUNTRY_CODE
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




class PermissionHandler(private val activity: Activity) {

    // Erforderliche Berechtigungen für die App
    private val requiredPermissions = arrayOf(
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.POST_NOTIFICATIONS,

        android.Manifest.permission.READ_PHONE_NUMBERS
    )
    //private lateinit var permissionHandler: PermissionHandler

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private var isBatteryOptimizationRequested = false

    // Callback für das Ergebnis der Berechtigungsanfrage
    private var onPermissionsResult: ((Boolean) -> Unit)? = null

    // Initialisierung des PermissionHandlers
    fun initialize(launcher: ActivityResultLauncher<Array<String>>) {
        requestPermissionLauncher = launcher
    }

    // Prüft und fordert Berechtigungen an
    fun checkAndRequestPermissions(onResult: (Boolean) -> Unit) {
        onPermissionsResult = onResult
        if (allPermissionsGranted()) {
            requestBatteryOptimization()
            onResult(true)
        } else {
            requestPermissions()
        }
    }

    // Prüft, ob alle erforderlichen Berechtigungen erteilt wurden
    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    // Fordert die Berechtigungen an
    private fun requestPermissions() {
        requestPermissionLauncher.launch(requiredPermissions)
    }

    // Behandelt das Ergebnis der Berechtigungsanfrage
    fun onRequestPermissionsResult(granted: Boolean) {
        if (granted) {
            requestBatteryOptimization()
        } else {
            showPermissionRequiredDialog()
        }
        onPermissionsResult?.invoke(granted)
    }

    // Fordert die Batterieoptimierung an
    private fun requestBatteryOptimization() {
        if (!isBatteryOptimizationRequested) {
            val packageName = activity.packageName
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                isBatteryOptimizationRequested = true
                activity.startActivity(intent)
            }
        }
    }

    // Zeigt einen Dialog an, wenn erforderliche Berechtigungen nicht erteilt wurden
    private fun showPermissionRequiredDialog() {
        AlertDialog.Builder(activity)
            .setMessage("Die Berechtigungen sind für die App erforderlich. Die Ausführung wird beendet.")
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                activity.finish()
            }
            .create()
            .show()
    }
}




class Logger(private val context: Context, private val maxEntries: Int = 1000) {
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
            logBuffer.clear() // Ensure buffer is clear when creating new file
            val doc = documentBuilder.newDocument()
            doc.appendChild(doc.createElement("logEntries"))
            saveDocumentToFile(doc)
            addLogEntry("New log file created due to loading failure or non-existence")
        } catch (e: Exception) {
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
                max-width: 100%;
                overflow-x: auto;
                background-color: white;
                box-shadow: 0 0 10px rgba(0,0,0,0.1);
            }
            table {
                width: 100%;
                border-collapse: collapse;
                margin-bottom: 20px;
            }
            th, td {
                padding: 12px;
                text-align: left;
                border-bottom: 1px solid #ddd;
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
                font-size: 12px; /* Kleinere Schriftgröße für die Zeitspalte */
                white-space: nowrap; /* Verhindert Umbruch des Zeitstempels */
            }
            @media screen and (max-width: 600px) {
                body {
                    font-size: 14px;
                }
                th, td {
                    padding: 8px;
                }
                .time-column {
                    font-size: 10px; /* Noch kleinere Schrift auf kleinen Bildschirmen */
                }
            }
        </style>
    </head>
    <body>
        <div class="container">
            <table>
                <thead>
                    <tr>
                        <th class="time-column">Zeit</th>
                        <th>Eintrag</th>
                    </tr>
                </thead>
                <tbody>
    """.trimIndent())

        logBuffer.asReversed().forEach { (time, text) ->
            append("<tr><td class=\"time-column\">$time</td><td>$text</td></tr>")
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