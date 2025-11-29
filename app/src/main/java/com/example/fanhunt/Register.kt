package com.example.fanhunt

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fanhunt.databinding.ActivityRegisterBinding
import com.example.fanhunt.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val fs: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener { doRegister() }
        binding.tvGoLogin.setOnClickListener {
            startActivity(Intent(this, Login::class.java))
            finish()
        }
    }

    private fun doRegister() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val pass = binding.etPassword.text?.toString()?.trim().orEmpty()

        if (!isValid(name, email, pass)) return

        setLoading(true)
        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(name)
                        .build()
                    user?.updateProfile(profileUpdates)?.addOnCompleteListener {
                        createProfileInFirestore(name, email)
                        Toast.makeText(this, "Account created!", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, HomeActivity::class.java))
                        finish()
                    }
                } else {
                    setLoading(false)
                    Toast.makeText(
                        this,
                        "Registration failed: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun isValid(name: String, email: String, pass: String): Boolean {
        if (name.isBlank() || name.length < 2) {
            binding.etName.error = "Enter a valid name"
            return false
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Enter a valid email"
            return false
        }
        if (pass.length < 6) {
            binding.etPassword.error = "Password must be at least 6 chars"
            return false
        }
        return true
    }

    private fun createProfileInFirestore(name: String, email: String) {
        val uid = auth.currentUser?.uid ?: return
        val profile = UserProfile(
            displayName = name,
            email = email,
            favouriteTeam = "",
            totalPoints = 0
        )

        fs.collection("users")
            .document(uid)
            .set(profile)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile saved!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                android.util.Log.e("RegisterActivity", "Error saving profile", e)
                Toast.makeText(this, "Error saving profile: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }


    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !loading
    }
}
