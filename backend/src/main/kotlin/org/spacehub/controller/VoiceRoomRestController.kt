package org.spacehub.controller

import org.slf4j.LoggerFactory
import org.spacehub.service.JanusService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*
import kotlin.random.Random

@RestController
@RequestMapping("/api/v1/voice-rooms")
class VoiceRoomRestController(private val janusService: JanusService) {
    private val logger = LoggerFactory.getLogger(VoiceRoomRestController::class.java)

    data class CreateVoiceRoomRequest(
        val name: String? = null,
        val createdByEmail: String? = null,
        val communityId: String? = null
    )

    data class CreateVoiceRoomResponse(
        val voiceRoomId: String,
        val voiceRoomCode: String,
        val sessionId: String,
        val handleId: String,
        val roomId: Int
    )

    @PostMapping
    fun createVoiceRoom(@RequestBody req: CreateVoiceRoomRequest): ResponseEntity<CreateVoiceRoomResponse> {
        logger.info("Received createVoiceRoom request: name={}, createdBy={}, community={}", req.name, req.createdByEmail, req.communityId)
        // Create Janus session and attach audiobridge plugin
        val sessionId = janusService.createSession()
        val handleId = janusService.attachAudioBridgePlugin(sessionId)

        // Generate a numeric room id. Janus audio bridge expects numeric room IDs.
        val roomId = generateRoomId()

        // Create the audio room on Janus
        janusService.createAudioRoom(sessionId, handleId, roomId)

        // Generate a UUID-based voiceRoomId and a short code similar to chatRoomCode
        val voiceRoomId = UUID.randomUUID().toString()
        val voiceRoomCode = generateVoiceRoomCode(roomId)

        val resp = CreateVoiceRoomResponse(
            voiceRoomId = voiceRoomId,
            voiceRoomCode = voiceRoomCode,
            sessionId = sessionId,
            handleId = handleId,
            roomId = roomId
        )
        return ResponseEntity.ok(resp)
    }

    private fun generateRoomId(): Int {
        // generate a reasonably large random positive int (6-9 digits)
        return Random.nextInt(100_000, 9_999_999)
    }

    private fun generateVoiceRoomCode(roomId: Int): String {
        // derivation: base36 of roomId plus random 3 chars for short code
        val base = roomId.toString(36).uppercase(Locale.getDefault())
        val suffix = UUID.randomUUID().toString().substring(0, 3).uppercase(Locale.getDefault())
        return "$base-$suffix"
    }
}
