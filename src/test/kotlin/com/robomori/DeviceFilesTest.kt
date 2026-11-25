package com.robomori

import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceFilesTest {
    @TempDir lateinit var directory: File
    private val pantry = InstalledApp("com.example.pantry", "Pantry", "1.0", "1", null)

    @Test fun `lists folders first, then files by name`() {
        val output = """
            regular file|12288|1795169919|./b.db
            directory|4096|1795169900|./cache
            regular empty file|0|1795169919|./A.txt
            symbolic link|10|1795169919|./broken
            ROBOMORI_DONE
        """.trimIndent()

        val files = parseFileListing(output, "./databases")

        assertEquals(listOf("cache", "A.txt", "b.db"), files.map { it.name })
        assertEquals("/system", parseFileListing("directory|0|0|./system", "/").single().path)
        assertEquals(DeviceFile("b.db", "./databases/b.db", DeviceFileKind.File, 12288, Instant.ofEpochSecond(1795169919)), files[2])
        assertTrue(files[2].isDatabase)
    }

    @Test fun `sorts by any column either way, keeping folders first`() {
        fun file(name: String, size: Long, kind: DeviceFileKind = DeviceFileKind.File) = DeviceFile(name, "./$name", kind, size, Instant.ofEpochSecond(size))
        val files = listOf(file("b.txt", 5), file("a.db", 9), file("cache", 1, DeviceFileKind.Folder), file("C.txt", 1))

        assertEquals(listOf("cache", "a.db", "b.txt", "C.txt"), files.sortedBy(FileSort()).map { it.name })
        assertEquals(listOf("cache", "a.db", "b.txt", "C.txt"), files.sortedBy(FileSort(FileColumn.Size, ascending = false)).map { it.name })
        assertEquals(listOf("cache", "C.txt", "b.txt", "a.db"), files.sortedBy(FileSort(FileColumn.Modified)).map { it.name })
        assertEquals(listOf("cache", "a.db", "b.txt", "C.txt"), files.sortedBy(FileSort(FileColumn.Kind)).map { it.name })
    }

    @Test fun `breaks the open folder into the folders on the way to it`() {
        assertEquals(listOf(FolderCrumb(null, "/"), FolderCrumb("sdcard", "/sdcard"), FolderCrumb("Android", "/sdcard/Android")),
            folderCrumbs(FileRoot.Storage, "/sdcard/Android"))
        assertEquals(listOf(FolderCrumb(null, "/")), folderCrumbs(FileRoot.Storage, "/"))
        assertEquals(listOf(FolderCrumb(null, "."), FolderCrumb("databases", "./databases")), folderCrumbs(FileRoot.AppData(pantry), "./databases"))
        assertEquals(listOf(FolderCrumb(null, ".")), folderCrumbs(FileRoot.AppData(pantry), "."))
    }

    @Test fun `reaches an app's own files with run-as, quoting paths`() {
        val device = FakeDevice { "ROBOMORI_DONE" }
        DeviceFilesService(device).list("emulator-5554", FileRoot.AppData(pantry), "./it's here")

        assertEquals("run-as com.example.pantry sh -c 'cd '\\''./it'\\''\\'\\'''\\''s here/'\\'' && { find . -mindepth 1 -maxdepth 1 -exec stat -L -c '\\''%F|%s|%Y|%n'\\'' {} + 2>/dev/null; echo ROBOMORI_DONE; }'",
            device.commands.single())
    }

    @Test fun `says why a folder can't be opened`() {
        val device = FakeDevice { "run-as: package not debuggable: com.example.pantry" }
        val failure = assertFailsWith<IllegalStateException> { DeviceFilesService(device).list("s", FileRoot.AppData(pantry), ".") }
        assertEquals("Pantry can't be browsed: it isn't debuggable.", failure.message)
    }

    @Test fun `uploads under a temporary name, then moves it into place once all of it arrived`() {
        val source = File(directory, "photo.jpg").apply { writeBytes(ByteArray(10)) }
        var checks = 0
        val device = FakeDevice { command -> if (command.startsWith("stat")) (if (++checks < 3) "4" else "10") else "" }

        DeviceFilesService(device).upload("s", FileRoot.Storage, source, "/sdcard/DCIM")

        assertEquals("cat > '/sdcard/DCIM/.photo.jpg.robomori'", device.pushes.single())
        assertEquals("mv -f '/sdcard/DCIM/.photo.jpg.robomori' '/sdcard/DCIM/photo.jpg'", device.commands.last())
    }

    @Test fun `selects one file, adds others with Cmd, and a run of them with Shift`() {
        val listing = listOf("a", "b", "c", "d").joinToString("\n") { "regular file|1|0|./$it" } + "\nROBOMORI_DONE"
        val files = runBlocking {
            DeviceFilesState(DeviceFilesService(FakeDevice { listing }), "s", this).also { it.refresh() }
        }
        val (a, b, c, d) = (files.snapshot as FolderSnapshot.Loaded).files

        files.select(b)
        files.select(d, toggle = true)
        assertEquals(setOf("b", "d"), files.selectedNames)
        files.select(c, extend = true)
        assertEquals(setOf("c", "d"), files.selectedNames)
        files.select(a)
        assertEquals(setOf("a"), files.selectedNames)
        assertEquals(listOf(b), files.contextTargets(b))
    }

    @Test fun `hides files named with a dot, and lets go of them`() {
        val listing = listOf(".nomedia", "a").joinToString("\n") { "regular file|1|0|./$it" } + "\nROBOMORI_DONE"
        val files = runBlocking { DeviceFilesState(DeviceFilesService(FakeDevice { listing }), "s", this).also { it.refresh() } }
        files.select(files.shownFiles.first(), toggle = true)
        files.select(files.shownFiles.last(), toggle = true)

        files.toggleHidden()

        assertEquals(listOf("a"), files.shownFiles.map { it.name })
        assertEquals(setOf("a"), files.selectedNames)
        files.toggleHidden()
        assertEquals(listOf(".nomedia", "a"), files.shownFiles.map { it.name })
    }

    @Test fun `searches the whole device by name, telling apart files of the same name`() = runBlocking {
        var searched = ""
        val device = FakeDevice { command ->
            when {
                "-iname" in command -> {
                    searched = command
                    listOf("regular file|1|0|./config.json", "regular file|1|0|./Download/config.json",
                        "directory|0|0|./Docs/JSON files").joinToString("\n") + "\nROBOMORI_DONE"
                }
                else -> "directory|0|0|./Pictures\nROBOMORI_DONE"
            }
        }
        val files = DeviceFilesState(DeviceFilesService(device), "s", this).also { it.refresh() }

        files.search(" json ")
        withTimeout(5000) { while (files.found !is FolderSnapshot.Loaded) delay(10) }

        assertEquals("/sdcard", files.folder)
        assertTrue("cd '/sdcard/'" in searched && "-iname '*json*'" in searched, "The whole shared storage is searched: $searched")
        assertEquals(listOf("/sdcard/Docs/JSON files", "/sdcard/Download/config.json", "/sdcard/config.json"),
            files.shownFiles.map { it.path }.sorted())
        assertFalse(files.showsParentRow)
        files.select(files.shownFiles.first { it.path == "/sdcard/Download/config.json" })
        assertEquals(listOf("/sdcard/Download/config.json"), files.shownFiles.filter { it.path in files.selection }.map { it.path },
            "Selecting one config.json leaves the other be.")

        files.open(files.shownFiles.first { it.kind == DeviceFileKind.Folder })
        assertEquals("", files.query, "Opening a folder found leaves the search for its files.")
        assertEquals("/sdcard/Docs/JSON files", files.folder)
    }

    @Test fun `names a copy as Finder does, keeping the extension`() {
        assertEquals("notes.txt", copyName("notes.txt", folder = false, taken = setOf("other.txt")))
        assertEquals("notes copy.txt", copyName("notes.txt", folder = false, taken = setOf("notes.txt")))
        assertEquals("notes copy 2.txt", copyName("notes.txt", folder = false, taken = setOf("notes.txt", "notes copy.txt")))
        assertEquals("v1.2 copy", copyName("v1.2", folder = true, taken = setOf("v1.2")))
        assertEquals(".nomedia copy", copyName(".nomedia", folder = false, taken = setOf(".nomedia")))
    }

    @Test fun `pastes copied files into the open folder, under new names where theirs are taken`() {
        val listing = "regular file|1|0|./notes.txt\nROBOMORI_DONE"
        val device = FakeDevice { command -> if ("cp -r" in command) "ROBOMORI_DONE" else listing }
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        runBlocking { files.refresh(); withTimeout(2000) { while (files.shownFiles.isEmpty()) delay(5) } }

        files.copy(files.shownFiles)
        files.paste()
        runBlocking { withTimeout(2000) { while (device.commands.none { "cp -r" in it }) delay(5) } }

        assertEquals("cp -r '/sdcard/notes.txt' '/sdcard/notes copy.txt'", device.commands.first { "cp -r" in it }.substringAfter("then echo ROBOMORI_EXISTS; else ").substringBefore(" && echo"))
    }

    @Test fun `going up selects the folder just left`() {
        val files = runBlocking { DeviceFilesState(DeviceFilesService(FakeDevice { "ROBOMORI_DONE" }), "s", this) }
        runBlocking { files.goTo(FolderCrumb("Pictures", "/sdcard/Pictures")) }

        files.up()

        assertEquals("/sdcard", files.folder)
        assertEquals(setOf("Pictures"), files.selectedNames)
    }

    @Test fun `makes a new folder at once and opens its name for editing, as Finder does`() {
        var made = false
        val device = FakeDevice { command ->
            when {
                "mkdir" in command -> { made = true; "ROBOMORI_DONE" }
                made -> "directory|4096|0|./untitled folder\ndirectory|4096|0|./untitled folder 2\nROBOMORI_DONE"
                else -> "directory|4096|0|./untitled folder\nROBOMORI_DONE"
            }
        }
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        runBlocking { files.refresh(); withTimeout(2000) { while (files.shownFiles.isEmpty()) delay(5) } }

        files.newFolder()
        runBlocking { withTimeout(2000) { while (files.renaming == null) delay(5) } }

        assertTrue(device.commands.any { "mkdir '/sdcard/untitled folder 2'" in it })
        assertEquals("untitled folder 2", files.renaming?.name)
    }

    /** A device with "notes.txt" and the folder "Docs" in /sdcard, which records its commands and says each worked. */
    private fun sdcard(): Pair<FakeDevice, DeviceFilesState> {
        val device = FakeDevice { command ->
            when {
                "mv " in command || "rmdir" in command || "rm -rf" in command || "mkdir" in command || "cp -r" in command -> "ROBOMORI_DONE"
                else -> "regular file|1|0|./notes.txt\ndirectory|4096|0|./Docs\nROBOMORI_DONE"
            }
        }
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        runBlocking { files.refresh(); withTimeout(2000) { while (files.shownFiles.isEmpty()) delay(5) } }
        return device to files
    }

    private fun DeviceFilesState.settle() = runBlocking {
        withTimeout(2000) { while (action is FileActionState.Running) delay(5) }
    }

    @Test fun `moves files dropped on a folder into it, and undoes the move`() {
        val (device, files) = sdcard()
        val notes = files.shownFiles.single { it.name == "notes.txt" }

        files.moveInto(listOf(notes), "/sdcard/Docs")
        files.settle()
        files.undo()
        files.settle()

        val moves = device.commands.filter { "mv " in it }.map { it.substringAfter("else ").substringBefore(" && echo") }
        assertEquals(listOf("mv '/sdcard/notes.txt' '/sdcard/Docs/notes.txt'", "mv '/sdcard/Docs/notes.txt' '/sdcard/notes.txt'"), moves)
    }

    @Test fun `asks before replacing a file of the same name, and keeps both when asked to`(@TempDir temp: File) {
        val (device, files) = sdcard()
        val upload = File(temp, "notes.txt").apply { writeText("new") }

        files.upload(listOf(upload))

        assertEquals(NameConflict("/sdcard", listOf("notes.txt")), files.conflict)
        assertTrue(device.commands.none { "cat >" in it })
        files.resolveConflict(ConflictChoice.Skip)
        files.settle()
        assertEquals(null, files.conflict)
        assertTrue(device.commands.none { "cat >" in it })
    }

    @Test fun `selects every file with Select All`() {
        val (_, files) = sdcard()

        files.selectAll()

        assertEquals(setOf("notes.txt", "Docs"), files.selectedNames)
    }

    @Test fun `opens every app's own folder as root on a device that allows it`() {
        val device = FakeDevice { command ->
            when {
                command == "su 0 id" -> "uid=0(root) gid=0(root)"
                command.startsWith("su 0 sh -c") -> "directory|4096|0|./com.release.app\nROBOMORI_DONE"
                else -> "ROBOMORI_DONE"
            }
        }
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))

        runBlocking { files.showApp("x"); files.goTo(FolderCrumb("data", "/data/data")); withTimeout(2000) { while (files.shownFiles.isEmpty()) delay(5) } }

        assertEquals(listOf("com.release.app"), files.shownFiles.map { it.name })
        assertTrue(device.commands.any { it.startsWith("su 0 sh -c") && "/data/data/" in it })
    }

    @Test fun `hands files made as root in an app's folder to that app`() {
        val script = ownedByApp("/data/data/com.a/files/new.txt")
        assertTrue("chown -R $(stat -c '%u:%g' '/data/data/com.a') '/data/data/com.a/files/new.txt'" in script)
        assertTrue("restorecon -R '/data/data/com.a/files/new.txt'" in script)
        assertEquals("", ownedByApp("/sdcard/new.txt"))
    }

    @Test fun `keeps each device's place in a file, history and scroll included`(@TempDir temp: File) {
        val store = FilesPlacesStore(File(temp, "places.json"))
        val place = FilesPlace("/sdcard/DCIM", back = listOf("/sdcard"), forward = listOf("/sdcard/DCIM/Camera"),
            scrolls = mapOf("/sdcard" to FilesScroll(12, 30)))

        store.save(mapOf("Pixel_10" to place))

        assertEquals(mapOf("Pixel_10" to place), FilesPlacesStore(File(temp, "places.json")).load())
    }

    @Test fun `starts where the device's page was, history and all`() {
        val files = DeviceFilesState(DeviceFilesService(FakeDevice { "ROBOMORI_DONE" }), "s", CoroutineScope(Dispatchers.IO),
            startPlace = FilesPlace("/sdcard/DCIM", back = listOf("/sdcard")))

        assertEquals("/sdcard/DCIM", files.folder)
        files.back()
        assertEquals("/sdcard", files.folder)
    }

    @Test fun `hands over a big folder's files while it's still being listed`() {
        val lines = (1..2000).joinToString("\n") { "regular file|1|0|./f$it" } + "\nROBOMORI_DONE"
        val device = object : DeviceFileTransport {
            override fun shell(serial: String, command: String) = lines
            override fun shellLines(serial: String, command: String, onLine: (String) -> Unit) =
                lines.lineSequence().forEach { Thread.sleep(0, 200_000); onLine(it) }
            override fun pull(serial: String, command: String, destination: File) {}
            override fun push(serial: String, command: String, source: File) {}
        }
        val parts = mutableListOf<Int>()

        val all = DeviceFilesService(device).list("s", FileRoot.Storage, "/sdcard") { parts += it.size }

        assertEquals(2000, all.size)
        assertTrue(parts.isNotEmpty() && parts.all { it < 2000 }, "parts: $parts")
    }

    @Test fun `goes back and forward through the folders opened`() {
        val files = runBlocking { DeviceFilesState(DeviceFilesService(FakeDevice { "ROBOMORI_DONE" }), "s", this) }
        runBlocking {
            files.goTo(FolderCrumb(null, "/"))
            files.goTo(FolderCrumb("system", "/system"))
            files.back()
            assertEquals("/", files.folder)
            files.back()
            assertEquals("/sdcard", files.folder)
            files.back()
            assertEquals("/sdcard", files.folder)
            files.forward()
            files.forward()
            assertEquals("/system", files.folder)
            files.back()
            files.goTo(FolderCrumb("sdcard", "/sdcard"))
            files.forward()
            assertEquals("/sdcard", files.folder)
        }
    }

    @Test fun `moves the selection with the keys, up to the parent row`() {
        val listing = listOf("a", "b").joinToString("\n") { "regular file|1|0|./$it" } + "\nROBOMORI_DONE"
        val files = runBlocking { DeviceFilesState(DeviceFilesService(FakeDevice { listing }), "s", this).also { it.refresh() } }

        assertEquals(0, files.moveSelection(1))
        assertEquals(setOf(ParentRowName), files.selection)
        files.moveSelection(1)
        files.moveSelection(1, extend = true)
        assertEquals(setOf("a", "b"), files.selectedNames)
        files.moveSelection(-2)
        assertEquals(setOf(ParentRowName), files.selection)
    }

    @Test fun `opens a debuggable app's own folder as the app, and lists the apps in data data`() {
        val device = FakeDevice { command ->
            if ("%s|%Y" in command && "%F" !in command) "4096|1795180552" else "directory|4096|1795180552|./databases\nROBOMORI_DONE"
        }
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO), debuggableApps = { listOf(pantry) })
        // Waits for each folder the state opens to be read; the folder before stays listed until then.
        fun opened(action: () -> Unit) = runBlocking {
            val before = files.snapshot
            action()
            withTimeout(2000) { while (files.snapshot === before || files.snapshot !is FolderSnapshot.Loaded) delay(5) }
        }
        runBlocking { files.loadApps() }

        opened { files.showApp("com.example.pantry") }
        assertEquals("/data/data/com.example.pantry", files.folder)
        assertTrue(device.commands.last().startsWith("run-as com.example.pantry sh -c "))
        assertTrue("/data/data/com.example.pantry/" in device.commands.last())
        assertEquals(listOf("databases"), files.shownFiles.map { it.name })

        opened(files::up)
        val folder = files.shownFiles.single()
        assertEquals(DeviceFile("com.example.pantry", "/data/data/com.example.pantry", DeviceFileKind.Folder, 4096,
            Instant.ofEpochSecond(1795180552)), folder)
        assertEquals(pantry, files.appFor(folder))
        opened(files::up)
        assertEquals(listOf("data", "local"), files.shownFiles.map { it.name })
    }

    @Test fun `knows which paths are inside an app's own folder`() {
        assertEquals("com.a", appPackageOf("/data/data/com.a"))
        assertEquals("com.a", appPackageOf("/data/data/com.a/files/x"))
        assertEquals(null, appPackageOf("/data/data"))
        assertEquals(null, appPackageOf("/sdcard/data/data/com.a"))
    }

    @Test fun `uses each platform's file browser keys`() {
        fun mac(key: Key, meta: Boolean = true, shift: Boolean = false) = fileShortcut(key, meta, ctrl = false, alt = false, shift, mac = true)
        fun pc(key: Key, ctrl: Boolean = false, alt: Boolean = false) = fileShortcut(key, meta = false, ctrl, alt, shift = false, mac = false)

        assertEquals(FileShortcut.Back, mac(Key.LeftBracket))
        assertEquals(FileShortcut.Forward, mac(Key.RightBracket))
        assertEquals(FileShortcut.Up, mac(Key.DirectionUp))
        assertEquals(FileShortcut.Open, mac(Key.DirectionDown))
        assertEquals(FileShortcut.ToggleHidden, mac(Key.Period, shift = true))
        assertEquals(FileShortcut.Next, mac(Key.DirectionDown, meta = false))
        assertEquals(null, mac(Key.Backspace, meta = false))

        assertEquals(FileShortcut.Back, pc(Key.DirectionLeft, alt = true))
        assertEquals(FileShortcut.Back, pc(Key.Backspace))
        assertEquals(FileShortcut.Up, pc(Key.DirectionUp, alt = true))
        assertEquals(FileShortcut.Open, pc(Key.Enter))
        assertEquals(FileShortcut.ToggleHidden, pc(Key.H, ctrl = true))
        assertEquals(FileShortcut.Search, mac(Key.F))
        assertEquals(FileShortcut.Search, pc(Key.F, ctrl = true))
        assertEquals(FileShortcut.Delete, mac(Key.Backspace))
        assertEquals(FileShortcut.Delete, pc(Key.Delete))
        assertEquals(FileShortcut.Copy, mac(Key.C))
        assertEquals(FileShortcut.Paste, mac(Key.V))
        assertEquals(FileShortcut.Copy, pc(Key.C, ctrl = true))
        assertEquals(FileShortcut.Paste, pc(Key.V, ctrl = true))
        assertEquals(FileShortcut.MoveHere, fileShortcut(Key.V, meta = true, ctrl = false, alt = true, shift = false, mac = true))
        assertEquals(FileShortcut.Cut, pc(Key.X, ctrl = true))
        assertEquals(FileShortcut.Undo, mac(Key.Z))
        assertEquals(FileShortcut.SelectAll, pc(Key.A, ctrl = true))
        assertEquals(FileShortcut.QuickLook, mac(Key.Spacebar, meta = false))
        assertEquals(FileShortcut.Previous, pc(Key.DirectionUp))
    }

    @Test fun `opens a file from a copy on this computer, under its own name`() {
        val file = DeviceFile("notes.txt", "/sdcard/notes.txt", DeviceFileKind.File, 5, Instant.EPOCH)
        val service = DeviceFilesService(FakeDevice(pulled = "hello") { "5" })
        var opened: File? = null

        runBlocking {
            DeviceFilesState(service, "s", this, openLocally = { opened = it }).open(file)
        }

        assertEquals("notes.txt", opened?.name)
        assertEquals("hello", opened?.readText())
        opened?.parentFile?.deleteRecursively()
    }

    @Test fun `renames a file in its folder, unless the new name is taken`() {
        val file = DeviceFile("a b.txt", "/sdcard/a b.txt", DeviceFileKind.File, 1, Instant.EPOCH)
        val device = FakeDevice { "ROBOMORI_DONE" }
        DeviceFilesService(device).rename("s", FileRoot.Storage, file, "it's.txt")
        assertEquals("if [ -e '/sdcard/it'\\''s.txt' ]; then echo ROBOMORI_EXISTS; else mv '/sdcard/a b.txt' '/sdcard/it'\\''s.txt' " +
            "&& echo ROBOMORI_DONE; fi", device.commands.single())

        val taken = assertFailsWith<IllegalStateException> { DeviceFilesService(FakeDevice { "ROBOMORI_EXISTS" }).rename("s", FileRoot.Storage, file, "b.txt") }
        assertEquals("Something named b.txt is already in this folder.", taken.message)
        assertTrue(listOf("", " ", "a/b", ".", "..").none(::isValidFileName))
        assertTrue(isValidFileName(".nomedia"))
    }

    @Test fun `offers an opened file saved on this computer for upload back to where it came from`() {
        val file = DeviceFile("strings.xml", "/sdcard/strings.xml", DeviceFileKind.File, 5, Instant.EPOCH)
        var size = "5"
        val device = FakeDevice(pulled = "hello") { command -> if ("stat -L -c %s" in command) size else "" }
        var opened: File? = null
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO), openLocally = { opened = it })
        fun settled(action: () -> Unit) = runBlocking {
            action()
            withTimeout(2000) { while (files.action !is FileActionState.Idle) delay(5) }
        }

        settled { files.open(file) }
        files.checkEdits()
        assertEquals(emptyList(), files.edits)

        val copy = opened!!
        copy.writeText("hello, edited")
        copy.setLastModified(System.currentTimeMillis() + 2000)
        files.checkEdits()
        val edit = files.edits.single()
        assertEquals(file, edit.file)
        assertEquals(13, edit.size)

        size = "13"
        settled { files.uploadEdit(edit) }
        assertEquals("cat > '/sdcard/.strings.xml.robomori'", device.pushes.single())
        assertTrue("mv -f '/sdcard/.strings.xml.robomori' '/sdcard/strings.xml'" in device.commands)
        files.checkEdits()
        assertEquals(emptyList(), files.edits)
        copy.parentFile.deleteRecursively()
    }

    @Test fun `previews keep the shape of the image, scaled to fit`() {
        fun png(width: Int, height: Int): ByteArray {
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(width, height)
            return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
        }
        val wide = thumbnailOf(png(200, 100), 48)!!
        assertEquals(48 to 24, wide.width to wide.height)
        val tall = thumbnailOf(png(90, 360), 48)!!
        assertEquals(12 to 48, tall.width to tall.height)
        val small = thumbnailOf(png(20, 10), 48)!!
        assertEquals(20 to 10, small.width to small.height)
    }

    @Test fun `copies a folder with everything in it, as one archive from the device`(@TempDir temp: File) {
        val source = File(temp, "device").apply { File(this, "Photos/nested").mkdirs() }
        File(source, "Photos/a.txt").writeText("hello")
        File(source, "Photos/nested/b.txt").writeText("deep")
        val archive = File(temp, "photos.tar")
        check(ProcessBuilder("tar", "-cf", archive.path, "-C", source.path, "Photos").start().waitFor() == 0)
        val pulls = mutableListOf<String>()
        val device = object : DeviceFileTransport {
            override fun shell(serial: String, command: String) = ""
            override fun pull(serial: String, command: String, destination: File) { pulls += command; archive.copyTo(destination, overwrite = true) }
            override fun push(serial: String, command: String, source: File) {}
        }
        val destination = File(temp, "out")

        DeviceFilesService(device).saveFolder("s", FileRoot.Storage, DeviceFile("Photos", "/sdcard/Photos", DeviceFileKind.Folder, 0, Instant.EPOCH), destination)

        assertEquals(listOf("tar -cf - -C '/sdcard' 'Photos'"), pulls)
        assertEquals("hello", File(destination, "Photos/a.txt").readText())
        assertEquals("deep", File(destination, "Photos/nested/b.txt").readText())
    }

    @Test fun `saves a file only when all of it was copied`() {
        val file = DeviceFile("notes.txt", "/sdcard/notes.txt", DeviceFileKind.File, 5, Instant.EPOCH)
        val destination = File(directory, "notes.txt")
        val short = FakeDevice(pulled = "abc") { "5" }

        assertFailsWith<IllegalStateException> { DeviceFilesService(short).save("s", FileRoot.Storage, file, destination) }
        assertTrue(!destination.exists())

        DeviceFilesService(FakeDevice(pulled = "hello") { "5" }).save("s", FileRoot.Storage, file, destination)
        assertEquals("hello", destination.readText())
        assertEquals(listOf("notes.txt"), directory.list()!!.toList())
    }

    @Test fun `finds an app's databases, leaving out their journals and other files`() {
        val listing = listOf("app.db|8", "app.db-wal|4", "app.db-shm|4", "room_master|8", "room_master-journal|0", "backup.json|5", "empty.db|0")
            .joinToString("\n") { "regular file|${it.substringAfter('|')}|0|./${it.substringBefore('|')}" } + "\nROBOMORI_DONE"
        val databases = AppDatabasesService(DeviceFilesService(FakeDevice { listing })).databases("s", pantry)
        assertEquals(listOf("app.db", "room_master"), databases.map { it.name })
    }

    @Test fun `queries a copy of a database, reading only`() {
        val file = File(directory, "pantry.db")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use {
                it.executeUpdate("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT, photo BLOB)")
                it.executeUpdate("INSERT INTO items(name, photo) VALUES ('Milk', x'0102'), ('Eggs', NULL), ('Rice', NULL)")
                it.executeUpdate("CREATE VIEW names AS SELECT name FROM items")
            }
        }

        DatabaseSnapshot(file).use { snapshot ->
            val tables = snapshot.tables()
            assertEquals(listOf("items" to 3L, "names" to null), tables.map { it.name to it.rows })
            assertEquals(listOf(true, false), tables.map { it.editable }, "A view's rows can't be changed one by one.")
            assertEquals(setOf("id"), tables.first().notNull, "An INTEGER PRIMARY KEY is the rowid, never NULL.")
            val result = snapshot.query("SELECT * FROM items", limit = 2)
            assertEquals(listOf("id", "name", "photo"), result.columns)
            assertEquals(listOf(listOf("1", "Milk", "BLOB · 2 bytes"), listOf("2", "Eggs", null)), result.rows)
            assertTrue(result.more)
            assertEquals(setOf(0 to 2), result.blobs, "Bytes are known as bytes, not by how their size reads.")
            assertFailsWith<WritingStatementException>("A change goes to the device, never this copy.") { snapshot.query("DELETE FROM items") }
        }
    }

    @Test fun `an open database follows what the app writes to it`() = runBlocking {
        val onDevice = File(directory, "device.db")
        fun write(sql: String) = DriverManager.getConnection("jdbc:sqlite:${onDevice.absolutePath}").use { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }
        write("CREATE TABLE items(name TEXT)")
        write("INSERT INTO items VALUES ('Milk')")
        // Two writes within a second differ only in the nanoseconds of when the file changed.
        var modified = "2026-11-22 16:20:01.100000000 +0700"
        var copies = 0
        var reachable = true
        val device = object : DeviceFileTransport {
            override fun shell(serial: String, command: String) = when {
                !reachable -> error("device offline")
                "%y" in command -> "/data/data/com.example.pantry/databases/app.db|${onDevice.length()}|$modified"
                "find ." in command -> "regular file|${onDevice.length()}|$modified|./app.db\nROBOMORI_DONE"
                "stat -L -c %s" in command -> "${onDevice.length()}"
                else -> ""
            }
            override fun pull(serial: String, command: String, destination: File) { copies++; onDevice.copyTo(destination, overwrite = true) }
            override fun push(serial: String, command: String, source: File) {}
        }
        // On this test's one thread, as the app's state runs on its main thread.
        val databases = AppDatabasesState(AppDatabasesService(DeviceFilesService(device)),
            InstalledAppsService(FakeAndroid(directory).settings), "s", this)
        suspend fun rows(count: Int) = withTimeout(5000) {
            while ((databases.result as? QueryState.Done)?.result?.rows?.size != count) delay(10)
        }

        databases.chooseApp(pantry)
        rows(1)
        databases.followChanges()
        assertEquals(1, copies, "Unchanged, the database isn't copied again.")

        write("INSERT INTO items VALUES ('Eggs')")
        modified = "2026-11-22 16:20:01.600000000 +0700"
        databases.followChanges()
        rows(2)
        assertEquals(2, copies)
        assertFalse(databases.updateFailed)

        reachable = false
        databases.followChanges()
        assertTrue(databases.updateFailed, "Losing the device says the rows may be out of date.")
        assertEquals(2, (databases.result as QueryState.Done).result.rows.size)
        reachable = true
        databases.followChanges()
        assertFalse(databases.updateFailed)
        databases.close()
    }

    @Test fun `editing a cell changes the app's database on the device`() = runBlocking {
        val onDevice = File(directory, "device.db")
        fun sql(statement: String) = DriverManager.getConnection("jdbc:sqlite:${onDevice.absolutePath}").use { connection ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }
        sql("CREATE TABLE items(name TEXT, amount INTEGER)")
        sql("INSERT INTO items VALUES ('Milk', 1), ('Eggs', 12)")
        var installed = false
        var modified = 0
        val device = object : DeviceFileTransport {
            override fun shell(serial: String, command: String): String = when {
                "DatabaseHelper" in command -> if (!installed) "ROBOMORI_MISSING" else {
                    val encoded = command.substringAfter("DatabaseHelper ").substringAfter(' ').substringBefore(';').trim()
                    sql(String(java.util.Base64.getDecoder().decode(encoded))).let { modified++; "ROBOMORI_CHANGED\t$it" }
                }
                "%y" in command -> "./databases/app.db|${onDevice.length()}|$modified"
                "find ." in command -> "regular file|${onDevice.length()}|0|./app.db\nROBOMORI_DONE"
                "stat -L -c %s" in command -> "${onDevice.length()}"
                else -> ""
            }
            override fun pull(serial: String, command: String, destination: File) { onDevice.copyTo(destination, overwrite = true) }
            override fun push(serial: String, command: String, source: File) { if ("robomori-database-test.jar" in command) installed = true }
        }
        val helper = DeviceHelperJar(File(directory, "helper.jar").apply { writeText("dex") }, "test")
        val databases = AppDatabasesState(AppDatabasesService(DeviceFilesService(device)) { helper },
            InstalledAppsService(FakeAndroid(directory).settings), "s", this)
        suspend fun shown() = withTimeout(5000) {
            while (databases.result !is QueryState.Done) delay(10)
            val result = (databases.result as QueryState.Done).result
            databases.shownRows(result).map { result.rows[it].drop(1) }
        }

        databases.chooseApp(pantry)
        assertEquals(listOf(listOf("Milk", "1"), listOf("Eggs", "12")), shown())
        assertTrue(databases.editable)
        assertEquals(listOf("name", "amount"), databases.layout.order, "The rowid each edit needs stays hidden.")

        val milk = (databases.result as QueryState.Done).result.rows.first()
        databases.editCell(milk, "name", "Oat milk")
        assertEquals(listOf("Oat milk", "1"), shown().first(), "The new value shows at once.")
        withTimeout(5000) { while (databases.action != DataAction.None) delay(10) }
        assertTrue(installed, "The helper goes into the app's folder the first time.")
        assertEquals("Oat milk", DriverManager.getConnection("jdbc:sqlite:${onDevice.absolutePath}").use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT name FROM items WHERE rowid = 1").use { rows -> rows.next(); rows.getString(1) } }
        })

        databases.sortBy("amount")
        databases.sortBy("amount")
        withTimeout(5000) { while (shown().first() != listOf("Eggs", "12")) delay(10) }
        databases.close()
    }

    @Test fun `knows which columns refuse NULL, and says why a change was refused without SQLite's code`() {
        val file = File(directory, "contexts.db")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE TABLE contexts(_id INTEGER PRIMARY KEY, backend TEXT NOT NULL, extras BLOB)") }
        }
        DatabaseSnapshot(file).use { assertEquals(setOf("_id", "backend"), it.tables().single().notNull) }

        val refusing = FakeDevice { "ROBOMORI_ERROR\tNOT NULL constraint failed: contexts.backend (code 1299 SQLITE_CONSTRAINT_NOTNULL)" }
        val service = AppDatabasesService(DeviceFilesService(refusing)) { DeviceHelperJar(File(directory, "helper.jar"), "test") }
        val failure = assertFailsWith<IllegalStateException> {
            service.execute("s", pantry, DeviceFile("app.db", "./databases/app.db", DeviceFileKind.File, 1, Instant.EPOCH), "UPDATE contexts SET backend = NULL")
        }
        assertEquals("NOT NULL constraint failed: contexts.backend", failure.message)
    }

    @Test fun `cells compare as SQLite orders them`() {
        assertEquals(listOf(null, "2", "10", "apple", "Banana"), listOf("Banana", "10", null, "apple", "2").sortedWith(::compareCells))
    }

    @Test fun `reads the open folder again only once it changed on the device, keeping the selection`() {
        var stamp = "a".repeat(32) + "  -"
        var listing = "regular file|1|0|./a\nROBOMORI_DONE"
        val device = FakeDevice { command -> when { "md5sum" in command -> stamp; "find ." in command -> listing; else -> "" } }
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        fun listings() = device.commands.count { "%F|%s|%Y|%n" in it }
        fun checks() = device.commands.count { "md5sum" in it }
        fun until(condition: () -> Boolean) = runBlocking { withTimeout(2000) { while (!condition()) delay(5) } }
        /** Checks the folder, and waits for the check, and the reading it may start, to finish. */
        fun poll() {
            val before = checks()
            files.poll()
            until { checks() > before }
            runBlocking { delay(100) }
        }
        files.refresh()
        until { files.shownFiles.map { it.name } == listOf("a") }
        files.select(files.shownFiles.single())

        poll()
        assertEquals(1, listings(), "An unchanged folder isn't read again.")

        listing = "regular file|1|0|./a\nregular file|1|0|./b\nROBOMORI_DONE"
        stamp = "b".repeat(32) + "  -"
        poll()
        assertEquals(listOf("a", "b"), files.shownFiles.map { it.name })
        assertEquals(setOf("/sdcard/a"), files.selection)

        listing = "regular file|1|0|./a\nregular file|1|0|./b\nregular file|1|0|./c\nROBOMORI_DONE"
        stamp = "c".repeat(32) + "  -"
        files.requestRename(files.shownFiles.first())
        files.poll()
        runBlocking { delay(100) }
        assertEquals(listOf("a", "b"), files.shownFiles.map { it.name }, "A folder isn't read again while a file in it is renamed.")
        files.dismissRename()
        poll()
        assertEquals(listOf("a", "b", "c"), files.shownFiles.map { it.name })
    }

    @Test fun `fingerprints a folder on the device, or tells it can't`() {
        val device = FakeDevice { "d41d8cd98f00b204e9800998ecf8427e  -" }
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", DeviceFilesService(device).folderStamp("s", FileRoot.Storage, "/sdcard"))
        assertEquals("cd '/sdcard/' && find . -mindepth 1 -maxdepth 1 -exec stat -L -c '%F|%s|%y|%n' {} + 2>/dev/null | md5sum",
            device.commands.single())
        assertEquals(null, DeviceFilesService(FakeDevice { "run-as: package not debuggable: com.example.pantry" }).folderStamp("s", FileRoot.AppData(pantry), "."))
    }

    @Test fun `asks Android to index files uploaded to shared storage, and only there`() {
        val source = File(directory, "photo 1.jpg").apply { writeBytes(ByteArray(3)) }
        val device = FakeDevice { command -> if (command.startsWith("stat")) "3" else "" }
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        fun scans() = device.commands.filter { "scan_file" in it }
        fun until(condition: () -> Boolean) = runBlocking { withTimeout(2000) { while (!condition()) delay(5) } }

        files.upload(listOf(source))
        until { scans().isNotEmpty() }
        assertEquals("content call --uri content://media/external/file --method scan_file --arg '/sdcard/photo 1.jpg' 2>/dev/null | " +
            "grep -q Result || am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file:///sdcard/photo%201.jpg' >/dev/null 2>&1; true",
            scans().single())

        until { files.action is FileActionState.Idle }
        files.upload(listOf(source), "/data/local/tmp")
        until { device.pushes.size == 2 && files.action is FileActionState.Idle }
        runBlocking { delay(100) }
        assertEquals(1, scans().size)
        assertTrue(inSharedStorage("/storage/emulated/0/DCIM/a.jpg") && inSharedStorage("/sdcard") && !inSharedStorage("/sdcard2/a.jpg"))
    }

    @Test fun `copies the device paths of files, one per line`() {
        fun file(path: String) = DeviceFile(path.substringAfterLast('/'), path, DeviceFileKind.File, 1, Instant.EPOCH)
        assertEquals("/sdcard/a b.txt\n/sdcard/c.txt", pathsText(listOf(file("/sdcard/a b.txt"), file("/sdcard/c.txt"))))
    }

    @Test fun `offers to open apps' databases and preferences in App data`() {
        fun file(path: String, size: Long = 8) = DeviceFile(path.substringAfterLast('/'), path, DeviceFileKind.File, size, Instant.EPOCH)
        val app = "/data/data/com.example.pantry"
        assertTrue(listOf("$app/databases/app.db", "$app/databases/room_master", "$app/shared_prefs/settings.xml",
            "$app/files/datastore/user.preferences_pb").all { opensInAppData(file(it)) })
        assertFalse(listOf("$app/databases/app.db-wal", "$app/databases/backup.json", "$app/files/app.db", "$app/shared_prefs/notes.txt",
            "/sdcard/app.db", "$app/databases").any { opensInAppData(file(it)) })
        assertFalse(opensInAppData(file("$app/databases/empty.db", size = 0)))
        assertFalse(opensInAppData(DeviceFile("databases", "$app/databases", DeviceFileKind.Folder, 0, Instant.EPOCH)))

        val files = DeviceFilesState(DeviceFilesService(FakeDevice { "" }), "s", CoroutineScope(Dispatchers.IO), debuggableApps = { listOf(pantry) })
        assertFalse(files.canOpenInAppData(file("$app/databases/app.db")), "Only debuggable apps' data opens there.")
        runBlocking { files.loadApps() }
        assertTrue(files.canOpenInAppData(file("$app/databases/app.db")))
        assertFalse(files.canOpenInAppData(file("/data/data/com.other/databases/app.db")))
    }

    @Test fun `App data opens the database or preferences file chosen in Files`() = runBlocking {
        val device = FakeDevice { command ->
            when {
                "./databases/" in command -> "regular file|8|0|./app.db\nregular file|8|0|./cache.db\nROBOMORI_DONE"
                "./shared_prefs/" in command -> "regular file|5|0|./settings.xml\nROBOMORI_DONE"
                else -> "ROBOMORI_DONE"
            }
        }
        val jar = DeviceHelperJar(File(directory, "h.jar"), "t")
        val installedApps = InstalledAppsService(FakeAndroid(directory).settings,
            executor = { _, _, _ -> "ROBOMORI_APP\tcom.example.other\tOther\nROBOMORI_APP\tcom.example.pantry\tPantry\nROBOMORI_DONE" }) { jar }
        val service = DeviceFilesService(device) { jar }
        val databases = AppDatabasesState(AppDatabasesService(service) { jar }, installedApps, "s",
            CoroutineScope(Dispatchers.IO), AppPreferencesService(service))

        databases.openFile("com.example.pantry", "/data/data/com.example.pantry/databases/cache.db")
        databases.loadApps()
        withTimeout(2000) { while (databases.database?.path != "./databases/cache.db") delay(5) }
        assertEquals("com.example.pantry", databases.app?.packageName)

        databases.openFile("com.example.pantry", "/data/data/com.example.pantry/shared_prefs/settings.xml")
        withTimeout(2000) { while (databases.preferenceFile?.file?.path != "./shared_prefs/settings.xml") delay(5) }
        assertEquals(null, databases.database)
        databases.close()
    }

    @Test fun `edits a text file in shared storage and saves it over the old one, keeping its permissions`() {
        val device = TextFileDevice("/sdcard/Download/notes.txt", "hello")
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        val editor = files.openEditor(device.file)
        assertEquals("hello", editor.text.text.toString())
        assertFalse(editor.changed)

        editor.text.edit { append(", world") }
        assertTrue(editor.changed)
        editor.saved { editor.save() }

        assertEquals(TextSaveState.Saved, editor.save)
        assertFalse(editor.changed)
        assertEquals("hello, world", device.content)
        assertEquals(listOf("cat > '/sdcard/Download/.notes.txt.robomori'"), device.pushes)
        assertTrue(keepingAttributes("/sdcard/Download/.notes.txt.robomori", "/sdcard/Download/notes.txt") in device.commands)
        assertTrue(device.commands.filter { "notes.txt" in it }.none { it.startsWith("su ") || it.startsWith("run-as ") })
    }

    @Test fun `keeps the old file's permissions, owner and label, then moves the new one over it`() {
        assertEquals("if [ -e '/a/b.txt' ]; then chmod $(stat -c %a '/a/b.txt') '/a/.b.txt.robomori'; " +
            "chown $(stat -c %u:%g '/a/b.txt') '/a/.b.txt.robomori' 2>/dev/null; chcon $(stat -c %C '/a/b.txt') '/a/.b.txt.robomori' 2>/dev/null; fi; " +
            "mv -f '/a/.b.txt.robomori' '/a/b.txt' && echo ROBOMORI_DONE", keepingAttributes(partialOf("/a/b.txt"), "/a/b.txt"))
    }

    @Test fun `writes a debuggable app's file as the app, and other apps' and system files as root`() {
        val app = TextFileDevice("/data/data/com.example.pantry/files/config.json", "{}")
        DeviceFilesService(app).writeText("s", FileRoot.AppData(pantry), app.file.path, "{ }", app.stamp, overwrite = false)
        assertEquals("{ }", app.content)
        assertTrue(app.commands.all { it.startsWith("run-as com.example.pantry sh -c ") })
        assertTrue(app.pushes.single().startsWith("run-as com.example.pantry sh -c "))

        val system = TextFileDevice("/system/etc/hosts", "127.0.0.1 localhost")
        DeviceFilesService(system).writeText("s", FileRoot.Superuser, system.file.path, "10.0.2.2 host", system.stamp, overwrite = false)
        assertEquals("10.0.2.2 host", system.content)
        assertTrue(system.commands.all { it.startsWith("su 0 sh -c ") })
    }

    @Test fun `doesn't overwrite a file that changed on the device since it was opened, unless told to`() {
        val device = TextFileDevice("/sdcard/notes.txt", "first")
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        val editor = files.openEditor(device.file)
        editor.text.edit { replace(0, length, "mine") }
        device.writeOnDevice("theirs")

        editor.saved { editor.save() }
        assertEquals(TextSaveState.Conflict, editor.save)
        assertEquals("theirs", device.content)
        assertTrue(device.pushes.isEmpty())

        editor.saved { editor.overwrite() }
        assertEquals(TextSaveState.Saved, editor.save)
        assertEquals("mine", device.content)

        // Saved again later, it compares with the stamp its own save left, so it doesn't conflict with itself.
        editor.text.edit { append(" again") }
        editor.saved { editor.save() }
        assertEquals(TextSaveState.Saved, editor.save)

        device.writeOnDevice("theirs again")
        editor.text.edit { append("!") }
        editor.saved { editor.save() }
        assertEquals(TextSaveState.Conflict, editor.save)
        runBlocking {
            editor.load()
            withTimeout(2000) { while (editor.content != TextEditorContent.Ready) delay(5) }
        }
        assertEquals("theirs again", editor.text.text.toString())
        assertFalse(editor.changed)
    }

    @Test fun `says why a save failed, keeping the edits`() {
        val device = TextFileDevice("/system/etc/hosts", "a", ready = "sh: can't create /system/etc/.hosts.robomori: Read-only file system")
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        val editor = files.openEditor(device.file)
        editor.text.edit { append("b") }

        editor.saved { editor.save() }

        val failed = editor.save as TextSaveState.Failed
        assertEquals("sh: can't create /system/etc/.hosts.robomori: Read-only file system", failed.cause.message)
        assertTrue(editor.changed)
        assertEquals("a", device.content)
    }

    @Test fun `asks before closing the editor with changes that weren't saved`() {
        val device = TextFileDevice("/sdcard/notes.txt", "hello")
        val files = DeviceFilesState(DeviceFilesService(device), "s", CoroutineScope(Dispatchers.IO))
        val editor = files.openEditor(device.file)

        editor.text.edit { append("!") }
        editor.cancel()
        assertTrue(editor.confirmingDiscard)
        editor.keepEditing()
        assertEquals(editor, files.editor)

        editor.cancel()
        editor.discard()
        assertEquals(null, files.editor)
        assertEquals("hello", device.content)

        // Unchanged, it closes at once.
        val again = files.openEditor(device.file)
        again.cancel()
        assertEquals(null, files.editor)
    }

    @Test fun `edits only text files of a reasonable size`() {
        assertEquals("héllo\n", textOf("héllo\n".toByteArray()))
        assertEquals(null, textOf(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals(null, textOf(byteArrayOf(0x50, 0x4B, 0x00, 0x03)))

        val files = DeviceFilesState(DeviceFilesService(FakeDevice { "" }), "s", CoroutineScope(Dispatchers.IO))
        fun file(name: String, size: Long = 10) = DeviceFile(name, "/sdcard/$name", DeviceFileKind.File, size, Instant.EPOCH)
        assertTrue(files.canEdit(file("notes.txt")))
        assertTrue(files.canEdit(file("Makefile")))
        assertFalse(files.canEdit(file("photo.png")))
        assertFalse(files.canEdit(file("app.db")))
        assertFalse(files.canEdit(file("big.log", EditableTextBytes + 1)))
        assertFalse(files.canEdit(DeviceFile("Download", "/sdcard/Download", DeviceFileKind.Folder, 0, Instant.EPOCH)))

        val binary = TextFileDevice("/sdcard/blob", "PK\u0000\u0003")
        assertEquals(TextFileRead.NotText, DeviceFilesService(binary).readText("s", FileRoot.Storage, binary.file))
        val grown = TextFileDevice("/sdcard/grown.log", "x".repeat(10))
        grown.writeOnDevice("x".repeat(EditableTextBytes.toInt() + 1))
        assertEquals(TextFileRead.TooLarge(EditableTextBytes + 1), DeviceFilesService(grown).readText("s", FileRoot.Storage, grown.file))
    }

    /** Opens [file] in the page's editor and waits for its text to be read. */
    private fun DeviceFilesState.openEditor(file: DeviceFile): TextFileEditor = runBlocking {
        edit(file)
        val editor = editor!!
        withTimeout(2000) { while (editor.content == TextEditorContent.Loading) delay(5) }
        editor
    }

    /** Does [action], then waits for the save it starts to finish. */
    private fun TextFileEditor.saved(action: () -> Unit) = runBlocking {
        action()
        withTimeout(5000) { while (save == TextSaveState.Saving) delay(5) }
    }

    /**
     * A device with one text [file], which answers the commands that read, stamp and write it. Each write gives it a new
     * stamp, as the file's change time to the nanosecond would. [ready] answers whether its folder can be written.
     */
    private class TextFileDevice(path: String, var content: String, private val ready: String = "ROBOMORI_READY") : DeviceFileTransport {
        val file = DeviceFile(path.substringAfterLast('/'), path, DeviceFileKind.File, content.length.toLong(), Instant.EPOCH)
        private val partial = partialOf(path)
        private var partialContent: String? = null
        private var version = 0
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        val pushes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val stamp get() = setOf("${file.path}|${content.toByteArray().size}|2026-11-22 10:00:00.00000000$version")

        /** An app or person writing the file on the device. */
        fun writeOnDevice(text: String) { content = text; version++ }

        override fun shell(serial: String, command: String): String {
            commands += command
            return when {
                "%n|%s|%y" in command -> stamp.joinToString("\n")
                ": > " in command -> ready
                "chmod $(stat" in command -> { content = partialContent!!; partialContent = null; version++; "ROBOMORI_DONE" }
                "stat -L -c %s" in command && partial in command -> partialContent?.toByteArray()?.size?.toString().orEmpty()
                "stat -L -c %s" in command -> content.toByteArray().size.toString()
                else -> ""
            }
        }
        override fun pull(serial: String, command: String, destination: File) = destination.writeBytes(content.toByteArray())
        override fun push(serial: String, command: String, source: File) { pushes += command; partialContent = source.readText() }
    }

    private class FakeDevice(private val pulled: String = "", private val answer: (String) -> String) : DeviceFileTransport {
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        val pushes = mutableListOf<String>()
        override fun shell(serial: String, command: String) = answer(command).also { commands += command }
        override fun pull(serial: String, command: String, destination: File) = destination.writeText(pulled)
        override fun push(serial: String, command: String, source: File) { pushes += command }
    }
}

/** The names of the selected files, which the selection holds by path. */
private val DeviceFilesState.selectedNames get() = selection.mapTo(mutableSetOf()) { it.substringAfterLast('/') }
