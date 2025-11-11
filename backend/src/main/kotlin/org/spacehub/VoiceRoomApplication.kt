package org.spacehub

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class VoiceRoomApplication

fun main(args: Array<String>) {
    runApplication<VoiceRoomApplication>(*args)
}

