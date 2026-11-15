package dev.adrianjost.lightbar

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Home Assistant WebSocket client: authenticates, loads all light states,
 * follows state changes and calls services. All listener callbacks run on the main thread.
 */
class HaClient(
    private val baseUrl: String,
    private val token: String,
    private val listener: Listener,
) {
    enum class Status { CONNECTING, CONNECTED, AUTH_FAILED, DISCONNECTED }

    interface Listener {
        fun onStatus(status: Status)
        /** Full snapshot of every `light.*` entity, sent once after connecting. */
        fun onStates(states: List<JSONObject>)
        fun onStateChanged(state: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var nextId = 1
    private var statesRequestId = -1
    private var closed = true

    var status = Status.DISCONNECTED
        private set

    private val retry = Runnable { if (!closed) open() }

    fun connect() {
        if (!closed) return
        closed = false
        open()
    }

    fun close() {
        closed = true
        main.removeCallbacks(retry)
        socket?.close(1000, null)
        socket = null
        status = Status.DISCONNECTED
    }

    fun callService(service: String, entityId: String, data: JSONObject? = null) {
        if (status != Status.CONNECTED) return
        send(
            JSONObject()
                .put("id", nextId++)
                .put("type", "call_service")
                .put("domain", "light")
                .put("service", service)
                .put("service_data", data ?: JSONObject())
                .put("target", JSONObject().put("entity_id", entityId))
        )
    }

    private fun open() {
        setStatus(Status.CONNECTING)
        val request = try {
            Request.Builder().url(websocketUrl(baseUrl)).build()
        } catch (e: IllegalArgumentException) {
            setStatus(Status.DISCONNECTED)
            return
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (webSocket === socket) handle(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
        })
    }

    private fun dropped(webSocket: WebSocket) {
        main.post {
            if (webSocket !== socket) return@post
            socket = null
            if (status != Status.AUTH_FAILED) setStatus(Status.DISCONNECTED)
            if (!closed && status != Status.AUTH_FAILED) main.postDelayed(retry, 3000)
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "auth_required" -> send(JSONObject().put("type", "auth").put("access_token", token))
            "auth_invalid" -> {
                setStatus(Status.AUTH_FAILED)
                socket?.close(1000, null)
            }
            "auth_ok" -> {
                nextId = 1
                statesRequestId = nextId++
                send(JSONObject().put("id", statesRequestId).put("type", "get_states"))
                send(
                    JSONObject().put("id", nextId++).put("type", "subscribe_events")
                        .put("event_type", "state_changed")
                )
                setStatus(Status.CONNECTED)
            }
            "result" -> if (msg.optInt("id") == statesRequestId) {
                val result = msg.optJSONArray("result") ?: return
                listener.onStates(
                    (0 until result.length())
                        .mapNotNull { result.optJSONObject(it) }
                        .filter { it.optString("entity_id").startsWith("light.") }
                )
            }
            "event" -> {
                val state = msg.optJSONObject("event")?.optJSONObject("data")?.optJSONObject("new_state") ?: return
                if (state.optString("entity_id").startsWith("light.")) listener.onStateChanged(state)
            }
        }
    }

    private fun send(obj: JSONObject) {
        socket?.send(obj.toString())
    }

    private fun setStatus(s: Status) {
        status = s
        listener.onStatus(s)
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        /** Accepts "192.168.1.5", "homeassistant.local:8123", "https://ha.example.com" and similar. */
        fun websocketUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            if (!url.contains("://")) {
                if (!url.contains(":")) url += ":8123"
                url = "http://$url"
            }
            url = url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            return "$url/api/websocket"
        }
    }
}
