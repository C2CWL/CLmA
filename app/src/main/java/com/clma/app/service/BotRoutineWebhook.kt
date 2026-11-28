// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/webhook-ingress.ts.
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.service

import android.content.Context
import com.clma.app.data.botmode.BotRoutineEventKind
import com.clma.app.data.botmode.BotRoutineTrigger
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull

internal class BotRoutineWebhook(private val context: Context) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            server?.stop(0, 1000)
            server = null
        } else if (server == null) {
            val instance = embeddedServer(CIO, port = BotRoutineEvents.PORT, host = "127.0.0.1") {
                routing {
                    post("/hooks/{id}/{secret}") {
                        val id = call.parameters["id"].orEmpty()
                        val secret = call.parameters["secret"].orEmpty()
                        val job = BotRoutineEvents.webhook(id, secret)
                        val trigger = job?.let(BotRoutineTrigger::from)
                        if (job == null || trigger == null) {
                            call.respond(HttpStatusCode.NotFound)
                            return@post
                        }
                        val channel = call.receiveChannel()
                        val buffer = ByteArray(MAX_BODY_BYTES + 1)
                        val size = withTimeoutOrNull(5_000L) {
                            var count = 0
                            while (count < buffer.size) {
                                val read = channel.readAvailable(buffer, count, buffer.size - count)
                                if (read == -1) break
                                count += read
                            }
                            count
                        }
                        when {
                            size == null -> call.respond(HttpStatusCode.RequestTimeout)
                            size > MAX_BODY_BYTES -> call.respond(HttpStatusCode.PayloadTooLarge)
                            else -> {
                                val event = BotRoutineEvents.eventContext(
                                    BotRoutineEventKind.WEBHOOK, body = buffer.decodeToString(endIndex = size),
                                )
                                BotRoutineEvents.enqueue(context, job, trigger, event)
                                call.respond(HttpStatusCode.Accepted)
                            }
                        }
                    }
                }
            }
            try {
                instance.start(wait = false)
                server = instance
            } catch (e: Exception) {
                instance.stop(0, 1000)
                throw e
            }
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 1000

        fun url(id: String, trigger: BotRoutineTrigger): String =
            "http://127.0.0.1:${BotRoutineEvents.PORT}/hooks/$id/${trigger.webhookSecret}"
    }
}
