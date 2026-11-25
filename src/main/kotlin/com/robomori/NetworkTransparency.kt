package com.robomori

import org.bouncycastle.jce.ECNamedCurveTable
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A certificate transparency log that only Robomori signs for. From Android 17, apps require each site's certificate
 * to carry timestamps from logs the device knows, so the certificates Robomori makes carry timestamps from two of
 * these, and the device's list of logs learns them while it is inspected.
 */
internal class TransparencyLog(private val privateKey: PrivateKey, val publicKey: PublicKey, val operator: String) {
    /** The SHA-256 of the log's public key, which names it in timestamps and in the device's list. */
    val id: ByteArray = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)

    /**
     * A signed certificate timestamp, as RFC 6962 lays it out, for a certificate whose to-be-signed part is
     * [precertificate] without the timestamps, issued by the authority whose public key hashes to [issuerKeyHash].
     */
    fun timestamp(precertificate: ByteArray, issuerKeyHash: ByteArray, at: Instant): ByteArray {
        val millis = at.toEpochMilli()
        val signed = ByteArrayOutputStream().apply {
            write(0) // Version 1.
            write(0) // A certificate timestamp.
            writeLong(millis)
            writeShort(1) // A precertificate entry.
            write(issuerKeyHash)
            write(precertificate.size shr 16); write(precertificate.size shr 8); write(precertificate.size)
            write(precertificate)
            writeShort(0) // No extensions.
        }.toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(signed); sign() }
        return ByteArrayOutputStream().apply {
            write(0)
            write(id)
            writeLong(millis)
            writeShort(0)
            write(4) // SHA-256.
            write(3) // ECDSA.
            writeShort(signature.size)
            write(signature)
        }.toByteArray()
    }

    companion object {
        const val OperatorPrefix = "Robomori CT Operator"

        /**
         * Two logs made from the authority's public key, so the same authority always has the same logs, as
         * HTTP Toolkit derives its own. Two from different operators are what a certificate of under 180 days needs.
         */
        fun derive(authorityKey: PublicKey): List<TransparencyLog> = (1..2).map { index ->
            val curve = ECNamedCurveTable.getParameterSpec("secp256r1")
            val scalar = BigInteger(1, hkdf(authorityKey.encoded, "robomori-ct".toByteArray(), "log-operator-$index".toByteArray()))
                .mod(curve.n - BigInteger.ONE) + BigInteger.ONE
            val point = curve.g.multiply(scalar).normalize()
            val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val keys = KeyFactory.getInstance("EC")
            TransparencyLog(keys.generatePrivate(ECPrivateKeySpec(scalar, parameters)),
                keys.generatePublic(ECPublicKeySpec(ECPoint(point.affineXCoord.toBigInteger(), point.affineYCoord.toBigInteger()), parameters)),
                "$OperatorPrefix $index")
        }

        private fun hkdf(input: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(input)
            return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }.doFinal(info + 1)
        }
    }
}

private fun ByteArrayOutputStream.writeShort(value: Int) { write(value shr 8); write(value) }
private fun ByteArrayOutputStream.writeLong(value: Long) { for (shift in 56 downTo 0 step 8) write((value shr shift).toInt()) }

/** The list of transparency logs an Android 17 device trusts, read from and written as its `log_list.ctfb`. */
internal data class TransparencyLogList(val versionMajor: Long, val versionMinor: Long, val timestamp: Long,
                                        val logs: List<Entry>) {
    /** One log; [type] and [state] are Conscrypt's numbers for them, such as 1 for an RFC 6962 log and 3 for usable. */
    data class Entry(val id: String, val publicKey: ByteArray, val operator: String, val type: Int, val state: Int,
                     val stateTimestamp: Long) {
        override fun equals(other: Any?) = other is Entry && id == other.id && publicKey.contentEquals(other.publicKey) &&
            operator == other.operator && type == other.type && state == other.state && stateTimestamp == other.stateTimestamp
        override fun hashCode() = id.hashCode()
    }

    /** This list with [logs] in place of any Robomori added before, such as for an earlier authority. */
    fun including(logs: List<TransparencyLog>, usableSince: Instant): TransparencyLogList {
        val ours = logs.map { log ->
            Entry(java.util.Base64.getEncoder().encodeToString(log.id), log.publicKey.encoded, log.operator, type = 1, state = 3,
                stateTimestamp = usableSince.toEpochMilli())
        }
        val ids = ours.map { it.id }.toSet()
        return copy(logs = this.logs.filterNot { it.operator.startsWith(TransparencyLog.OperatorPrefix) || it.id in ids } + ours)
    }

    /**
     * The list as a FlatBuffer with the `CTFB` identifier. Conscrypt looks logs up by a binary search on their ID,
     * so they are written in its order.
     */
    fun encode(): ByteArray {
        val out = FlatWriter()
        out.u32(0)
        out.bytes("CTFB".toByteArray())
        // The list's table: its vtable, then the table, laid out so its longs fall on 8-byte boundaries.
        val listVtable = out.vtable(32, 4, 12, 20, 28)
        out.alignTo(8, remainder = 4)
        val list = out.position
        out.patchU32(0, list)
        out.i32(list - listVtable)
        out.i64(versionMajor); out.i64(versionMinor); out.i64(timestamp)
        val logsField = out.position
        out.u32(0)
        out.patchOffset(logsField, out.position)
        val sorted = logs.sortedBy { it.id }
        out.u32(sorted.size)
        val slots = sorted.map { out.position.also { out.u32(0) } }
        for ((log, slot) in sorted.zip(slots)) {
            val vtable = out.vtable(28, 4, 8, 12, 16, 17, 20)
            out.alignTo(8, remainder = 4)
            val table = out.position
            out.patchOffset(slot, table)
            out.i32(table - vtable)
            val fields = List(3) { out.position.also { out.u32(0) } }
            out.u8(log.type); out.u8(log.state); out.u8(0); out.u8(0)
            out.i64(log.stateTimestamp)
            for ((field, value) in fields.zip(listOf(log.id.toByteArray(), log.publicKey, log.operator.toByteArray()))) {
                out.alignTo(4)
                out.patchOffset(field, out.position)
                out.u32(value.size)
                out.bytes(value)
                // Strings end in a zero; it does no harm after the key's bytes.
                out.u8(0)
            }
        }
        return out.toByteArray()
    }

    companion object {
        /** Reads a `log_list.ctfb`, or returns null when it isn't one. */
        fun decode(bytes: ByteArray): TransparencyLogList? = runCatching {
            if (bytes.size < 8 || String(bytes, 4, 4, Charsets.US_ASCII) != "CTFB") return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun field(table: Int, index: Int): Int {
                val vtable = table - buffer.getInt(table)
                val slot = 4 + index * 2
                if (slot >= buffer.getShort(vtable).toInt()) return 0
                val offset = buffer.getShort(vtable + slot).toInt() and 0xffff
                return if (offset == 0) 0 else table + offset
            }
            fun long(table: Int, index: Int) = field(table, index).let { if (it == 0) 0L else buffer.getLong(it) }
            fun byte(table: Int, index: Int) = field(table, index).let { if (it == 0) 0 else buffer.get(it).toInt() }
            fun blob(table: Int, index: Int): ByteArray? {
                val at = field(table, index).takeIf { it != 0 } ?: return null
                val start = at + buffer.getInt(at)
                return bytes.copyOfRange(start + 4, start + 4 + buffer.getInt(start))
            }
            val list = buffer.getInt(0)
            val vector = field(list, 3).let { it + buffer.getInt(it) }
            val logs = (0 until buffer.getInt(vector)).mapNotNull { index ->
                val slot = vector + 4 + index * 4
                val table = slot + buffer.getInt(slot)
                Entry(String(blob(table, 0) ?: return@mapNotNull null), blob(table, 1) ?: return@mapNotNull null,
                    String(blob(table, 2) ?: return@mapNotNull null), byte(table, 3), byte(table, 4), long(table, 5))
            }
            TransparencyLogList(long(list, 0), long(list, 1), long(list, 2), logs)
        }.getOrNull()
    }
}

/** Writes a FlatBuffer front to back, so every offset points forward, as FlatBuffers require. */
private class FlatWriter {
    private var buffer = ByteBuffer.allocate(32 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    val position get() = buffer.position()

    private fun room(bytes: Int) {
        if (buffer.remaining() >= bytes) return
        val bigger = ByteBuffer.allocate(maxOf(buffer.capacity() * 2, buffer.position() + bytes)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.flip()
        bigger.put(buffer)
        buffer = bigger
    }

    fun u8(value: Int) { room(1); buffer.put(value.toByte()) }
    fun u16(value: Int) { room(2); buffer.putShort(value.toShort()) }
    fun u32(value: Int) { room(4); buffer.putInt(value) }
    fun i32(value: Int) = u32(value)
    fun i64(value: Long) { room(8); buffer.putLong(value) }
    fun bytes(value: ByteArray) { room(value.size); buffer.put(value) }
    fun alignTo(alignment: Int, remainder: Int = 0) { while (position % alignment != remainder) u8(0) }
    fun patchU32(at: Int, value: Int) { buffer.putInt(at, value) }
    /** Points the offset at [at] to [target], which comes after it. */
    fun patchOffset(at: Int, target: Int) = patchU32(at, target - at)

    /** A vtable for a table of [tableSize] bytes whose fields sit at [offsets] from its start; returns where it is. */
    fun vtable(tableSize: Int, vararg offsets: Int): Int {
        alignTo(2)
        val at = position
        u16(4 + offsets.size * 2)
        u16(tableSize)
        offsets.forEach(::u16)
        return at
    }

    fun toByteArray(): ByteArray = buffer.array().copyOf(position)
}
