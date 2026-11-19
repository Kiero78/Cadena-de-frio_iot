package cl.inacap.coldchainmonitor.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cl.inacap.coldchainmonitor.data.model.TelemetriaModel
import java.security.MessageDigest

sealed class LoginResult {
    data class Exito(val email: String, val rol: String) : LoginResult()
    data class Fallo(val intentosRestantes: Int) : LoginResult()
    data class Bloqueado(val email: String) : LoginResult()
}

class ColdChainDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "coldchain_monitor.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_TELEMETRIA = "historico_telemetria"
        private const val TABLE_USUARIOS = "usuarios"

        const val ROL_SUPERVISOR = "SUPERVISOR"
        const val ROL_MANTENEDOR = "MANTENEDOR"
        const val ROL_OPERADOR = "OPERADOR"

        const val MAX_INTENTOS = 3
        const val BLOQUEO_PERMANENTE = -1L

        fun sha256(texto: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(texto.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TELEMETRIA (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                temp REAL NOT NULL,
                puerta INTEGER NOT NULL,
                estado TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_USUARIOS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                rol TEXT NOT NULL,
                intentos_fallidos INTEGER NOT NULL DEFAULT 0,
                bloqueado_hasta INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // Cuentas precargadas con contraseñas hasheadas en SHA-256 (RBAC 3 perfiles)
        insertarUsuarioInicial(db, "supervisor@inacap.cl", "Sup12345!", ROL_SUPERVISOR)
        insertarUsuarioInicial(db, "mantenedor@inacap.cl", "Man12345!", ROL_MANTENEDOR)
        insertarUsuarioInicial(db, "operador@inacap.cl", "Ope12345!", ROL_OPERADOR)
    }

    private fun insertarUsuarioInicial(db: SQLiteDatabase, email: String, pass: String, rol: String) {
        val values = ContentValues().apply {
            put("email", email.lowercase())
            put("password_hash", sha256(pass))
            put("rol", rol)
            put("intentos_fallidos", 0)
            put("bloqueado_hasta", 0L)
        }
        db.insert(TABLE_USUARIOS, null, values)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TELEMETRIA")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_USUARIOS")
        onCreate(db)
    }

    // --- AUTENTICACIÓN Y BLOQUEO TRAS 3 INTENTOS ---

    fun autenticarUsuario(emailInput: String, passwordInput: String): LoginResult {
        val email = emailInput.trim().lowercase()
        val db = writableDatabase

        val cursor = db.query(
            TABLE_USUARIOS,
            arrayOf("id", "password_hash", "rol", "intentos_fallidos", "bloqueado_hasta"),
            "email = ?",
            arrayOf(email),
            null, null, null
        )

        cursor.use {
            if (!it.moveToFirst()) {
                return LoginResult.Fallo(-1)
            }

            val id = it.getLong(0)
            val hashGuardado = it.getString(1)
            val rol = it.getString(2)
            val intentos = it.getInt(3)
            val bloqueadoHasta = it.getLong(4)

            // Si la cuenta está bloqueada permanentemente (bloqueadoHasta != 0)
            if (bloqueadoHasta != 0L) {
                return LoginResult.Bloqueado(email)
            }

            val hashIngresado = sha256(passwordInput)
            if (hashIngresado == hashGuardado) {
                actualizarIntentos(db, id, 0, 0L)
                return LoginResult.Exito(email, rol)
            } else {
                val nuevosIntentos = intentos + 1
                if (nuevosIntentos >= MAX_INTENTOS) {
                    actualizarIntentos(db, id, nuevosIntentos, BLOQUEO_PERMANENTE)
                    return LoginResult.Bloqueado(email)
                } else {
                    actualizarIntentos(db, id, nuevosIntentos, 0L)
                    return LoginResult.Fallo(MAX_INTENTOS - nuevosIntentos)
                }
            }
        }
    }

    fun desbloquearUsuario(emailInput: String): Boolean {
        val email = emailInput.trim().lowercase()
        val db = writableDatabase
        val values = ContentValues().apply {
            put("intentos_fallidos", 0)
            put("bloqueado_hasta", 0L)
        }
        val filas = db.update(TABLE_USUARIOS, values, "email = ?", arrayOf(email))
        return filas > 0
    }

    private fun actualizarIntentos(db: SQLiteDatabase, userId: Long, intentos: Int, bloqueadoHasta: Long) {
        val values = ContentValues().apply {
            put("intentos_fallidos", intentos)
            put("bloqueado_hasta", bloqueadoHasta)
        }
        db.update(TABLE_USUARIOS, values, "id = ?", arrayOf(userId.toString()))
    }

    // --- HISTORIAL DE TELEMETRÍA ---

    fun insertarTelemetria(dato: TelemetriaModel): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("temp", dato.temperatura)
            put("puerta", dato.puertaAbierta)
            put("estado", dato.estado)
            put("timestamp", dato.timestamp)
        }
        return db.insert(TABLE_TELEMETRIA, null, values)
    }

    fun obtenerHistorial(limite: Int = 100): List<TelemetriaModel> {
        val lista = mutableListOf<TelemetriaModel>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TELEMETRIA,
            arrayOf("id", "temp", "puerta", "estado", "timestamp"),
            null, null, null, null,
            "timestamp DESC",
            limite.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                lista.add(
                    TelemetriaModel(
                        id = it.getLong(0),
                        temperatura = it.getFloat(1),
                        puertaAbierta = it.getInt(2),
                        estado = it.getString(3),
                        timestamp = it.getLong(4)
                    )
                )
            }
        }
        return lista
    }

    fun limpiarHistorial() {
        val db = writableDatabase
        db.delete(TABLE_TELEMETRIA, null, null)
    }
}
