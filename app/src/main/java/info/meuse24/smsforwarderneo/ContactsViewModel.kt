package info.meuse24.smsforwarderneo

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel für die Verwaltung von Kontakten und SMS-Weiterleitungsfunktionen.
 * Implementiert DefaultLifecycleObserver für Lifecycle-bezogene Aktionen.
 */
class ContactsViewModel(
    private val application: Application,
    private val prefsManager: SharedPreferencesManager,
    private val logger: Logger
) : AndroidViewModel(application), DefaultLifecycleObserver {

    private val context get() = getApplication<Application>()

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

    private val _showOwnNumberMissingDialog = MutableLiveData<Boolean>()
    val showOwnNumberMissingDialog: LiveData<Boolean> = _showOwnNumberMissingDialog

    init {
        loadSavedState()
        loadContacts()
        loadSavedContact()
        checkForwardingStatus()
        _ownPhoneNumber.value = prefsManager.getOwnPhoneNumber()
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        logger.addLogEntry("ViewModel: onCreate")
        initializeApp()
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        logger.addLogEntry("ViewModel: onStart")
        // Hier könnten Sie z.B. die Kontaktliste aktualisieren
        loadContacts(filterText.value)
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
            val number = PhoneSmsUtils.getSimCardNumber(context)
            if (number.isNotEmpty() && number != "Nummer konnte nicht ermittelt werden") {
                updateOwnPhoneNumber(number)
            } else {
                Toast.makeText(context, "Telefonnummer konnte nicht ermittelt werden", Toast.LENGTH_SHORT).show()
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
                _selectedContact.value = Contact("", savedPhoneNumber) // Temporärer Kontakt
                loadContacts(_filterText.value)
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
            loadContacts(_filterText.value)
            reloadLogs()
            updateForwardingStatus(prefsManager.isForwardingActive())
        }
    }

    /**
     * Wechselt die Auswahl eines Kontakts.
     */
    fun toggleContactSelection(contact: Contact) {
        val currentSelected = _selectedContact.value
        if (contact == currentSelected) {
            deactivateForwarding()
            _selectedContact.value = null
        } else {
            _selectedContact.value = contact
            selectContact(contact)
        }
    }

    /**
     * Lädt Kontakte basierend auf einem Filter.
     */
    fun loadContacts(filter: String = "") {
        viewModelScope.launch {
            _isLoading.value = true
            val loadedContacts = readContacts(filter)
            _contacts.value = loadedContacts
            _isLoading.value = false

            // Aktualisiere den selectedContact mit vollständigen Informationen
            _selectedContact.value?.let { tempContact ->
                _selectedContact.value =
                    loadedContacts.find { it.phoneNumber == tempContact.phoneNumber }
            }

            logger.addLogEntry("Kontakte geladen: ${loadedContacts.size}")
        }
    }

    /**
     * Aktualisiert den Filtertext für Kontakte.
     */
    fun updateFilterText(newFilter: String) {
        _filterText.value = newFilter
        prefsManager.saveFilterText(newFilter)
        loadContacts(newFilter)
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
     * Wählt einen Kontakt aus und aktiviert die Weiterleitung.
     */
    fun selectContact(contact: Contact?) {
        _selectedContact.value = contact
        if (contact != null) {
            prefsManager.saveSelectedPhoneNumber(contact.phoneNumber)
            _forwardingActive.value = true
            prefsManager.saveForwardingStatus(true)
            updateForwardingStatus(true)
            if (PhoneSmsUtils.sendUssdCode(application, "*21*${contact.phoneNumber}#")) {
                logger.addLogEntry("Umleitung an ${contact.name} (${contact.phoneNumber}) wurde aktiviert.")
                updateNotification("Weiterleitung aktiv zu ${contact.name} (${contact.phoneNumber})")

            }
        } else {
            deactivateForwarding()
            updateForwardingStatus(false)
        }
    }


    /**
     * Deaktiviert die Weiterleitung.
     */
    fun deactivateForwarding() {
        _selectedContact.value = null
        prefsManager.clearSelection()
        _forwardingActive.value = false
        prefsManager.saveForwardingStatus(false)
        if (PhoneSmsUtils.sendUssdCode(application, "##21#")) {
            logger.addLogEntry("Weiterleitung wurde deaktiviert.")
            updateNotification("keine Weiterleitung aktiv")
            updateForwardingStatus(false)
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
                    prefsManager.getTestSmsText()
                )
            ) {
                logger.addLogEntry("Test-SMS '${prefsManager.getTestSmsText()}' an ${contact.name} (${receiver}) wurde versandt.")
            }
        }
    }

    fun onOwnNumberMissingDialogDismissed() {
        _showOwnNumberMissingDialog.value = false
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

    /**
     * Liest Kontakte aus dem Gerät.
     */
    private fun readContacts(filter: String): List<Contact> {
        val contacts = mutableSetOf<Contact>() // Verwendet Set zur Vermeidung von Duplikaten
        val contentResolver: ContentResolver = application.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        // Fügt eine WHERE-Klausel hinzu, um auf Datenbankebene zu filtern
        val selection = if (filter.isNotEmpty()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        } else null
        val selectionArgs = if (filter.isNotEmpty()) arrayOf("%$filter%", "%$filter%") else null

        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC" // Sortiert auf Datenbankebene
            )?.use { cursor ->
                val nameIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    val phoneNumber = cursor.getString(numberIndex).orEmpty()

                    // Bereinigt die Telefonnummer
                    val cleanedNumber = cleanPhoneNumber(phoneNumber)

                    if (name.isNotBlank() && cleanedNumber.isNotBlank()) {
                        contacts.add(Contact(name, cleanedNumber))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactsViewModel", "Error reading contacts", e)
            // Hier könnte eine Benachrichtigung an den Benutzer gesendet werden
        }

        return contacts.toList()
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

/**
 * Datenklasse für einen Kontakt.
 */
data class Contact(val name: String, val phoneNumber: String)

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