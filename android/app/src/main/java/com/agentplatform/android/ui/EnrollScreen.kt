package com.agentplatform.android.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.fasterxml.jackson.databind.ObjectMapper
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class BindResult(val serverUrl: String, val token: String, val deviceId: String)

@Composable
fun EnrollScreen(onBound: (BindResult) -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("phone") }
    var status by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runBind() {
        if (serverUrl.isBlank() || token.isBlank()) return
        inFlight = true
        status = null
        scope.launch {
            val r = runCatching { redeem(serverUrl.trim(), token.trim(), deviceName.trim()) }
            inFlight = false
            r.onSuccess {
                status = "已绑定"
                onBound(BindResult(serverUrl.trim(), it.token, it.deviceId))
            }.onFailure {
                status = "失败:${it.message}"
            }
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        val parsed = parseEnrollPayload(raw)
        if (parsed != null) {
            serverUrl = parsed.first
            token = parsed.second
            status = "已扫描 — 检查无误后点\"绑定\""
            // Auto-trigger bind on a clean scan; if anything's off the user can adjust.
            runBind()
        } else {
            status = "无法识别的二维码:${raw.take(60)}"
        }
    }

    Column(
        modifier = Modifier.padding(20.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("绑定此设备", style = MaterialTheme.typography.titleLarge)
        Text(
            "扫描网页管理端(设备页)显示的二维码,\n或在下方手动填写服务器地址和绑定 token。",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = {
                val opts = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setOrientationLocked(false)
                    setBeepEnabled(false)
                    setPrompt("对准绑定二维码")
                }
                scannerLauncher.launch(opts)
            },
            enabled = !inFlight,
            modifier = Modifier.fillMaxWidth()
        ) { Text("扫描二维码") }

        Text("— 或者 —", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(
            value = serverUrl, onValueChange = { serverUrl = it },
            label = { Text("服务器地址") },
            placeholder = { Text("https://agent.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it },
            label = { Text("绑定 Token") },
            placeholder = { Text("从网页端复制粘贴") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = deviceName, onValueChange = { deviceName = it },
            label = { Text("设备名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = ::runBind,
            enabled = !inFlight && serverUrl.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (inFlight) "绑定中…" else "绑定") }
        if (status != null) Text(status!!)
    }
}

/**
 * Parse the enrollment QR payload {@code agent-platform://enroll?server=X&token=Y}.
 * Returns null when the URI is malformed or doesn't match this scheme — caller
 * shows a diagnostic and lets the user retry / paste manually.
 */
private fun parseEnrollPayload(raw: String): Pair<String, String>? {
    return try {
        val uri = Uri.parse(raw.trim())
        if (uri.scheme != "agent-platform" || uri.host != "enroll") return null
        val server = uri.getQueryParameter("server")?.takeIf { it.isNotBlank() } ?: return null
        val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
        server to token
    } catch (_: Exception) { null }
}

private suspend fun redeem(serverUrl: String, enrollmentToken: String, deviceName: String): RedeemResp =
    withContext(Dispatchers.IO) {
        val mapper = ObjectMapper()
        val client = OkHttpClient()
        val body = mapper.writeValueAsString(
            mapOf(
                "name" to deviceName.ifBlank { "phone" },
                "model" to Build.MODEL,
                "osVersion" to Build.VERSION.RELEASE
            )
        ).toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/auth/redeem/$enrollmentToken")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string()}")
            }
            mapper.readValue(resp.body!!.byteStream(), RedeemResp::class.java)
        }
    }

/** Mirrors the server's RedeemResponse on the wire. */
data class RedeemResp(val deviceId: String = "", val token: String = "")
