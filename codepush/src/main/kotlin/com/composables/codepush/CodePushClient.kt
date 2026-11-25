package com.composables.codepush

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Used by the running app: reports which update is running, confirms it came up, and downloads newer updates
 * for the next launch. Calls block; run them off the UI thread.
 *
 * The server lays updates out under [CodePushConfig.appUrl]:
 * ```
 * <base>/latest                 the newest patch number for that installed version
 * <base>/<patch>/manifest       the update's manifest
 * <base>/<patch>/manifest.sig   its Ed25519 signature
 * files/<sha256>                the jars, by content
 * ```
 */
class CodePushClient(
    private val config: CodePushConfig,
    private val fetch: (String) -> ByteArray? = HttpFetch(),
) {
    private val store = PatchStore(config.dataDirectory)

    /** The update this process runs, or null when it runs the installed code. */
    val runningPatch: Int? get() = System.getProperty(CodePushLauncher.PATCH_PROPERTY)?.toIntOrNull()

    /** The downloaded update the next launch will run, when it's newer than the one running now; null otherwise. */
    fun readyPatch(): Int? {
        val bad = store.bad(config.base)
        val next = store.installed(config.appId, config.base, config.publicKey).firstOrNull { it.patch !in bad }?.patch ?: return null
        return next.takeIf { it > (runningPatch ?: 0) }
    }

    /** Call once the app is up and working: the running update stays in use on later launches. */
    fun markHealthy() = store.markHealthy(config.base)

    /**
     * Downloads the newest update for the installed version that's newer than what's running or already downloaded,
     * hasn't failed before, and whose publish time [eligible] accepts, such as one within a license's update period.
     * Returns its number when one was downloaded, for the next launch.
     */
    fun downloadLatest(eligible: (Instant) -> Boolean = { true }): Int? {
        val latest = fetch(config.appUrl + "${config.base}/latest")?.decodeToString()?.trim()?.toIntOrNull() ?: return null
        val have = maxOf(runningPatch ?: 0, store.installed(config.appId, config.base, config.publicKey).firstOrNull()?.patch ?: 0)
        val bad = store.bad(config.base)
        for (patch in latest downTo have + 1) {
            if (patch in bad) continue
            val (manifest, signature) = fetchManifest(patch) ?: continue
            if (!eligible(Instant.ofEpochSecond(manifest.published))) continue
            manifest.replacements.filter { store.file(it.sha256).length() != it.size }.forEach { jar ->
                val bytes = fetch(config.appUrl + "files/${jar.sha256}") ?: error("Missing ${jar.module} jar on the server")
                store.storeFile(jar.sha256, bytes)
            }
            store.install(manifest, signature)
            store.prune(config.base)
            return manifest.patch
        }
        return null
    }

    private fun fetchManifest(patch: Int): Pair<Manifest, String>? {
        val manifestBytes = fetch(config.appUrl + "${config.base}/$patch/manifest") ?: return null
        val signature = fetch(config.appUrl + "${config.base}/$patch/manifest.sig")?.decodeToString() ?: return null
        check(Signing.verify(manifestBytes, signature, config.publicKey)) { "Update $patch is not signed by this app's key" }
        val manifest = Manifest.parse(manifestBytes.decodeToString())
        check(manifest.app == config.appId && manifest.base == config.base && manifest.patch == patch) {
            "Update $patch is for ${manifest.app} ${manifest.base}"
        }
        return manifest to signature
    }
}

/** GETs a URL: its bytes, or null when the server says it isn't there. Other failures throw. */
class HttpFetch(private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
    .followRedirects(HttpClient.Redirect.NORMAL).build()) : (String) -> ByteArray? {
    override fun invoke(url: String): ByteArray? {
        val response = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(2))
            .header("Cache-Control", "no-cache").build(), HttpResponse.BodyHandlers.ofByteArray())
        return when (response.statusCode()) {
            200 -> response.body()
            404, 403 -> null
            else -> error("GET $url failed with ${response.statusCode()}")
        }
    }
}
