package com.robomori.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.composeunstyled.LocalContentColor
import com.robomori.iconography.ChevronRight
import com.robomori.iconography.Icons
import com.robomori.theme.RobomoriTheme

/** What a [PopupMenu] lists, in order: options to click, lines between groups, and text that does nothing. */
class MenuScope internal constructor() {
    internal sealed interface Entry {
        class Item(val enabled: Boolean, val onClick: () -> Unit, val content: @Composable () -> Unit) : Entry
        data object Separator : Entry
        class Passive(val content: @Composable () -> Unit) : Entry
        class Submenu(val entries: List<Entry>, val content: @Composable () -> Unit) : Entry
    }

    internal val entries = mutableListOf<Entry>()

    /** An option; one that's chosen shows a check in its [content]. */
    fun item(enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
        entries += Entry.Item(enabled, onClick, content)
    }

    fun separator() { entries += Entry.Separator }

    fun passiveItem(content: @Composable () -> Unit) { entries += Entry.Passive(content) }

    /** An option that opens more options beside it, under the pointer or when clicked. */
    fun submenu(submenu: MenuScope.() -> Unit, content: @Composable () -> Unit) {
        entries += Entry.Submenu(MenuScope().apply(submenu).entries, content)
    }
}

/**
 * A menu that pops up under the element it's placed in, its [horizontalAlignment] edge lined up with that element's,
 * or where [popupPositionProvider] puts it, such as at the pointer for a context menu. Clicking outside or Esc calls
 * [onDismissRequest].
 */
@Composable
fun PopupMenu(
    onDismissRequest: () -> Boolean,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    popupPositionProvider: PopupPositionProvider? = null,
    content: MenuScope.() -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { MenuOffset.roundToPx() }
    val room = with(density) { MenuShadowRoom.roundToPx() }
    val provider = popupPositionProvider ?: remember(horizontalAlignment, gap, room) { BelowAnchor(horizontalAlignment, gap, room) }
    val entries = MenuScope().apply(content).entries
    Popup(popupPositionProvider = provider, onDismissRequest = { onDismissRequest() },
        properties = PopupProperties(focusable = true),
        onKeyEvent = { event -> (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onDismissRequest() } }) {
        MenuPanel(modifier) { MenuEntries(entries) }
    }
}

/** [entries] in a menu's panel; a submenu opens beside its option while the pointer is on it, until it moves to another. */
@Composable
private fun MenuEntries(entries: List<MenuScope.Entry>) {
    var open by remember { mutableStateOf<Int?>(null) }
    val room = with(LocalDensity.current) { MenuShadowRoom.roundToPx() }
    entries.forEachIndexed { index, entry ->
        when (entry) {
            is MenuScope.Entry.Item -> MenuItem(entry.enabled, entry.onClick, onHover = { open = null }, content = entry.content)
            MenuScope.Entry.Separator -> MenuSeparator()
            is MenuScope.Entry.Passive -> entry.content()
            is MenuScope.Entry.Submenu -> Box {
                MenuItem(true, { open = index }, highlighted = open == index, onHover = { open = index }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f, fill = false)) { entry.content() }
                        Spacer(Modifier.width(12.dp).weight(1f))
                        Icon(Icons.ChevronRight, modifier = Modifier.size(12.dp))
                    }
                }
                if (open == index) Popup(popupPositionProvider = remember(room) { BesideAnchor(room) },
                    onDismissRequest = { open = null }) {
                    MenuPanel { MenuEntries(entry.entries) }
                }
            }
        }
    }
}

/** A menu's panel, as wide as its widest option, scrolling when it's taller than [MenuMaxHeight]. */
@Composable
private fun MenuPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(MenuShadowRoom).width(IntrinsicSize.Max).heightIn(max = MenuMaxHeight).menuSurface()
        .verticalScroll(rememberScrollState()).padding(vertical = 7.dp, horizontal = 2.dp), content = content)
}

/** A menu's surface: white, outlined and shadowed, as every menu and dropdown in the app looks. */
@Composable
internal fun Modifier.menuSurface(): Modifier {
    val style = RobomoriTheme.styles.popupContainer
    val shape = RoundedCornerShape(style.cornerSize)
    return shadow(style.shadowSize, shape, ambientColor = style.shadow, spotColor = style.shadow)
        .clip(shape).background(style.background).border(style.borderWidth, style.border, shape)
}

internal val MenuMaxHeight = 560.dp

/** One option of a menu: highlighted under the pointer, dimmed when [enabled] is false. */
@Composable
private fun MenuItem(enabled: Boolean, onClick: () -> Unit, highlighted: Boolean = false, onHover: () -> Unit = {},
                     content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    val colors = RobomoriTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(4.dp))
        .background(if (enabled && (hovered || highlighted)) colors.highlight else androidx.compose.ui.graphics.Color.Transparent)
        .hoverable(interaction, enabled)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 20.dp).padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.text else colors.textDisabled, content = content)
    }
}

/** A line between groups of a menu's options. */
@Composable
internal fun MenuSeparator() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(1.dp).background(RobomoriTheme.colors.separator))
}

/** The room a menu's panel keeps around it for its shadow. */
internal val MenuShadowRoom = 12.dp

private val MenuOffset = 2.dp

/** Beside the anchor's end, its first option level with the anchor, or before it when there's no room after. */
private class BesideAnchor(private val room: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val after = anchorBounds.right - room
        val x = if (after + popupContentSize.width <= windowSize.width) after else anchorBounds.left - popupContentSize.width + room
        val y = (anchorBounds.top - room - 7).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x.coerceAtLeast(0), y)
    }
}

/**
 * Under the anchor, its visible panel lined up with the anchor's [alignment] edge, or above it when there's no room
 * below, kept inside the window. The popup is [room] bigger on each side than its panel, for the shadow.
 */
private class BelowAnchor(private val alignment: Alignment.Horizontal, private val gap: Int, private val room: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = when (alignment) {
            Alignment.End -> anchorBounds.right - popupContentSize.width + room
            Alignment.CenterHorizontally -> anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
            else -> anchorBounds.left - room
        }
        val below = anchorBounds.bottom + gap - room
        val above = anchorBounds.top - gap - popupContentSize.height + room
        val y = if (below + popupContentSize.height <= windowSize.height) below else above
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceAtLeast(0))
    }
}
