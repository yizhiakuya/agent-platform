package com.agentplatform.android.ui.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Singleton owning the MediaProjection token + a long-lived ImageReader so
 * [UiScreenCaptureTool] can synthesise a screenshot without spinning up a
 * new MediaProjection (which always pops the system "allow recording?"
 * dialog) every time.
 *
 * Lifecycle:
 *   1. [requestProjectionIntent] — built by MainActivity, launched via
 *      ActivityResultLauncher to get user consent.
 *   2. [onProjectionGranted] — Activity hands back resultCode + data; we
 *      start [UiCaptureForegroundService] (mandatory FGS for projection
 *      from API 33+) which calls [start] with the consent.
 *   3. [captureNow] — synchronous-ish (suspending) snapshot off the
 *      ImageReader's most recent frame; reused per request.
 *   4. [stop] — release projection + ImageReader. Triggered when the user
 *      revokes consent (calling stop on the FGS notification action) or
 *      when the host app is force-stopped.
 */
object UiCaptureManager {
    private const val TAG = "UiCaptureManager"
    private const val MAX_IMAGES = 2
    /** Capped by Android's MediaProjection — we don't need full native res
     *  since the LLM doesn't read fine print. ~720p balances vision quality
     *  with token cost. The aspect ratio matches the device, so coordinates
     *  the LLM returns scale linearly back to native pixels. */
    private const val MAX_WIDTH = 1280
    private const val MAX_HEIGHT = 1280

    private val projection = AtomicReference<MediaProjection?>(null)
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var deviceWidth: Int = 0
    private var deviceHeight: Int = 0

    fun requestProjectionIntent(ctx: Context): Intent {
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    /** True after the user has granted projection AND the FGS has called [start]. */
    fun isReady(): Boolean = projection.get() != null && imageReader != null

    /**
     * Wire up the projection from a granted Activity result. Must be called
     * from inside an FGS — Android refuses MediaProjection.createVirtualDisplay
     * outside of one (API 33+ getMediaProjection() throws SecurityException
     * in plain background).
     */
    fun start(ctx: Context, resultCode: Int, data: Intent) {
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "projection denied: resultCode=$resultCode")
            return
        }
        if (isReady()) {
            Log.i(TAG, "projection already ready, ignoring duplicate start")
            return
        }
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mgr.getMediaProjection(resultCode, data)
        if (mp == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return
        }
        // Required from API 34: register a callback so we know when the
        // system revokes the projection (user dismissed the screencast
        // notification, killed our app, etc).
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopInternal("system callback onStop") }
        }, null)

        val (devW, devH, dpi) = readDisplayMetrics(ctx)
        deviceWidth = devW
        deviceHeight = devH
        // Scale down preserving aspect so neither dimension exceeds MAX_*.
        val scale = minOf(
            1.0,
            MAX_WIDTH.toDouble() / devW,
            MAX_HEIGHT.toDouble() / devH
        )
        captureWidth = (devW * scale).toInt().coerceAtLeast(1)
        captureHeight = (devH * scale).toInt().coerceAtLeast(1)

        captureThread = HandlerThread("ui-capture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, MAX_IMAGES)
        virtualDisplay = mp.createVirtualDisplay(
            "agent-platform-capture",
            captureWidth,
            captureHeight,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )
        projection.set(mp)
        Log.i(TAG, "capture started: $captureWidth x $captureHeight (device $devW x $devH)")
    }

    /** Snapshot the latest frame from the ImageReader, decoded into a
     *  Bitmap. Returns null if no frame has arrived yet (user just opened
     *  the consent dialog and projection isn't producing frames yet). */
    fun captureNow(): Bitmap? {
        val reader = imageReader ?: return null
        // acquireLatestImage drops earlier queued frames so we always see
        // current state (we're polling, not streaming).
        val image: Image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth
            val width = captureWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(width, captureHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            // Trim row-padding columns when present.
            if (width != captureWidth) {
                val trimmed = Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
                bitmap.recycle()
                trimmed
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureNow decode failed: ${e.message}")
            null
        } finally {
            image.close()
        }
    }

    /** Native (un-scaled) screen pixels — used by [UiScreenCaptureTool] to
     *  return a {capture: {w,h}, device: {w,h}} record so the LLM can
     *  rescale tap coordinates if it wants. */
    fun captureSize(): Pair<Int, Int> = captureWidth to captureHeight
    fun deviceSize(): Pair<Int, Int> = deviceWidth to deviceHeight

    fun stop() = stopInternal("explicit stop")

    private fun stopInternal(reason: String) {
        Log.i(TAG, "stopping projection: $reason")
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            projection.getAndSet(null)?.stop()
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
            captureWidth = 0
            captureHeight = 0
        } catch (e: Exception) {
            Log.w(TAG, "stop encountered: ${e.message}")
        }
    }

    private fun readDisplayMetrics(ctx: Context): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            // densityDpi pulled from resources; ImageReader needs DPI in addition to size.
            val dpi = ctx.resources.displayMetrics.densityDpi
            return Triple(bounds.width(), bounds.height(), dpi)
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }
}
