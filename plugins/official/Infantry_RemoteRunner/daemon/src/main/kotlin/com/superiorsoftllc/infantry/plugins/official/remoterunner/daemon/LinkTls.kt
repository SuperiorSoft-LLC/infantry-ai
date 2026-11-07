// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * RUNNER-POOL-5 (moved to Infantry_RemoteRunner by RUNNER-POOL-6a) (docs/RUNNER-POOL.md section 7): the runner link's TLS. A runner generates ONE self-signed certificate (EC P-256,
 * SHA256withECDSA) on its first pairing, kept as a PKCS12 file in the link folder, its password in the link keystore. The
 * orchestrator never trusts a CA: it pins the certificate's SHA-256 [fingerprint], which `runner pair` prints on the runner's own
 * console and the owner passes to `runner add --fingerprint`. Built with the JDK only (the DER is encoded here), so no TLS library
 * ships in the daemon.
 */
object LinkTls {
    const val FILE = "tls.p12"
    private const val ALIAS = "runner-link"
    private const val VALID_YEARS = 20L
    private const val SERIAL_BITS = 64

    /** The runner's certificate store, generating it on the first call; [password] is the store's password. */
    fun ensure(dir: File, password: String): KeyStore {
        val file = File(dir, FILE)
        val pw = password.toCharArray()
        if (file.isFile) {
            runCatching { return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, pw) } } }
        }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val cert = selfSigned(pair, "infantry-runner")
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry(ALIAS, pair.private, pw, arrayOf(cert)) }
        dir.mkdirs()
        val temp = File(dir, "$FILE.tmp")
        temp.outputStream().use { store.store(it, pw) }
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not write ${file.path}" }
        }
        return store
    }

    fun certificate(store: KeyStore): X509Certificate = store.getCertificate(ALIAS) as X509Certificate

    /** The SHA-256 of [cert]'s DER, upper-case hex pairs joined by `:` (the form `runner pair` prints). */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** [fingerprint] reduced to lower-case hex, so `AB:CD`, `abcd` and `ab cd` compare equal. */
    fun normalized(fingerprint: String): String = fingerprint.filter { it.isLetterOrDigit() }.lowercase()

    fun serverContext(store: KeyStore, password: String): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password.toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    /**
     * A client context that trusts exactly the certificate whose fingerprint is [expected] (no CA, no host name: the pin IS the
     * identity). With [expected] null it trusts nothing, so the handshake fails, but [Pin.presented] still says what the server
     * showed, for the "compare this fingerprint" message.
     */
    fun pinnedContext(expected: String?): Pair<SSLContext, Pin> {
        val pin = Pin(expected?.let(::normalized))
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(pin), SecureRandom()) } to pin
    }

    /** The pinning trust manager. Being an [X509ExtendedTrustManager], it also replaces the JDK's host name check. */
    class Pin(private val expected: String?) : X509ExtendedTrustManager() {
        @Volatile var presented: String? = null
            private set

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("the runner presented no certificate")
            val shown = fingerprint(leaf)
            presented = shown
            if (expected == null) throw CertificateException("no fingerprint pinned; the runner presented $shown")
            if (!MessageDigest.isEqual(normalized(shown).toByteArray(), expected.toByteArray())) {
                throw CertificateException("certificate fingerprint $shown does not match the pinned one")
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit = throw CertificateException(NO_CLIENT_CERTS)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit = throw CertificateException(NO_CLIENT_CERTS)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit = throw CertificateException(NO_CLIENT_CERTS)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val NO_CLIENT_CERTS = "client certificates are not used"

    // ---- a minimal X.509 v3 certificate, DER-encoded by hand (no extensions) ----

    private fun selfSigned(pair: KeyPair, commonName: String): X509Certificate {
        val algorithm = seq(oid("1.2.840.10045.4.3.2")) // ecdsa-with-SHA256
        val name = seq(set(seq(oid("2.5.4.3"), der(TAG_UTF8, commonName.toByteArray()))))
        val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
        val tbs = seq(
            der(TAG_EXPLICIT_0, der(TAG_INTEGER, byteArrayOf(2))), // v3
            der(TAG_INTEGER, BigInteger(SERIAL_BITS, SecureRandom()).add(BigInteger.ONE).toByteArray()),
            algorithm,
            name,
            seq(time(now.minusDays(1)), time(now.plusYears(VALID_YEARS))),
            name,
            pair.public.encoded, // SubjectPublicKeyInfo, already DER
        )
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(tbs); sign() }
        val cert = seq(tbs, algorithm, der(TAG_BIT_STRING, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
    }

    private fun time(t: ZonedDateTime): ByteArray =
        if (t.year < GENERALIZED_TIME_FROM_YEAR) der(TAG_UTC_TIME, t.format(UTC_TIME).toByteArray())
        else der(TAG_GENERALIZED_TIME, t.format(GENERALIZED_TIME).toByteArray())

    private fun seq(vararg parts: ByteArray) = der(TAG_SEQUENCE, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = der(TAG_SET, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * OID_FIRST_ARC + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val groups = mutableListOf<Int>()
            var v = arc
            do {
                groups.add((v and SEVEN_BITS).toInt())
                v = v shr OID_GROUP_SHIFT
            } while (v > 0)
            groups.reversed().forEachIndexed { i, g -> out.write(if (i < groups.size - 1) g or HIGH_BIT else g) }
        }
        return der(TAG_OID, out.toByteArray())
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        if (n < HIGH_BIT) {
            out.write(n)
        } else {
            val len = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it == 0.toByte() }
            out.write(HIGH_BIT or len.size)
            len.forEach { out.write(it.toInt()) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private val UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
    private val GENERALIZED_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OID = 0x06
    private const val TAG_UTF8 = 0x0C
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_GENERALIZED_TIME = 0x18
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_EXPLICIT_0 = 0xA0
    private const val HIGH_BIT = 0x80
    private const val SEVEN_BITS = 0x7FL
    private const val OID_GROUP_SHIFT = 7
    private const val OID_FIRST_ARC = 40
    private const val GENERALIZED_TIME_FROM_YEAR = 2050
}
