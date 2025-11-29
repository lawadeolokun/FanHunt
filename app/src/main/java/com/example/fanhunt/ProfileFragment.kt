package com.example.fanhunt

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.fanhunt.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private val auth = FirebaseAuth.getInstance()
    private val fs = FirebaseFirestore.getInstance()

    private lateinit var tvName: TextView
    private lateinit var tvEmail: TextView
    private lateinit var spTeam: Spinner
    private lateinit var btnSave: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvName = view.findViewById(R.id.tvName)
        tvEmail = view.findViewById(R.id.tvEmail)
        spTeam = view.findViewById(R.id.spTeam)
        btnSave = view.findViewById(R.id.btnSave)

        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.teams,
            android.R.layout.simple_spinner_dropdown_item
        ).also { spTeam.adapter = it }

        val user = auth.currentUser
        val uid = user?.uid

        tvName.text = user?.displayName ?: "Fan"
        tvEmail.text = user?.email ?: ""

        if (uid == null) {
            Toast.makeText(requireContext(), "Not signed in", Toast.LENGTH_LONG).show()
            btnSave.isEnabled = false
            return
        }

        // Load profile
        fs.collection("users").document(uid).get()
            .addOnSuccessListener { snap ->
                val profile = snap.toObject(UserProfile::class.java)
                profile?.favouriteTeam?.let { fav ->
                    val adapter = spTeam.adapter as ArrayAdapter<String>
                    val idx = adapter.getPosition(fav)
                    if (idx >= 0) spTeam.setSelection(idx)

                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Load error: ${e.message}", Toast.LENGTH_LONG).show()
            }

        //  Save favourite team
        btnSave.setOnClickListener {
            val selected = spTeam.selectedItem?.toString().orEmpty()

            val data = mapOf(
                "displayName" to tvName.text.toString(),
                "email" to tvEmail.text.toString(),
                "favouriteTeam" to selected
            )

            fs.collection("users").document(uid)
                .set(data, SetOptions.merge())
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Save error: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}
