package cl.inacap.coldchainmonitor.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cl.inacap.coldchainmonitor.data.db.ColdChainDbHelper
import cl.inacap.coldchainmonitor.data.model.TelemetriaModel
import cl.inacap.coldchainmonitor.databinding.ActivityDashboardBinding
import cl.inacap.coldchainmonitor.network.BluetoothIoTManager
import cl.inacap.coldchainmonitor.network.IoTNetworkManager
import cl.inacap.coldchainmonitor.ui.emitter.NodoEmisorActivity
import cl.inacap.coldchainmonitor.ui.history.HistorialActivity
import cl.inacap.coldchainmonitor.ui.login.LoginActivity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var dbHelper: ColdChainDbHelper
    private lateinit var btManager: BluetoothIoTManager
    private val gson = Gson()

    private var networkManager: IoTNetworkManager? = null
    private var pollingJob: Job? = null
    private var btJob: Job? = null
    private var ultimaTramaValidaMs: Long = 0L
    private val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private var userEmail: String = ""
    private var userRol: String = ColdChainDbHelper.ROL_OPERADOR
    private var simulando: Boolean = false
    private var accionPendienteBt: (() -> Unit)? = null

    private val permisoBtLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        val concedido = resultados.values.all { it }
        if (concedido) {
            accionPendienteBt?.invoke()
        } else {
            Toast.makeText(this, "Se requieren permisos Bluetooth para enlazar dispositivos", Toast.LENGTH_LONG).show()
        }
        accionPendienteBt = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = ColdChainDbHelper(this)
        btManager = BluetoothIoTManager(this)

        userEmail = intent.getStringExtra("USER_EMAIL") ?: "usuario@inacap.cl"
        userRol = intent.getStringExtra("USER_ROL") ?: ColdChainDbHelper.ROL_OPERADOR

        aplicarPermisosRBAC()

        binding.btnConectar.setOnClickListener {
            val ip = binding.etIpNodo.text.toString().trim()
            if (ip.isEmpty()) {
                binding.etIpNodo.error = "Ingrese la IP del nodo"
                return@setOnClickListener
            }
            detenerConexionesActivas()
            iniciarMonitoreoWifi(ip)
        }

        binding.btnBluetooth.setOnClickListener {
            if (btManager.estaConectado) {
                detenerConexionesActivas()
                binding.tvEstadoEnlace.text = "ENLACE BLUETOOTH DESCONECTADO"
                binding.tvEstadoEnlace.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E8EEF3"))
                binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.text_body))
            } else {
                verificarPermisosBluetooth { mostrarSelectorDispositivosBt() }
            }
        }

        binding.btnModoEmisor.setOnClickListener {
            detenerConexionesActivas()
            val intent = Intent(this, NodoEmisorActivity::class.java).apply {
                putExtra("USER_ROL", userRol)
            }
            startActivity(intent)
        }

        binding.btnSimular.setOnClickListener {
            if (!simulando) {
                detenerConexionesActivas()
                simulando = true
                binding.btnSimular.text = "Detener Simulación"
                iniciarSimulacion()
            } else {
                detenerConexionesActivas()
                binding.tvEstadoEnlace.text = "SIMULACIÓN DETENIDA"
                binding.tvEstadoEnlace.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E8EEF3"))
                binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.text_body))
            }
        }

        binding.btnHistorial.setOnClickListener {
            val intent = Intent(this, HistorialActivity::class.java).apply {
                putExtra("USER_ROL", userRol)
            }
            startActivity(intent)
        }

        binding.btnCerrarSesion.setOnClickListener {
            detenerConexionesActivas()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.btnSilenciar.setOnClickListener {
            confirmarYEnviarComando("CMD:SILENCIO", "¿Silenciar la alarma sonora de la cámara?")
        }
        binding.btnModoAuto.setOnClickListener {
            confirmarYEnviarComando("CMD:AUTO", "¿Restablecer modo automático de alarmas?")
        }
        binding.btnTest.setOnClickListener {
            confirmarYEnviarComando("CMD:TEST", "¿Ejecutar prueba de LEDs y buzzer?")
        }
    }

    private fun aplicarPermisosRBAC() {
        val (bgRes, textRes) = when (userRol) {
            ColdChainDbHelper.ROL_SUPERVISOR -> Pair(cl.inacap.coldchainmonitor.R.color.role_supervisor_bg, cl.inacap.coldchainmonitor.R.color.role_supervisor)
            ColdChainDbHelper.ROL_MANTENEDOR -> Pair(cl.inacap.coldchainmonitor.R.color.role_mantenedor_bg, cl.inacap.coldchainmonitor.R.color.role_mantenedor)
            else -> Pair(cl.inacap.coldchainmonitor.R.color.role_operador_bg, cl.inacap.coldchainmonitor.R.color.role_operador)
        }
        binding.tvUsuarioActivo.text = "Operador: $userEmail  •  Rol: $userRol"
        binding.tvUsuarioActivo.backgroundTintList = ContextCompat.getColorStateList(this, bgRes)
        binding.tvUsuarioActivo.setTextColor(ContextCompat.getColor(this, textRes))

        when (userRol) {
            ColdChainDbHelper.ROL_SUPERVISOR -> {
                // Control total
                binding.btnSilenciar.isEnabled = true
                binding.btnModoAuto.isEnabled = true
                binding.btnTest.isEnabled = true
                binding.btnBluetooth.isEnabled = true
                binding.btnModoEmisor.isEnabled = true
                binding.tvTituloComandos.text = "Control Operativo Remoto (Supervisor - Control Total)"
            }
            ColdChainDbHelper.ROL_MANTENEDOR -> {
                // Mantenimiento y pruebas: Bluetooth, Modo Emisor, Auto y Test habilitados; Silenciar bloqueado
                binding.btnSilenciar.isEnabled = false
                binding.btnModoAuto.isEnabled = true
                binding.btnTest.isEnabled = true
                binding.btnBluetooth.isEnabled = true
                binding.btnModoEmisor.isEnabled = true
                binding.tvTituloComandos.text = "Control Operativo Remoto (Mantenedor: Auto/Test activos)"
            }
            else -> {
                // OPERADOR: Solo lectura (monitor en vivo e historial)
                binding.btnSilenciar.isEnabled = false
                binding.btnModoAuto.isEnabled = false
                binding.btnTest.isEnabled = false
                binding.btnBluetooth.isEnabled = false
                binding.btnModoEmisor.isEnabled = false
                binding.tvTituloComandos.text = "Control Operativo Remoto (Operador - Solo Lectura)"
            }
        }
    }

    private fun detenerConexionesActivas() {
        simulando = false
        binding.btnSimular.text = "Simular Datos"
        binding.btnBluetooth.text = "Conectar Bluetooth"
        pollingJob?.cancel()
        btJob?.cancel()
        btManager.cerrarConexion()
    }

    private fun verificarPermisosBluetooth(onConcedido: () -> Unit) {
        val adapter = btManager.adapter
        if (adapter == null) {
            Toast.makeText(this, "Este dispositivo no cuenta con Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth del teléfono antes de conectar", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permisos = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val faltantes = permisos.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (faltantes.isNotEmpty()) {
                accionPendienteBt = onConcedido
                permisoBtLauncher.launch(faltantes.toTypedArray())
                return
            }
        }
        onConcedido()
    }

    @SuppressLint("MissingPermission")
    private fun mostrarSelectorDispositivosBt() {
        val vinculados = btManager.obtenerDispositivosVinculados()
        if (vinculados.isEmpty()) {
            Toast.makeText(
                this,
                "No hay dispositivos vinculados. Empareja primero el otro teléfono o HC-05 en Ajustes > Bluetooth.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val etiquetas = vinculados.map { "${it.name ?: "Dispositivo"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Nodo Bluetooth")
            .setItems(etiquetas) { _, index ->
                conectarDispositivoBt(vinculados[index])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun conectarDispositivoBt(device: BluetoothDevice) {
        detenerConexionesActivas()
        val nombre = device.name ?: device.address
        binding.tvEstadoEnlace.text = "CONECTANDO BLUETOOTH A $nombre..."
        binding.tvEstadoEnlace.backgroundTintList = ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.status_warning_bg)
        binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.status_warning))

        btJob = lifecycleScope.launch {
            val res = btManager.conectarComoCliente(device)
            if (res.isSuccess) {
                binding.btnBluetooth.text = "Desconectar BT"
                binding.tvEstadoEnlace.text = "ENLACE BLUETOOTH: ACTIVO ($nombre)"
                binding.tvEstadoEnlace.backgroundTintList = ContextCompat.getColorStateList(this@DashboardActivity, cl.inacap.coldchainmonitor.R.color.temp_safe_bg)
                binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this@DashboardActivity, cl.inacap.coldchainmonitor.R.color.temp_safe_dark))

                btManager.escucharLineas(
                    onLineaRecibida = { jsonLine ->
                        try {
                            val parsed = gson.fromJson(jsonLine, TelemetriaModel::class.java)
                            val dato = parsed.copy(timestamp = System.currentTimeMillis())
                            withContext(Dispatchers.Main) {
                                actualizarUI(dato)
                            }
                            guardarEnHistorial(dato)
                        } catch (_: Exception) {
                            // Ignorar líneas no JSON
                        }
                    },
                    onDesconectado = { motivo ->
                        withContext(Dispatchers.Main) {
                            binding.btnBluetooth.text = "Conectar Bluetooth"
                            binding.tvEstadoEnlace.text = "BLUETOOTH DESCONECTADO: $motivo"
                            binding.tvEstadoEnlace.backgroundTintList = ContextCompat.getColorStateList(this@DashboardActivity, cl.inacap.coldchainmonitor.R.color.temp_critical_bg)
                            binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this@DashboardActivity, cl.inacap.coldchainmonitor.R.color.temp_critical))
                        }
                    }
                )
            } else {
                binding.tvEstadoEnlace.text = "ERROR BLUETOOTH: Verifique que el Nodo Emisor esté escuchando"
                binding.tvEstadoEnlace.backgroundTintList = ContextCompat.getColorStateList(this@DashboardActivity, cl.inacap.coldchainmonitor.R.color.temp_critical_bg)
                binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this@DashboardActivity, cl.inacap.coldchainmonitor.R.color.temp_critical))
            }
        }
    }

    private fun iniciarMonitoreoWifi(ip: String) {
        pollingJob?.cancel()
        networkManager = IoTNetworkManager(ip)
        ultimaTramaValidaMs = System.currentTimeMillis()

        pollingJob = lifecycleScope.launch {
            while (isActive) {
                val resultado = networkManager?.obtenerTelemetria()
                resultado?.onSuccess { datos ->
                    ultimaTramaValidaMs = System.currentTimeMillis()
                    binding.tvEstadoEnlace.text = "ENLACE WI-FI: ACTIVO ($ip)"
                    binding.tvEstadoEnlace.backgroundTintList = ContextCompat.getColorStateList(this@DashboardActivity, cl.inacap.coldchainmonitor.R.color.temp_safe_bg)
                    binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this@DashboardActivity, cl.inacap.coldchainmonitor.R.color.temp_safe_dark))
                    actualizarUI(datos)
                    guardarEnHistorial(datos)
                }?.onFailure {
                    verificarWatchdog()
                }
                delay(2000L)
            }
        }
    }

    private fun iniciarSimulacion() {
        pollingJob?.cancel()
        binding.tvEstadoEnlace.text = "MODO SIMULACIÓN LOCAL ACTIVO"
        binding.tvEstadoEnlace.backgroundTintList = ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.status_warning_bg)
        binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.status_warning))

        pollingJob = lifecycleScope.launch {
            while (isActive && simulando) {
                val tempSim = Random.nextDouble(1.0, 9.5).toFloat()
                val puertaSim = if (Random.nextInt(0, 5) == 0) 1 else 0
                val estadoSim = when {
                    tempSim < 2.0f -> "CONGELAMIENTO"
                    tempSim > 8.0f -> "SOBRETEMPERATURA"
                    else -> "NORMAL"
                }
                val dato = TelemetriaModel(
                    temperatura = tempSim,
                    puertaAbierta = puertaSim,
                    estado = estadoSim,
                    timestamp = System.currentTimeMillis()
                )
                actualizarUI(dato)
                guardarEnHistorial(dato)
                delay(2500L)
            }
        }
    }

    private fun guardarEnHistorial(dato: TelemetriaModel) {
        lifecycleScope.launch(Dispatchers.IO) {
            dbHelper.insertarTelemetria(dato)
        }
    }

    private fun verificarWatchdog() {
        val sinRespuestaMs = System.currentTimeMillis() - ultimaTramaValidaMs
        if (sinRespuestaMs >= 5000L) {
            binding.tvEstadoEnlace.text = "!ALERTA: FALLA DE ENLACE (> 5s)!"
            binding.tvEstadoEnlace.backgroundTintList = ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.temp_critical_bg)
            binding.tvEstadoEnlace.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_critical))
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
        }
    }

    private fun actualizarUI(datos: TelemetriaModel) {
        if (datos.temperatura >= 900.0f || datos.temperatura < -40.0f) {
            binding.tvTemperatura.text = "ERR"
            binding.tvEstadoTermico.text = "!FALLA DE SENSOR!"
            binding.cardTemperatura.setCardBackgroundColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_critical_dark))
            toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)
            return
        }

        binding.tvTemperatura.text = String.format(java.util.Locale.US, "%.1f °C", datos.temperatura)

        when {
            datos.temperatura < 2.0f -> {
                binding.cardTemperatura.setCardBackgroundColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_freeze))
                binding.tvEstadoTermico.text = "!ALERTA: CONGELAMIENTO (< 2.0°C)!"
                toneGen.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300)
            }
            datos.temperatura > 8.0f -> {
                binding.cardTemperatura.setCardBackgroundColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_critical))
                binding.tvEstadoTermico.text = "!ALERTA CRÍTICA: SOBRETEMPERATURA!"
                toneGen.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300)
            }
            else -> {
                binding.cardTemperatura.setCardBackgroundColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_safe))
                binding.tvEstadoTermico.text = "RANGO SEGURO (2.0 °C - 8.0 °C)"
            }
        }

        if (datos.puertaAbierta == 1) {
            binding.tvPuerta.text = "COMPUERTA: ABIERTA (ALERTA)"
            binding.tvPuerta.backgroundTintList = ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.door_open_bg)
            binding.tvPuerta.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.door_open_text))
        } else {
            binding.tvPuerta.text = "COMPUERTA: CERRADA (ESTABLE)"
            binding.tvPuerta.backgroundTintList = ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.door_closed_bg)
            binding.tvPuerta.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.door_closed_text))
        }
    }

    private fun confirmarYEnviarComando(comando: String, mensaje: String) {
        val usandoBt = btManager.estaConectado
        if (!usandoBt && networkManager == null) {
            Toast.makeText(
                this,
                "Primero conéctese por Wi-Fi o por Bluetooth al nodo",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Confirmar Comando OT")
            .setMessage(mensaje)
            .setPositiveButton("Ejecutar") { _, _ ->
                lifecycleScope.launch {
                    val enviado = if (usandoBt) {
                        btManager.enviarLinea(comando).getOrDefault(false)
                    } else {
                        networkManager?.enviarComando(comando)?.getOrDefault(false) == true
                    }
                    if (enviado) {
                        val via = if (usandoBt) "Bluetooth" else "Wi-Fi"
                        Toast.makeText(
                            this@DashboardActivity,
                            "Comando $comando enviado vía $via",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@DashboardActivity,
                            "Error al enviar comando",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        detenerConexionesActivas()
        toneGen.release()
        dbHelper.close()
    }
}
