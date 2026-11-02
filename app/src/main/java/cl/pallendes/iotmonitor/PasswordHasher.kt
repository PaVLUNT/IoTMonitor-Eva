package cl.pallendes.iotmonitor

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Cifrado de credenciales: PBKDF2-HMAC-SHA256 con sal aleatoria (nunca se guarda la contraseña en claro). */
object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Comparación en tiempo constante para evitar ataques de temporización. */
    fun matches(password: CharArray, saltB64: String, hashB64: String): Boolean =
        MessageDigest.isEqual(hash(password, decode(saltB64)), decode(hashB64))
}
