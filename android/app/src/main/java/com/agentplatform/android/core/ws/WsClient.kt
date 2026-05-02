package com.agentplatform.android.core.ws

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * OkHttp WebSocket wrapper with exponential-backoff auto-reconnect.
 *
 * <p>Authenticates via {@code Sec-WebSocket-Protocol: bearer.<jwt>}. OkHttp
 * automatically does the WS handshake; we let the {@code TokenAware...}
 * server-side handler echo back {@code "bearer"}.
 *
 * <p>Threading: callbacks run on OkHttp's dispatcher thread. Callers should
 * post UI updates to the main looper themselves.
 */
class WsClient(
    private val serverUrl: String,
    private val token: String,
    private val mapper: ObjectMapper,
    private val onConnected: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onClosed: (code: Int, reason: String) -> Unit
) {

    private val http = OkHttpClient.Builder()
        // Native WS pings every 15s — home routers often drop idle UDP/TCP
        // mappings after 30s; 30s ping was too marginal and saw frequent
        // disconnects. 15s gives one retry before NAT eviction.
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 0 = no timeout for long-lived WS
        .build()

    private val wantOpen = AtomicBoolean(true)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var reconnectAttempt = 0

    fun connect() {
        wantOpen.set(true)
        openOnce()
    }

    fun close(reason: String = "client requested") {
        wantOpen.set(false)
        ws?.close(1000, reason)
        ws = null
    }

    /** Encode and send a JSON-RPC message. Drops silently if not connected. */
    fun send(msg: Any): Boolean {
        val w = ws ?: return false
        return try {
            w.send(mapper.writeValueAsString(msg))
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            false
        }
    }

    private fun openOnce() {
        val wsUrl = serverUrl.replace(Regex("^https?://"), "ws://").replace(Regex("^wss?://"), "ws://")
            .let { if (serverUrl.startsWith("https://")) it.replaceFirst("ws://", "wss://") else it }
            .trimEnd('/') + "/ws/device"

        val req = Request.Builder()
            .url(wsUrl)
            .addHeader("Sec-WebSocket-Protocol", "bearer.$token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS opened: $wsUrl")
                reconnectAttempt = 0
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed: $code $reason")
                this@WsClient.ws = null
                onClosed(code, reason)
                if (wantOpen.get()) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.javaClass.simpleName}: ${t.message}, code=${response?.code}", t)
                this@WsClient.ws = null
                val why = "${t.javaClass.simpleName}: ${t.message ?: "no msg"}"
                onClosed(response?.code ?: -1, why)
                if (wantOpen.get()) scheduleReconnect()
            }
        }
        ws = http.newWebSocket(req, listener)
    }

    /** Exponential backoff: 1s, 2s, 4s, 8s, ..., capped at 60s. */
    private fun scheduleReconnect() {
        val delayMs = min(60_000L, 1000L shl reconnectAttempt.coerceAtMost(6))
        reconnectAttempt++
        Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            if (wantOpen.get()) openOnce()
        }.start()
    }

    companion object {
        private const val TAG = "WsClient"
    }
}
