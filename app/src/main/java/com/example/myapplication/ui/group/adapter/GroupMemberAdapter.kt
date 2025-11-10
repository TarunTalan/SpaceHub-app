package com.example.myapplication.ui.group.adapter

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
import com.example.myapplication.data.groups.model.DataXXX

class GroupMemberAdapter(
    private val currentUserEmail: String? = null,
    private val onClick: (DataXXX) -> Unit = {}
) : ListAdapter<DataXXX, GroupMemberAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<DataXXX>() {
        override fun areItemsTheSame(oldItem: DataXXX, newItem: DataXXX) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DataXXX, newItem: DataXXX) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivAvatar: ImageView = view.findViewById(R.id.ivMemberAvatar)
        private val tvName: TextView = view.findViewById(R.id.tvMemberName)
        private val tvEmail: TextView = view.findViewById(R.id.tvMemberEmail)

        fun bind(item: DataXXX) {
            val isSelf = !currentUserEmail.isNullOrBlank() && item.email?.equals(currentUserEmail, ignoreCase = true) == true
            val name = if (isSelf) {
                itemView.context.getString(R.string.you)
            } else {
                item.username?.takeIf { it.isNotBlank() } ?: item.email
            }
            tvName.text = name
            tvEmail.text = item.email

            val avatar = item.avatarPreviewUrl
            if (!avatar.isNullOrBlank()) {
                Glide.with(ivAvatar.context)
                    .load(avatar)
                    .placeholder(R.drawable.default_comm_icon)
                    .error(R.drawable.default_comm_icon)
                    .circleCrop()
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.default_comm_icon)
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group_member, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
