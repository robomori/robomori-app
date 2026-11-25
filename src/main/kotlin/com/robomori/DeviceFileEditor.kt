package com.robomori

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** What the editor has of its file: still reading it, its text to edit, or why it can't be edited. */
internal sealed interface TextEditorContent {
    data object Loading : TextEditorContent
    /** The file's text is in [TextFileEditor.text]. */
    data object Ready : TextEditorContent
    data class TooLarge(val size: Long) : TextEditorContent
    data object NotText : TextEditorContent
    data class Failed(val cause: Throwable) : TextEditorContent
}

/** How the last save went. */
internal sealed interface TextSaveState {
    data object Idle : TextSaveState
    data object Saving : TextSaveState
    data object Saved : TextSaveState
    data class Failed(val cause: Throwable) : TextSaveState
    /** Not saved, as the file changed on the device since it was opened: waiting to reload it or overwrite it. */
    data object Conflict : TextSaveState
}

/**
 * A device text file open for editing on the Files page. It remembers the file's change stamp from when it was read,
 * so a save never silently replaces what an app or someone else wrote to it since.
 */
internal class TextFileEditor(
    val file: DeviceFile,
    private val scope: CoroutineScope,
    private val read: () -> TextFileRead,
    private val write: (text: String, opened: Set<String>, overwrite: Boolean) -> TextFileWrite,
    private val onClose: () -> Unit,
    private val onSaved: () -> Unit = {},
) {
    var content by mutableStateOf<TextEditorContent>(TextEditorContent.Loading); private set
    /** The text being edited. */
    val text = TextFieldState()
    var save by mutableStateOf<TextSaveState>(TextSaveState.Idle); private set
    /** Whether closing waits for the person to confirm losing their changes. */
    var confirmingDiscard by mutableStateOf(false); private set

    // The text as it is on the device, and the file's stamp then.
    private var original by mutableStateOf("")
    private var stamp: Set<String> = emptySet()
    private var job: Job? = null

    /** Whether the text differs from the device's file as last read or saved. */
    val changed get() = content == TextEditorContent.Ready && text.text.toString() != original

    /** Reads the file from the device, replacing any edits. */
    fun load() {
        job?.cancel()
        content = TextEditorContent.Loading
        save = TextSaveState.Idle
        job = scope.launch {
            content = try {
                when (val result = runInterruptible(Dispatchers.IO) { read() }) {
                    is TextFileRead.Text -> {
                        original = result.text
                        stamp = result.stamp
                        text.edit { replace(0, length, result.text); placeCursorBeforeCharAt(0) }
                        TextEditorContent.Ready
                    }
                    is TextFileRead.TooLarge -> TextEditorContent.TooLarge(result.size)
                    TextFileRead.NotText -> TextEditorContent.NotText
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TextEditorContent.Failed(failure)
            }
        }
    }

    /**
     * Writes the text to the device. When the file changed there since it was opened, it isn't saved and [save] becomes
     * [TextSaveState.Conflict], unless [overwrite].
     */
    fun save(overwrite: Boolean = false) {
        if (content != TextEditorContent.Ready || save == TextSaveState.Saving) return
        if (!overwrite && !changed) return
        val saving = text.text.toString()
        save = TextSaveState.Saving
        job = scope.launch {
            save = try {
                when (val result = runInterruptible(Dispatchers.IO) { write(saving, stamp, overwrite) }) {
                    is TextFileWrite.Written -> {
                        original = saving
                        stamp = result.stamp
                        onSaved()
                        TextSaveState.Saved
                    }
                    TextFileWrite.Changed -> TextSaveState.Conflict
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TextSaveState.Failed(failure)
            }
        }
    }

    /** Replaces the device's file with this text, although it changed there since it was opened. */
    fun overwrite() = save(overwrite = true)

    /** Leaves the conflict to keep editing, without saving. */
    fun dismissConflict() { if (save == TextSaveState.Conflict) save = TextSaveState.Idle }

    /** Closes the editor, first asking to confirm losing the changes when there are any. */
    fun cancel() {
        if (save == TextSaveState.Saving) return
        if (changed) confirmingDiscard = true else close()
    }

    fun discard() {
        confirmingDiscard = false
        close()
    }

    fun keepEditing() { confirmingDiscard = false }

    private fun close() {
        job?.cancel()
        onClose()
    }
}
