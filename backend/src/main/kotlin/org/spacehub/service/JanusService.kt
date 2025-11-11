package org.spacehub.service

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.*
import java.util.concurrent.*
import java.util.function.Consumer

@Service
class JanusService {
    private val logger = LoggerFactory.getLogger(JanusService::class.java)

    @Value("\${janus.server.url:http://localhost:8088/janus}")
    lateinit var janusUrl: String

    private val restTemplate = RestTemplate()
    private val pollExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val pollingTasks: ConcurrentMap<String, Future<*>> = ConcurrentHashMap()

    fun startEventPolling(sessionId: String, onEvent: Consumer<JsonNode>) {
        if (sessionId.isBlank()) return
        if (pollingTasks.containsKey(sessionId)) {
            logger.debug("Polling for session {} already active.", sessionId)
            return
        }

        val sessionUrl = "${'$'}{janusUrl.removeSuffix("/")}/${'$'}sessionId"

        val future = pollExecutor.submit {
            logger.info("Started Janus poll for session={}", sessionId)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val resp: ResponseEntity<JsonNode> = restTemplate.getForEntity(sessionUrl, JsonNode::class.java)
                        val body = resp.body
                        if (body != null && !body.isNull) {
                            if (body.isArray) {
                                body.forEach { event -> onEvent.accept(event) }
                            } else {
                                onEvent.accept(body)
                            }
                        }
                        Thread.sleep(200)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        if (Thread.currentThread().isInterrupted) break
                        logger.warn("Error polling Janus for session {}: {}", sessionId, e.message)
                        try { Thread.sleep(500) } catch (ex: InterruptedException) { Thread.currentThread().interrupt(); break }
                    }
                }
            } finally {
                logger.info("Stopping Janus poll for session={}", sessionId)
            }
        }

        pollingTasks[sessionId] = future
    }

    fun stopEventPolling(sessionId: String) {
        val f = pollingTasks.remove(sessionId)
        f?.cancel(true)
    }

    fun createSession(): String {
        val request = mapOf("janus" to "create", "transaction" to UUID.randomUUID().toString())
        val response = restTemplate.postForEntity(janusUrl, request, JsonNode::class.java)
        if (response.body != null && response.body!!.has("data")) {
            return response.body!!.get("data").get("id").asText()
        }
        throw RuntimeException("Failed to create Janus session")
    }

    fun attachAudioBridgePlugin(sessionId: String): String {
        val request = mapOf("janus" to "attach", "plugin" to "janus.plugin.audiobridge", "transaction" to UUID.randomUUID().toString())
        val sessionUrl = janusUrl.removeSuffix("/") + "/" + sessionId
        val response = restTemplate.postForEntity(sessionUrl, request, JsonNode::class.java)
        if (response.body != null && response.body!!.has("data")) {
            return response.body!!.get("data").get("id").asText()
        }
        throw RuntimeException("Failed to attach AudioBridge plugin")
    }

    fun createAudioRoom(sessionId: String, handleId: String, roomId: Int) {
        val body = mapOf("request" to "create", "room" to roomId, "description" to "SpaceHub Voice Room", "is_private" to false)
        val request = mapOf("janus" to "message", "transaction" to UUID.randomUUID().toString(), "body" to body)
        val handleUrl = "${'$'}{janusUrl.removeSuffix("/")}/${'$'}sessionId/${'$'}handleId"
        restTemplate.postForEntity(handleUrl, request, JsonNode::class.java)
    }

    fun joinAudioRoom(sessionId: String, handleId: String, roomId: Int, displayName: String) {
        val body = mapOf("request" to "join", "room" to roomId, "display" to displayName)
        val request = mapOf("janus" to "message", "transaction" to UUID.randomUUID().toString(), "body" to body)
        val handleUrl = "${'$'}{janusUrl.removeSuffix("/")}/${'$'}sessionId/${'$'}handleId"
        restTemplate.postForEntity(handleUrl, request, JsonNode::class.java)
    }

    fun sendOffer(sessionId: String, handleId: String, sdpOffer: String, userId: String, roomId: String, messagingTemplate: SimpMessagingTemplate) {
        val body = mapOf("request" to "configure", "muted" to false, "audio" to true)
        val jsep = mapOf("type" to "offer", "sdp" to sdpOffer)
        val request = mapOf("janus" to "message", "transaction" to UUID.randomUUID().toString(), "body" to body, "jsep" to jsep)
        val handleUrl = "${'$'}{janusUrl.removeSuffix("/")}/${'$'}sessionId/${'$'}handleId"
        val response = restTemplate.postForEntity(handleUrl, request, JsonNode::class.java)
        if (response.body != null) {
            val destination = "/topic/room/${'$'}roomId/answer/${'$'}userId"
            logger.info("Forwarding SDP Answer to {}", destination)
            messagingTemplate.convertAndSend(destination, response.body)
        } else {
            logger.warn("Janus returned no body for sendOffer for user {}", userId)
        }
    }

    fun sendIce(sessionId: String, handleId: String, candidate: Any) {
        val request = mapOf("janus" to "trickle", "transaction" to UUID.randomUUID().toString(), "candidate" to candidate)
        val handleUrl = "${'$'}{janusUrl.removeSuffix("/")}/${'$'}sessionId/${'$'}handleId"
        restTemplate.postForEntity(handleUrl, request, JsonNode::class.java)
    }

    fun setMute(sessionId: String, handleId: String, mute: Boolean) {
        val body = mapOf("request" to "configure", "audio" to !mute, "muted" to mute)
        val request = mapOf("janus" to "message", "transaction" to UUID.randomUUID().toString(), "body" to body)
        val handleUrl = "${'$'}{janusUrl.removeSuffix("/")}/${'$'}sessionId/${'$'}handleId"
        restTemplate.postForEntity(handleUrl, request, JsonNode::class.java)
    }
}

