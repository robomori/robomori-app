package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robomori.iconography.ChevronDown
import com.robomori.iconography.ChevronUp
import com.robomori.iconography.Icons
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text

/** A small icon after a column's title saying something about it, such as its type, named by [description] on hover. */
class TableColumnMark(val icon: ImageVector, val description: String, val tint: Color = Color.Unspecified)

/** A column of a [TableHeader], keyed by [key], with [title] in the header followed by its [marks]. */
class TableColumn<K>(val key: K, val title: String, val marks: List<TableColumnMark> = emptyList())

object TableDefaults {
    /** How far text in the columns after the first starts past their edge, in the header and the rows alike. */
    val CellInset = 4.dp
    /** The narrowest a column can be dragged to. */
    val MinColumnWidth = 48.dp
    val HeaderHeight = 28.dp

    /** The background of the row at [index]: every other row is shaded, as in Finder. */
    @Composable
    fun rowColor(index: Int): Color = if (index % 2 == 1) RobomoriTheme.colors.tableStripe else Color.Transparent
}

/**
 * The column titles of a table, as in Finder and Chrome's Network tab. Each column takes its share of the table's
 * width from [shares], so they all grow and shrink with it, as Chrome's do.
 *
 * Clicking a title sorts by its column, with an arrow at its end when [sortedBy] is that column. The line between two
 * columns resizes just those two, as AG Grid's shift resizing does: one grows by what the other gives up, so no other
 * column moves. It stops where either reaches [minColumnWidth].
 *
 * Columns in [fixedWidths] keep that width instead of a share and can't be moved. Dragging the line after one passes
 * its new width to [onResizeFixed], and the columns with shares make room; without it, they can't be resized.
 *
 * [startPadding] and [endPadding] are the rows' own, so the columns line up with them; the first title's clickable
 * area still reaches the table's edge, and [firstTitleInset] moves its text past something the rows show first, such
 * as an icon. Tags are "[tagPrefix]Sort-key" and "[tagPrefix]Resize-key", the resize one on the line at that column's
 * start.
 */
@Composable
fun <K> TableHeader(
    columns: List<TableColumn<K>>,
    shares: Map<K, Float>,
    onResize: (Map<K, Float>) -> Unit,
    sortedBy: K?,
    ascending: Boolean,
    onSort: (K) -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 12.dp,
    endPadding: Dp = 12.dp,
    firstTitleInset: Dp = 0.dp,
    minColumnWidth: Dp = TableDefaults.MinColumnWidth,
    tagPrefix: String = "Table",
    /** Takes the columns' keys in a new order, after a title after the first is dragged sideways; null when they can't move. */
    onReorder: ((List<K>) -> Unit)? = null,
    fixedWidths: Map<K, Dp> = emptyMap(),
    onResizeFixed: ((K, Dp) -> Unit)? = null,
) {
    require(columns.isNotEmpty()) { "A table needs a column." }
    // The column being dragged to a new place, and how far it's been dragged from where it now sits.
    var dragging by remember { mutableStateOf<K?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    BoxWithConstraints(modifier.fillMaxWidth().height(TableDefaults.HeaderHeight)) {
        val keys = columns.map { it.key }
        // The width the columns share, as the rows lay theirs out.
        val room = (maxWidth - startPadding - endPadding - keys.sumOf { fixedWidths[it]?.value?.toDouble() ?: 0.0 }.toFloat().dp)
            .coerceAtLeast(1.dp)
        val total = keys.filter { it !in fixedWidths }.sumOf { shares.getValue(it).toDouble() }.toFloat()
        fun widthOf(index: Int) = fixedWidths[keys[index]] ?: (room * (shares.getValue(keys[index]) / total))
        val density = LocalDensity.current
        val currentKeys by rememberUpdatedState(keys)
        val widthPx by rememberUpdatedState { index: Int -> with(density) { widthOf(index).toPx() } }
        Row(Modifier.fillMaxSize().padding(start = startPadding, end = endPadding), verticalAlignment = Alignment.CenterVertically) {
            // Keyed by column, so a column moved mid-drag keeps the gesture dragging it rather than handing it to another.
            columns.forEachIndexed { index, column -> key(column.key) {
                val moving = dragging == column.key
                val fixed = fixedWidths[column.key]
                Box((if (fixed != null) Modifier.width(fixed) else Modifier.weight(shares.getValue(column.key))).fillMaxHeight()
                    .zIndex(if (moving) 1f else 0f)
                    .graphicsLayer { if (moving) translationX = dragOffset }
                    .let { box ->
                        if (onReorder == null || index == 0 || fixed != null) box
                        else box.pointerInput(column.key) {
                            // Moves the column past a neighbour once it's dragged over half of it, as Finder's do.
                            detectHorizontalDragGestures(
                                onDragStart = { dragging = column.key; dragOffset = 0f },
                                onDragEnd = { dragging = null; dragOffset = 0f },
                                onDragCancel = { dragging = null; dragOffset = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount
                                val order = currentKeys
                                val at = order.indexOf(column.key)
                                if (dragOffset > 0 && at < order.lastIndex && dragOffset > widthPx(at + 1) / 2) {
                                    dragOffset -= widthPx(at + 1)
                                    onReorder(order.toMutableList().apply { add(at + 1, removeAt(at)) })
                                } else if (dragOffset < 0 && at > 1 && -dragOffset > widthPx(at - 1) / 2) {
                                    dragOffset += widthPx(at - 1)
                                    onReorder(order.toMutableList().apply { add(at - 1, removeAt(at)) })
                                }
                            }
                        }
                    }) {
                    if (index == 0) HeaderTitle(column, sortedBy, ascending, onSort, Modifier.fillMaxSize().extendStart(startPadding),
                        start = startPadding + firstTitleInset, tagPrefix)
                    else HeaderTitle(column, sortedBy, ascending, onSort, Modifier.fillMaxSize(), start = TableDefaults.CellInset, tagPrefix)
                    // On the line between this column and the one before it.
                    if (index > 0) Box(Modifier.align(Alignment.CenterStart).offset(x = (-4).dp).width(9.dp).fillMaxHeight(),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.width(1.dp).height(14.dp).background(RobomoriTheme.colors.shelfBorder))
                        val leftFixed = fixedWidths[keys[index - 1]]
                        if (fixed == null && leftFixed != null && onResizeFixed != null) {
                            // Narrower than other columns can go, as one that keeps its width holds short values, such as numbers.
                            ResizeHandle(leftFixed, { onResizeFixed(keys[index - 1], it) }, MinFixedColumnWidth,
                                (leftFixed + widthOf(index) - minColumnWidth).coerceAtLeast(minColumnWidth), Modifier.matchParentSize(),
                                tag = "${tagPrefix}Resize-${column.key}")
                        } else if (fixed == null && leftFixed == null) {
                            val pair = widthOf(index - 1) + widthOf(index)
                            ResizeHandle(widthOf(index - 1), { left ->
                                onResize(resizedBetween(keys, shares, index, (left / room) * total))
                            }, minColumnWidth, (pair - minColumnWidth).coerceAtLeast(minColumnWidth), Modifier.matchParentSize(),
                                tag = "${tagPrefix}Resize-${column.key}")
                        }
                    }
                }
            } }
        }
    }
}

private val MinFixedColumnWidth = 30.dp

/** Lays this out [by] wider, reaching that far before where it's placed, such as over its parent's padding. */
private fun Modifier.extendStart(by: Dp) = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/**
 * [shares] after the line before the column at [index] moved so the column to its left has [left]: the column at
 * [index] gives up what that one gains, and every other column keeps its share.
 */
internal fun <K> resizedBetween(keys: List<K>, shares: Map<K, Float>, index: Int, left: Float): Map<K, Float> {
    val leftKey = keys[index - 1]
    val rightKey = keys[index]
    val gained = left - shares.getValue(leftKey)
    return shares + (leftKey to left) + (rightKey to shares.getValue(rightKey) - gained)
}

/**
 * A cell of a column, taking the column's [share] of the row as [TableHeader] gives it, with its content starting
 * [TableDefaults.CellInset] in, or ending that far from the column's end with [alignEnd], as Finder lines up sizes.
 * The first column's cell has no inset, so its content lines up with the row's own padding.
 */
@Composable
fun RowScope.TableCell(share: Float, modifier: Modifier = Modifier, alignEnd: Boolean = false, first: Boolean = false,
                       content: @Composable BoxScope.() -> Unit) =
    TableCell(modifier.weight(share), alignEnd, first, content)

/** A cell of a column that keeps [width], as [TableHeader]'s fixedWidths give it. */
@Composable
fun TableCell(width: Dp, modifier: Modifier = Modifier, alignEnd: Boolean = false, first: Boolean = false,
              content: @Composable BoxScope.() -> Unit) =
    TableCell(modifier.width(width), alignEnd, first, content)

@Composable
private fun TableCell(modifier: Modifier, alignEnd: Boolean, first: Boolean, content: @Composable BoxScope.() -> Unit) {
    val inset = if (first) 0.dp else TableDefaults.CellInset
    Box(modifier.padding(start = inset, end = if (alignEnd) TableDefaults.CellInset else 0.dp),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart, content = content)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun <K> HeaderTitle(column: TableColumn<K>, sortedBy: K?, ascending: Boolean, onSort: (K) -> Unit,
                            modifier: Modifier, start: Dp, tagPrefix: String) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val sorted = sortedBy == column.key
    Row(modifier.fillMaxHeight()
        .background(if (hovered) RobomoriTheme.colors.text.copy(alpha = .05f) else Color.Transparent)
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onSort(column.key) }
        .testTag("${tagPrefix}Sort-${column.key}")
        .padding(start = start, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(column.title, Modifier.weight(1f), style = RobomoriTheme.typography.small, maxLines = 1,
            overflow = TextOverflow.Ellipsis, fontWeight = if (sorted) FontWeight.SemiBold else null)
        // At the column's end, so the titles line up with their rows' values.
        for (mark in column.marks) {
            Spacer(Modifier.width(4.dp))
            Tooltip(mark.description) {
                Icon(mark.icon, tint = mark.tint.takeOrElse { RobomoriTheme.colors.textSecondary }, modifier = Modifier.size(12.dp))
            }
        }
        if (column.marks.isNotEmpty() && sorted) Spacer(Modifier.width(4.dp))
        if (sorted) Icon(if (ascending) Icons.ChevronUp else Icons.ChevronDown, tint = RobomoriTheme.colors.textSecondary,
            modifier = Modifier.size(10.dp))
    }
}

/** Marks the selected cell of a table, as a spreadsheet does: tinted, with a line around it. */
@Composable
fun Modifier.selectedTableCell(selected: Boolean): Modifier =
    if (!selected) this
    else background(RobomoriTheme.colors.textSelection.copy(alpha = 0.35f))
        .border(1.5.dp, RobomoriTheme.colors.textSelectionHandle)

/**
 * The text of a table's cell, typed into in place. It's set exactly as the cell's own text, with no box or padding of its
 * own, so editing a cell moves and resizes nothing.
 */
@Composable
fun TableCellField(state: TextFieldState, modifier: Modifier = Modifier) {
    BasicTextField(state, modifier, textStyle = RobomoriTheme.typography.small.copy(color = RobomoriTheme.colors.text),
        lineLimits = TextFieldLineLimits.SingleLine, cursorBrush = SolidColor(RobomoriTheme.colors.text))
}

/**
 * Finds a table's row by the start of its name as it's typed, as Finder does: letters typed within a moment of each other
 * add up, and after a pause the next one starts over.
 */
class TableTypeAhead(private val now: () -> Long = System::currentTimeMillis) {
    private var typed = ""
    private var lastTyped = Long.MIN_VALUE

    /** The index of the first of [names] starting with what's been typed, now with [char], or null when none does. */
    fun type(char: Char, names: List<String>): Int? {
        val time = now()
        typed = if (time - lastTyped > PauseMillis) char.toString() else typed + char
        lastTyped = time
        return names.indexOfFirst { it.startsWith(typed, ignoreCase = true) }.takeIf { it >= 0 }
    }

    private companion object { const val PauseMillis = 1000L }
}

/** The letter [event] types, when it's a key press with no ⌘, Ctrl or Alt held, for [TableTypeAhead]. */
fun typedChar(event: androidx.compose.ui.input.key.KeyEvent): Char? {
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return null
    val code = event.utf16CodePoint
    return code.toChar().takeIf { code in 0x20..0xFFFF && !it.isISOControl() && !it.isWhitespace() }
}
