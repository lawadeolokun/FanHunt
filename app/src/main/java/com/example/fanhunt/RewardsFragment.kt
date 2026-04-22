package com.example.fanhunt

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fanhunt.model.PhotoPost
import com.example.fanhunt.model.Rewards
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage

class RewardsFragment : Fragment(R.layout.fragment_rewards) {

    // 🔹 RECYCLERS
    private lateinit var photoRecycler: RecyclerView

    // 🔹 ADAPTERS
    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var rewardAdapter: RewardAdapter

    // 🔹 DATA
    private val photos = mutableListOf<PhotoPost>()
    private val rewards = mutableListOf<Rewards>()

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // 🔹 IMAGE PICKER
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { uploadImage(it) }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        // 🔹 INIT VIEWS
        photoRecycler = view.findViewById(R.id.photoRecycler)

        // 🔹 PHOTO ADAPTER
        photoAdapter = PhotoAdapter(
            photos,
            onUploadClick = {
                pickImage.launch("image/*")
            },
            onVoteClick = { votePhoto(it) }
        )

        photoRecycler.layoutManager = LinearLayoutManager(requireContext())
        photoRecycler.adapter = photoAdapter

        // 🔹 REWARD ADAPTER
        rewardAdapter = RewardAdapter(rewards) { reward ->

            RewardsManager.redeemReward(
                reward.id,
                onSuccess = {
                    Toast.makeText(context, "Redeemed!", Toast.LENGTH_SHORT).show()
                },
                onError = {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
            )
        }

                // 🔹 LOAD DATA
        loadPhotos()
        loadRewards()
    }

    // =========================
    // 🔥 LOAD REWARDS
    // =========================
    private fun loadRewards() {

        db.collection("rewards")
            .whereEqualTo("active", true)
            .get()
            .addOnSuccessListener { result ->

                val rewardsList = result.documents.mapNotNull {
                    it.toObject(Rewards::class.java)?.apply {
                        id = it.id
                    }
                }

                photoAdapter.setRewards(rewardsList)
            }
    }

    // =========================
    // 📸 LOAD PHOTOS
    // =========================
    private fun loadPhotos() {

        db.collection("photo_posts")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->

                photos.clear()

                snapshot?.documents?.forEach { doc ->
                    val photo = doc.toObject(PhotoPost::class.java)
                    if (photo != null) {
                        photo.id = doc.id
                        photos.add(photo)
                    }
                }

                photoAdapter.notifyDataSetChanged()
            }
    }

    // =========================
    // ⬆️ UPLOAD IMAGE
    // =========================
    private fun uploadImage(uri: Uri) {

        val storageRef = FirebaseStorage.getInstance()
            .reference.child("photos/${System.currentTimeMillis()}.jpg")

        storageRef.putFile(uri)
            .continueWithTask { storageRef.downloadUrl }
            .addOnSuccessListener { downloadUrl ->

                val userId = auth.currentUser?.uid ?: ""

                val post = hashMapOf(
                    "userId" to userId,
                    "imageUrl" to downloadUrl.toString(),
                    "caption" to "Fan Photo",
                    "votes" to 0,
                    "timestamp" to System.currentTimeMillis(),
                    "votedBy" to listOf<String>()
                )

                db.collection("photo_posts").add(post)
            }
    }

    // =========================
    // 👍 VOTE
    // =========================
    private fun votePhoto(photo: PhotoPost) {

        val userId = auth.currentUser?.uid ?: return

        // 🔒 PREVENT DOUBLE VOTE
        if (photo.votedBy.contains(userId)) {
            Toast.makeText(context, "Already voted", Toast.LENGTH_SHORT).show()
            return
        }

        db.collection("photo_posts")
            .document(photo.id)
            .update(
                mapOf(
                    "votes" to FieldValue.increment(1),
                    "votedBy" to FieldValue.arrayUnion(userId)
                )
            )
            .addOnSuccessListener {

                Toast.makeText(context, "Vote added", Toast.LENGTH_SHORT).show()

                // 🔥 UPDATE UI INSTANTLY
                photo.votedBy.add(userId)
                photo.votes += 1

                photoAdapter.notifyDataSetChanged()
            }
            .addOnFailureListener {
                Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            }
    }
}