package info.meuse24.smsforwarderneo

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class SmsForwarderApplication : Application() {
    lateinit var logger: Logger
        private set

    lateinit var loggingHelper: LoggingHelper
        private set

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val applicationScope = CoroutineScope(Job() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Starte Initialisierung in einer Coroutine
        applicationScope.launch {
            try {
                initializeLogging()
                _isReady.value = true
            } catch (e: Exception) {
                Log.e("Application", "Fehler bei der Initialisierung", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.coroutineContext.cancel()
    }
    private  fun initializeLogging() {
        try {
            logger = Logger(this)
            loggingHelper = LoggingHelper(logger)

            // Initial Log-Eintrag
            LoggingManager.logInfo(
                component = "Application",
                action = "INIT",
                message = "SMS Forwarder Application gestartet",
                details = mapOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "build_type" to BuildConfig.BUILD_TYPE,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei der Logging-Initialisierung", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()

        // Asynchrones Logging mit applicationScope

            LoggingManager.logWarning(
                component = "Application",
                action = "LOW_MEMORY",
                message = "Gerät hat wenig Arbeitsspeicher",
                details = mapOf(
                    "free_memory" to Runtime.getRuntime().freeMemory(),
                    "total_memory" to Runtime.getRuntime().totalMemory()
                )
            )

    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Asynchrones Logging im applicationScope

                LoggingManager.logWarning(
                    component = "Application",
                    action = "TRIM_MEMORY",
                    message = "System fordert Speicherbereinigung an",
                    details = mapOf("level" to level)
                )

        }
    }

    companion object {
        private const val TAG = "SmsForwarderApp"

        @Volatile
        private var instance: SmsForwarderApplication? = null

        fun getInstance(): SmsForwarderApplication {
            return instance ?: throw IllegalStateException(
                "Application nicht initialisiert"
            )
        }
    }
}

object LoggingManager {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)


    private val loggingHelper: LoggingHelper
        get() = SmsForwarderApplication.getInstance().loggingHelper

    // Allgemeine log-Funktion mit gleicher Signatur wie LoggingHelper
     fun log(
        level: LoggingHelper.LogLevel,
        metadata: LoggingHelper.LogMetadata,
        message: String,
        throwable: Throwable? = null
    ) {
        try {
            scope.launch {
                loggingHelper.log(level, metadata, message, throwable)
            }
        } catch (e: Exception) {
            Log.e("LoggingManager", "Fehler beim Logging: ${e.message}", e)
        }
    }

    // Bestehende spezifische Logging-Methoden bleiben erhalten
    fun logInfo(
        component: String,
        action: String,
        message: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        log(
            LoggingHelper.LogLevel.INFO,
            LoggingHelper.LogMetadata(component, action, details),
            message
        )
    }

    fun logWarning(
        component: String,
        action: String,
        message: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        log(
            LoggingHelper.LogLevel.WARNING,
            LoggingHelper.LogMetadata(component, action, details),
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
        log(
            LoggingHelper.LogLevel.ERROR,
            LoggingHelper.LogMetadata(component, action, details),
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
        log(
            LoggingHelper.LogLevel.DEBUG,
            LoggingHelper.LogMetadata(component, action, details),
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

