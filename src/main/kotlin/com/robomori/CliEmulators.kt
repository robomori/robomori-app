package com.robomori

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

internal fun Cli.emulatorCreate(options: Options) {
    options.expect(0..1, values = setOf("name", "api"), flags = setOf("start", "play-store"))
    val profile = options.positional.firstOrNull()?.let(::profile) ?: HardwareProfiles.all.first { it.type == DeviceType.PHONE }
    val playStore = options.flag("play-store")
    if (playStore && !profile.type.offersPlayStore) throw UsageException("--play-store is for phones, tablets and foldables.")
    val existing = android.devices().map(Device::displayName)
    val name = options.value("name")?.trim() ?: suggestedDeviceName(profile.name, existing)
    creationNameError(name, existing)?.let { throw IllegalArgumentException(nameMessage(it, name)) }
    val images = creationImages(profile.type, catalog(refresh = false), playStore = playStore)
    val api = options.value("api")
    val image = if (api == null) images.firstOrNull { it.installed } ?: images.firstOrNull()
        else images.firstOrNull { creationApiLabel(it) == apiLabel(api) }
    image ?: throw IllegalArgumentException(when {
        api == null -> "No compatible system image for ${profile.name} on this computer."
        images.isEmpty() -> "No Android $api image for ${profile.name} on this computer."
        else -> "No Android $api image for ${profile.name}. Available: ${images.map(::creationApiLabel).distinct().joinToString(", ")}. " +
            "See 'robomori image list --profile ${profile.id}'."
    })
    val spec = profile.createSpec(name, image, android.devices().map(Device::name))
    check(storagePlanner(spec, android.settings).canCreate) { "Not enough available disk space to create this emulator." }
    if (!image.installed) progress("Downloading ${image.platformTitle}…")
    val created = android.create(spec, AtomicBoolean()) { }
    val device = android.devices().first { it.name == created.name }
    val serial = if (options.flag("start")) startAndWait(device) else null
    if (jsonOutput) printJson(emulatorJson(device, serial)) else out.println(listOfNotNull(device.name, serial).joinToString("\n"))
}

internal fun Cli.emulatorStart(options: Options) {
    options.expect(1..1, flags = setOf("cold", "no-wait"), what = "emulator")
    val device = emulator(options.positional.single())
    android.running()[device.name]?.let { serial ->
        return if (jsonOutput) printJson(emulatorJson(device, serial)) else out.println(serial)
    }
    val serial = if (options.flag("no-wait")) { android.launch(device, coldBoot = options.flag("cold"), media = mediaStore.load(), graphics = graphicsStore.load()); null }
        else startAndWait(device, coldBoot = options.flag("cold"))
    if (jsonOutput) printJson(emulatorJson(device, serial)) else serial?.let(out::println)
}

internal fun Cli.emulatorOpen(options: Options) {
    options.expect(1..1, what = "emulator")
    val device = emulator(options.positional.single())
    // Shows the window first, so the emulator appears while it boots instead of only once Android is up.
    windows.request(device.name)
    if (!windows.appRunning()) check(windows.launchApp()) { "Open the Robomori app to see ${device.displayName}." }
    val serial = android.running()[device.name] ?: startAndWait(device)
    if (jsonOutput) printJson(emulatorJson(device, serial)) else out.println(serial)
}

internal fun Cli.emulatorStop(options: Options) {
    options.expect(1..1, what = "emulator")
    val device = emulator(options.positional.single())
    requireNotClaimedByOthers(device.name)
    android.running()[device.name]?.let { serial ->
        android.stop(serial)
        val deadline = System.currentTimeMillis() + 30_000
        while (device.name in android.running()) {
            check(System.currentTimeMillis() < deadline) { "${device.displayName} did not stop." }
            pause(500)
        }
    }
    if (jsonOutput) printJson(emulatorJson(device, null))
}

internal fun Cli.emulatorDelete(options: Options) {
    options.expect(1..1, what = "emulator")
    val device = emulator(options.positional.single())
    check(device.name !in android.running()) { "${device.name} is running. Stop it with 'robomori emulator stop ${shellWord(device.name)}' first." }
    android.delete(device)
}

internal fun Cli.emulatorWipe(options: Options) {
    options.expect(1..1, what = "emulator")
    val device = emulator(options.positional.single())
    check(device.name !in android.running()) {
        "${device.name} is running. Stop it with 'robomori emulator stop ${shellWord(device.name)}', then wipe it."
    }
    val serial = startAndWait(device, wipeData = true)
    if (jsonOutput) printJson(emulatorJson(device, serial)) else out.println(serial)
}

internal fun Cli.profileList(options: Options) {
    options.expect()
    val profiles = HardwareProfiles.all
    if (jsonOutput) return printJson(JsonArray(profiles.map { profile ->
        buildJsonObject { put("id", profile.id); put("name", profile.name); put("type", profile.type.label) }
    }))
    printTable(listOf("ID", "NAME", "TYPE"), profiles.map { listOf(it.id, it.name, it.type.label) })
}

internal fun Cli.imageList(options: Options) {
    options.expect(values = setOf("profile"), flags = setOf("refresh"))
    val profile = options.value("profile")?.let(::profile)
    val images = creationImages(profile?.type ?: DeviceType.PHONE, catalog(options.flag("refresh")))
    if (jsonOutput) return printJson(JsonArray(images.map { image ->
        buildJsonObject {
            put("api", creationApiLabel(image)); put("android", image.platformTitle); put("services", image.services)
            put("installed", image.installed); put("package", image.path)
        }
    }))
    if (images.isEmpty()) return progress("No compatible system images for this computer.")
    printTable(listOf("API", "ANDROID", "SERVICES", "INSTALLED", "PACKAGE"), images.map {
        listOf(creationApiLabel(it), it.platformTitle, it.services, if (it.installed) "yes" else "no", it.path)
    })
}

/** Starts [device] and waits until Android has booted, as the app does, then returns its adb serial. */
private fun Cli.startAndWait(device: Device, coldBoot: Boolean = false, wipeData: Boolean = false): String {
    progress(if (wipeData) "Wiping and starting ${device.displayName}…" else "Starting ${device.displayName}…")
    android.launch(device, coldBoot, wipeData, mediaStore.load(), graphicsStore.load())
    val deadline = System.currentTimeMillis() + bootTimeoutMillis
    fun waitFor(what: String, condition: () -> Boolean) {
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.currentTimeMillis() < deadline) { "${device.displayName} did not $what in time." }
            pause(1000)
        }
    }
    var serial: String? = null
    waitFor("start") { android.running()[device.name]?.also { serial = it } != null }
    waitFor("finish booting") { android.bootCompleted(serial!!) }
    if (device.needsInitialSetup) android.configureInitialSettings(device, serial!!)
    // The app does this too when it's open; the command can't count on that.
    runCatching { android.reverseLocalhostPorts(serial!!, localhostPorts.load()) }
    return serial!!
}

/** The system image list: the app's saved copy when it's fresh enough, otherwise listed again by sdkmanager. */
private fun Cli.catalog(refresh: Boolean): List<SystemImage> {
    val cached = catalogCache.read(android.settings)?.takeIf { !refresh && it.age < CatalogMaxAge }
    val text = cached?.text ?: android.catalog().also { catalogCache.write(android.settings, it) }
    val installed = runCatching { android.installedImages() }.getOrNull()
    return parseImages(text).let { known -> installed?.let { withInstalled(known, it) } ?: known }
}

private fun profile(id: String) = HardwareProfiles.all.firstOrNull { it.id.equals(id, true) }
    ?: throw UsageException("Unknown profile: $id.${suggestion(id, HardwareProfiles.all.map { it.id })}", "See 'robomori profile list'.")

private fun nameMessage(issue: CreationNameIssue, name: String) = when (issue) {
    CreationNameIssue.Empty -> "Enter a name for the emulator."
    CreationNameIssue.TooLong -> "Use a name of 100 characters or fewer."
    CreationNameIssue.ControlCharacters -> "Remove control characters from the name."
    CreationNameIssue.Duplicate -> "An emulator named $name already exists."
}

private fun emulatorJson(device: Device, serial: String?) = buildJsonObject {
    put("name", device.name)
    put("displayName", device.displayName)
    put("kind", "emulator")
    put("type", device.type.label)
    put("api", apiLabel(device.api))
    put("android", device.platformTitle)
    put("state", if (serial != null) "running" else "not running")
    put("running", serial != null)
    put("serial", serial)
}
