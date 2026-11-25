package com.robomori

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

internal const val LICENSE_PRODUCT = "robomori"
internal const val LICENSE_ORIGIN = "https://licenses.robomori.com"
internal const val LICENSE_PUBLIC_KEY = "0EBwSuYuIW0hRaBXAF/aQ6vc2usFCZYwEGiQhAJmZQc="

/** How long Robomori works without a license. */
internal const val TRIAL_DAYS = 7L

internal class LicenseServiceException(val statusCode: Int) : IllegalStateException()

sealed interface LicenseOperation {
    data object Idle : LicenseOperation
    data object Activating : LicenseOperation
    data object Unlinking : LicenseOperation
    data object Activated : LicenseOperation
    data object MissingKey : LicenseOperation
    data class ActivationFailed(val cause: Throwable) : LicenseOperation
    data class UnlinkFailed(val cause: Throwable) : LicenseOperation
}

internal fun verifyLicense(receipt: JsonObject, deviceId: String, publicKey: String = licensePublicKey, issuer: String = licenseOrigin): JsonObject {
    require(receipt["algorithm"]?.jsonPrimitive?.content == "Ed25519")
    require(receipt["key_id"]?.jsonPrimitive?.content == "primary")
    val payload = receipt.getValue("payload").jsonPrimitive.content
    // RFC 8410 SubjectPublicKeyInfo prefix for a raw 32-byte Ed25519 public key.
    val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + Base64.getDecoder().decode(publicKey)))
    val verifier = Signature.getInstance("Ed25519").apply { initVerify(key); update(payload.toByteArray(Charsets.UTF_8)) }
    require(verifier.verify(Base64.getDecoder().decode(receipt.getValue("signature").jsonPrimitive.content)))
    val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).toString(Charsets.UTF_8)).jsonObject
    val hash = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray()).joinToString("") { "%02x".format(it) }
    require(claims["schema"]?.jsonPrimitive?.int == 1 && claims["issuer"]?.jsonPrimitive?.content == issuer)
    require(claims["product"]?.jsonPrimitive?.content == LICENSE_PRODUCT && claims["device_hash"]?.jsonPrimitive?.content == hash)
    require(claims["perpetual"]?.jsonPrimitive?.boolean == true)
    require(claims.getValue("updates_until").jsonPrimitive.long > 0)
    return claims
}

internal fun atomicWrite(file: File, text: String) {
    Files.createDirectories(file.parentFile.toPath())
    val tmp = Files.createTempFile(file.parentFile.toPath(), ".robomori-", ".tmp")
    try {
        Files.writeString(tmp, text)
        Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(tmp) }
}

internal suspend fun licenseRequest(path: String, input: JsonObject, origin: String = licenseOrigin): JsonObject = withContext(Dispatchers.IO) {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val request = HttpRequest.newBuilder(URI("$origin$path")).timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json").header("User-Agent", "Robomori/$APP_DISPLAY_VERSION")
        .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    val bytes = response.body().use { it.readNBytes(65537) }
    check(bytes.size <= 65536) { "The server response was too large. Please try again later." }
    if (response.statusCode() !in 200..299) {
        throw LicenseServiceException(response.statusCode())
    }
    Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
}

class AppLicense internal constructor(private val scope: CoroutineScope, directory: File = licenseDirectory,
                                     private val origin: String = licenseOrigin, private val publicKey: String = licensePublicKey,
                                     private val request: suspend (String, JsonObject) -> JsonObject = { path, input -> licenseRequest(path, input, origin) },
                                     private val now: () -> Instant = Instant::now) {
    private val deviceFile = File(directory, "license-device-id")
    private val receiptFile = File(directory, "license-receipt.json")
    private data class VerifiedLicense(val receipt: JsonObject, val updatesUntil: Instant)
    private fun verified(receipt: JsonObject, id: String): VerifiedLicense {
        val claims = verifyLicense(receipt, id, publicKey, origin)
        return VerifiedLicense(receipt, Instant.ofEpochSecond(claims.getValue("updates_until").jsonPrimitive.long))
    }
    private fun deviceId(): String = if (deviceFile.exists()) deviceFile.readText().trim().also { require(it.isNotBlank()) }
        else UUID.randomUUID().toString().also { atomicWrite(deviceFile, it) }
    private var verifiedLicense by mutableStateOf(runCatching {
        verified(Json.parseToJsonElement(receiptFile.readText()).jsonObject, deviceId())
    }.getOrNull()); private set
    internal val receipt get() = verifiedLicense?.receipt
    var operation by mutableStateOf<LicenseOperation>(LicenseOperation.Idle); private set
    val working get() = operation == LicenseOperation.Activating || operation == LicenseOperation.Unlinking
    /** Forgets a finished activation or unlink outcome, so it does not show again later. */
    fun clearOutcome() { if (!working) operation = LicenseOperation.Idle }
    val licensed: Boolean get() = receipt != null && !pretendUnlicensed

    private val pretendUnlicensedFile = File(directory, "pretend-unlicensed")
    /**
     * Development builds only: acts as if this computer had no license, to see what people without one see. Kept as a
     * file next to the license, so the setting lasts across restarts and the command line built alongside follows it.
     */
    private var pretending by mutableStateOf(pretendUnlicensedFile.exists())
    var pretendUnlicensed: Boolean
        get() = pretending
        set(on) {
            runCatching { if (on) atomicWrite(pretendUnlicensedFile, "") else pretendUnlicensedFile.delete() }
            pretending = on
        }
    /**
     * The free trial: everything works for [TRIAL_DAYS] days from the first time Robomori runs on this computer, in the
     * app or on the command line. After that, Robomori needs a license.
     */
    private val trialFile = File(directory, "trial-started")
    val trialEnds: Instant by lazy {
        val started = runCatching { Instant.parse(trialFile.readText().trim()) }.getOrElse {
            now().also { runCatching { atomicWrite(trialFile, it.toString()) } }
        }
        started.plus(Duration.ofDays(TRIAL_DAYS))
    }
    /** The time the trial is judged against, which [tick] moves on so the app locks when the trial ends. */
    private var clock by mutableStateOf(now())
    fun tick() { clock = now() }
    private val trialEnded get() = pretendTrialEnded || !clock.isBefore(trialEnds)
    /** Whether Robomori may be used: with a license, or during the trial. */
    val unlocked: Boolean get() = licensed || !trialEnded
    /** Whole days left in the trial, counting today; 0 once it has ended. */
    val trialDaysLeft: Int get() = if (trialEnded) 0
        else ((Duration.between(clock, trialEnds).toHours() + 23) / 24).toInt()

    private val pretendTrialEndedFile = File(directory, "pretend-trial-ended")
    /** Development builds only: acts as if the trial had ended, like [pretendUnlicensed]. */
    private var trialOver by mutableStateOf(pretendTrialEndedFile.exists())
    var pretendTrialEnded: Boolean
        get() = trialOver
        set(on) {
            runCatching { if (on) atomicWrite(pretendTrialEndedFile, "") else pretendTrialEndedFile.delete() }
            trialOver = on
        }

    val updatesUntil: Instant? get() = verifiedLicense?.updatesUntil
    val updatesThrough: String? get() = updatesUntil?.atZone(ZoneOffset.UTC)?.toLocalDate()?.toString()

    internal suspend fun refresh() {
        val current = receipt ?: return
        if (working) return
        try {
            val saved = withContext(Dispatchers.IO) {
                val id = deviceId()
                val response = request("/api/v1/refresh", buildJsonObject {
                    put("receipt", current); put("product", LICENSE_PRODUCT); put("device_id", id)
                }).getValue("receipt").jsonObject
                verified(response, id)
            }
            if (receipt != current || working || updatesUntil?.let { !saved.updatesUntil.isAfter(it) } != false) return
            atomicWrite(receiptFile, saved.receipt.toString())
            verifiedLicense = saved
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep the last verified offline receipt. */ }
    }

    /** Called after a key activates this computer. */
    var onActivated: () -> Unit = {}

    fun activate(key: String) {
        if (working) return
        if (key.isBlank()) { operation = LicenseOperation.MissingKey; return }
        operation = LicenseOperation.Activating
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val id = deviceId()
                    val response = request("/api/v1/activate", buildJsonObject {
                        put("license_key", key.trim()); put("product", LICENSE_PRODUCT); put("device_id", id)
                        put("device_name", "${HostOs.detect().name.lowercase().replaceFirstChar(Char::uppercase)} computer")
                    }).getValue("receipt").jsonObject
                    val license = verified(response, id)
                    atomicWrite(receiptFile, response.toString())
                    license
                }
                verifiedLicense = saved; operation = LicenseOperation.Activated
                onActivated()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                operation = LicenseOperation.ActivationFailed(e)
            }
            finally { if (working) operation = LicenseOperation.Idle }
        }
    }

    fun deactivate() {
        val current = receipt ?: return
        if (working) return
        operation = LicenseOperation.Unlinking
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    request("/api/v1/deactivate", buildJsonObject {
                        put("receipt", current); put("product", LICENSE_PRODUCT); put("device_id", deviceId())
                    })
                    Files.deleteIfExists(receiptFile.toPath())
                }
                verifiedLicense = null; operation = LicenseOperation.Idle
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                operation = LicenseOperation.UnlinkFailed(e)
            }
            finally { if (working) operation = LicenseOperation.Idle }
        }
    }
}
