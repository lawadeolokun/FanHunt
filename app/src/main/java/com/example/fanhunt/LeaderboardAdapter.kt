package com.example.fanhunt

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.fanhunt.model.UserProfile

class LeaderboardAdapter(
    private val users: List<UserProfile>
) : RecyclerView.Adapter<LeaderboardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rank: TextView = view.findViewById(R.id.tvRank)
        val name: TextView = view.findViewById(R.id.tvName)
        val points: TextView = view.findViewById(R.id.tvPoints)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_leaderboard, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = users[position]
        holder.rank.text = (position + 1).toString()
        holder.name.text = user.displayName
        holder.points.text = "${user.totalPoints} pts"
    }

    override fun getItemCount() = users.size
}
