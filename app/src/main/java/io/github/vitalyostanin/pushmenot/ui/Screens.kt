package io.github.vitalyostanin.pushmenot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushmenot.core.Action
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.listener.ShadeCard
import io.github.vitalyostanin.pushmenot.preview.RulePreview
import kotlinx.coroutines.flow.Flow
import io.github.vitalyostanin.pushmenot.rules.ModeScope
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.storage.JournalItem
import io.github.vitalyostanin.pushmenot.storage.SourceSettings
import io.github.vitalyostanin.pushmenot.transfer.ImportMode
import io.github.vitalyostanin.pushmenot.transfer.HistoryExportOptions
import io.github.vitalyostanin.pushmenot.updates.UpdateResult

data class ScreenCallbacks(
    val openAccess: () -> Unit,
    val openSourceSettings: (String) -> Unit,
    val openBatteryOptimizations: () -> Unit,
    val openBackgroundAppSettings: () -> Unit,
    val openLanguageSettings: () -> Unit,
    val checkForUpdates: () -> Unit,
    val openUpdateRelease: (UpdateResult.Available) -> Unit,
    val installUpdate: () -> Unit,
    val openInstallPermission: () -> Unit,
    val confirmUpdate: () -> Unit,
    val saveContentRetention: (Boolean) -> Unit,
    val updateSource: (String, Boolean) -> Unit,
    val searchHistory: (String) -> Unit,
    val loadMoreHistory: () -> Unit,
    val saveRule: (FilterRule, Boolean) -> Unit,
    val observeShade: () -> Flow<List<ShadeCard>?>,
    val deleteRule: (String) -> Unit,
    val beginImport: () -> Unit,
    val beginExport: () -> Unit,
    val shareRules: () -> Unit = {},
    val confirmImport: (ImportMode, Boolean) -> Unit,
    val configureHistoryShortcut: () -> Unit,
    val beginArchiveOpen: () -> Unit,
    val beginArchiveSave: () -> Unit,
    val prepareRuleRestore: (String) -> Unit,
    val confirmArchive: () -> Unit,
    val cancelImport: () -> Unit,
    val undoImport: () -> Unit,
    val clearHistory: () -> Unit,
    val saveJournalCapacity: (Int) -> Unit,
    val dismissMessage: () -> Unit,
    val previewRule: suspend (FilterRule, Boolean) -> RulePreview,
    val filterHistory: (HistoryFilters) -> Unit = {},
    val unmatchedModeSample: suspend (String) -> List<JournalItem> = { emptyList() },
    val saveUnmatchedMode: (ModeScope, Action?) -> Unit = { _, _ -> },
    val countHistoryExport: suspend (HistoryExportOptions) -> Int = { 0 },
    val exportHistory: (HistoryExportOptions) -> Unit = {},
)

data class HistoryFilters(
    val packageNames: Set<String> = emptySet(),
    val channelId: String? = null,
    val sender: String? = null,
    val verdict: String? = null,
    val period: HistoryPeriod = HistoryPeriod.ALL,
)

enum class HistoryPeriod { ALL, TODAY, LAST_SEVEN_DAYS }

private enum class Section(val title: Int, val tag: String) {
    HISTORY(R.string.history, "history_tab"),
    SOURCES(R.string.apps, "sources_tab"),
    RULES(R.string.rules, "rules_tab"),
    SETTINGS(R.string.settings, "settings_tab"),
}

private enum class Destination(val section: Section) {
    HISTORY(Section.HISTORY),
    APPS(Section.SOURCES),
    SETTINGS(Section.SETTINGS),
    SETTINGS_SECTION(Section.SETTINGS),
    HISTORY_EXPORT(Section.SETTINGS),
    APP(Section.SOURCES),
    APP_HISTORY(Section.HISTORY),
    RULES(Section.RULES),
    RULES_HELP(Section.RULES),
    GLOBAL_RULES(Section.RULES),
    RULE_VERSIONS(Section.RULES),
    APP_RULES(Section.RULES),
    UNMATCHED_MODE(Section.RULES),
    EDIT_RULE(Section.RULES),
    NEW_RULE(Section.RULES),
    RULE_FROM_ENTRY(Section.RULES),
    RULE_FROM_SHADE(Section.RULES),
}

private data class Route(val destination: Destination, val key: String? = null) {
    val saveKey: String get() = "${destination.name}:${key.orEmpty()}"
}

private val routeSaver = listSaver<List<Route>, String>(
    save = { routes ->
        routes.takeWhile { it.destination != Destination.RULE_FROM_SHADE }
            .flatMap { listOf(it.destination.name, it.key.orEmpty()) }
    },
    restore = { values ->
        values.chunked(2).map { (kind, key) ->
            Route(Destination.valueOf(kind), key.ifEmpty { null })
        }
    },
)

private val filtersSaver = listSaver<HistoryFilters, String>(
    save = {
        listOf(it.channelId.orEmpty(), it.sender.orEmpty(), it.verdict.orEmpty(), it.period.name) +
            it.packageNames
    },
    restore = {
        HistoryFilters(
            packageNames = it.drop(4).toSet(),
            channelId = it[0].ifEmpty { null },
            sender = it[1].ifEmpty { null },
            verdict = it[2].ifEmpty { null },
            period = HistoryPeriod.valueOf(it[3]),
        )
    },
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PushMeNotScreen(state: ScreenState, callbacks: ScreenCallbacks) {
    var routes by rememberSaveable(stateSaver = routeSaver) {
        mutableStateOf(listOf(Route(Destination.HISTORY)))
    }
    var shadeDraft by remember { mutableStateOf<FilterRule?>(null) }
    var journalFilters by rememberSaveable(stateSaver = filtersSaver) { mutableStateOf(HistoryFilters()) }
    var journalQuery by rememberSaveable { mutableStateOf("") }
    var appFilters by rememberSaveable(stateSaver = filtersSaver) { mutableStateOf(HistoryFilters()) }
    var appQuery by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberSaveableStateHolder()
    val labels = remember(state.installedApps) { state.installedApps.associate { it.packageName to it.label } }
    val settings = remember(state.sources) { state.sources.associateBy(SourceSettings::packageName) }
    val route = routes.last()
    val import = state.importPreview
    val archive = state.archivePreview
    val selectedPackages = state.sources.filter(SourceSettings::enabled).mapTo(mutableSetOf()) { it.packageName }
    val activeHistory = routes.lastOrNull {
        it.destination == Destination.HISTORY || it.destination == Destination.APP_HISTORY
    }
    val focused = activeHistory?.destination == Destination.APP_HISTORY
    val desiredFilters = if (focused) appFilters else journalFilters
    val desiredQuery = if (focused) appQuery else journalQuery

    LaunchedEffect(activeHistory, desiredFilters, desiredQuery) {
        if (activeHistory != null) {
            callbacks.filterHistory(desiredFilters)
            callbacks.searchHistory(desiredQuery)
        }
    }

    var handledShortcut by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.historyShortcutOpen) {
        if (state.historyShortcutOpen != null && state.historyShortcutOpen != handledShortcut) {
            scrollState.removeState(Route(Destination.HISTORY).saveKey + ":" + handledShortcut)
            shadeDraft = null
            handledShortcut = state.historyShortcutOpen
            journalFilters = HistoryFilters()
            journalQuery = ""
            routes = listOf(Route(Destination.HISTORY))
        }
    }

    fun open(destination: Destination, key: String? = null) {
        routes = routes + Route(destination, key)
    }

    fun back() {
        if (route.destination == Destination.RULE_FROM_SHADE) shadeDraft = null
        if (routes.size > 1) {
            routes = routes.dropLast(1)
            val previousApp = routes.lastOrNull { it.destination == Destination.APP_HISTORY }?.key
            if (previousApp != null && previousApp !in appFilters.packageNames) {
                appFilters = HistoryFilters(packageNames = setOf(previousApp))
                appQuery = ""
            }
        } else {
            routes = listOf(Route(Destination.HISTORY))
        }
    }

    fun openJournal(packageName: String) {
        appFilters = HistoryFilters(packageNames = setOf(packageName))
        appQuery = ""
        open(Destination.APP_HISTORY, packageName)
    }

    fun updateFilters(filters: HistoryFilters) {
        if (focused) appFilters = filters else journalFilters = filters
    }

    fun updateQuery(query: String) {
        if (focused) appQuery = query else journalQuery = query
    }

    BackHandler(import != null || archive != null || routes.size > 1 || route.destination != Destination.HISTORY) {
        if (import != null || archive != null) callbacks.cancelImport() else back()
    }

    GraphiteTheme {
        Surface(
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val screenKey = if (route.destination == Destination.HISTORY) {
                        route.saveKey + ":" + handledShortcut
                    } else route.saveKey
                    scrollState.SaveableStateProvider(screenKey) {
                        RenderRoute(
                            route, state, callbacks, labels, settings, selectedPackages, shadeDraft,
                            hasParent = routes.size > 1,
                            focused = focused,
                            filters = desiredFilters,
                            query = desiredQuery,
                            open = ::open,
                            back = ::back,
                            openJournal = ::openJournal,
                            updateQuery = ::updateQuery,
                            updateFilters = ::updateFilters,
                            createFromShade = {
                                shadeDraft = it.draftRule()
                                open(Destination.RULE_FROM_SHADE)
                            },
                        )
                    }
                }
                Navigation(route.destination.section) { section ->
                    if (import != null || archive != null) callbacks.cancelImport()
                    val root = when (section) {
                        Section.HISTORY -> Destination.HISTORY
                        Section.SOURCES -> Destination.APPS
                        Section.RULES -> Destination.RULES
                        Section.SETTINGS -> Destination.SETTINGS
                    }
                    shadeDraft = null
                    routes = listOf(Route(root))
                }
            }
            state.message?.let { message ->
                AlertDialog(
                    onDismissRequest = callbacks.dismissMessage,
                    title = if (message == R.string.rule_file_failed) {
                        { Text(stringResource(R.string.rule_file_failed)) }
                    } else null,
                    text = {
                        Text(stringResource(if (message == R.string.rule_file_failed) R.string.rule_file_failed_help else message))
                    },
                    confirmButton = {
                        TextButton(onClick = callbacks.dismissMessage) { Text(stringResource(R.string.ok)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun RenderRoute(
    route: Route,
    state: ScreenState,
    callbacks: ScreenCallbacks,
    labels: Map<String, String>,
    settings: Map<String, SourceSettings>,
    selectedPackages: Set<String>,
    shadeDraft: FilterRule?,
    hasParent: Boolean,
    focused: Boolean,
    filters: HistoryFilters,
    query: String,
    open: (Destination, String?) -> Unit,
    back: () -> Unit,
    openJournal: (String) -> Unit,
    updateQuery: (String) -> Unit,
    updateFilters: (HistoryFilters) -> Unit,
    createFromShade: (ShadeCard) -> Unit,
) {
    val app = route.key.orEmpty()
    val installed = labels.keys
    val import = state.importPreview
    val archive = state.archivePreview
    when {
        archive != null -> BackupReviewScreen(archive, callbacks.confirmArchive, callbacks.cancelImport)
        import?.restoreSnapshot != null -> VersionRestoreScreen(
            import, labels, { callbacks.confirmImport(ImportMode.REPLACE, false) }, callbacks.cancelImport,
        )
        import != null -> ImportScreen(import, labels, settings.filterValues { it.enabled }.keys, callbacks.confirmImport, callbacks.cancelImport)
        route.destination.isRuleEditor -> RenderRuleEditor(
            route, state, selectedPackages, shadeDraft, callbacks, back,
        )
        route.destination == Destination.APP -> SourceDetail(
            app, labels[app] ?: app, settings[app],
            openSettings = { open(Destination.SETTINGS, app) },
            openHistory = { openJournal(app) },
            openRules = { open(Destination.APP_RULES, app) },
            onBack = back,
        )
        route.destination == Destination.APPS -> SelectedAppsScreen(
            settings, labels, installed,
            add = { open(Destination.SETTINGS_SECTION, SettingsSection.APPS.name) },
            open = { open(Destination.APP, it) },
        )
        route.destination == Destination.HISTORY_EXPORT -> HistoryExportScreen(
            state.historyApps, labels, callbacks.countHistoryExport, callbacks.exportHistory, back,
        )
        route.destination == Destination.SETTINGS || route.destination == Destination.SETTINGS_SECTION -> SettingsScreen(
            state, callbacks,
            focusedPackage = route.key.takeIf { route.destination == Destination.SETTINGS },
            section = if (route.destination == Destination.SETTINGS_SECTION) SettingsSection.valueOf(app) else null,
            onBack = back.takeIf { hasParent },
            openHistoryExport = { open(Destination.HISTORY_EXPORT, null) },
        )
        route.destination == Destination.RULES -> RulesScreen(
            state.rules, labels, settings, installed, state.rulesCanUndo,
            versions = { open(Destination.RULE_VERSIONS, null) },
            help = { open(Destination.RULES_HELP, null) },
            globals = { open(Destination.GLOBAL_RULES, null) },
            appRules = { open(Destination.APP_RULES, it) },
            callbacks = callbacks,
        )
        route.destination == Destination.RULES_HELP -> RulesHelpScreen(back)
        route.destination == Destination.RULE_VERSIONS -> RuleVersionsScreen(
            state.ruleVersions, state.activeSnapshotId, callbacks, back,
        )
        route.destination == Destination.UNMATCHED_MODE -> UnmatchedModeScreen(
            app, labels[app] ?: app, state.rules, state.modes,
            app in selectedPackages, callbacks.unmatchedModeSample, callbacks.saveUnmatchedMode, back,
        )
        route.destination == Destination.GLOBAL_RULES ||
            route.destination == Destination.APP_RULES -> ScopedRulesScreen(
            app.takeIf { route.destination == Destination.APP_RULES },
            state.rules, labels, selectedPackages,
            edit = { open(Destination.EDIT_RULE, it.id) },
            openUnmatchedMode = { open(Destination.UNMATCHED_MODE, app) },
            create = { open(Destination.NEW_RULE, it) },
            onBack = back,
        )
        !state.accessGranted && state.history.isEmpty() && !focused && filters == HistoryFilters() ->
            AccessScreen(state.statistics) { open(Destination.SETTINGS_SECTION, SettingsSection.ACCESS.name) }
        else -> HistoryScreen(
            state, labels, callbacks, filters, query, focused,
            search = updateQuery,
            apply = updateFilters,
            reset = {
                updateQuery("")
                updateFilters(HistoryFilters(packageNames = if (focused) setOf(app) else emptySet()))
            },
            onBack = back,
            openSource = { open(Destination.APP, it) },
            showRule = { open(Destination.EDIT_RULE, it) },
            createFromEntry = {
                open(Destination.RULE_FROM_ENTRY, RuleJson.encode(listOf(ruleFromEntry(it))))
            },
            createFromShade = createFromShade,
            openSettings = { open(Destination.SETTINGS_SECTION, it.name) },
        )
    }
}

private val Destination.isRuleEditor: Boolean
    get() = when (this) {
        Destination.EDIT_RULE, Destination.NEW_RULE, Destination.RULE_FROM_ENTRY, Destination.RULE_FROM_SHADE -> true
        else -> false
    }

@Composable
private fun RenderRuleEditor(
    route: Route,
    state: ScreenState,
    selectedPackages: Set<String>,
    shadeDraft: FilterRule?,
    callbacks: ScreenCallbacks,
    back: () -> Unit,
) {
    val app = route.key.orEmpty()
    val initial = when (route.destination) {
        Destination.EDIT_RULE -> state.rules.firstOrNull { it.id == app }
        Destination.RULE_FROM_ENTRY -> remember(app) { RuleJson.decode(app).single() }
        Destination.RULE_FROM_SHADE -> shadeDraft
        else -> null
    }
    val fromShade = route.destination == Destination.RULE_FROM_SHADE
    if ((route.destination == Destination.EDIT_RULE || fromShade) && initial == null) {
        Column {
            ScreenHeader(stringResource(R.string.rule_unavailable), back)
            Help(stringResource(R.string.rule_unavailable_help))
        }
        return
    }
    val creating = route.destination != Destination.EDIT_RULE
    RuleEditor(
        initial,
        state.installedApps,
        state.sources.map(SourceSettings::packageName),
        selectedPackages.size,
        fixedSource = initial?.takeIf { fromShade },
        enableSource = fromShade && initial?.packageName?.let { it !in selectedPackages } == true,
        newRulePackage = app.takeIf { route.destination == Destination.NEW_RULE && it.isNotEmpty() },
        newRuleGlobal = route.destination == Destination.NEW_RULE && app.isEmpty(),
        previewRule = callbacks.previewRule,
        onSave = { rule, enable -> callbacks.saveRule(rule, enable); back() },
        onDelete = if (creating) null else ({ id -> callbacks.deleteRule(id); back() }),
        onCancel = back,
    )
}

@Composable
private fun Navigation(active: Section, select: (Section) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            for (section in Section.entries) {
                TextButton(
                    onClick = { select(section) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag(section.tag).semantics { selected = section == active },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = if (section == active) ButtonDefaults.textButtonColors(
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                    ) else ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                ) {
                    Text(
                        stringResource(section.title),
                        fontSize = 13.sp,
                        fontWeight = if (section == active) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
