package com.outofthewhale.groupme

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val TAG = "FayeClient"
private const val FAYE_URL = "wss://push.groupme.com/faye"

/**
 * Minimal Faye/Bayeux client over an OkHttp WebSocket, speaking the GroupMe push protocol:
 * handshake, subscribe to /user/{userId} with the access token as ext auth, then a connect
 * loop. Incoming "line.create" events are surfaced through [onMessage].
 */
internal class FayeClient(
    private val token: String,
    private val userId: String,
    private val scope: CoroutineScope,
    private val onMessage: (GroupMeMessage) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var clientId: String? = null
    private var messageId = 0
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    @Volatile
    private var stopped = false

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "bye")
        webSocket = null
        clientId = null
    }

    private fun connect() {
        val request = Request.Builder().url(FAYE_URL).build()
        webSocket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            sendHandshake()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            scheduleReconnect()
        }
    }

    private fun sendHandshake() {
        send(
            buildJsonObject {
                put("channel", "/meta/handshake")
                put("version", "1.0")
                putJsonArray("supportedConnectionTypes") { add("websocket") }
                put("id", nextId())
            }
        )
    }

    private fun sendSubscribe(clientId: String) {
        send(
            buildJsonObject {
                put("channel", "/meta/subscribe")
                put("clientId", clientId)
                put("subscription", "/user/$userId")
                put("id", nextId())
                putJsonObject("ext") {
                    put("access_token", token)
                    put("timestamp", System.currentTimeMillis() / 1000)
                }
            }
        )
    }

    private fun sendConnect(clientId: String) {
        send(
            buildJsonObject {
                put("channel", "/meta/connect")
                put("clientId", clientId)
                put("connectionType", "websocket")
                put("id", nextId())
            }
        )
    }

    private fun handleFrame(text: String) {
        val frames = try {
            val element = json.parseToJsonElement(text)
            if (element is JsonArray) element.map { it.jsonObject } else listOf(element.jsonObject)
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable Faye frame: ${e.message}")
            return
        }

        frames.forEach { frame -> handleMessage(frame) }
    }

    private fun handleMessage(frame: JsonObject) {
        val channel = frame["channel"]?.jsonPrimitive?.content ?: return
        val successful = frame["successful"]?.jsonPrimitive?.content == "true"

        when (channel) {
            "/meta/handshake" -> {
                val id = frame["clientId"]?.jsonPrimitive?.content
                if (successful && id != null) {
                    clientId = id
                    sendSubscribe(id)
                } else {
                    Log.w(TAG, "Handshake failed: $frame")
                    scheduleReconnect()
                }
            }

            "/meta/subscribe" -> {
                val id = clientId
                if (successful && id != null) {
                    sendConnect(id)
                } else {
                    Log.w(TAG, "Subscribe failed: $frame")
                    scheduleReconnect()
                }
            }

            "/meta/connect" -> {
                // Bayeux long-poll cycle: immediately issue the next connect.
                clientId?.let { sendConnect(it) }
            }

            "/user/$userId" -> {
                val data = frame["data"]?.jsonObject ?: return
                val type = data["type"]?.jsonPrimitive?.content
                if (type == "line.create") {
                    val subject = data["subject"]?.jsonObject ?: return
                    try {
                        onMessage(json.decodeFromJsonElement<GroupMeMessage>(subject))
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not decode pushed message: ${e.message}")
                    }
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        webSocket = null
        clientId = null
        val backoffMs = min(30_000L, 1000L * (1 shl min(reconnectAttempts, 5)))
        reconnectAttempts++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (!stopped) connect()
        }
    }

    private fun nextId(): String = (++messageId).toString()

    private fun send(frame: JsonObject) {
        webSocket?.send("[$frame]")
    }
}
