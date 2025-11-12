package com.example.myapplication.ui.chat

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
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

class ChatMessagesAdapter(private val hidePeerInfoInDirectChat: Boolean = false) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        private fun formatTime(timestamp: Long): String {
            return timeFormat.format(Date(timestamp))
        }
    }

    // Selection state stored as set of message ids
    private val selectedIds = mutableSetOf<String>()

    // Callbacks
    var onItemClick: ((ChatMessage) -> Unit)? = null
    var onItemLongClick: ((ChatMessage) -> Unit)? = null

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
        val message = getItem(position)
        when (holder) {
            is SentMessageViewHolder -> holder.bind(message)
            is ReceivedMessageViewHolder -> holder.bind(message)
        }

        // Visual selection indicator (dim the item when selected)
        holder.itemView.alpha = if (selectedIds.contains(message.id)) 0.6f else 1.0f

        // Highlight background when selected (more visible than just alpha)
        val bgColorRes = if (selectedIds.contains(message.id)) R.color.selection_bg else android.R.color.transparent
        holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, bgColorRes))

        // Click / long-click wiring
        holder.itemView.setOnClickListener {
            if (selectedIds.isNotEmpty()) {
                toggleSelection(message.id)
                onItemLongClick?.invoke(message)
            } else {
                onItemClick?.invoke(message)
            }
        }

        holder.itemView.setOnLongClickListener {
            toggleSelection(message.id)
            onItemLongClick?.invoke(message)
            true
        }
    }

    fun toggleSelection(messageId: String) {
        if (selectedIds.contains(messageId)) selectedIds.remove(messageId) else selectedIds.add(messageId)
        // notify list changed minimally by finding index
        val index = currentList.indexOfFirst { it.id == messageId }
        if (index >= 0) notifyItemChanged(index)
    }

    fun clearSelection() {
        val copy = selectedIds.toSet()
        selectedIds.clear()
        // notify changed for previously selected items
        for (id in copy) {
            val i = currentList.indexOfFirst { it.id == id }
            if (i >= 0) notifyItemChanged(i)
        }
    }

    fun getSelectedIds(): Set<String> = selectedIds.toSet()

    // Sent message (from current user)
    class SentMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView = view.findViewById(R.id.tv_message)
        private val tvTime: TextView = view.findViewById(R.id.tv_time)
        private val ivStatus: ImageView = view.findViewById(R.id.iv_status)

        fun bind(message: ChatMessage) {
            try { android.util.Log.d("ChatMessagesAdapter", "BIND SENT: id=${message.id} fromMe=${message.isFromMe} content=${message.content}") } catch (_: Exception) {}
            // If deleted, server/repo updates message.content to deletion text. Prefer that; otherwise show default.
            val isDeleted = message.senderDeleted || message.receiverDeleted
            val displayText = if (isDeleted) message.content.takeIf { it.isNotBlank() } ?: "message deleted" else message.content
            tvMessage.text = displayText
            tvMessage.setTypeface(null, if (isDeleted) Typeface.ITALIC else Typeface.NORMAL)
            tvTime.text = formatTime(message.timestamp)

            // If message is deleted (either side), show muted look and hide status
            if (isDeleted) {
                tvMessage.setTextColor(ContextCompat.getColor(tvMessage.context, R.color.gray_medium))
                ivStatus.visibility = View.GONE
            } else {
                ivStatus.visibility = View.VISIBLE
                // Show message status (sent, delivered, read)
                when (message.status) {
                    MessageStatus.SENDING -> ivStatus.setImageResource(R.drawable.ic_clock)
                    MessageStatus.SENT -> ivStatus.setImageResource(R.drawable.ic_check)
                    MessageStatus.DELIVERED -> ivStatus.setImageResource(R.drawable.ic_double_check)
                    MessageStatus.READ -> ivStatus.setImageResource(R.drawable.ic_double_check_blue)
                    MessageStatus.FAILED -> ivStatus.setImageResource(R.drawable.ic_error)
                }
                // Ensure default text color for non-deleted
                tvMessage.setTextColor(ContextCompat.getColor(tvMessage.context, android.R.color.white))
            }
        }
    }

    // Received message (from peer)
    inner class ReceivedMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivAvatar: ImageView = view.findViewById(R.id.iv_avatar)
        private val tvMessage: TextView = view.findViewById(R.id.tv_message)
        private val tvSenderName: TextView = view.findViewById(R.id.tv_sender_name)
        private val tvTime: TextView = view.findViewById(R.id.tv_time)

        fun bind(message: ChatMessage) {
            try { android.util.Log.d("ChatMessagesAdapter", "BIND RECV: id=${message.id} fromMe=${message.isFromMe} content=${message.content}") } catch (_: Exception) {}

            // Hide peer info for direct chat if requested
            if (hidePeerInfoInDirectChat) {
                ivAvatar.visibility = View.GONE
                tvSenderName.visibility = View.GONE
            } else {
                ivAvatar.visibility = View.VISIBLE
                tvSenderName.visibility = View.VISIBLE
                // Show sender name (username) when available, otherwise fall back to senderId/email
                tvSenderName.text = message.senderName?.takeIf { it.isNotBlank() } ?: message.senderId

                // Load sender avatar
                val avatarUrl = message.senderAvatar?.trim()?.let { raw ->
                    when {
                        raw.isBlank() -> null
                        raw.startsWith("http://", ignoreCase = true) ||
                        raw.startsWith("https://", ignoreCase = true) -> raw
                        else -> "${BuildConfig.BASE_URL.trimEnd('/')}/${raw.trimStart('/') }"
                    }
                }

                Glide.with(ivAvatar.context)
                    .load(avatarUrl ?: R.drawable.default_profile)
                    .placeholder(R.drawable.default_profile)
                    .error(R.drawable.default_profile)
                    .circleCrop()
                    .into(ivAvatar)
            }

            // If message is deleted (either side), show muted look and hide avatar/name per direct-chat flag
            val isDeleted = message.senderDeleted || message.receiverDeleted
            val displayText = if (isDeleted) message.content.takeIf { it.isNotBlank() } ?: "message deleted" else message.content
            tvMessage.text = displayText
            tvMessage.setTypeface(null, if (isDeleted) Typeface.ITALIC else Typeface.NORMAL)
            if (isDeleted) {
                tvMessage.setTextColor(ContextCompat.getColor(tvMessage.context, R.color.gray_medium))
                tvTime.setTextColor(ContextCompat.getColor(tvTime.context, R.color.gray_medium))
                if (hidePeerInfoInDirectChat) {
                    ivAvatar.visibility = View.GONE
                    tvSenderName.visibility = View.GONE
                }
            } else {
                tvMessage.setTextColor(ContextCompat.getColor(tvMessage.context, android.R.color.white))
                tvTime.setTextColor(ContextCompat.getColor(tvTime.context, R.color.white))
            }

            tvTime.text = formatTime(message.timestamp)
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
    }
}
