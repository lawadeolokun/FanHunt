package com.example.fanhunt

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fanhunt.model.UserProfile
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class LeaderboardFragment : Fragment(R.layout.fragment_leaderboard) {

    private val fs = FirebaseFirestore.getInstance()
    private lateinit var recycler: RecyclerView
    private val users = mutableListOf<UserProfile>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.rvLeaderboard)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        loadLeaderboard()
    }

    private fun loadLeaderboard() {
        fs.collection("users")
            .orderBy("totalPoints", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                users.clear()
                snap.documents.forEach { doc ->
                    val user = doc.toObject(UserProfile::class.java)
                    if (user != null) users.add(user)
                }

                recycler.adapter = LeaderboardAdapter(users)
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
    }
}
