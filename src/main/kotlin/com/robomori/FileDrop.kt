package com.robomori

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.robomori.components.Icon
import com.robomori.iconography.ArrowDownTray
import com.robomori.iconography.Icons
import com.robomori.theme.RobomoriTheme
import com.composeunstyled.Text
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * Takes files dropped from the computer, saying [title] over [content] while they're dragged over it. Files that
 * [accepts] turns down aren't taken. [working], when given, keeps the same panel up after the drop, such as to show
 * progress.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun FileDropArea(
    title: String,
    tag: String,
    enabled: Boolean = true,
    accepts: (List<File>) -> Boolean = { true },
    onDrop: (List<File>) -> Unit,
    modifier: Modifier = Modifier,
    working: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var hovering by remember { mutableStateOf(false) }
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentAccepts by rememberUpdatedState(accepts)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val dropped = event.droppedFiles()?.takeIf(currentAccepts) ?: return false
                currentOnDrop(dropped)
                return true
            }
        }
    }
    Box(modifier.testTag(tag).dragAndDropTarget(
        // macOS lets the files be read while they're still being dragged; where it doesn't, they're checked on drop.
        shouldStartDragAndDrop = { event -> enabled && event.droppedFiles().let { it == null || currentAccepts(it) } &&
            event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    )) {
        content()
        if (hovering || working != null) {
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.matchParentSize().clip(shape).background(RobomoriTheme.colors.cardBackground.copy(alpha = .94f))
                .border(2.dp, RobomoriTheme.colors.textSecondary, shape).padding(24.dp).testTag("$tag-Panel"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                if (hovering || working == null) {
                    Icon(Icons.ArrowDownTray, tint = RobomoriTheme.colors.text, modifier = Modifier.size(32.dp))
                    Text(title, style = RobomoriTheme.typography.heading)
                } else working()
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.droppedFiles(): List<File>? =
    runCatching { (awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>() }.getOrNull()
