package io.github.vitalyostanin.pushmenot.controller

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.listener.JournalDestinations
import io.github.vitalyostanin.pushmenot.listener.PushMeNotListenerService
import io.github.vitalyostanin.pushmenot.preview.HistoryPreview
import io.github.vitalyostanin.pushmenot.preview.PreviewEntry
import io.github.vitalyostanin.pushmenot.preview.RulePreview
import io.github.vitalyostanin.pushmenot.rules.ModeScope
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.rules.unmatchedAction
import io.github.vitalyostanin.pushmenot.reliability.BackgroundSettings
import io.github.vitalyostanin.pushmenot.reliability.BatterySettingsDestination
import io.github.vitalyostanin.pushmenot.sources.AndroidSources
import io.github.vitalyostanin.pushmenot.storage.HistoryCursor
import io.github.vitalyostanin.pushmenot.storage.HistoryQuery
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.PushDatabase
import io.github.vitalyostanin.pushmenot.storage.writeTransaction
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcutState
import io.github.vitalyostanin.pushmenot.transfer.HistoryExport
import io.github.vitalyostanin.pushmenot.transfer.HistoryExportOptions
import io.github.vitalyostanin.pushmenot.transfer.ImportMode
import io.github.vitalyostanin.pushmenot.transfer.RuleArchive
import io.github.vitalyostanin.pushmenot.transfer.RuleTransfer
import io.github.vitalyostanin.pushmenot.ui.HistoryFilters
import io.github.vitalyostanin.pushmenot.ui.HistoryPeriod
import io.github.vitalyostanin.pushmenot.ui.ScreenState
import io.github.vitalyostanin.pushmenot.updates.GitHubUpdateChecker
import io.github.vitalyostanin.pushmenot.updates.UpdateInstaller
import io.github.vitalyostanin.pushmenot.updates.UpdateResult
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppController(application: Application) : AndroidViewModel(application) {
    private val database = PushDatabase.open(application)
    private val storage = database.storage
    private val sources = AndroidSources(application)
    private val backgroundSettings = BackgroundSettings(application)
    private val archive = RuleArchive(application.contentResolver, storage)
    private val transfer = RuleTransfer(application.contentResolver, storage)
    private val historyExport = HistoryExport(application.contentResolver, application.cacheDir, storage)
    var pendingHistoryExport: HistoryExportOptions? = null
    private val updateChecker = GitHubUpdateChecker()
    private val updateInstaller = UpdateInstaller(application)
    @Suppress("DEPRECATION")
    private val installedVersion = application.packageManager.getPackageInfo(
        application.packageName, 0,
    ).versionName.orEmpty()
    private val mutableState = MutableStateFlow(
        ScreenState(
            accessGranted = false,
            listenerConnected = PushMeNotListenerService.isConnected.value,
            installedApps = emptyList(),
            sources = emptyList(),
            history = emptyList(),
            rules = emptyList(),
            installedVersion = installedVersion,
        ),
    )
    val state = mutableState.asStateFlow()

    private var historyJob: Job? = null
    private var importJob: Job? = null
    private var historyLimit = PAGE_SIZE
    private var currentWindow: JournalWindow? = null

    init {
        operation {
            UpdateInstaller.status.collect { status -> mutableState.update { it.copy(updateInstallStatus = status) } }
        }
        operation {
            storage.observeJournalSettings().collect { settings ->
                mutableState.update {
                    it.copy(journalCapacity = settings.capacity, retainContent = settings.retainContent)
                }
            }
        }
        operation {
            storage.observeSources().collect { values -> mutableState.update { it.copy(sources = values) } }
        }
        operation {
            PushMeNotListenerService.isConnected.collect { connected ->
                mutableState.update { it.copy(listenerConnected = connected) }
            }
        }
        operation {
            storage.observeRules().collect { set ->
                val document = withContext(Dispatchers.Default) { RuleJson.decodeStored(set.rulesJson) }
                mutableState.update {
                    it.copy(rules = document.rules, modes = document.modes,
                        rulesCanUndo = set.previousRulesJson != null, activeSnapshotId = set.activeRevisionId)
                }
            }
        }
        operation {
            storage.ruleHistory.observeVersions().collect { versions ->
                mutableState.update { it.copy(ruleVersions = versions) }
            }
        }
        operation {
            storage.observeStatisticsChanges().collect { refreshStatistics() }
        }
        operation {
            while (true) {
                delay(60_000)
                refreshStatistics()
            }
        }
        operation {
            storage.observeJournalChanges().collect {
                val packages = storage.historyPackages()
                mutableState.update { it.copy(historyApps = packages) }
                refreshHistory()
            }
        }
    }

    private suspend fun refreshStatistics() {
        val value = storage.notificationStatistics()
        mutableState.update { it.copy(statistics = value) }
    }

    fun onResume(accessGranted: Boolean) {
        updateInstaller.refresh()
        operation { refreshStatistics() }
        val backgroundStatus = backgroundSettings.read()
        mutableState.update { it.copy(accessGranted = accessGranted, backgroundStatus = backgroundStatus, foreground = true) }
        operation {
            val installed = withContext(Dispatchers.IO) { sources.installedApps() }
            mutableState.update { it.copy(installedApps = installed) }
        }
    }

    fun onPause() {
        mutableState.update { it.copy(foreground = false) }
    }

    fun observeShade() = flow {
        PushMeNotListenerService.visibleShadeChanges.collect {
            emit(withContext(Dispatchers.Default) { PushMeNotListenerService.visibleShade() })
            delay(150)
        }
    }

    fun openBatteryOptimizations(activity: Activity) {
        when (BackgroundSettings(activity).openBatteryOptimizations()) {
            BatterySettingsDestination.OPTIMIZATION_LIST -> Unit
            BatterySettingsDestination.APP_DETAILS -> showMessage(R.string.battery_optimization_settings_fallback)
            null -> reportFailure()
        }
    }

    fun openBackgroundAppSettings(activity: Activity) {
        if (!BackgroundSettings(activity).openAppDetails()) reportFailure()
    }

    fun updateSource(packageName: String, enabled: Boolean) {
        operation { storage.setSource(packageName, enabled) }
    }

    fun searchHistory(query: String) {
        if (query == state.value.historyQuery) return
        historyLimit = PAGE_SIZE
        currentWindow = null
        mutableState.update { it.copy(historyQuery = query, history = emptyList(), historyHasMore = false) }
        refreshHistory(debounce = true)
    }

    fun filterHistory(filters: HistoryFilters) {
        if (filters == state.value.historyFilters) return
        historyLimit = PAGE_SIZE
        currentWindow = null
        mutableState.update { it.copy(historyFilters = filters, history = emptyList(), historyHasMore = false) }
        refreshHistory()
    }

    fun loadMoreHistory() {
        if (!state.value.historyHasMore) return
        historyLimit += PAGE_SIZE
        refreshHistory()
    }

    private fun refreshHistory(debounce: Boolean = false) {
        historyJob?.cancel()
        val snapshot = state.value
        val filters = snapshot.historyFilters
        val since = when (filters.period) {
            HistoryPeriod.ALL -> null
            HistoryPeriod.TODAY -> {
                val zone = ZoneId.systemDefault()
                LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            HistoryPeriod.LAST_SEVEN_DAYS -> System.currentTimeMillis() - SEVEN_DAYS_MILLIS
        }
        val query = HistoryQuery(
            sinceMillis = since,
            packageNames = filters.packageNames,
            includeGaps = true,
            channelId = filters.channelId,
            sender = filters.sender,
            verdict = filters.verdict,
            search = snapshot.historyQuery,
        )
        val limit = historyLimit
        historyJob = operation {
            if (debounce) delay(150)
            val window = JournalWindow.load(database, query, currentWindow, limit)
            currentWindow = window
            mutableState.update {
                it.copy(history = window.entries.take(limit), historyHasMore = window.hasUnloadedRows)
            }
        }
    }

    private suspend fun readHistory(query: HistoryQuery, limit: Int): List<JournalItem> =
        database.writeTransaction {
            val rows = mutableListOf<JournalItem>()
            var cursor: HistoryCursor? = null
            while (rows.size < limit) {
                val requested = minOf(limit - rows.size, PAGE_SIZE)
                val next = storage.historyPage(query.copy(before = cursor, limit = requested))
                rows.addAll(next)
                if (next.size < requested) break
                val last = next.last()
                cursor = HistoryCursor(last.observedAtMillis, last.id)
            }
            rows
        }

    fun saveRule(draft: FilterRule, enableSource: Boolean) {
        operation {
            database.writeTransaction { persistRule(draft, enableSource) }
            showMessage(R.string.rules_saved)
        }
    }

    private suspend fun persistRule(draft: FilterRule, enableSource: Boolean) {
        val current = storage.rules()
        val document = RuleJson.decodeStored(current.rulesJson)
        val rules = document.rules
        val previous = rules.firstOrNull { it.id == draft.id }
        val updated = if (previous == null) {
            check(draft.revision == 1L)
            rules + draft
        } else {
            check(draft.revision == previous.revision && previous.revision < Long.MAX_VALUE)
            if (draft == previous) rules else rules.map {
                if (it.id == draft.id) draft.copy(revision = previous.revision + 1) else it
            }
        }
        check(storage.replaceRules(current.version, RuleJson.encode(updated, modes = document.modes)) != null)
        if (enableSource) storage.setSource(requireNotNull(draft.packageName), true)
    }

    fun deleteRule(id: String) {
        operation {
            val current = storage.rules()
            val document = RuleJson.decodeStored(current.rulesJson)
            val rules = document.rules.filterNot { it.id == id }
            check(storage.replaceRules(current.version, RuleJson.encode(rules, modes = document.modes)) != null)
            showMessage(R.string.rules_saved)
        }
    }

    suspend fun unmatchedModeSample(packageName: String): List<JournalItem> =
        storage.historyPage(HistoryQuery(packageName = packageName, limit = 100))

    fun saveUnmatchedMode(scope: ModeScope, action: Action?) {
        operation {
            val current = storage.rules()
            val document = RuleJson.decodeStored(current.rulesJson)
            val modes = if (action == null || (scope.channelId == null && action == Action.KEEP)) {
                document.modes - scope
            } else {
                document.modes + (scope to action)
            }
            check(storage.replaceRules(current.version, RuleJson.encode(document.rules, modes = modes)) != null)
            showMessage(R.string.rules_saved)
        }
    }

    suspend fun previewRule(rule: FilterRule, enableSource: Boolean): RulePreview {
        try {
            val document = RuleJson.decodeStored(storage.rules().rulesJson)
            val current = document.rules
            val unmatched: (com.pushmenot.core.NotificationEvent) -> Action = {
                document.modes.unmatchedAction(it.packageName, it.channelId)
            }
            val existing = current.firstOrNull { it.id == rule.id }
            val proposed = if (existing == null) current + rule else current.map {
                if (it.id == rule.id) rule else it
            }
            val enabled = state.value.sources.filter { it.enabled }.mapTo(mutableSetOf()) { it.packageName }
            val newSource = rule.packageName.takeIf { enableSource && it !in enabled }
            val affected = enabled.filterTo(mutableSetOf()) {
                rule.appliesTo(it) || existing?.appliesTo(it) == true
            }
            if (newSource != null) affected.add(newSource)
            if (affected.isEmpty()) return RulePreview(emptyList())
            val sample = readHistory(HistoryQuery(packageNames = affected, includeGaps = true), HISTORY_CAP + 1)
            return withContext(Dispatchers.Default) {
                val rows = sample.take(HISTORY_CAP)
                val baseline = HistoryPreview.evaluate(
                    rows.filter { it.packageName != newSource || newSource == null }, current, unmatched,
                ).associateBy(PreviewEntry::journalId)
                val entries = HistoryPreview.evaluate(rows, proposed, unmatched).map { candidate ->
                    val previous = baseline[candidate.journalId]
                    candidate.copy(
                        complete = candidate.complete && previous?.complete != false,
                        previousDecision = previous?.decision,
                    )
                }
                RulePreview(entries, HISTORY_CAP.takeIf { sample.size > HISTORY_CAP })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportFailure()
            return RulePreview(emptyList())
        }
    }

    fun prepareImport(uri: Uri) {
        cancelImport()
        importJob = operation(R.string.rule_file_failed) {
            val preview = transfer.prepareImport(uri)
            mutableState.update { it.copy(importPreview = preview) }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        mutableState.update { it.copy(importPreview = null, archivePreview = null) }
    }

    fun confirmImport(mode: ImportMode, importApps: Boolean) {
        val preview = state.value.importPreview ?: return
        cancelImport()
        operation {
            check(transfer.applyImport(preview, mode, importApps) != null)
            showMessage(if (preview.restoreSnapshot == null) R.string.import_completed else R.string.history_restore_done)
        }
    }

    fun prepareRuleRestore(revisionId: String) {
        cancelImport()
        importJob = operation {
            val preview = checkNotNull(transfer.prepareRestore(revisionId))
            mutableState.update { it.copy(importPreview = preview) }
        }
    }

    fun prepareArchive(uri: Uri) {
        cancelImport()
        importJob = operation(R.string.backup_failed) {
            val preview = archive.prepare(uri)
            mutableState.update { it.copy(archivePreview = preview) }
        }
    }

    fun restoreArchive() {
        val preview = state.value.archivePreview ?: return
        cancelImport()
        operation(R.string.backup_failed) {
            check(archive.restore(preview) != null)
            showMessage(R.string.backup_restore_done)
        }
    }

    fun saveArchive(uri: Uri) {
        operation(R.string.backup_save_failed) {
            archive.save(uri)
            showMessage(R.string.backup_save_done)
        }
    }

    fun updateHistoryShortcut(value: HistoryShortcutState) {
        mutableState.update { it.copy(historyShortcut = value) }
    }

    fun openHistoryFromShortcut() {
        cancelImport()
        mutableState.update { it.copy(historyShortcutOpen = UUID.randomUUID().toString(), message = null) }
    }

    fun reportImportFailure() {
        cancelImport()
        showMessage(R.string.rule_file_failed)
    }

    suspend fun prepareRulesShare() = transfer.prepareShare(getApplication())

    fun export(uri: Uri) {
        operation {
            transfer.export(uri)
            showMessage(R.string.export_completed)
        }
    }

    fun undoRules() {
        operation {
            val current = storage.rules()
            check(storage.undoRules(current.version) != null)
            showMessage(R.string.rules_saved)
        }
    }

    fun saveJournalCapacity(capacity: Int) {
        operation { storage.setJournalCapacity(capacity) }
    }

    fun saveContentRetention(enabled: Boolean) {
        operation { storage.setContentRetention(enabled) }
    }

    suspend fun countHistoryExport(options: HistoryExportOptions): Int = historyExport.count(options)

    fun completeHistoryExport(uri: Uri?) {
        val options = pendingHistoryExport
        pendingHistoryExport = null
        if (uri == null) return
        operation {
            historyExport.write(uri, checkNotNull(options))
            showMessage(R.string.history_export_done)
        }
    }

    fun clearHistory() {
        operation {
            storage.clearHistory()
            JournalDestinations.clear()
            showMessage(R.string.history_cleared)
        }
    }

    fun checkForUpdates() {
        if (mutableState.value.updateResult == UpdateResult.Checking || UpdateInstaller.status.value.busy) return
        mutableState.update { it.copy(updateResult = UpdateResult.Checking) }
        viewModelScope.launch {
            val result = updateChecker.check(installedVersion)
            mutableState.update { it.copy(updateResult = result) }
        }
    }

    fun installUpdate() {
        val release = mutableState.value.updateResult as? UpdateResult.Available ?: return
        viewModelScope.launch { updateInstaller.install(release) }
    }

    fun reportFailure() = showMessage(R.string.operation_failed)

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun showMessage(message: Int) {
        mutableState.update { it.copy(message = message) }
    }

    private fun operation(errorMessage: Int = R.string.operation_failed, block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showMessage(errorMessage)
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val HISTORY_CAP = 10_000
        const val SEVEN_DAYS_MILLIS = 7 * 24 * 60 * 60 * 1000L
    }
}
