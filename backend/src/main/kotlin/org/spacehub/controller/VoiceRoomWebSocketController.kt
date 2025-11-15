package org.spacehub.controller

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.spacehub.service.JanusService
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import java.util.concurrent.ConcurrentHashMap

@Controller
class VoiceRoomWebSocketController(
    private val janusService: JanusService,
    private val messagingTemplate: SimpMessagingTemplate
) {
    private val logger = LoggerFactory.getLogger(VoiceRoomWebSocketController::class.java)

    private val userSessionMap = ConcurrentHashMap<String, String>()
    private val userHandleMap = ConcurrentHashMap<String, String>()
    private val userRoomMap = ConcurrentHashMap<String, String>()

    @MessageMapping("/register")
    fun registerUser(payload: Map<String, String>) {
        val userId = payload["userId"]
        val sessionId = payload["sessionId"]
        val handleId = payload["handleId"]
        val roomId = payload["roomId"]

        // Require at minimum userId and roomId. sessionId/handleId are optional (may be provided after REST join).
        if (userId == null || roomId == null) {
            logger.warn("Invalid registration payload (missing userId or roomId): {}", payload)
            return
        }

        // Store basic mapping and room association
        userRoomMap[userId] = roomId

        // Only store session/handle mapping and start Janus polling if sessionId and handleId are provided and non-blank
        if (!sessionId.isNullOrBlank() && !handleId.isNullOrBlank()) {
            userSessionMap[userId] = sessionId
            userHandleMap[userId] = handleId
            logger.info("User {} registered for room {} with session/handle (starting event polling)", userId, roomId)

            try {
                janusService.startEventPolling(sessionId) { janusEvent ->
                    try {
                        val senderHandle = janusEvent.path("sender").asLong(0L)
                        if (senderHandle == 0L || senderHandle.toString() == handleId) {
                            messagingTemplate.convertAndSend("/topic/room/$roomId/answer/$userId", janusEvent)
                        }
                    } catch (e: Exception) {
                        logger.error("Error forwarding Janus event: {}", e.message)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to start Janus event polling for session=$sessionId: {}", e.message)
            }
        } else {
            logger.info("User {} registered for room {} without session/handle (will not start Janus polling yet)", userId, roomId)
        }

        val event = mapOf("type" to "joined", "userId" to userId)
        messagingTemplate.convertAndSend("/topic/room/$roomId/events", event)
    }

    @MessageMapping("/unregister")
    fun unregisterUser(payload: Map<String, String>) {
        val userId = payload["userId"] ?: return
        logger.info("User {} unregistering.", userId)
        val sessionId = userSessionMap.remove(userId)
        userHandleMap.remove(userId)
        val roomId = userRoomMap.remove(userId)

        if (sessionId != null) janusService.stopEventPolling(sessionId)
        if (roomId != null) {
            val event = mapOf("type" to "left", "userId" to userId)
            messagingTemplate.convertAndSend("/topic/room/$roomId/events", event)
        }
    }

    @MessageMapping("/offer")
    fun handleOffer(payload: Map<String, String>) {
        val userId = payload["userId"]
        val sdp = payload["sdp"]
        val roomId = payload["roomId"]
        if (userId == null || sdp == null || roomId == null) {
            logger.warn("Invalid offer payload: {}", payload)
            return
        }
        val sessionId = userSessionMap[userId]
        val handleId = userHandleMap[userId]
        if (sessionId == null || handleId == null) {
            logger.warn("Offer from unregistered user {}", userId)
            return
        }
        janusService.sendOffer(sessionId, handleId, sdp, userId, roomId, messagingTemplate)
    }

    @MessageMapping("/ice")
    fun handleIceCandidate(payload: Map<String, Any>) {
        val userId = payload["userId"] as? String ?: return
        val candidateObj = payload["candidate"] ?: return
        val sessionId = userSessionMap[userId]
        val handleId = userHandleMap[userId]
        if (sessionId == null || handleId == null) {
            logger.warn("ICE from unregistered user {}", userId)
            return
        }
        janusService.sendIce(sessionId, handleId, candidateObj)
    }

    @MessageMapping("/mute")
    fun handleMute(payload: Map<String, String>) {
        val userId = payload["userId"] ?: return
        val roomId = payload["roomId"] ?: return
        val action = payload["action"] ?: return
        val sessionId = userSessionMap[userId] ?: return
        val handleId = userHandleMap[userId] ?: return
        val mute = action.equals("mute", ignoreCase = true)
        janusService.setMute(sessionId, handleId, mute)
        val event = mapOf("type" to if (mute) "muted" else "unmuted", "userId" to userId)
        messagingTemplate.convertAndSend("/topic/room/$roomId/events", event)
    }
}
