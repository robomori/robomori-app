package com.robomori

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.AnchorAlignment
import com.robomori.components.*
import com.robomori.iconography.AdjustmentsHorizontal
import com.robomori.iconography.ArrowDownTray
import com.robomori.iconography.ChevronDown
import com.robomori.iconography.ChevronRight
import com.robomori.iconography.CircleStack
import com.robomori.iconography.CommandLine
import com.robomori.iconography.Eye
import com.robomori.iconography.TextType
import com.robomori.iconography.Calculator
import com.robomori.iconography.Calendar
import com.robomori.iconography.CheckCircle
import com.robomori.iconography.Cube
import com.robomori.iconography.Hashtag
import com.robomori.iconography.Icons
import com.robomori.iconography.Key
import com.robomori.iconography.PencilSquare
import com.robomori.iconography.Plus
import com.robomori.iconography.Trash
import com.robomori.iconography.TableCells
import com.robomori.theme.LazyColumn
import com.robomori.theme.RobomoriTheme
import kotlinx.coroutines.delay
import com.composeunstyled.Text
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
internal fun rememberAppDatabases(state: AppState, visible: Boolean): AppDatabasesState? {
    val serial = reachableSerial(state) ?: return null
    return key(serial, state.settings) {
        val scope = rememberCoroutineScope()
        val databases = remember {
            AppDatabasesState(state.appDatabasesService, state.installedAppsService, serial, scope, state.appPreferencesService,
                queryHistory = QueryHistoryStore(), live = state.liveAppData)
        }
        DisposableEffect(databases) { onDispose { databases.close() } }
        // Every few seconds while on screen, so newly installed apps show up.
        LaunchedEffect(databases, visible) { while (visible) { databases.loadApps(); delay(5000) } }
        // While on screen, the open database follows what the app writes to it.
        LaunchedEffect(databases, visible) { while (visible) { delay(2000); databases.followChanges() } }
        databases
    }
}

@Composable
internal fun AppDatabasesPanel(state: AppState, databases: AppDatabasesState?) {
    val apps = databases?.apps
    val message = when {
        state.selected.size > 1 -> "Select one emulator to browse its apps' data."
        databases == null && state.connected.selected != null -> "Connect this device to browse its apps' data."
        databases == null && state.previewDevice == null -> "Select a device to browse its apps' data."
        databases == null -> "Start this emulator to browse its apps' data."
        apps == null -> "Reading the installed apps…"
        apps.isEmpty() -> "No debug builds installed. Install a debug build of your app to see its databases and preferences."
        else -> null
    }
    if (message != null || databases == null || apps == null) {
        Box(Modifier.fillMaxSize().padding(16.dp).testTag("AppDatabasesPanel"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.CircleStack, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
                DeviceStateMessage(message.orEmpty())
            }
        }
        return
    }
    // Set once the list is dragged; until then it takes a share of the pane, so a narrow side panel leaves the rows room.
    var listWidth by remember { mutableStateOf<Dp?>(null) }
    Column(Modifier.fillMaxSize().testTag("AppDatabasesPanel")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val systemApps = if (!databases.hasSystemApps) emptyList()
                else listOf(SelectAction(if (databases.showSystemApps) "Hide System Apps" else "Show System Apps", databases::toggleSystemApps))
            Select(databases.app, databases.shownApps, { it?.let(databases::chooseApp) }, label = { it?.name ?: "Choose an app" },
                tag = "DatabaseApp", modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(), description = { it?.packageName },
                icon = { app, size -> if (app != null) AppIcon(app, size) }, actions = systemApps)
        }
        Rule()
        if (databases.databases.isEmpty() && databases.preferenceFiles.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(RobomoriTheme.colors.cardBackground), contentAlignment = Alignment.Center) {
                DeviceStateMessage("${databases.app?.name ?: "This app"} hasn't saved any databases or preferences yet.")
            }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // At most half the pane, so the rows keep the rest however narrow it opens.
            val widest = (maxWidth / 2).coerceIn(MinDataListWidth, MaxDataListWidth)
            val width = (listWidth ?: (maxWidth * .3f).coerceIn(MinDataListWidth, DefaultDataListWidth)).coerceIn(MinDataListWidth, widest)
            Row(Modifier.fillMaxSize().background(RobomoriTheme.colors.cardBackground)) {
                DataList(databases, Modifier.width(width).fillMaxHeight())
                Box(Modifier.fillMaxHeight()) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(RobomoriTheme.colors.shelfBorder))
                    ResizeHandle(width, { listWidth = it }, MinDataListWidth, widest,
                        Modifier.offset(x = (-4).dp).width(8.dp).fillMaxHeight(), tag = "ResizeDatabaseList")
                }
                val preferences = databases.preferenceFile
                when {
                    preferences?.kind == PreferenceKind.ProtoDataStore -> ProtoDataStorePane(databases, preferences, Modifier.weight(1f).fillMaxHeight())
                    preferences != null -> PreferencesPane(databases, preferences, Modifier.weight(1f).fillMaxHeight())
                    else -> DatabasePane(databases, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
    AppDialog(databases.pendingDelete, onClose = databases::dismissDelete) { ConfirmDeleteDialog(it, databases) }
}

private val MinDataListWidth = 140.dp
private val DefaultDataListWidth = 220.dp
private val MaxDataListWidth = 400.dp

@Composable
private fun DatabasePane(databases: AppDatabasesState, modifier: Modifier) {
    Column(modifier) {
        when (val load = databases.load) {
            DatabaseLoad.None -> {}
            DatabaseLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DeviceStateMessage("Copying the database from the device…")
            }
            DatabaseLoad.Encrypted -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                DeviceStateMessage("This database is encrypted, as SQLCipher's are, so it can't be read without the app's key.")
            }
            is DatabaseLoad.Failed -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Label(load.cause.message ?: "Could not open the database.", color = RobomoriTheme.colors.danger)
            }
            is DatabaseLoad.Ready -> {
                // A table shows its rows; with none chosen, the pane is the database's query editor.
                val table = databases.openTable
                var selection by remember(databases.table) { mutableStateOf(GridSelection()) }
                var typing by remember(databases.table) { mutableStateOf(false) }
                // How many rows there were when a row was added, so the new one is selected and typed into once it shows.
                var addedAfter by remember(databases.table) { mutableStateOf<Int?>(null) }
                val done = databases.result as? QueryState.Done
                val rows = done?.let { rowsOf(databases, it.result) }.orEmpty()
                fun rowOf(key: String?) = done?.result?.rows?.firstOrNull { it.firstOrNull() == key }
                val editing = GridEditing(
                    canEdit = { row, column -> databases.editable && column !in row.blobs },
                    canSetNull = { _, column -> databases.editable && column !in table?.notNull.orEmpty() },
                    onEdit = { row, column, value -> rowOf(row.key)?.let { databases.editCell(it, column, value) } },
                    onDeleteRows = if (databases.editable) ({ targets -> databases.requestDeleteRows(targets.mapNotNull { rowOf(it.key) }) }) else null,
                )
                LaunchedEffect(rows) {
                    val before = addedAfter ?: return@LaunchedEffect
                    if (rows.size <= before) return@LaunchedEffect
                    addedAfter = null
                    // The newest row has the highest rowid, wherever the sort puts it.
                    val added = rows.maxByOrNull { it.key.toLongOrNull() ?: Long.MIN_VALUE } ?: return@LaunchedEffect
                    val column = databases.layout.order.firstOrNull { editing.canEdit(added, it) && it !in table?.columns.orEmpty().filter { c -> c.isRowId }.map { c -> c.name } }
                        ?: return@LaunchedEffect
                    selection = GridSelection.of(GridCell(added.key, column))
                    typing = true
                }
                val selectedRow = rows.firstOrNull { it.key == selection.cell?.row }
                val selectedRows = rows.filter { it.key in selection.rows }
                val structure = table != null && databases.tableView == TableView.Structure
                PaneToolbar(table?.name ?: "Run Query", databases::search, searching = databases.search) {
                    if (table != null) {
                        for (view in TableView.entries) ToggleButton(databases.tableView == view, { databases.showTableView(view) },
                            size = ButtonSize.Small, tag = "TableView:${view.name}") { Label(view.name) }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (databases.editable && !structure) {
                        Button(onClick = { addedAfter = rows.size; databases.addRow() }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                            contentDescription = "Add a row", tag = "AddDatabaseRow") { Icon(Icons.Plus) }
                        Button(onClick = { typing = true }, enabled = selectedRow != null && selection.cell?.let { editing.canEdit(selectedRow, it.column) } == true,
                            style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Edit the selected cell",
                            tag = "EditDatabaseCell") { Icon(Icons.PencilSquare) }
                        Button(onClick = { editing.onDeleteRows?.invoke(selectedRows) }, enabled = selectedRows.isNotEmpty(),
                            style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                            contentDescription = if (selectedRows.size > 1) "Delete the selected rows" else "Delete the selected row",
                            tag = "DeleteDatabaseRow") { Icon(Icons.Trash) }
                    }
                    Button(onClick = { chooseSaveFile(databases.database?.name ?: "database")?.let(databases::saveCopy) },
                        style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Save a copy of the database",
                        tag = "SaveDatabase") { Icon(Icons.ArrowDownTray) }
                }
                if (table == null) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) { QueryRow(databases) }
                Rule()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (structure) StructureView(databases, Modifier.weight(1f).fillMaxHeight())
                    else ResultView(databases, rows, selection, { selection = it }, editing, typing, { typing = it }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** What a column's header shows after its name: a gold key for the primary key, then an icon for its declared type. */
@Composable
private fun marksOf(column: DatabaseColumn): List<TableColumnMark> = listOfNotNull(
    TableColumnMark(Icons.Key, "Primary Key", RobomoriTheme.colors.keyIcon).takeIf { column.primaryKey },
    typeMark(column.type),
)

/** An icon for a declared column type, by SQLite's rules for which kind of value a type holds; none for a column without one. */
private fun typeMark(type: String): TableColumnMark? {
    val declared = type.uppercase()
    return when {
        declared.isBlank() -> null
        "BOOL" in declared -> TableColumnMark(Icons.CheckCircle, "Boolean Type")
        "DATE" in declared || "TIME" in declared -> TableColumnMark(Icons.Calendar, "Date Type")
        "INT" in declared -> TableColumnMark(Icons.Hashtag, "Integer Type")
        listOf("CHAR", "CLOB", "TEXT").any { it in declared } -> TableColumnMark(Icons.TextType, "Text Type")
        "BLOB" in declared -> TableColumnMark(Icons.Cube, "Blob Type")
        listOf("REAL", "FLOA", "DOUB", "NUM", "DEC").any { it in declared } -> TableColumnMark(Icons.Calculator, "Real Type")
        else -> null
    }
}

/** The rows of a query's result for the grid, keyed by rowid where they have one, and by their place otherwise. */
private fun rowsOf(databases: AppDatabasesState, result: QueryResult): List<GridRow> {
    val keyed = result.columns.firstOrNull() == RowIdColumn
    return databases.shownRows(result).map { index ->
        val row = result.rows[index]
        GridRow(if (keyed) row.first().orEmpty() else "#$index", result.columns.withIndex().associate { (at, column) -> column to row[at] },
            blobs = result.columns.withIndex().filter { (at, _) -> (index to at) in result.blobs }.mapTo(mutableSetOf()) { it.value })
    }
}

@Composable
private fun ResultView(databases: AppDatabasesState, rows: List<GridRow>, selection: GridSelection, onSelect: (GridSelection) -> Unit,
                       editing: GridEditing, typing: Boolean, onTyping: (Boolean) -> Unit, modifier: Modifier) {
    when (val state = databases.result) {
        QueryState.Idle -> Spacer(modifier)
        QueryState.Running -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DeviceStateMessage("Running the query…") }
        is QueryState.Failed -> Column(modifier.padding(12.dp)) { Label(state.message, color = RobomoriTheme.colors.danger, modifier = Modifier.testTag("DatabaseQueryError")) }
        is QueryState.Done -> {
            val result = state.result
            if (result.columns.isEmpty()) return Column(modifier.padding(12.dp)) {
                SecondaryLine(when (result.changed) {
                    null, 0 -> "The query ran."
                    1 -> "Changed 1 row."
                    else -> "Changed ${result.changed} rows."
                })
            }
            Column(modifier) {
                DataGrid(databases.layout, databases.sort, databases::sortBy, rows, selection, onSelect, editing, typing, onTyping, tag = "Database",
                    modifier = Modifier.weight(1f).fillMaxWidth(), onReachEnd = databases::loadMore,
                    columnMarks = databases.openTable?.columns.orEmpty().associate { it.name to marksOf(it) })
                val count = rows.size
                StatusBar(databases, when {
                    result.more -> "$count+ rows"
                    count == 1 -> "1 row"
                    else -> "$count rows"
                }) {
                    // A release build can't be told of a change while it runs, so what it shows may be from before.
                    if (databases.databaseEdit == LiveEdit.NeedsRestart) RestartNotice(databases, "The app shows changes once it restarts.")
                }
            }
        }
    }
}

/** How the open table is built: its columns, its indexes under them, then the statements that create it. */
@Composable
private fun StructureView(databases: AppDatabasesState, modifier: Modifier) {
    when (val load = databases.structure) {
        StructureLoad.Loading -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DeviceStateMessage("Reading the table's structure…") }
        is StructureLoad.Failed -> Column(modifier.padding(12.dp)) { Label(load.message, color = RobomoriTheme.colors.danger) }
        is StructureLoad.Ready -> Column(modifier) {
            val structure = load.structure
            var selection by remember(databases.table) { mutableStateOf(GridSelection()) }
            var indexSelection by remember(databases.table) { mutableStateOf(GridSelection()) }
            fun yes(value: Boolean) = if (value) "Yes" else ""
            val (name, type, notNull, default, primaryKey) = StructureColumns
            val columns = structure.columns.map { column ->
                GridRow(column.name, mapOf(name to column.name, type to column.type, notNull to yes(column.refusesNull),
                    default to column.default, primaryKey to yes(column.primaryKey)))
            }
            DataGrid(databases.structureLayout, null, {}, columns, selection, { selection = it }, GridEditing(), false, {},
                tag = "TableColumns", modifier = Modifier.weight(1f).fillMaxWidth())
            Rule()
            GroupTitle("Indexes")
            if (structure.indexes.isEmpty()) Box(Modifier.padding(start = 14.dp, bottom = 8.dp)) { SecondaryLine("No indexes") }
            else {
                val (index, unique, indexed) = IndexColumns
                val indexes = structure.indexes.map {
                    GridRow(it.name, mapOf(index to it.name, unique to yes(it.unique), indexed to it.columns.joinToString { column -> column ?: "(expression)" }))
                }
                // Tall enough for a few indexes; more scroll.
                DataGrid(databases.indexLayout, null, {}, indexes, indexSelection, { indexSelection = it }, GridEditing(), false, {},
                    tag = "TableIndexes", modifier = Modifier.fillMaxWidth().height(28.dp + 26.dp * minOf(indexes.size, 4) + 4.dp))
            }
            Rule()
            GroupTitle("Definition")
            Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                CodeBlock(structure.sql, tag = "TableDefinition")
            }
        }
    }
}

/** A Proto DataStore file, which can't be shown without the app's schema, but can be saved to read elsewhere. */
@Composable
private fun ProtoDataStorePane(databases: AppDatabasesState, file: PreferenceFile, modifier: Modifier) {
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AdjustmentsHorizontal, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
            DeviceStateMessage("${file.file.name} is a Proto DataStore. Its fields are saved by number, not by name, so it can only be read with the app's own schema.")
            val size = (databases.preferences as? PreferencesLoad.NeedsSchema)?.size ?: file.file.size
            SecondaryLine(if (size < 1000) "$size bytes" else formatSize(size))
            Button(onClick = { chooseSaveFile(file.file.name, "Save a Copy of the File")?.let(databases::savePreferencesCopy) },
                style = ButtonStyle.Secondary, enabled = databases.action != DataAction.Working, tag = "SaveProtoDataStore") { Label("Save a Copy…") }
            when (val action = databases.action) {
                DataAction.None, DataAction.Working -> {}
                is DataAction.Saved -> SecondaryLine("Saved a copy to ${action.file.name}")
                is DataAction.Failed -> Label("Couldn't save: ${action.message}", color = RobomoriTheme.colors.danger, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PreferencesPane(databases: AppDatabasesState, file: PreferenceFile, modifier: Modifier) {
    var selection by remember(file) { mutableStateOf(GridSelection()) }
    var typing by remember(file) { mutableStateOf(false) }
    val selectedEntry = (databases.preferences as? PreferencesLoad.Ready)?.entries.orEmpty().firstOrNull { it.key == selection.cell?.row }
    Column(modifier) {
        PaneToolbar(file.file.path, databases::searchPreferences, searching = databases.search) {
            Button(onClick = { selection = GridSelection.of(GridCell(selectedEntry!!.key, PreferenceColumns[2])); typing = true }, enabled = selectedEntry?.editable == true,
                style = ButtonStyle.Ghost, size = ButtonSize.Icon, contentDescription = "Edit the selected value",
                tag = "EditPreference") { Icon(Icons.PencilSquare) }
            Button(onClick = { databases.requestDeletePreferences(selection.rows.toList()) }, enabled = selection.rows.isNotEmpty(),
                style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                contentDescription = if (selection.rows.size > 1) "Delete the selected keys" else "Delete the selected key",
                tag = "DeletePreference") { Icon(Icons.Trash) }
        }
        Rule()
        when (val load = databases.preferences) {
            PreferencesLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DeviceStateMessage("Reading the preferences…") }
            is PreferencesLoad.Failed -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Label(load.message, color = RobomoriTheme.colors.danger)
            }
            // Shown by ProtoDataStorePane instead.
            is PreferencesLoad.NeedsSchema -> {}
            is PreferencesLoad.Ready -> {
                val entries = databases.shownPreferences(load.entries)
                val byKey = entries.associateBy { it.key }
                val (key, type, value) = PreferenceColumns
                val rows = entries.map { entry ->
                    GridRow(entry.key, mapOf(key to entry.key, type to entry.type.label, value to entry.shown()),
                        blobs = if (entry.type == PreferenceType.Bytes) setOf(value) else emptySet())
                }
                val editing = GridEditing(
                    canEdit = { row, column -> column == value && byKey[row.key]?.editable == true },
                    // A boolean switches between true and false, as Xcode's YES and NO do, rather than being typed.
                    flipped = { row, column ->
                        byKey[row.key]?.takeIf { column == value && it.type == PreferenceType.Boolean }?.let { if (it.value == "true") "false" else "true" }
                    },
                    onEdit = { row, _, text -> if (text != null) databases.editPreference(row.key, text) },
                    onDeleteRows = { targets -> databases.requestDeletePreferences(targets.map { it.key }) },
                )
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        DataGrid(databases.preferencesLayout, databases.sort, databases::sortPreferences, rows, selection, { selection = it },
                            editing, typing, { typing = it }, tag = "Preferences", modifier = Modifier.weight(1f).fillMaxWidth())
                        StatusBar(databases, if (entries.size == 1) "1 key" else "${entries.size} keys") {
                            // A change the running app couldn't take in shows in it once it starts again.
                            if (databases.preferencesEdit == LiveEdit.NeedsRestart) RestartNotice(databases, "The app reads changes when it starts.")
                        }
                    }
                }
            }
        }
    }
}

/** Says the running app hasn't taken in a change made here, with a button to restart it so it does. */
@Composable
private fun RowScope.RestartNotice(databases: AppDatabasesState, message: String) {
    Box(Modifier.weight(1f, fill = false)) { SecondaryLine(message) }
    Button(onClick = databases::restartApp, style = ButtonStyle.Secondary, size = ButtonSize.Small,
        tag = "RestartAppForChanges") { Label("Restart app") }
}

/** A preference's type, named as Kotlin code reads it. */
private val PreferenceType.label get() = when (this) {
    PreferenceType.StringSet -> "String Set"
    else -> name
}

/**
 * The bar above the rows: a filter of them, then [actions]. The list on the left says what's [showing], so the bar
 * doesn't; a filter is left behind when something else is shown.
 */
@Composable
private fun PaneToolbar(showing: String, onSearch: (String) -> Unit, searching: String, actions: @Composable RowScope.() -> Unit) {
    val search = rememberTextFieldState(searching)
    val latest by rememberUpdatedState(onSearch)
    LaunchedEffect(search) { snapshotFlow { search.text.toString() }.collect { latest(it) } }
    LaunchedEffect(showing) { if (searching.isEmpty() && search.text.isNotEmpty()) search.clearText() }
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Up to 240 points, giving way to the actions in a narrow pane.
        Box(Modifier.weight(1f)) { Field(search, "SearchRows", Modifier.widthIn(max = 240.dp).fillMaxWidth(), placeholder = "Filter…") }
        actions()
    }
}

/** The bar under the rows: how many there are, then how a change made here went, and [extra]. */
@Composable
private fun StatusBar(databases: AppDatabasesState, count: String, extra: @Composable RowScope.() -> Unit = {}) {
    Rule()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryLine(count)
        Spacer(Modifier.weight(1f))
        when (val action = databases.action) {
            DataAction.None -> {}
            DataAction.Working -> SecondaryLine("Saving…")
            is DataAction.Saved -> SecondaryLine("Saved a copy to ${action.file.name}")
            is DataAction.Failed -> Label("Couldn't save: ${action.message}", color = RobomoriTheme.colors.danger, maxLines = 1,
                modifier = Modifier.testTag("DatabaseEditFailed"))
        }
        if (databases.updateFailed) Label("Couldn't update from the device · retrying", color = RobomoriTheme.colors.danger,
            modifier = Modifier.testTag("DatabaseUpdateFailed"))
        extra()
    }
}

/** The app's databases, the open one with its tables beneath it, then its preferences files, as Finder lists folders. */
@Composable
private fun DataList(databases: AppDatabasesState, modifier: Modifier) {
    val tables = (databases.load as? DatabaseLoad.Ready)?.tables.orEmpty()
    // Clicking the open database folds its tables away and back, as a folder in Finder's list does.
    var folded by remember(databases.database?.path) { mutableStateOf(false) }
    val showingDatabase = databases.preferenceFile == null
    LazyColumn(modifier.testTag("DatabaseList"), contentPadding = PaddingValues(vertical = 6.dp)) {
        if (databases.databases.isNotEmpty()) item { GroupTitle("Databases") }
        items(databases.databases, key = { it.path }) { database ->
            val open = database.path == databases.database?.path
            Column {
                ContextMenuArea(items = {
                    listOf(ContextMenuItem("Save a Copy…") {
                        if (!open) databases.chooseDatabase(database)
                        else chooseSaveFile(database.name)?.let(databases::saveCopy)
                    })
                }) {
                    ListRow(selected = false, tag = "Database:${database.name}",
                        onClick = { if (open && showingDatabase) folded = !folded else databases.chooseDatabase(database) }) {
                        Icon(if (open && !folded) Icons.ChevronDown else Icons.ChevronRight, tint = RobomoriTheme.colors.textSecondary,
                            modifier = Modifier.size(12.dp))
                        Icon(Icons.CircleStack, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                        Text(database.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                if (open && !folded) {
                    if (tables.isNotEmpty()) ListRow(selected = showingDatabase && databases.table == null, tag = "DatabaseQuery:${database.name}",
                        indent = 20.dp, onClick = databases::writeQuery) {
                        Icon(Icons.CommandLine, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                        Text("Run Query", maxLines = 1, modifier = Modifier.weight(1f))
                    }
                    if (databases.load is DatabaseLoad.Loading) Box(Modifier.padding(start = 44.dp, top = 2.dp, bottom = 2.dp)) {
                        SecondaryLine("Copying…")
                    }
                    for (table in tables) {
                        ListRow(selected = showingDatabase && table.name == databases.table, tag = "DatabaseTable:${table.name}", indent = 20.dp,
                            onClick = { databases.chooseTable(table.name) }) {
                            Icon(if (table.view) Icons.Eye else Icons.TableCells, tint = RobomoriTheme.colors.textSecondary,
                                modifier = Modifier.size(16.dp))
                            Text(table.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            table.rows?.let {
                                Text(it.toString(), style = RobomoriTheme.typography.small, color = RobomoriTheme.colors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
        if (databases.preferenceFiles.isNotEmpty()) item { GroupTitle("Preferences") }
        items(databases.preferenceFiles, key = { it.file.path }) { file ->
            ListRow(selected = databases.preferenceFile == file, tag = "Preferences:${file.name}", onClick = { databases.choosePreferences(file) }) {
                Icon(Icons.AdjustmentsHorizontal, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (file.kind == PreferenceKind.DataStore) Text("DataStore", style = RobomoriTheme.typography.small,
                    color = RobomoriTheme.colors.textSecondary)
                if (file.kind == PreferenceKind.ProtoDataStore) Text("Proto", style = RobomoriTheme.typography.small,
                    color = RobomoriTheme.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(title, style = RobomoriTheme.typography.small, color = RobomoriTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
private fun ListRow(selected: Boolean, tag: String, onClick: () -> Unit, indent: Dp = 0.dp, content: @Composable RowScope.() -> Unit) {
    SidebarListItem(selected = selected, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(6.dp)).testTag(tag).clickable(onClick = onClick)) {
        Row(Modifier.padding(start = indent), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/**
 * The query field, which takes several statements separated by `;`. ↑ and ↓ step through the queries run before, as a
 * shell's history does, and the menu beside it lists them.
 */
@Composable
private fun QueryRow(databases: AppDatabasesState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(databases.query, "DatabaseQuery", Modifier.weight(1f).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Enter -> databases.run()
                Key.DirectionUp -> databases.previousQuery()
                Key.DirectionDown -> databases.nextQuery()
                else -> return@onPreviewKeyEvent false
            }
            true
        }, placeholder = "SELECT * FROM …; separate several statements with ;")
        Select(null as String?, databases.history, { it?.let(databases::useQuery) },
            label = { it?.let { sql -> sql.replace(Regex("\\s+"), " ").let { line -> if (line.length > 90) line.take(89) + "…" else line } } ?: "History" },
            tag = "DatabaseQueryHistory", enabled = databases.history.isNotEmpty(), style = SelectStyle.Ghost, menuAlignment = AnchorAlignment.End)
        Button(onClick = databases::run, enabled = databases.result != QueryState.Running, tag = "RunDatabaseQuery") { Label("Run") }
    }
}

/** Asks where to save a copy of [name], on the Desktop to start with; a name without an extension is a database's. */
private fun chooseSaveFile(name: String, title: String = "Save a Copy of the Database"): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
        directory = File(System.getProperty("user.home"), "Desktop").takeIf(File::isDirectory)?.path
        file = if ('.' in name) name else "$name.db"
        isVisible = true
    }
    val chosen = dialog.file ?: return null
    return File(dialog.directory, chosen)
}

@Composable
private fun ConfirmDeleteDialog(pending: PendingDelete, databases: AppDatabasesState) {
    val cancelFocus = remember { FocusRequester() }
    val (title, detail) = when (pending) {
        is PendingDelete.Rows -> if (pending.rows.size == 1) "Delete this row from ${pending.table}?" to
            "It will be removed from the app's database on the device. This cannot be undone."
            else "Delete ${pending.rows.size} rows from ${pending.table}?" to
            "They'll be removed from the app's database on the device. This cannot be undone."
        is PendingDelete.Keys -> if (pending.keys.size == 1) "Delete ${pending.keys.single()}?" to
            "It will be removed from ${pending.file} on the device. This cannot be undone."
            else "Delete ${pending.keys.size} keys?" to
            "They'll be removed from ${pending.file} on the device. This cannot be undone."
    }
    AppDialogPanel(title, "ConfirmDeleteData", Modifier.width(420.dp)) {
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(title)
            Label(detail, color = RobomoriTheme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = databases::dismissDelete, style = ButtonStyle.Outlined, modifier = Modifier.focusRequester(cancelFocus),
                    tag = "CancelDeleteData") { Label("Cancel") }
                Button(onClick = databases::confirmDelete, style = ButtonStyle.Destructive, tag = "ConfirmDeleteData") { Label("Delete") }
            }
        }
    }
}
