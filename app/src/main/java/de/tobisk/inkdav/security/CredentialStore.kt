package de.tobisk.inkdav.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/** Credentials are encrypted with a non-exportable device key and excluded from Android backup. */
class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("dav_credentials_no_backup", Context.MODE_PRIVATE)
    private val alias = "inkdav.credentials.v1"

    fun put(accountId: String, password: CharArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val clear = password.concatToString().encodeToByteArray()
        val encrypted = try { cipher.doFinal(clear) } finally { clear.fill(0) }
        preferences.edit()
            .putString("$accountId.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$accountId.value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
        password.fill('\u0000')
    }

    fun get(accountId: String): CharArray? {
        val iv = preferences.getString("$accountId.iv", null) ?: return null
        val value = preferences.getString("$accountId.value", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val clear = cipher.doFinal(Base64.decode(value, Base64.NO_WRAP))
        return try { clear.decodeToString().toCharArray() } finally { clear.fill(0) }
    }

    fun remove(accountId: String) {
        preferences.edit().remove("$accountId.iv").remove("$accountId.value").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }
}

