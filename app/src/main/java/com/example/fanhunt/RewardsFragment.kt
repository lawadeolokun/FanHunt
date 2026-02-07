package com.example.fanhunt

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.fanhunt.R
import com.example.fanhunt.databinding.FragmentRewardsBinding
import com.example.fanhunt.model.Rewards
import com.example.fanhunt.RewardsManager
import com.google.firebase.firestore.FirebaseFirestore

class RewardsFragment : Fragment(R.layout.fragment_rewards) {

    private lateinit var binding: FragmentRewardsBinding
    private val fs = FirebaseFirestore.getInstance()

    private val rewardId = "rewards_200"


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentRewardsBinding.bind(view)

        loadReward()

        binding.btnRedeem.setOnClickListener {
            redeemReward()
        }
    }

    private fun loadReward() {
        fs.collection("rewards")
            .document(rewardId)
            .get()
            .addOnSuccessListener { doc ->
                val reward = doc.toObject(Rewards::class.java)
                if (reward != null) {
                    binding.tvRewardName.text = reward.name
                    binding.tvRewardCost.text =
                        "Cost: ${reward.pointsRequired} points"
                }
            }
    }

    private fun redeemReward() {
        RewardsManager.redeemReward(
            rewardId = rewardId,
            onSuccess = {
                Toast.makeText(
                    requireContext(),
                    "Reward redeemed!",
                    Toast.LENGTH_LONG
                ).show()
            },
            onError = {
                Toast.makeText(
                    requireContext(),
                    it,
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}
