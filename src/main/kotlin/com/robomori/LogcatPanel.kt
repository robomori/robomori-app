package com.robomori

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import com.robomori.components.*
import com.robomori.iconography.Icons
import com.robomori.iconography.LayoutGrid
import com.robomori.iconography.Bug
import com.robomori.iconography.Cat
import com.robomori.iconography.Check
import com.robomori.iconography.ChevronDown
import com.robomori.iconography.ChevronUp
import com.robomori.iconography.Document
import com.robomori.iconography.EllipsisVertical
import com.robomori.iconography.FolderOpen
import com.robomori.iconography.History
import com.robomori.iconography.Pause
import com.robomori.iconography.Star
import com.robomori.iconography.StarFilled
import com.robomori.iconography.Trash
import com.robomori.theme.LazyColumn
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

@Composable
internal fun rememberLogcat(state: AppState, visible: Boolean): LogcatState? {
    val serial = reachableSerial(state) ?: return null
    return key(serial, state.settings) {
        val scope = rememberCoroutineScope()
        val logcat = remember {
            LogcatState(AdbLogcatSource(state.settings, state.installedAppsService), serial, scope, FileLogcatSettingsStore())
        }
        // Starts the first time the tab opens, then keeps reading while this device is selected, so leaving the tab
        // and coming back shows everything logged meanwhile.
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(visible) { if (visible) opened = true }
        LaunchedEffect(logcat, opened) { if (opened) logcat.observe() }
        logcat
    }
}

@Composable
internal fun LogcatPanel(state: AppState, logcat: LogcatState?) {
    val message = when {
        state.selected.size > 1 -> "Select one emulator to see its logs."
        logcat == null && state.connected.selected != null -> "Connect this device to see its logs."
        logcat == null && state.previewDevice == null -> "Select a device to see its logs."
        logcat == null -> "Start this emulator to see its logs."
        // A saved log shows whatever the device's own log is doing.
        logcat.imported != null -> null
        else -> when (val status = logcat.status) {
            LogcatStatus.Connecting -> "Reading the device's logs…"
            is LogcatStatus.Failed -> status.cause.message ?: "Could not read the device's logs."
            LogcatStatus.Streaming, LogcatStatus.Ended -> null
        }
    }
    if (message != null || logcat == null) {
        Box(Modifier.fillMaxSize().padding(16.dp).testTag("LogcatPanel"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Cat, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
                DeviceStateMessage(message.orEmpty())
            }
        }
        return
    }
    // Opens on the newest line, at the bottom. A saved log gets a list of its own, starting at its top.
    val list = key(logcat.imported?.name) {
        rememberLazyListState(initialFirstVisibleItemIndex = if (logcat.imported != null) 0 else logcat.shown.lastIndex.coerceAtLeast(0))
    }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().testTag("LogcatPanel")) {
        // Side by side when the filter still gets room, as in a full-width dock; narrower, such as in the side panel, the
        // app and the buttons go above the filter and the levels, and narrower still, the levels get a row of their own.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val select: @Composable (Modifier) -> Unit = { modifier ->
                val options = listOf<String?>(null) + logcat.apps.map { it.packageName }
                val apps = logcat.apps.associateBy { it.packageName }
                Select(logcat.app, options, logcat::selectApp, label = { it?.let { apps[it]?.name } ?: it ?: "All apps" },
                    tag = "LogcatApp", modifier = modifier, description = { it }, enabled = logcat.imported == null,
                    icon = { packageName, size ->
                        apps[packageName]?.let { AppIcon(it, size) } ?: Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                            Icon(Icons.LayoutGrid, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    })
            }
            val field: @Composable (Modifier) -> Unit = { modifier ->
                Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Enter keeps the filter among the recent ones straight away, rather than after a pause.
                    Field(logcat.search, "LogcatSearch", Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) logcat.rememberSearch()
                        false
                    }, placeholder = "Filter, such as tag:Net level:W is:crash")
                    SavedQueriesMenu(logcat)
                }
            }
            val levels: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    LogLevelFilter.entries.forEach { filter ->
                        val shown = filter !in logcat.hiddenLevels
                        val level = filter.levels.first()
                        // The level's block from the log rows while shown, an empty outline while hidden.
                        Button(onClick = { logcat.toggleLevel(filter) }, style = ButtonStyle.Ghost, size = ButtonSize.Regular,
                            modifier = Modifier.semantics { toggleableState = ToggleableState(shown) }, tag = "LogcatLevel-${filter.name}") {
                            val (fill, letter) = levelColors(level)
                            val shape = RoundedCornerShape(4.dp)
                            Box(Modifier.size(18.dp).then(if (shown) Modifier.background(fill, shape)
                                else Modifier.border(1.dp, RobomoriTheme.colors.textDisabled, shape)), contentAlignment = Alignment.Center) {
                                Text(level.letter.toString(), color = if (shown) letter else RobomoriTheme.colors.textDisabled,
                                    style = RobomoriTheme.typography.codeSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            val buttons: @Composable () -> Unit = {
                ToggleButton(value = logcat.paused, onValueChange = { logcat.paused = it }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                    contentDescription = if (logcat.paused) "Resume autoscroll" else "Pause autoscroll", tag = "PauseLogcat") {
                    Icon(Icons.Pause)
                }
                Button(onClick = logcat::clear, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                    contentDescription = "Clear logs", tag = "ClearLogcat") { Icon(Icons.Trash) }
                Button(onClick = { chooseSavedLog()?.let(logcat::open) }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                    contentDescription = "Open a saved log", tag = "OpenSavedLog") { Icon(Icons.FolderOpen) }
                LogOptionsMenu(logcat)
            }
            if (maxWidth >= 900.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    select(Modifier.widthIn(max = 240.dp))
                    field(Modifier.weight(1f))
                    levels()
                    buttons()
                }
            } else {
                val levelsBeside = maxWidth >= 480.dp
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        select(Modifier.weight(1f))
                        buttons()
                    }
                    if (levelsBeside) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            field(Modifier.weight(1f))
                            levels()
                        }
                    } else {
                        field(Modifier.fillMaxWidth())
                        levels()
                    }
                }
            }
        }
        logcat.imported?.let { log ->
            Rule()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Document, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                Label("Reading ${log.name}, a saved log", Modifier.weight(1f), color = RobomoriTheme.colors.textSecondary, maxLines = 1)
                Button(onClick = logcat::closeImport, size = ButtonSize.Small, tag = "CloseSavedLog") { Label("Back to Live Logs") }
            }
        }
        Rule()
        ContextMenuDataProvider(items = {
            val selected = logcat.selectedLines.isNotEmpty()
            listOfNotNull(
                ContextMenuItem("Copy lines") { copy(logcat.selectedText()) }.takeIf { selected },
                ContextMenuItem(if (logcat.selectedLines.size == 1) "Copy message" else "Copy messages") {
                    copy(logcat.selectedMessages())
                }.takeIf { selected },
            ) + logcat.selectedTags().take(3).map { tag -> ContextMenuItem("Hide tag “$tag”") { logcat.hideTag(tag) } } + listOfNotNull(
                ContextMenuItem("Clear logs", logcat::clear),
                ContextMenuItem("Clear device logs", logcat::clearDevice).takeIf { logcat.imported == null },
                ContextMenuItem("Copy logs") { copy(logcat.text()) },
                ContextMenuItem("Save as…") { chooseLogFile()?.let(logcat::save) },
            )
        }) {
            LogLines(logcat, list)
        }
        LogStatus(logcat, onCrash = { forward ->
            logcat.goToCrash(forward, list.firstVisibleItemIndex)?.let { row -> scope.launch { list.scrollToItem(row) } }
        })
        logcat.failure?.let { failure ->
            Rule()
            Label(when (failure) {
                LogcatFailure.ClearDevice -> "Could not clear the device's logs."
                LogcatFailure.Save -> "Could not save the logs."
                LogcatFailure.Open -> "Could not open the saved log."
            }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = RobomoriTheme.colors.danger)
        }
        logcat.unopenedSource?.let { unopened ->
            Rule()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("UnopenedSource"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val location = unopened.frame.location
                Label(when (unopened) {
                    is UnopenedSource.NoIde -> "Open your project in Android Studio or IntelliJ IDEA to go to $location. Copied “$location”."
                    is UnopenedSource.NotInProjects -> "${unopened.frame.file} isn't in a project open in Android Studio or IntelliJ IDEA. Copied “$location”."
                }, Modifier.weight(1f), color = RobomoriTheme.colors.textSecondary)
                Button(onClick = logcat::dismissUnopenedSource, style = ButtonStyle.Ghost, size = ButtonSize.Small,
                    tag = "DismissUnopenedSource") { Label("Dismiss") }
            }
        }
        if (logcat.status == LogcatStatus.Ended && logcat.imported == null) {
            Rule()
            Label("The device stopped sending its logs.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = RobomoriTheme.colors.textSecondary)
        }
    }
}

private fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

/** How many crashes show, with buttons to go from one to the next, and what in the filter couldn't be read. */
@Composable
private fun LogStatus(logcat: LogcatState, onCrash: (forward: Boolean) -> Unit) {
    val crashes = logcat.crashRows.size
    val problem = logcat.query.problems.firstOrNull()
    if (crashes == 0 && problem == null) return
    Rule()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("LogcatStatus"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (crashes > 0) {
            Icon(Icons.Bug, tint = RobomoriTheme.colors.danger, modifier = Modifier.size(16.dp))
            Label(if (crashes == 1) "1 crash" else "$crashes crashes", color = RobomoriTheme.colors.danger, maxLines = 1)
            Button(onClick = { onCrash(false) }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                contentDescription = "Previous crash", tag = "PreviousCrash") { Icon(Icons.ChevronUp, modifier = Modifier.size(16.dp)) }
            Button(onClick = { onCrash(true) }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                contentDescription = "Next crash", tag = "NextCrash") { Icon(Icons.ChevronDown, modifier = Modifier.size(16.dp)) }
        }
        Spacer(Modifier.weight(1f))
        problem?.let { Label(problemText(it), color = RobomoriTheme.colors.textSecondary, maxLines = 1) }
    }
}

private fun problemText(problem: QueryProblem) = when (problem) {
    is QueryProblem.InvalidRegex -> "“${problem.pattern}” isn't a valid regex, so it matches nothing."
    is QueryProblem.UnknownLevel -> "There's no level “${problem.value}”. Use V, D, I, W, E, F or A."
    is QueryProblem.InvalidAge -> "Write ages like 30s, 5m, 1h or 2d."
    is QueryProblem.UnknownKind -> "Use is:crash or is:stacktrace."
    QueryProblem.UnclosedQuote -> "A quote isn't closed."
}

/** The recent and starred filters, to use again, and starring the one in use. */
@Composable
private fun SavedQueriesMenu(logcat: LogcatState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = !expanded }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
            contentDescription = "Saved filters", tag = "LogcatSavedFilters") { Icon(Icons.History, modifier = Modifier.size(16.dp)) }
        FadingMenu(expanded) { fade ->
            val current = logcat.search.text.toString().trim()
            val starred = logcat.settings.starred
            val recent = logcat.settings.history.filter { it !in starred }
            PopupMenu(onDismissRequest = { expanded = false; true }, horizontalAlignment = Alignment.End, modifier = fade) {
                if (current.isNotEmpty()) {
                    val isStarred = current in starred
                    item(onClick = { expanded = false; logcat.setStarred(current, !isStarred) }) {
                        MenuRow(if (isStarred) Icons.StarFilled else Icons.Star) { Text(if (isStarred) "Unstar This Filter" else "Star This Filter") }
                    }
                    if (starred.isNotEmpty() || recent.isNotEmpty()) separator()
                }
                starred.forEach { query ->
                    item(onClick = { expanded = false; logcat.applySearch(query) }) {
                        MenuRow(Icons.StarFilled) { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (starred.isNotEmpty() && recent.isNotEmpty()) separator()
                recent.forEach { query ->
                    item(onClick = { expanded = false; logcat.applySearch(query) }) {
                        MenuRow(null) { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (current.isEmpty() && starred.isEmpty() && recent.isEmpty()) {
                    passiveItem { Label("Filters you use show here.", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = RobomoriTheme.colors.textSecondary) }
                }
            }
        }
    }
}

/** Whether filters highlight or hide, which columns show, and the tags that are hidden, to show them again. */
@Composable
private fun LogOptionsMenu(logcat: LogcatState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = !expanded }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
            contentDescription = "Log options", tag = "LogcatOptions") { Icon(Icons.EllipsisVertical) }
        FadingMenu(expanded) { fade ->
            val settings = logcat.settings
            val columns = settings.columns
            PopupMenu(onDismissRequest = { expanded = false; true }, horizontalAlignment = Alignment.End, modifier = fade) {
                fun check(label: String, checked: Boolean, onClick: () -> Unit) =
                    item(onClick = onClick) { MenuRow(Icons.Check.takeIf { checked }) { Text(label) } }
                check("Highlight Matches Instead of Hiding", settings.highlight) { logcat.setHighlight(!settings.highlight) }
                separator()
                check("Show Time", columns.time) { logcat.setColumns(columns.copy(time = !columns.time)) }
                check("Show PID", columns.pid) { logcat.setColumns(columns.copy(pid = !columns.pid)) }
                check("Show Tag", columns.tag) { logcat.setColumns(columns.copy(tag = !columns.tag)) }
                if (settings.ignoredTags.isNotEmpty()) {
                    separator()
                    submenu(submenu = {
                        settings.ignoredTags.sorted().forEach { tag ->
                            item(onClick = { logcat.showTag(tag) }) { MenuRow(null) { Text("Show “$tag”") } }
                        }
                        separator()
                        item(onClick = { expanded = false; logcat.showAllTags() }) { MenuRow(null) { Text("Show All") } }
                    }) { Text("Hidden Tags (${settings.ignoredTags.size})") }
                }
            }
        }
    }
}

/** A menu item's text after a column for [icon], kept empty without one so the texts line up. */
@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, modifier = Modifier.size(14.dp))
        }
        content()
    }
}

@Composable
private fun ColumnScope.LogLines(logcat: LogcatState, list: LazyListState) {
    val rows = logcat.shown
    // Follows new lines while the view is at the bottom and not paused; scrolling up holds it still to read. Only a
    // scroll that ends decides, so the view's first layout doesn't count as leaving the bottom.
    var following by remember(list) { mutableStateOf(logcat.imported == null) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.drop(1).collect { scrolling ->
            following = !scrolling && !list.canScrollForward
        }
    }
    LaunchedEffect(rows.lastOrNull()?.id, logcat.paused) {
        if (following && !logcat.paused && rows.isNotEmpty()) list.scrollToItem(rows.lastIndex)
    }
    val style = RobomoriTheme.typography.codeSmall
    // Each row is a single text, so it lays out and selects quickly; its columns line up because the font is
    // monospaced, and wrapped lines indent under the message by the width of the columns before it.
    val measurer = rememberTextMeasurer()
    val charWidth = remember(style) { measurer.measure("0", style).size.width }
    val density = LocalDensity.current
    val colors = LogColors(RobomoriTheme.colors.text, RobomoriTheme.colors.textSecondary, RobomoriTheme.colors.shelfBorder,
        RobomoriTheme.colors.link)
    val divider = RobomoriTheme.colors.shelfBorder
    val danger = RobomoriTheme.colors.danger
    val columns = logcat.settings.columns
    val selection = rememberSelectionState()
    val scope = rememberCoroutineScope()
    // Long lines show two lines until clicked open.
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val clamped = remember { mutableStateMapOf<Long, Boolean>() }
    // A right-click selects its line for the menu. Compose also selects the word under the pointer then; that word is
    // let go again, unless the click landed in text selected before, which the menu's Copy then copies.
    fun openMenu(id: Long) {
        logcat.selectForMenu(id)
        val before = selection.selectedTexts
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (selection.selectedTexts != before) selection.clear()
        }
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(RobomoriTheme.colors.cardBackground)) {
        // How many characters fit across a row, inside its padding, decides how its columns are laid out.
        val fits = with(density) { ((maxWidth - 24.dp).toPx() / charWidth.coerceAtLeast(1)).toInt() }
        val widths = RowColumns(columns)
        val layout = when {
            fits >= widths.message + 40 -> RowLayout.Columns
            fits >= widths.levelEnd + 30 -> RowLayout.Inline
            else -> RowLayout.Narrow
        }
        val messageIndent = with(density) { (charWidth * widths.indent(layout)).toSp() }
        // Rows are picked like a list's, so the pointer stays an arrow rather than a text cursor.
        // Copies the selected text without the invisible breaks added for wrapping.
        SelectionContainer(selection, Modifier.fillMaxSize().pointerHoverIcon(PointerIcon.Default, overrideDescendants = true)
            .onPreviewKeyEvent { event ->
                val copy = event.type == KeyEventType.KeyDown && event.key == Key.C &&
                    (if (HostOs.detect() == HostOs.MAC) event.isMetaPressed else event.isCtrlPressed)
                if (!copy || selection.selectedTexts.isEmpty()) return@onPreviewKeyEvent false
                val text = unbreakable(selection.selectedTexts.joinToString("\n") { it.text })
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                true
            }) {
            LazyColumn(Modifier.fillMaxSize().testTag("LogcatLines"), state = list) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    val id = row.id
                    // Read per row, so a change of selection redraws only the rows it changes.
                    val selected by remember(id) { derivedStateOf { id in logcat.selectedLines } }
                    val crash by remember(id) { derivedStateOf { id in logcat.kinds.crashLines } }
                    val clicks = remember(id) {
                        Modifier.lineClicks(id, onClick = { toggle, extend ->
                            logcat.selectLine(id, toggle, extend)
                            if (!toggle && !extend && (clamped[id] == true || expanded[id] == true)) expanded[id] = expanded[id] != true
                        }, onSecondaryClick = { openMenu(id) })
                    }
                    val line = row.line
                    val background = when {
                        selected -> RobomoriTheme.colors.textSelection
                        crash -> danger.copy(alpha = .16f)
                        line is LogEntry && line.level >= LogLevel.Error -> LevelRed.copy(alpha = .1f)
                        else -> Color.Transparent
                    }
                    // A tag repeating the line above is left out, so changes of tag stand out.
                    val showTag = line is LogEntry && (rows.getOrNull(index - 1)?.line as? LogEntry)?.tag != line.tag
                    val highlights = (line as? LogEntry)?.let(logcat::highlights)
                    val text = remember(line, row.count, showTag, colors, layout, columns, messageIndent, highlights) {
                        rowText(line, row.count, showTag, colors, layout, columns, messageIndent, highlights, logcat::openSource)
                    }
                    val isExpanded = expanded[id] == true
                    // A crash's lines are marked down their edge too, so a crash reads as one block.
                    Box(Modifier.fillMaxWidth().background(background)
                        .then(if (crash) Modifier.drawBehind { drawRect(danger, size = Size(3.dp.toPx(), size.height)) } else Modifier)
                        // A process starting or ending divides the log with a hairline above it.
                        .then(if (line is ProcessMarker) Modifier.drawBehind { drawRect(divider, size = Size(size.width, 1.dp.toPx())) } else Modifier)
                        .then(clicks)
                        .padding(horizontal = 12.dp, vertical = if (line is ProcessMarker) 3.dp else 1.dp)) {
                        // Cut off rather than ellipsized: Compose drops the indent of a line it shortens with "…".
                        Text(text, Modifier.fillMaxWidth(), style = style.copy(textIndent = TextIndent(restLine = messageIndent)),
                            maxLines = if (isExpanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Clip,
                            onTextLayout = { layout ->
                                if (!isExpanded && clamped[id] != layout.hasVisualOverflow) clamped[id] = layout.hasVisualOverflow
                            })
                        // Says there's more, at the end of the last line shown, over whatever it covers.
                        if (!isExpanded && clamped[id] == true) {
                            Text("…", Modifier.align(Alignment.BottomEnd).background(RobomoriTheme.colors.cardBackground)
                                .background(background).padding(start = 4.dp), style = style, color = RobomoriTheme.colors.textSecondary)
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** The widths of the columns before the message, in characters, each followed by a space. */
private const val TimeColumn = 12
private const val PidColumn = 6
private const val LevelColumn = 3
private const val TagColumn = 24

/** Where the columns that are on end: the level's, and the tag's, where the message starts. */
private class RowColumns(columns: LogColumns) {
    val levelEnd = (if (columns.time) TimeColumn + 1 else 0) + (if (columns.pid) PidColumn + 1 else 0) + LevelColumn + 1
    val message = levelEnd + if (columns.tag) TagColumn + 1 else 0

    fun indent(layout: RowLayout) = when (layout) {
        RowLayout.Columns -> message
        RowLayout.Inline -> levelEnd
        RowLayout.Narrow -> 0
    }
}

/**
 * How a row's parts sit, by how wide the panel is: the tag in its own column when there's room; else just before the
 * message, with wrapped lines lined up after the level, or, narrower still, across the whole width.
 */
private enum class RowLayout { Columns, Inline, Narrow }

/**
 * [text] with an invisible break after punctuation such as dots and slashes, so long names and paths wrap there, filling
 * the line, rather than moving whole onto the next one. Copying takes the breaks out again.
 */
internal fun breakable(text: String): String = buildString(text.length + text.length / 4) {
    for (char in text) {
        append(char)
        if (char in BreakAfter) append(ZeroWidthSpace)
    }
}

internal fun unbreakable(text: String) = text.replace(ZeroWidthSpace.toString(), "")

private const val ZeroWidthSpace = '​'
private const val BreakAfter = "./\\=,;:_-()[]{}<>@&?#|"

private data class LogColors(val text: Color, val secondary: Color, val badge: Color, val link: Color)

/** Matched text, in a tone that reads on light and dark alike. */
private val MatchMark = SpanStyle(background = Color(0xFFF5A524).copy(alpha = .4f))

/**
 * Appends [text], which starts at [offset] in the text [marks] index into, with the marked parts stood out and each
 * part passed through [transform], such as [breakable], so the marks still line up.
 */
private fun AnnotatedString.Builder.appendMarked(text: String, offset: Int, marks: List<IntRange>, transform: (String) -> String = { it }) {
    var at = 0
    for (range in marks.sortedBy { it.first }) {
        val start = (range.first - offset).coerceIn(0, text.length)
        val end = (range.last + 1 - offset).coerceIn(0, text.length)
        if (end <= at) continue
        val from = maxOf(start, at)
        append(transform(text.substring(at, from)))
        withStyle(MatchMark) { append(transform(text.substring(from, end))) }
        at = end
    }
    append(transform(text.substring(at)))
}

/** time | pid | level | tag | message, as one styled text, with [highlights] marked. */
private fun rowText(line: LogLine, count: Int, showTag: Boolean, colors: LogColors, layout: RowLayout, columns: LogColumns,
                    indent: TextUnit, highlights: Highlights?, onFrame: (StackFrame) -> Unit): AnnotatedString = buildAnnotatedString {
    when (line) {
        is ProcessMarker -> withStyle(SpanStyle(color = colors.secondary)) {
            if (layout != RowLayout.Narrow) append(" ".repeat(RowColumns(columns).levelEnd))
            append("Process ${line.pid} ${if (line.started) "started" else "ended"} · ${line.process}")
        }
        is LogEntry -> {
            withStyle(SpanStyle(color = colors.secondary)) {
                if (columns.time) append(line.time.substringAfter(' ').padEnd(TimeColumn).take(TimeColumn) + " ")
                if (columns.pid) append(line.pid.toString().padStart(PidColumn) + " ")
            }
            // The level as a coloured block: V and D grey, I blue, W amber, E and worse red.
            val (fill, letter) = levelColors(line.level)
            withStyle(SpanStyle(color = letter, background = fill, fontWeight = FontWeight.Bold)) { append(" ${line.level.letter} ") }
            append(' ')
            // Tags are right-aligned, keeping their end when they're too long, in a colour of their own.
            val tagMarks = highlights?.tag.orEmpty()
            if (columns.tag && layout == RowLayout.Columns) {
                if (showTag) {
                    val kept = line.tag.takeLast(TagColumn)
                    append(" ".repeat(TagColumn - kept.length))
                    withStyle(SpanStyle(color = tagColor(line.tag))) { appendMarked(kept, line.tag.length - kept.length, tagMarks) }
                } else append(" ".repeat(TagColumn))
                append(' ')
            } else if (columns.tag && showTag) {
                withStyle(SpanStyle(color = tagColor(line.tag))) { appendMarked(line.tag, 0, tagMarks) }
                append(' ')
            }
            if (count > 1) {
                withStyle(SpanStyle(color = colors.text, background = colors.badge, fontWeight = FontWeight.Bold)) { append(" $count ") }
                append(' ')
            }
            withStyle(SpanStyle(color = colors.text)) {
                // The message's own lines, such as a stack trace's, each start under the message too.
                val marks = highlights?.message.orEmpty()
                val lines = line.message.split('\n')
                var offset = 0
                // A stack frame naming its file is a link that opens it in the IDE.
                val frame = stackFrame(lines.first())
                val links = TextLinkStyles(SpanStyle(color = colors.link), hoveredStyle = SpanStyle(color = colors.link,
                    textDecoration = TextDecoration.Underline))
                lines.forEachIndexed { index, text ->
                    if (index == 0 && frame != null) {
                        val (start, end) = frame.range.first to frame.range.last + 1
                        appendMarked(text.substring(0, start), 0, marks, ::breakable)
                        withLink(LinkAnnotation.Clickable("frame", links) { onFrame(frame) }) {
                            appendMarked(text.substring(start, end), start, marks, ::breakable)
                        }
                        appendMarked(text.substring(end), end, marks, ::breakable)
                    } else if (index == 0) appendMarked(text, offset, marks, ::breakable)
                    else {
                        append('\n')
                        withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = indent))) {
                            appendMarked(text, offset, marks, ::breakable)
                        }
                    }
                    offset += text.length + 1
                }
            }
        }
    }
}

private fun levelColors(level: LogLevel) = when (level) {
    LogLevel.Verbose, LogLevel.Debug -> Color(0xFF8E8E93) to Color.White
    LogLevel.Info -> Color(0xFF3B82F6) to Color.White
    LogLevel.Warn -> Color(0xFFF5A524) to Color(0xFF1F1F1F)
    LogLevel.Error, LogLevel.Fatal, LogLevel.Assert -> LevelRed to Color.White
}

private val LevelRed = Color(0xFFEF4444)


/** Tag colours readable on light and dark, so each tag keeps its own colour, which makes scanning easier. */
private val TagColors = listOf(0xFF2F7FE0, 0xFF10A37F, 0xFFD97706, 0xFF8B5CF6, 0xFFDB2777, 0xFF0D9488, 0xFFEA580C,
    0xFF4F46E5, 0xFF65A30D, 0xFF0284C7).map(::Color)

/** The same colour for a tag every time, picked by its name. */
private fun tagColor(tag: String) = TagColors[Math.floorMod(tag.hashCode(), TagColors.size)]

/**
 * Calls [onClick] when a line is clicked without dragging, with whether ⌘ (Ctrl elsewhere) or Shift was held, and
 * [onSecondaryClick] when it's right-clicked. A plain press is only watched, so dragging still selects text; a press
 * with ⌘ or Shift is taken, as it picks lines rather than extending the text selection.
 */
private fun Modifier.lineClicks(key: Any, onClick: (toggle: Boolean, extend: Boolean) -> Unit, onSecondaryClick: () -> Unit) =
    pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent(PointerEventPass.Initial)
                if (down.type != PointerEventType.Press) continue
                if (down.buttons.isSecondaryPressed) { onSecondaryClick(); continue }
                if (!down.buttons.isPrimaryPressed) continue
                val modifiers = down.keyboardModifiers
                val toggle = if (HostOs.detect() == HostOs.MAC) modifiers.isMetaPressed else modifiers.isCtrlPressed
                val extend = modifiers.isShiftPressed
                if (toggle || extend) down.changes.forEach { it.consume() }
                val start = down.changes.first().position
                var dragged = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (toggle || extend) event.changes.forEach { it.consume() }
                    if ((event.changes.first().position - start).getDistance() > viewConfiguration.touchSlop) dragged = true
                    if (event.type == PointerEventType.Release) break
                }
                if (!dragged) onClick(toggle, extend)
            }
        }
    }

/** Asks where to save the logs with the system's save dialog; null when cancelled. */
private fun chooseLogFile(): File? {
    val dialog = FileDialog(null as Frame?, "Save logs", FileDialog.SAVE).apply {
        directory = File(System.getProperty("user.home"), "Desktop").takeIf(File::isDirectory)?.path
        file = "logcat.txt"
        isVisible = true
    }
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

/** Asks for a log saved earlier, such as with Save as…, with the system's open dialog; null when cancelled. */
private fun chooseSavedLog(): File? {
    val dialog = FileDialog(null as Frame?, "Open a saved log", FileDialog.LOAD).apply {
        directory = File(System.getProperty("user.home"), "Desktop").takeIf(File::isDirectory)?.path
        setFilenameFilter { _, name -> name.endsWith(".txt", true) || name.endsWith(".logcat", true) || name.endsWith(".log", true) }
        isVisible = true
    }
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
