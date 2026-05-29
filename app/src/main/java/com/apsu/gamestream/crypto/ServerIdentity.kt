package com.apsu.gamestream.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

class ServerIdentity private constructor(
    val certificate: X509Certificate,
    private val privateKey: PrivateKey,
    val sslServerSocketFactory: SSLServerSocketFactory,
) {
    fun signSha256(data: ByteArray): ByteArray {
        val algorithm = when (privateKey.algorithm) {
            "EC" -> "SHA256withECDSA"
            else -> "SHA256withRSA"
        }
        return Signature.getInstance(algorithm).run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "apsu-gamestream-server"
        private val keyPassword = CharArray(0)

        fun load(context: Context): ServerIdentity {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                "AndroidKeyStore self-signed certificates require Android 6.0+"
            }

            val androidKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!androidKeyStore.containsAlias(ALIAS)) {
                createKey()
            }

            val refreshedStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val privateKey = refreshedStore.getKey(ALIAS, null) as PrivateKey
            val certificate = refreshedStore.getCertificate(ALIAS) as X509Certificate

            val tlsKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry(ALIAS, privateKey, keyPassword, arrayOf(certificate))
            }
            val keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(tlsKeyStore, keyPassword)
                }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, null, SecureRandom())
            }

            context.applicationContext.getSharedPreferences("server-identity", Context.MODE_PRIVATE)
                .edit()
                .putString("certificate_hex", Hex.encode(certificate.encoded))
                .apply()

            return ServerIdentity(
                certificate = certificate,
                privateKey = privateKey,
                sslServerSocketFactory = sslContext.serverSocketFactory,
            )
        }

        private fun createKey() {
            val now = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or
                    KeyProperties.PURPOSE_VERIFY or
                    KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT,
            )
                .setCertificateSubject(X500Principal("CN=Apsu GameStream"))
                .setCertificateSerialNumber(BigInteger(64, SecureRandom()).abs().plus(BigInteger.ONE))
                .setCertificateNotBefore(now.time)
                .setCertificateNotAfter(end.time)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .build()

            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}
