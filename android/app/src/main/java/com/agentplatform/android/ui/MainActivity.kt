package com.agentplatform.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.agentplatform.android.data.AppPrefs
import com.agentplatform.android.service.AgentForegroundService
import com.agentplatform.android.ui.capture.UiCaptureForegroundService
import com.agentplatform.android.ui.capture.UiCaptureManager

class MainActivity : ComponentActivity() {
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        UiCaptureForegroundService.startWithGrantedProjection(this, result.resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPrefs(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var bound by remember { mutableStateOf(prefs.isBound()) }
                    if (bound) {
                        BoundScreen(
                            prefs = prefs,
                            onRequestScreenCapture = {
                                screenCaptureLauncher.launch(UiCaptureManager.requestProjectionIntent(this))
                            },
                            onUnbind = {
                                stopAgentService()
                                UiCaptureForegroundService.stopService(this)
                                prefs.clear()
                                bound = false
                            }
                        )
                    } else {
                        EnrollScreen(
                            onBound = {
                                prefs.save(it.serverUrl, it.token, it.deviceId)
                                startAgentService()
                                bound = true
                            }
                        )
                    }
                }
            }
        }
        if (prefs.isBound()) startAgentService()
    }

    private fun startAgentService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AgentForegroundService::class.java)
        )
    }

    private fun stopAgentService() {
        stopService(Intent(this, AgentForegroundService::class.java))
    }
}
