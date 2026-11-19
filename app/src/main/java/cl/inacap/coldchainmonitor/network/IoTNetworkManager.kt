package cl.inacap.coldchainmonitor.network

import cl.inacap.coldchainmonitor.data.model.TelemetriaModel
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class IoTNetworkManager(private val baseIp: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    suspend fun obtenerTelemetria(): Result<TelemetriaModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://$baseIp/api/telemetria")
            .get()
            .build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val cuerpo = response.body?.string() ?: ""
                val datos = gson.fromJson(cuerpo, TelemetriaModel::class.java)
                val conFecha = datos.copy(timestamp = System.currentTimeMillis())
                Result.success(conFecha)
            } else {
                Result.failure(IOException("HTTP Error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun enviarComando(comando: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val body = comando.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("http://$baseIp/api/comando")
            .post(body)
            .build()
        try {
            val response = client.newCall(request).execute()
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
