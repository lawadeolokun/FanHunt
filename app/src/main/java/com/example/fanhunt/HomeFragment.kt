package com.example.fanhunt

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import androidx.navigation.fragment.findNavController

class HomeFragment : Fragment(R.layout.fragment_home) {

    private val auth = FirebaseAuth.getInstance()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnLogout: Button = view.findViewById(R.id.btnLogout)
        val btnScan: Button = view.findViewById(R.id.btnScan)
        val btnAR: Button = view.findViewById(R.id.btnAR)

        // 🔐 Logout (unchanged)
        btnLogout.setOnClickListener {
            auth.signOut()

            val intent = Intent(requireContext(), Login::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }

        // 📷 Navigate to QR Scanner
        /*btnScan.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, ScanFragment())
                .addToBackStack(null)
                .commit()
        }
         */

        btnScan.setOnClickListener {
            findNavController().navigate(R.id.nav_scan)
        }

        /*
        // 🧭 Navigate to AR Screen
        btnAR.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, ARScreenFragment())
                .addToBackStack(null)
                .commit()
        }
        */
        btnAR.setOnClickListener {
            findNavController().navigate(R.id.arFragment)
        }
    }
}


