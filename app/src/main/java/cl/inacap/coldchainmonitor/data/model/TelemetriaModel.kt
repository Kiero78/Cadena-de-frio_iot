package cl.inacap.coldchainmonitor.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "historico_telemetria")
data class TelemetriaModel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @SerializedName("temp")
    val temperatura: Float,
    @SerializedName("puerta")
    val puertaAbierta: Int, // 0 = Cerrada, 1 = Abierta
    @SerializedName("estado")
    val estado: String,
    val timestamp: Long = System.currentTimeMillis()
)
