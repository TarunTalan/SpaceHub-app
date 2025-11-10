package com.example.myapplication.ui.community.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.community.model.Community

class YourCommunityAdapter(
    private val showRoleBadge: Boolean = true,
    private val onClick: (Community) -> Unit
) : ListAdapter<Community, YourCommunityAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Community>() {
        override fun areItemsTheSame(oldItem: Community, newItem: Community): Boolean =
            oldItem.communityId == newItem.communityId
        override fun areContentsTheSame(oldItem: Community, newItem: Community): Boolean =
            oldItem == newItem
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvDesc: TextView = itemView.findViewById(R.id.tvDesc)
        private val tvRoleBadge: TextView = itemView.findViewById(R.id.tvRoleBadge)
        private val btnJoin: TextView? = itemView.findViewById(R.id.btnJoinRequest)
        private val btnJoinProgress: View? = itemView.findViewById(R.id.btnJoinProgress)

        fun bind(item: Community) {
            tvName.text = item.name
            tvDesc.text = item.description ?: ""

            // Show role badge for OWNER/ADMIN/MODERATOR only when enabled
            if (showRoleBadge) {
                val role = item.role?.uppercase()
                when {
                    role in listOf("OWNER", "CREATOR") -> {
                        tvRoleBadge.visibility = View.VISIBLE
                        tvRoleBadge.text = "OWNER"
                        tvRoleBadge.setBackgroundResource(R.drawable.rounded_button_bg_blue)
                    }
                    role in listOf("ADMIN", "MODERATOR", "MANAGER") -> {
                        tvRoleBadge.visibility = View.VISIBLE
                        tvRoleBadge.text = "ADMIN"
                        tvRoleBadge.setBackgroundResource(R.drawable.rounded_button_bg_blue)
                    }
                    else -> {
                        tvRoleBadge.visibility = View.GONE
                    }
                }
            } else {
                tvRoleBadge.visibility = View.GONE
            }

            val url = item.profilePicUrl ?: item.coverPhotoUrl
            if (!url.isNullOrBlank()) {
                Glide.with(itemView)
                    .load(url)
                    .placeholder(R.drawable.default_comm_icon)
                    .error(R.drawable.default_comm_icon)
                    .circleCrop()
                    .into(ivCover)
            } else {
                ivCover.setImageResource(R.drawable.default_comm_icon)
            }

            // Dashboard lists only communities you belong to; hide join controls entirely
            btnJoin?.visibility = View.GONE
            btnJoinProgress?.visibility = View.GONE

            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_community_card, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
