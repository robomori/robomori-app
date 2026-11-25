package com.robomori

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Robomori's own certificate authority, made once per computer and kept in [directory], which signs a certificate for
 * each site the network inspector decrypts. Devices that trust [certificate] accept those as the sites' own.
 */
internal class NetworkCertificateAuthority(private val directory: File) {
    private val store = File(directory, "network-ca.p12")
    private val keyPairs = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
    private val authority: Pair<X509Certificate, PrivateKey> by lazy { load() ?: create() }
    private val sites = ConcurrentHashMap<String, SSLContext>()

    val certificate: X509Certificate get() = authority.first

    /** The certificate in PEM, as Android and `network_security_config` read it. */
    val pem: String get() = certificate.toPem()

    /**
     * The base64 SHA-256 of the certificate's public key, which Chrome's `--ignore-certificate-errors-spki-list` takes.
     * Chrome checks sites against its own root store, not Android's, so a certificate added to the system's isn't
     * enough for it.
     */
    val spkiFingerprint: String get() = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    /** The transparency logs whose timestamps the sites' certificates carry. */
    val transparencyLogs: List<TransparencyLog> by lazy { TransparencyLog.derive(certificate.publicKey) }

    /** The file Android looks the certificate up by in its system store: the subject's OpenSSL old-style hash. */
    val systemFileName: String get() = "${subjectHashOld(certificate)}.0"

    /** The certificate in PEM, written next to the authority's key, for copying into an app's resources. */
    val pemFile: File get() = File(directory, "robomori-ca.pem").also { if (!it.isFile || it.readText() != pem) it.writeText(pem) }

    /** A server context that presents a certificate for [host], signed by this authority. */
    fun serverContext(host: String): SSLContext = sites.getOrPut(host.lowercase()) {
        val keys = keyPairs.generateKeyPair()
        val leaf = sign(keys, host)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("site", keys.private, password, arrayOf(leaf, certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }

    /** A certificate for [host] with [keys], signed by this authority and carrying transparency timestamps. */
    internal fun sign(keys: KeyPair, host: String): X509Certificate {
        val (ca, caKey) = authority
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(ca, BigInteger(64, SecureRandom()).abs(), Date.from(now - Duration.ofDays(1)),
            // Under 180 days, for which two transparency timestamps are enough.
            Date.from(now + Duration.ofDays(45)), X500Name("CN=${host.replace(Regex("[,=+<>#;\"\\\\]"), "")}"), keys.public)
        val name = if (host.matches(Regex("[0-9.]+|.*:.*"))) GeneralName(GeneralName.iPAddress, host) else GeneralName(GeneralName.dNSName, host)
        val extensions = JcaX509ExtensionUtils()
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(name))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        builder.addExtension(Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(ca))
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(keys.public))
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
        // Android 17 apps need transparency timestamps, which sign the certificate as it is without them; the
        // timestamps then go last, so taking them out again gives back exactly what was signed.
        val precertificate = builder.build(signer.build(caKey)).toASN1Structure().tbsCertificate.encoded
        val issuerKeyHash = MessageDigest.getInstance("SHA-256").digest(ca.publicKey.encoded)
        val timestamps = transparencyLogs.map { it.timestamp(precertificate, issuerKeyHash, now - Duration.ofDays(1)) }
        val list = ByteArrayOutputStream().apply {
            val total = timestamps.sumOf { it.size + 2 }
            write(total shr 8); write(total)
            for (timestamp in timestamps) { write(timestamp.size shr 8); write(timestamp.size); write(timestamp) }
        }.toByteArray()
        builder.addExtension(ASN1ObjectIdentifier(SignedCertificateTimestamps), false, DEROctetString(list))
        return JcaX509CertificateConverter().getCertificate(builder.build(signer.build(caKey)))
    }

    private fun load(): Pair<X509Certificate, PrivateKey>? = runCatching {
        val keyStore = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, password) } }
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        certificate.checkValidity(Date.from(Instant.now() + Duration.ofDays(30)))
        certificate to keyStore.getKey(ALIAS, password) as PrivateKey
    }.getOrNull()

    private fun create(): Pair<X509Certificate, PrivateKey> {
        val keys = keyPairs.generateKeyPair()
        val now = Instant.now()
        val subject = X500Name("CN=Robomori Network Inspector, O=Robomori, OU=${Base64.getUrlEncoder().withoutPadding().encodeToString(SecureRandom().generateSeed(6))}")
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger(64, SecureRandom()).abs(), Date.from(now - Duration.ofDays(1)),
            Date.from(now + Duration.ofDays(3650)), subject, keys.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        builder.addExtension(Extension.subjectKeyIdentifier, false, JcaX509ExtensionUtils().createSubjectKeyIdentifier(keys.public))
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)))
        directory.mkdirs()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keys.private, password, arrayOf(certificate))
        }
        val temporary = File(directory, "${store.name}.tmp")
        temporary.outputStream().use { keyStore.store(it, password) }
        temporary.setReadable(false, false); temporary.setReadable(true, true)
        check(temporary.renameTo(store) || (store.delete() && temporary.renameTo(store))) { "Could not save the network inspector's certificate." }
        return certificate to keys.private
    }

    private companion object {
        const val ALIAS = "robomori-ca"
        /** RFC 6962's extension for the timestamps embedded in a certificate. */
        const val SignedCertificateTimestamps = "1.3.6.1.4.1.11129.2.4.2"
        // The key never leaves this computer's settings folder; the store's password only satisfies the format.
        val password = "robomori".toCharArray()
    }
}

internal fun X509Certificate.toPem(): String =
    "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded) + "\n-----END CERTIFICATE-----\n"

/** OpenSSL's `subject_hash_old`: the first four bytes of the MD5 of the DER subject, little-endian, in hex. */
internal fun subjectHashOld(certificate: X509Certificate): String {
    val md5 = MessageDigest.getInstance("MD5").digest(certificate.subjectX500Principal.encoded)
    val value = (md5[0].toLong() and 0xff) or ((md5[1].toLong() and 0xff) shl 8) or
        ((md5[2].toLong() and 0xff) shl 16) or ((md5[3].toLong() and 0xff) shl 24)
    return "%08x".format(value)
}
