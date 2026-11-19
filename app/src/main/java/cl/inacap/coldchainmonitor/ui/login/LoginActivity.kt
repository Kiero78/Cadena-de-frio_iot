package cl.inacap.coldchainmonitor.ui.login

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.coldchainmonitor.data.db.ColdChainDbHelper
import cl.inacap.coldchainmonitor.data.db.LoginResult
import cl.inacap.coldchainmonitor.databinding.ActivityLoginBinding
import cl.inacap.coldchainmonitor.ui.main.DashboardActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var dbHelper: ColdChainDbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = ColdChainDbHelper(this)

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (validarCampos(email, password)) {
                ejecutarLoginSeguro(email, password)
            }
        }

        binding.btnDesbloquearGmail.setOnClickListener {
            val emailTarget = binding.etEmail.text.toString().trim()
            solicitarDesbloqueoGmail(emailTarget)
        }
    }

    private fun validarCampos(email: String, pass: String): Boolean {
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Ingrese un correo institucional válido"
            return false
        }
        if (pass.length < 6) {
            binding.etPassword.error = "La contraseña debe tener al menos 6 caracteres"
            return false
        }
        return true
    }

    private fun ejecutarLoginSeguro(email: String, pass: String) {
        when (val resultado = dbHelper.autenticarUsuario(email, pass)) {
            is LoginResult.Exito -> {
                binding.tvAlertaLogin.visibility = View.GONE
                binding.btnDesbloquearGmail.visibility = View.GONE
                Toast.makeText(
                    this,
                    "Acceso concedido (${resultado.rol})",
                    Toast.LENGTH_SHORT
                ).show()
                navegarAlDashboard(resultado.email, resultado.rol)
            }
            is LoginResult.Fallo -> {
                binding.tvAlertaLogin.visibility = View.VISIBLE
                binding.tvAlertaLogin.setTextColor(Color.parseColor("#C62828"))
                binding.btnDesbloquearGmail.visibility = View.GONE
                if (resultado.intentosRestantes > 0) {
                    binding.tvAlertaLogin.text =
                        "Contraseña incorrecta. Intentos restantes antes de bloqueo permanente: ${resultado.intentosRestantes}"
                } else {
                    binding.tvAlertaLogin.text = "Usuario no registrado en el sistema."
                }
            }
            is LoginResult.Bloqueado -> {
                mostrarBloqueoPermanente(resultado.email)
            }
        }
    }

    private fun mostrarBloqueoPermanente(email: String) {
        binding.btnLogin.isEnabled = false
        binding.etEmail.isEnabled = false
        binding.etPassword.isEnabled = false
        binding.tvAlertaLogin.visibility = View.VISIBLE
        binding.tvAlertaLogin.setTextColor(Color.parseColor("#B71C1C"))
        binding.tvAlertaLogin.text =
            "CUENTA BLOQUEADA PERMANENTEMENTE ($email) tras 3 intentos fallidos. Debe desbloquearla enviando un correo vía Gmail."
        binding.btnDesbloquearGmail.visibility = View.VISIBLE
    }

    private fun solicitarDesbloqueoGmail(email: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("soporte@inacap.cl"))
            putExtra(Intent.EXTRA_SUBJECT, "Solicitud de Desbloqueo de Cuenta - Cadena de Frío")
            putExtra(
                Intent.EXTRA_TEXT,
                "Solicito el desbloqueo de la cuenta de operador en la aplicación Cadena de Frío:\n\nCorreo: $email\nTimestamp: ${System.currentTimeMillis()}"
            )
        }

        try {
            startActivity(Intent.createChooser(intent, "Enviar correo de desbloqueo con Gmail..."))
        } catch (_: Exception) {
            Toast.makeText(this, "Abriendo solicitud de desbloqueo...", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setTitle("Confirmar Desbloqueo por Gmail")
            .setMessage("Se ha generado la solicitud por correo para la cuenta ($email). ¿Desea restablecer el acceso a este operador ahora?")
            .setPositiveButton("Restablecer Acceso") { _, _ ->
                val desbloqueado = dbHelper.desbloquearUsuario(email)
                if (desbloqueado) {
                    binding.btnLogin.isEnabled = true
                    binding.etEmail.isEnabled = true
                    binding.etPassword.isEnabled = true
                    binding.btnDesbloquearGmail.visibility = View.GONE
                    binding.tvAlertaLogin.text = "Acceso restablecido exitosamente vía Gmail. Inicie sesión nuevamente."
                    binding.tvAlertaLogin.setTextColor(Color.parseColor("#2E7D32"))
                    Toast.makeText(this, "Cuenta desbloqueada correctamente", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun navegarAlDashboard(email: String, rol: String) {
        val intent = Intent(this, DashboardActivity::class.java).apply {
            putExtra("USER_EMAIL", email)
            putExtra("USER_ROL", rol)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
}
