package com.agentplatform.android.confirm

import android.os.Bundle
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Stub for the per-tool-call confirmation prompt (sensitive tools).
 *
 * <p>PR 11+ wires this in: the foreground service launches this activity in
 * "single task" mode whenever a {@link com.agentplatform.android.core.tool.Tool}
 * with {@code confirmRequired=true} is about to fire, blocking on the user's
 * approve/deny choice.
 */
class ConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolName = intent.getStringExtra("toolName") ?: "<未知工具>"
        val summary = intent.getStringExtra("summary") ?: ""
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("批准工具调用", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        Text("工具:$toolName")
                        if (summary.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(summary)
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { setResult(RESULT_CANCELED); finish() }) {
                                Text("拒绝")
                            }
                            Button(onClick = { setResult(RESULT_OK); finish() }) {
                                Text("批准")
                            }
                        }
                    }
                }
            }
        }
    }
}
