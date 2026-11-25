package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.*

@Composable
fun rememberAppSidebarState(initialValue: Boolean = true): UnstyledDrawerState<Boolean> = remember {
    UnstyledDrawerState(initialValue = initialValue, snapPoints = DrawerSnapPoints {
        false at DrawerSnapPoint.Zero
        true at DrawerSnapPoint.ContentSize
    })
}

@Composable
fun AppScaffold(
    topInset: Dp,
    modifier: Modifier = Modifier,
    sidebarState: UnstyledDrawerState<Boolean> = rememberAppSidebarState(),
    sidebarPinned: Boolean = false,
    showSidebars: Boolean = true,
    contentTopInset: Dp = maxOf(topInset, 40.dp),
    title: String? = null,
    subtitle: String? = null,
    sidebar: @Composable (topInset: Dp) -> Unit,
    topStartActions: @Composable RowScope.() -> Unit = {},
    topEndActions: @Composable RowScope.() -> Unit = {},
    /** The sidebar's width before it's dragged, and where each drag leaves it, to keep between runs. */
    initialSidebarWidth: Dp = 270.dp,
    onSidebarWidthChange: (Dp) -> Unit = {},
    trailingSidebarState: UnstyledDrawerState<Boolean>? = null,
    /** The share of the window the trailing sidebar opens at, before it's dragged; it keeps that share as the window resizes. */
    trailingSidebarFraction: Float = 0.3f,
    /**
     * When set, the trailing sidebar opens this wide instead, and dragging keeps it a width rather than a share of the
     * window, up to [trailingSidebarMaxWidth].
     */
    trailingSidebarWidth: Dp? = null,
    trailingSidebarMaxWidth: Dp = Dp.Infinity,
    /** Where each drag leaves the trailing sidebar's width, to keep between runs. */
    onTrailingSidebarWidthChange: (Dp) -> Unit = {},
    trailingSidebar: @Composable (topInset: Dp) -> Unit = {},
    /**
     * Actions fixed at the window's top end, over the trailing sidebar when it's open, such as the buttons that open
     * its panels. [topEndActions] keep clear of them.
     */
    fixedEndActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val headerHeight = maxOf(topInset, if (title != null) 64.dp else 40.dp)
    val fullSidebarWidth = 270.dp
    var leadingWidth by remember { mutableStateOf(initialSidebarWidth) }
    var trailingFraction by remember(trailingSidebarFraction) { mutableStateOf(trailingSidebarFraction) }
    var trailingFixedWidth by remember(trailingSidebarWidth) { mutableStateOf(trailingSidebarWidth) }
    var fixedActionsWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val sidebarWidth = if (!showSidebars) 0.dp else if (sidebarPinned) fullSidebarWidth else with(LocalDensity.current) { sidebarState.offset.toDp() }
    val trailingWidth = if (!showSidebars) 0.dp else with(LocalDensity.current) { trailingSidebarState?.offset?.toDp() ?: 0.dp }
    val startActionsWidth = maxOf(sidebarWidth, if (topInset > 0.dp) 152.dp else 80.dp)
    val titleClearanceWidth = maxOf(sidebarWidth, if (topInset > 0.dp) 98.dp else 80.dp)
    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xFFF1F1F1))) {
        val windowWidth = maxWidth
        val windowHeight = maxHeight
        // The device keeps at least this much room beside the trailing sidebar.
        val trailingMaxWidth = minOf(windowWidth - sidebarWidth - 320.dp, trailingSidebarMaxWidth).coerceAtLeast(TrailingMinWidth)
        val trailingPanelWidth = (trailingFixedWidth ?: (windowWidth * trailingFraction)).coerceIn(TrailingMinWidth, trailingMaxWidth)
        Box(Modifier.fillMaxSize().padding(start = sidebarWidth, end = trailingWidth)
            .background(Color.White)
            .clipToBounds()
            .padding(top = if (title != null) 0.dp else contentTopInset)) {
            Column(Modifier.fillMaxSize()) {
                // Above the content, which can reach under it, such as a zoomed-in device.
                if (title != null) AppBar(title, subtitle, Modifier.fillMaxWidth().zIndex(1f).padding(
                    start = when {
                        showSidebars -> titleClearanceWidth - sidebarWidth
                        topInset > 0.dp -> 80.dp
                        else -> 0.dp
                    },
                    // Clear of the fixed actions while the trailing sidebar is narrower than them.
                    end = (fixedActionsWidth + 8.dp - trailingWidth).coerceAtLeast(0.dp),
                ), topEndActions)
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
        if (showSidebars && sidebarPinned) {
            Box(Modifier.width(fullSidebarWidth).fillMaxHeight()
                .border(1.dp, Color(0xFFD6D6D6), listOf(BorderSide.End))) { sidebar(headerHeight) }
        } else if (showSidebars) UnstyledDrawer(
            state = sidebarState,
            placement = DrawerPlacement.Start,
            presentation = DrawerPresentation.InPlace,
            dismissOnClickOutside = false,
            dismissOnNavigateBack = false,
            gesturesEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) {
            Viewport<Boolean>(Modifier.fillMaxSize()) {
                Panel(Modifier.width(leadingWidth).fillMaxHeight()
                    .border(1.dp, Color(0xFFD6D6D6), listOf(BorderSide.End))) { sidebar(headerHeight) }
            }
        }
        if (showSidebars && !sidebarPinned) SidebarResizeHandle(sidebarState, leadingWidth, { leadingWidth = it; onSidebarWidthChange(it) },
            minWidth = 240.dp, maxWidth = 300.dp, edge = sidebarWidth, trailing = false, tag = "ResizeSidebar")
        Box(Modifier.fillMaxSize()) {
            if (showSidebars && trailingSidebarState != null) UnstyledDrawer(
                state = trailingSidebarState,
                placement = DrawerPlacement.End,
                presentation = DrawerPresentation.InPlace,
                dismissOnClickOutside = false,
                dismissOnNavigateBack = false,
                gesturesEnabled = false,
                modifier = Modifier.fillMaxSize(),
            ) {
                Viewport<Boolean>(Modifier.fillMaxSize()) {
                    Panel(Modifier.width(trailingPanelWidth).fillMaxHeight()
                        .border(1.dp, Color(0xFFD6D6D6), listOf(BorderSide.Start))) {
                        trailingSidebar(headerHeight)
                    }
                }
            }
            if (showSidebars && trailingSidebarState != null) SidebarResizeHandle(trailingSidebarState, trailingPanelWidth,
                { if (trailingFixedWidth != null) trailingFixedWidth = it else trailingFraction = it / windowWidth; onTrailingSidebarWidthChange(it) }, minWidth = TrailingMinWidth, maxWidth = trailingMaxWidth,
                edge = trailingWidth, trailing = true, tag = "ResizeTrailingSidebar")
        }
        Row(Modifier.align(Alignment.TopEnd).height(headerHeight).padding(end = 8.dp)
            .onSizeChanged { fixedActionsWidth = with(density) { it.width.toDp() } + 8.dp },
            verticalAlignment = Alignment.CenterVertically, content = fixedEndActions)
        Row(Modifier.align(Alignment.TopStart)
            .width(startActionsWidth)
            .height(headerHeight).padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.Top,
            content = topStartActions)
        if (title == null) Row(Modifier.align(Alignment.TopEnd).height(headerHeight).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, content = topEndActions)
    }
}

private val TrailingMinWidth = 280.dp


/**
 * The draggable inner edge of a sidebar. Dragging resizes it between [minWidth] and [maxWidth]; dragging well past
 * [minWidth] hides it, and dragging back out within the same drag shows it again, as macOS split views do.
 */
@Composable
private fun BoxScope.SidebarResizeHandle(
    state: UnstyledDrawerState<Boolean>,
    width: Dp,
    onWidthChange: (Dp) -> Unit,
    minWidth: Dp,
    maxWidth: Dp,
    /** Where the sidebar's inner edge is now, measured from its own side of the window. */
    edge: Dp,
    trailing: Boolean,
    tag: String,
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    // The width the pointer asks for, unclamped, so the drag can pass the minimum and come back.
    var requested by remember { mutableStateOf(0f) }
    if (!state.targetValue && !dragging) return
    val handleWidth = 8.dp
    Box(
        Modifier.align(if (trailing) Alignment.TopEnd else Alignment.TopStart)
            .offset(x = if (trailing) -(edge - handleWidth / 2) else edge - handleWidth / 2)
            .width(handleWidth).fillMaxHeight()
            .testTag(tag)
            // At its widest the sidebar can only shrink, so the cursor points only that way.
            .pointerHoverIcon(resizeCursor(atMin = false, atMax = width >= maxWidth, growsLeft = trailing))
            .draggable(
                rememberDraggableState { delta ->
                    requested += if (trailing) -delta else delta
                    val asked = with(density) { requested.toDp() }
                    val show = asked >= minWidth / 2
                    if (show) onWidthChange(asked.coerceIn(minWidth, maxWidth))
                    // Hides and shows at once, without animating, so the panel follows the pointer.
                    if (state.targetValue != show) state.jumpTo(show)
                },
                Orientation.Horizontal,
                onDragStarted = {
                    dragging = true
                    requested = with(density) { (if (state.targetValue) width else 0.dp).toPx() }
                },
                onDragStopped = { dragging = false },
            ),
    )
}

