package com.example.myapplication.ui.community.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.community.model.Member

class MemberAdapter(
    private val isAdmin: Boolean,
    private val currentUserEmail: String?,
    private val onChangeRole: (Member, String) -> Unit,
    private val onRemove: (Member) -> Unit,
    private val onLongClick: ((Member) -> Unit)? = null
) : ListAdapter<Member, MemberAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<Member>() {
        override fun areItemsTheSame(oldItem: Member, newItem: Member) = oldItem.memberId == newItem.memberId
        override fun areContentsTheSame(oldItem: Member, newItem: Member) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        private val tvName: TextView = view.findViewById(R.id.tvMemberName)
        private val tvRole: TextView = view.findViewById(R.id.tvMemberRole)
        private val btnPromote: ImageButton = view.findViewById(R.id.btnPromote)
        private val btnRemove: Button = view.findViewById(R.id.btnRemove)

        fun bind(item: Member) {
            val isSelf = !currentUserEmail.isNullOrBlank() && item.email.equals(currentUserEmail, ignoreCase = true)
            val displayName = if (isSelf) itemView.context.getString(R.string.you) else item.username?.takeIf { it.isNotBlank() } ?: item.email ?: "Unknown"
            tvName.text = displayName
            tvRole.text = item.role

            // Load avatar (prefer previewUrl; fallback to key path if needed)
            val url = item.avatarPreviewUrl ?: item.avatarKey
            if (!url.isNullOrBlank()) {
                Glide.with(ivAvatar.context)
                    .load(url)
                    .placeholder(R.drawable.default_comm_icon)
                    .error(R.drawable.default_comm_icon)
                    .circleCrop()
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.default_comm_icon)
            }

            val roleUpper = item.role?.trim()?.uppercase()
            val isOwner = roleUpper?.contains("OWNER") == true

            // Only admins can see action buttons; never allow remove on OWNER or on self
            btnPromote.visibility = if (isAdmin && !isSelf) View.VISIBLE else View.GONE
            btnRemove.visibility = if (isAdmin && !isOwner && !isSelf) View.VISIBLE else View.GONE

            // Disable long-press for own card
            itemView.isLongClickable = (onLongClick != null && !isSelf)

            btnPromote.setOnClickListener {
                val next = if (roleUpper == "ADMIN") "MEMBER" else "ADMIN"
                onChangeRole(item, next)
            }
            btnRemove.setOnClickListener { onRemove(item) }

            // Optional long-press handler
            itemView.setOnLongClickListener {
                onLongClick?.invoke(item)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_member, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
