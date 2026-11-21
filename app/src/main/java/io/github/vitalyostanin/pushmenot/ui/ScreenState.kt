package io.github.vitalyostanin.pushmenot.ui

import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.rules.ModeScope
import io.github.vitalyostanin.pushmenot.reliability.BackgroundStatus
import io.github.vitalyostanin.pushmenot.sources.InstalledSource
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.NotificationStatistics
import io.github.vitalyostanin.pushmenot.storage.RuleVersionSummary
import io.github.vitalyostanin.pushmenot.storage.SourceSettings
import io.github.vitalyostanin.pushmenot.shortcut.HistoryShortcutState
import io.github.vitalyostanin.pushmenot.transfer.ArchivePreview
import io.github.vitalyostanin.pushmenot.transfer.ImportPreview
import io.github.vitalyostanin.pushmenot.updates.UpdateInstallStatus
import io.github.vitalyostanin.pushmenot.updates.UpdateResult

data class ScreenState(
    val accessGranted: Boolean,
    val listenerConnected: Boolean,
    val installedApps: List<InstalledSource>,
    val sources: List<SourceSettings>,
    val history: List<JournalItem>,
    val rules: List<FilterRule>,
    val modes: Map<ModeScope, Action> = emptyMap(),
    val historyHasMore: Boolean = false,
    val historyQuery: String = "",
    val importPreview: ImportPreview? = null,
    val message: Int? = null,
    val rulesCanUndo: Boolean = false,
    val historyFilters: HistoryFilters = HistoryFilters(),
    val historyApps: Set<String> = emptySet(),
    val backgroundStatus: BackgroundStatus? = null,
    val journalCapacity: Int? = null,
    val retainContent: Boolean? = null,
    val historyShortcut: HistoryShortcutState? = null,
    val historyShortcutOpen: String? = null,
    val statistics: NotificationStatistics? = null,
    val ruleVersions: List<RuleVersionSummary> = emptyList(),
    val activeSnapshotId: String? = null,
    val archivePreview: ArchivePreview? = null,
    val foreground: Boolean = false,
    val installedVersion: String = "",
    val updateResult: UpdateResult = UpdateResult.Idle,
    val updateInstallStatus: UpdateInstallStatus = UpdateInstallStatus.Idle,
)
