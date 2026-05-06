package com.agentplatform.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agentplatform.android.AgentApplication
import com.agentplatform.android.confirm.ToolConfirmation
import com.agentplatform.android.core.tool.ToolRegistry
import com.agentplatform.android.core.ws.JsonRpcMethods
import com.agentplatform.android.core.ws.JsonRpcNotification
import com.agentplatform.android.core.ws.JsonRpcRequest
import com.agentplatform.android.core.ws.JsonRpcResponse
import com.agentplatform.android.core.ws.WsClient
import com.agentplatform.android.core.ws.decodeJsonRpc
import com.agentplatform.android.core.ws.JsonRpcError
import com.agentplatform.android.data.AppPrefs
import com.agentplatform.android.photos.PhotoIndexUploader
import com.agentplatform.android.tools.photos.PhotosGetFullTool
import com.agentplatform.android.tools.photos.PhotosGetMetadataTool
import com.agentplatform.android.tools.photos.PhotosListAlbumsTool
import com.agentplatform.android.tools.photos.PhotosListByAlbumTool
import com.agentplatform.android.tools.photos.PhotosListRecentTool
import com.agentplatform.android.tools.photos.PhotosRecentScreenshotsTool
import com.agentplatform.android.tools.photos.PhotosSemanticCandidatesTool
import com.agentplatform.android.tools.ui.UiDumpTreeTool
import com.agentplatform.android.tools.ui.UiGlobalTool
import com.agentplatform.android.tools.ui.UiOpenAppTool
import com.agentplatform.android.tools.ui.UiScreenCaptureTool
import com.agentplatform.android.tools.ui.UiSwipeTool
import com.agentplatform.android.tools.ui.UiTapTool
import com.agentplatform.android.tools.ui.UiTypeTextTool
import com.agentplatform.android.tools.videos.VideosListRecentTool
import com.agentplatform.android.ui.MainActivity
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service that owns the long-lived WebSocket to the platform
 * server. Receives {@code tool.call} requests and dispatches them to the
 * {@link ToolRegistry}, sends back a {@link JsonRpcResponse} per call.
 *
 * <p>FGS type is {@code specialUse} (Android 14+), see AndroidManifest.
 */
class AgentForegroundService : Service() {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val toolRegistry = ToolRegistry()
    private lateinit var wsClient: WsClient
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTI_ID, buildNotification(getString(android.R.string.dialog_alert_title)))

        // PR 11: register the device's tools. Add more here as we ship them.
        toolRegistry.register(PhotosListRecentTool(applicationContext, mapper))
        toolRegistry.register(PhotosGetMetadataTool(applicationContext, mapper))
        toolRegistry.register(PhotosGetFullTool(applicationContext, mapper))
        toolRegistry.register(PhotosListAlbumsTool(applicationContext, mapper))
        toolRegistry.register(PhotosListByAlbumTool(applicationContext, mapper))
        toolRegistry.register(PhotosRecentScreenshotsTool(applicationContext, mapper))
        toolRegistry.register(PhotosSemanticCandidatesTool(applicationContext, mapper))
        toolRegistry.register(VideosListRecentTool(applicationContext, mapper))
        toolRegistry.register(UiDumpTreeTool(mapper))
        toolRegistry.register(UiScreenCaptureTool(mapper))
        toolRegistry.register(UiOpenAppTool(applicationContext, mapper))
        toolRegistry.register(UiTapTool(mapper))
        toolRegistry.register(UiSwipeTool(mapper))
        toolRegistry.register(UiTypeTextTool(mapper))
        toolRegistry.register(UiGlobalTool(mapper))

        val prefs = AppPrefs(this)
        if (!prefs.isBound()) {
            Log.w(TAG, "Not bound — stopping service")
            stopSelf()
            return
        }

        wsClient = WsClient(
            serverUrl = prefs.serverUrl!!,
            token = prefs.token!!,
            mapper = mapper,
            onConnected = ::onWsConnected,
            onMessage = ::onWsMessage,
            onClosed = { code, reason -> updateNotification("连接已关闭 $code: ${reason.take(40)}") }
        )
        wsClient.connect()
        startPhotoIndexSync()
    }

    /** Tracks the last server-pushed frame for visibility in the FGS notification. */
    @Volatile private var lastRecvSummary: String = "暂未收到帧"

    private fun onWsConnected() {
        updateNotification("已连接")
        // Step 1: hello handshake
        val helloId = UUID.randomUUID().toString()
        val helloParams: ObjectNode = mapper.createObjectNode()
        helloParams.put("protocolVersion", "1")
        val device = mapper.createObjectNode()
        device.put("model", Build.MODEL)
        device.put("manufacturer", Build.MANUFACTURER)
        device.put("osVersion", Build.VERSION.RELEASE)
        helloParams.set<ObjectNode>("deviceInfo", device)
        wsClient.send(JsonRpcRequest(id = helloId, method = JsonRpcMethods.HELLO, params = helloParams))

        // Step 2: announce manifest immediately (server will look up by deviceId)
        val manifestParams = toolRegistry.toManifestParams(mapper)
        wsClient.send(JsonRpcNotification(method = JsonRpcMethods.TOOL_MANIFEST, params = manifestParams))
    }

    private fun onWsMessage(text: String) {
        // PR-debug: surface every received frame in the notification so we can
        // tell from the status bar whether OkHttp delivers server pushes.
        lastRecvSummary = "${text.length}b ${text.take(60).replace('\n', ' ')}"
        updateNotification("已收到 $lastRecvSummary")

        val msg = try { decodeJsonRpc(mapper, text) } catch (e: Exception) {
            Log.w(TAG, "Bad WS message: ${e.message}")
            updateNotification("收到但解析失败 ${e.message?.take(40)}")
            return
        }
        when (msg) {
            is JsonRpcRequest -> handleRequest(msg)
            is JsonRpcNotification -> handleNotification(msg)
            is JsonRpcResponse -> Log.d(TAG, "Got response for ${msg.id}")
            else -> Log.d(TAG, "Ignored WS message: $text")
        }
    }

    private fun handleRequest(req: JsonRpcRequest) {
        when (req.method) {
            JsonRpcMethods.TOOL_CALL -> dispatchToolCall(req)
            // We don't currently expect server → device requests other than tool.call.
            else -> {
                Log.d(TAG, "Unknown server request: ${req.method}")
                wsClient.send(
                    JsonRpcResponse(
                        id = req.id,
                        error = JsonRpcError(JsonRpcError.INVALID_PARAMS, "method ${req.method} not supported")
                    )
                )
            }
        }
    }

    private fun handleNotification(note: JsonRpcNotification) {
        // PR 13 may handle $/cancel here to abort an in-flight tool job.
        Log.d(TAG, "Notification: ${note.method}")
    }

    private fun dispatchToolCall(req: JsonRpcRequest) {
        val toolName = req.params?.get("tool")?.asText()
        val args = req.params?.get("args") ?: mapper.createObjectNode()
        if (toolName == null) {
            wsClient.send(JsonRpcResponse(
                id = req.id,
                error = JsonRpcError(JsonRpcError.INVALID_PARAMS, "missing tool name")
            ))
            return
        }
        val tool = toolRegistry.get(toolName)
        if (tool == null) {
            wsClient.send(JsonRpcResponse(
                id = req.id,
                error = JsonRpcError(JsonRpcError.TOOL_NOT_FOUND, "tool '$toolName' not found on this device")
            ))
            return
        }

        scope.launch {
            try {
                if (tool.confirmRequired && !AppPrefs(applicationContext).autoApproveUiTools) {
                    val approved = ToolConfirmation.request(applicationContext, tool.name, args)
                    if (!approved) {
                        wsClient.send(JsonRpcResponse(
                            id = req.id,
                            error = JsonRpcError(
                                JsonRpcError.CONFIRMATION_REJECTED,
                                "user rejected tool '${tool.name}'"
                            )
                        ))
                        return@launch
                    }
                }
                val result = tool.execute(args)
                wsClient.send(JsonRpcResponse(id = req.id, result = result))
            } catch (e: Exception) {
                Log.w(TAG, "Tool ${tool.name} failed", e)
                wsClient.send(JsonRpcResponse(
                    id = req.id,
                    error = JsonRpcError(JsonRpcError.INTERNAL_ERROR, e.message ?: "tool failed")
                ))
            }
        }
    }

    private fun startPhotoIndexSync() {
        scope.launch {
            val uploader = PhotoIndexUploader(applicationContext, mapper)
            while (true) {
                try {
                    val result = uploader.syncOnce()
                    Log.i(TAG, "photo index sync ${result.status}: scanned=${result.scanned} uploaded=${result.uploaded}")
                    val nextDelay = if (result.uploaded > 0) PHOTO_INDEX_ACTIVE_DELAY_MS else PHOTO_INDEX_IDLE_DELAY_MS
                    delay(nextDelay)
                } catch (e: Exception) {
                    Log.w(TAG, "photo index sync failed: ${e.message}", e)
                    delay(PHOTO_INDEX_ERROR_DELAY_MS)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        if (::wsClient.isInitialized) wsClient.close("service destroyed")
        scope.cancel()
        _status.value = "已停止"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* -------------------- foreground notification -------------------- */

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AgentApplication.FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Agent Platform")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        _status.value = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTI_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "AgentFGS"
        private const val NOTI_ID = 1001
        private const val PHOTO_INDEX_ACTIVE_DELAY_MS = 5_000L
        private const val PHOTO_INDEX_IDLE_DELAY_MS = 15 * 60_000L
        private const val PHOTO_INDEX_ERROR_DELAY_MS = 60_000L

        /**
         * Single shared status string updated alongside the foreground notification.
         * UI (BoundScreen) collects this so users can see "Connected"/"recv ..."/
         * "WS closed ..." without pulling down the notification shade.
         */
        private val _status = MutableStateFlow("已停止")
        val status: StateFlow<String> = _status.asStateFlow()
    }
}
