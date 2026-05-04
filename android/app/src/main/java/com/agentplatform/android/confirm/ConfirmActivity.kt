package com.agentplatform.android.confirm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class ConfirmActivity : ComponentActivity() {
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val requestIdState = mutableStateOf<String?>(null)
    private val toolNameState = mutableStateOf("unknown tool")
    private val summaryState = mutableStateOf("")
    private var finishedWithDecision = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateFromIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("批准工具调用", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        Text("工具: ${toolNameState.value}")
                        if (summaryState.value.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(summaryState.value)
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { finishWithDecision(requestIdState.value, false) }) {
                                Text("拒绝")
                            }
                            Button(onClick = { finishWithDecision(requestIdState.value, true) }) {
                                Text("批准")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateFromIntent(intent)
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations && !finishedWithDecision) {
            ToolConfirmation.resolve(requestIdState.value, false)
        }
        super.onDestroy()
    }

    private fun updateFromIntent(intent: Intent?) {
        val nextRequestId = intent?.getStringExtra(ToolConfirmation.EXTRA_REQUEST_ID)
        val previousRequestId = requestIdState.value
        if (!finishedWithDecision && previousRequestId != null && previousRequestId != nextRequestId) {
            ToolConfirmation.resolve(previousRequestId, false)
        }

        finishedWithDecision = false
        requestIdState.value = nextRequestId
        toolNameState.value = intent?.getStringExtra(ToolConfirmation.EXTRA_TOOL_NAME) ?: "unknown tool"
        summaryState.value = intent?.getStringExtra(ToolConfirmation.EXTRA_SUMMARY) ?: ""
        scheduleTimeout(nextRequestId)
    }

    private fun scheduleTimeout(requestId: String?) {
        timeoutHandler.removeCallbacksAndMessages(null)
        if (requestId == null) return
        timeoutHandler.postDelayed({
            if (!finishedWithDecision && requestIdState.value == requestId) {
                finishWithDecision(requestId, false)
            }
        }, ToolConfirmation.TIMEOUT_MS)
    }

    private fun finishWithDecision(requestId: String?, approved: Boolean) {
        finishedWithDecision = true
        timeoutHandler.removeCallbacksAndMessages(null)
        ToolConfirmation.resolve(requestId, approved)
        setResult(if (approved) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }
}
