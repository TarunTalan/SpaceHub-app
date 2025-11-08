package com.example.myapplication.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.data.chat.model.ChatMessage

class ChatMessageAdapter : ListAdapter<ChatMessage, ChatMessageAdapter.VH>(Diff) {
    object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSender: TextView = view.findViewById(android.R.id.text1)
        private val tvMsg: TextView = view.findViewById(android.R.id.text2)
        fun bind(m: ChatMessage) {
            // Use senderName if available, otherwise fall back to senderId
            tvSender.text = m.senderName ?: m.senderId
            // ChatMessage stores the message text in `content`
            tvMsg.text = m.content
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
