package com.anezium.rokidbus.phone.speech

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class HubSecretEnvelope(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("version", VERSION)
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))

    companion object {
        private const val VERSION = 1

        fun parse(raw: String): HubSecretEnvelope? =
            runCatching {
                val json = JSONObject(raw)
                if (json.optInt("version", -1) != VERSION) return null
                HubSecretEnvelope(
                    iv = Base64.getDecoder().decode(json.getString("iv")),
                    ciphertext = Base64.getDecoder().decode(json.getString("ciphertext")),
                ).takeIf { it.iv.isNotEmpty() && it.ciphertext.isNotEmpty() }
            }.getOrNull()
    }
}

class HubSecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    fun hasCredential(kind: SpeechCredentialKind): Boolean =
        !apiKey(kind).isNullOrBlank()

    fun hasCredential(engine: SpeechEngine): Boolean =
        hasCredential(engine.credentialKind)

    fun apiKey(kind: SpeechCredentialKind): String? {
        val raw = prefs.getString(kind.preferenceName(), null) ?: return null
        return runCatching {
            val envelope = HubSecretEnvelope.parse(raw) ?: return null
            decrypt(envelope)
                .optString("apiKey")
                .trim()
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun saveApiKey(kind: SpeechCredentialKind, apiKey: String): Boolean {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return false
        val raw = runCatching {
            encrypt(JSONObject().put("apiKey", cleanKey)).toJson().toString()
        }.getOrNull() ?: return false
        return runCatching {
            prefs.edit().putString(kind.preferenceName(), raw).commit()
        }.getOrDefault(false)
    }

    fun clearApiKey(kind: SpeechCredentialKind): Boolean =
        runCatching {
            prefs.edit().remove(kind.preferenceName()).commit()
        }.getOrDefault(false)

    fun azureRegion(): String? =
        runCatching {
            prefs.getString(PREF_AZURE_REGION, null)
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    fun saveAzureRegion(region: String): Boolean {
        val clean = region.trim().lowercase(Locale.US)
        return runCatching {
            prefs.edit().putString(PREF_AZURE_REGION, clean.ifBlank { null }).commit()
        }.getOrDefault(false)
    }

    fun clearAzureRegion(): Boolean =
        runCatching {
            prefs.edit().remove(PREF_AZURE_REGION).commit()
        }.getOrDefault(false)

    private fun encrypt(json: JSONObject): HubSecretEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return HubSecretEnvelope(
            iv = cipher.iv,
            ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)),
        )
    }

    private fun decrypt(envelope: HubSecretEnvelope): JSONObject {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_BITS, envelope.iv),
        )
        return JSONObject(String(cipher.doFinal(envelope.ciphertext), Charsets.UTF_8))
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
            return entry.secretKey
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun SpeechCredentialKind.preferenceName(): String =
        when (this) {
            SpeechCredentialKind.OPENAI -> PREF_OPENAI_KEY
            SpeechCredentialKind.ELEVENLABS -> PREF_ELEVENLABS_KEY
            SpeechCredentialKind.AZURE -> PREF_AZURE_KEY
        }

    companion object {
        internal const val PREFERENCES_FILE = "nexus_speech_credentials"
        internal const val KEY_ALIAS = "nexus_hub_secrets_aes"
        private const val PREF_OPENAI_KEY = "openai_api_key"
        private const val PREF_ELEVENLABS_KEY = "elevenlabs_api_key"
        private const val PREF_AZURE_KEY = "azure_api_key"
        private const val PREF_AZURE_REGION = "azure_region"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
