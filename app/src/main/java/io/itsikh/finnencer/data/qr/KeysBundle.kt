package io.itsikh.finnencer.data.qr

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.itsikh.finnencer.data.repo.ApiKey
import io.itsikh.finnencer.data.repo.ApiKeysRepository
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wire format for sharing finnencer API keys via QR.
 *
 * Plaintext form (no passphrase):
 * ```json
 * {"v":1,"app":"finnencer","encrypted":false,
 *  "keys":{"anthropic":"sk-ant-...","finnhub":"...", ...}}
 * ```
 *
 * Encrypted form (PBKDF2-HMAC-SHA256 → AES-256-GCM):
 * ```json
 * {"v":1,"app":"finnencer","encrypted":true,
 *  "kdf":"PBKDF2-HMAC-SHA256","iter":100000,
 *  "salt":"...base64...","iv":"...base64...","ct":"...base64..."}
 * ```
 *
 * `ct` (ciphertext) is GCM-encrypted JSON of the same `keys` object as in the
 * plaintext form.
 */
@Singleton
class KeysBundle @Inject constructor(
    private val repo: ApiKeysRepository,
    private val gson: Gson,
) {

    private val secureRandom = SecureRandom()

    /** Build a JSON payload from the currently-stored keys. */
    fun buildPayload(passphrase: String? = null): Result<String> = runCatching {
        val keys = collectKeys()
        if (keys.isEmpty()) error("No keys configured yet")

        if (passphrase.isNullOrBlank()) {
            val root = JsonObject().apply {
                addProperty("v", 1)
                addProperty("app", "finnencer")
                addProperty("encrypted", false)
                add("keys", gson.toJsonTree(keys))
            }
            gson.toJson(root)
        } else {
            val plaintext = gson.toJson(keys).toByteArray(Charsets.UTF_8)
            val salt = ByteArray(16).also(secureRandom::nextBytes)
            val iv = ByteArray(12).also(secureRandom::nextBytes)
            val secret = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(plaintext)

            val root = JsonObject().apply {
                addProperty("v", 1)
                addProperty("app", "finnencer")
                addProperty("encrypted", true)
                addProperty("kdf", "PBKDF2-HMAC-SHA256")
                addProperty("iter", PBKDF2_ITERATIONS)
                addProperty("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                addProperty("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                addProperty("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            }
            gson.toJson(root)
        }
    }

    /**
     * Parse a scanned QR payload. Returns the keys map on success, or a failure
     * including a hint for the UI (wrong app, bad passphrase, malformed).
     */
    fun parsePayload(raw: String, passphrase: String? = null): Result<Map<String, String>> = runCatching {
        val root = gson.fromJson(raw, JsonObject::class.java)
            ?: error("Could not parse QR payload")
        val app = root["app"]?.asString
        require(app == "finnencer") { "QR is not a finnencer keys bundle (app=$app)" }
        val v = root["v"]?.asInt ?: error("Missing v")
        require(v == 1) { "Unsupported bundle version v=$v" }

        val encrypted = root["encrypted"]?.asBoolean ?: false
        if (!encrypted) {
            return@runCatching parseKeysObject(root["keys"]?.asJsonObject ?: error("Missing keys"))
        }

        requireNotNull(passphrase?.takeIf { it.isNotBlank() }) { "Passphrase required" }
        val salt = Base64.decode(root["salt"].asString, Base64.NO_WRAP)
        val iv = Base64.decode(root["iv"].asString, Base64.NO_WRAP)
        val ct = Base64.decode(root["ct"].asString, Base64.NO_WRAP)
        val iter = root["iter"]?.asInt ?: PBKDF2_ITERATIONS

        val secret = deriveKey(passphrase, salt, iter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
        val plaintext = try {
            cipher.doFinal(ct)
        } catch (t: Throwable) {
            error("Wrong passphrase or tampered payload")
        }
        val keysObj = gson.fromJson(String(plaintext, Charsets.UTF_8), JsonObject::class.java)
        parseKeysObject(keysObj)
    }

    fun importPayload(parsed: Map<String, String>, overwrite: Boolean) {
        parsed.forEach { (slug, value) ->
            val key = ApiKey.entries.firstOrNull { it.slugForBundle() == slug } ?: return@forEach
            if (overwrite || !repo.isConfigured(key)) {
                repo.save(key, value)
            }
        }
    }

    private fun collectKeys(): Map<String, String> = ApiKey.entries
        .mapNotNull { k -> repo.get(k)?.let { k.slugForBundle() to it } }
        .toMap()

    private fun parseKeysObject(obj: JsonObject): Map<String, String> =
        obj.entrySet().mapNotNull { (k, v) ->
            v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it }
        }.toMap()

    private fun deriveKey(passphrase: String, salt: ByteArray, iter: Int = PBKDF2_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iter, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    companion object {
        const val PBKDF2_ITERATIONS = 100_000
    }
}

/** Stable identifier per key in the QR payload — must NOT change after shipping. */
private fun ApiKey.slugForBundle(): String = when (this) {
    ApiKey.ANTHROPIC -> "anthropic"
    ApiKey.FINNHUB -> "finnhub"
    ApiKey.GEMINI -> "gemini"
    ApiKey.GITHUB_PAT -> "github_pat"
    ApiKey.EDGAR_UA -> "edgar_ua"
}
