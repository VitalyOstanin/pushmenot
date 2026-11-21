package io.github.vitalyostanin.pushmenot.sources

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

data class InstalledSource(val packageName: String, val label: String)

class AndroidSources(private val context: Context) {
    @Suppress("DEPRECATION")
    fun installedApps(): List<InstalledSource> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = context.packageManager
        return packageManager.queryIntentActivities(launcher, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                InstalledSource(it.activityInfo.packageName, it.loadLabel(packageManager).toString())
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { source: InstalledSource -> source.label }
                .thenBy { source -> source.packageName })
            .toList()
    }

    /** Opening settings does not imply that the permission was changed. */
    fun openNotificationSettings(packageName: String): Boolean {
        val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        return openSettings(notificationSettings) || openSettings(appDetails)
    }

    private fun openSettings(intent: Intent): Boolean {
        return try {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
