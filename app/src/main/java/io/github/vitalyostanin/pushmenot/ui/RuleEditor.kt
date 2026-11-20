package io.github.vitalyostanin.pushmenot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import com.pushmenot.core.FrequencyCondition
import com.pushmenot.core.FrequencyGroup
import com.pushmenot.core.TextCondition
import com.pushmenot.core.TextOperation
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.preview.RulePreview
import io.github.vitalyostanin.pushmenot.sources.InstalledSource
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import java.text.NumberFormat
import java.util.UUID
import kotlinx.coroutines.delay

internal fun ruleFromEntry(entry: JournalItem): FilterRule = FilterRule(
    id = UUID.randomUUID().toString(),
    revision = 1,
    packageName = requireNotNull(entry.packageName),
    action = Action.HIDE,
    channelId = entry.channelId?.takeIf { it.isNotBlank() && it.length <= 256 },
    title = entry.title?.takeIf { it.isNotBlank() && it.length <= 1024 }?.let { TextCondition(it) },
)

@Composable
internal fun RuleEditor(
    initial: FilterRule?,
    installedApps: List<InstalledSource>,
    configuredApps: List<String>,
    selectedAppCount: Int,
    fixedSource: FilterRule?,
    enableSource: Boolean,
    newRulePackage: String?,
    newRuleGlobal: Boolean,
    previewRule: suspend (FilterRule, Boolean) -> RulePreview,
    onSave: (FilterRule, Boolean) -> Unit,
    onDelete: ((String) -> Unit)?,
    onCancel: () -> Unit,
) {
    val draft = remember(initial, newRulePackage, newRuleGlobal) { RuleDraft(initial, newRulePackage, newRuleGlobal) }
    val labels = remember(installedApps) { installedApps.associate { it.packageName to it.label } }
    val apps = remember(installedApps, configuredApps, initial) {
        (configuredApps + installedApps.map(InstalledSource::packageName) + listOfNotNull(initial?.packageName, newRulePackage))
            .distinct()
    }
    val sourceLabel = fixedSource?.packageName?.let { labels[it] ?: it }
    val candidate = draft.toRule()?.takeIf {
        fixedSource == null || it.title != null || it.body != null || it.sender != null
    }
    var preview by remember(candidate, enableSource) { mutableStateOf<RulePreview?>(null) }
    var previewFailed by remember(candidate, enableSource) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(candidate, enableSource) {
        if (candidate != null) {
            delay(300)
            preview = try {
                previewRule(candidate, enableSource)
            } catch (exception: IllegalArgumentException) {
                previewFailed = true
                null
            }
        }
    }

    Column {
        val heading = when {
            draft.global -> R.string.global_rule
            onDelete == null -> R.string.new_rule
            else -> R.string.edit_rule
        }
        ScreenHeader(stringResource(heading), onCancel)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fixedSource != null && sourceLabel != null) {
                item {
                    Panel {
                        Title(sourceLabel)
                        fixedSource.channelId?.let { Help(stringResource(R.string.channel_detail, it)) }
                    }
                }
            } else {
                item {
                    Choice(
                        R.string.rule_apply_to,
                        draft.global,
                        listOf(false, true),
                        { global -> stringResource(if (global) R.string.rule_all_selected else R.string.rule_one_app) },
                        { draft.global = it },
                    )
                    if (draft.global) {
                        Help(stringResource(R.string.global_scope_help))
                        Help(stringResource(R.string.affected_apps, selectedAppCount))
                    }
                }
                if (!draft.global) {
                    item {
                        Choice(
                            R.string.app_field,
                            draft.packageName,
                            apps,
                            { labels[it] ?: it },
                            { draft.packageName = it },
                        )
                    }
                }
                item {
                    RuleField(R.string.channel_field, draft.channelId, { draft.channelId = it }, maxLength = 256)
                }
            }
            item { ConditionEditor(R.string.title_field, draft.title) }
            item { ConditionEditor(R.string.text_field, draft.body) }
            item { ConditionEditor(R.string.sender_field, draft.sender) }
            item {
                Choice(R.string.action_field, draft.action, Action.entries, { stringResource(it.label()) }) {
                    draft.action = it
                }
            }
            if (draft.action == Action.HIDE) {
                item {
                    TogglePanel(
                        R.string.frequency,
                        if (draft.frequencyEnabled) R.string.frequency_on else R.string.frequency_off,
                        draft.frequencyEnabled,
                        "rule_frequency",
                    ) { draft.frequencyEnabled = it }
                }
                if (draft.frequencyEnabled) {
                    item {
                        RuleField(R.string.frequency_count, draft.frequencyCount, { draft.frequencyCount = it }, 10, numeric = true)
                    }
                    item {
                        RuleField(R.string.frequency_minutes, draft.frequencyMinutes, { draft.frequencyMinutes = it }, 12, numeric = true)
                    }
                    item {
                        Choice(R.string.frequency_group, draft.group, FrequencyGroup.entries, { stringResource(it.label()) }) {
                            draft.group = it
                        }
                    }
                }
            }
            if (onDelete != null) {
                item {
                    TogglePanel(
                        R.string.rule_enabled,
                        if (draft.enabled) R.string.rule_on_help else R.string.rule_off_help,
                        draft.enabled,
                        "rule_enabled",
                    ) { draft.enabled = it }
                }
            }
            item { PreviewPanel(candidate, preview, previewFailed) }
            if (onDelete != null) {
                item { ActionButton(R.string.delete_rule, { deleting = true }, primary = false) }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (enableSource && sourceLabel != null) {
                Help(stringResource(R.string.shade_enable_source_help, sourceLabel))
            }
            ActionButton(
                if (enableSource) R.string.shade_enable_and_save else R.string.save_rule,
                { candidate?.let { onSave(it, enableSource) } },
                enabled = candidate != null,
                tag = "rule_save",
            )
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
    if (deleting && onDelete != null && initial != null) {
        Confirmation(
            R.string.delete_rule,
            R.string.confirm_delete_rule,
            R.string.delete_action,
            confirm = { deleting = false; onDelete(initial.id) },
            cancel = { deleting = false },
        )
    }
}

@Composable
private fun PreviewPanel(candidate: FilterRule?, result: RulePreview?, failed: Boolean) {
    val preview = result?.entries
    val incomplete = preview?.any { !it.complete } == true
    Notice(if (incomplete) R.string.preview_incomplete else R.string.preview, warning = incomplete) {
        result?.limitedTo?.let { count ->
            val locale = LocalConfiguration.current.locales[0]
            val limit = remember(count, locale) { NumberFormat.getIntegerInstance(locale).format(count) }
            Help(stringResource(R.string.preview_limit, limit))
        }
        when {
            candidate == null -> Help(stringResource(R.string.preview_enter_fields))
            failed -> Help(stringResource(R.string.preview_failed))
            !candidate.enabled -> Help(stringResource(R.string.preview_disabled))
            preview == null -> Help(stringResource(R.string.preview_loading))
            preview.isEmpty() -> Help(stringResource(R.string.preview_empty))
            else -> {
                val hidden = preview.count { it.complete && it.decision.action == Action.HIDE }
                val kept = preview.count { it.complete && it.decision.action == Action.KEEP }
                Text(stringResource(R.string.preview_counts, hidden, kept))
                val changed = preview.count {
                    it.complete && it.previousDecision?.action?.let { action -> action != it.decision.action } == true
                }
                if (preview.any { it.previousDecision != null }) Help(stringResource(R.string.preview_changed, changed))
                val missing = preview.count { !it.complete }
                if (missing > 0) Help(stringResource(R.string.preview_missing, missing))
            }
        }
    }
}

@Composable
internal fun RuleSummary(rule: FilterRule) {
    Text(stringResource(rule.action.label()))
    rule.channelId?.let { Help(stringResource(R.string.channel_detail, it)) }
    rule.title?.let { ConditionSummary(R.string.title_field, it) }
    rule.body?.let { ConditionSummary(R.string.text_field, it) }
    rule.sender?.let { ConditionSummary(R.string.sender_field, it) }
    rule.frequency?.let {
        Help(stringResource(R.string.frequency_detail, it.allowedCount, it.windowMillis / 60_000))
    }
}

@Composable
private fun ConditionSummary(label: Int, condition: TextCondition) {
    val format = if (condition.operation == TextOperation.EQUALS) R.string.condition_equals else R.string.condition_contains
    Help(stringResource(format, stringResource(label), condition.value))
}

@Composable
private fun ConditionEditor(label: Int, draft: ConditionDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RuleField(label, draft.value, { draft.value = it }, 1024)
        if (draft.value.isNotBlank()) {
            Choice(
                R.string.match_field,
                draft.operation,
                TextOperation.entries,
                { stringResource(if (it == TextOperation.EQUALS) R.string.match_equals else R.string.match_contains) },
                { draft.operation = it },
            )
            TextButton(onClick = { draft.ignoreCase = !draft.ignoreCase }) {
                Text(stringResource(if (draft.ignoreCase) R.string.ignore_case else R.string.match_case))
            }
        }
    }
}

@Composable
internal fun RuleField(label: Int, value: String, change: (String) -> Unit, maxLength: Int, numeric: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength && (!numeric || it.all { char -> char in '0'..'9' })) change(it) },
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
    )
}

@Composable
internal fun <T> Choice(
    label: Int,
    selected: T?,
    options: List<T>,
    text: @Composable (T) -> String,
    change: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val prompt = stringResource(label)
    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = options.isNotEmpty(),
        ) {
            Text(if (selected == null) prompt else stringResource(R.string.selected_option, prompt, text(selected)))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(text(option)) },
                    onClick = {
                        expanded = false
                        change(option)
                    },
                )
            }
        }
    }
}

private class RuleDraft(initial: FilterRule?, newRulePackage: String?, newRuleGlobal: Boolean) {
    val id = initial?.id ?: UUID.randomUUID().toString()
    val revision = initial?.revision ?: 1
    var packageName by mutableStateOf(initial?.packageName ?: newRulePackage)
    var global by mutableStateOf(if (initial != null) initial.packageName == null else newRuleGlobal)
    var channelId by mutableStateOf(initial?.channelId.orEmpty())
    var action by mutableStateOf(initial?.action ?: Action.HIDE)
    var enabled by mutableStateOf(initial?.enabled ?: true)
    val title = ConditionDraft(initial?.title)
    val body = ConditionDraft(initial?.body)
    val sender = ConditionDraft(initial?.sender)
    var frequencyEnabled by mutableStateOf(initial?.frequency != null)
    var frequencyCount by mutableStateOf(initial?.frequency?.allowedCount?.toString() ?: "2")
    private val oldWindow = initial?.frequency?.windowMillis
    private val initialMinutes = oldWindow?.div(60_000)?.coerceAtLeast(1)?.toString() ?: "30"
    var frequencyMinutes by mutableStateOf(initialMinutes)
    var group by mutableStateOf(initial?.frequency?.groupBy ?: FrequencyGroup.APP)

    fun toRule(): FilterRule? {
        val app = if (global) null else packageName ?: return null
        val frequency = if (frequencyEnabled && action == Action.HIDE) {
            val count = frequencyCount.toIntOrNull()?.takeIf { it in 1 until Int.MAX_VALUE } ?: return null
            val minutes = frequencyMinutes.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 60_000 } ?: return null
            val window = if (oldWindow != null && frequencyMinutes == initialMinutes) oldWindow else minutes * 60_000
            FrequencyCondition(count, window, group)
        } else {
            null
        }
        return FilterRule(
            id, revision, app, action, channelId.trim().takeIf(String::isNotEmpty),
            title.condition(), body.condition(), sender.condition(), frequency, enabled,
        )
    }
}

private class ConditionDraft(initial: TextCondition?) {
    var value by mutableStateOf(initial?.value.orEmpty())
    var operation by mutableStateOf(initial?.operation ?: TextOperation.CONTAINS)
    var ignoreCase by mutableStateOf(initial?.ignoreCase ?: true)

    fun condition(): TextCondition? =
        value.takeIf(String::isNotBlank)?.let { TextCondition(it, operation, ignoreCase) }
}

internal fun Action.label(): Int = when (this) {
    Action.KEEP -> R.string.keep_action
    Action.HIDE -> R.string.hide_action
}

private fun FrequencyGroup.label(): Int = when (this) {
    FrequencyGroup.APP -> R.string.group_app
    FrequencyGroup.CHANNEL -> R.string.group_channel
    FrequencyGroup.SENDER -> R.string.group_sender
}
