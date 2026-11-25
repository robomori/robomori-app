package com.robomori

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.Extensions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkTransparencyTest {
    @TempDir lateinit var directory: File

    @Test fun `a site's certificate carries timestamps that the authority's two logs signed`() {
        val authority = NetworkCertificateAuthority(directory)
        val leaf = authority.sign(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair(), "example.com")
        leaf.verify(authority.certificate.publicKey)

        val certificate = Certificate.getInstance(leaf.encoded)
        val oid = org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.4.2")
        val list = ASN1OctetString.getInstance(certificate.tbsCertificate.extensions.getExtension(oid).extnValue.octets).octets
        // What the logs signed: the certificate as it is without the timestamps.
        val fields = ASN1Sequence.getInstance(certificate.tbsCertificate.encoded).toArray()
        val precertificate = DERSequence(ASN1EncodableVector().apply {
            for (field in fields) {
                if (field is ASN1TaggedObject && field.tagNo == 3) {
                    val kept = Extensions.getInstance(field.baseObject).extensionOIDs.filter { it != oid }
                        .map { certificate.tbsCertificate.extensions.getExtension(it) }
                    add(DERTaggedObject(true, 3, Extensions(kept.toTypedArray())))
                } else add(field)
            }
        }).encoded
        val issuerKeyHash = MessageDigest.getInstance("SHA-256").digest(authority.certificate.publicKey.encoded)

        val input = DataInputStream(list.inputStream())
        assertEquals(list.size - 2, input.readUnsignedShort())
        val logs = authority.transparencyLogs
        for (log in logs) {
            val timestamp = ByteArray(input.readUnsignedShort()).also(input::readFully)
            val sct = DataInputStream(timestamp.inputStream())
            assertEquals(0, sct.read())
            assertContentEquals(log.id, ByteArray(32).also(sct::readFully))
            val millis = sct.readLong()
            assertEquals(0, sct.readUnsignedShort())
            assertEquals(4, sct.read())
            assertEquals(3, sct.read())
            val signature = ByteArray(sct.readUnsignedShort()).also(sct::readFully)
            val signed = ByteArrayOutputStream().apply {
                write(0); write(0)
                write(java.nio.ByteBuffer.allocate(8).putLong(millis).array())
                write(0); write(1)
                write(issuerKeyHash)
                write(precertificate.size shr 16); write(precertificate.size shr 8); write(precertificate.size)
                write(precertificate)
                write(0); write(0)
            }.toByteArray()
            assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(log.publicKey); update(signed); verify(signature) })
            assertTrue(millis <= Instant.now().toEpochMilli())
        }
        assertEquals(-1, input.read())
        assertNotEquals(logs[0].operator, logs[1].operator)
        // Under 180 days, for which two timestamps are enough.
        assertTrue(leaf.notAfter.time - leaf.notBefore.time < 180L * 24 * 60 * 60 * 1000)
    }

    @Test fun `the same authority always has the same logs`() {
        val first = NetworkCertificateAuthority(directory).transparencyLogs
        val again = NetworkCertificateAuthority(directory).transparencyLogs
        assertEquals(first.map { it.id.toList() }, again.map { it.id.toList() })
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(first[0].publicKey.encoded), first[0].id)
    }

    @Test fun `adds the authority's logs to the device's list in place of ones added before`() {
        val authority = NetworkCertificateAuthority(directory)
        val google = TransparencyLogList.Entry("b".repeat(43) + "=", byteArrayOf(1, 2, 3), "Google", type = 2, state = 3, stateTimestamp = 5)
        val stale = TransparencyLogList.Entry("a".repeat(43) + "=", byteArrayOf(4), "${TransparencyLog.OperatorPrefix} 1", 1, 3, 6)
        val list = TransparencyLogList(98, 1, 1_000, listOf(google, stale))

        val updated = list.including(authority.transparencyLogs, Instant.ofEpochMilli(42))

        assertEquals(listOf(google) + authority.transparencyLogs.map { log ->
            TransparencyLogList.Entry(Base64.getEncoder().encodeToString(log.id), log.publicKey.encoded, log.operator, 1, 3, 42)
        }, updated.logs)
        assertEquals(updated, updated.including(authority.transparencyLogs, Instant.ofEpochMilli(42)))
    }

    @Test fun `writes the list as Android 17 reads it, ordered by log ID`() {
        val logs = listOf(
            TransparencyLogList.Entry("Zm9v", byteArrayOf(9, 8, 7), "Zed", 1, 3, 1_700_000_000_000),
            TransparencyLogList.Entry("Abcd", ByteArray(91) { it.toByte() }, "Ace", 2, 5, 1_600_000_000_000),
        )
        val list = TransparencyLogList(98, 1, 1_763_000_000_000, logs)

        val bytes = list.encode()

        assertEquals("CTFB", String(bytes, 4, 4))
        assertEquals(list.copy(logs = logs.sortedBy { it.id }), TransparencyLogList.decode(bytes))
        assertNull(TransparencyLogList.decode("{}".toByteArray()))
    }

    @Test fun `reads a list laid out as the device's is`() {
        // The header of an Android 17 emulator's log_list.ctfb: version 98.1 and its timestamp, with no logs.
        val bytes = byteArrayOf(
            0x18, 0, 0, 0, 'C'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), 0, 0, 0, 0,
            0x0c, 0, 0x24, 0, 0x18, 0, 0x10, 0, 0x08, 0, 0x04, 0,
            0x0c, 0, 0, 0, 0x20, 0, 0, 0, 0xd0.toByte(), 0xc9.toByte(), 0x2d, 0xf9.toByte(), 0xa1.toByte(), 0x01, 0, 0,
            1, 0, 0, 0, 0, 0, 0, 0, 0x62, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        val list = TransparencyLogList.decode(bytes)

        assertEquals(TransparencyLogList(98, 1, 0x1a1f92dc9d0, emptyList()), list)
    }
}
