package com.example.fanhunt

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.fanhunt.model.Rewards

class RewardAdapter(
    private val rewards: List<Rewards>,
    private val onRedeem: (Rewards) -> Unit
) : RecyclerView.Adapter<RewardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.rewardName)
        val cost: TextView = view.findViewById(R.id.rewardCost)
        val btn: Button = view.findViewById(R.id.btnRedeem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.reward_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = rewards.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val reward = rewards[position]

        holder.name.text = reward.name
        holder.cost.text = "${reward.pointsRequired} points"

        holder.btn.setOnClickListener {
            onRedeem(reward)
        }
    }
}