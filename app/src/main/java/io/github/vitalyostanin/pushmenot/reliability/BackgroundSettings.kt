package io.github.vitalyostanin.pushmenot.reliability

import android.app.ActivityManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

data class BackgroundStatus(
    val restricted: Boolean?,
    val ignoresBatteryOptimizations: Boolean?,
)

enum class BatterySettingsDestination { OPTIMIZATION_LIST, APP_DETAILS }

class BackgroundSettings(private val context: Context) {
    fun read(): BackgroundStatus = BackgroundStatus(
        restricted = context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted,
        ignoresBatteryOptimizations = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName),
    )

    fun openBatteryOptimizations(): BatterySettingsDestination? = when {
        open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ->
            BatterySettingsDestination.OPTIMIZATION_LIST
        openAppDetails() -> BatterySettingsDestination.APP_DETAILS
        else -> null
    }

    fun openAppDetails(): Boolean = open(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    private fun open(intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
