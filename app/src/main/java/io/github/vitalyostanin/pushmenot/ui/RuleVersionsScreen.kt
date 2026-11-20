package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.storage.NotificationStatistics
import io.github.vitalyostanin.pushmenot.storage.RuleChange
import io.github.vitalyostanin.pushmenot.storage.RuleVersionSummary
import io.github.vitalyostanin.pushmenot.transfer.ArchivePreview
import io.github.vitalyostanin.pushmenot.transfer.ImportPreview
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Date

@Composable
internal fun RuleVersionsScreen(
    versions: List<RuleVersionSummary>,
    activeId: String?,
    callbacks: ScreenCallbacks,
    onBack: () -> Unit,
) {
    Column {
        ScreenHeader(stringResource(R.string.rule_version_history), onBack)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(versions, key = RuleVersionSummary::revisionId) { version ->
                Panel(Modifier.testTag("version_${version.revisionId}")) {
                    val current = version.revisionId == activeId
                    if (current) Text(stringResource(R.string.rule_version_current), fontWeight = FontWeight.SemiBold)
                    Title(versionDate(version.createdAtMillis))
                    val cause = when (version.reason) {
                        RuleChange.MANUAL.name -> R.string.rule_version_manual
                        RuleChange.APPEND.name -> R.string.rule_version_append
                        RuleChange.REPLACE.name -> R.string.rule_version_replace
                        RuleChange.RESTORE.name -> R.string.rule_version_restore
                        RuleChange.ARCHIVE.name -> R.string.rule_version_archive
                        else -> R.string.rule_version_baseline
                    }
                    Help(stringResource(R.string.rule_version_summary, stringResource(cause), version.count))
                    if (!current) {
                        ActionButton(
                            R.string.rule_version_compare, { callbacks.prepareRuleRestore(version.revisionId) },
                            primary = false, tag = "compare_${version.revisionId}",
                        )
                    }
                }
            }
            item {
                Panel {
                    ActionButton(R.string.backup_save, callbacks.beginArchiveSave, primary = false, tag = "backup_save")
                    ActionButton(R.string.backup_open, callbacks.beginArchiveOpen, primary = false, tag = "backup_open")
                    Help(stringResource(R.string.backup_description))
                }
            }
        }
    }
}

@Composable
internal fun VersionRestoreScreen(
    preview: ImportPreview,
    labels: Map<String, String>,
    confirm: () -> Unit,
    cancel: () -> Unit,
) {
    val snapshot = requireNotNull(preview.restoreSnapshot)
    Column {
        ScreenHeader(stringResource(R.string.rule_version_restore_title))
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel {
                    Title(stringResource(
                        R.string.rule_version_summary, versionDate(snapshot.createdAtMillis), snapshot.count,
                    ))
                    Text(stringResource(R.string.import_add, preview.addedIds.size))
                    RuleDifference(preview, labels)
                }
            }
            item { Panel { Help(stringResource(R.string.rule_version_restore_help)) } }
            item { ActionButton(R.string.rule_version_restore_title, confirm, tag = "version_restore") }
            item { ActionButton(R.string.cancel, cancel, primary = false) }
        }
    }
}

@Composable
internal fun BackupReviewScreen(preview: ArchivePreview, confirm: () -> Unit, cancel: () -> Unit) {
    Column {
        ScreenHeader(stringResource(R.string.backup_review))
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel {
                    Text(stringResource(R.string.backup_added, preview.missingVersions))
                    Text(stringResource(R.string.backup_active, preview.activeCount))
                }
            }
            item { Panel { Help(stringResource(R.string.backup_restore_help)) } }
            item { ActionButton(R.string.backup_restore, confirm, tag = "backup_restore") }
            item { ActionButton(R.string.cancel, cancel, primary = false) }
        }
    }
}

@Composable
internal fun JournalStatistics(statistics: NotificationStatistics) {
    Panel(Modifier.testTag("journal_statistics")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Help(stringResource(R.string.stats_today))
                Title(stringResource(R.string.stats_hidden_of, statistics.today.hidden, statistics.today.received))
            }
            Column(Modifier.weight(1f)) {
                Help(stringResource(R.string.stats_week))
                Title(stringResource(R.string.stats_hidden_of, statistics.week.hidden, statistics.week.received))
            }
        }
    }
    val zone = ZoneId.systemDefault()
    val weekStart = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant().toEpochMilli()
    if (statistics.startedAtMillis > weekStart) {
        Notice(R.string.stats_partial, warning = true) {
            Help(stringResource(R.string.stats_started, versionDate(statistics.startedAtMillis)))
        }
    }
}

@Composable
private fun versionDate(millis: Long?): String {
    if (millis == null) return stringResource(R.string.rule_version_date_unknown)
    val locale = LocalConfiguration.current.locales[0]
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(millis))
}
