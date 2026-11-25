package com.robomori

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.theme.LazyColumn
import com.robomori.theme.RobomoriTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.composeunstyled.Text
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * A row of a [DataGrid]: its [key], which stays its own as rows are sorted and refreshed, its [cells] by column, the
 * and the columns whose cell holds bytes, shown by their size.
 */
internal class GridRow(
    val key: String,
    val cells: Map<String, String?>,
    val blobs: Set<String> = emptySet(),
)

/** A cell of a [DataGrid], by its row's key and its column. */
internal data class GridCell(val row: String, val column: String)

/**
 * What's selected in a [DataGrid]: the [rows], by key, and the [cell] the keys and typing act on, in one of them. [anchor]
 * is the row a Shift-click or Shift with an arrow key selects the rows from.
 */
internal data class GridSelection(val cell: GridCell? = null, val rows: Set<String> = emptySet(), val anchor: String? = null) {
    companion object {
        /** [cell] alone selected, with its row. */
        fun of(cell: GridCell) = GridSelection(cell, setOf(cell.row), cell.row)
    }
}

/** What a [DataGrid] lets be done to its cells and rows; each left out can't be done. */
internal class GridEditing(
    val canEdit: (GridRow, String) -> Boolean = { _, _ -> false },
    val canSetNull: (GridRow, String) -> Boolean = { _, _ -> false },
    val onEdit: (GridRow, String, String?) -> Unit = { _, _, _ -> },
    val onDeleteRows: ((List<GridRow>) -> Unit)? = null,
    /** For a cell with only two values, such as a boolean, the other one: editing it switches to that rather than typing. */
    val flipped: (GridRow, String) -> String? = { _, _ -> null },
)

// The narrowest a column gets: columns share the grid's width, and scroll sideways once they'd be narrower.
private val NarrowestColumn = 120.dp
private val CellHeight = 26.dp

/**
 * Rows of values under resizable, reorderable, sortable columns, worked like a spreadsheet: a click selects a cell, the
 * arrow keys and Tab move it, Return or a double-click types into it in place, ⌘C copies it. ⌘-click adds or removes a
 * row, Shift-click or Shift with ↑ ↓ selects the rows from the last one, and ⌘A selects them all, as in Finder. [onReachEnd] is told when
 * the list is scrolled near its last row, to show more. Whether the selected cell is being typed into is [typing], so a
 * button outside the grid can start it too.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DataGrid(
    layout: GridLayout,
    sort: DatabaseSort?,
    onSort: (String) -> Unit,
    rows: List<GridRow>,
    selection: GridSelection,
    onSelect: (GridSelection) -> Unit,
    editing: GridEditing,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    onReachEnd: () -> Unit = {},
    /** Icons after a column's title, by column, such as its type and a key for the primary key. */
    columnMarks: Map<String, List<TableColumnMark>> = emptyMap(),
) {
    val columns = layout.order.filter { it in layout.shares }
    if (columns.isEmpty()) return Spacer(modifier)
    val grid = remember { FocusRequester() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowIndex = remember(rows) { rows.withIndex().associate { (index, row) -> row.key to index } }
    val currentRows by rememberUpdatedState(rows)
    val reachEnd by rememberUpdatedState(onReachEnd)
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { it >= 0 && it >= currentRows.size - 20 }
            .collect { reachEnd() }
    }
    fun rowOf(cell: GridCell) = rowIndex[cell.row]?.let(rows::get)
    fun editable(cell: GridCell) = rowOf(cell)?.let { editing.canEdit(it, cell.column) } == true
    val selected = selection.cell
    fun select(next: GridSelection) { onSelect(next); onTyping(false) }
    // The rows from the anchor to [to], as Shift selects them.
    fun range(to: Int): Set<String> {
        val from = selection.anchor?.let(rowIndex::get) ?: to
        return rows.subList(minOf(from, to), maxOf(from, to) + 1).mapTo(mutableSetOf()) { it.key }
    }
    // Moving with the keyboard brings the cell into view; clicking one never scrolls.
    fun move(row: Int, column: String, extend: Boolean = false) {
        val target = rows.getOrNull(row) ?: return
        val cell = GridCell(target.key, column)
        select(if (extend) selection.copy(cell = cell, rows = range(row)) else GridSelection.of(cell))
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        if (row < visible.first().index + 1) scope.launch { list.scrollToItem(row) }
        else if (row > visible.last().index - 1) scope.launch { list.scrollToItem(row - visible.size + 2) }
    }
    Box(modifier.focusRequester(grid).focusable().testTag(tag).onPreviewKeyEvent { event ->
        val cell = selected
        if (event.type != KeyEventType.KeyDown || typing || cell == null) return@onPreviewKeyEvent false
        val row = rowIndex[cell.row] ?: return@onPreviewKeyEvent false
        val column = columns.indexOf(cell.column)
        when {
            event.key == Key.DirectionUp -> { if (row > 0) move(row - 1, cell.column, extend = event.isShiftPressed); true }
            event.key == Key.DirectionDown -> { if (row < rows.lastIndex) move(row + 1, cell.column, extend = event.isShiftPressed); true }
            event.key == Key.DirectionLeft -> { if (column > 0) move(row, columns[column - 1]); true }
            event.key == Key.DirectionRight || event.key == Key.Tab -> { if (column < columns.lastIndex) move(row, columns[column + 1]); true }
            event.key == Key.Enter || event.key == Key.NumPadEnter -> { if (editable(cell)) onTyping(true); true }
            event.key == Key.Escape -> { select(GridSelection()); true }
            event.key == Key.A && (event.isMetaPressed || event.isCtrlPressed) ->
                { select(selection.copy(rows = rows.mapTo(mutableSetOf()) { it.key })); true }
            // One row copies its cell; several copy as CSV, for a spreadsheet.
            event.key == Key.C && (event.isMetaPressed || event.isCtrlPressed) -> {
                if (selection.rows.size > 1) copy(toCsv(columns, rows.filter { it.key in selection.rows }.map { shown -> columns.map { shown.cells[it] } }))
                else copy(rows[row].cells[cell.column] ?: "NULL")
                true
            }
            else -> false
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tableWidth = maxOf(maxWidth, NarrowestColumn * columns.size)
            val sideways = rememberScrollState()
            Column(Modifier.fillMaxHeight().horizontalScroll(sideways).width(tableWidth)) {
                TableHeader(columns.map { TableColumn(it, it, columnMarks[it].orEmpty()) }, layout.shares, layout::resize, sort?.column, sort?.ascending ?: true,
                    onSort, tagPrefix = tag,
                    // Cells run edge to edge, so a selected one is outlined whole; their text sits inset in them.
                    startPadding = 0.dp, endPadding = 0.dp, firstTitleInset = TableDefaults.CellInset, onReorder = layout::reorder)
                CardDivider()
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        val rowSelected = row.key in selection.rows
                        Row(Modifier.fillMaxWidth().height(CellHeight)
                            .background(if (rowSelected) RobomoriTheme.colors.textSelection.copy(alpha = 0.25f) else TableDefaults.rowColor(index)),
                            verticalAlignment = Alignment.CenterVertically) {
                            columns.forEach { column ->
                                val cell = GridCell(row.key, column)
                                val isSelected = selected == cell
                                val value = row.cells[column]
                                val canEdit = editing.canEdit(row, column)
                                TableCell(layout.shares.getValue(column), Modifier.fillMaxHeight().selectedTableCell(isSelected)
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        if (!event.buttons.isPrimaryPressed) return@onPointerEvent
                                        val keys = event.keyboardModifiers
                                        if (!(isSelected && typing)) {
                                            select(when {
                                                keys.isShiftPressed -> selection.copy(cell = cell, rows = range(index))
                                                keys.isMetaPressed || keys.isCtrlPressed ->
                                                    GridSelection(cell, if (rowSelected) selection.rows - row.key else selection.rows + row.key, row.key)
                                                else -> GridSelection.of(cell)
                                            })
                                            grid.requestFocus()
                                        }
                                        // A second click types into it, as in a spreadsheet.
                                        if (event.awtEventOrNull?.clickCount == 2 && canEdit) onTyping(true)
                                    }) {
                                    val flip = if (isSelected && typing) editing.flipped(row, column) else null
                                    if (flip != null) LaunchedEffect(row.key, column) { onTyping(false); editing.onEdit(row, column, flip) }
                                    else if (isSelected && typing) CellEditor(value) { edited ->
                                        onTyping(false)
                                        grid.requestFocus()
                                        if (edited != null) editing.onEdit(row, column, edited.value)
                                    }
                                    else ContextMenuArea(items = {
                                        listOfNotNull(
                                            ContextMenuItem("Copy") { copy(value ?: "NULL") },
                                            if (canEdit) ContextMenuItem("Edit") { select(GridSelection.of(cell)); onTyping(true) } else null,
                                            if (value != null && editing.canSetNull(row, column)) ContextMenuItem("Set to NULL") { editing.onEdit(row, column, null) } else null,
                                            ContextMenuItem("Copy Rows as CSV") {
                                                copy(toCsv(columns, rows.map { shown -> columns.map { shown.cells[it] } }))
                                            },
                                            // On one of the selected rows, it deletes them all; on another, just that one.
                                            editing.onDeleteRows?.let { delete ->
                                                val targets = if (rowSelected) rows.filter { it.key in selection.rows } else listOf(row)
                                                ContextMenuItem(if (targets.size == 1) "Delete Row" else "Delete ${targets.size} Rows") { delete(targets) }
                                            },
                                        )
                                    }) {
                                        Text(value ?: "NULL", modifier = Modifier.fillMaxWidth().padding(end = TableDefaults.CellInset), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, style = RobomoriTheme.typography.small,
                                            color = if (value == null || column in row.blobs) RobomoriTheme.colors.textSecondary else RobomoriTheme.colors.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Over the rows, below the header, where they stay put however far the grid scrolls sideways.
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.TopEnd).fillMaxHeight()
                .padding(top = TableDefaults.HeaderHeight + 1.dp))
            if (tableWidth > maxWidth) HorizontalScrollbar(rememberScrollbarAdapter(sideways), Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
    }
}

private fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

/** What an edit of a cell ends with: the text typed, which saves, or nothing, which leaves the cell as it was. */
private class EditedCell(val value: String)

/** Types into a cell in place: Return saves what's typed, as does clicking away; Esc leaves the cell as it was. */
@Composable
private fun CellEditor(value: String?, onDone: (EditedCell?) -> Unit) {
    val state = rememberTextFieldState(value.orEmpty(), initialSelection = TextRange(0, value.orEmpty().length))
    val focus = remember { FocusRequester() }
    var finished by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    fun finish(edited: EditedCell?) { if (!finished) { finished = true; onDone(edited) } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TableCellField(state, Modifier.fillMaxWidth().testTag("GridCellEditor").focusRequester(focus)
        .onFocusChanged { if (focused && !it.isFocused) finish(EditedCell(state.text.toString())); focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Enter, Key.NumPadEnter -> { finish(EditedCell(state.text.toString())); true }
                Key.Escape -> { finish(null); true }
                else -> false
            }
        })
}
