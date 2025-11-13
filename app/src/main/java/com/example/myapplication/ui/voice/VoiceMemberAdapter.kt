package com.example.myapplication.ui.voice

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

data class VoiceMember(
    val userId: String,
    val name: String,
    val imageUrl: String?
)

class VoiceMemberAdapter(
    private val onClick: (VoiceMember) -> Unit = {}
) : ListAdapter<VoiceMember, VoiceMemberAdapter.VH>(Diff) {

    private var speakingUserId: String? = null

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAvatar: ImageView = itemView.findViewById(R.id.iv_member_avatar)
        val tvName: TextView = itemView.findViewById(R.id.tv_member_name)
        val speakingIndicator: View = itemView.findViewById(R.id.speaking_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_voice_member, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.name
        if (!item.imageUrl.isNullOrBlank()) {
            Glide.with(holder.itemView.context)
                .load(item.imageUrl)
                .placeholder(R.drawable.default_profile)
                .error(R.drawable.default_profile)
                .into(holder.ivAvatar)
        } else {
            holder.ivAvatar.setImageResource(R.drawable.default_profile)
        }

        val isSpeaking = item.userId == speakingUserId
        holder.speakingIndicator.visibility = if (isSpeaking) View.VISIBLE else View.GONE
        holder.itemView.isActivated = isSpeaking

        // pulse animation for speaking state: start when speaking, clear when not
        val context = holder.itemView.context
        if (isSpeaking) {
            // simple alpha/scale animation for speaking indicator
            holder.speakingIndicator.animate().alpha(1f).scaleX(1.0f).scaleY(1.0f).setDuration(300).start()
        } else {
            holder.speakingIndicator.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    fun setSpeaking(userId: String?) {
        speakingUserId = userId
        notifyDataSetChanged()
    }

    companion object Diff : DiffUtil.ItemCallback<VoiceMember>() {
        override fun areItemsTheSame(oldItem: VoiceMember, newItem: VoiceMember): Boolean = oldItem.userId == newItem.userId
        override fun areContentsTheSame(oldItem: VoiceMember, newItem: VoiceMember): Boolean = oldItem == newItem
    }
}
