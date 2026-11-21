package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.preview.HistoryPreview
import io.github.vitalyostanin.pushmenot.rules.ModeScope
import io.github.vitalyostanin.pushmenot.rules.unmatchedAction
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import kotlinx.coroutines.CancellationException

@Composable
internal fun UnmatchedModeScreen(
    packageName: String,
    label: String,
    rules: List<FilterRule>,
    modes: Map<ModeScope, Action>,
    filtering: Boolean,
    loadSample: suspend (String) -> List<JournalItem>,
    save: (ModeScope, Action?) -> Unit,
    back: () -> Unit,
) {
    var channel by rememberSaveable(packageName) { mutableStateOf("") }
    val scope = ModeScope(packageName, channel.takeIf(String::isNotEmpty))
    val saved = modes[scope] ?: Action.KEEP.takeIf { channel.isEmpty() }
    var draft by rememberSaveable(scope, saved) { mutableStateOf(saved) }
    val sampleResult by produceState<Result<List<JournalItem>>?>(null, packageName) {
        value = try {
            Result.success(loadSample(packageName))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val rows = sampleResult?.getOrNull().orEmpty()
    val channelLabels = remember(rows) {
        rows.mapNotNull { row -> row.channelId?.let { it to row.channelName } }.toMap()
    }
    val channels = remember(channelLabels, rules, modes, packageName) {
        val stored = modes.keys.filter { it.packageName == packageName }.mapNotNull(ModeScope::channelId)
        val ruleChannels = rules.filter { it.packageName == packageName }.mapNotNull(FilterRule::channelId)
        listOf("") + (channelLabels.keys + stored + ruleChannels).distinct().sortedBy { channelLabels[it] ?: it }
    }
    val candidate = remember(modes, scope, draft) {
        if (draft == null || (scope.channelId == null && draft == Action.KEEP)) modes - scope
        else modes + (scope to checkNotNull(draft))
    }
    val previewRows = remember(rows, rules, candidate, channel) {
        val notifications = rows.filter { channel.isEmpty() || it.channelId == channel }.take(4)
        val byId = HistoryPreview.evaluate(notifications, rules) {
            candidate.unmatchedAction(packageName, it.channelId)
        }.associateBy { it.journalId }
        notifications.map { it to byId[it.id] }
    }

    Column {
        ScreenHeader(stringResource(R.string.unmatched_title), back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel {
                    Title(label)
                    Choice(R.string.unmatched_scope, channel, channels, {
                        if (it.isEmpty()) stringResource(R.string.unmatched_all_channels) else channelLabels[it] ?: it
                    }) { channel = it }
                    Column(Modifier.selectableGroup()) {
                        if (channel.isNotEmpty()) {
                            ModeChoice(R.string.unmatched_inherit, draft == null) { draft = null }
                        }
                        ModeChoice(R.string.keep_action, draft == Action.KEEP) { draft = Action.KEEP }
                        ModeChoice(R.string.hide_action, draft == Action.HIDE) { draft = Action.HIDE }
                    }
                    if (!filtering) Help(stringResource(R.string.unmatched_filtering_off))
                }
            }
            item {
                Panel {
                    Title(stringResource(R.string.unmatched_preview))
                    when {
                        sampleResult == null -> Help(stringResource(R.string.unmatched_loading))
                        sampleResult?.isFailure == true -> Help(stringResource(R.string.operation_failed))
                        previewRows.isEmpty() -> Help(stringResource(R.string.unmatched_no_examples))
                        else -> for ((row, result) in previewRows) {
                            val name = row.title ?: row.channelName ?: stringResource(R.string.text_not_saved)
                            val outcome = when {
                                !filtering -> R.string.filtering_off
                                result == null || !result.complete -> R.string.history_current_unavailable
                                result.decision.action == Action.HIDE -> R.string.unmatched_will_hide
                                else -> R.string.unmatched_will_keep
                            }
                            Text(name)
                            Help(stringResource(outcome))
                        }
                    }
                }
            }
            if (candidate.unmatchedAction(packageName, channel.takeIf(String::isNotEmpty)) == Action.HIDE) {
                item { Notice(R.string.unmatched_warning, warning = true) {} }
            }
            item {
                ActionButton(R.string.unmatched_save, { save(scope, draft) }, enabled = draft != saved, tag = "save_unmatched")
            }
        }
    }
}

@Composable
private fun ModeChoice(label: Int, selected: Boolean, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = choose),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label))
    }
}
