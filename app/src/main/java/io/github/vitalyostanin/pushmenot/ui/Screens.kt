package io.github.vitalyostanin.pushmenot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushmenot.core.FilterRule
import io.github.vitalyostanin.pushmenot.R
import io.github.vitalyostanin.pushmenot.listener.ShadeCard
import io.github.vitalyostanin.pushmenot.preview.RulePreview
import kotlinx.coroutines.flow.Flow
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import io.github.vitalyostanin.pushmenot.storage.NotificationStatistics
import io.github.vitalyostanin.pushmenot.storage.SourceSettings
import io.github.vitalyostanin.pushmenot.transfer.ImportMode
import io.github.vitalyostanin.pushmenot.transfer.ImportPreview
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
    val confirmImport: (ImportMode) -> Unit,
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

private val ink = Color(0xff192824)
internal val secondaryInk = Color(0xff435b51)
internal val accent = Color(0xff225d45)
private val screen = Color(0xfffafcfb)
private val outline = Color(0xff527766)
private val border = Color(0xffd2ded7)
private val header = Color(0xffe0eee7)
private val okay = Color(0xffe7f3ec)
private val warningSurface = Color(0xfffff3df)
private val focus = Color(0xfff0b429)
private val palette = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    background = Color(0xffe9efec),
    onBackground = ink,
    surface = screen,
    onSurface = ink,
    onSurfaceVariant = secondaryInk,
    outline = outline,
    secondaryContainer = okay,
)
private val cardShape = RoundedCornerShape(14.dp)
internal val buttonShape = RoundedCornerShape(12.dp)
internal val screenPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)

private enum class Destination(val section: Section) {
    HISTORY(Section.HISTORY),
    APPS(Section.SOURCES),
    SETTINGS(Section.SETTINGS),
    SETTINGS_SECTION(Section.SETTINGS),
    APP(Section.SOURCES),
    APP_HISTORY(Section.HISTORY),
    RULES(Section.RULES),
    GLOBAL_RULES(Section.RULES),
    RULE_VERSIONS(Section.RULES),
    APP_RULES(Section.RULES),
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

    MaterialTheme(colorScheme = palette) {
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
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
            import, labels, { callbacks.confirmImport(ImportMode.REPLACE) }, callbacks.cancelImport,
        )
        import != null -> ImportScreen(import, labels, callbacks.confirmImport, callbacks.cancelImport)
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
        route.destination == Destination.SETTINGS || route.destination == Destination.SETTINGS_SECTION -> SettingsScreen(
            state, callbacks,
            focusedPackage = route.key.takeIf { route.destination == Destination.SETTINGS },
            section = if (route.destination == Destination.SETTINGS_SECTION) SettingsSection.valueOf(app) else null,
            onBack = back.takeIf { hasParent },
        )
        route.destination == Destination.RULES -> RulesScreen(
            state.rules, labels, settings, installed, state.rulesCanUndo,
            versions = { open(Destination.RULE_VERSIONS, null) },
            globals = { open(Destination.GLOBAL_RULES, null) },
            appRules = { open(Destination.APP_RULES, it) },
            callbacks = callbacks,
        )
        route.destination == Destination.RULE_VERSIONS -> RuleVersionsScreen(
            state.ruleVersions, state.activeSnapshotId, callbacks, back,
        )
        route.destination == Destination.GLOBAL_RULES ||
            route.destination == Destination.APP_RULES -> ScopedRulesScreen(
            app.takeIf { route.destination == Destination.APP_RULES },
            state.rules, labels, selectedPackages,
            edit = { open(Destination.EDIT_RULE, it.id) },
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
    Surface(color = screen, border = BorderStroke(1.dp, border)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            for (section in Section.entries) {
                TextButton(
                    onClick = { select(section) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag(section.tag).semantics { selected = section == active },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
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

@Composable
internal fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(color = header) {
        Column {
            if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    Modifier.weight(1f).padding(vertical = 20.dp),
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                action?.invoke()
            }
        }
    }
}

@Composable
private fun AccessScreen(statistics: NotificationStatistics?, open: () -> Unit) {
    Column {
        ScreenHeader(stringResource(R.string.access_title))
        Column(Modifier.padding(screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            statistics?.let { JournalStatistics(it) }
            Notice(R.string.access_off, warning = true) { Help(stringResource(R.string.access_description)) }
            ActionButton(R.string.settings, onClick = open, tag = "access_settings")
        }
    }
}

@Composable
private fun SelectedAppsScreen(
    settings: Map<String, SourceSettings>,
    labels: Map<String, String>,
    installed: Set<String>,
    add: () -> Unit,
    open: (String) -> Unit,
) {
    val packages = settings.values.filter(SourceSettings::enabled).map(SourceSettings::packageName)
        .sortedBy { labels[it] ?: it }
    Column {
        ScreenHeader(stringResource(R.string.apps))
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Title(stringResource(R.string.selected_app)) }
            item { ActionButton(R.string.add_app, add, tag = "add_app") }
            if (packages.isEmpty()) item { Help(stringResource(R.string.no_selected_apps)) }
            items(packages, key = { it }) { packageName ->
                SourceRow(packageName, labels, installed, open)
            }
        }
    }
}

@Composable
private fun SourceRow(
    packageName: String,
    labels: Map<String, String>,
    installed: Set<String>,
    open: (String) -> Unit,
) {
    val label = labels[packageName] ?: packageName
    Panel(Modifier.testTag("source_row_$packageName").clickable { open(packageName) }) {
        Title(label)
        if (packageName !in installed) Help(stringResource(R.string.app_not_installed))
        if (labels.values.count { it == label } > 1) Help(packageName)
    }
}

@Composable
private fun SourceDetail(
    packageName: String,
    label: String,
    settings: SourceSettings?,
    openSettings: () -> Unit,
    openHistory: () -> Unit,
    openRules: () -> Unit,
    onBack: () -> Unit,
) {
    Column {
        ScreenHeader(label, onBack)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Help(packageName)
                Help(stringResource(if (settings?.enabled == true) R.string.selected_app else R.string.filtering_off))
            }
            item { ActionButton(R.string.app_history, openHistory, primary = false, tag = "app_history") }
            item { ActionButton(R.string.app_rules, openRules, primary = false, tag = "app_rules") }
            item { ActionButton(R.string.filtering_settings, openSettings, primary = false, tag = "filtering_settings") }
        }
    }
}

@Composable
internal fun TogglePanel(title: Int, description: Int, checked: Boolean, tag: String, toggle: (Boolean) -> Unit) {
    val label = stringResource(title)
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title(label)
                Help(stringResource(description))
            }
            Switch(
                checked = checked,
                onCheckedChange = toggle,
                modifier = Modifier.testTag(tag).semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun RulesScreen(
    rules: List<FilterRule>,
    labels: Map<String, String>,
    settings: Map<String, SourceSettings>,
    installed: Set<String>,
    canUndo: Boolean,
    versions: () -> Unit,
    globals: () -> Unit,
    appRules: (String) -> Unit,
    callbacks: ScreenCallbacks,
) {
    val packages = (
        settings.values.filter(SourceSettings::enabled).map(SourceSettings::packageName) +
            rules.mapNotNull(FilterRule::packageName)
        ).distinct().sortedBy { labels[it] ?: it }
    Column {
        ScreenHeader(stringResource(R.string.rules))
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ActionButton(R.string.global_rules, globals, primary = false, tag = "global_rules") }
            item { Title(stringResource(R.string.rules_by_app)) }
            if (packages.isEmpty()) item { Help(stringResource(R.string.no_app_rules_sources)) }
            items(packages, key = { it }) { packageName ->
                val label = labels[packageName] ?: packageName
                Panel(Modifier.clickable { appRules(packageName) }.testTag("rules_source_$packageName")) {
                    Title(label)
                    if (labels.values.count { it == label } > 1) Help(packageName)
                    if (packageName !in installed) Help(stringResource(R.string.app_not_installed))
                    if (settings[packageName]?.enabled != true) Help(stringResource(R.string.filtering_off))
                }
            }
            item { ActionButton(R.string.rule_version_history, versions, primary = false, tag = "rule_versions") }
            item { ActionButton(R.string.import_rules, callbacks.beginImport, primary = false, tag = "rule_import") }
            item { ActionButton(R.string.export_rules, callbacks.beginExport, primary = false, tag = "rule_export") }
            if (canUndo) item { ActionButton(R.string.undo_rules, callbacks.undoImport, primary = false) }
            item { Help(stringResource(R.string.rule_files_explanation)) }
        }
    }
}

@Composable
private fun ScopedRulesScreen(
    packageName: String?,
    rules: List<FilterRule>,
    labels: Map<String, String>,
    selectedPackages: Set<String>,
    edit: (FilterRule) -> Unit,
    create: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val globals = rules.filter { it.packageName == null }
    val localRules = rules.filter { packageName != null && it.packageName == packageName }
    val title = if (packageName == null) stringResource(R.string.global_rules) else {
        stringResource(R.string.app_rules_title, labels[packageName] ?: packageName)
    }
    Column {
        ScreenHeader(title, onBack)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (packageName != null) {
                item { Title(stringResource(R.string.app_rules)) }
                item { ActionButton(R.string.new_rule, { create(packageName) }, tag = "rule_new") }
                if (localRules.isEmpty()) item { Help(stringResource(R.string.no_rules)) }
                items(localRules, key = FilterRule::id) { RuleRow(it, edit) }
                item { Title(stringResource(R.string.global_rules)) }
                item {
                    Help(stringResource(
                        if (packageName in selectedPackages) R.string.global_scope_help
                        else R.string.global_rules_when_filtering,
                    ))
                }
                if (globals.isEmpty()) {
                    item {
                        Help(stringResource(R.string.no_rules))
                        ActionButton(R.string.new_global_rule, { create(null) }, primary = false)
                    }
                }
            } else {
                item { Help(stringResource(R.string.global_scope_help)) }
                item { ActionButton(R.string.new_rule, { create(null) }, tag = "rule_new") }
                if (globals.isEmpty()) item { Help(stringResource(R.string.no_rules)) }
            }
            items(globals, key = FilterRule::id) { RuleRow(it, edit) }
        }
    }
}

@Composable
private fun RuleRow(rule: FilterRule, edit: (FilterRule) -> Unit) {
    Panel(Modifier.clickable { edit(rule) }) {
        RuleSummary(rule)
        if (!rule.enabled) Help(stringResource(R.string.rule_disabled))
    }
}

@Composable
private fun ImportScreen(
    preview: ImportPreview,
    labels: Map<String, String>,
    confirm: (ImportMode) -> Unit,
    cancel: () -> Unit,
) {
    var mode by remember(preview) { mutableStateOf(ImportMode.APPEND) }
    val append = mode == ImportMode.APPEND
    Column {
        ScreenHeader(stringResource(R.string.review_import))
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel {
                    Title(stringResource(R.string.import_mode))
                    Column(Modifier.selectableGroup()) {
                        ImportChoice(R.string.import_append_mode, R.string.import_append_help,
                            append, "import_mode_append") { mode = ImportMode.APPEND }
                        ImportChoice(R.string.import_replace_mode, R.string.import_replace_help,
                            !append, "import_mode_replace") { mode = ImportMode.REPLACE }
                    }
                }
            }
            item {
                Panel {
                    Text(stringResource(R.string.import_add, if (append) preview.appendedIds.size else preview.addedIds.size))
                    if (append) {
                        Text(stringResource(R.string.import_skipped, preview.skippedCount))
                        if (!preview.appendFits) Help(stringResource(R.string.import_append_limit))
                        else if (!preview.canAppend) Help(stringResource(R.string.import_no_new))
                    } else {
                        RuleDifference(preview, labels)
                    }
                }
            }
            item {
                ActionButton(
                    if (append) R.string.append_rules else R.string.replace_rules,
                    { confirm(mode) },
                    enabled = !append || preview.canAppend,
                    tag = if (append) "rule_append" else "rule_replace",
                )
            }
            item { ActionButton(R.string.cancel, cancel, primary = false) }
        }
    }
}

@Composable
private fun ImportChoice(title: Int, help: Int, checked: Boolean, tag: String, select: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .selectable(checked, role = Role.RadioButton, onClick = select),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = checked, onClick = null)
        Column {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            Help(stringResource(help))
        }
    }
}

@Composable
internal fun RuleDifference(preview: ImportPreview, labels: Map<String, String>) {
    Text(stringResource(R.string.import_update, preview.changedIds.size))
    Text(stringResource(R.string.import_remove, preview.removedIds.size))
    if (preview.orderChanged) Help(stringResource(R.string.import_order))
    if (preview.scopeChangedIds.isNotEmpty()) Help(stringResource(R.string.import_scope_changes, preview.scopeChangedIds.size))
    if (preview.allSelectedAppsAffected) Help(stringResource(R.string.import_global_impact))
    if (preview.affectedPackageNames.isNotEmpty()) {
        val apps = preview.affectedPackageNames.map { labels[it] ?: it }.sorted()
        Help(stringResource(R.string.import_local_impact, apps.joinToString(", ")))
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = cardShape,
        color = Color.White,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun Notice(title: Int, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = cardShape,
        color = if (warning) warningSurface else okay,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Title(stringResource(title))
            content()
        }
    }
}

@Composable
internal fun ActionButton(
    label: Int,
    onClick: () -> Unit,
    primary: Boolean = true,
    enabled: Boolean = true,
    tag: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val size = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val modifier = if (tag == null) size else size.testTag(tag)
    val text: @Composable () -> Unit = { Text(stringResource(label), fontWeight = FontWeight.SemiBold) }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused },
            enabled = enabled,
            shape = buttonShape,
            border = if (focused) BorderStroke(2.dp, focus) else null,
            content = { text() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused },
            enabled = enabled,
            shape = buttonShape,
            border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) focus else outline),
            content = { text() },
        )
    }
}

@Composable
internal fun Confirmation(title: Int, message: Int, confirmLabel: Int, confirm: () -> Unit, cancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(confirmLabel)) } },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun Title(label: String) {
    Text(label, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Help(label: String) {
    Text(label, color = secondaryInk, fontSize = 14.sp, lineHeight = 20.sp)
}
