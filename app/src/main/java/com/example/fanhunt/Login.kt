package com.example.fanhunt

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fanhunt.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth

class Login : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun doLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val pass = binding.etPassword.text?.toString()?.trim().orEmpty()

        if (!isValid(email, pass)) return

        setLoading(true)
        auth.signInWithEmailAndPassword(email, pass).addOnCompleteListener { task ->
            setLoading(false)
            if (task.isSuccessful) {
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Login failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isValid(email: String, pass: String): Boolean {
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Enter a valid email"
            binding.etEmail.requestFocus()
            return false
        }
        if (pass.length < 6) {
            binding.etPassword.error = "Min 6 characters"
            binding.etPassword.requestFocus()
            return false
        }
        return true
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
    }
}
