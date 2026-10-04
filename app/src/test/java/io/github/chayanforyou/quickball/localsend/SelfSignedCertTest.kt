package io.github.chayanforyou.quickball.localsend

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

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
}
