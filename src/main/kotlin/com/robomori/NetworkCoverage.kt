package com.robomori

/** Whose traffic the Network page can read on a device, shown above the requests. */
internal sealed interface NetworkCoverage {
    /** Robomori's certificate is among the system's, so every app's HTTPS is read, except apps that pin theirs. */
    data object AllApps : NetworkCoverage
    /**
     * Only debuggable apps trust Robomori's certificate. Other apps' connections are listed by site, still encrypted.
     * [canCreateGoogleApisCopy] when the device is a Google Play emulator, which can't be rooted, so the same emulator
     * with Google APIs would read every app.
     */
    data class DebugAppsOnly(val canCreateGoogleApisCopy: Boolean) : NetworkCoverage
}

/** What [routing] reads, or null until recording has started. */
internal fun networkCoverage(routing: NetworkRouting, canCreateGoogleApisCopy: Boolean): NetworkCoverage? =
    when ((routing as? NetworkRouting.On)?.trust) {
        CertificateTrust.System -> NetworkCoverage.AllApps
        CertificateTrust.AppConfiguration -> NetworkCoverage.DebugAppsOnly(canCreateGoogleApisCopy)
        null -> null
    }

/**
 * An emulator like [device], with the same hardware profile and Android version, but a Google APIs image in place of
 * its Google Play one, named like "Pixel 9 (Google APIs)". Null when [device] isn't a Google Play emulator made from
 * one of Robomori's profiles. [existing] are the emulators' names, which the copy's must differ from.
 */
internal fun googleApisCopy(device: Device, existing: Collection<String>): CreateSpec? {
    val profile = HardwareProfiles.all.find { it.id == device.config["robomori.profile"] } ?: return null
    // Such as system-images/android-37/google_apis_playstore/arm64-v8a/.
    val parts = device.config["image.sysdir.1"]?.replace('\\', '/')?.trim('/')?.split('/') ?: return null
    if (parts.size != 4 || parts[0] != "system-images" || !parts[2].startsWith("google_apis_playstore")) return null
    val image = SystemImage(listOf(parts[0], parts[1], parts[2].replace("google_apis_playstore", "google_apis"), parts[3]).joinToString(";"),
        "Google APIs", installed = false)
    val base = "${device.displayName} (Google APIs)"
    val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }
        .first { candidate -> existing.none { it.equals(candidate, ignoreCase = true) } }
    return runCatching { profile.createSpec(name, image, existing) }.getOrNull()
}
