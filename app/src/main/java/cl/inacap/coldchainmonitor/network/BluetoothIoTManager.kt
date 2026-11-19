package cl.inacap.coldchainmonitor.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

class BluetoothIoTManager(context: Context) {

    companion object {
        // UUID estándar Serial Port Profile (compatible con HC-05 y otro celular Android)
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "ColdChainNodoOT"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var activeSocket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null

    val estaConectado: Boolean
        get() = activeSocket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun obtenerDispositivosVinculados(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    suspend fun conectarComoCliente(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cerrarConexion()
            adapter?.cancelDiscovery()
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            activeSocket = socket
            Result.success(Unit)
        } catch (e: IOException) {
            cerrarConexion()
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun iniciarServidorYEsperarCliente(): Result<String> = withContext(Dispatchers.IO) {
        try {
            cerrarConexion()
            val srv = adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                ?: return@withContext Result.failure(IOException("Adaptador Bluetooth no disponible"))
            serverSocket = srv
            val socket = srv.accept() // Bloquea hasta que el Monitor se conecte
            activeSocket = socket
            srv.close()
            serverSocket = null
            val nombreCliente = socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "Monitor"
            Result.success(nombreCliente)
        } catch (e: IOException) {
            cerrarConexion()
            Result.failure(e)
        }
    }

    suspend fun enviarLinea(mensaje: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val out = activeSocket?.outputStream
                ?: return@withContext Result.failure(IOException("Sin conexión Bluetooth activa"))
            out.write((mensaje + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            Result.success(true)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun escucharLineas(
        onLineaRecibida: suspend (String) -> Unit,
        onDesconectado: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val input = activeSocket?.inputStream ?: return@withContext
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            while (estaConectado) {
                val linea = reader.readLine() ?: break
                if (linea.isNotBlank()) {
                    onLineaRecibida(linea.trim())
                }
            }
            onDesconectado("Enlace Bluetooth cerrado")
        } catch (e: IOException) {
            onDesconectado(e.message ?: "Conexión Bluetooth perdida")
        }
    }

    fun cerrarConexion() {
        try { activeSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        serverSocket = null
    }
}
