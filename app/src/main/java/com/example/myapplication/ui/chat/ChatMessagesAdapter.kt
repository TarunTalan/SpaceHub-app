package com.example.myapplication.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.BuildConfig
import com.example.myapplication.R
import com.example.myapplication.data.chat.model.ChatMessage
import com.example.myapplication.data.chat.model.MessageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessagesAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        private fun formatTime(timestamp: Long): String {
            return timeFormat.format(Date(timestamp))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isFromMe) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_sent, parent, false)
                SentMessageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_received, parent, false)
                ReceivedMessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SentMessageViewHolder -> holder.bind(getItem(position))
            is ReceivedMessageViewHolder -> holder.bind(getItem(position))
        }
    }

    // Sent message (from current user)
    class SentMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView = view.findViewById(R.id.tv_message)
        private val tvTime: TextView = view.findViewById(R.id.tv_time)
        private val ivStatus: ImageView = view.findViewById(R.id.iv_status)

        fun bind(message: ChatMessage) {
            tvMessage.text = message.content
            tvTime.text = formatTime(message.timestamp)

            // Show message status (sent, delivered, read)
            when (message.status) {
                MessageStatus.SENDING -> ivStatus.setImageResource(R.drawable.ic_clock)
                MessageStatus.SENT -> ivStatus.setImageResource(R.drawable.ic_check)
                MessageStatus.DELIVERED -> ivStatus.setImageResource(R.drawable.ic_double_check)
                MessageStatus.READ -> ivStatus.setImageResource(R.drawable.ic_double_check_blue)
                MessageStatus.FAILED -> ivStatus.setImageResource(R.drawable.ic_error)
            }
        }
    }

    // Received message (from peer)
    class ReceivedMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivAvatar: ImageView = view.findViewById(R.id.iv_avatar)
        private val tvMessage: TextView = view.findViewById(R.id.tv_message)
        private val tvTime: TextView = view.findViewById(R.id.tv_time)

        fun bind(message: ChatMessage) {
            tvMessage.text = message.content
            tvTime.text = formatTime(message.timestamp)

            // Load sender avatar
            val avatarUrl = message.senderAvatar?.trim()?.let { raw ->
                when {
                    raw.isBlank() -> null
                    raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true) -> raw
                    else -> "${BuildConfig.BASE_URL.trimEnd('/')}/${raw.trimStart('/')}"
                }
            }

            Glide.with(ivAvatar.context)
                .load(avatarUrl ?: R.drawable.default_profile)
                .placeholder(R.drawable.default_profile)
                .error(R.drawable.default_profile)
                .circleCrop()
                .into(ivAvatar)
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
    }
}

