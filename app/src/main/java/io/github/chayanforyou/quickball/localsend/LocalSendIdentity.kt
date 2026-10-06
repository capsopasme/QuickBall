package io.github.chayanforyou.quickball.localsend

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The TLS client identity QuickBall presents to LocalSend.
 *
 * LocalSend's HTTPS server requires a client certificate (mutual TLS) unless its web share is
 * on, and accepts any self-signed certificate whose signature and validity check out. We
 * generate an EC P-256 key and a minimal self-signed X.509 v3 certificate once, then keep both
 * in private preferences. Built by hand so it works on every API level without Bouncy Castle.
 */
class LocalSendIdentity private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate
) {
    /** SHA-256 of the certificate DER, uppercase hex: the format LocalSend uses. */
    val fingerprint: String by lazy { sha256Hex(certificate.encoded) }

    companion object {
        private const val PREFS = "localsend_identity"
        private const val KEY_PRIVATE = "private_key_pkcs8"
        private const val KEY_CERT = "certificate_der"
        private const val VALIDITY_YEARS = 20

        @Volatile
        private var cached: LocalSendIdentity? = null

        fun get(context: Context): LocalSendIdentity {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: (load(context) ?: create(context)).also { cached = it }
            }
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02X".format(it) }

        private fun load(context: Context): LocalSendIdentity? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val keyB64 = prefs.getString(KEY_PRIVATE, null) ?: return null
            val certB64 = prefs.getString(KEY_CERT, null) ?: return null
            return try {
                val key = KeyFactory.getInstance("EC")
                    .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(keyB64, Base64.NO_WRAP)))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(Base64.decode(certB64, Base64.NO_WRAP).inputStream())
                        as X509Certificate
                cert.checkValidity()
                LocalSendIdentity(key, cert)
            } catch (_: Exception) {
                null
            }
        }

        private fun create(context: Context): LocalSendIdentity {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val keyPair = generator.generateKeyPair()

            val der = SelfSignedCert.build(
                commonName = "QuickBall",
                subjectPublicKeyInfo = keyPair.public.encoded,
                validityYears = VALIDITY_YEARS
            ) { tbs ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(tbs)
                    sign()
                }
            }
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_PRIVATE, Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP))
                putString(KEY_CERT, Base64.encodeToString(der, Base64.NO_WRAP))
            }
            return LocalSendIdentity(keyPair.private, cert)
        }
    }
}

/** Minimal DER writer for one self-signed ecdsa-with-SHA256 certificate. */
internal object SelfSignedCert {
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_COMMON_NAME = "2.5.4.3"

    fun build(
        commonName: String,
        subjectPublicKeyInfo: ByteArray,
        validityYears: Int,
        now: Date = Date(),
        sign: (ByteArray) -> ByteArray
    ): ByteArray {
        val algorithm = seq(oid(OID_ECDSA_SHA256))
        val name = seq(set(seq(oid(OID_COMMON_NAME), tlv(0x0C, commonName.toByteArray()))))
        val notBefore = Date(now.time - 24L * 3600 * 1000)
        val notAfter = Date(now.time + validityYears * 365L * 24 * 3600 * 1000)

        val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
        serial[0] = (serial[0].toInt() and 0x7F or 0x01).toByte() // positive, non-zero lead

        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // [0] EXPLICIT version v3
            integer(BigInteger(1, serial)),
            algorithm,
            name,
            seq(certTime(notBefore), certTime(notAfter)),
            name,
            subjectPublicKeyInfo
        )
        val signature = sign(tbs)
        return seq(tbs, algorithm, bitString(signature))
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 6)
        out.write(tag)
        val len = value.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xFF -> { out.write(0x81); out.write(len) }
            len <= 0xFFFF -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> {
                out.write(0x83); out.write(len shr 16)
                out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
            }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    private fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
    private fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)

    /**
     * RFC 5280: UTCTime up to 2049, GeneralizedTime from 2050 on. UTCTime has a two-digit year
     * read as 1950–2049, so a certificate made from 2030 on (20-year validity) would otherwise
     * have expired in 1950 and been regenerated, with a new fingerprint, on every send.
     */
    private fun certTime(date: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = Calendar.getInstance(utc).apply { time = date }.get(Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }
        return tlv(tag, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            var value = arc
            val stack = ArrayList<Int>()
            stack.add((value and 0x7F).toInt())
            value = value shr 7
            while (value > 0) {
                stack.add(((value and 0x7F) or 0x80).toInt())
                value = value shr 7
            }
            for (i in stack.indices.reversed()) out.write(stack[i])
        }
        return tlv(0x06, out.toByteArray())
    }
}
