package com.inkwell.notes.core.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials (AI API keys, vault password and session) encrypted with an AES-256-GCM key
 * that lives in the Android Keystore and never leaves it. Values are stored only as
 * ciphertext in a private preferences file that is excluded from device backup.
 *
 * Never log a value from here, never put one into AppSettings, exports or shared files.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("inkwell_secrets", Context.MODE_PRIVATE)

    @Synchronized
    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.encodeToByteArray())
        val blob = cipher.iv + ct
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (blob.size < IV_BYTES) throw IllegalArgumentException("blob too short")
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ct = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(ct).decodeToString()
        } catch (e: AEADBadTagException) {
            // Encrypted with a key that no longer exists (Keystore wiped, e.g. device credential
            // reset): the value is unrecoverable. Do not log the value.
            Log.w(TAG, "Secret '$name' could not be decrypted; clearing it")
            prefs.edit().remove(name).apply()
            null
        } catch (e: IllegalArgumentException) {
            // Malformed ciphertext: unrecoverable.
            Log.w(TAG, "Secret '$name' is malformed; clearing it")
            prefs.edit().remove(name).apply()
            null
        } catch (e: Exception) {
            // Keystore hiccups (KeyStoreException, ProviderException, UnrecoverableKeyException
            // while the device is locked...) are transient: keep the ciphertext for the next call
            // instead of silently deleting the user's API key or vault password.
            Log.w(TAG, "Secret '$name' temporarily unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    fun remove(name: String) = put(name, null)

    /** Last 4 characters for display ("••••4f2a"), never the full value. */
    fun hint(name: String): String? = get(name)?.let { v -> if (v.length <= 4) "••••" else "••••" + v.takeLast(4) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val TAG = "SecretStore"
        private const val ALIAS = "inkwell.secrets.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        fun aiKey(provider: com.inkwell.notes.core.model.AiProviderId) = "ai.key.${provider.name}"
        const val VAULT_PASSWORD = "vault.password"
        const val VAULT_SESSION = "vault.session"
        /** Fingerprint of the vault link [VAULT_PASSWORD] was entered for (see VaultPassword). */
        const val VAULT_PASSWORD_VAULT = "vault.password.vault"
    }
}
