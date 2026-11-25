package com.robomori

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Desktop
import java.awt.EventQueue
import java.io.File

/** Keys pressed while the preview has the keyboard, which the file list acts on as if they were pressed on it. */
internal enum class PreviewKey { Up, Down, Close }

/** Previews files copied to this computer, beside the list they're picked in. */
internal interface FilePreviewer {
    /**
     * Shows [files], the first one in front, replacing any shown: opening the preview when [opening], otherwise only
     * while it's still open, telling [onClosed] instead when it was closed in the meantime. [onKey] gets the list's keys.
     */
    fun show(files: List<File>, opening: Boolean, onKey: (PreviewKey) -> Unit, onClosed: () -> Unit)
    fun close()
}

/** Opens each file in the app that opens its kind, where there's no previewer of the system's own. */
internal object OpenFilePreviewer : FilePreviewer {
    override fun show(files: List<File>, opening: Boolean, onKey: (PreviewKey) -> Unit, onClosed: () -> Unit) {
        if (opening) files.firstOrNull()?.let { Desktop.getDesktop().open(it) }
        onClosed()
    }

    override fun close() {}
}

/** The previewer of this computer: macOS's own Quick Look panel, or opening the file elsewhere. */
internal val SystemPreviewer: FilePreviewer by lazy {
    if (HostOs.detect() == HostOs.MAC) runCatching { MacQuickLook.also { it.prepare() } }.getOrDefault(OpenFilePreviewer)
    else OpenFilePreviewer
}

/**
 * Finder's Quick Look panel, `QLPreviewPanel`, driven through the Objective-C runtime. The app's `NSApplication` becomes
 * the panel's controller, and an object of a class made here at runtime gives it the files and hears its keys, so ↑ and
 * ↓ move through the list behind it as they do in Finder.
 *
 * Every call to AppKit goes to its main thread.
 */
internal object MacQuickLook : FilePreviewer {
    private val objc = NativeLibrary.getInstance("objc")
    private val system = NativeLibrary.getInstance("System")
    private val send = objc.getFunction("objc_msgSend")

    private fun cls(name: String): Pointer = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
    private fun sel(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun msg(target: Pointer?, selector: String, vararg args: Any?): Pointer? = send.invokePointer(arrayOf(target, sel(selector), *args))
    private fun msgLong(target: Pointer?, selector: String): Long = send.invokeLong(arrayOf(target, sel(selector)))
    private fun msgVoid(target: Pointer?, selector: String, vararg args: Any?) = send.invokeVoid(arrayOf(target, sel(selector), *args))

    private fun nsString(text: String): Pointer? {
        val bytes = Native.toByteArray(text, "UTF-8")
        val memory = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        return msg(cls("NSString"), "stringWithUTF8String:", memory)
    }

    // The files shown, as retained NSURLs; read on the main thread only.
    private var items: List<Pointer> = emptyList()
    private var onKey: (PreviewKey) -> Unit = {}
    private var onClosed: () -> Unit = {}
    private var source: Pointer? = null

    // Kept here so the garbage collector never frees code AppKit calls into.
    private interface CountImp : Callback { fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?): Long }
    private interface ItemImp : Callback { fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?, index: Long): Pointer? }
    private interface PanelImp : Callback { fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?) }
    private interface AcceptImp : Callback { fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?): Int }
    private interface EventImp : Callback { fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?, event: Pointer?): Int }
    private interface Work : Callback { fun invoke(context: Pointer?) }

    private val count = object : CountImp { override fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?) = items.size.toLong() }
    private val item = object : ItemImp {
        override fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?, index: Long) = items.getOrNull(index.toInt())
    }
    private val accepts = object : AcceptImp { override fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?) = 1 }
    private val begin = object : PanelImp {
        override fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?) {
            msgVoid(panel, "setDataSource:", source)
            msgVoid(panel, "setDelegate:", source)
        }
    }
    private val end = object : PanelImp {
        override fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?) {
            val closed = onClosed
            EventQueue.invokeLater { closed() }
        }
    }
    // The panel has the keyboard while it's in front; the list's own keys go back to the list.
    private val handleEvent = object : EventImp {
        override fun invoke(self: Pointer?, cmd: Pointer?, panel: Pointer?, event: Pointer?): Int {
            if (msgLong(event, "type") != KeyDown) return 0
            val key = when (msgLong(event, "keyCode").toInt() and 0xFFFF) {
                KeyUp -> PreviewKey.Up
                KeyDownArrow -> PreviewKey.Down
                KeySpace, KeyEscape -> PreviewKey.Close
                else -> return 0
            }
            val handler = onKey
            EventQueue.invokeLater { handler(key) }
            return 1
        }
    }

    private val tasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private val drain = object : Work { override fun invoke(context: Pointer?) { while (true) (tasks.poll() ?: break)() } }

    /** Runs [task] on AppKit's main thread. */
    private fun onMain(task: () -> Unit) {
        tasks += task
        system.getFunction("dispatch_async_f").invokeVoid(arrayOf(system.getGlobalVariableAddress("_dispatch_main_q"), null, drain))
    }

    /** Loads Quick Look, and makes the class its panel asks for files and sends keys to. Fails where it can't. */
    fun prepare() {
        NativeLibrary.getInstance("/System/Library/Frameworks/Quartz.framework/Quartz")
        val alloc = objc.getFunction("objc_allocateClassPair")
        val add = objc.getFunction("class_addMethod")
        val made = alloc.invokePointer(arrayOf(cls("NSObject"), "RobomoriQuickLookSource", 0L))
            ?: cls("RobomoriQuickLookSource")
        add.invokeInt(arrayOf(made, sel("numberOfPreviewItemsInPreviewPanel:"), count, "q@:@"))
        add.invokeInt(arrayOf(made, sel("previewPanel:previewItemAtIndex:"), item, "@@:@q"))
        add.invokeInt(arrayOf(made, sel("previewPanel:handleEvent:"), handleEvent, "c@:@@"))
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(made))
        source = msg(msg(made, "alloc"), "init")
        // The application takes control of the panel, as Finder's does; it's at the end of every window's responder chain.
        val application = msg(cls("NSApplication"), "sharedApplication")
        val applicationClass = objc.getFunction("object_getClass").invokePointer(arrayOf(application))
        add.invokeInt(arrayOf(applicationClass, sel("acceptsPreviewPanelControl:"), accepts, "c@:@"))
        add.invokeInt(arrayOf(applicationClass, sel("beginPreviewPanelControl:"), begin, "v@:@"))
        add.invokeInt(arrayOf(applicationClass, sel("endPreviewPanelControl:"), end, "v@:@"))
    }

    override fun show(files: List<File>, opening: Boolean, onKey: (PreviewKey) -> Unit, onClosed: () -> Unit) {
        val paths = files.map { it.absolutePath }
        onMain {
            val panelOpen = msgLong(cls("QLPreviewPanel"), "sharedPreviewPanelExists") and 0xFF != 0L &&
                msgLong(msg(cls("QLPreviewPanel"), "sharedPreviewPanel"), "isVisible") and 0xFF != 0L
            // Closed with its own button since it last showed, it stays closed when the selection moves on.
            if (!opening && !panelOpen) return@onMain EventQueue.invokeLater { onClosed() }
            this.onKey = onKey
            this.onClosed = onClosed
            val old = items
            items = paths.mapNotNull { path -> msg(cls("NSURL"), "fileURLWithPath:", nsString(path))?.also { msgVoid(it, "retain") } }
            old.forEach { msgVoid(it, "release") }
            val panel = msg(cls("QLPreviewPanel"), "sharedPreviewPanel")
            msgVoid(panel, "setDataSource:", source)
            msgVoid(panel, "setDelegate:", source)
            msgVoid(panel, "reloadData")
            msgVoid(panel, "setCurrentPreviewItemIndex:", 0L)
            // Already open, it only swaps what it shows, as in Finder, rather than coming forward again. It opens without
            // taking the keyboard, so Space, Esc and the arrows stay with the list, which closes it and moves the selection.
            if (panelOpen) msgVoid(panel, "refreshCurrentPreviewItem")
            else msgVoid(panel, "orderFront:", null)
        }
    }

    override fun close() = onMain {
        if (msgLong(cls("QLPreviewPanel"), "sharedPreviewPanelExists") and 0xFF != 0L)
            msgVoid(msg(cls("QLPreviewPanel"), "sharedPreviewPanel"), "orderOut:", null)
    }

    private const val KeyDown = 10L
    private const val KeyUp = 126
    private const val KeyDownArrow = 125
    private const val KeySpace = 49
    private const val KeyEscape = 53
}
