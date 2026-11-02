package com.clma.app.connectors

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal data class GmailOAuthAppIdentity(
    val packageName: String,
    val sha1Fingerprints: List<String>,
)

@Suppress("DEPRECATION") // Android 8.x needs GET_SIGNATURES; signingInfo starts at API 28.
internal fun gmailOAuthAppIdentity(context: Context): GmailOAuthAppIdentity {
    val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners.orEmpty()
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures.orEmpty()
    }
    return GmailOAuthAppIdentity(
        packageName = context.packageName,
        sha1Fingerprints = certificates.map { gmailOAuthCertificateSha1(it.toByteArray()) }.distinct(),
    )
}

internal fun gmailOAuthCertificateSha1(certificate: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(certificate).joinToString(":") { "%02X".format(it.toInt() and 0xff) }
