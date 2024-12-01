package info.meuse24.smsforwarderneo

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Textsms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import info.meuse24.smsforwarderneo.AppContainer.prefsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: ContactsViewModel by viewModels { ContactsViewModel.Factory() }
    private val _isLoading = MutableStateFlow(true)
    private val _loadingError = MutableStateFlow<String?>(null)
    private lateinit var permissionHandler: PermissionHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) {
            // Prüfe ob irgendeine Art der Weiterleitung aktiv ist
            if (viewModel.forwardingActive.value || viewModel.forwardSmsToEmail.value) {
                // Zeige Exit-Dialog mit Optionen zum Deaktivieren/Beibehalten
                viewModel.onShowExitDialog()
            } else {
                // Wenn keine Weiterleitung aktiv ist, beende direkt
                finish()
            }
        }

        // Initialisiere PermissionHandler direkt
        permissionHandler = PermissionHandler(this)

        lifecycleScope.launch {
            try {
                // Warte auf Basis-Initialisierung
                AppContainer.isBasicInitialized.first { it }

                // Führe Activity-Initialisierung durch
                AppContainer.initializeWithActivity(this@MainActivity)

                // Setze Content
                setContent {
                    MaterialTheme {
                        val isLoading by _isLoading.collectAsState()
                        val error by _loadingError.collectAsState()
                        val isFullyInitialized by AppContainer.isInitialized.collectAsState()

                        when {
                            !isFullyInitialized || isLoading -> {
                                LoadingScreen(error = error)
                            }
                            else -> {
                                UI(viewModel)
                            }
                        }
                    }
                }

                // Starte vollständige App-Initialisierung
                initializeApp()

            } catch (e: Exception) {
                _loadingError.value = "Initialisierungsfehler: ${e.message}"
                Log.e("MainActivity", "Error during initialization", e)
            }
        }
    }

    private fun initializeApp() {
        lifecycleScope.launch {
            try {
                _loadingError.value = "Initialisiere App..."

                // Warte auf vollständige AppContainer Initialisierung
                AppContainer.isInitialized.first { it }

                // Prüfe und fordere Berechtigungen an
                permissionHandler.checkPermissions(
                    onGranted = {
                        // Starte Services und weitere Initialisierungen
                        SmsForegroundService.startService(this@MainActivity)

                        // Füge kleine Verzögerung hinzu um sicherzustellen, dass
                        // Berechtigungen vollständig gewährt wurden
                        lifecycleScope.launch {
                            delay(500) // 500ms Verzögerung
                            viewModel.initialize() // Dies lädt nun die Kontakte
                        }

                        // Verstecke LoadingScreen
                        _isLoading.value = false
                    },
                    onDenied = {
                        _loadingError.value = "Erforderliche Berechtigungen wurden nicht erteilt"
                        LoggingManager.logWarning(
                            component = "MainActivity",
                            action = "PERMISSIONS_DENIED",
                            message = "Berechtigungen verweigert"
                        )
                    }
                )

            } catch (e: Exception) {
                _loadingError.value = "Fehler bei der Initialisierung: ${e.message}"
                LoggingManager.logError(
                    component = "MainActivity",
                    action = "INIT_ERROR",
                    message = "App-Initialisierung fehlgeschlagen",
                    error = e
                )
            }
        }
    }

    @Composable
    private fun LoadingScreen(error: String?) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // App Logo
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logofwd2),
                        contentDescription = "App Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                    )
                }

                Text(
                    text = "SMS/TEL Forwarder",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (error == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 16.dp)
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            text = "App wird initialisiert...",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Erforderliche Berechtigungen:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            listOf(
                                "Kontakte - Für die Auswahl des Weiterleitungsziels",
                                "SMS - Zum Empfangen und Weiterleiten von Nachrichten",
                                "Telefon - Für die Anrufweiterleitung",
                                "Batterieoptimierung - Für Stabilität im Hintergrund"
                            ).forEach { permission ->
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = permission,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )

                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )

                        Button(
                            onClick = { finish() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("App beenden")
                            }
                        }
                    }
                }
            }
        }
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            LoggingManager.logInfo(
                component = "MainActivity",
                action = "CONFIG_CHANGED",
                message = "Bildschirmausrichtung wurde geändert",
                details = mapOf(
                    "orientation" to if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait",
                    "screenWidthDp" to resources.configuration.screenWidthDp,
                    "screenHeightDp" to resources.configuration.screenHeightDp
                )
            )
            viewModel.saveCurrentState()
            //viewModel.loadSavedState()
        } catch (e: UninitializedPropertyAccessException) {
            // ViewModel noch nicht initialisiert - ignorieren
            LoggingManager.logInfo(
                component = "MainActivity",
                action = "CONFIG_CHANGED_SKIP",
                message = "Konfigurationsänderung übersprungen - ViewModel noch nicht initialisiert"
            )
        }
    }

    override fun onDestroy() {
        //viewModel.deactivateForwarding()
        //viewModel.saveCurrentState() // Neue Methode, die wir im ViewModel hinzufügen werden
        LoggingManager.logInfo(
            component = "MainActivity",
            action = "DESTROY",
            message = "App wird beendet",
            details = mapOf(
                "forwardingActive" to viewModel.forwardingActive.value,
                "timestamp" to System.currentTimeMillis()
            )
        )
        if (!prefsManager.getKeepForwardingOnExit()) {
            SmsForegroundService.stopService(this)
        }

        super.onDestroy()
    }

    @Composable
    fun UI(viewModel: ContactsViewModel) {
        val navController = rememberNavController()
        val topBarTitle by viewModel.topBarTitle.collectAsState()
        val navigationTarget by viewModel.navigationTarget.collectAsState()
        val showExitDialog by viewModel.showExitDialog.collectAsState()
        val showProgressDialog by viewModel.showProgressDialog.collectAsState()
        val errorState by viewModel.errorState.collectAsState()
        val showOwnNumberMissingDialog by viewModel.showOwnNumberMissingDialog.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        val coroutineScope = rememberCoroutineScope()

        // Cleanup Effect
        LaunchedEffect(Unit) {
            viewModel.cleanupCompleted.collect {
                finish()
            }
        }

        // Initialisieren Sie den SnackbarManager mit dem State und Scope
        LaunchedEffect(snackbarHostState, coroutineScope) {
            SnackbarManager.setSnackbarState(snackbarHostState, coroutineScope)
        }

        // Navigation Effect
        LaunchedEffect(navigationTarget) {
            navigationTarget?.let { target ->
                navController.navigate(target) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                }
                viewModel.onNavigated()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {  // Äußere Box für absolutes Positioning
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
                    composable("mail") {
                        MailScreen()
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

            // Snackbar außerhalb des Scaffolds aber innerhalb der Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)  // Ausrichtung oben
                    .padding(top = 40.dp)  // Abstand zur TopBar
                    .offset(y = 8.dp)  // Feinjustierung
            ) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            if (showOwnNumberMissingDialog) {
                OwnNumberMissingDialog(
                    onDismiss = { viewModel.hideOwnNumberMissingDialog() },
                    onNavigateToSettings = {
                        viewModel.hideOwnNumberMissingDialog()
                        viewModel.navigateToSettings()
                    }
                )
            }

            // Exit Dialog
            if (showExitDialog) {
                val selectedContact by viewModel.selectedContact.collectAsState() // Hier collectAsState verwenden
                ExitDialog(
                    contact = selectedContact,
                    onDismiss = { viewModel.hideExitDialog() },
                    onConfirm = { keepForwarding ->
                        viewModel.hideExitDialog()
                        viewModel.startCleanup(keepForwarding)
                    },
                    onSettings = {
                        viewModel.hideExitDialog()
                        viewModel.navigateToSettings()
                    }
                )
            }

            // Progress Dialog
            if (showProgressDialog) {
                CleanupProgressDialog()
            }

            // Error Dialog
            errorState?.let { error ->
                CleanupErrorDialog(
                    error = error,
                    onRetry = {
                        viewModel.clearErrorState()
                        viewModel.startCleanup(false)
                    },
                    onIgnore = {
                        viewModel.clearErrorState()
                        finish()
                    },
                    onDismiss = {
                        viewModel.clearErrorState()
                    }
                )
            }
        }
    }

    @Composable
    private fun OwnNumberMissingDialog(
        onDismiss: () -> Unit,
        onNavigateToSettings: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Eigene Telefonnummer fehlt")
            },
            text = {
                Text(
                    "Bitte tragen Sie Ihre eigene Telefonnummer in den Einstellungen ein.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onNavigateToSettings
                ) {
                    Text("Zu den Einstellungen")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }
            }
        )
    }

    @Composable
    private fun ExitDialog(
        contact: Contact?,
        onDismiss: () -> Unit,
        onConfirm: (Boolean) -> Unit,
        onSettings: () -> Unit
    ) {
        var keepForwarding by remember { mutableStateOf(prefsManager.getKeepForwardingOnExit()) }

        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("App beenden")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    contact?.let {
                        Text(
                            text = "Aktive Weiterleitung zu: ${it.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (contact != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = keepForwarding,
                                onCheckedChange = {
                                    keepForwarding = it
                                    viewModel.updateKeepForwardingOnExit(it)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Weiterleitung beim Beenden beibehalten",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirm(keepForwarding) }
                ) {
                    Text("Beenden")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onSettings) {
                        Text("Einstellungen")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Abbrechen")
                    }
                }
            }
        )
    }

    @Composable
    private fun CleanupProgressDialog() {
        AlertDialog(
            onDismissRequest = { /* Nicht abbrechbar */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            title = { Text("Beende App") },
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Bitte warten...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = { /* Keine Buttons während des Cleanups */ }
        )
    }

    @Composable
    private fun CleanupErrorDialog(
        error: ContactsViewModel.ErrorDialogState,
        onRetry: () -> Unit,
        onIgnore: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val (title, message) = when (error) {
            is ContactsViewModel.ErrorDialogState.DeactivationError ->
                Pair("Deaktivierung fehlgeschlagen", error.message)

            is ContactsViewModel.ErrorDialogState.TimeoutError ->
                Pair(
                    "Zeitüberschreitung",
                    "Die Deaktivierung der Weiterleitung dauert zu lange."
                )

            is ContactsViewModel.ErrorDialogState.GeneralError ->
                Pair(
                    "Fehler",
                    "Ein unerwarteter Fehler ist aufgetreten: ${error.error.message}"
                )
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = onRetry) {
                    Text("Wiederholen")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onIgnore) {
                        Text("Ignorieren")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Abbrechen")
                    }
                }
            }
        )
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
        val items = listOf("start", "mail", "setup", "log", "info")
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

                            "mail" -> Icon(
                                Icons.Filled.Email,
                                contentDescription = "Mail"
                            )

                            "log" -> Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = "Log"
                            )

                            "info" -> Icon(
                                Icons.Filled.Info,
                                contentDescription = "Info"
                            )

                            else -> Icon(
                                Icons.Filled.Home,
                                contentDescription = "Start"
                            )
                        }
                    },
                    label = {
                        Text(
                            when (screen) {
                                "start" -> "Start"
                                "mail" -> "Mail"
                                "setup" -> "Setup"
                                "log" -> "Log"
                                else -> "Info"
                            }
                        )
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
        val selectedContact by viewModel.selectedContact.collectAsState()
        val forwardingActive by viewModel.forwardingActive.collectAsState()
        val filterText by viewModel.filterText.collectAsState()

        // Initialisierung beim ersten Laden
        LaunchedEffect(Unit) {
            viewModel.initialize()
        }

        // Filter neu anwenden beim Betreten des Screens
        LaunchedEffect(Unit) {
            if (filterText.isNotEmpty()) {
                viewModel.applyCurrentFilter()
            }
        }

        BoxWithConstraints {
            val isLandscape = maxWidth > maxHeight

            if (isLandscape) {
                LandscapeLayout(
                    viewModel = viewModel,
                    contacts = contacts,
                    selectedContact = selectedContact,
                    forwardingActive = forwardingActive,
                    filterText = filterText
                )
            } else {
                PortraitLayout(
                    viewModel = viewModel,
                    contacts = contacts,
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
        selectedContact: Contact?,
        forwardingActive: Boolean,
        filterText: String
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(0.dp)
        ) {
            ContactListBox(
                contacts = contacts,
                selectedContact = selectedContact,
                onSelectContact = viewModel::toggleContactSelection,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            Spacer(modifier = Modifier.width(8.dp))

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
                    },
                    forwardingActive = forwardingActive,
                    onDeactivateForwarding = viewModel::deactivateForwarding
                )

                ForwardingStatus(forwardingActive, selectedContact)

                ControlButtons(
                    onDeactivateForwarding = viewModel::deactivateForwarding,
                    onSendTestSms = viewModel::sendTestSms,
                    isEnabled = selectedContact != null
                )
            }
        }
    }

    @Composable
    fun PortraitLayout(
        viewModel: ContactsViewModel,
        contacts: List<Contact>,
        selectedContact: Contact?,
        forwardingActive: Boolean,
        filterText: String
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            FilterAndLogo(
                filterText = filterText,
                onFilterTextChange = {
                    viewModel.updateFilterText(it)
                },
                forwardingActive = forwardingActive,
                onDeactivateForwarding = viewModel::deactivateForwarding
            )

            ContactListBox(
                contacts = contacts,
                selectedContact = selectedContact,
                onSelectContact = viewModel::toggleContactSelection,
                modifier = Modifier.weight(1f)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ForwardingStatus(forwardingActive, selectedContact)
                Spacer(modifier = Modifier.height(4.dp))
                ControlButtons(
                    onDeactivateForwarding = viewModel::deactivateForwarding,
                    onSendTestSms = viewModel::sendTestSms,
                    isEnabled = selectedContact != null
                )
            }
        }
    }

    @Composable
    fun FilterAndLogo(
        filterText: String,
        onFilterTextChange: (String) -> Unit,
        forwardingActive: Boolean,
        onDeactivateForwarding: () -> Unit
    ) {
        val rotation = remember { Animatable(0f) }
        var hasAnimated by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        var isFilterFocused by remember { mutableStateOf(false) }

        // Animiere nur beim ersten Start, nicht bei jedem Recompose
        LaunchedEffect(Unit) {
            if (!hasAnimated) {
                rotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(
                        durationMillis = 2000,
                        easing = LinearEasing
                    )
                )
                hasAnimated = true
            }
        }

// Speichere den Orientierungszustand
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 4.dp,
                    horizontal = if (isLandscape) 8.dp else 4.dp
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = forwardingActive,
                        onCheckedChange = { checked ->
                            if (checked) {
                                SnackbarManager.showInfo(
                                    "Bitte wählen Sie einen Kontakt aus der Liste aus, um die Weiterleitung zu aktivieren")
                            } else {
                                onDeactivateForwarding()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SMS an SMS und Anrufe weiterleiten",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .graphicsLayer(rotationZ = rotation.value)  // Keine Animation, nur der Wert
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logofwd2),
                        contentDescription = "App Icon",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
                            .align(Alignment.Center)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = filterText,
                        onValueChange = onFilterTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                isFilterFocused = it.isFocused
                            },
                        label = { Text("Kontakt suchen") },
                        placeholder = { Text("Namen oder Nummer eingeben") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                            }
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Suchen",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (filterText.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        onFilterTextChange("")
                                        focusManager.clearFocus()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Filter löschen",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun ContactListBox(
        contacts: List<Contact>,
        selectedContact: Contact?,
        onSelectContact: (Contact) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Box(modifier = modifier) {
            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Keine Kontakte gefunden",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                .clickable {
                    val ownNumber = viewModel.ownPhoneNumber.value

                    if (ownNumber.isBlank()) {
                        viewModel.showOwnNumberMissingDialog()
                    } else {
                        onSelect()

                    }
                }
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .padding(vertical = 4.dp, horizontal = 16.dp)
        ) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = contact.description,
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
        isEnabled: Boolean
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onDeactivateForwarding,
                modifier = Modifier.weight(1f),
                enabled = isEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Deaktivieren",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Button(
                onClick = onSendTestSms,
                modifier = Modifier.weight(1f),
                enabled = isEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Textsms,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Test-SMS",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    @Composable
    fun MailScreen() {
        //val viewModel: ContactsViewModel = viewModel
        val emailAddresses by viewModel.emailAddresses.collectAsState()
        val newEmailAddress by viewModel.newEmailAddress.collectAsState()
        val forwardSmsToEmail by viewModel.forwardSmsToEmail.collectAsState()
        var isEmailAddressFocused by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(4.dp)
        ) {


            // Checkbox mit deaktiviertem Zustand wenn keine E-Mails vorhanden
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = forwardSmsToEmail,
                        onCheckedChange = { checked ->
                            if (emailAddresses.isNotEmpty()) {
                                viewModel.updateForwardSmsToEmail(checked)
                            } else if (checked) {
                                SnackbarManager.showWarning("Bitte fügen Sie zuerst E-Mail-Adressen hinzu")
                            }
                        },
                        enabled = emailAddresses.isNotEmpty()
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SMS an alle E-Mails weiterleiten",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (emailAddresses.isNotEmpty())
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

            }

            Spacer(modifier = Modifier.height(16.dp))

            // Eingabefeld und Add-Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newEmailAddress,
                    onValueChange = { viewModel.updateNewEmailAddress(it) },
                    label = { Text("Neue E-Mail-Adresse") },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { isEmailAddressFocused = it.isFocused },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            viewModel.addEmailAddress()
                        }
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.addEmailAddress()
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Hinzufügen",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // E-Mail-Liste
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                if (emailAddresses.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Keine E-Mail-Adressen vorhanden",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        emailAddresses.forEachIndexed { index, email ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = email,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                // Buttons für Test-Mail und Löschen
                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { viewModel.sendTestEmail(email) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Email,
                                            contentDescription = "Test-Mail senden",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeEmailAddress(email) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Löschen",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            if (index < emailAddresses.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }

            // Zusätzlicher Spacer am Ende für besseres Scrolling
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    @Composable
    fun SettingsScreen() {
        val scrollState = rememberScrollState()
        LocalFocusManager.current
        var isAnyFieldFocused by remember { mutableStateOf(false) }
        var showPinDialog by remember { mutableStateOf(false) }
        var showChangePinDialog by remember { mutableStateOf(false) }
        val sectionTitleStyle = MaterialTheme.typography.titleMedium

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {

            PhoneSettingsSection(
                viewModel = viewModel,
                onFocusChanged = { isAnyFieldFocused = it },
                sectionTitleStyle = sectionTitleStyle)

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            AppSettingsSection(
                viewModel = viewModel,
                onFocusChanged = { isAnyFieldFocused = it },
                sectionTitleStyle = sectionTitleStyle
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            EmailSettingsSection(
                viewModel = viewModel,
                sectionTitleStyle = sectionTitleStyle
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Neue Log Settings Section
            LogSettingsSection(
                sectionTitleStyle = sectionTitleStyle,
                onDeleteLogs = { showPinDialog = true },
                onChangePin = { showChangePinDialog = true }
            )

            // Die existierenden PIN-Dialoge aus dem LogScreen
            if (showPinDialog) {
                PinDialog(
                    onPinCorrect = {
                        AppContainer.logger.clearLog()
                        LoggingManager.logInfo(
                            component = "SettingsScreen",
                            action = "CLEAR_LOGS",
                            message = "Log-Einträge wurden gelöscht"
                        )
                        SnackbarManager.showSuccess("Logs wurden gelöscht")
                        showPinDialog = false
                    },
                    onDismiss = { showPinDialog = false }
                )
            }

            if (showChangePinDialog) {
                ChangePinDialog(
                    onDismiss = { showChangePinDialog = false }
                )
            }

        }
    }

    @Composable
    private fun LogSettingsSection(
        sectionTitleStyle: TextStyle,
        onDeleteLogs: () -> Unit,
        onChangePin: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Log-Einstellungen",
                style = sectionTitleStyle,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Log-Datei löschen",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Löscht alle Protokolleinträge",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDeleteLogs) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Logs löschen",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .alpha(0.5f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PIN ändern",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "PIN für Löschfunktion ändern",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onChangePin) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "PIN ändern",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    @Composable
    private fun PhoneSettingsSection(
        viewModel: ContactsViewModel,
        onFocusChanged: (Boolean) -> Unit,
        sectionTitleStyle: TextStyle
    ) {
        val context = LocalContext.current
        val ownPhoneNumber by viewModel.ownPhoneNumber.collectAsState()
        val isForwardingActive by viewModel.forwardingActive.collectAsState()
        val countryCode by viewModel.countryCode.collectAsState()
        val countryCodeSource by viewModel.countryCodeSource.collectAsState()

        var isOwnPhoneNumberFocused by remember { mutableStateOf(false) }

        LaunchedEffect(isOwnPhoneNumberFocused) {
            onFocusChanged(isOwnPhoneNumberFocused)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Telefon-Einstellungen",
                style = sectionTitleStyle,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
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
                        getPhoneNumber(context, viewModel::updateOwnPhoneNumber)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Telefonnummer ermitteln"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isForwardingActive,
                        onCheckedChange = null,
                        enabled = false
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Weiterleitung aktiv")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column {
                Text(
                    text = "Erkannte Ländervorwahl",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(text = PhoneSmsUtils.getCountryNameForCode(countryCode))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Quelle: $countryCodeSource",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AppSettingsSection(
        viewModel: ContactsViewModel,
        onFocusChanged: (Boolean) -> Unit,
        sectionTitleStyle: TextStyle
    ) {
        val filterText by viewModel.filterText.collectAsState()
        val testSmsText by viewModel.testSmsText.collectAsState()
        val testEmailText by viewModel.testEmailText.collectAsState()
        val topBarTitle by viewModel.topBarTitle.collectAsState()

        var isFilterTextFocused by remember { mutableStateOf(false) }
        var isTestSmsTextFocused by remember { mutableStateOf(false) }
        var isTestEmailTextFocused by remember { mutableStateOf(false) }
        var isTopBarTitleFocused by remember { mutableStateOf(false) }

        LaunchedEffect(
            isFilterTextFocused,
            isTestSmsTextFocused,
            isTestEmailTextFocused,
            isTopBarTitleFocused
        ) {
            onFocusChanged(
                isFilterTextFocused || isTestSmsTextFocused ||
                        isTestEmailTextFocused || isTopBarTitleFocused
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "App-Einstellungen",
                style = sectionTitleStyle,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = filterText,
                onValueChange = { viewModel.updateFilterText(it) },
                label = { Text("Kontakte - Suchfilter") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFilterTextFocused = it.isFocused }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = testSmsText,
                onValueChange = { viewModel.updateTestSmsText(it) },
                label = { Text("Text der Test-SMS") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isTestSmsTextFocused = it.isFocused }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = testEmailText,
                onValueChange = { viewModel.updateTestEmailText(it) },
                label = { Text("Text der Test-Email") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isTestEmailTextFocused = it.isFocused }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = topBarTitle,
                onValueChange = { viewModel.updateTopBarTitle(it) },
                label = { Text("App Titel") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isTopBarTitleFocused = it.isFocused }
            )
        }
    }

    @Composable
    private fun EmailSettingsSection(
        viewModel: ContactsViewModel,
        sectionTitleStyle: TextStyle
    ) {
        val smtpHost by viewModel.smtpHost.collectAsState()
        val smtpPort by viewModel.smtpPort.collectAsState()
        val smtpUsername by viewModel.smtpUsername.collectAsState()
        val smtpPassword by viewModel.smtpPassword.collectAsState()
        var isPasswordVisible by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "E-Mail-Einstellungen",
                style = sectionTitleStyle,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = smtpHost,
                onValueChange = { viewModel.updateSmtpSettings(it, smtpPort, smtpUsername, smtpPassword) },
                label = { Text("SMTP Server") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = smtpPort.toString(),
                onValueChange = {
                    val newPort = it.toIntOrNull() ?: smtpPort
                    viewModel.updateSmtpSettings(smtpHost, newPort, smtpUsername, smtpPassword)
                },
                label = { Text("TLS Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = smtpUsername,
                onValueChange = { viewModel.updateSmtpSettings(smtpHost, smtpPort, it, smtpPassword) },
                label = { Text("Benutzername/Email-Adresse") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (!smtpHost.equals("smtp.world4you.com", ignoreCase = true)) {
                OutlinedTextField(
                    value = smtpPassword,
                    onValueChange = { viewModel.updateSmtpSettings(smtpHost, smtpPort, smtpUsername, it) },
                    label = { Text("Passwort") },
                    visualTransformation = if (isPasswordVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible)
                                    Icons.Default.Visibility
                                else
                                    Icons.Default.VisibilityOff,
                                contentDescription = if (isPasswordVisible)
                                    "Passwort verbergen"
                                else
                                    "Passwort anzeigen"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = {
                        viewModel.updateSmtpSettings(
                            "smtp.gmail.com",
                            587,
                            "",
                            ""
                        )
                        SnackbarManager.showSuccess("Benutzername und App-spezifisches Passwort eingeben.")
                    }
                ) {
                    Text("Gmail")
                }
                Button(
                    onClick = {
                        viewModel.updateSmtpSettings(
                            "mail.gmx.net",
                            587,
                            "",
                            ""
                        )
                        SnackbarManager.showSuccess("Email-Adresse und Passwort eingeben.")
                    }
                ) {
                    Text("GMX")
                }

                Button(
                    onClick = {
                        viewModel.updateSmtpSettings(
                            "smtp.world4you.com",
                            587,
                            "smsfwd@meuse24.info",
                            "V8Rv4TqM!5d8tEM"
                        )
                        SnackbarManager.showSuccess("Es wird ein meuse24-Mailserver verwendet (Verwendung ohne Gewähr!).")
                    }
                ) {
                    Text("Intern")
                }
            }

            Text(
                text = "Hinweis: Für Gmail wird ein App-spezifisches Passwort benötigt und für GMX muss IMAP oder POP3 in den Einstellungen aktiviert werden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }


    private fun getPhoneNumber(context: Context, callback: (String) -> Unit) {
        AppContainer.permissionHandler.checkPermissions(
            onGranted = {
                // Wenn die Berechtigung erteilt wurde, versuchen Sie die Nummer abzurufen
                val number = PhoneSmsUtils.getSimCardNumber(context)
                LoggingManager.logInfo(
                    component = "MainActivity",
                    action = "PHONE_NUMBER_RETRIEVAL",
                    message = "Telefonnummer wurde ermittelt",
                    details = mapOf(
                        "numberFound" to (number.isNotEmpty()),
                        "numberLength" to number.length,
                        "source" to "SIM"
                    )
                )
                callback(number)
            },
            onDenied = {
                LoggingManager.logWarning(
                    component = "MainActivity",
                    action = "PHONE_NUMBER_RETRIEVAL",
                    message = "Berechtigung zum Lesen der Telefonnummer nicht erteilt",
                    details = mapOf(
                        "permissionState" to "denied"
                    )
                )
                // Wenn die Berechtigung nicht erteilt wurde, informieren Sie den Benutzer
                SnackbarManager.showError(
                    "Berechtigung zum Lesen der Telefonnummer nicht erteilt",
                    duration = SnackbarManager.Duration.LONG
                )
                callback("")
            }
        )
    }

    @Composable
    fun LogScreen() {
        val context = LocalContext.current
        val logEntriesHtml by viewModel.logEntriesHtml.collectAsState()
        val logEntries by viewModel.logEntries.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.reloadLogs()
        }

        BoxWithConstraints {
            val isLandscape = maxWidth > maxHeight

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()

                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        LogTable(logEntriesHtml)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Icon-Spalte ohne weight, nur mit der benötigten Breite
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ShareLogIconButton(context, logEntries)
                            //DeleteLogIconButton()
                            RefreshLogButton(viewModel)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()

                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LogTable(logEntriesHtml)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ShareLogIconButton(context, logEntries)
                        Spacer(modifier = Modifier.width(16.dp))
                        RefreshLogButton(viewModel)
                    }
                }
            }
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
                                LoggingManager.logError(
                                    component = "LogScreen",
                                    action = "WEBVIEW_ERROR",
                                    message = "Fehler beim Laden der WebView: ${error?.description}",
                                    details = mapOf(
                                        "errorCode" to (error?.errorCode ?: -1),
                                        "url" to (request?.url?.toString() ?: "unknown"),
                                        "description" to (error?.description?.toString() ?: "unknown")
                                    )
                                )
                            }
                        }
                        settings.apply {
                            javaScriptEnabled = false
                            loadWithOverviewMode = false  // Geändert zu false
                            useWideViewPort = false      // Geändert zu false
                            builtInZoomControls = true
                            displayZoomControls = false
                            setSupportZoom(true)
                        }

                        val css = """
                    <style>
                        html, body {
                            margin: 0;
                            padding: 0;
                            width: 100%;
                            overflow-x: hidden;
                            font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                            font-size: 11px;
                            line-height: 1.2;
                        }
                        table {
                            width: 100%;
                            border-collapse: collapse;
                            table-layout: fixed;
                        }
                        th {
                            background-color: #f0f0f0;
                            position: sticky;
                            top: 0;
                            z-index: 1;
                            padding: 2px 4px;
                            text-align: left;
                            border: none;
                            font-size: 10px;
                            overflow: hidden;
                            text-overflow: ellipsis;
                        }
                        td {
                            padding: 1px 4px;
                            border: none;
                            border-bottom: 1px solid #eee;
                            vertical-align: top;
                            font-size: 10px;
                            max-width: 0;
                            overflow: hidden;
                            text-overflow: ellipsis;
                            word-wrap: break-word;
                        }
                        .time-column {
                            width: 90px;
                            min-width: 90px;
                            max-width: 90px;
                        }
                        .time-cell {
                            white-space: nowrap;
                            overflow: hidden;
                            text-overflow: ellipsis;
                        }
                        .date {
                            font-weight: normal;
                            font-size: 9px;
                        }
                        .time {
                            color: #666;
                            font-size: 9px;
                        }
                        .entry-column {
                            word-break: break-all;
                            white-space: normal;
                            width: auto;
                            overflow-wrap: break-word;
                        }
                        .sms-forward {
                            color: #d32f2f;
                            font-weight: 500;
                        }
                        tr:hover {
                            background: #f8f8f8;
                        }
                        @media (prefers-color-scheme: dark) {
                            body {
                                background-color: #121212;
                                color: #ffffff;
                            }
                            th {
                                background-color: #1e1e1e;
                                color: #ffffff;
                            }
                            td {
                                border-bottom-color: #333;
                            }
                            tr:hover {
                                background: #1a1a1a;
                            }
                            .time {
                                color: #999;
                            }
                        }
                    </style>
                    """

                        val modifiedHtml = logEntriesHtml.replace(
                            "<table",
                            "$css<table class=\"log-table\""
                        ).replace(
                            "<td>",
                            "<td class=\"entry-col\">"
                        )

                        loadDataWithBaseURL(null, modifiedHtml, "text/html", "UTF-8", null)
                    }
                },
                update = { webView ->
                    val modifiedHtml = logEntriesHtml.replace(
                        "<table",
                        "<table class=\"log-table\""
                    ).replace(
                        "<td>",
                        "<td class=\"entry-col\">"
                    )
                    webView.loadDataWithBaseURL(null, modifiedHtml, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxSize()
            )
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
    fun ShareLogIconButton(context: Context, logEntries: String) {
        IconButton(
            onClick = {
                if (logEntries.isNotEmpty()) {
                    shareLogsAsFile(context)
                } else {
                    SnackbarManager.showWarning(
                        "Keine Log-Einträge zum Teilen vorhanden",
                        duration = SnackbarManager.Duration.LONG
                    )
                }
            }
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = "Log-Einträge teilen"
            )
        }
    }

    private fun shareLogsAsFile(context: Context) {
        try {
            // Erstelle temporäre Datei
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "sms_forwarder_log_$timeStamp.csv"
            val file = File(context.cacheDir, fileName)

            // Hole CSV-Daten vom Logger und schreibe sie in die Datei
            val csvContent = AppContainer.logger.getLogEntriesAsCsv()
            file.writeText(csvContent)

            // Erstelle FileProvider URI
            val fileUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            // Erstelle und starte Share Intent
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, "Log-Datei teilen"))

            LoggingManager.logInfo(
                component = "MainActivity",
                action = "SHARE_LOGS",
                message = "CSV-Log-Datei wurde zum Teilen erstellt",
                details = mapOf(
                    "filename" to fileName,
                    "size" to file.length()
                )
            )

        } catch (e: Exception) {
            LoggingManager.logError(
                component = "MainActivity",
                action = "SHARE_LOGS_ERROR",
                message = "Fehler beim Erstellen der CSV-Datei",
                error = e
            )
            SnackbarManager.showError("Fehler beim Teilen der Logs")
        }
    }

    @Composable
    fun PinDialog(
        onPinCorrect: () -> Unit,
        onDismiss: () -> Unit
    ) {
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("PIN eingeben") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                pin = it
                                error = false
                            }
                        },
                        label = { Text("PIN") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = error,
                        supportingText = if (error) {
                            { Text("Falsche PIN") }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pin == prefsManager.getLogPIN()) {
                        onPinCorrect()
                    } else {
                        error = true
                    }
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }
            }
        )
    }

    @Composable
    fun ChangePinDialog(
        onDismiss: () -> Unit
    ) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("PIN ändern") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = {
                            if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                currentPin = it
                                error = null
                            }
                        },
                        label = { Text("Aktuelle PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newPin,
                        onValueChange = {
                            if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                newPin = it
                                error = null
                            }
                        },
                        label = { Text("Neue PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                confirmPin = it
                                error = null
                            }
                        },
                        label = { Text("Neue PIN bestätigen") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (error != null) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        currentPin != prefsManager.getLogPIN() -> {
                            error = "Aktuelle PIN ist falsch"
                        }
                        newPin.length != 4 -> {
                            error = "Neue PIN muss 4 Stellen haben"
                        }
                        newPin != confirmPin -> {
                            error = "PINs stimmen nicht überein"
                        }
                        else -> {
                            prefsManager.setLogPIN(newPin)
                            LoggingManager.logInfo(
                                component = "MainActivity",
                                action = "CHANGE_PIN",
                                message = "Log-PIN wurde geändert"
                            )
                            SnackbarManager.showSuccess("PIN wurde geändert")
                            onDismiss()
                        }
                    }
                }) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }
            }
        )
    }

    @Composable
    fun InfoScreen() {
        val scrollState = rememberScrollState()
        val context = LocalContext.current
        val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5
        val packageInfo = remember {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Logo-Sektion
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
                        painter = painterResource(id = R.drawable.logofwd2),
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
                    Text(
                        text = "Build: ${BuildConfig.BUILD_TIME}",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // HTML-Content mit neuem Inhalt
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = false
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                        webViewClient = WebViewClient()
                        setBackgroundColor(if (isDarkTheme) 0xFF121212.toInt() else 0xFFFFFFFF.toInt())
                        loadDataWithBaseURL(
                            null,
                            getHtmlContent(isDarkTheme, context),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(
                        null,
                        getHtmlContent(isDarkTheme, context),
                        "text/html",
                        "UTF-8",
                        null
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    private fun getHtmlContent(isDarkTheme: Boolean, context: Context): String {
        val backgroundColor = if (isDarkTheme) "#121212" else "#FFFFFF"
        val textColor = if (isDarkTheme) "#E0E0E0" else "#333333"

        val currentAndroidVersion = Build.VERSION.RELEASE
        val currentSDKVersion = Build.VERSION.SDK_INT
        val minSDKVersion = context.applicationInfo.minSdkVersion
        val targetSDKVersion = context.applicationInfo.targetSdkVersion

        return """
    <!DOCTYPE html>
    <html lang="de">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
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
                color: ${if (isDarkTheme) "#E0E0E0" else "#333333"};
            }
            .section-container {
                background-color: ${if (isDarkTheme) "#1E1E1E" else "#F5F5F5"};
                border-radius: 8px;
                padding: 16px;
                margin-bottom: 16px;
            }
            .info-grid {
                display: grid;
                grid-template-columns: auto 1fr;
                gap: 8px;
                align-items: baseline;
            }
            .info-label {
                font-weight: 500;
                color: ${if (isDarkTheme) "#9E9E9E" else "#666666"};
                padding-right: 16px;
            }
            .info-value {
                color: ${if (isDarkTheme) "#E0E0E0" else "#333333"};
            }
            .badge {
                display: inline-block;
                padding: 2px 8px;
                border-radius: 4px;
                font-size: 12px;
                background-color: ${if (isDarkTheme) "#333333" else "#E0E0E0"};
                color: ${if (isDarkTheme) "#E0E0E0" else "#333333"};
            }
            .feature-list {
                list-style-type: none;
                padding: 0;
                margin: 0;
            }
            .feature-item {
                margin-bottom: 24px;
            }
            .feature-title {
                font-weight: 600;
                color: ${if (isDarkTheme) "#E0E0E0" else "#333333"};
                margin-bottom: 8px;
            }
            .feature-description {
                color: ${if (isDarkTheme) "#B0B0B0" else "#666666"};
                margin-left: 16px;
            }
            .footnote {
                font-style: italic;
                margin-top: 16px;
                color: ${if (isDarkTheme) "#9E9E9E" else "#666666"};
            }
        </style>
    </head>
    <body>
        <div class="section-container">
            <h2>Hauptfunktionen</h2>
            <ul class="feature-list">
                <li class="feature-item">
                    <div class="feature-title">Kontaktauswahl und Management</div>
                    <div class="feature-description">
                        Die App ermöglicht die intuitive Auswahl eines Kontakts für die Weiterleitung von SMS und Anrufen. 
                        Nutzen Sie die verbesserte Suchfunktion, die sowohl nach Namen als auch nach Telefonnummern sucht. 
                        Die Kontaktliste unterstützt:
                        <ul>
                            <li>Direkte Suche nach Namen oder Nummern</li>
                            <li>Automatische Erkennung von Mobilfunkanbietern</li>
                            <li>Persistente Speicherung der Auswahl</li>
                            <li>Schnelle Kontaktwechsel</li>
                        </ul>
                    </div>
                </li>
                
                <li class="feature-item">
                    <div class="feature-title">Weiterleitung von SMS</div>
                    <div class="feature-description">
                        Die SMS-Weiterleitung bietet zwei unabhängige Weiterleitungswege, die Sie einzeln oder kombiniert nutzen können:
                        <ul>
                            <li>SMS-zu-SMS: Direkte Weiterleitung an eine ausgewählte Telefonnummer</li>
                            <li>SMS-zu-Email: Weiterleitung an beliebig viele Email-Adressen</li>
                            <li>Formatierte Weiterleitungen mit Absender und Zeitstempel</li>
                            <li>Test-Funktionen für beide Weiterleitungswege</li>
                        </ul>
                    </div>
                </li>
                
                <li class="feature-item">
                    <div class="feature-title">Email-Konfiguration</div>
                    <div class="feature-description">
                        Die Email-Weiterleitung bietet umfangreiche Konfigurationsmöglichkeiten:
                        <ul>
                            <li>Unterstützung für verschiedene Email-Provider (Gmail, eigene SMTP-Server)</li>
                            <li>Verschlüsselte Speicherung der Zugangsdaten</li>
                            <li>Individuelle Test-Emails an jede konfigurierte Adresse</li>
                            <li>Detaillierte Fehlerdiagnose bei Zustellungsproblemen</li>
                        </ul>
                    </div>
                </li>
                
                <li class="feature-item">
                    <div class="feature-title">Anrufweiterleitung</div>
                    <div class="feature-description">
                        Die Anrufweiterleitung nutzt die native Telefonfunktion Ihres Geräts:
                        <ul>
                            <li>Automatische Weiterleitung aller eingehenden Anrufe</li>
                            <li>Verwendung von USSD-Codes für maximale Kompatibilität</li>
                            <li>Gleichzeitige Aktivierung mit SMS-Weiterleitung</li>
                            <li>Status-Anzeige der aktiven Weiterleitung</li>
                        </ul>
                    </div>
                </li>
                
                <li class="feature-item">
                    <div class="feature-title">Test und Überwachung</div>
                    <div class="feature-description">
                        Umfangreiche Test- und Überwachungsfunktionen für alle Weiterleitungen:
                        <ul>
                            <li>Test-SMS mit anpassbarem Inhalt</li>
                            <li>Separate Test-Emails an einzelne Adressen</li>
                            <li>Detaillierte Protokollierung aller Aktivitäten</li>
                            <li>Export von Protokollen im CSV-Format</li>
                        </ul>
                    </div>
                </li>
                
                <li class="feature-item">
                    <div class="feature-title">Benutzeroberfläche</div>
                    <div class="feature-description">
                        Die Benutzeroberfläche wurde für intuitive Bedienung optimiert:
                        <ul>
                            <li>Separate Bereiche für SMS- und Email-Konfiguration</li>
                            <li>Übersichtliche Statusanzeigen</li>
                            <li>Farbkodierte Protokollansicht</li>
                            <li>Anpassung an Tablets und Querformat</li>
                        </ul>
                    </div>
                </li>
                
                <li class="feature-item">
                    <div class="feature-title">Sicherheit und Datenschutz</div>
                    <div class="feature-description">
                        Die App legt besonderen Wert auf Sicherheit und Datenschutz:
                        <ul>
                            <li>Verschlüsselte Speicherung sensibler Daten</li>
                            <li>Sichere Handhabung von Email-Zugangsdaten</li>
                            <li>Automatische Bereinigung beim Beenden</li>
                            <li>Keine dauerhafte Speicherung von SMS-Inhalten</li>
                        </ul>
                    </div>
                </li>
            </ul>
        </div>

        <div class="section-container">
            <h2>System-Information</h2>
            <div class="info-grid">
                <span class="info-label">Programmiersprache:</span>
                <span class="info-value">Kotlin ${BuildConfig.KOTLIN_VERSION}</span>
                
                <span class="info-label">UI-Framework:</span>
                <span class="info-value">Jetpack Compose </span>
                
                <span class="info-label">Build-System:</span>
                <span class="info-value">Gradle ${BuildConfig.GRADLE_VERSION} mit AGP ${BuildConfig.AGP_VERSION}</span>
                                    
                <span class="info-label">Build Tools:</span>
                <span class="info-value">${BuildConfig.BUILD_TOOLS_VERSION}</span>

                <span class="info-label">Build Zeitpunkt:</span>
                <span class="info-value">${BuildConfig.BUILD_TIME}</span>
                
                <span class="info-label">Build Typ:</span>
                <span class="info-value"><span class="badge">${BuildConfig.BUILD_TYPE}</span></span>
                                
                <span class="info-label">Architektur:</span>
                <span class="info-value">MVVM (Model-View-ViewModel) mit Repository-Pattern</span>
                
                <span class="info-label">Nebenläufigkeit:</span>
                <span class="info-value">Coroutines und Flow</span>
                
                <span class="info-label">Datenspeicherung:</span>
                <span class="info-value">Verschlüsselte SharedPreferences</span>
                
                <span class="info-label">Hintergrunddienst:</span>
                <span class="info-value">Foreground Service</span>
                
                <span class="info-label">Android Version:</span>
                <span class="info-value">Android $currentAndroidVersion (API Level $currentSDKVersion)</span>
                
                <span class="info-label">Min SDK:</span>
                <span class="info-value">Android ${getAndroidVersionName(minSDKVersion)} (API Level $minSDKVersion)</span>
                
                <span class="info-label">Target SDK:</span>
                <span class="info-value">Android ${getAndroidVersionName(targetSDKVersion)} (API Level $targetSDKVersion)</span>

                <span class="info-label">JDK:</span>
                <span class="info-value">${BuildConfig.JDK_VERSION}</span>
            </div>
        </div>

        <p class="footnote">
            Die App wurde unter Berücksichtigung moderner Android-Entwicklungspraktiken und Datenschutzrichtlinien entwickelt. 
            Sie läuft energieeffizient im Hintergrund und gewährleistet dabei eine zuverlässige Weiterleitung Ihrer Nachrichten und Anrufe.
        </p>
    </body>
    </html>
    """.trimIndent()
    }

    private fun getAndroidVersionName(sdkInt: Int): String {
        return when (sdkInt) {
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "14"  // API 34
            Build.VERSION_CODES.TIRAMISU -> "13"          // API 33
            Build.VERSION_CODES.S_V2 -> "12L/12.1"        // API 32
            Build.VERSION_CODES.S -> "12"                 // API 31
            Build.VERSION_CODES.R -> "11"                 // API 30
            Build.VERSION_CODES.Q -> "10"                 // API 29
            Build.VERSION_CODES.P -> "9"                  // API 28
            Build.VERSION_CODES.O_MR1 -> "8.1"           // API 27
            Build.VERSION_CODES.O -> "8.0"               // API 26
            Build.VERSION_CODES.N_MR1 -> "7.1"           // API 25
            Build.VERSION_CODES.N -> "7.0"               // API 24
            Build.VERSION_CODES.M -> "6.0"               // API 23
            else -> "Version $sdkInt"
        }
    }
}

