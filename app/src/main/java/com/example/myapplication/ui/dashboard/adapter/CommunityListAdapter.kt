package com.example.myapplication.ui.dashboard.adapter

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

data class CommunityUi(
    val id: Int,
    val name: String,
    val imageUrl: String?,
    val subtitle: String? = null,
    val isLocal: Boolean = false
)

class CommunityListAdapter(
    private val onItemClick: (CommunityUi) -> Unit
) : ListAdapter<CommunityUi, CommunityListAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<CommunityUi>() {
        override fun areItemsTheSame(oldItem: CommunityUi, newItem: CommunityUi): Boolean = oldItem.id == newItem.id && oldItem.isLocal == newItem.isLocal
        override fun areContentsTheSame(oldItem: CommunityUi, newItem: CommunityUi): Boolean = oldItem == newItem
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val img: ImageView = itemView.findViewById(R.id.imgAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        private val btn: TextView = itemView.findViewById(R.id.btnJoin)
        fun bind(item: CommunityUi) {
            tvName.text = item.name
            tvSubtitle.text = item.subtitle ?: ""
            Glide.with(itemView).load(item.imageUrl).placeholder(R.drawable.default_profile).into(img)
            itemView.setOnClickListener { onItemClick(item) }
            btn.setOnClickListener { onItemClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_community_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
