package cl.pallendes.iotmonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Enlace Bluetooth Classic (RFCOMM) entre dos dispositivos Android.
 * Se usa el socket seguro (autenticado y cifrado por el emparejamiento), no el "insecure".
 * Los permisos en tiempo de ejecución se verifican en MainActivity antes de usar esta clase.
 */
@SuppressLint("MissingPermission")
class BluetoothLink(private val adapter: BluetoothAdapter, private val cb: Callback) {

    interface Callback {
        fun onState(msg: String, connected: Boolean)
        fun onLine(line: String)
    }

    companion object {
        val APP_UUID: UUID = UUID.fromString("5b0f1c52-7c64-4d0e-9a4e-1f0a3c9b7e21")
    }

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    private fun state(msg: String, connected: Boolean) = main.post { if (!closed) cb.onState(msg, connected) }

    /** Rol servidor (nodo sensor): espera que el panel se conecte. */
    fun listen() {
        thread(name = "bt-listen") {
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("IoTMonitor", APP_UUID)
                state("Esperando al panel de control…", false)
                val s = server!!.accept()
                server?.close()
                handle(s)
            } catch (e: Exception) {
                if (!closed) state("Error al escuchar: ${e.message}", false)
            }
        }
    }

    /** Rol cliente (panel): se conecta a un nodo ya emparejado. */
    fun connect(device: BluetoothDevice) {
        thread(name = "bt-connect") {
            try {
                state("Conectando con ${device.name}…", false)
                val s = device.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                handle(s)
            } catch (e: Exception) {
                if (!closed) state("No se pudo conectar: ${e.message}", false)
            }
        }
    }

    private fun handle(s: BluetoothSocket) {
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.outputStream))
        state("Conectado con ${s.remoteDevice.name}", true)
        try {
            val r = BufferedReader(InputStreamReader(s.inputStream))
            while (true) {
                val line = r.readLine() ?: break
                if (line.length > 256) continue // límite de tamaño: descarta entradas anómalas
                main.post { if (!closed) cb.onLine(line) }
            }
        } catch (_: IOException) {
        }
        state("Conexión cerrada", false)
    }

    fun send(line: String) {
        sender.execute {
            try {
                writer?.apply { write(line); write("\n"); flush() }
            } catch (e: IOException) {
                state("Error al enviar: ${e.message}", false)
            }
        }
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        try { server?.close() } catch (_: IOException) {}
        sender.shutdownNow()
    }
}
