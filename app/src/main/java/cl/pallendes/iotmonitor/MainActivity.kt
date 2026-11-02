package cl.pallendes.iotmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cl.pallendes.iotmonitor.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Dos roles sobre la misma APK:
 *  - Nodo sensor: simula sensores de la cámara de frío (temperatura/humedad/ventilador) y los envía.
 *  - Panel de control: recibe datos en tiempo real y envía órdenes (ventilador ON/OFF).
 * Protocolo: DATA|ts|temp|hum|fan  y  CMD|ts|FAN|0/1  (cada mensaje con HMAC, ver SecureCodec).
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), BluetoothLink.Callback {
    private lateinit var b: ActivityMainBinding
    private lateinit var db: Database
    private var adapter: BluetoothAdapter? = null
    private var link: BluetoothLink? = null
    private var codec: SecureCodec? = null
    private var connected = false
    private var lastRxTs = 0L // protección anti-replay: solo se aceptan mensajes más nuevos
    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Estado simulado del nodo
    private var temp = 24.0
    private var hum = 55.0
    private var fan = false
    private val alertThreshold = 28.0

    private var pending: (() -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) pending?.invoke() else toast("Permiso Bluetooth denegado")
        pending = null
    }

    private val ticker = object : Runnable {
        override fun run() { simulateTick(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Database(this)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        b.tvWelcome.text = "Hola, ${intent.getStringExtra("user") ?: ""}"

        b.rgRole.setOnCheckedChangeListener { _, id ->
            b.btnConnect.text = if (id == b.rbNode.id) "Esperar conexión" else "Conectar a nodo vinculado"
        }
        b.btnConnect.setOnClickListener { onConnectClicked() }
        b.btnFan.setOnClickListener { sendFanCommand(!fan) }
        b.btnLogout.setOnClickListener { logout() }
        refreshHistory()
    }

    // ---------- Conexión ----------
    private fun onConnectClicked() {
        val pin = b.etPin.text.toString()
        if (pin.length < 6) { toast("La clave de enlace debe tener al menos 6 dígitos"); return }
        withBluetooth {
            stopLink()
            codec = SecureCodec(pin)
            lastRxTs = 0
            link = BluetoothLink(adapter!!, this)
            if (b.rbNode.isChecked) link!!.listen() else chooseDevice()
        }
    }

    private fun chooseDevice() {
        val devices = adapter!!.bondedDevices.toList()
        if (devices.isEmpty()) { toast("Empareja primero el otro teléfono en Ajustes > Bluetooth"); return }
        AlertDialog.Builder(this)
            .setTitle("Elige el nodo sensor")
            .setItems(devices.map { "${it.name}\n${it.address}" }.toTypedArray()) { _, i -> link?.connect(devices[i]) }
            .show()
    }

    private fun withBluetooth(action: () -> Unit) {
        val a = adapter
        if (a == null) { toast("Este dispositivo no tiene Bluetooth"); return }
        if (!a.isEnabled) { toast("Activa el Bluetooth e inténtalo de nuevo"); return }
        val missing = if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        if (missing.isEmpty()) action() else { pending = action; permLauncher.launch(missing) }
    }

    override fun onState(msg: String, connected: Boolean) {
        this.connected = connected
        b.tvStatus.text = msg
        b.btnFan.isEnabled = connected && b.rbPanel.isChecked
        if (b.rbNode.isChecked) {
            handler.removeCallbacks(ticker)
            if (connected) handler.post(ticker)
        }
    }

    // ---------- Mensajería ----------
    override fun onLine(line: String) {
        val payload = codec?.verify(line)
        if (payload == null) { b.tvStatus.text = "Mensaje rechazado (HMAC inválido: revisa la clave de enlace)"; return }
        val p = payload.split("|")
        val ts = p.getOrNull(1)?.toLongOrNull() ?: return
        if (ts <= lastRxTs) { b.tvStatus.text = "Mensaje repetido descartado"; return }
        lastRxTs = ts
        try {
            when (p[0]) {
                "DATA" -> if (b.rbPanel.isChecked) {
                    val r = Reading(ts, "remoto", p[2].toDouble(), p[3].toDouble(), p[4] == "1")
                    db.insertReading(r); show(r)
                }
                "CMD" -> if (b.rbNode.isChecked && p[2] == "FAN") { fan = p[3] == "1"; toast("Orden recibida: ventilador ${if (fan) "ON" else "OFF"}") }
            }
        } catch (_: Exception) { /* mensaje mal formado: se ignora */ }
    }

    private fun sendFanCommand(on: Boolean) {
        val c = codec ?: return
        link?.send(c.encode("CMD|${System.currentTimeMillis()}|FAN|${if (on) 1 else 0}"))
    }

    private fun simulateTick() {
        temp = (temp + if (fan) -0.4 else 0.25 + Random.nextDouble(-0.15, 0.15)).coerceIn(15.0, 40.0)
        hum = (hum + Random.nextDouble(-1.0, 1.0)).coerceIn(30.0, 80.0)
        val r = Reading(System.currentTimeMillis(), "local", temp, hum, fan)
        db.insertReading(r); show(r)
        codec?.let { link?.send(it.encode("DATA|${r.ts}|${"%.1f".format(Locale.US, r.temp)}|${"%.1f".format(Locale.US, r.hum)}|${if (r.fan) 1 else 0}")) }
    }

    // ---------- UI ----------
    private fun show(r: Reading) {
        b.tvTemp.text = "%.1f °C".format(r.temp)
        b.tvHum.text = "%.0f %%".format(r.hum)
        b.tvFan.text = "Vent: ${if (r.fan) "ON" else "OFF"}"
        fan = r.fan
        b.tvAlert.text = if (r.temp > alertThreshold) "⚠ ALERTA: temperatura sobre %.0f °C".format(alertThreshold) else ""
        b.tvTemp.setTextColor(if (r.temp > alertThreshold) Color.RED else Color.GRAY)
        refreshHistory()
    }

    private fun refreshHistory() {
        val rows = db.lastReadings(20).map {
            "${fmt.format(Date(it.ts))}  %.1f°C  %.0f%%  Vent:${if (it.fan) "ON" else "OFF"}  [${it.source}]".format(it.temp, it.hum)
        }
        b.lvHistory.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        b.tvAvg.text = db.avgTemp()?.let { "Promedio histórico: %.1f °C".format(it) } ?: "Promedio histórico: --"
    }

    private fun stopLink() {
        handler.removeCallbacks(ticker)
        link?.close(); link = null; connected = false
    }

    private fun logout() {
        stopLink(); codec = null
        startActivity(Intent(this, LoginActivity::class.java)); finish()
    }

    override fun onDestroy() { stopLink(); super.onDestroy() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
