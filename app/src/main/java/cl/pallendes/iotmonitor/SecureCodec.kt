package cl.pallendes.iotmonitor

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Autenticación e integridad de mensajes entre dispositivos (ISO/IEC 27400):
 * cada mensaje lleva un HMAC-SHA256 calculado con una clave derivada (PBKDF2) de la clave de enlace
 * que ambos usuarios ingresan. Un mensaje sin HMAC válido se descarta.
 * Formato: TIPO|timestamp|datos...|hmac
 */
class SecureCodec(pin: String) {
    private val key: SecretKeySpec

    init {
        val spec = PBEKeySpec(pin.toCharArray(), "iot-monitor-ti3042".toByteArray(), 50_000, 256)
        key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "HmacSHA256")
        spec.clearPassword()
    }

    fun encode(payload: String): String = "$payload|${mac(payload)}"

    /** @return el payload si el HMAC es válido, o null si fue manipulado / clave distinta. */
    fun verify(line: String): String? {
        val i = line.lastIndexOf('|')
        if (i < 0) return null
        val payload = line.substring(0, i)
        return if (MessageDigest.isEqual(mac(payload).toByteArray(), line.substring(i + 1).toByteArray())) payload else null
    }

    private fun mac(p: String): String = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(p.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
