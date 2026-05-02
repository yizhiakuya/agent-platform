package com.agentplatform.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class MainActivity : ComponentActivity() {

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
                            onUnbind = {
                                stopAgentService()
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
