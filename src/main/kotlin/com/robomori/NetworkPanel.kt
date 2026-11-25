package com.robomori

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.robomori.iconography.LayoutGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Point
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthContext
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.robomori.components.*
import com.robomori.iconography.Globe
import com.robomori.iconography.XMark
import com.robomori.iconography.Icons
import com.robomori.theme.LazyColumn
import com.robomori.theme.RobomoriTheme
import com.robomori.theme.verticalScroll
import com.robomori.theme.horizontalScroll
import androidx.compose.foundation.text.input.rememberTextFieldState
import kotlinx.coroutines.delay
import com.composeunstyled.Text
import com.composeunstyled.DialogHost
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.robomori.iconography.ChevronDown
import com.robomori.iconography.ChevronUp

/** Whether a connected device's traffic can be recorded. */
internal sealed interface PhoneNetworkAccess {
    /** Plugged in over USB, which carries its traffic to Robomori. */
    data class Usb(val serial: String) : PhoneNetworkAccess
    /** Connected over Wi-Fi only, which is too slow and unsteady to carry every app's traffic. */
    data object WifiOnly : PhoneNetworkAccess
    data object Disconnected : PhoneNetworkAccess
}

internal fun phoneNetworkAccess(device: ConnectedDevice): PhoneNetworkAccess = when {
    !device.available -> PhoneNetworkAccess.Disconnected
    device.wireless -> PhoneNetworkAccess.WifiOnly
    else -> PhoneNetworkAccess.Usb(device.serial)
}

/** The network inspector of the selected emulator, when one is running, or of the selected device on USB. */
internal fun networkInspectorOf(state: AppState): NetworkInspectorState? {
    if (state.selected.size > 1) return null
    val serial = state.connected.selected?.let { (phoneNetworkAccess(it) as? PhoneNetworkAccess.Usb)?.serial ?: return null }
        ?: state.previewDevice?.let { state.running[it.name] } ?: return null
    return state.networkInspector(serial)
}

@Composable
internal fun NetworkPanel(state: AppState, network: NetworkInspectorState?) {
    val connected = state.connected.selected
    val message = when {
        connected != null -> when (phoneNetworkAccess(connected)) {
            is PhoneNetworkAccess.Usb -> null
            PhoneNetworkAccess.WifiOnly -> "Connect with USB to inspect traffic."
            PhoneNetworkAccess.Disconnected -> "Connect this device to see its network traffic."
        }
        state.selected.size > 1 -> "Select one emulator to see its network traffic."
        state.previewDevice == null -> "Select an emulator to see its network traffic."
        network == null -> "Start this emulator to see its network traffic."
        else -> null
    }
    if (message != null || network == null) {
        Box(Modifier.fillMaxSize().padding(16.dp).testTag("NetworkPanel"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Globe, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
                DeviceStateMessage(message.orEmpty())
            }
        }
        return
    }
    // Records while the tab is open: routes the device through Robomori on the way in, and back on the way out.
    DisposableEffect(network) {
        network.start()
        onDispose { network.stop() }
    }
    // Its own host, so a dialog about recording covers only this panel, not the device beside it.
    DialogHost(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().testTag("NetworkPanel")) {
            ExchangeList(network, state.networkRules, { network.avd?.let(state::createGoogleApisCopy) }, Modifier.weight(1f))
        }
        // Requests kept coming in while recording was off would otherwise look like recording carries on.
        AppDialog(network.stoppedRecording, onClose = network::dismissStoppedRecording) { failed ->
            RecordingStoppedDialog(failed, onRetry = network::start, onClose = network::dismissStoppedRecording)
        }
    }
    NetworkRulesDialogs(state.networkRules, network.exchanges)
}

/** Says only some apps' traffic can be read here, with a way to read every app's where there is one. */
@Composable
private fun CoverageLine(coverage: NetworkCoverage.DebugAppsOnly, onCreateGoogleApisCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("NetworkCoverage"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reading your debug apps. Other apps show which servers they reach but not what they send.", Modifier.weight(1f), color = RobomoriTheme.colors.textSecondary, style = RobomoriTheme.typography.small)
        if (coverage.canCreateGoogleApisCopy)
            Button(onClick = onCreateGoogleApisCopy, size = ButtonSize.Small, tag = "CreateGoogleApisCopy") { Label("Create a Google APIs copy") }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ExchangeList(network: NetworkInspectorState, rules: NetworkRulesState, onCreateGoogleApisCopy: () -> Unit, modifier: Modifier) {
    // The controls sit on the panel's chrome, the requests on white below them, as in the other tools.
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val apps = network.installedApps
            Select(network.appFilter, listOf(null) + network.appsSeen, network::filter,
                label = { it?.let { apps[it]?.name } ?: it ?: "All Apps" }, description = { it },
                icon = { packageName, size ->
                    apps[packageName]?.let { AppIcon(it, size) } ?: Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                        Icon(Icons.LayoutGrid, tint = RobomoriTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }, tag = "NetworkAppFilter", modifier = Modifier.weight(1f))
            NetworkRulesButton(rules)
            Button(onClick = { chooseHarFile(network.exportFileName())?.let(network::exportHar) },
                enabled = network.visibleExchanges.isNotEmpty() && network.export != NetworkExport.Exporting, tag = "ExportNetworkHar") {
                when (network.export) {
                    null, NetworkExport.Exporting -> Label("Export HAR")
                    is NetworkExport.Saved -> Label("Exported")
                    is NetworkExport.Failed -> Label("Could Not Export", color = RobomoriTheme.colors.danger)
                }
            }
            Button(onClick = network::clear, enabled = network.exchanges.isNotEmpty(), tag = "ClearNetworkRequests") { Label("Clear") }
        }
        FilterBar(network)
        // Nothing to say when every app's traffic is read.
        (network.coverage as? NetworkCoverage.DebugAppsOnly)?.let { CoverageLine(it, onCreateGoogleApisCopy) }
    }
    Rule()
    // The table is always there, empty until requests come in, so nothing moves as recording starts.
    val requests = network.visibleExchanges
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // New requests come in at the end, like Chrome's, and stay in view while the list is scrolled to its end.
    var atEnd by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        // Only scrolling decides it, as a new request arriving below would otherwise count as leaving the end.
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }
            .collect { (scrolling, more) -> if (scrolling) atEnd = !more }
    }
    LaunchedEffect(requests.size) { if (atEnd && network.sort == null && requests.isNotEmpty()) list.scrollToItem(requests.lastIndex) }
    // Like Chrome's, a selected request's details open on the right, over the list.
    val selected = network.selected
    // The list's width beside the details, which dragging the line between them changes.
    // Null until dragged: the details then open beside the list at [DetailsShare] of the width.
    var listWidth by remember { mutableStateOf<Dp?>(null) }
    // The request the details show, kept while they animate away after closing.
    var shown by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) { if (selected != null) shown = selected }
    // Esc closes the details. They take the keyboard focus as they open, so it reaches here from them.
    val detailsFocus = remember { FocusRequester() }
    LaunchedEffect(selected?.id) { if (selected != null) runCatching { detailsFocus.requestFocus() } }
    // Clicking in the table gives it the keys: typing a request's first letters selects it, as in Finder.
    val tableFocus = remember { FocusRequester() }
    val typeAhead = remember(network) { TableTypeAhead() }
    Box(modifier.fillMaxWidth().background(RobomoriTheme.colors.cardBackground).focusRequester(tableFocus).focusable()
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { if (selected == null) tableFocus.requestFocus() }
        .onPreviewKeyEvent { event ->
            if (selected != null && event.type == KeyEventType.KeyDown && event.key == Key.Escape) return@onPreviewKeyEvent run { network.select(null); true }
            // ↑ and ↓ step through the requests, as in Chrome, keeping the one chosen in view.
            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionDown || event.key == Key.DirectionUp) && requests.isNotEmpty()) {
                val at = requests.indexOfFirst { it.id == selected?.id }
                val next = if (at < 0) 0 else (at + if (event.key == Key.DirectionDown) 1 else -1).coerceIn(0, requests.lastIndex)
                network.select(requests[next])
                val shown = list.layoutInfo.visibleItemsInfo
                if (shown.none { it.index == next && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }) {
                    scope.launch { list.scrollToItem(if (next > at) (next - shown.size + 2).coerceAtLeast(0) else next) }
                }
                return@onPreviewKeyEvent true
            }
            val char = typedChar(event) ?: return@onPreviewKeyEvent false
            typeAhead.type(char, requests.map { it.name })?.let { index ->
                network.select(requests[index])
                scope.launch { list.scrollToItem(index) }
            }
            true
        }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxListWidth = (maxWidth - MinDetailsWidth).coerceAtLeast(MinListWidth)
            val shownListWidth = (listWidth ?: maxWidth * (1 - DetailsShare)).coerceIn(MinListWidth, maxListWidth)
            val detailsWidth = maxWidth - shownListWidth
            // The list keeps its layout; the details slide in over its right side, so nothing under them moves. Its
            // columns never get narrower than their least; a window too narrow for them scrolls the table sideways.
            val available = maxWidth
            val columns = network.columnLayout(available)
            val across = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().horizontalScroll(across)) {
                    Column(Modifier.width(columns.width).fillMaxHeight()) {
                        ExchangeHeader(network, columns)
                        CardDivider()
                        LazyColumn(Modifier.fillMaxSize().clipToBounds(), state = list) {
                            itemsIndexed(requests, key = { _, exchange -> exchange.id }) { index, exchange ->
                                ExchangeRow(network, rules, exchange, columns, selected = exchange.id == selected?.id,
                                    modifier = Modifier.background(TableDefaults.rowColor(index)))
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.TopEnd).fillMaxHeight()
                    .padding(top = TableDefaults.HeaderHeight + 1.dp))
                if (columns.width > available) HorizontalScrollbar(rememberScrollbarAdapter(across),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth())
                if (requests.isEmpty()) when (val routing = network.routing) {
                    // Until the device is ready and recording, such as while it's still starting up.
                    NetworkRouting.Starting -> Spinner(Modifier.align(Alignment.Center).testTag("NetworkStarting"))
                    is NetworkRouting.Failed -> RecordingFailed(routing, network::start, Modifier.align(Alignment.Center))
                    // The filters hide every request, or none has come in yet.
                    else -> DeviceStateMessage(when {
                        network.exchanges.isNotEmpty() -> "No requests match the filters."
                        routing is NetworkRouting.On -> "Use an app on the device, and its requests show up here."
                        else -> ""
                    }, Modifier.align(Alignment.Center).padding(24.dp).testTag("NetworkEmpty"))
                }
            }
            SlidingPane(selected != null, Modifier.align(Alignment.CenterEnd)) {
                Row(Modifier.width(detailsWidth).fillMaxHeight().background(RobomoriTheme.colors.cardBackground)
                    .focusRequester(detailsFocus).focusable()) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(RobomoriTheme.colors.shelfBorder))
                    (selected ?: shown)?.let { ExchangeDetails(network, rules, it, Modifier.weight(1f)) }
                }
            }
            // Over the line between them, and wider than it, so it's easy to grab.
            if (selected != null) ResizeHandle(shownListWidth, { listWidth = it }, MinListWidth, maxListWidth,
                Modifier.offset(x = shownListWidth - 4.dp).width(9.dp).fillMaxHeight(), tag = "NetworkDetailsResize")
        }
    }
}

/**
 * Fades [content] in while it moves the last bit of the way from the right, easing to a stop, and out the same way,
 * quicker. A short move reads as smoother than sliding the whole width of a wide pane.
 */
@Composable
private fun SlidingPane(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val offset = with(LocalDensity.current) { PaneSlide.roundToPx() }
    AnimatedVisibility(visible, modifier,
        enter = fadeIn(tween(240, easing = EaseOutCubic)) + slideInHorizontally(tween(240, easing = EaseOutCubic)) { offset },
        exit = fadeOut(tween(160, easing = EaseInCubic)) + slideOutHorizontally(tween(160, easing = EaseInCubic)) { offset }) {
        // Takes the pointer wherever it is over the pane, so the list underneath doesn't highlight rows the pointer
        // only seems to be over; what the pane holds handles it first.
        Box(Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
            content()
        }
    }
}

private val PaneSlide = 48.dp
private val MinListWidth = 160.dp
private val MinDetailsWidth = 320.dp
/** The share of the page the details open at, beside the list. */
private const val DetailsShare = 0.6f


/** Why recording stopped while requests are listed, with a way to start it again. */
@Composable
private fun RecordingStoppedDialog(failed: NetworkRouting.Failed, onRetry: () -> Unit, onClose: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    AppDialogPanel("Recording Stopped", "NetworkRecordingStopped", Modifier.widthIn(max = 420.dp)) {
        LaunchedEffect(Unit) { retryFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label("Recording Stopped")
            Label(failed.cause.message ?: "Robomori lost the connection to the device.", color = RobomoriTheme.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose, style = ButtonStyle.Outlined, tag = "DismissRecordingStopped") { Label("Close") }
                Button(onClick = onRetry, style = ButtonStyle.Primary, modifier = Modifier.focusRequester(retryFocus),
                    tag = "RetryNetworkInspection") { Label("Try Again") }
            }
        }
    }
}

/** Why recording couldn't start, in the empty table, with a way to try again. */
@Composable
private fun RecordingFailed(failed: NetworkRouting.Failed, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(24.dp).testTag("NetworkInspectorError"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DeviceStateMessage(failed.cause.message ?: "Could not record this device's traffic.")
        Button(onClick = onRetry, tag = "RetryNetworkInspection") { Label("Try Again") }
    }
}

/** One request per line, in Chrome's columns. */
@Composable
private fun ExchangeRow(network: NetworkInspectorState, rules: NetworkRulesState, exchange: NetworkExchange,
                        columns: ColumnLayout, selected: Boolean,
                        modifier: Modifier) {
    // Muted when Robomori passed the connection through without reading it, so there's nothing in it to look at.
    val unread = exchange.outcome is NetworkOutcome.Encrypted || exchange.outcome == NetworkOutcome.CertificateRejected
    val text = if (unread) RobomoriTheme.colors.textSecondary else RobomoriTheme.colors.text
    ContextMenuArea(items = { networkRuleMenu(rules, exchange) }) {
        SidebarListItem(selected = selected, contentPadding = RowPadding, cornerSize = 0.dp,
            modifier = modifier.fillMaxWidth().testTag("NetworkRequest:${exchange.id}").semantics { this.selected = selected }
                .clickable { network.select(exchange) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (column in network.columnOrder) when (column) {
                    NetworkColumn.Number -> TableCell(columns.fixed.getValue(column), alignEnd = true, first = true) {
                        Text(network.numbers[exchange.id]?.toString() ?: "", color = text,
                            style = RobomoriTheme.typography.small, maxLines = 1)
                    }
                    NetworkColumn.Name -> TableCell(columns.shares.getValue(column)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(exchange.name, Modifier.weight(1f), color = text, style = RobomoriTheme.typography.small,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (exchange.rule != null) NetworkRuleMark()
                        }
                    }
                    NetworkColumn.Method -> TableCell(columns.fixed.getValue(column)) {
                        StatusBadge(exchange.method, methodTone(exchange.method))
                    }
                    NetworkColumn.Status -> TableCell(columns.fixed.getValue(column)) {
                        exchange.statusText?.let { StatusBadge(it, exchange.statusTone) }
                    }
                    NetworkColumn.Type -> Cell(columns, column, exchange.type ?: "", text)
                    NetworkColumn.App -> TableCell(columns.shares.getValue(column)) {
                        val packageName = network.appOf(exchange)
                        val app = packageName?.let { network.installedApps[it] }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (app != null) AppIcon(app, 14.dp)
                            Text(app?.name ?: packageName ?: "", color = text, style = RobomoriTheme.typography.small,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    NetworkColumn.Time -> Cell(columns, column, exchange.time, text)
                    NetworkColumn.Size -> Cell(columns, column, exchange.responseSize?.let(::formatBytes) ?: "", text)
                    // How long an unread connection stayed open isn't how long a request took.
                    NetworkColumn.Duration -> {
                        val duration = if (unread) null else exchange.duration
                        // Still waiting reads as no value yet, not as a duration.
                        val pending = !unread && duration == null && exchange.inProgress
                        Cell(columns, column, duration ?: if (pending) "Pending" else "",
                            if (pending) RobomoriTheme.colors.textSecondary else text)
                    }
                }
            }
        }
    }
}

/** Making a rule from a request, or editing the one that changed it. */
private fun networkRuleMenu(rules: NetworkRulesState, exchange: NetworkExchange): List<ContextMenuItem> = listOfNotNull(
    ContextMenuItem("Add Rule…") { rules.ruleFrom(exchange) }.takeIf { exchange.method != "CONNECT" },
    exchange.rule?.let { rules.rule(it.id) }?.let { rule -> ContextMenuItem("Edit Rule…") { rules.edit(rule) } },
)

/** A picture from a response: [bitmap] drawn at [width] by [height] pixels of the page, as a browser shows it at 100%. */
internal class ResponseImage(val bitmap: ImageBitmap, val width: Float, val height: Float)

/** The picture an image response holds, decoded off the main thread, or null for other responses. */
@Composable
private fun rememberResponseImage(exchange: NetworkExchange): ResponseImage? {
    val image by produceState<ResponseImage?>(null, exchange.id, exchange.outcome) {
        val response = (exchange.outcome as? NetworkOutcome.Answered)?.response
        val body = response?.body
        // Only a whole image decodes; cut-off bodies don't.
        value = if (body != null && !body.truncated && body.size > 0 && (exchange.type in thumbnailTypes || exchange.type == "svg"))
            withContext(Dispatchers.Default) {
                runCatching {
                    // SVGs are text, so sites usually send them compressed.
                    val bytes = body.decoded(response.headers) ?: return@runCatching null
                    if (exchange.type == "svg") renderSvg(bytes)
                    else SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap().let { ResponseImage(it, it.width.toFloat(), it.height.toFloat()) }
                }.getOrNull()
            }
        else null
    }
    return image
}

/**
 * Draws an SVG at the size it asks for, or its view box's when it gives none, as browsers do, at twice that so it stays
 * sharp on Retina screens.
 */
internal fun renderSvg(bytes: ByteArray): ResponseImage? {
    val svg = SVGDOM(Data.makeFromBytes(bytes))
    val root = svg.root ?: return null
    val intrinsic = root.getIntrinsicSize(SVGLengthContext(Point(0f, 0f)))
    val viewBox = root.viewBox
    val (width, height) = when {
        intrinsic.x > 0 && intrinsic.y > 0 -> intrinsic.x to intrinsic.y
        viewBox != null && viewBox.width > 0 && viewBox.height > 0 -> viewBox.width to viewBox.height
        else -> return null
    }
    svg.setContainerSize(width, height)
    val surface = Surface.makeRasterN32Premul((width * SvgScale).roundToInt().coerceAtLeast(1), (height * SvgScale).roundToInt().coerceAtLeast(1))
    surface.canvas.scale(SvgScale, SvgScale)
    svg.render(surface.canvas)
    return ResponseImage(surface.makeImageSnapshot().toComposeImageBitmap(), width, height)
}

private const val SvgScale = 2f

/** The image types a thumbnail is drawn for. */
private val thumbnailTypes = setOf("png", "jpeg", "jpg", "gif", "webp", "bmp", "icon", "vnd.microsoft.icon")

@Composable
private fun RowScope.Cell(columns: ColumnLayout, column: NetworkColumn, text: String,
                          color: Color) {
    // Times, sizes and durations line up on their right, as Finder lines up sizes, so they read as numbers.
    val alignEnd = column == NetworkColumn.Time || column == NetworkColumn.Size || column == NetworkColumn.Duration
    val content: @Composable BoxScope.() -> Unit = {
        Text(text, color = color, style = RobomoriTheme.typography.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val fixed = columns.fixed[column]
    if (fixed != null) TableCell(fixed, alignEnd = alignEnd, content = content)
    else TableCell(columns.shares.getValue(column), alignEnd = alignEnd, content = content)
}

/**
 * How the table's columns are laid out: [fixed] ones keep their width, and the others' [shares] are their widths in dp,
 * so the table, [width] wide, gives each exactly that.
 */
internal class ColumnLayout(val fixed: Map<NetworkColumn, Dp>, val shares: Map<NetworkColumn, Float>, val width: Dp)

/**
 * The columns laid out in [available] room: each one that shares the room takes its part of it, but never less than its
 * [minimumWidth]. When that needs more than [available], the table is wider and scrolls.
 */
@Composable
private fun NetworkInspectorState.columnLayout(available: Dp): ColumnLayout {
    val fixed = fittedColumnWidths()
    val sharing = columnOrder.filter { it !in fixed }
    val padding = RowPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) * 2
    val room = (available - padding - fixed.values.fold(0.dp) { sum, width -> sum + width }).coerceAtLeast(0.dp)
    val total = sharing.sumOf { columnShares.getValue(it).toDouble() }.toFloat()
    val widths = sharing.associateWith { maxOf(it.minimumWidth, room * (columnShares.getValue(it) / total)) }
    return ColumnLayout(fixed, widths.mapValues { it.value.value },
        maxOf(available, padding + fixed.values.fold(0.dp) { sum, width -> sum + width } + widths.values.fold(0.dp) { sum, width -> sum + width }))
}

/** The least a column that shares the table's room gets: enough for a name or an app to read, and a few characters for the rest. */
private val NetworkColumn.minimumWidth: Dp get() = when (this) {
    NetworkColumn.Name -> 140.dp
    NetworkColumn.App -> 110.dp
    else -> 64.dp
}

/**
 * The widths of the columns that fit their content rather than share the table: just enough for their widest value
 * and their title, or as dragged. The number column fits the highest number listed, from three digits up, so it stays
 * narrow and still fits 2000 requests.
 */
@Composable
private fun NetworkInspectorState.fittedColumnWidths(): Map<NetworkColumn, Dp> {
    val digits = maxOf(3, exchanges.size.toString().length)
    val typography = RobomoriTheme.typography
    val badge = badgeTextStyle
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fitted = remember(digits, typography, badge, density) {
        fun width(text: String, style: TextStyle) = with(density) { measurer.measure(text, style).size.width.toDp() }
        val inset = TableDefaults.CellInset
        // A title's inset, its text, and room for the sort arrow after it.
        fun fit(title: String, content: Dp) = maxOf(width(title, typography.small) + inset + 16.dp, content)
        mapOf(
            NetworkColumn.Number to width("9".repeat(digits), typography.small) + inset,
            // Badges: the longest text each holds, with the badge's padding.
            NetworkColumn.Method to fit("Method", width("CONNECT", badge) + BadgePadding * 2 + inset * 2),
            NetworkColumn.Status to fit("Status", width("Pending", badge) + BadgePadding * 2 + inset * 2),
            NetworkColumn.Time to fit("Time", width(if (uses24HourClock) "88:88:88.888" else "88:88:88.888 PM", typography.small) + inset * 2),
            NetworkColumn.Size to fit("Size", width("1023.9 KB", typography.small) + inset * 2),
        )
    }
    return fitted + fixedColumnWidths
}

private val RowPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)


private val NetworkColumn.title get() = when (this) {
    NetworkColumn.Number -> "#"
    NetworkColumn.Name -> "Name"
    NetworkColumn.Method -> "Method"
    NetworkColumn.Status -> "Status"
    NetworkColumn.Type -> "Type"
    NetworkColumn.App -> "App"
    NetworkColumn.Size -> "Size"
    NetworkColumn.Time -> "Time"
    NetworkColumn.Duration -> "Duration"
}

/** Like Chrome's Network filter bar: text to look for, a kind of response, and only the requests that went wrong. */
@Composable
private fun FilterBar(network: NetworkInspectorState) {
    val query = rememberTextFieldState(network.filter.query)
    LaunchedEffect(network, query) { snapshotFlow { query.text.toString() }.collect(network::search) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(query, tag = "NetworkFilterQuery", placeholder = "Filter", modifier = Modifier.weight(1f).widthIn(min = 120.dp))
        Row(Modifier.weight(2f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (type in NetworkTypeFilter.entries) {
                ToggleButton(network.filter.type == type, { network.filterType(type) }, size = ButtonSize.Small,
                    tag = "NetworkFilterType-${type.name}") { Label(type.label) }
            }
            Spacer(Modifier.width(6.dp))
            ToggleButton(network.filter.errorsOnly, network::showErrorsOnly, size = ButtonSize.Small, tag = "NetworkFilterErrors") {
                Label("Errors")
            }
        }
    }
}

private val NetworkTypeFilter.label: String get() = when (this) {
    NetworkTypeFilter.All -> "All"
    NetworkTypeFilter.Api -> "API"
    NetworkTypeFilter.Images -> "Images"
    NetworkTypeFilter.WebSocket -> "WebSocket"
    NetworkTypeFilter.Web -> "Web"
    NetworkTypeFilter.Other -> "Other"
}

/** The column titles: clicking one sorts by it, and dragging the line between two resizes just those two. */
@Composable
private fun ExchangeHeader(network: NetworkInspectorState, columns: ColumnLayout) {
    TableHeader(network.columnOrder.map { TableColumn(it, it.title) }, columns.shares, network::resizeColumns,
        network.sort?.column, network.sort?.ascending ?: true, network::sortBy, endPadding = 12.dp,
        minColumnWidth = MinNetworkColumnWidth, tagPrefix = "Network", onReorder = network::reorderColumns,
        fixedWidths = columns.fixed,
        onResizeFixed = network::resizeFixedColumn)
}

/** The parts of a request's details, as Chrome's tabs for a request. */
private enum class DetailsTab(val title: String) {
    Headers("Headers"), Payload("Payload"), Preview("Preview"), Response("Response"), Messages("Messages")
}

/** The tabs a request has: a WebSocket's messages in place of a body, as Chrome shows them. */
private fun detailsTabs(exchange: NetworkExchange) =
    if (exchange.webSocket != null) listOf(DetailsTab.Headers, DetailsTab.Messages)
    else listOf(DetailsTab.Headers, DetailsTab.Payload, DetailsTab.Preview, DetailsTab.Response)

@Composable
private fun ExchangeDetails(network: NetworkInspectorState, rules: NetworkRulesState, exchange: NetworkExchange, modifier: Modifier) {
    val response = (exchange.outcome as? NetworkOutcome.Answered)?.response
    // Stays on the tab chosen last when another request is selected, like Chrome.
    var chosen by rememberSaveable { mutableStateOf(DetailsTab.Headers) }
    val tabs = detailsTabs(exchange)
    // A tab this request doesn't have, such as Messages for a request that isn't a WebSocket, shows its headers.
    val tab = chosen.takeIf { it in tabs } ?: DetailsTab.Headers
    Column(modifier.fillMaxHeight().testTag("NetworkRequestDetails")) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Button(onClick = { network.select(null) }, style = ButtonStyle.Ghost, size = ButtonSize.Icon,
                contentDescription = "Close details", tag = "CloseNetworkRequest") { Icon(Icons.XMark) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (entry in tabs) {
                    ToggleButton(value = tab == entry, onValueChange = { chosen = entry }, size = ButtonSize.Small,
                        tag = "NetworkDetailsTab-${entry.name}") { Label(entry.title) }
                }
            }
            val rule = exchange.rule?.let { rules.rule(it.id) }
            if (rule != null) Button(onClick = { rules.edit(rule) }, size = ButtonSize.Small, tag = "EditNetworkRule") { Label("Edit Rule…") }
            else if (exchange.method != "CONNECT")
                Button(onClick = { rules.ruleFrom(exchange) }, size = ButtonSize.Small, tag = "AddNetworkRule") { Label("Add Rule…") }
            CopyButton("Copy as cURL", "CopyAsCurl") { exchange.toCurl() }
        }
        CardDivider()
        val session = exchange.webSocket
        if (tab == DetailsTab.Messages && session != null) return@Column WebSocketMessages(session, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                DetailsTab.Headers -> {
                    when (val outcome = exchange.outcome) {
                        NetworkOutcome.CertificateRejected -> Callout("The app only accepts this server's own certificate, so Robomori can't read its traffic. If it's your app, turn off certificate pinning in its debug build, run it again, then click Clear.",
                            StatusTone.Warning)
                        is NetworkOutcome.Encrypted -> Callout("The app doesn't accept Robomori's certificate, so Robomori passes this connection through without reading it. If it's your app, run its debug build, or turn off certificate pinning in it, then click Clear.",
                            StatusTone.Warning)
                        is NetworkOutcome.Failed -> Callout(outcome.message, StatusTone.Danger)
                        else -> {}
                    }
                    // As HTTP Toolkit's transform card does: this isn't what the server sent.
                    exchange.rule?.let { Callout("A rule changed this response: ${it.summary}", StatusTone.Info) }
                    FieldsSection("General", listOfNotNull(
                        "Request URL" to exchange.url,
                        "Request Method" to exchange.method,
                        (response?.let { "${it.status} ${it.reason}".trim() } ?: exchange.statusText)?.let { "Status Code" to it },
                        network.appOf(exchange)?.let { "App" to (network.installedApps[it]?.name ?: it) },
                        "Time" to exchange.time,
                        exchange.duration?.takeUnless { exchange.outcome is NetworkOutcome.Encrypted }?.let { "Duration" to it },
                    ), "NetworkGeneral", leading = network.appOf(exchange)?.let { network.installedApps[it] }
                        ?.let { app -> mapOf<String, @Composable () -> Unit>("App" to { AppIcon(app, 14.dp) }) }.orEmpty())
                    if (response != null) FieldsSection("Response Headers", response.headers, "NetworkResponseHeaders")
                    (exchange.outcome as? NetworkOutcome.Encrypted)?.let { encrypted ->
                        FieldsSection("Encrypted Bytes", listOf("Sent" to formatBytes(encrypted.sent), "Received" to formatBytes(encrypted.received)),
                            "NetworkEncryptedBytes")
                    }
                    FieldsSection("Request Headers", exchange.requestHeaders, "NetworkRequestHeaders")
                }
                DetailsTab.Payload -> {
                    val query = remember(exchange.url) { queryParameters(exchange.url) }
                    FieldsSection("Query String Parameters", query, "NetworkQueryParameters")
                    FilterMatch(network.filter.wordsIn(exchange, BodyMatch.RequestBody), "request body")
                    ProtobufOrBody("Request Body", exchange.requestBody, exchange.requestHeaders, "NetworkRequestBody")
                    if (query.isEmpty() && exchange.requestBody.size == 0L) SecondaryText("This request has no payload.")
                }
                DetailsTab.Preview, DetailsTab.Response -> when {
                    response == null -> SecondaryText(if (exchange.outcome == NetworkOutcome.Pending) "Waiting for the response…"
                        else "No response was read for this request.", maxLines = Int.MAX_VALUE)
                    response.body.size == 0L -> SecondaryText("The response has no body.")
                    tab == DetailsTab.Preview -> ResponsePreview(exchange, response)
                    else -> {
                        FilterMatch(network.filter.wordsIn(exchange, BodyMatch.ResponseBody), "response")
                        BodySection(null, response.body, response.headers, "NetworkResponseBody")
                    }
                }
                DetailsTab.Messages -> {}
            }
        }
    }
}

/** An image response as the picture it holds, at its own size or narrower, as Chrome previews it; others as their text. */
@Composable
private fun ResponsePreview(exchange: NetworkExchange, response: NetworkResponse) {
    val image = rememberResponseImage(exchange)
    when {
        // A pixel to a point, as a browser shows an image at 100%.
        image != null -> Image(image.bitmap, contentDescription = null, modifier = Modifier.testTag("NetworkResponsePreview")
            .widthIn(max = image.width.dp)
            .aspectRatio(image.width / image.height))
        exchange.type in thumbnailTypes || exchange.type == "svg" -> SecondaryText(if (response.body.truncated) "Only part of this image was read, so it can't be shown."
            else "This image can't be shown.", maxLines = Int.MAX_VALUE)
        else -> ProtobufOrBody(null, response.body, response.headers, "NetworkResponsePreview")
    }
}

/** Names and values in two columns, as Chrome lists headers: names on the left, values in code type, selectable. */
@Composable
private fun FieldsSection(title: String, fields: List<Pair<String, String>>, tag: String,
                          /** Something shown before a field's value, by the field's name, such as an app's icon. */
                          leading: Map<String, @Composable () -> Unit> = emptyMap()) {
    if (fields.isEmpty()) return
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = RobomoriTheme.typography.heading)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((name, value) in fields) {
                    Row(verticalAlignment = Alignment.Top) {
                        // Only values are selected and copied; the names are labels.
                        DisableSelection {
                            Text(name, Modifier.width(FieldNameWidth).padding(end = 12.dp), style = RobomoriTheme.typography.small,
                                color = RobomoriTheme.colors.textSecondary)
                        }
                        leading[name]?.let { Box(Modifier.padding(end = 6.dp)) { it() } }
                        Text(value, Modifier.weight(1f), style = RobomoriTheme.typography.codeSmall)
                    }
                }
            }
        }
    }
}

private val FieldNameWidth = 180.dp

/** A protobuf or gRPC body as its fields, decoded without a schema; any other body as [BodySection] shows it. */
@Composable
private fun ProtobufOrBody(title: String?, body: CapturedBody, headers: List<Pair<String, String>>, tag: String) {
    val fields = remember(body, headers) { protobufText(body, headers) }
    if (fields == null) return BodySection(title, body, headers, tag)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, style = RobomoriTheme.typography.heading)
        CodeBlock(fields, tag = tag)
        SecondaryText("Protobuf fields by number, read without the app's schema.", maxLines = Int.MAX_VALUE)
    }
}

@Composable
private fun BodySection(title: String?, body: CapturedBody, headers: List<Pair<String, String>>, tag: String) {
    if (body.size == 0L) return
    val text = remember(body, headers) { body.text(headers)?.let(::prettyBody) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, style = RobomoriTheme.typography.heading)
        if (text == null) {
            // Binary, as Chrome's hex viewer shows it: decompressed when it can be, else the bytes as they came.
            val bytes = remember(body, headers) { body.decoded(headers) ?: body.bytes }
            val dump = remember(bytes) { hexDump(bytes, HexDumpLimit) }
            CodeBlock(dump, tag = tag)
            if (bytes.size > HexDumpLimit) SecondaryText("Showing the first ${formatBytes(HexDumpLimit.toLong())} of ${formatBytes(bytes.size.toLong())}.")
        } else CodeBlock(text, language = remember(text, headers) { codeLanguageOf(text, headers) }, tag = tag)
        if (body.truncated) SecondaryText("Robomori kept the first ${formatBytes(body.bytes.size.toLong())} of ${formatBytes(body.size)}.")
    }
}

/** How much of a binary body the hex viewer shows, so a large download doesn't stall the page. */
private const val HexDumpLimit = 64 * 1024

/**
 * Up to [limit] of [bytes] as a hex viewer lays them out: 16 to a line, each line with its offset, the bytes in hex in
 * two groups of eight, and the same bytes as text, with a dot for each that isn't a printable ASCII character.
 */
internal fun hexDump(bytes: ByteArray, limit: Int = bytes.size): String = buildString {
    val shown = minOf(bytes.size, limit)
    for (start in 0 until shown step 16) {
        if (start > 0) append('\n')
        append("%08x".format(start)).append("  ")
        for (index in start until start + 16) {
            if (index == start + 8) append(' ')
            if (index < shown) append("%02x ".format(bytes[index].toInt() and 0xFF)) else append("   ")
        }
        append(' ')
        for (index in start until minOf(start + 16, shown)) {
            val byte = bytes[index].toInt() and 0xFF
            append(if (byte in 0x20..0x7E) byte.toChar() else '.')
        }
    }
}

/** How to color a body: by what it is, or as JSON when it is JSON whatever its Content-Type says. */
internal fun codeLanguageOf(text: String, headers: List<Pair<String, String>>): CodeLanguage {
    val mime = headers.firstOrNull { it.first.equals("Content-Type", true) }?.second?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        mime.endsWith("json") || text.trimStart().let { it.startsWith("{") || it.startsWith("[") } && looksLikeJson(text) -> CodeLanguage.Json
        "javascript" in mime || "ecmascript" in mime || mime == "text/css" -> CodeLanguage.Script
        mime == "text/html" || mime.endsWith("xml") -> CodeLanguage.Xml
        else -> CodeLanguage.Plain
    }
}

private fun looksLikeJson(text: String) = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.isSuccess

@Composable
private fun CopyButton(title: String, tag: String, text: () -> String) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    Button(onClick = {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text()), null)
        copied = true
    }, size = ButtonSize.Small, tag = tag) { Label(if (copied) "Copied" else title) }
}

@Composable
private fun SecondaryText(text: String, maxLines: Int = 1) {
    Text(text, color = RobomoriTheme.colors.textSecondary, style = RobomoriTheme.typography.small,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** The response's status code, or what became of the request without one; null when Robomori couldn't read the connection. */
private val NetworkExchange.statusText: String? get() = when (val outcome = outcome) {
    NetworkOutcome.Pending -> "Pending"
    is NetworkOutcome.Answered -> outcome.response.status.toString()
    NetworkOutcome.CertificateRejected, is NetworkOutcome.Encrypted -> null
    is NetworkOutcome.Failed -> "Failed"
}

/** GET in green and the methods that send data in blue; no method gets a warning or failure color, so none looks wrong. */
private fun methodTone(method: String): StatusTone = when (method.uppercase()) {
    "GET" -> StatusTone.Success
    "POST", "PUT", "PATCH" -> StatusTone.Info
    else -> StatusTone.Neutral
}

/** Green for success, blue for redirects and switching to a WebSocket, orange when the site turned the request down, red when it or the connection failed. */
private val NetworkExchange.statusTone: StatusTone get() = when (val outcome = outcome) {
    NetworkOutcome.Pending, NetworkOutcome.CertificateRejected, is NetworkOutcome.Encrypted -> StatusTone.Neutral
    is NetworkOutcome.Answered -> when (outcome.response.status) {
        in 200..299 -> StatusTone.Success
        in 400..499 -> StatusTone.Warning
        in 500..599 -> StatusTone.Danger
        else -> StatusTone.Info
    }
    is NetworkOutcome.Failed -> StatusTone.Danger
}

private val NetworkExchange.time: String get() = formatRequestTime(startedAt, ZoneId.systemDefault(), uses24HourClock)

private val NetworkExchange.duration: String? get() {
    val millis = durationMillis ?: return null
    return if (millis < 1000) "$millis ms" else "%.1f s".format(millis / 1000.0)
}

private fun formatBytes(bytes: Long) = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}

private fun chooseHarFile(name: String): File? {
    val dialog = FileDialog(null as Frame?, "Export HAR", FileDialog.SAVE).apply {
        directory = File(System.getProperty("user.home"), "Desktop").takeIf(File::isDirectory)?.path
        file = name
        isVisible = true
    }
    val chosen = dialog.file ?: return null
    return File(dialog.directory, chosen)
}

/** Says which of the filter's [words] were found in this body, so a request listed for its body shows why. */
@Composable
private fun FilterMatch(words: List<String>, part: String) {
    if (words.isEmpty()) return
    SecondaryText("Filter matches ${words.joinToString(", ") { "“$it”" }} in the $part.", maxLines = Int.MAX_VALUE)
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private fun clockTime(instant: Instant) = clockFormat.format(instant.atZone(ZoneId.systemDefault()))

/**
 * A WebSocket's messages, as Chrome's Messages tab lists them: an arrow up for each one the app sent and down for each
 * one it received, with its data, length and time. Selecting one shows it whole below, JSON laid out.
 */
@Composable
private fun WebSocketMessages(session: WebSocketSession, modifier: Modifier) {
    var selected by remember { mutableStateOf<WebSocketMessage?>(null) }
    var shares by remember { mutableStateOf(mapOf(MessageColumn.Data to .66f, MessageColumn.Length to .14f, MessageColumn.Time to .2f)) }
    if (session.messages.isEmpty()) return Box(modifier.padding(12.dp)) {
        SecondaryText(if (session.closedAt == null) "No messages yet." else "No messages were sent.")
    }
    Column(modifier.testTag("NetworkMessages")) {
        TableHeader(MessageColumn.entries.map { TableColumn(it, it.name) }, shares, { shares = it }, sortedBy = null, ascending = true,
            onSort = {}, firstTitleInset = MessageIconSize + 6.dp, tagPrefix = "NetworkMessages")
        CardDivider()
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                itemsIndexed(session.messages) { index, message ->
                    MessageRow(message, shares, message == selected, { selected = message },
                        Modifier.background(TableDefaults.rowColor(index)).testTag("NetworkMessage:$index"))
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        val shown = selected?.takeIf { it in session.messages }
        if (shown != null) {
            Rule()
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MessageContent(shown)
            }
        }
    }
}

private enum class MessageColumn { Data, Length, Time }
private val MessageIconSize = 12.dp

@Composable
private fun MessageRow(message: WebSocketMessage, shares: Map<MessageColumn, Float>, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val sent = message.direction == WebSocketDirection.Sent
    SidebarListItem(selected = selected, contentPadding = RowPadding, cornerSize = 0.dp,
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected }.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(shares.getValue(MessageColumn.Data)), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (sent) Icons.ChevronUp else Icons.ChevronDown, tint = if (sent) RobomoriTheme.colors.success else RobomoriTheme.colors.danger,
                    modifier = Modifier.size(MessageIconSize).semantics { contentDescription = if (sent) "Sent" else "Received" })
                Spacer(Modifier.width(6.dp))
                Text(message.summary.replace('\n', ' '), Modifier.weight(1f), style = RobomoriTheme.typography.codeSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (message.opcode == WebSocketOpcode.Text) RobomoriTheme.colors.text else RobomoriTheme.colors.textSecondary)
            }
            TableCell(shares.getValue(MessageColumn.Length)) {
                Text(message.data.size.toString(), style = RobomoriTheme.typography.small, color = RobomoriTheme.colors.textSecondary, maxLines = 1)
            }
            TableCell(shares.getValue(MessageColumn.Time)) {
                Text(clockTime(message.at), style = RobomoriTheme.typography.small, color = RobomoriTheme.colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** What a message's row shows: its text, or what kind of message it is. */
private val WebSocketMessage.summary: String get() = when (opcode) {
    WebSocketOpcode.Text -> text.orEmpty()
    WebSocketOpcode.Binary -> "Binary Message"
    WebSocketOpcode.Close -> "Connection Close Frame" +
        (closeCode?.let { code -> " ($code${closeReason.takeIf(String::isNotEmpty)?.let { ", $it" }.orEmpty()})" }.orEmpty())
}

@Composable
private fun MessageContent(message: WebSocketMessage) {
    val text = message.text
    when {
        text != null -> {
            val pretty = remember(message) { prettyBody(text) }
            CodeBlock(pretty, language = remember(pretty) { codeLanguageOf(pretty, emptyList()) }, tag = "NetworkMessageContent")
        }
        message.opcode == WebSocketOpcode.Close -> SecondaryText(message.summary, maxLines = Int.MAX_VALUE)
        else -> {
            SecondaryText("${formatBytes(message.data.size)} of binary data, in base64:")
            CodeBlock(remember(message) { java.util.Base64.getMimeEncoder().encodeToString(message.data.bytes) }, tag = "NetworkMessageContent")
        }
    }
    if (message.data.truncated) SecondaryText("Showing the first ${formatBytes(message.data.bytes.size.toLong())} of ${formatBytes(message.data.size)}.")
}
