package com.clma.app.connectors

import com.google.android.gms.common.api.CommonStatusCodes

/** Only numeric SDK statuses cross this boundary, never provider exception messages. */
internal fun gmailAuthorizationErrorCode(statusCode: Int): String = when (statusCode) {
    CommonStatusCodes.CANCELED -> "gmail_authorization_cancelled"
    CommonStatusCodes.DEVELOPER_ERROR -> "gmail_oauth_configuration_error"
    CommonStatusCodes.NETWORK_ERROR -> "gmail_authorization_network_error"
    else -> "gmail_authorization_failed_$statusCode"
}
