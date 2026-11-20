package io.github.vitalyostanin.pushmenot.ui

import android.service.notification.NotificationListenerService
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import com.pushmenot.core.Protection
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.listener.ShadeCard
import io.github.vitalyostanin.pushmenot.sources.JournalApps
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.SourceSettings
import java.util.Date

@Composable
internal fun HistoryScreen(
    state: ScreenState,
    labels: Map<String, String>,
    callbacks: ScreenCallbacks,
    filters: HistoryFilters,
    query: String,
    focused: Boolean,
    search: (String) -> Unit,
    apply: (HistoryFilters) -> Unit,
    reset: () -> Unit,
    onBack: () -> Unit,
    openSource: (String) -> Unit,
    showRule: (String) -> Unit,
    createFromEntry: (JournalItem) -> Unit,
    createFromShade: (ShadeCard) -> Unit,
    openSettings: (SettingsSection) -> Unit,
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var choosingApps by rememberSaveable { mutableStateOf(false) }
    val packages = filters.packageNames
    val onlyApp = packages.singleOrNull()
    val appLabel = onlyApp?.let { labels[it] ?: it }
    val isFiltered = query.isNotBlank() ||
        filters.copy(packageNames = if (focused) emptySet() else packages) != HistoryFilters()
    val observeShade = !focused && !isFiltered && packages.isEmpty() &&
        state.foreground && state.accessGranted && state.listenerConnected
    val journalApps = rememberJournalApps(state.foreground)
    var shade by remember(observeShade) { mutableStateOf<List<ShadeCard>?>(null) }
    LaunchedEffect(observeShade, callbacks) {
        if (observeShade) callbacks.observeShade().collect { shade = it }
    }
    val selectionTitle = when (packages.size) {
        0 -> stringResource(R.string.all_apps)
        1, 2 -> packages.joinToString(", ") { labels[it] ?: it }
        else -> stringResource(R.string.apps_selected_count, packages.size)
    }
    val title = if (focused && appLabel != null) stringResource(R.string.app_history_title, appLabel)
        else stringResource(R.string.history)
    Column {
        ScreenHeader(title, onBack.takeIf { focused })
        LazyColumn(
            modifier = Modifier.testTag("history_list"),
            contentPadding = screenPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            historyWarnings(state, focused, openSettings)
            shade?.let { shadeCards(it, state.history.isNotEmpty(), labels, journalApps, createFromShade) }
            if (!focused && state.statistics != null) item { JournalStatistics(state.statistics) }
            if (!focused) {
                item {
                    OutlinedButton(
                        onClick = { choosingApps = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("history_apps"),
                        shape = buttonShape,
                    ) { Text(selectionTitle) }
                }
            }
            item {
                OutlinedTextField(
                    query,
                    search,
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.search_history)) },
                    singleLine = true,
                    shape = buttonShape,
                )
            }
            if (isFiltered) {
                item {
                    val active = listOfNotNull(
                        query.takeIf { it.isNotBlank() }?.let { stringResource(R.string.active_search, it) },
                        filters.period.takeUnless { it == HistoryPeriod.ALL }?.let { stringResource(it.label()) },
                        filters.channelId?.let { stringResource(R.string.channel_detail, it) },
                        filters.sender?.let { stringResource(R.string.sender_detail, it) },
                        filters.verdict?.let { stringResource(if (it == Action.KEEP.name) R.string.keep_action else R.string.hide_action) },
                    )
                    if (active.isNotEmpty()) Help(active.joinToString(" · "))
                    ActionButton(R.string.reset_filters, reset, primary = false, tag = "history_reset")
                }
            }
            item {
                ActionButton(
                    if (filtersOpen) R.string.hide_history_filters else R.string.show_history_filters,
                    { filtersOpen = !filtersOpen },
                    primary = false,
                )
            }
            if (filtersOpen) item { HistoryFilterPanel(filters, apply) }
            val records = state.history.filter { it.kind != JournalItem.KIND_GAP }
            if (records.isEmpty()) {
                item {
                    Notice(if (isFiltered) R.string.no_matches else R.string.no_history) {
                        if (!isFiltered && state.accessGranted && state.listenerConnected) {
                            Help(stringResource(R.string.history_waiting_help))
                        }
                    }
                }
            }
            items(state.history, key = JournalItem::id) { entry ->
                val currentRule = state.rules.firstOrNull {
                    it.id == entry.ruleId && it.revision == entry.ruleRevision
                }
                HistoryRecord(
                    entry,
                    journalApps,
                    entry.packageName?.let(labels::get),
                    currentRule,
                    openSource,
                    showRule,
                    createFromEntry,
                )
            }
            if (state.historyHasMore) item { ActionButton(R.string.load_more, callbacks.loadMoreHistory, primary = false) }
        }
    }
    if (choosingApps) {
        val eligible = state.historyApps + state.sources.filter(SourceSettings::enabled).map(SourceSettings::packageName) + packages
        HistoryAppsDialog(eligible, labels, packages, cancel = { choosingApps = false }) { selection ->
            choosingApps = false
            apply(filters.copy(packageNames = selection))
        }
    }
}

private fun LazyListScope.shadeCards(
    cards: List<ShadeCard>,
    hasHistory: Boolean,
    labels: Map<String, String>,
    journalApps: JournalApps,
    createRule: (ShadeCard) -> Unit,
) {
    if (cards.isEmpty() && !hasHistory) return
    val noSuggestions = cards.none(ShadeCard::canSuggestRule)
    item("shade-heading") {
        Title(stringResource(R.string.shade_now))
        if (noSuggestions) Help(stringResource(R.string.shade_no_suggestions))
        if (noSuggestions && hasHistory) Help(stringResource(R.string.shade_history_fallback))
    }
    items(cards) { card ->
        Panel {
            JournalAppHeader(journalApps, card.packageName, labels[card.packageName])
            card.channelId?.let { Help(stringResource(R.string.channel_detail, it)) }
            JournalNotificationTitle(card.title)
            card.body?.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 3) }
            when {
                card.canSuggestRule -> {
                    val suggestion = when {
                        card.suggestsTitle && card.channelId != null -> R.string.shade_suggest_title_channel
                        card.suggestsTitle -> R.string.shade_suggest_title
                        card.channelId != null -> R.string.shade_suggest_body_channel
                        else -> R.string.shade_suggest_body
                    }
                    Help(stringResource(suggestion))
                    ActionButton(
                        R.string.shade_review_rule, { createRule(card) }, primary = false,
                        tag = "shade_rule_${card.packageName}",
                    )
                }
                card.protection == Protection.ONGOING -> {
                    Help(stringResource(R.string.shade_ongoing))
                }
                card.protection != null -> Help(stringResource(R.string.protected_entry))
                else -> Help(stringResource(R.string.shade_no_rule_for_card))
            }
        }
    }
}

private fun LazyListScope.historyWarnings(
    state: ScreenState,
    focused: Boolean,
    openSettings: (SettingsSection) -> Unit,
) {
    if (!state.accessGranted) {
        item {
            Notice(R.string.access_off, warning = true) {
                Help(stringResource(R.string.history_access_off))
                ActionButton(R.string.settings, { openSettings(SettingsSection.ACCESS) }, primary = false)
            }
        }
    } else if (!state.listenerConnected) {
        item {
            Notice(R.string.listener_not_ready, warning = true) {
                Help(stringResource(R.string.listener_not_ready_help))
                Help(stringResource(R.string.listener_restart_help))
                ActionButton(R.string.settings, { openSettings(SettingsSection.ACCESS) }, primary = false)
            }
        }
    }
    if (state.accessGranted && !focused) {
        if (state.backgroundStatus?.ignoresBatteryOptimizations == false) {
            item {
                Notice(R.string.battery_optimization_on, warning = true) {
                    Help(stringResource(R.string.battery_optimization_help))
                    ActionButton(R.string.settings, { openSettings(SettingsSection.BATTERY) }, primary = false, tag = "battery_optimization_settings")
                }
            }
        }
        if (state.backgroundStatus?.restricted == true) {
            item {
                Notice(R.string.background_restricted, warning = true) {
                    Help(stringResource(R.string.background_restricted_help))
                    ActionButton(R.string.settings, { openSettings(SettingsSection.BACKGROUND) }, primary = false, tag = "background_app_settings")
                }
            }
        }
    }
}

@Composable
private fun HistoryAppsDialog(
    eligible: Set<String>,
    labels: Map<String, String>,
    selectedApps: Set<String>,
    cancel: () -> Unit,
    apply: (Set<String>) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(ArrayList(selectedApps)) }
    val orderedApps = eligible.sortedWith(compareBy({ labels[it] ?: it }, { it }))
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.apps_in_history)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Help(stringResource(R.string.history_app_selection_help))
                AppCheckRow(stringResource(R.string.all_apps), draft.isEmpty()) { draft = arrayListOf() }
                for (packageName in orderedApps) {
                    val label = labels[packageName] ?: packageName
                    val disambiguate = orderedApps.count { (labels[it] ?: it) == label } > 1
                    AppCheckRow(if (disambiguate) "$label · $packageName" else label, packageName in draft) { checked ->
                        draft = ArrayList(if (checked) draft + packageName else draft - packageName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(draft.toSet()) }, modifier = Modifier.testTag("history_apps_apply")) {
                Text(stringResource(R.string.apply_filters))
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AppCheckRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = change),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun HistoryRecord(
    entry: JournalItem,
    journalApps: JournalApps,
    label: String?,
    currentRule: FilterRule?,
    openSource: (String) -> Unit,
    showRule: (String) -> Unit,
    createRule: (JournalItem) -> Unit,
) {
    val context = LocalContext.current
    val formatter = android.text.format.DateFormat.getTimeFormat(context)
    val time = formatter.format(Date(entry.observedAtMillis))
    if (entry.kind == JournalItem.KIND_GAP) {
        Notice(R.string.history_gap, warning = true) {
            Help(stringResource(R.string.history_gap_time, time))
        }
        return
    }
    Panel(Modifier.testTag("history_entry_${entry.id}")) {
        val packageName = entry.packageName
        JournalAppHeader(
            apps = journalApps,
            packageName = packageName,
            knownLabel = label,
            time = time,
            openSource = packageName?.let { { openSource(it) } },
        )
        entry.channelId?.let { Help(stringResource(R.string.channel_detail, it)) }
        JournalNotificationTitle(entry.title)
        listOfNotNull(entry.body, entry.archivedText).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString("\n"))
        }
        entry.sender?.let { Help(stringResource(R.string.sender_detail, it)) }
        val hiddenByRule = entry.verdict == Action.HIDE.name && entry.dismissalRequestedAtMillis != null &&
            entry.removedAtMillis != null && entry.removalReason == NotificationListenerService.REASON_LISTENER_CANCEL
        val status = when (entry.verdict) {
            null -> R.string.not_filtered
            Action.HIDE.name -> if (hiddenByRule) R.string.hidden_by_rule else R.string.hide_decision
            else -> R.string.kept
        }
        Text(
            stringResource(status),
            color = when (status) {
                R.string.kept -> accent
                R.string.not_filtered -> secondaryInk
                else -> Color(0xff79480c)
            },
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        entry.removedAtMillis?.let { removedAt ->
            val removed = when (entry.removalReason) {
                NotificationListenerService.REASON_CANCEL,
                NotificationListenerService.REASON_CANCEL_ALL -> R.string.card_dismissed_manually_at
                else -> R.string.card_dismissed_at
            }
            Help(stringResource(removed, formatter.format(Date(removedAt))))
        } ?: entry.dismissalRequestedAtMillis?.let { Help(stringResource(R.string.removal_requested)) }
        when {
            entry.ruleId != null -> {
                val revision = entry.ruleRevision
                if (revision != null) {
                    val scopeLabel = if (entry.ruleScope == "ALL_SELECTED_APPS") R.string.global_rule else R.string.app_rules
                    Help(stringResource(R.string.matched_rule_revision, entry.ruleId, revision, stringResource(scopeLabel)))
                } else {
                    Help(stringResource(R.string.matched_rule, entry.ruleId))
                }
                if (currentRule != null) {
                    ActionButton(R.string.view_rule, { showRule(currentRule.id) }, primary = false)
                } else {
                    Help(stringResource(R.string.rule_revision_unavailable))
                }
            }
            entry.decisionReason == "PROTECTED" -> Help(stringResource(R.string.protected_entry))
            entry.decisionReason == "CONTENT_UNAVAILABLE" -> Help(stringResource(R.string.content_unavailable))
            entry.decisionReason == "NO_MATCH" -> Help(stringResource(R.string.kept_no_rule))
        }
        if (!entry.contentRetained) Help(stringResource(R.string.text_not_saved))
        if (entry.packageName != null) {
            ActionButton(R.string.create_from_entry, { createRule(entry) }, primary = false, tag = "entry_new_rule")
        }
    }
}

@Composable
private fun HistoryFilterPanel(filters: HistoryFilters, apply: (HistoryFilters) -> Unit) {
    var channel by rememberSaveable(filters.channelId) { mutableStateOf(filters.channelId.orEmpty()) }
    var sender by rememberSaveable(filters.sender) { mutableStateOf(filters.sender.orEmpty()) }
    Panel {
        RuleField(R.string.channel_field, channel, { channel = it }, maxLength = 256)
        RuleField(R.string.sender_field, sender, { sender = it }, maxLength = 1024)
        Choice(
            R.string.decision_field,
            filters.verdict.orEmpty(),
            listOf("", Action.KEEP.name, Action.HIDE.name),
            {
                stringResource(when (it) {
                    Action.KEEP.name -> R.string.keep_action
                    Action.HIDE.name -> R.string.hide_action
                    else -> R.string.any_decision
                })
            },
        ) { apply(filters.copy(verdict = it.takeIf(String::isNotEmpty))) }
        Choice(R.string.period_field, filters.period, HistoryPeriod.entries, {
            stringResource(it.label())
        }) { apply(filters.copy(period = it)) }
        ActionButton(R.string.apply_filters, {
            apply(filters.copy(channelId = channel.trim().ifEmpty { null }, sender = sender.trim().ifEmpty { null }))
        })
    }
}

private fun HistoryPeriod.label(): Int = when (this) {
    HistoryPeriod.ALL -> R.string.any_period
    HistoryPeriod.TODAY -> R.string.today
    HistoryPeriod.LAST_SEVEN_DAYS -> R.string.last_seven_days
}
