package io.github.vitalyostanin.pushmenot

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import io.github.vitalyostanin.pushmenot.controller.AppController
import io.github.vitalyostanin.pushmenot.listener.PushMeNotListenerService
import io.github.vitalyostanin.pushmenot.sources.AndroidSources
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcut
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcutState
import io.github.vitalyostanin.pushmenot.transfer.RuleArchive
import io.github.vitalyostanin.pushmenot.transfer.RuleTransfer
import io.github.vitalyostanin.pushmenot.transfer.externalRuleUri
import io.github.vitalyostanin.pushmenot.transfer.isRuleFileDelivery
import io.github.vitalyostanin.pushmenot.ui.PushMeNotScreen
import io.github.vitalyostanin.pushmenot.ui.ScreenCallbacks
import io.github.vitalyostanin.pushmenot.updates.UpdateInstaller

class MainActivity : ComponentActivity() {
    private val historyShortcut by lazy { HistoryShortcut(this) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.updateHistoryShortcut(historyShortcut.publish())
    }

    private val controller by lazy { ViewModelProvider(this)[AppController::class.java] }

    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.prepareImport(uri)
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(RuleTransfer.MIME)) { uri ->
        if (uri != null) controller.export(uri)
    }

    private val openArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.prepareArchive(uri)
    }
    private val saveArchive = registerForActivityResult(ActivityResultContracts.CreateDocument(RuleArchive.MIME)) { uri ->
        if (uri != null) controller.saveArchive(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRecentsScreenshotEnabled(false)
        if (savedInstanceState == null) handleLaunch(intent)
        val callbacks = ScreenCallbacks(
            openAccess = { openSystemSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            openSourceSettings = { packageName ->
                if (!AndroidSources(this).openNotificationSettings(packageName)) {
                    controller.reportFailure()
                }
            },
            openBatteryOptimizations = controller::openBatteryOptimizations,
            openBackgroundAppSettings = controller::openBackgroundAppSettings,
            openLanguageSettings = {
                openSystemSettings(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            checkForUpdates = controller::checkForUpdates,
            openUpdateRelease = { release ->
                launchExternalActivity { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl))) }
            },
            installUpdate = controller::installUpdate,
            openInstallPermission = {
                openSystemSettings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null)))
            },
            confirmUpdate = {
                UpdateInstaller.confirm { prompt -> launchExternalActivity { startActivity(prompt) } }
            },
            configureHistoryShortcut = ::configureHistoryShortcut,
            saveContentRetention = controller::saveContentRetention,
            updateSource = controller::updateSource,
            searchHistory = controller::searchHistory,
            filterHistory = controller::filterHistory,
            loadMoreHistory = controller::loadMoreHistory,
            saveRule = controller::saveRule,
            observeShade = controller::observeShade,
            deleteRule = controller::deleteRule,
            beginImport = {
                launchExternalActivity {
                    importDocument.launch(arrayOf(RuleTransfer.MIME, "application/json", "text/plain", "application/octet-stream"))
                }
            },
            beginExport = { launchExternalActivity { exportDocument.launch(RuleTransfer.NAME) } },
            beginArchiveOpen = {
                launchExternalActivity { openArchive.launch(arrayOf(RuleArchive.MIME, "application/octet-stream", "text/plain")) }
            },
            beginArchiveSave = { launchExternalActivity { saveArchive.launch(RuleArchive.NAME) } },
            prepareRuleRestore = controller::prepareRuleRestore,
            confirmArchive = controller::restoreArchive,
            confirmImport = controller::confirmImport,
            cancelImport = controller::cancelImport,
            undoImport = controller::undoRules,
            clearHistory = controller::clearHistory,
            saveJournalCapacity = controller::saveJournalCapacity,
            dismissMessage = controller::dismissMessage,
            previewRule = controller::previewRule,
        )
        setContent {
            val state by controller.state.collectAsState()
            PushMeNotScreen(state, callbacks)
        }
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val service = ComponentName(this, PushMeNotListenerService::class.java)
        val hasAccess = getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(service) == true
        controller.onResume(hasAccess)
        controller.updateHistoryShortcut(historyShortcut.publish())
        if (hasAccess && !PushMeNotListenerService.isConnected.value) {
            try {
                NotificationListenerService.requestRebind(service)
            } catch (_: SecurityException) {
                // The user may revoke access between checking it and asking Android to bind.
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) controller.updateHistoryShortcut(historyShortcut.status())
    }

    private fun handleLaunch(intent: Intent) {
        when {
            intent.action == HistoryShortcut.ACTION -> controller.openHistoryFromShortcut()
            intent.isRuleFileDelivery() -> {
                val uri = intent.externalRuleUri(packageName)
                val canRead = uri != null && checkUriPermission(
                    uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ) == PackageManager.PERMISSION_GRANTED
                if (canRead) controller.prepareImport(checkNotNull(uri))
                else controller.reportImportFailure()
            }
        }
    }

    private fun configureHistoryShortcut() {
        when (historyShortcut.status()) {
            HistoryShortcutState.PERMISSION_OFF -> {
                val askedBefore = getPreferences(MODE_PRIVATE).getBoolean("askedForNotifications", false)
                if (!askedBefore || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("askedForNotifications", true).apply()
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openSystemSettings(historyShortcut.systemSettings(channel = false))
                }
            }
            HistoryShortcutState.CHANNEL_OFF -> openSystemSettings(historyShortcut.systemSettings(channel = true))
            HistoryShortcutState.NOT_SHOWN -> controller.updateHistoryShortcut(historyShortcut.publish())
            HistoryShortcutState.SHOWN -> Unit
        }
    }

    private fun openSystemSettings(intent: Intent) = launchExternalActivity { startActivity(intent) }

    private fun launchExternalActivity(action: () -> Unit): Boolean {
        return try {
            action()
            true
        } catch (_: ActivityNotFoundException) {
            controller.reportFailure()
            false
        } catch (_: SecurityException) {
            controller.reportFailure()
            false
        }
    }
}
