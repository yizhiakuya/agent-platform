package com.agentplatform.android.media

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity

/**
 * Tiny trampoline activity for Android's MediaStore confirmation PendingIntents.
 *
 * The foreground service cannot call startIntentSenderForResult directly, so it
 * asks this activity to launch the system UI and report the result back to
 * MediaStoreRequestBridge.
 */
class MediaStoreRequestActivity : ComponentActivity() {
    private var requestId: String? = null
    private var launched = false
    private var finishedWithResult = false

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        finishedWithResult = true
        MediaStoreRequestBridge.resolve(requestId, result.resultCode == Activity.RESULT_OK)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(MediaStoreRequestBridge.EXTRA_REQUEST_ID)
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) ?: false
        if (!launched) {
            launchSystemRequest(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LAUNCHED, launched)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && !finishedWithResult) {
            MediaStoreRequestBridge.resolve(requestId, false)
        }
        super.onDestroy()
    }

    private fun launchSystemRequest(intent: Intent?) {
        val pendingIntent = pendingIntentFrom(intent)
        if (requestId.isNullOrBlank() || pendingIntent == null) {
            finishedWithResult = true
            MediaStoreRequestBridge.resolve(requestId, false)
            finish()
            return
        }

        launched = true
        try {
            launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } catch (_: Exception) {
            finishedWithResult = true
            MediaStoreRequestBridge.resolve(requestId, false)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun pendingIntentFrom(intent: Intent?): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(
                MediaStoreRequestBridge.EXTRA_PENDING_INTENT,
                PendingIntent::class.java
            )
        } else {
            intent?.getParcelableExtra(MediaStoreRequestBridge.EXTRA_PENDING_INTENT)
        }
    }

    companion object {
        private const val KEY_LAUNCHED = "launched"
    }
}
