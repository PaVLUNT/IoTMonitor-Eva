package cl.pallendes.iotmonitor

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import cl.pallendes.iotmonitor.databinding.ActivityLoginBinding

/** Pantalla de login con autenticación local SQLite (funciona sin conexión). */
class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding
    private lateinit var db: Database
    private var failures = 0
    private var lockedUntil = 0L
    private val userRegex = Regex("^[a-z0-9_.]{3,20}$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Database(this)
        b.btnLogin.setOnClickListener { login() }
        b.btnRegister.setOnClickListener { register() }
    }

    private fun creds(): Pair<String, CharArray>? {
        val u = b.etUser.text.toString().trim().lowercase()
        val p = b.etPass.text.toString().toCharArray()
        if (!userRegex.matches(u)) { b.tvLoginMsg.text = "Usuario: 3-20 caracteres (a-z, 0-9, _ .)"; return null }
        if (p.size < 8) { b.tvLoginMsg.text = "La contraseña debe tener al menos 8 caracteres"; return null }
        return u to p
    }

    private fun register() {
        val (u, p) = creds() ?: return
        b.tvLoginMsg.text = if (db.createUser(u, p)) "Cuenta creada. Ya puedes ingresar." else "Ese usuario ya existe"
        p.fill('0')
    }

    private fun login() {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) {
            b.tvLoginMsg.text = "Demasiados intentos. Espera ${(lockedUntil - now) / 1000 + 1} s"; return
        }
        val (u, p) = creds() ?: return
        val ok = db.verifyUser(u, p)
        p.fill('0')
        if (ok) {
            failures = 0
            startActivity(Intent(this, MainActivity::class.java).putExtra("user", u))
            finish()
        } else {
            failures++
            if (failures >= 5) { lockedUntil = now + 30_000; failures = 0 }
            b.tvLoginMsg.text = "Credenciales incorrectas"
        }
    }
}
