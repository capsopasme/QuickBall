package io.github.chayanforyou.quickball.localsend

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.TimeZone

class SelfSignedCertTest {

    @Test
    fun buildsVerifiableV3Certificate() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val der = SelfSignedCert.build("QuickBall", keyPair.public.encoded, 20) { tbs ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
        }

        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate

        assertEquals(3, cert.version)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.checkValidity()
        cert.verify(keyPair.public) // self-signature, which LocalSend checks
    }

    @Test
    fun validityPast2049UsesGeneralizedTime() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val utc = TimeZone.getTimeZone("UTC")
        val createdIn2035 = Calendar.getInstance(utc).apply { set(2035, Calendar.MARCH, 1, 12, 0, 0) }.time

        val der = SelfSignedCert.build("QuickBall", keyPair.public.encoded, 20, createdIn2035) { tbs ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
        }
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate

        val notAfterYear = Calendar.getInstance(utc).apply { time = cert.notAfter }.get(Calendar.YEAR)
        assertEquals(2055, notAfterYear)
        cert.checkValidity(createdIn2035)
        cert.verify(keyPair.public)
    }
}
