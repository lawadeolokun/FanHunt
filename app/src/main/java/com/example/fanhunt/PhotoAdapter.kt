package com.example.fanhunt

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.fanhunt.model.PhotoPost
import com.example.fanhunt.model.Rewards

class PhotoAdapter(
    private val photos: List<PhotoPost>,
    private val onUploadClick: () -> Unit,
    private val onVoteClick: (PhotoPost) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    private var rewards: List<Rewards> = emptyList()

    fun setRewards(list: List<Rewards>) {
        rewards = list
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = photos.size + 1

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {

        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.header_rewards, parent, false)
            HeaderViewHolder(view, onUploadClick)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.photo_item, parent, false)
            PhotoViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

        // 🔥 HEADER
        if (holder is HeaderViewHolder) {
            holder.bindTop3(photos)
            holder.bindRewards(rewards)
        }

        // 🔥 PHOTO ITEMS
        if (holder is PhotoViewHolder) {

            val photo = photos[position - 1]

            Glide.with(holder.itemView.context)
                .load(photo.imageUrl)
                .into(holder.image)

            holder.caption.text = photo.caption
            holder.votes.text = "Votes: ${photo.votes}"

            holder.voteBtn.setOnClickListener {
                onVoteClick(photo)
            }
        }
    }

    // =========================
    // 🔥 HEADER VIEW HOLDER
    // =========================

    class HeaderViewHolder(
        view: View,
        private val onUploadClick: () -> Unit
    ) : RecyclerView.ViewHolder(view) {

        init {
            val uploadBtn = itemView.findViewById<Button>(R.id.btnUploadPhoto)
            uploadBtn.setOnClickListener { onUploadClick() }
        }

        fun bindRewards(list: List<Rewards>) {

            val recycler = itemView.findViewById<RecyclerView>(R.id.rewardsRecycler)

            recycler.layoutManager = LinearLayoutManager(itemView.context)
            recycler.isNestedScrollingEnabled = false

            recycler.adapter = RewardAdapter(list) { reward ->

                RewardsManager.redeemReward(
                    reward.id,
                    onSuccess = {
                        Toast.makeText(itemView.context, "Redeemed!", Toast.LENGTH_SHORT).show()
                    },
                    onError = {
                        Toast.makeText(itemView.context, it, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        fun bindTop3(list: List<PhotoPost>) {

            val top1 = itemView.findViewById<ImageView>(R.id.top1)
            val top2 = itemView.findViewById<ImageView>(R.id.top2)
            val top3 = itemView.findViewById<ImageView>(R.id.top3)

            val votes1 = itemView.findViewById<TextView>(R.id.votes1)
            val votes2 = itemView.findViewById<TextView>(R.id.votes2)
            val votes3 = itemView.findViewById<TextView>(R.id.votes3)

            val sorted = list.sortedByDescending { it.votes }

            // Reset
            top1.setImageDrawable(null)
            top2.setImageDrawable(null)
            top3.setImageDrawable(null)

            votes1.text = "0"
            votes2.text = "0"
            votes3.text = "0"

            if (sorted.isNotEmpty()) {
                Glide.with(itemView).load(sorted[0].imageUrl).into(top1)
                votes1.text = "${sorted[0].votes}"
            }

            if (sorted.size > 1) {
                Glide.with(itemView).load(sorted[1].imageUrl).into(top2)
                votes2.text = "${sorted[1].votes}"
            }

            if (sorted.size > 2) {
                Glide.with(itemView).load(sorted[2].imageUrl).into(top3)
                votes3.text = "${sorted[2].votes}"
            }
        }
    }

    // =========================
    // 🔥 PHOTO ITEM VIEW HOLDER
    // =========================

    class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        val image: ImageView = view.findViewById(R.id.photoImage)
        val caption: TextView = view.findViewById(R.id.photoCaption)
        val votes: TextView = view.findViewById(R.id.photoVotes)
        val voteBtn: Button = view.findViewById(R.id.btnVote)
    }
}