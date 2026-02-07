package com.example.fanhunt

import com.example.fanhunt.model.Rewards
import com.example.fanhunt.model.UserProfile
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object RewardsManager {

    private val auth = FirebaseAuth.getInstance()
    private val fs = FirebaseFirestore.getInstance()

    fun redeemReward(
        rewardId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val uid = auth.currentUser?.uid
            ?: run {
                onError("Not signed in")
                return
            }

        val userRef = fs.collection("users").document(uid)
        val rewardRef = fs.collection("rewards").document(rewardId)
        val redemptionRef = fs.collection("redemptions").document()

        fs.runTransaction { tx ->

            // --- Fetch user ---
            val user = tx.get(userRef)
                .toObject(UserProfile::class.java)
                ?: throw Exception("User not found")

            // --- Fetch reward ---
            val reward = tx.get(rewardRef)
                .toObject(Rewards::class.java)
                ?: throw Exception("Reward not found")

            if (!reward.active) {
                throw Exception("Reward unavailable")
            }

            val currentPoints = user.totalPoints ?: 0
            if (currentPoints < reward.pointsRequired) {
                throw Exception("Not enough points")
            }

            val newPoints = currentPoints - reward.pointsRequired

            // --- Deduct points ---
            tx.update(userRef, "totalPoints", newPoints)

            // --- Record redemption ---
            tx.set(redemptionRef, mapOf(
                "userId" to uid,
                "rewardId" to rewardId,
                "pointsSpent" to reward.pointsRequired,
                "redeemedAt" to Timestamp.now()
            ))
        }
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { e ->
                onError(e.message ?: "Redemption failed")
            }
    }
}
