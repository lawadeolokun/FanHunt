package com.example.fanhunt

import android.location.Location
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object PointsManager {

    private val auth = FirebaseAuth.getInstance()
    private val fs = FirebaseFirestore.getInstance()

    fun processQrScan(
        qrId: String,
        userLat: Double,
        userLng: Double,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val uid = auth.currentUser?.uid
            ?: run {
                onError("Not signed in")
                return
            }

        val userRef = fs.collection("users").document(uid)
        val codeRef = fs.collection("qrCodes").document(qrId)
        val redemptionRef = userRef.collection("redemptions").document(qrId)

        fs.runTransaction { tx ->

            val qrSnap = tx.get(codeRef)
            if (!qrSnap.exists()) throw Exception("Invalid QR code")
            if (qrSnap.getBoolean("active") != true) throw Exception("Code inactive")

            val qrLat = qrSnap.getDouble("latitude")
                ?: throw Exception("QR missing latitude")
            val qrLng = qrSnap.getDouble("longitude")
                ?: throw Exception("QR missing longitude")
            val radius = qrSnap.getLong("radius") ?: 50L

            val qrLocation = Location("").apply {
                latitude = qrLat
                longitude = qrLng
            }

            val userLocation = Location("").apply {
                latitude = userLat
                longitude = userLng
            }

            val distance = userLocation.distanceTo(qrLocation)
            if (distance > radius) {
                throw Exception("You must be at the checkpoint location")
            }

            if (tx.get(redemptionRef).exists()) {
                throw Exception("Already redeemed")
            }

            val points = qrSnap.getLong("points") ?: 0L
            val userSnap = tx.get(userRef)
            val currentPoints = userSnap.getLong("totalPoints") ?: 0L

            tx.update(userRef, "totalPoints", currentPoints + points)

            tx.set(
                redemptionRef,
                mapOf(
                    "codeId" to qrId,
                    "scannedAt" to FieldValue.serverTimestamp(),
                    "pointsAwarded" to points
                )
            )

            points
        }
            .addOnSuccessListener { points ->
                onSuccess(points)
            }
            .addOnFailureListener { e ->
                onError(e.message ?: "Scan failed")
            }
    }
}
