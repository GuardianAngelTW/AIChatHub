package com.aichathub.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API Key / OAuth 取得的金鑰以 Android Keystore 的 AES-256-GCM 加密後才落地 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_keys", Context.MODE_PRIVATE)
    private val alias = "aichathub_master_key"

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(id: String, secret: String) {
        if (secret.isBlank()) { remove(id); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        val out = c.iv + c.doFinal(secret.trim().toByteArray())
        prefs.edit().putString(id, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun get(id: String): String? {
        val stored = prefs.getString(id, null) ?: return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrNull()
    }

    fun has(id: String) = prefs.contains(id)
    fun remove(id: String) = prefs.edit().remove(id).apply()
}
