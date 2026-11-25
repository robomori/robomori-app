@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.robomori

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import java.awt.Toolkit
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.awt.datatransfer.Transferable
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.foundation.draganddrop.dragAndDropSource
import java.io.File
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import com.robomori.theme.verticalScroll
import androidx.compose.ui.input.key.*
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.focusable
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.*
import com.robomori.theme.LazyColumn
import com.robomori.theme.RobomoriTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.PlatformFile
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.composeunstyled.Text
import java.time.Instant
import java.util.Locale
import java.time.ZoneId

@Composable
internal fun rememberDeviceFiles(state: AppState, visible: Boolean): DeviceFilesState? {
    val serial = reachableSerial(state) ?: return null
    return key(serial, state.settings) {
        val scope = rememberCoroutineScope()
        // The folder last open on this device, by its name rather than its serial, which changes when it restarts or
        // reconnects, so the page picks up where it was.
        val device = state.connected.selected?.name ?: state.previewDevice?.name ?: serial
        val files = remember {
            DeviceFilesState(state.deviceFilesService, serial, scope, debuggableApps = state.installedAppsService::debuggableApps,
                startPlace = state.filesPlaces[device])
        }
        LaunchedEffect(files) { snapshotFlow { files.place }.collect { state.rememberFilesPlace(device, it) } }
        LaunchedEffect(files) { files.refresh() }
        // Every few seconds while on screen, so newly installed apps' folders show up in /data/data.
        LaunchedEffect(files, visible) { while (visible) { files.loadApps(); delay(5000) } }
        // Android has no way to watch a folder over adb, so every few seconds the open one is checked, and read again
        // when it changed.
        LaunchedEffect(files, visible) { while (visible) { delay(2000); files.poll() } }
        // Files opened on this computer are checked for edits even while the page is hidden, as they're edited elsewhere.
        LaunchedEffect(files) { while (true) { files.checkEdits(); delay(1000) } }
        files
    }
}

@Composable
internal fun DeviceFilesPanel(state: AppState, files: DeviceFilesState?, onOpenInAppData: (DeviceFile) -> Unit = {}) {
    val message = when {
        state.selected.size > 1 -> "Select one emulator to browse its files."
        files == null && state.connected.selected != null -> "Connect this device to browse its files."
        files == null && state.previewDevice == null -> "Select a device to browse its files."
        files == null -> "Start this emulator to browse its files."
        else -> null
    }
    if (message != null || files == null) {
        Box(Modifier.fillMaxSize().padding(16.dp).testTag("DeviceFilesPanel"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Folder, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
                DeviceStateMessage(message.orEmpty())
            }
        }
        return
    }
    val window = LocalAwtWindow.current
    val scope = rememberCoroutineScope()
    val dialogs = { title: String -> FileKitDialogSettings(title = title, parent = window?.let { FileKitDialogParent.awt(it) }) }
    /** Asks where to save [targets]: a file alone under a name, several into a folder. */
    fun save(targets: List<DeviceFile>) = scope.launch {
        val file = targets.singleOrNull()
        if (file != null) {
            val destination = FileKit.openFileSaver(file.name.substringBeforeLast('.', file.name),
                defaultExtension = file.name.substringAfterLast('.', "").ifEmpty { null }, dialogSettings = dialogs("Save ${file.name}"))?.file
            if (destination != null) files.save(file, destination)
        } else {
            val directory = FileKit.openDirectoryPicker(dialogSettings = dialogs("Save ${targets.size} files"))?.file
            if (directory != null) files.saveAll(targets, directory)
        }
    }
    /** Asks which app to open [file] in: one from /Applications on macOS, Windows' own dialog, or any program on Linux. */
    fun openWith(file: DeviceFile) = scope.launch {
        when (HostOs.detect()) {
            HostOs.WINDOWS -> files.openWith(file, null)
            HostOs.MAC -> FileKit.openFilePicker(type = FileKitType.File(extensions = setOf("app")), directory = PlatformFile("/Applications"),
                dialogSettings = dialogs("Open ${file.name} With"))?.file?.let { files.openWith(file, it) }
            HostOs.LINUX -> FileKit.openFilePicker(dialogSettings = dialogs("Open ${file.name} With"))?.file?.let { files.openWith(file, it) }
        }
    }
    val busy = files.action is FileActionState.Running
    // Each folder opens scrolled to its top.
    // Each folder keeps where it was scrolled to, so going back up returns to the same place, as in Finder.
    val scrolls = remember(files) { mutableMapOf<String, LazyListState>() }
    val list = scrolls.getOrPut(files.folder) { files.scrollOf(files.folder).let { LazyListState(it.index, it.offset) } }
    LaunchedEffect(list, files.folder) {
        val folder = files.folder
        snapshotFlow { FilesScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }.collect { files.rememberScroll(folder, it) }
    }
    // Rows animate once hidden files are shown or hidden in this folder, not as a folder's files first arrive.
    var animateRows by remember(files.folder) { mutableStateOf(false) }
    // Keyboard shortcuts reach the page once it has focus, which clicking in the list gives it.
    val focus = remember { FocusRequester() }
    val search = rememberTextFieldState()
    val searchFocus = remember { FocusRequester() }
    val typeAhead = remember(files.folder) { TableTypeAhead() }
    var selectionBox by remember { mutableStateOf<Rect?>(null) }
    // The row selected on arriving in a folder, such as the one just left going up, is scrolled into view.
    LaunchedEffect(files.folder, files.snapshot is FolderSnapshot.Loaded) {
        val name = files.selection.singleOrNull() ?: return@LaunchedEffect
        val index = files.shownFiles.indexOfFirst { it.path == name }.takeIf { it >= 0 } ?: return@LaunchedEffect
        list.scrollIntoView(index + if (files.showsParentRow) 1 else 0)
    }
    // While previewing, the keys move the selection from the preview, so the list follows it into view.
    LaunchedEffect(files.selection) {
        if (!files.previewing) return@LaunchedEffect
        val name = files.selection.singleOrNull() ?: return@LaunchedEffect
        val index = files.shownFiles.indexOfFirst { it.path == name }.takeIf { it >= 0 } ?: return@LaunchedEffect
        list.scrollIntoView(index + if (files.showsParentRow) 1 else 0)
    }
    // Once a name has been edited in its row, the keys go back to the files.
    var wasRenaming by remember { mutableStateOf(false) }
    LaunchedEffect(files.renaming) {
        if (files.renaming == null && wasRenaming) runCatching { focus.requestFocus() }
        wasRenaming = files.renaming != null
    }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(files) { snapshotFlow { search.text.toString() }.collect(files::search) }
    // Opening another folder clears the search, so the field clears too.
    LaunchedEffect(files.query) { if (files.query.isEmpty() && search.text.isNotEmpty()) search.clearText() }
    // Opening the page, or closing the editor, gives the files the keys.
    LaunchedEffect(files.editor == null) { if (files.editor == null) runCatching { focus.requestFocus() } }
    val editor = files.editor
    // A file being edited takes the page's place until it's closed.
    if (editor != null) TextFileEditorView(editor)
    // The path sits on the panel's chrome, the files on white below it.
    else Column(Modifier.fillMaxSize().testTag("DeviceFilesPanel")
        .focusRequester(focus).focusable()
        .onPointerEvent(PointerEventType.Press) { focus.requestFocus() }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // While a name is edited in its row, the keys are the field's own.
            if (files.renaming != null) return@onPreviewKeyEvent false
            // Esc closes the preview, as in Finder.
            if (files.previewing && event.key == Key.Escape) return@onPreviewKeyEvent run { files.closePreview(); true }
            // While typing a search, Esc clears it and goes back to the files; other keys are the field's own.
            if (searching) {
                if (event.key != Key.Escape) return@onPreviewKeyEvent false
                search.clearText()
                focus.requestFocus()
                return@onPreviewKeyEvent true
            }
            val shortcut = fileShortcut(event.key, meta = event.isMetaPressed, ctrl = event.isCtrlPressed, alt = event.isAltPressed,
                shift = event.isShiftPressed, mac = HostOs.detect() == HostOs.MAC) ?: run {
                    // Typing a name's first letters selects the first file that starts with them, as in Finder.
                    val char = typedChar(event) ?: return@onPreviewKeyEvent false
                    val shown = files.shownFiles
                    typeAhead.type(char, shown.map { it.name })?.let { index ->
                        files.select(shown[index])
                        scope.launch { list.scrollIntoView(index + if (files.showsParentRow) 1 else 0) }
                    }
                    return@onPreviewKeyEvent true
                }
            when (shortcut) {
                FileShortcut.Back -> files.back()
                FileShortcut.Forward -> files.forward()
                FileShortcut.Up -> files.up()
                FileShortcut.Open -> files.openSelection()
                FileShortcut.ToggleHidden -> { animateRows = true; files.toggleHidden() }
                FileShortcut.Search -> searchFocus.requestFocus()
                FileShortcut.Delete -> files.deleteSelection()
                FileShortcut.Copy -> copyFiles(files)
                FileShortcut.Paste -> pasteFiles(files)
                FileShortcut.Cut -> files.cut(files.shownFiles.filter { it.path in files.selection })
                FileShortcut.MoveHere -> files.paste(moving = true)
                FileShortcut.Undo -> files.undo()
                FileShortcut.SelectAll -> files.selectAll()
                FileShortcut.QuickLook -> files.toggleQuickLook()
                FileShortcut.Previous, FileShortcut.Next ->
                    files.moveSelection(if (shortcut == FileShortcut.Next) 1 else -1, extend = event.isShiftPressed)?.let { row ->
                        scope.launch { list.scrollIntoView(row) }
                    }
            }
            true
        }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The search narrows with a narrow panel, so the path keeps room.
            val searchWidth = minOf(SearchWidth, maxWidth * .3f)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = files::up, enabled = !files.atRoot, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                    contentDescription = "Up a folder", tag = "FilesUp") { Icon(Icons.ArrowLeftSolid) }
                Spacer(Modifier.width(6.dp))
                PathBar(files, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Field(search, tag = "FilesSearch", placeholder = "Search",
                    modifier = Modifier.width(searchWidth).focusRequester(searchFocus).onFocusChanged { searching = it.isFocused })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(RobomoriTheme.colors.cardBackground).clipToBounds()) {
            // While a folder is read, its list stays empty under the header rather than showing a message, so moving
            // between folders doesn't flicker.
            when (val snapshot = files.snapshot) {
                is FolderSnapshot.Failed -> CenteredMessage(snapshot.cause.message ?: "Could not open this folder.")
                FolderSnapshot.Loading, is FolderSnapshot.Loaded -> FileDropArea("Drop files here", "FilesDropArea", enabled = !busy && !files.draggingOut && snapshot is FolderSnapshot.Loaded,
                    onDrop = files::upload, modifier = Modifier.fillMaxSize()) {
                    Column {
                        FileHeader(files)
                        CardDivider()
                        // Right-clicking the space around the files offers what can be made or put in this folder.
                        ContextMenuArea(items = {
                            if (busy) emptyList() else listOfNotNull(
                                ContextMenuItem("New Folder", files::newFolder),
                                ContextMenuItem("Paste") { pasteFiles(files) }.takeIf { files.copied.isNotEmpty() || finderFiles() != null },
                                ContextMenuItem("Move Here") { files.paste(moving = true) }.takeIf { files.copied.isNotEmpty() },
                            )
                        }) {
                        Box(Modifier.fillMaxSize().clipToBounds().selectionBoxGesture(files, list) { selectionBox = it }) {
                            // Room after the last row for the bar of edited files, so it never covers a row for good.
                            LazyColumn(Modifier.fillMaxSize(), state = list,
                                contentPadding = PaddingValues(bottom = if (files.edits.isEmpty()) 0.dp
                                    else (SnackbarMinHeight + EditBarSpacing) * files.edits.size + EditBarMargin * 2)) {
                                if (files.showsParentRow) item(key = ParentRowName) { ParentRow(files) }
                                itemsIndexed(files.shownFiles, key = { _, file -> file.path }) { index, file ->
                                    // Hidden files fade in or out, and the rows around them slide to their new places.
                                    // Every other row is shaded, counting the ".." row above them.
                                    Box((if (animateRows) Modifier.animateItem(fadeInSpec = tween(220), fadeOutSpec = tween(160))
                                        else Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null))
                                        .background(TableDefaults.rowColor(index + if (files.showsParentRow) 1 else 0))) {
                                        FileRow(files, file, busy, onSave = ::save, onOpenWith = ::openWith, onOpenInAppData = onOpenInAppData)
                                    }
                                }
                            }
                            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                            if (files.isSearching) when (val found = files.found) {
                                FolderSnapshot.Loading -> Box(Modifier.align(Alignment.Center).padding(24.dp)) { DeviceStateMessage("Searching…") }
                                is FolderSnapshot.Failed -> Box(Modifier.align(Alignment.Center).padding(24.dp)) { DeviceStateMessage("Could not search this device.") }
                                is FolderSnapshot.Loaded -> if (files.shownFiles.isEmpty()) Box(Modifier.align(Alignment.Center).padding(24.dp)) {
                                    DeviceStateMessage("No files match “${files.query.trim()}”.")
                                }
                                null -> {}
                            }
                            EditBar(files, busy, Modifier.align(Alignment.BottomCenter))
                            TransferBar(files, Modifier.align(Alignment.BottomCenter))
                            SelectionBox(selectionBox, Modifier.matchParentSize())
                        }
                        }
                    }
                }
            }
        }
    }
    AppDialog(files.conflict, onClose = { files.resolveConflict(null) }) { conflict -> ConflictDialog(conflict, files) }
    AppDialog(files.confirmingDelete, onClose = files::dismissDelete) { targets -> ConfirmDeleteDialog(targets, files) }
    AppDialog(files.action as? FileActionState.Failed, onClose = files::dismissFailure) { failure ->
        FileActionFailedDialog(failure, onClose = files::dismissFailure)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(files: DeviceFilesState, file: DeviceFile, busy: Boolean, onSave: (List<DeviceFile>) -> Unit,
                    onOpenWith: (DeviceFile) -> Unit, onOpenInAppData: (DeviceFile) -> Unit) {
    val selected = file.path in files.selection
    // Dragged, the row shows only a tag with its name, or how many files go with it, as Finder does.
    val textMeasurer = rememberTextMeasurer()
    val dragLabel = if (selected && files.selection.size > 1) "${files.selection.size} items" else file.name
    val tagColors = RobomoriTheme.colors.toastBackground to RobomoriTheme.colors.toastText
    // The row's own icon goes with it, as Finder drags a file's icon with its name.
    val dragThumbnail = files.thumbnail(file)
    val dragIcon = rememberVectorPainter(when (file.type) {
        FileType.Folder -> Icons.FolderSolid
        FileType.Database -> Icons.CircleStackSolid
        FileType.Image -> Icons.PhotoSolid
        FileType.File -> Icons.DocumentSolid
    })
    val dragTint = if (file.type == FileType.Folder) RobomoriTheme.colors.folderIcon else RobomoriTheme.colors.textSecondary
    ContextMenuArea(items = {
        val targets = files.contextTargets(file)
        if (busy) emptyList() else buildList {
            val single = targets.singleOrNull()
            add(ContextMenuItem("Open") { files.openAll(targets) })
            add(ContextMenuItem("Copy") { copyFiles(files, targets) })
            add(ContextMenuItem("Copy Path") { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(pathsText(targets)), null) })
            add(ContextMenuItem("Quick Look") { files.toggleQuickLook() })
            if (single?.kind == DeviceFileKind.File) add(ContextMenuItem("Open With…") { onOpenWith(single) })
            if (single != null && files.canEdit(single)) add(ContextMenuItem("Edit") { files.edit(single) })
            if (single != null && files.canOpenInAppData(single)) add(ContextMenuItem("Open in App Data") { onOpenInAppData(single) })
            if (targets.any { it.kind == DeviceFileKind.File }) add(ContextMenuItem("Save to Computer…") { onSave(targets) })
            if (single != null) add(ContextMenuItem("Rename…") { files.requestRename(single) })
            add(ContextMenuItem("Delete…") { files.requestDelete(targets) })
        }
    }) {
        var dropping by remember { mutableStateOf(false) }
        SidebarListItem(selected = selected || dropping, contentPadding = RowPadding, cornerSize = 0.dp,
            modifier = Modifier.fillMaxWidth().testTag("DeviceFile:${file.name}")
                // Files dragged from this page onto a folder move into it, as in Finder.
                .let { if (file.kind == DeviceFileKind.Folder) it.moveTarget(files, file.path) { dropping = it } else it }
                // Dragging a file or folder out drops a copy of it, or of every selected one, in Finder or another app.
                .dragAndDropSource(drawDragDecoration = { dragTag(dragLabel, textMeasurer, tagColors, dragIcon, dragTint, dragThumbnail) }) { _ ->
                    if (busy) return@dragAndDropSource null
                    val targets = files.contextTargets(file)
                    files.draggingOut = true
                    files.dragged = targets
                    DragAndDropTransferData(DragAndDropTransferable(DeviceFilesTransferable { files.copiesForDrag(targets) }),
                        supportedActions = listOf(DragAndDropTransferAction.Copy),
                        onTransferCompleted = { files.draggingOut = false; files.dragged = emptyList() })
                }
                .semantics { this.selected = selected }
                // Selects as the button goes down. A click waits to rule out a double-click first, so selecting on it
                // would lag, and would select this row again after a quick click on another.
                // Cmd adds or removes it, Shift selects the files from the last one clicked, like Finder.
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (!event.buttons.isPrimaryPressed) return@onPointerEvent
                    val keys = event.keyboardModifiers
                    files.select(file, toggle = keys.isMetaPressed || keys.isCtrlPressed, extend = keys.isShiftPressed)
                }
                .combinedClickable(onDoubleClick = { files.open(file) }) {}) {
            Box(Modifier.fillMaxWidth()) {
                // Hidden files are faded, like in Finder.
                Row(Modifier.fillMaxWidth().alpha(if (file.isHidden) .5f else 1f), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(files.columnShares.getValue(FileColumn.Name)), verticalAlignment = Alignment.CenterVertically) {
                        FileIcon(files, file)
                        Spacer(Modifier.width(10.dp))
                        if (files.renaming?.path == file.path) InlineName(file, files, Modifier.weight(1f))
                        else {
                            Label(file.name, Modifier.weight(1f, fill = false), maxLines = 1)
                            // A file a search found says which folder it's in, as Finder's search results do.
                            if (files.isSearching) {
                                Spacer(Modifier.width(8.dp))
                                SecondaryLine(parentOf(file.path).removePrefix(files.searchFolder).trimStart('/').ifEmpty { "/" })
                            }
                        }
                    }
                    for (column in files.columnOrder.drop(1)) Cell(files, column, when (column) {
                        FileColumn.Modified -> if (file.modified == Instant.EPOCH) "--"
                            else formatModified(file.modified, Instant.now(), ZoneId.systemDefault(), Locale.getDefault(), uses24HourClock)
                        FileColumn.Size -> if (file.kind == DeviceFileKind.File) formatFileSize(file.size) else "--"
                        FileColumn.Kind -> file.kindTitle
                        FileColumn.Name -> file.name
                    })
                }
            }
        }
    }
}

private val RowPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)

private val FileColumn.title get() = when (this) {
    FileColumn.Name -> "Name"
    FileColumn.Modified -> "Date Modified"
    FileColumn.Size -> "Size"
    FileColumn.Kind -> "Kind"
}

/** What the file is, like Finder's Kind column: "PNG image", or the extension of other files. */
private val DeviceFile.kindTitle get() = when (type) {
    FileType.Folder -> "Folder"
    FileType.Database -> "Database"
    FileType.Image -> "${extension.uppercase()} image"
    FileType.File -> if (extension.isEmpty()) "File" else "${extension.uppercase()} file"
}

private val DeviceFile.extension get() = name.substringAfterLast('.', "").takeIf { name.lastIndexOf('.') > 0 }.orEmpty()

/** The column titles, like Finder's: clicking one sorts by it, and dragging the line between two resizes just those two. */
@Composable
private fun FileHeader(files: DeviceFilesState) {
    TableHeader(files.columnOrder.map { TableColumn(it, it.title) }, files.columnShares, files::resizeColumns,
        files.sort.column, files.sort.ascending, files::sortBy, startPadding = 16.dp, endPadding = 16.dp,
        firstTitleInset = IconSlot + 10.dp, minColumnWidth = MinColumnWidth, tagPrefix = "Files",
        onReorder = files::reorderColumns)
}

@Composable
private fun RowScope.Cell(files: DeviceFilesState, column: FileColumn, text: String) {
    // Sizes line up on their right, so their digits compare at a glance.
    TableCell(files.columnShares.getValue(column), alignEnd = column == FileColumn.Size) { SecondaryLine(text) }
}

// Wide enough for an image's preview; type icons sit in the middle of it.
private val IconSlot = 24.dp

/** A preview of an image, or an icon of the file's type. */
@Composable
private fun FileIcon(files: DeviceFilesState, file: DeviceFile) {
    if (file.isImage || file.isVideo) LaunchedEffect(file) { files.loadThumbnail(file) }
    val thumbnail = files.thumbnail(file)
    val app = files.appFor(file)
    // Spins in the icon's place while the file is opened, saved, uploaded or deleted.
    val working = file.name in (files.action as? FileActionState.Running)?.files.orEmpty()
    Box(Modifier.size(IconSlot), contentAlignment = Alignment.Center) {
        if (working) Spinner(Modifier.size(14.dp))
        else if (app != null) AppIcon(app, IconSlot)
        // At the media's own shape, as large as fits the slot.
        else if (thumbnail != null) {
            val ratio = thumbnail.width.toFloat() / thumbnail.height
            Image(thumbnail, contentDescription = null, modifier = Modifier
                .size(if (ratio >= 1) IconSlot else IconSlot * ratio, if (ratio >= 1) IconSlot / ratio else IconSlot)
                .clip(RoundedCornerShape(3.dp)))
        }
        else Icon(when (file.type) {
            FileType.Folder -> Icons.FolderSolid
            FileType.Database -> Icons.CircleStackSolid
            FileType.Image -> Icons.PhotoSolid
            FileType.File -> Icons.DocumentSolid
        }, tint = if (file.type == FileType.Folder) RobomoriTheme.colors.folderIcon else RobomoriTheme.colors.textSecondary,
            modifier = Modifier.size(16.dp))
    }
}

/** Scrolls just enough to show the row at [index]. */
private suspend fun LazyListState.scrollIntoView(index: Int) {
    val shown = layoutInfo.visibleItemsInfo
    val first = shown.firstOrNull()?.index ?: return
    val last = shown.last().index
    when {
        index <= first -> scrollToItem(index)
        index >= last -> scrollToItem(maxOf(0, index - (last - first) + 1))
    }
}

/**
 * The files opened on this computer and saved since, as snackbars over the bottom of the list, each to upload back or
 * discard.
 */
@Composable
private fun EditBar(files: DeviceFilesState, busy: Boolean, modifier: Modifier) {
    val edits = files.edits
    // Keeps the last edits on screen while the snackbars slide away.
    var shown by remember { mutableStateOf(edits) }
    if (edits.isNotEmpty()) shown = edits
    AnimatedVisibility(edits.isNotEmpty(), modifier.fillMaxWidth(),
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it }) {
        Column(Modifier.fillMaxWidth().padding(EditBarMargin).testTag("FilesEdits"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(EditBarSpacing)) {
            for (edit in shown) {
                Snackbar("Push changes to ${edit.file.name}?", icon = Icons.DocumentSolid, tag = "FileEdit:${edit.file.name}") {
                    Button(onClick = { files.discardEdit(edit) }, enabled = !busy, style = ButtonStyle.Ghost, size = ButtonSize.Small,
                        tag = "DiscardEdit:${edit.file.name}") { Label("Discard", color = RobomoriTheme.colors.toastText) }
                    Button(onClick = { files.uploadEdit(edit) }, enabled = !busy, style = ButtonStyle.Primary, size = ButtonSize.Small,
                        tag = "PushEdit:${edit.file.name}") { Label("Push") }
                }
            }
        }
    }
}

private val EditBarMargin = 12.dp
private val EditBarSpacing = 8.dp

/** The `..` row, going up a folder when double-clicked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParentRow(files: DeviceFilesState) {
    val selected = ParentRowName in files.selection
    SidebarListItem(selected = selected, contentPadding = RowPadding, cornerSize = 0.dp,
        modifier = Modifier.fillMaxWidth().testTag("DeviceFile:..")
            .semantics { this.selected = selected }
            .onPointerEvent(PointerEventType.Press) { event -> if (event.buttons.isPrimaryPressed) files.selectParent() }
            .combinedClickable(onDoubleClick = files::up) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(IconSlot), contentAlignment = Alignment.Center) {
                Icon(Icons.FolderSolid, tint = RobomoriTheme.colors.folderIcon, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Label("..")
        }
    }
}

/**
 * [file]'s name, editable in its row, as Finder renames: selected up to its extension, saved with Enter or by clicking
 * away, and left as it was with Esc.
 */
@Composable
private fun InlineName(file: DeviceFile, files: DeviceFilesState, modifier: Modifier) {
    val name = remember(file) {
        TextFieldState(file.name, TextRange(0, file.name.lastIndexOf('.').takeIf { it > 0 && file.kind == DeviceFileKind.File } ?: file.name.length))
    }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(file) { focus.requestFocus() }
    Field(name, "RenameFileName", modifier.focusRequester(focus)
        .onFocusChanged { if (focused && !it.isFocused) files.confirmRename(name.text.toString()); focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.Enter, Key.NumPadEnter -> { files.confirmRename(name.text.toString()); true }
                Key.Escape -> { files.dismissRename(); true }
                else -> false
            }
        })
}

@Composable
private fun ConfirmDeleteDialog(targets: List<DeviceFile>, files: DeviceFilesState) {
    val cancelFocus = remember { FocusRequester() }
    val file = targets.singleOrNull()
    val title = if (file != null) "Delete ${file.name}?" else "Delete ${targets.size} items?"
    AppDialogPanel(title, "ConfirmDeleteFile", Modifier.width(420.dp)) {
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(title)
            if (file == null) {
                Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).testTag("DeleteFileNames"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    targets.forEach { Label(it.name, maxLines = 1) }
                }
            }
            Label(when {
                file?.kind == DeviceFileKind.Folder -> "It will be removed from the device, with everything in it. This cannot be undone."
                file != null -> "It will be removed from the device. This cannot be undone."
                targets.any { it.kind == DeviceFileKind.Folder } -> "They'll be removed from the device, with everything in the folders. This cannot be undone."
                else -> "They'll be removed from the device. This cannot be undone."
            }, color = RobomoriTheme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = files::dismissDelete, style = ButtonStyle.Outlined, modifier = Modifier.focusRequester(cancelFocus),
                    tag = "CancelDeleteFile") { Label("Cancel") }
                Button(onClick = files::confirmDelete, style = ButtonStyle.Destructive, tag = "ConfirmDeleteFile") { Label("Delete") }
            }
        }
    }
}

@Composable
private fun FileActionFailedDialog(failure: FileActionState.Failed, onClose: () -> Unit) {
    val okFocus = remember { FocusRequester() }
    AppDialogPanel("Couldn't ${failure.action.failureVerb} ${failure.name}", "FileActionFailed", Modifier.width(420.dp)) {
        LaunchedEffect(Unit) { okFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            failure.cause.message?.takeIf { it.isNotBlank() }?.let { Label(it) }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose, style = ButtonStyle.Primary, modifier = Modifier.focusRequester(okFocus),
                    tag = "DismissFileActionFailed") { Label("OK") }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { DeviceStateMessage(text) }
}

@Composable
internal fun SecondaryLine(text: String) {
    Text(text, color = RobomoriTheme.colors.textSecondary, style = RobomoriTheme.typography.small,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private val FileAction.failureVerb get() = when (this) {
    FileAction.Open -> "open"
    FileAction.Rename -> "rename"
    FileAction.Save -> "save"
    FileAction.Upload -> "upload"
    FileAction.Delete -> "delete"
    FileAction.CreateFolder -> "make"
    FileAction.Paste -> "paste"
    FileAction.Move -> "move"
    FileAction.Undo -> "undo"
}

/** What a running action says it's doing, with the file under way, such as "Uploading clip.mp4". */
private val FileAction.progressVerb get() = when (this) {
    FileAction.Open -> "Opening"
    FileAction.Save -> "Saving"
    FileAction.Upload -> "Uploading"
    FileAction.Paste -> "Pasting"
    FileAction.Move -> "Moving"
    else -> "Working on"
}


internal fun formatFileSize(bytes: Long) = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/** The open folder's path, each folder a button that goes straight to it. */
private val FocusRingRoom = 3.dp
private val SearchWidth = 180.dp

@Composable
private fun PathBar(files: DeviceFilesState, modifier: Modifier) {
    val scroll = rememberScrollState()
    val crumbs = files.crumbs
    // Keeps the open folder in view when the path is too long to fit.
    LaunchedEffect(crumbs) { scroll.scrollTo(scroll.maxValue) }
    // Room inside the scrolling row for the focus ring, which is drawn just outside a button and would be cut off.
    Row(modifier.horizontalScroll(scroll).padding(FocusRingRoom).testTag("FilesPath"), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Icon(Icons.ChevronRightSolid, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(12.dp))
            Button(onClick = { files.goTo(crumb) }, style = ButtonStyle.Ghost,
                size = ButtonSize.Small, tag = "FilesPath-$index") {
                Icon(Icons.FolderSolid, tint = RobomoriTheme.colors.folderIcon, modifier = Modifier.size(14.dp))
                Label(crumb.name ?: "/", maxLines = 1)
            }
        }
    }
}

/** Files from the device as a list of files on this computer, copied only when the app they're dropped on asks for them. */
private class DeviceFilesTransferable(copy: () -> List<File>) : Transferable {
    private val files by lazy(copy)
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (isDataFlavorSupported(flavor)) files else throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
}

/** The tag a dragged row shows in its place: [label] on a rounded background, where the row's name starts. */
private fun DrawScope.dragTag(label: String, measurer: TextMeasurer, colors: Pair<Color, Color>, icon: Painter, tint: Color,
                               thumbnail: ImageBitmap?) {
    // The icon where it sits in the row: the image's own preview when there is one, at its shape.
    val slot = IconSlot.toPx()
    val slotLeft = 16.dp.toPx()
    val slotTop = (size.height - slot) / 2
    if (thumbnail != null) {
        val ratio = thumbnail.width.toFloat() / thumbnail.height
        val drawn = if (ratio >= 1) Size(slot, slot / ratio) else Size(slot * ratio, slot)
        drawImage(thumbnail, dstOffset = IntOffset((slotLeft + (slot - drawn.width) / 2).toInt(), (slotTop + (slot - drawn.height) / 2).toInt()),
            dstSize = IntSize(drawn.width.toInt(), drawn.height.toInt()))
    } else {
        val iconSize = 16.dp.toPx()
        translate(slotLeft + (slot - iconSize) / 2, (size.height - iconSize) / 2) {
            with(icon) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(tint)) }
        }
    }
    val text = measurer.measure(label, TextStyle(fontSize = 12.sp, color = colors.second), maxLines = 1,
        overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (size.width * .6f).toInt().coerceAtLeast(1)))
    val padding = Offset(8.dp.toPx(), 3.dp.toPx())
    val tag = Size(text.size.width + padding.x * 2, text.size.height + padding.y * 2)
    val topLeft = Offset((IconSlot + 16.dp).toPx(), (size.height - tag.height) / 2)
    drawRoundRect(colors.first, topLeft, tag, CornerRadius(tag.height / 2))
    drawText(text, topLeft = topLeft + padding)
}

/**
 * Copies [targets] to paste into another folder on the device, and puts copies of them on the clipboard as files, once
 * they're on this computer, so Finder and other apps paste them too.
 */
private fun copyFiles(files: DeviceFilesState, targets: List<DeviceFile> = files.shownFiles.filter { it.path in files.selection }) {
    files.copy(targets) { local ->
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(FileListTransferable(local), null) }
    }
}

/** Files on this computer, for the clipboard. */
private class FileListTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (isDataFlavorSupported(flavor)) files else throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
}

/**
 * Pastes files copied in Finder or another app into the open folder, or else the device's own files copied here, which
 * the clipboard holds copies of on this computer.
 */
private fun pasteFiles(files: DeviceFilesState) {
    val local = finderFiles()
    if (local != null && local.toSet() != files.copiedToComputer.toSet()) files.upload(local) else files.paste()
}

/** The files on this computer copied to the clipboard, such as in Finder, or null when there are none. */
private fun finderFiles(): List<File>? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (!clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return null
    (clipboard.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().ifEmpty { null }
}.getOrNull()

/** Makes this a place files dragged from the page drop into, telling [onHover] while they're over it. */
@Composable
private fun Modifier.moveTarget(files: DeviceFilesState, destination: String, onHover: (Boolean) -> Unit): Modifier {
    val currentHover by rememberUpdatedState(onHover)
    val target = remember(files, destination) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = currentHover(true)
            override fun onExited(event: DragAndDropEvent) = currentHover(false)
            override fun onEnded(event: DragAndDropEvent) = currentHover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentHover(false)
                files.moveInto(files.dragged, destination)
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { files.draggingOut && files.dragged.none { it.path == destination } }, target = target)
}

/** Asks what to do with files going into a folder that has some of their names already, as Finder does. */
@Composable
private fun ConflictDialog(conflict: NameConflict, files: DeviceFilesState) {
    val single = conflict.names.singleOrNull()
    val focus = remember { FocusRequester() }
    AppDialogPanel(if (single != null) "\u201c$single\u201d is already here" else "${conflict.names.size} items are already here",
        "NameConflict", Modifier.width(460.dp)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(if (single != null) "An item named \u201c$single\u201d is already in this folder. Replace it, keep both, or skip it?"
                else "These are already in this folder: ${conflict.names.joinToString()}. Replace them, keep both, or skip them?")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { files.resolveConflict(null) }, style = ButtonStyle.Ghost, tag = "CancelConflict") { Label("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { files.resolveConflict(ConflictChoice.Skip) }, style = ButtonStyle.Outlined, tag = "SkipConflict") { Label("Skip") }
                Button(onClick = { files.resolveConflict(ConflictChoice.KeepBoth) }, style = ButtonStyle.Outlined,
                    modifier = Modifier.focusRequester(focus), tag = "KeepBothConflict") { Label("Keep Both") }
                Button(onClick = { files.resolveConflict(ConflictChoice.Replace) }, style = ButtonStyle.Primary, tag = "ReplaceConflict") { Label("Replace") }
            }
        }
    }
}

/**
 * How far a long copy to or from the device has got, with a way to stop it. It shows only once a copy has gone on a
 * moment, so quick ones don't flash it.
 */
@Composable
private fun TransferBar(files: DeviceFilesState, modifier: Modifier) {
    val running = files.action as? FileActionState.Running
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(running?.action, running?.name) {
        shown = false
        if (running != null) { delay(TransferBarDelayMillis); shown = true }
    }
    val progress = running?.progress
    AnimatedVisibility(shown && running != null && progress != null, modifier.fillMaxWidth(),
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it }) {
        val current = running ?: return@AnimatedVisibility
        val done = current.progress ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth().padding(EditBarMargin), contentAlignment = Alignment.Center) {
            Snackbar("${current.action.progressVerb} ${done.file}", icon = Icons.DocumentSolid, tag = "FilesTransfer",
                supportingText = done.total?.takeIf { it > 0 }?.let { "${(done.done * 100 / it).coerceIn(0, 100)}% of ${formatFileSize(it)}" }
                    ?: formatFileSize(done.done)) {
                Button(onClick = files::cancelAction, style = ButtonStyle.Ghost, size = ButtonSize.Small, tag = "CancelTransfer") {
                    Label("Cancel", color = RobomoriTheme.colors.toastText)
                }
            }
        }
    }
}

private const val TransferBarDelayMillis = 600L

/**
 * Draws a box dragged out from the empty space below the files, selecting the rows it crosses, as in Finder. On the
 * list's container rather than over it, so presses on rows still reach them, and drag the file instead.
 */
private fun Modifier.selectionBoxGesture(files: DeviceFilesState, list: LazyListState, onBox: (Rect?) -> Unit) = pointerInput(files, list) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val rowsEnd = list.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size } ?: 0
        if (down.isConsumed || down.position.y <= rowsEnd) return@awaitEachGesture
        val start = down.position
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            val rect = Rect(minOf(start.x, change.position.x), minOf(start.y, change.position.y),
                maxOf(start.x, change.position.x), maxOf(start.y, change.position.y))
            onBox(rect)
            files.selectPaths(list.layoutInfo.visibleItemsInfo
                .filter { it.offset < rect.bottom && it.offset + it.size > rect.top }
                .mapNotNull { (it.key as? String)?.takeIf { key -> key != ParentRowName } })
        }
        onBox(null)
    }
}

/** The selection box, while one is being dragged out. */
@Composable
private fun SelectionBox(box: Rect?, modifier: Modifier) {
    val rect = box ?: return
    Box(modifier) {
        with(LocalDensity.current) {
            Box(Modifier.offset(rect.left.toDp(), rect.top.toDp()).size(rect.width.toDp(), rect.height.toDp())
                .background(RobomoriTheme.colors.textSelection.copy(alpha = .25f))
                .border(1.dp, RobomoriTheme.colors.textSelection))
        }
    }
}

/**
 * [editor]'s file as editable text, filling the page: its name and how saving went above, with Cancel and Save. ⌘S
 * (Ctrl+S elsewhere) saves, and Esc closes, asking first when there are changes.
 */
@Composable
private fun TextFileEditorView(editor: TextFileEditor) {
    val file = editor.file
    val mac = HostOs.detect() == HostOs.MAC
    val saving = editor.save == TextSaveState.Saving
    Column(Modifier.fillMaxSize().testTag("FileEditor").onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            event.key == Key.S && (if (mac) event.isMetaPressed else event.isCtrlPressed) -> { editor.save(); true }
            event.key == Key.Escape -> { editor.cancel(); true }
            else -> false
        }
    }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.DocumentSolid, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            // Takes what the buttons leave, so a long name or error shortens rather than pushing them out of a narrow panel.
            Column(Modifier.weight(1f)) {
                Label(file.name, maxLines = 1)
                SecondaryLine(when (val save = editor.save) {
                    TextSaveState.Saving -> "Saving…"
                    is TextSaveState.Failed -> "Couldn't save: ${save.cause.message ?: "the device didn't take the file."}"
                    TextSaveState.Saved -> if (editor.changed) "Edited" else "Saved to the device"
                    TextSaveState.Idle, TextSaveState.Conflict -> if (editor.changed) "Edited" else parentOf(file.path)
                })
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = editor::cancel, enabled = !saving, style = ButtonStyle.Outlined, tag = "CancelFileEdit") {
                Label(if (editor.changed) "Cancel" else "Close")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { editor.save() }, enabled = editor.changed && !saving, style = ButtonStyle.Primary, tag = "SaveFileEdit") {
                Label("Save")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(RobomoriTheme.colors.cardBackground).padding(12.dp)) {
            when (val content = editor.content) {
                TextEditorContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                TextEditorContent.Ready -> {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    CodeBlock(editor.text, Modifier.fillMaxSize().focusRequester(focus), tag = "FileEditorText")
                }
                is TextEditorContent.TooLarge ->
                    CenteredMessage("${file.name} is ${formatFileSize(content.size)}, too large to edit here. Open it on your computer instead.")
                TextEditorContent.NotText -> CenteredMessage("${file.name} isn't a text file, so it can't be edited here.")
                is TextEditorContent.Failed -> CenteredMessage(content.cause.message ?: "Could not open ${file.name}.")
            }
        }
    }
    AppDialog(editor.save as? TextSaveState.Conflict, onClose = editor::dismissConflict) { EditConflictDialog(editor) }
    AppDialog(editor.takeIf { it.confirmingDiscard }, onClose = editor::keepEditing) { DiscardEditDialog(it) }
}

/** Says the file changed on the device since it was opened, and asks whether to reload it or overwrite it. */
@Composable
private fun EditConflictDialog(editor: TextFileEditor) {
    val focus = remember { FocusRequester() }
    val name = editor.file.name
    AppDialogPanel("$name changed on the device", "FileEditConflict", Modifier.width(460.dp)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label("$name changed on the device")
            Label("It was changed on the device after you opened it. Reload it to see the device's version, losing your changes, " +
                "or overwrite it with yours.", color = RobomoriTheme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = editor::dismissConflict, style = ButtonStyle.Ghost, modifier = Modifier.focusRequester(focus),
                    tag = "KeepEditingConflict") { Label("Keep Editing") }
                Spacer(Modifier.weight(1f))
                Button(onClick = editor::load, style = ButtonStyle.Outlined, tag = "ReloadEditedFile") { Label("Reload") }
                Button(onClick = editor::overwrite, style = ButtonStyle.Destructive, tag = "OverwriteEditedFile") { Label("Overwrite") }
            }
        }
    }
}

/** Asks before closing the editor with changes that weren't saved. */
@Composable
private fun DiscardEditDialog(editor: TextFileEditor) {
    val focus = remember { FocusRequester() }
    val title = "Discard your changes to ${editor.file.name}?"
    AppDialogPanel(title, "DiscardFileEdit", Modifier.width(420.dp)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(title)
            Label("They haven't been saved to the device.", color = RobomoriTheme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = editor::keepEditing, style = ButtonStyle.Outlined, modifier = Modifier.focusRequester(focus),
                    tag = "KeepEditingFile") { Label("Keep Editing") }
                Button(onClick = editor::discard, style = ButtonStyle.Destructive, tag = "DiscardFileEdit") { Label("Discard") }
            }
        }
    }
}
