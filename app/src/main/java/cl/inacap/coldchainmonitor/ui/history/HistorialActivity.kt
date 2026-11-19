package cl.inacap.coldchainmonitor.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.coldchainmonitor.R
import cl.inacap.coldchainmonitor.data.db.ColdChainDbHelper
import cl.inacap.coldchainmonitor.data.model.TelemetriaModel
import cl.inacap.coldchainmonitor.databinding.ActivityHistorialBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistorialBinding
    private lateinit var dbHelper: ColdChainDbHelper
    private var userRol: String = ColdChainDbHelper.ROL_OPERADOR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = ColdChainDbHelper(this)
        userRol = intent.getStringExtra("USER_ROL") ?: ColdChainDbHelper.ROL_OPERADOR

        binding.btnVolver.setOnClickListener { finish() }
        binding.btnActualizar.setOnClickListener { cargarHistorial() }

        // Solo Supervisor puede limpiar el historial
        binding.btnLimpiar.isEnabled = (userRol == ColdChainDbHelper.ROL_SUPERVISOR)
        binding.btnLimpiar.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpiar Historial")
                .setMessage("¿Desea eliminar todos los registros guardados en SQLite?")
                .setPositiveButton("Eliminar") { _, _ ->
                    dbHelper.limpiarHistorial()
                    cargarHistorial()
                    Toast.makeText(this, "Historial limpiado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        cargarHistorial()
    }

    private fun cargarHistorial() {
        val registros = dbHelper.obtenerHistorial(100)
        binding.tvContadorRegistros.text = "Registros guardados: ${registros.size}"
        binding.lvHistorial.adapter = HistorialAdapter(registros)
    }

    private inner class HistorialAdapter(private val items: List<TelemetriaModel>) : BaseAdapter() {
        private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@HistorialActivity)
                .inflate(R.layout.item_historial, parent, false)

            val item = items[position]
            val tvFecha = view.findViewById<TextView>(R.id.tvItemFecha)
            val tvDetalle = view.findViewById<TextView>(R.id.tvItemDetalle)
            val tvTempBadge = view.findViewById<TextView>(R.id.tvItemTempBadge)
            val tvEstadoPill = view.findViewById<TextView>(R.id.tvItemEstadoPill)

            val puertaTxt = if (item.puertaAbierta == 1) "ABIERTA" else "CERRADA"
            tvFecha.text = sdf.format(Date(item.timestamp))
            tvTempBadge.text = String.format(Locale.US, "%.1f°C", item.temperatura)
            tvDetalle.text = "Puerta: $puertaTxt | Estado: ${item.estado}"
            tvEstadoPill.text = item.estado

            when {
                item.temperatura < 2.0f -> {
                    tvTempBadge.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this@HistorialActivity, R.color.temp_freeze_bg)
                    tvTempBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this@HistorialActivity, R.color.temp_freeze))
                    tvEstadoPill.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this@HistorialActivity, R.color.temp_freeze_bg)
                    tvEstadoPill.setTextColor(androidx.core.content.ContextCompat.getColor(this@HistorialActivity, R.color.temp_freeze))
                }
                item.temperatura > 8.0f -> {
                    tvTempBadge.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this@HistorialActivity, R.color.temp_critical_bg)
                    tvTempBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this@HistorialActivity, R.color.temp_critical))
                    tvEstadoPill.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this@HistorialActivity, R.color.temp_critical_bg)
                    tvEstadoPill.setTextColor(androidx.core.content.ContextCompat.getColor(this@HistorialActivity, R.color.temp_critical))
                }
                else -> {
                    tvTempBadge.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this@HistorialActivity, R.color.temp_safe_bg)
                    tvTempBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this@HistorialActivity, R.color.temp_safe))
                    tvEstadoPill.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(this@HistorialActivity, R.color.temp_safe_bg)
                    tvEstadoPill.setTextColor(androidx.core.content.ContextCompat.getColor(this@HistorialActivity, R.color.temp_safe))
                }
            }
            return view
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
}
