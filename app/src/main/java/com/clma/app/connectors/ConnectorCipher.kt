package com.clma.app.connectors

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The store's authenticated envelope, independent of the Android key provider. */
internal object ConnectorCipher {
    fun encrypt(name: String, value: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(cipher.iv) + ":" +
            encoder.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(name: String, encrypted: String, key: SecretKey): String {
        val parts = encrypted.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
    }
}
