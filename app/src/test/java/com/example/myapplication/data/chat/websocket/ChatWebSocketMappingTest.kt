package com.example.myapplication.data.chat.websocket

import com.example.myapplication.data.chat.model.WSChatMessage
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatWebSocketMappingTest {
    @Test
    fun tempIdUsedWhenIdNull() {
        val json = """{"id":null,"type":"MESSAGE","senderEmail":"taruntalan314@gmail.com","message":null,"timestamp":1762949926083,"fileName":null,"fileUrl":null,"contentType":null,"senderUsername":"jskdid","tempId":"335fa454-94c4-4a88-abeb-46e84832142c","optimistic":true}"""
        val ws = Gson().fromJson(json, WSChatMessage::class.java)
        val id = ws.id ?: ws.messageId ?: ws.tempId ?: "fallback"
        assertEquals("335fa454-94c4-4a88-abeb-46e84832142c", id)
        val content = ws.content ?: ws.message ?: ""
        assertEquals("", content)
    }
}

