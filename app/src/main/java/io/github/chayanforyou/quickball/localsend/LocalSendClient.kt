package io.github.chayanforyou.quickball.localsend

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** The receiving LocalSend device, as configured once in settings. */
data class LocalSendTarget(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val https: Boolean = true,
    /** Certificate SHA-256 (hex) to pin; blank means accept any certificate. */
    val fingerprint: String = "",
    val pin: String = "",
    val alias: String = ""
) {
    val isConfigured: Boolean get() = host.isNotBlank() && port in 1..65535

    /** Name for toasts: the stored alias, else host:port. */
    val displayName: String get() = alias.ifBlank { "$host:$port" }

    companion object {
        const val DEFAULT_PORT = 53317
    }
}

data class LocalSendDeviceInfo(val alias: String, val fingerprint: String)

sealed class LocalSendResult {
    /** Receiver accepted; for a message this means its dialog was shown and closed. */
    data object Delivered : LocalSendResult()
    data object Rejected : LocalSendResult()
    data object PinRequired : LocalSendResult()
    data object PinInvalid : LocalSendResult()
    data object TooManyPinAttempts : LocalSendResult()
    data object Busy : LocalSendResult()
    data object FingerprintMismatch : LocalSendResult()
    data class Unreachable(val reason: String) : LocalSendResult()
    data class HttpError(val code: Int) : LocalSendResult()
}

/**
 * Minimal LocalSend protocol v2 sender for text messages.
 *
 * A message is a single text/plain file whose content travels in `preview`; LocalSend shows
 * it in a dialog and answers 204 once the user closes it, so no upload step is needed. The
 * request stays open until then, which is why [sendText] uses a long read timeout and must run
 * off the main thread.
 */
class LocalSendClient(private val context: Context) {

    companion object {
        private const val API = "/api/localsend/v2"
        private const val PROTOCOL_VERSION = "2.2"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val INFO_READ_TIMEOUT_MS = 6_000
        private const val SEND_READ_TIMEOUT_MS = 10 * 60_000
    }

    private val identity by lazy { LocalSendIdentity.get(context) }

    /** Reads the receiver's alias and, over HTTPS, the fingerprint of the certificate it presented. */
    @Throws(IOException::class)
    fun fetchInfo(target: LocalSendTarget): LocalSendDeviceInfo {
        val seenFingerprint = arrayOfNulls<String>(1)
        val conn = open(target, "$API/info", INFO_READ_TIMEOUT_MS, seenFingerprint)
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val fingerprint = seenFingerprint[0] ?: json.optString("fingerprint")
            return LocalSendDeviceInfo(json.optString("alias"), fingerprint)
        } finally {
            conn.disconnect()
        }
    }

    fun sendText(target: LocalSendTarget, text: String): LocalSendResult {
        val pinQuery = if (target.pin.isNotBlank()) "?pin=" + URLEncoder.encode(target.pin, "UTF-8") else ""
        return try {
            val conn = open(target, "$API/prepare-upload$pinQuery", SEND_READ_TIMEOUT_MS, null)
            try {
                val fileId = UUID.randomUUID().toString()
                val body = buildPrepareUpload(target, fileId, text).toByteArray(Charsets.UTF_8)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }

                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_NO_CONTENT -> LocalSendResult.Delivered
                    // Receiver wants the bytes after all (non-LocalSend-app receivers may do this).
                    HttpURLConnection.HTTP_OK -> {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        upload(target, json, fileId, text)
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED ->
                        if (target.pin.isBlank()) LocalSendResult.PinRequired else LocalSendResult.PinInvalid
                    HttpURLConnection.HTTP_FORBIDDEN -> LocalSendResult.Rejected
                    HttpURLConnection.HTTP_CONFLICT -> LocalSendResult.Busy
                    429 -> LocalSendResult.TooManyPinAttempts
                    else -> LocalSendResult.HttpError(code)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            if (e.hasCause<FingerprintMismatchException>()) LocalSendResult.FingerprintMismatch
            else LocalSendResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun upload(target: LocalSendTarget, response: JSONObject, fileId: String, text: String): LocalSendResult {
        val sessionId = response.optString("sessionId")
        val token = response.optJSONObject("files")?.optString(fileId).orEmpty()
        if (sessionId.isEmpty() || token.isEmpty()) return LocalSendResult.Delivered

        val query = "?sessionId=${enc(sessionId)}&fileId=${enc(fileId)}&token=${enc(token)}"
        val conn = open(target, "$API/upload$query", SEND_READ_TIMEOUT_MS, null)
        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code in 200..299) LocalSendResult.Delivered else LocalSendResult.HttpError(code)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildPrepareUpload(target: LocalSendTarget, fileId: String, text: String): String {
        val info = JSONObject()
            .put("alias", deviceAlias())
            .put("version", PROTOCOL_VERSION)
            .put("deviceModel", Build.MODEL)
            .put("deviceType", "mobile")
            .put("fingerprint", if (target.https) identity.fingerprint else UUID.randomUUID().toString())
            .put("port", LocalSendTarget.DEFAULT_PORT)
            .put("protocol", if (target.https) "https" else "http")
            .put("download", false)
        val file = JSONObject()
            .put("id", fileId)
            .put("fileName", "$fileId.txt")
            .put("size", text.toByteArray(Charsets.UTF_8).size)
            .put("fileType", "text/plain")
            .put("preview", text)
        return JSONObject()
            .put("info", info)
            .put("files", JSONObject().put(fileId, file))
            .toString()
    }

    private fun deviceAlias(): String {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } else null
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    private fun open(
        target: LocalSendTarget,
        path: String,
        readTimeoutMs: Int,
        seenFingerprint: Array<String?>?
    ): HttpURLConnection {
        val host = if (target.host.contains(':') && !target.host.startsWith("[")) "[${target.host}]" else target.host
        val scheme = if (target.https) "https" else "http"
        val conn = URL("$scheme://$host:${target.port}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        if (conn is HttpsURLConnection) {
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(
                arrayOf(ClientKeyManager(identity.privateKey, identity.certificate)),
                arrayOf(PinningTrustManager(target.fingerprint, seenFingerprint)),
                null
            )
            conn.sslSocketFactory = ssl.socketFactory
            // LocalSend certificates are self-signed with a random CN; identity comes from the pin.
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return conn
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }
}

internal class FingerprintMismatchException : CertificateException("Certificate fingerprint mismatch")

/** Accepts the server certificate only if its SHA-256 matches the configured fingerprint. */
private class PinningTrustManager(
    private val expected: String,
    private val seen: Array<String?>?
) : X509ExtendedTrustManager() {

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No server certificate")
        val actual = LocalSendIdentity.sha256Hex(leaf.encoded)
        seen?.set(0, actual)
        if (expected.isNotBlank() && !actual.equals(expected.trim(), ignoreCase = true)) {
            throw FingerprintMismatchException()
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not checked here")
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        throw CertificateException("Client certificates are not checked here")
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        throw CertificateException("Client certificates are not checked here")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Always offers QuickBall's certificate. A stock key manager would filter by the issuer list
 * the server sends (LocalSend's own self-signed name) and end up sending no certificate.
 */
private class ClientKeyManager(
    private val key: PrivateKey,
    private val cert: X509Certificate
) : X509ExtendedKeyManager() {
    private val alias = "quickball"

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun getCertificateChain(alias: String?) = arrayOf(cert)
    override fun getPrivateKey(alias: String?) = key
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
}
