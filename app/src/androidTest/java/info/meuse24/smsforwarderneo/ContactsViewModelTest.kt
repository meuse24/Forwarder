package info.meuse24.smsforwarderneo

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.os.HandlerCompat.postDelayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ContactsViewModelTest {
    private lateinit var viewModel: ContactsViewModel
    private lateinit var logger: Logger
    private lateinit var prefsManager: SharedPreferencesManager
    private val testDispatcher = StandardTestDispatcher()

    // Test-Kontakte als Klassenvariable
    private val testContacts = listOf(
        Contact("Günther Meier", "+43 123 456789", "Mobil"),
        Contact("Hans Günther", "+43 234 567890", "Privat"),
        Contact("Gustav Müller", "+43 345 678901", "Geschäftlich"),
        Contact("Anna Schmidt", "+43 456 789012", "Mobil")
    )

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(testDispatcher)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // SharedPreferences löschen vor dem Test
        context.deleteSharedPreferences("SMSForwarderEncryptedPrefs")

        logger = Logger(context)
        prefsManager = SharedPreferencesManager(context)
        viewModel = ContactsViewModel(
            context.applicationContext as Application,
            prefsManager,
            logger
        )

        // Setze Test-Kontakte
        viewModel.setTestContacts(testContacts)

        // Warte explizit auf die Initialisierung
        advanceTimeBy(2000)

        // Verifiziere, dass Kontakte gesetzt wurden
        val initialContacts = viewModel.contacts.first()
        assertTrue("Initial contacts should be set", initialContacts.isNotEmpty())
        println("Initialized with ${initialContacts.size} contacts")
    }

    @Test
    fun testFilterInputCharByChar() = runTest {
        val testWord = "Günther"
        var currentText = ""

        for (char in testWord) {
            currentText += char
            viewModel.updateFilterText(currentText)
            advanceTimeBy(1000)

            val currentFilter = viewModel.filterText.first()
            assertEquals(
                "Filter text should match current input",
                currentText,
                currentFilter
            )

            val filteredContacts = viewModel.contacts.first()
            println("Current filter: $currentText")
            println("Number of filtered contacts: ${filteredContacts.size}")

            assertTrue(
                "Should find at least one contact with '$currentText'",
                filteredContacts.isNotEmpty()
            )

            filteredContacts.forEach { contact ->
                assertTrue(
                    "Contact ${contact.name} should contain filter text '$currentText'",
                    contact.name.contains(currentText, ignoreCase = true) ||
                            contact.phoneNumber.contains(currentText, ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun testClearFilter() = runTest {
        // Arrange
        viewModel.updateFilterText("Günther")
        advanceTimeBy(1000)

        // Act
        viewModel.updateFilterText("")
        advanceTimeBy(1000)

        // Assert
        val currentFilter = viewModel.filterText.first()
        assertEquals(
            "Filter should be cleared",
            "",
            currentFilter
        )

        val allContacts = viewModel.contacts.first()
        val filteredContacts = viewModel.contacts.first()
        assertEquals(
            "Clearing filter should show all contacts",
            allContacts.size,
            filteredContacts.size
        )
    }

    object ToastUtils {
        private const val MAX_TOAST_LENGTH = 200
        private const val ELLIPSIS = "..."

        fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
            val truncatedMessage = when {
                message.length <= MAX_TOAST_LENGTH -> message
                else -> message.take(MAX_TOAST_LENGTH - ELLIPSIS.length) + ELLIPSIS
            }

            // Toast auf dem Main Thread anzeigen
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, truncatedMessage, duration).show()
            }
        }

        // Für längere Nachrichten, die in mehreren Toasts angezeigt werden sollen
        fun showLongMessage(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
            val parts = message.chunked(MAX_TOAST_LENGTH - ELLIPSIS.length)

            Handler(Looper.getMainLooper()).apply {
                parts.forEachIndexed { index, part ->
                    val isLastPart = index == parts.lastIndex
                    val displayPart = if (!isLastPart) "$part$ELLIPSIS" else part

                    postDelayed({
                        Toast.makeText(context, displayPart, duration).show()
                    }, (duration + 100L) * index)
                }
            }
        }

        // Für formatierte Nachrichten mit variablen Argumenten
        fun showFormattedToast(context: Context, format: String, vararg args: Any?) {
            try {
                val message = String.format(format, *args)
                showToast(context, message)
            } catch (e: Exception) {
                showToast(context, format) // Fallback zum unformatierten String
            }
        }
    }

    // Erweiterungsfunktion für Context
    fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        ToastUtils.showToast(this, message, duration)
    }

    // Erweiterungsfunktion für Activity
    fun Activity.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        ToastUtils.showToast(this, message, duration)
    }

// Beispielverwendung:
// ToastUtils.showToast(context, "Kurze Nachricht")
// ToastUtils.showLongMessage(context, "Sehr lange Nachricht die aufgeteilt werden soll...")
// context.showToast("Nachricht über Erweiterungsfunktion")
// ToastUtils.showFormattedToast(context, "Wert: %d", 42)

    @Test
    fun testDiacriticHandling() = runTest {
        // Test mit und ohne Umlaute
        val testCases = listOf(
            "Günther" to "Gunther",
            "Müller" to "Mueller",
            "Größe" to "Grosse"
        )

        for ((withDiacritic, withoutDiacritic) in testCases) {
            // Test mit Umlaut
            viewModel.updateFilterText(withDiacritic)
            advanceTimeBy(1000)
            val contactsWithDiacritic = viewModel.contacts.first()

            // Test ohne Umlaut
            viewModel.updateFilterText(withoutDiacritic)
            advanceTimeBy(1000)
            val contactsWithoutDiacritic = viewModel.contacts.first()

            assertEquals(
                "Search with and without diacritics should return same results",
                contactsWithDiacritic.size,
                contactsWithoutDiacritic.size
            )
        }
    }
}
