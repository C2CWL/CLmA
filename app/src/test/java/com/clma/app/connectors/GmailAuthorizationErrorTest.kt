package com.clma.app.connectors

import com.google.android.gms.common.api.CommonStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GmailAuthorizationErrorTest {
    @Test
    fun `only the SDK cancellation status is labelled cancelled`() {
        assertEquals("gmail_authorization_cancelled", gmailAuthorizationErrorCode(CommonStatusCodes.CANCELED))
        listOf(CommonStatusCodes.DEVELOPER_ERROR, CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.ERROR)
            .forEach { status ->
                assertNotEquals("gmail_authorization_cancelled", gmailAuthorizationErrorCode(status))
            }
    }

    @Test
    fun `configuration and network failures have separate diagnostics`() {
        assertEquals("gmail_oauth_configuration_error", gmailAuthorizationErrorCode(CommonStatusCodes.DEVELOPER_ERROR))
        assertEquals("gmail_authorization_network_error", gmailAuthorizationErrorCode(CommonStatusCodes.NETWORK_ERROR))
    }

    @Test
    fun `other failures retain their numeric SDK status`() {
        assertEquals(
            "gmail_authorization_failed_${CommonStatusCodes.ERROR}",
            gmailAuthorizationErrorCode(CommonStatusCodes.ERROR),
        )
    }
}
