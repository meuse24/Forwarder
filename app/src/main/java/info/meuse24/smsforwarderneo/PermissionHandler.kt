package info.meuse24.smsforwarderneo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

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