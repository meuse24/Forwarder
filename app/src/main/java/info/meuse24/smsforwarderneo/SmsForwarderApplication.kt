package info.meuse24.smsforwarderneo

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

typealias LogLevel = Logger.LogLevel

typealias LogMetadata = Logger.LogMetadata

object AppContainer {
    private lateinit var application: SmsForwarderApplication
    private var activity: MainActivity? = null // Optional statt lateinit

    private val _isBasicInitialized = MutableStateFlow(false)
    val isBasicInitialized = _isBasicInitialized.asStateFlow() // Public StateFlow

    private val _isFullyInitialized = MutableStateFlow(false)
    val isInitialized = _isFullyInitialized.asStateFlow()


    lateinit var logger: Logger
        private set

    lateinit var prefsManager: SharedPreferencesManager
        private set

    lateinit var permissionHandler: PermissionHandler
        private set

    private lateinit var phoneUtils: PhoneSmsUtils.Companion

    fun initializeCritical(app: SmsForwarderApplication) {
        Log.d("AppContainer", "Starting critical initialization")
        application = app
        logger = Logger(app)
        prefsManager = SharedPreferencesManager(app)
        PhoneSmsUtils.initialize()
        phoneUtils = PhoneSmsUtils
        _isBasicInitialized.value = true
        Log.d("AppContainer", "Critical initialization complete")
    }

    fun initializeWithActivity(mainActivity: MainActivity) {
        Log.d("AppContainer", "Starting activity-dependent initialization")
        try {
            activity = mainActivity
            permissionHandler = PermissionHandler(mainActivity)
            _isFullyInitialized.value = true
            Log.d("AppContainer", "Full initialization complete")
        } catch (e: Exception) {
            Log.e("AppContainer", "Error in activity initialization", e)
            throw e
        }
    }

    // Hilfsmethode zum Prüfen des Initialisierungsstatus
    fun isBasicInitialized() = _isBasicInitialized.value

    fun getApplication(): SmsForwarderApplication = application
}

class SmsForwarderApplication : Application() {
    private val applicationScope = CoroutineScope(Job() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Nur Basis-Initialisierung
        initializeCriticalComponents()
        initializeBaseComponents()  // Add this call
    }

    private fun initializeCriticalComponents() {
        try {
            AppContainer.initializeCritical(this)
        } catch (e: Exception) {
            Log.e("SmsForwarderApplication", "Critical initialization failed", e)
            throw e
        }
    }

    private fun initializeBaseComponents() {
        try {
            // Initialize LoggingManager with Application context
            LoggingManager.initialize(this)  // 'this' ist der Application context

            // Add initial log entry after proper initialization
            LoggingManager.logInfo(
                component = "Application",
                action = "INIT",
                message = "Base components initialized successfully"
            )
        } catch (e: Exception) {
            Log.e("SmsForwarderApplication", "Base initialization failed", e)
            throw e
        }
    }


    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
        LoggingManager.logInfo(
            component = "Application",
            action = "TERMINATE",
            message = "Application is terminating"
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LoggingManager.logWarning(
            component = "Application",
            action = "LOW_MEMORY",
            message = "System reports low memory",
            details = mapOf(
                "free_memory" to Runtime.getRuntime().freeMemory(),
                "total_memory" to Runtime.getRuntime().totalMemory()
            )
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            LoggingManager.logWarning(
                component = "Application",
                action = "TRIM_MEMORY",
                message = "System requests memory trim",
                details = mapOf("level" to level)
            )
        }
    }

    companion object {
        @Volatile
        private var instance: SmsForwarderApplication? = null

    }
}

object LoggingManager {
    private lateinit var logger: Logger
    private val initialized = AtomicBoolean(false)

    fun initialize(context: Context) {
        logger = Logger(context)
        initialized.set(true)

        logInfo(
            component = "LoggingManager",
            action = "INIT",
            message = "Logging system initialized"
        )
    }
    fun log(
        level: LogLevel,
        metadata: LogMetadata,
        message: String,
        throwable: Throwable? = null
    ) {
        if (!initialized.get()) {
            Log.e("LoggingManager", "Logging before initialization: $message")
            return
        }

        try {
            logger.log(level, metadata, message, throwable)
        } catch (e: Exception) {
            Log.e("LoggingManager", "Logging error: ${e.message}", e)
        }
    }

    fun logInfo(
        component: String,
        action: String,
        message: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        if (!initialized.get()) {
            Log.e("LoggingManager", "Logging before initialization: $message")
            return
        }

        logger.log(
            Logger.LogLevel.INFO,
            Logger.LogMetadata(component, action, details),
            message
        )
    }

    fun logWarning(
        component: String,
        action: String,
        message: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        if (!initialized.get()) return

        logger.log(
            Logger.LogLevel.WARNING,
            Logger.LogMetadata(component, action, details),
            message
        )
    }

    fun logError(
        component: String,
        action: String,
        message: String,
        error: Throwable? = null,
        details: Map<String, Any?> = emptyMap()
    ) {
        if (!initialized.get()) return

        logger.log(
            Logger.LogLevel.ERROR,
            Logger.LogMetadata(component, action, details),
            message,
            error
        )
    }

    fun logDebug(
        component: String,
        action: String,
        message: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        if (!initialized.get()) return

        logger.log(
            Logger.LogLevel.DEBUG,
            Logger.LogMetadata(component, action, details),
            message
        )
    }
}

object SnackbarManager {

        private var snackbarHostState: SnackbarHostState? = null
        private var coroutineScope: CoroutineScope? = null
        private val pendingMessages = mutableListOf<SnackbarConfig>()
        private val lock = Any()

        enum class SnackbarType {
            INFO,
            SUCCESS,
            WARNING,
            ERROR
        }

        enum class Duration(val composeDuration: SnackbarDuration) {
            SHORT(SnackbarDuration.Short),
            LONG(SnackbarDuration.Long),
        }

        data class SnackbarConfig(
            val message: String,
            val type: SnackbarType = SnackbarType.INFO,
            val duration: Duration = Duration.SHORT,
            val actionText: String? = null,
            val action: (() -> Unit)? = null
        )

        fun setSnackbarState(hostState: SnackbarHostState, scope: CoroutineScope) {
            synchronized(lock) {
                snackbarHostState = hostState
                coroutineScope = scope

                // Zeige ausstehende Nachrichten
                if (pendingMessages.isNotEmpty()) {
                    scope.launch {
                        pendingMessages.toList().forEach { config ->
                            showMessage(config)
                            pendingMessages.remove(config)
                        }
                    }
                }
            }
        }

        private fun showMessage(config: SnackbarConfig) {
            synchronized(lock) {
                val hostState = snackbarHostState
                val scope = coroutineScope

                if (hostState == null || scope == null) {

                    pendingMessages.add(config)
                    return
                }

                scope.launch {
                    try {
                        val result = hostState.showSnackbar(
                            message = config.message,
                            actionLabel = config.actionText,
                            duration = config.duration.composeDuration,
                            withDismissAction = true
                        )

                        when (result) {
                            SnackbarResult.ActionPerformed -> {
                                config.action?.invoke()
                                LoggingManager.logDebug(
                                    component = "SnackbarManager",
                                    action = "SNACKBAR_ACTION",
                                    message = "Snackbar-Aktion ausgeführt"
                                )
                            }
                            SnackbarResult.Dismissed -> {
                                LoggingManager.logDebug(
                                    component = "SnackbarManager",
                                    action = "SNACKBAR_DISMISSED",
                                    message = "Snackbar geschlossen"
                                )
                            }
                        }

                        LoggingManager.logDebug(
                            component = "SnackbarManager",
                            action = "SHOW_MESSAGE",
                            message = "Snackbar angezeigt",
                            details = mapOf(
                                "message" to config.message,
                                "type" to config.type.name
                            )
                        )
                    } catch (e: Exception) {
                        LoggingManager.logError(
                            component = "SnackbarManager",
                            action = "SHOW_MESSAGE_ERROR",
                            message = "Fehler beim Anzeigen der Snackbar",
                            error = e
                        )
                    }
                }
            }
        }

    // Helper methods
    fun showInfo(
        message: String,
        duration: Duration = Duration.SHORT,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        showMessage(
            SnackbarConfig(
                message = message,
                type = SnackbarType.INFO,
                duration = duration,
                actionText = actionText,
                action = action
            )
        )
    }

    fun showSuccess(
        message: String,
        duration: Duration = Duration.SHORT,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        showMessage(
            SnackbarConfig(
                message = message,
                type = SnackbarType.SUCCESS,
                duration = duration,
                actionText = actionText,
                action = action
            )
        )
    }

    fun showWarning(
        message: String,
        duration: Duration = Duration.LONG,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        showMessage(
            SnackbarConfig(
                message = message,
                type = SnackbarType.WARNING,
                duration = duration,
                actionText = actionText,
                action = action
            )
        )
    }

    fun showError(
        message: String,
        duration: Duration = Duration.LONG,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        showMessage(
            SnackbarConfig(
                message = message,
                type = SnackbarType.ERROR,
                duration = duration,
                actionText = actionText,
                action = action
            )
        )
    }
}

