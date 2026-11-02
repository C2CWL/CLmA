package com.clma.app.connectors

import org.junit.Assert.assertEquals
import org.junit.Test

class GmailOAuthAppIdentityTest {
    @Test
    fun `certificate fingerprint uses the Google Cloud uppercase colon separated SHA1 format`() {
        assertEquals(
            "A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D",
            gmailOAuthCertificateSha1("abc".toByteArray(Charsets.US_ASCII)),
        )
    }
}
