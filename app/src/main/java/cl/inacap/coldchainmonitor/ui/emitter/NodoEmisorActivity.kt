package cl.inacap.coldchainmonitor.ui.emitter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cl.inacap.coldchainmonitor.data.model.TelemetriaModel
import cl.inacap.coldchainmonitor.databinding.ActivityNodoEmisorBinding
import cl.inacap.coldchainmonitor.network.BluetoothIoTManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NodoEmisorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodoEmisorBinding
    private lateinit var btManager: BluetoothIoTManager
    private val gson = Gson()
    private val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var serverJob: Job? = null
    private var txJob: Job? = null
    private var temperaturaActual: Float = 4.5f
    private var puertaAbierta: Int = 0
    private var fallaSensorActiva: Boolean = false
    private var alarmaSilenciada: Boolean = false

    private val permisoLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        if (resultados.values.all { it }) {
            iniciarServidorNodo()
        } else {
            Toast.makeText(this, "Permiso Bluetooth requerido para iniciar el Modo Nodo Emisor", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodoEmisorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        btManager = BluetoothIoTManager(this)

        // SeekBar: 0..200 representa -5.0 °C a 15.0 °C (pasos de 0.1 °C)
        binding.sbTemperatura.max = 200
        binding.sbTemperatura.progress = 95 // 4.5 °C
        actualizarEtiquetaTemp()

        binding.sbTemperatura.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fallaSensorActiva = false
                temperaturaActual = -5.0f + (progress / 10.0f)
                actualizarEtiquetaTemp()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.swPuerta.setOnCheckedChangeListener { _, isChecked ->
            puertaAbierta = if (isChecked) 1 else 0
            binding.swPuerta.text = if (isChecked) "Compuerta: ABIERTA" else "Compuerta: CERRADA"
        }

        binding.btnFallaSensor.setOnClickListener {
            fallaSensorActiva = !fallaSensorActiva
            binding.btnFallaSensor.text = if (fallaSensorActiva) "Restaurar Sensor" else "Simular Falla Sensor (ERR)"
            actualizarEtiquetaTemp()
        }

        binding.btnIniciarServidor.setOnClickListener {
            if (btManager.estaConectado || serverJob?.isActive == true) {
                detenerServidor()
            } else {
                verificarPermisosYArrancar()
            }
        }

        binding.btnVolverDashboard.setOnClickListener {
            detenerServidor()
            finish()
        }
    }

    private fun actualizarEtiquetaTemp() {
        if (fallaSensorActiva) {
            binding.tvTempEmisor.text = "Temperatura a emitir: ERR (Falla Sensor)"
            binding.tvTempEmisor.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.temp_critical_bg)
            binding.tvTempEmisor.setTextColor(androidx.core.content.ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_critical))
        } else {
            binding.tvTempEmisor.text = String.format("Temperatura a emitir: %.1f °C", temperaturaActual)
            when {
                temperaturaActual < 2.0f -> {
                    binding.tvTempEmisor.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.temp_freeze_bg)
                    binding.tvTempEmisor.setTextColor(androidx.core.content.ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_freeze))
                }
                temperaturaActual > 8.0f -> {
                    binding.tvTempEmisor.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.temp_critical_bg)
                    binding.tvTempEmisor.setTextColor(androidx.core.content.ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_critical))
                }
                else -> {
                    binding.tvTempEmisor.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.temp_safe_bg)
                    binding.tvTempEmisor.setTextColor(androidx.core.content.ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.temp_safe))
                }
            }
        }
    }

    private fun verificarPermisosYArrancar() {
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth en este dispositivo primero", Toast.LENGTH_LONG).show()
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
                permisoLauncher.launch(faltantes.toTypedArray())
                return
            }
        }
        iniciarServidorNodo()
    }

    private fun iniciarServidorNodo() {
        detenerServidor()
        binding.btnIniciarServidor.text = "Detener Servidor Bluetooth"
        binding.tvEstadoServidor.text = "ESPERANDO CONEXIÓN DEL MONITOR..."
        binding.tvEstadoServidor.backgroundTintList = ContextCompat.getColorStateList(this, cl.inacap.coldchainmonitor.R.color.status_warning_bg)
        binding.tvEstadoServidor.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.status_warning))
        agregarLog("Servidor Bluetooth abierto. Conéctate desde el celular Monitor.")

        serverJob = lifecycleScope.launch {
            val res = btManager.iniciarServidorYEsperarCliente()
            if (res.isSuccess) {
                val cliente = res.getOrDefault("Monitor")
                binding.tvEstadoServidor.text = "CONECTADO A: $cliente (EMITIENDO)"
                binding.tvEstadoServidor.backgroundTintList = ContextCompat.getColorStateList(this@NodoEmisorActivity, cl.inacap.coldchainmonitor.R.color.temp_safe_bg)
                binding.tvEstadoServidor.setTextColor(ContextCompat.getColor(this@NodoEmisorActivity, cl.inacap.coldchainmonitor.R.color.temp_safe))
                agregarLog("Monitor conectado: $cliente")

                iniciarTransmisionPeriodica()

                btManager.escucharLineas(
                    onLineaRecibida = { cmd ->
                        withContext(Dispatchers.Main) {
                            procesarComandoRecibido(cmd)
                        }
                    },
                    onDesconectado = { motivo ->
                        withContext(Dispatchers.Main) {
                            agregarLog("Desconectado: $motivo")
                            detenerServidor()
                        }
                    }
                )
            } else {
                agregarLog("Error al esperar conexión: ${res.exceptionOrNull()?.message}")
                detenerServidor()
            }
        }
    }

    private fun iniciarTransmisionPeriodica() {
        txJob?.cancel()
        txJob = lifecycleScope.launch {
            while (isActive && btManager.estaConectado) {
                val tempEnviar = if (fallaSensorActiva) 999.0f else temperaturaActual
                val estado = when {
                    fallaSensorActiva -> "FALLA_SENSOR"
                    tempEnviar < 2.0f -> "CONGELAMIENTO"
                    tempEnviar > 8.0f -> "SOBRETEMPERATURA"
                    else -> "NORMAL"
                }
                val trama = TelemetriaModel(
                    temperatura = tempEnviar,
                    puertaAbierta = puertaAbierta,
                    estado = estado
                )
                val json = gson.toJson(trama)
                btManager.enviarLinea(json)
                delay(2000L)
            }
        }
    }

    private fun procesarComandoRecibido(comando: String) {
        agregarLog("COMANDO RECIBIDO -> $comando")
        when (comando) {
            "CMD:SILENCIO" -> {
                alarmaSilenciada = true
                Toast.makeText(this, "Alarma silenciada remotamente", Toast.LENGTH_SHORT).show()
            }
            "CMD:AUTO" -> {
                alarmaSilenciada = false
                Toast.makeText(this, "Modo automático restablecido", Toast.LENGTH_SHORT).show()
            }
            "CMD:TEST" -> {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
                Toast.makeText(this, "Test de actuadores ejecutado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun agregarLog(texto: String) {
        val hora = sdf.format(Date())
        val actual = binding.tvLogEmisor.text.toString()
        binding.tvLogEmisor.text = "[$hora] $texto\n$actual"
    }

    private fun detenerServidor() {
        txJob?.cancel()
        serverJob?.cancel()
        btManager.cerrarConexion()
        binding.btnIniciarServidor.text = "Iniciar Servidor Bluetooth"
        binding.tvEstadoServidor.text = "NODO EMISOR DETENIDO"
        binding.tvEstadoServidor.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E8EEF3"))
        binding.tvEstadoServidor.setTextColor(ContextCompat.getColor(this, cl.inacap.coldchainmonitor.R.color.text_body))
    }

    override fun onDestroy() {
        super.onDestroy()
        detenerServidor()
        toneGen.release()
    }
}
