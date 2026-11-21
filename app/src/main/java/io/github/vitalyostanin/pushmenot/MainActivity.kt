package io.github.vitalyostanin.pushmenot

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BadParcelableException
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
import io.github.vitalyostanin.pushmenot.controller.AppController
import io.github.vitalyostanin.pushmenot.listener.PushMeNotListenerService
import io.github.vitalyostanin.pushmenot.sources.AndroidSources
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcut
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcutState
import io.github.vitalyostanin.pushmenot.transfer.HistoryExportOptions
import io.github.vitalyostanin.pushmenot.transfer.RuleArchive
import io.github.vitalyostanin.pushmenot.transfer.RuleTransfer
import io.github.vitalyostanin.pushmenot.transfer.externalRuleUri
import io.github.vitalyostanin.pushmenot.transfer.isRuleFileDelivery
import io.github.vitalyostanin.pushmenot.ui.PushMeNotScreen
import io.github.vitalyostanin.pushmenot.ui.ScreenCallbacks
import io.github.vitalyostanin.pushmenot.updates.UpdateInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val PENDING_HISTORY_EXPORT = "pending_history_export"

class MainActivity : ComponentActivity() {
    private var ruleShare: Job? = null
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

    private val exportHistoryDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        uri -> controller.completeHistoryExport(uri)
    }

    private val openArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.prepareArchive(uri)
    }
    private val saveArchive = registerForActivityResult(ActivityResultContracts.CreateDocument(RuleArchive.MIME)) { uri ->
        if (uri != null) controller.saveArchive(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        savedInstanceState?.getBundle(PENDING_HISTORY_EXPORT)?.let {
            if (controller.pendingHistoryExport == null) {
                controller.pendingHistoryExport = HistoryExportOptions.fromSavedState(it)
            }
        }
        if (savedInstanceState == null) handleLaunch(intent)
        val callbacks = ScreenCallbacks(
            openAccess = { openSystemSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            openSourceSettings = { packageName ->
                if (!AndroidSources(this).openNotificationSettings(packageName)) {
                    controller.reportFailure()
                }
            },
            openBatteryOptimizations = { controller.openBatteryOptimizations(this) },
            openBackgroundAppSettings = { controller.openBackgroundAppSettings(this) },
            openLanguageSettings = {
                val settings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null))
                } else {
                    Intent(Settings.ACTION_LOCALE_SETTINGS)
                }
                openSystemSettings(settings)
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
            shareRules = ::shareRules,
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
            unmatchedModeSample = controller::unmatchedModeSample,
            saveUnmatchedMode = controller::saveUnmatchedMode,
            countHistoryExport = controller::countHistoryExport,
            exportHistory = { options ->
                controller.pendingHistoryExport = options
                if (!launchExternalActivity { exportHistoryDocument.launch("push-me-not-history.json") }) {
                    controller.pendingHistoryExport = null
                }
            },
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        controller.pendingHistoryExport?.let {
            outState.putBundle(PENDING_HISTORY_EXPORT, it.toSavedState())
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
                val uri = try {
                    intent.externalRuleUri(packageName)?.takeIf {
                        checkUriPermission(
                            it, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                } catch (_: BadParcelableException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
                if (uri != null) controller.prepareImport(uri)
                else controller.reportImportFailure()
            }
        }
    }

    private fun configureHistoryShortcut() {
        when (historyShortcut.status()) {
            HistoryShortcutState.PERMISSION_OFF -> {
                val askedBefore = getPreferences(MODE_PRIVATE).getBoolean("askedForNotifications", false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (!askedBefore || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                ) {
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

    private fun shareRules() {
        if (ruleShare?.isActive == true) return
        ruleShare = lifecycleScope.launch {
            try {
                val send = controller.prepareRulesShare()
                launchExternalActivity { startActivity(Intent.createChooser(send, null)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controller.reportFailure()
            }
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
