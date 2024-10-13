package info.meuse24.smsforwarderneo

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import java.util.Locale

object InterfaceHolder {
    var myInterface: MyInterface? = null
}

interface MyInterface {
    fun showToast(data: String)
    fun addLogEntry(entry: String)
}

class MainActivity : ComponentActivity(), MyInterface {
    private lateinit var permissionHandler: PermissionHandler
    private lateinit var viewModel: ContactsViewModel
    private lateinit var logger: Logger
    private lateinit var prefsManager: SharedPreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InterfaceHolder.myInterface = this
        logger = Logger(this)
        PhoneSmsUtils.initialize(logger) // Initialisieren Sie PhoneSmsUtils mit dem Logger

        prefsManager = SharedPreferencesManager(this)
        viewModel = ViewModelProvider(
            this,
            ContactsViewModelFactory(application, prefsManager, logger)
        )[ContactsViewModel::class.java]
        permissionHandler = PermissionHandler(this)
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.all { it.value }
            permissionHandler.onRequestPermissionsResult(allGranted)
        }
        permissionHandler.initialize(requestPermissionLauncher)
        checkAndRequestPermissions()
        viewModel.loadSavedState()
        viewModel.loadOwnPhoneNumberIfEmpty(this)
        viewModel.showOwnNumberMissingDialog.observe(this) { shouldShow ->
            if (shouldShow) {
                showOwnNumberMissingDialog()
            }
        }
        SmsForegroundService.startService(this)
    }

    private fun checkAndRequestPermissions() {
        permissionHandler.checkAndRequestPermissions { granted ->
            if (granted) {
                initializeApp()
            }
        }
    }

    private fun initializeApp() {
        //requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        viewModel.initializeApp()
        setContent {
            UI(viewModel)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        logger.addLogEntry("Bildschirmausrichtung wurde geändert.")
        // Explizit den Zustand speichern und wiederherstellen
        viewModel.saveCurrentState()
        viewModel.loadSavedState()
    }

    override fun onDestroy() {
        //viewModel.deactivateForwarding()
        viewModel.saveCurrentState() // Neue Methode, die wir im ViewModel hinzufügen werden
        logger.addLogEntry("App wurde beendet.")
        SmsForegroundService.stopService(this)
        super.onDestroy()
    }

    override fun showToast(data: String) {

        // Verarbeite die Daten hier
        Toast.makeText(this, "$data", Toast.LENGTH_SHORT).show()
    }

    override fun addLogEntry(entry: String) {
        logger.addLogEntry(entry)
    }

    // @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun UI(viewModel: ContactsViewModel) {
        val navController = rememberNavController()
        val topBarTitle by viewModel.topBarTitle.collectAsState()

        Scaffold(
            topBar = { CustomTopAppBar(title = topBarTitle) },

            bottomBar = { BottomNavigationBar(navController) }
        ) { innerPadding ->
            NavHost(
                navController,
                startDestination = "start",
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                composable("start") {
                    HomeScreen(viewModel)
                }
                composable("setup") {
                    SettingsScreen()
                }
                composable("log") {
                    LogScreen()
                }
                composable("info") {
                    InfoScreen()
                }
            }
        }
    }

    private fun showOwnNumberMissingDialog() {
        AlertDialog.Builder(this)
            .setTitle("Eigene Telefonnummer fehlt")
            .setMessage("Bitte tragen Sie Ihre eigene Telefonnummer in den Einstellungen ein, bevor Sie eine Test-SMS versenden.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                viewModel.onOwnNumberMissingDialogDismissed()
            }
            .show()
    }

    @Composable
    fun CustomTopAppBar(title: String) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    @Composable
    fun BottomNavigationBar(navController: NavController) {
        val items = listOf("start", "setup", "log", "info")
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        NavigationBar(
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            items.forEach { screen ->
                NavigationBarItem(
                    icon = {
                        when (screen) {
                            "setup" -> Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Setup"
                            )

                            "log" -> Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = "Log"
                            )

                            "info" -> Icon(Icons.Filled.Info, contentDescription = "Info")
                            else -> Icon(Icons.Filled.Home, contentDescription = "Start")
                        }
                    },
                    label = {
                        Text(screen.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(
                                Locale.getDefault()
                            ) else it.toString()
                        })
                    },
                    selected = currentRoute == screen,
                    onClick = {
                        navController.navigate(screen) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun HomeScreen(viewModel: ContactsViewModel) {
        val contacts by viewModel.contacts.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val selectedContact by viewModel.selectedContact.collectAsState()
        val forwardingActive by viewModel.forwardingActive.collectAsState()
        val filterText by viewModel.filterText.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.loadContacts(filterText)
        }

        BoxWithConstraints {
            val isLandscape = maxWidth > maxHeight

            if (isLandscape) {
                LandscapeLayout(
                    viewModel = viewModel,
                    contacts = contacts,
                    isLoading = isLoading,
                    selectedContact = selectedContact,
                    forwardingActive = forwardingActive,
                    filterText = filterText
                )
            } else {
                PortraitLayout(
                    viewModel = viewModel,
                    contacts = contacts,
                    isLoading = isLoading,
                    selectedContact = selectedContact,
                    forwardingActive = forwardingActive,
                    filterText = filterText
                )
            }
        }
    }

    @Composable
    fun LandscapeLayout(
        viewModel: ContactsViewModel,
        contacts: List<Contact>,
        isLoading: Boolean,
        selectedContact: Contact?,
        forwardingActive: Boolean,
        filterText: String
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            ContactListBox(
                contacts = contacts,
                isLoading = isLoading,
                selectedContact = selectedContact,
                onSelectContact = viewModel::toggleContactSelection,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                FilterAndLogo(
                    filterText = filterText,
                    onFilterTextChange = {
                        viewModel.updateFilterText(it)
                        viewModel.loadContacts(it)
                    }
                )

                ForwardingStatus(forwardingActive, selectedContact)

                ControlButtons(
                    onDeactivateForwarding = viewModel::deactivateForwarding,
                    onSendTestSms = viewModel::sendTestSms,
                    isEnabled = selectedContact != null // Buttons sind nur aktiv, wenn ein Kontakt ausgewählt ist
                )
            }
        }
    }

    @Composable
    fun PortraitLayout(
        viewModel: ContactsViewModel,
        contacts: List<Contact>,
        isLoading: Boolean,
        selectedContact: Contact?,
        forwardingActive: Boolean,
        filterText: String
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            FilterAndLogo(
                filterText = filterText,
                onFilterTextChange = {
                    viewModel.updateFilterText(it)
                    viewModel.loadContacts(it)
                }
            )

            ContactListBox(
                contacts = contacts,
                isLoading = isLoading,
                selectedContact = selectedContact,
                onSelectContact = viewModel::toggleContactSelection,
                modifier = Modifier.weight(1f)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ForwardingStatus(forwardingActive, selectedContact)
                Spacer(modifier = Modifier.height(8.dp))
                ControlButtons(
                    onDeactivateForwarding = viewModel::deactivateForwarding,
                    onSendTestSms = viewModel::sendTestSms,
                    isEnabled = selectedContact != null // Buttons sind nur aktiv, wenn ein Kontakt ausgewählt ist
                )
            }
        }
    }

    @Composable
    fun FilterAndLogo(filterText: String, onFilterTextChange: (String) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RectangleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logofwd),
                    contentDescription = "App Icon",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                        .align(Alignment.Center)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = filterText,
                onValueChange = onFilterTextChange,
                label = { Text("Kontakt für Weiterleitung suchen") },
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp)
            )
            IconButton(onClick = { onFilterTextChange("") }) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = "Filter zurücksetzen"
                )
            }
        }
    }

    @Composable
    fun ContactListBox(
        contacts: List<Contact>,
        isLoading: Boolean,
        selectedContact: Contact?,
        onSelectContact: (Contact) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Box(modifier = modifier) {
            ContactList(
                contacts = contacts,
                isLoading = isLoading,
                selectedContact = selectedContact,
                onSelectContact = onSelectContact
            )
        }
    }

    @Composable
    fun ContactList(
        contacts: List<Contact>,
        isLoading: Boolean,
        selectedContact: Contact?,
        onSelectContact: (Contact) -> Unit
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                items(contacts) { contact ->
                    ContactItem(
                        contact = contact,
                        isSelected = contact == selectedContact,
                        onSelect = { onSelectContact(contact) }
                    )
                }
            }
        }
    }

    @Composable
    fun ContactItem(
        contact: Contact,
        isSelected: Boolean,
        onSelect: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .padding(vertical = 4.dp, horizontal = 16.dp)
        ) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = contact.phoneNumber,
                style = MaterialTheme.typography.bodySmall
            )
        }
        HorizontalDivider()
    }

    @Composable
    fun ForwardingStatus(forwardingActive: Boolean, selectedContact: Contact?) {
        Surface(
            color = if (forwardingActive) Color.Green else Color.Red,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Text(
                text = if (forwardingActive)
                    "Weiterleitung aktiv zu ${selectedContact?.phoneNumber}"
                else
                    "Weiterleitung inaktiv",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp)
            )
        }
    }

    @Composable
    fun ControlButtons(
        onDeactivateForwarding: () -> Unit,
        onSendTestSms: () -> Unit,
        isEnabled: Boolean // Neuer Parameter für den Aktivierungszustand
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onDeactivateForwarding,
                modifier = Modifier.weight(1f),
                enabled = isEnabled // Button wird nur aktiviert, wenn isEnabled true ist
            ) {
                Text(
                    "Weiterleitung deaktivieren",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Button(
                onClick = onSendTestSms,
                modifier = Modifier.weight(1f),
                enabled = isEnabled // Button wird nur aktiviert, wenn isEnabled true ist
            ) {
                Text(
                    "Test-SMS senden",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }


    @Composable
    fun SettingsScreen() {
        val filterText by viewModel.filterText.collectAsState()
        val isForwardingActive by viewModel.forwardingActive.collectAsState()
        val testSmsText by viewModel.testSmsText.collectAsState()
        val ownPhoneNumber by viewModel.ownPhoneNumber.collectAsState()
        val topBarTitle by viewModel.topBarTitle.collectAsState()
        val context = LocalContext.current
        val scrollState = rememberScrollState()
        val focusManager = LocalFocusManager.current

        var isFilterTextFocused by remember { mutableStateOf(false) }
        var isTestSmsTextFocused by remember { mutableStateOf(false) }
        var isOwnPhoneNumberFocused by remember { mutableStateOf(false) }
        var isTopBarTitleFocused by remember { mutableStateOf(false) }

        val isAnyFieldFocused = isFilterTextFocused || isTestSmsTextFocused ||
                isOwnPhoneNumberFocused || isTopBarTitleFocused

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            if (isAnyFieldFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { focusManager.clearFocus() }
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Tastatur einklappen",
                            tint = Color.Gray
                        )
                        Text(
                            "Tippen zum Einklappen der Tastatur",
                            color = Color.Gray
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {

                TextField(
                    value = filterText,
                    onValueChange = { viewModel.updateFilterText(it) },
                    label = { Text("Kontakte - Suchfilter") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFilterTextFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = testSmsText,
                    onValueChange = { viewModel.updateTestSmsText(it) },
                    label = { Text("Text des Test-SMS") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isTestSmsTextFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = ownPhoneNumber,
                        onValueChange = { viewModel.updateOwnPhoneNumber(it) },
                        label = { Text("Eigene Telefonnummer") },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { isOwnPhoneNumberFocused = it.isFocused }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            getPhoneNumber(context, viewModel::updateOwnPhoneNumber)                       }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Telefonnummer ermitteln"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = topBarTitle,
                    onValueChange = { viewModel.updateTopBarTitle(it) },
                    label = { Text("TopBar Titel") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isTopBarTitleFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = isForwardingActive,
                        onCheckedChange = null,
                        enabled = false
                    )
                    Text("Weiterleitung aktiv",
                        color = Color.Gray)
                }

                // Zusätzlicher Platz am Ende
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }


    private fun getPhoneNumber(context: Context, callback: (String) -> Unit) {
        // Überprüfen Sie zuerst die Berechtigung
        permissionHandler.checkAndRequestPermissions { granted ->
            if (granted) {
                // Wenn die Berechtigung erteilt wurde, versuchen Sie, die Nummer abzurufen
                val number = PhoneSmsUtils.getSimCardNumber(context)
                callback(number)
            } else {
                // Wenn die Berechtigung nicht erteilt wurde, informieren Sie den Benutzer
                Toast.makeText(
                    context,
                    "Berechtigung zum Lesen der Telefonnummer nicht erteilt",
                    Toast.LENGTH_SHORT
                ).show()
                callback("")
            }
        }
    }


    @Composable
    fun LogScreen() {
        val context = LocalContext.current
        //val configuration = LocalConfiguration.current
        val logEntriesHtml by viewModel.logEntriesHtml.collectAsState()
        val logEntries by viewModel.logEntries.collectAsState()

        // Laden der Logs beim Aufrufen des Screens
        LaunchedEffect(Unit) {
            viewModel.reloadLogs()
        }

        BoxWithConstraints {
            val isLandscape = maxWidth > maxHeight

            if (isLandscape) {
                // Landscape layout
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Left column: Log table
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxHeight()
                    ) {
                        LogTable(logEntriesHtml)
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Right column: Controls
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ShareLogIconButton(context, logEntries)
                            DeleteLogIconButton(context)
                            RefreshLogButton(viewModel)
                        }
                    }
                }
            } else {
                // Portrait layout
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LogTable(logEntriesHtml)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ShareLogIconButton(context, logEntries)
                        Spacer(modifier = Modifier.width(16.dp))
                        DeleteLogIconButton(context)
                        Spacer(modifier = Modifier.width(16.dp))
                        RefreshLogButton(viewModel)
                    }
                }
            }
        }
    }

    @Composable
    fun RefreshLogButton(viewModel: ContactsViewModel) {
        IconButton(
            onClick = {
                viewModel.reloadLogs()
            }
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Logs aktualisieren"
            )
        }
    }

    @Composable
    fun LogTable(logEntriesHtml: String) {
        if (logEntriesHtml.isEmpty()) {
            Text("Keine Log-Einträge vorhanden oder Fehler beim Laden der Logs.")
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                logger.addLogEntry("WebView-Fehler: ${error?.description}")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                logger.addLogEntry("WebView Seite geladen")
                            }
                        }
                        settings.apply {
                            javaScriptEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            setSupportZoom(true)
                        }
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(null, logEntriesHtml, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

//    @OptIn(ExperimentalMaterial3Api::class)
//    @Composable
//    fun ShareLogIconButton(context: Context, logEntries: String) {
//        val tooltipState = rememberTooltipState()
//        TooltipBox(
//            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
//            tooltip = {
//                PlainTooltip {
//                    Text("Log-Einträge teilen")
//                }
//            },
//            state = tooltipState,
//            content = {
//                IconButton(
//                    onClick = {
//                        if (logEntries.isNotEmpty()) {
//                            val shareIntent = Intent().apply {
//                                action = Intent.ACTION_SEND
//                                putExtra(Intent.EXTRA_TEXT, logEntries)
//                                type = "text/plain"
//                            }
//                            context.startActivity(
//                                Intent.createChooser(
//                                    shareIntent,
//                                    "Log-Einträge teilen"
//                                )
//                            )
//                        } else {
//                            Toast.makeText(
//                                context,
//                                "Keine Log-Einträge zum Teilen vorhanden",
//                                Toast.LENGTH_SHORT
//                            ).show()
//                        }
//                    }
//                ) {
//                    Icon(
//                        imageVector = Icons.Filled.Share,
//                        contentDescription = "Log-Einträge teilen"
//                    )
//                }
//            }
//        )
//    }
//
//    @OptIn(ExperimentalMaterial3Api::class)
//    @Composable
//    fun DeleteLogIconButton(context: Context) {
//        val tooltipState = rememberTooltipState()
//        TooltipBox(
//            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
//            tooltip = {
//                PlainTooltip {
//                    Text("Log löschen")
//                }
//            },
//            state = tooltipState,
//            content = {
//                IconButton(
//                    onClick = {
//                        logger.clearLog()
//                        Toast.makeText(
//                            context,
//                            "Log-Löschen-Funktion",
//                            Toast.LENGTH_SHORT
//                        ).show()
//                    }
//                ) {
//                    Icon(
//                        imageVector = Icons.Filled.Delete,
//                        contentDescription = "Log löschen"
//                    )
//                }
//            }
//        )
//    }

    @Composable
    fun ShareLogIconButton(context: Context, logEntries: String) {
        IconButton(
            onClick = {
                if (logEntries.isNotEmpty()) {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, logEntries)
                        type = "text/plain"
                    }
                    context.startActivity(
                        Intent.createChooser(
                            shareIntent,
                            "Log-Einträge teilen"
                        )
                    )
                } else {
                    Toast.makeText(
                        context,
                        "Keine Log-Einträge zum Teilen vorhanden",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = "Log-Einträge teilen"
            )
        }
    }

    @Composable
    fun DeleteLogIconButton(context: Context) {
        IconButton(
            onClick = {
                logger.clearLog()
                Toast.makeText(
                    context,
                    "Logs wurden gelöscht",
                    Toast.LENGTH_SHORT
                ).show()
            }
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Log löschen"
            )
        }
    }


    @Composable
    fun InfoScreen() {
        val scrollState = rememberScrollState()
        val context = LocalContext.current
        val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5
        val packageInfo = remember {
            context.packageManager.getPackageInfo(context.packageName, 0)}
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {

            // Logo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logofwd),
                        contentDescription = "App Icon",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                            .align(Alignment.Center)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "(C) 2024",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Günther Meusburger",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Version ${packageInfo.versionName} (${packageInfo.longVersionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // HTML-Inhalt
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = false
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                        webViewClient = WebViewClient()

                        // Setze die Hintergrundfarbe basierend auf dem Thema
                        setBackgroundColor(if (isDarkTheme) 0xFF121212.toInt() else 0xFFFFFFFF.toInt())

                        // Lade den HTML-Inhalt
                        loadDataWithBaseURL(
                            null,
                            getHtmlContent(isDarkTheme),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                update = { webView ->
                    // Aktualisiere die WebView, wenn sich das Theme ändert
                    webView.loadDataWithBaseURL(
                        null,
                        getHtmlContent(isDarkTheme),
                        "text/html",
                        "UTF-8",
                        null
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    private fun getHtmlContent(isDarkTheme: Boolean): String {
        val backgroundColor = if (isDarkTheme) "#121212" else "#FFFFFF"
        val textColor = if (isDarkTheme) "#E0E0E0" else "#333333"

        return """
    <!DOCTYPE html>
    <html lang="de">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>App Beschreibung</title>
        <style>
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen-Sans, Ubuntu, Cantarell, 'Helvetica Neue', sans-serif;
                font-size: 14px;
                line-height: 1.5;
                color: $textColor;
                background-color: $backgroundColor;
                margin: 0;
                padding: 10px;
            }
            h2 {
                font-size: 16px;
                margin-top: 20px;
                margin-bottom: 10px;
            }
            ul, ol {
                padding-left: 20px;
            }
            li {
                margin-bottom: 5px;
            }
        </style>
    </head>
    <body>
        <h2>Funktionsweise und Techniken</h2>
        <p>Diese App ermöglicht die Weiterleitung von eingehenden SMS und Telefonanrufen an eine von Ihnen gewählte Nummer. Hier sind einige Details zur Funktionsweise:</p>
        
        <ul>
            <li><strong>SMS-Weiterleitung:</strong> Verwendet einen BroadcastReceiver, der eingehende SMS abfängt und über einen WorkManager asynchron weiterleitet.</li>
            <li><strong>Anrufweiterleitung:</strong> Nutzt USSD-Codes, um die native Anrufweiterleitungsfunktion des Telefons zu aktivieren.</li>
            <li><strong>Benutzeroberfläche:</strong> Entwickelt mit Jetpack Compose für eine moderne, reaktive UI.</li>
            <li><strong>Datenspeicherung:</strong> Verwendet Encrypted SharedPreferences zur Speicherung von Benutzereinstellungen.</li>
            <li><strong>Hintergrunddienst:</strong> Ein Foreground Service sorgt für zuverlässigen Betrieb auch bei App-Inaktivität.</li>
            <li><strong>Berechtigungshandling:</strong> Implementiert dynamische Berechtigungsanfragen für Android 10.0+.</li>
        </ul>

        <h2>Hauptfunktionen</h2>
        <ol>
            <li><strong>Kontaktauswahl:</strong> Wählen Sie einen Kontakt aus Ihrer Kontaktliste für die Weiterleitung.</li>
            <li><strong>SMS-Weiterleitung:</strong> Automatische Weiterleitung eingehender SMS an die gewählte Nummer.</li>
            <li><strong>Anrufweiterleitung:</strong> Aktivierung der Anrufweiterleitung über USSD-Codes.</li>
            <li><strong>Test-SMS:</strong> Senden Sie eine Test-SMS, um die Weiterleitung zu überprüfen.</li>
            <li><strong>Protokollierung:</strong> Alle Weiterleitungsaktivitäten werden in einem Logbuch festgehalten.</li>
        </ol>

        <h2>Technische Details</h2>
        <ul>
            <li><strong>Programmiersprache:</strong> Kotlin</li>
            <li><strong>UI-Framework:</strong> Jetpack Compose</li>
            <li><strong>Nebenläufigkeit:</strong> Coroutines und Flow für asynchrone Operationen</li>
            <li><strong>Architektur:</strong> MVVM (Model-View-ViewModel) mit Repository-Pattern</li>
            <li><strong>Dependency Injection:</strong> Koin (leichtgewichtige DI-Lösung)</li>
        </ul>

        <p>Die App wurde unter Berücksichtigung moderner Android-Entwicklungspraktiken und Datenschutzrichtlinien entwickelt. Sie benötigt bestimmte Berechtigungen, um ordnungsgemäß zu funktionieren, und arbeitet im Hintergrund, um eine zuverlässige Weiterleitung zu gewährleisten.</p>
    </body>
    </html>
    """.trimIndent()
    }}


