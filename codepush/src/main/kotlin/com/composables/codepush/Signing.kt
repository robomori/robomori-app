package com.composables.codepush

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Ed25519 signatures over manifests, using only the JDK. Keys travel as Base64 text. */
object Signing {
    /** A new key pair: the private key signs updates and stays with the publisher; the public key ships in the app. */
    fun newKeyPair(): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(pair.private.encoded) to encoder.encodeToString(pair.public.encoded)
    }

    fun sign(message: ByteArray, privateKey: String): String {
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.trim())))
        return Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run {
            initSign(key)
            update(message)
            sign()
        })
    }

    fun verify(message: ByteArray, signature: String, publicKey: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())))
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message)
            verify(Base64.getDecoder().decode(signature.trim()))
        }
    }.getOrDefault(false)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
