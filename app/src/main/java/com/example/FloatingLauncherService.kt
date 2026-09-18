package com.example

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import kotlin.math.min
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ui.theme.MyApplicationTheme
import com.example.data.OrbitDatabase
import com.example.data.VaultEntry
import com.example.capture.ScreenCaptureManager
import com.example.ocr.OcrManager
import com.google.mlkit.common.sdkinternal.MlKitContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

class FloatingLauncherService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: FrameLayout? = null
    private var bubbleImageView: ImageView? = null
    private var isViewAdded = false
    private var snapAnimator: ValueAnimator? = null
    
    private var isBubbleHidden = false
    private var bubbleParams: WindowManager.LayoutParams? = null
    
    private var dismissZoneView: FrameLayout? = null
    private var isDismissZoneAdded = false
    
    private var springX: SpringAnimation? = null

    // Background scope to preload the cache without blocking the main UI thread
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Dynamically registered receivers to avoid battery drain when screen is idle
    private var screenReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "FloatingLauncherService"
        private const val CHANNEL_ID = "floating_launcher_channel"
        private const val CAPTURE_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 4132
        /** Separate notification ID for the screen-capture-in-progress foreground notification. */
        private const val CAPTURE_NOTIFICATION_ID = 4133
        const val ACTION_UPDATE_THEME = "com.example.ACTION_UPDATE_THEME"
        const val ACTION_SHOW_BUBBLE = "com.example.ACTION_SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.example.ACTION_HIDE_BUBBLE"
        const val ACTION_START_PROJECTION = "com.example.ACTION_START_PROJECTION"
        const val ACTION_NOTIFICATION_DISMISSED = "com.example.ACTION_NOTIFICATION_DISMISSED"

        /**
         * New action: perform a single screen capture + OCR pass on the supplied region.
         * Extras expected:
         *   - [EXTRA_RESULT_CODE]: Int, the Activity result code from MediaProjectionManager.createScreenCaptureIntent()
         *   - [EXTRA_RESULT_DATA]: Intent (Parcelable), the result data Intent from the same call
         *   - [EXTRA_CAPTURE_REGION]: Rect (Parcelable), pixel coords of the region to capture+OCR
         */
        const val ACTION_CAPTURE_SCREEN = "com.example.ACTION_CAPTURE_SCREEN"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CAPTURE_REGION = "extra_capture_region"

        /**
         * Reliability delay between "UI fully dismissed" and "trigger MediaProjection capture".
         *
         * Why this exists: when the user taps "Capture", Orbit's overlay activity and bubble
         * are still mid-dismiss-animation. If we capture immediately, the resulting frame
         * contains Orbit's own UI instead of the underlying app the user wanted to scan.
         * 350ms empirically gives both the overlay activity's exit animation AND the
         * WindowManager's removal of the bubble enough time to complete on slow devices.
         *
         * Named (not a magic number) so callers can find / tune it in one place.
         */
        const val CAPTURE_SETTLE_DELAY_MS = 350L

        // Accessible from MainActivity to observe service lifecycle state in real-time
        val isServiceRunning = MutableStateFlow(false)

        @Volatile
        var instance: FloatingLauncherService? = null

        /**
         * Instantly respawns the launcher notification when swiped away by the user.
         */
        fun repostNotificationImmediately(context: Context) {
            val service = instance
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (service != null) {
                val notif = service.buildNotification(service.isBubbleHidden)
                manager.notify(NOTIFICATION_ID, notif)
            } else if (Settings.canDrawOverlays(context)) {
                val showIntent = Intent(context, FloatingLauncherService::class.java).apply {
                    action = ACTION_SHOW_BUBBLE
                }
                val pendingIntent = PendingIntent.getService(
                    context,
                    1,
                    showIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val deletePendingIntent = com.example.shortcut.NotificationDismissReceiver.createLauncherDeleteIntent(context)
                val runShortsPendingIntent = com.example.shortcut.NotificationDismissReceiver.createRunShortsIntent(context)
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.orbit_launcher_active))
                    .setSmallIcon(android.R.drawable.ic_dialog_dialer)
                    .setContentIntent(pendingIntent)
                    .setDeleteIntent(deletePendingIntent)
                    .addAction(
                        android.R.drawable.ic_media_play,
                        context.getString(R.string.action_run_shorts),
                        runShortsPendingIntent
                    )
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build()
                manager.notify(NOTIFICATION_ID, notif)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Explicitly initialize MlKitContext early to avoid initialization-order issues in a Service-based context.
        try {
            MlKitContext.initializeIfNeeded(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MlKitContext", e)
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isServiceRunning.value = true
        instance = this

        registerScreenReceiver()
        registerPackageReceiver()

        // Preload in-memory cache in background to achieve instant launch
        serviceScope.launch {
            AppCache.getApps(this@FloatingLauncherService)
        }

        // Periodic background Wi-Fi telemetry update so downloads (e.g. Play Store games) are tracked
        serviceScope.launch {
            while (isActive) {
                try {
                    WifiMonitorPreferences.updateTrafficDelta(applicationContext)
                } catch (_: Throwable) {}
                delay(10_000L) // every 10 seconds
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        // Default foreground start: bubble service, specialUse type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(isBubbleHidden), 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(isBubbleHidden))
        }

        when (intent?.action) {
            ACTION_UPDATE_THEME -> {
                updateBubbleThemeAndSize()
            }
            ACTION_SHOW_BUBBLE -> {
                showBubble()
            }
            ACTION_HIDE_BUBBLE -> {
                hideBubbleForCapture()
            }
            ACTION_START_PROJECTION -> {
                handleStartProjectionIntent(intent)
            }
            ACTION_CAPTURE_SCREEN -> {
                handleCaptureScreenIntent(intent)
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                if (Settings.canDrawOverlays(this)) {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(isBubbleHidden))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            startForeground(
                                NOTIFICATION_ID,
                                buildNotification(isBubbleHidden),
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error re-attaching foreground notification", e)
                        }
                    }
                }
            }
            else -> {
                if (isBubbleHidden) {
                    showBubble()
                } else {
                    addFloatingBubble()
                }
            }
        }

        return START_STICKY
    }

    private fun handleStartProjectionIntent(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val combinedType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    startForeground(
                        CAPTURE_NOTIFICATION_ID,
                        buildCaptureNotification(),
                        combinedType
                    )
                } else {
                    startForeground(CAPTURE_NOTIFICATION_ID, buildCaptureNotification())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to promote foreground service to mediaProjection type on start", t)
            }
            ScreenCaptureManager.createProjection(this, resultCode, resultData)
        }
        hideBubbleForCapture()
    }

    /**
     * Handles [ACTION_CAPTURE_SCREEN]: promotes the foreground service to also include the
     * mediaProjection type (required by Android 14+ within 10s of the activity-result being
     * granted), hides the bubble, waits [CAPTURE_SETTLE_DELAY_MS], captures the requested
     * region, runs OCR, saves the result into the Vault, restores the bubble, and reopens
     * the OverlayActivity on the Vault tab so the user sees the new entry.
     *
     * Two entry paths:
     *   - First scan this session: [EXTRA_RESULT_CODE] + [EXTRA_RESULT_DATA] both present.
     *     We call [ScreenCaptureManager.createProjection] to build a fresh MediaProjection.
     *   - Subsequent scans: [EXTRA_RESULT_DATA] is null because we reuse the cached
     *     projection. We skip [createProjection] and go straight to capture. This avoids
     *     re-prompting the user with the consent dialog on every single scan (previous
     *     failure mode).
     */
    private fun handleCaptureScreenIntent(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val region: Rect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CAPTURE_REGION, Rect::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CAPTURE_REGION)
        }

        if (region == null) {
            Log.e(TAG, "ACTION_CAPTURE_SCREEN missing required region extra; aborting")
            restoreBubbleAndSpecialUseForeground()
            return
        }

        // 1) Promote foreground service to include mediaProjection type. This MUST happen
        //    within 10s of the user granting the projection consent dialog. On the
        //    "reuse existing projection" path the projection was already granted earlier
        //    this session, but we still need the foreground service type to be active
        //    while we capture.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val combinedType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(
                    CAPTURE_NOTIFICATION_ID,
                    buildCaptureNotification(),
                    combinedType
                )
            } else {
                startForeground(CAPTURE_NOTIFICATION_ID, buildCaptureNotification())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to promote foreground service to mediaProjection type", t)
        }

        // 2) Build (or reuse) the MediaProjection.
        if (resultData != null) {
            // First scan this session — build a fresh projection from the consent result.
            ScreenCaptureManager.createProjection(this, resultCode, resultData)
        } else {
            // Subsequent scan — projection should already be cached from a previous scan.
            if (!ScreenCaptureManager.hasActiveProjection()) {
                Log.e(TAG, "ACTION_CAPTURE_SCREEN: no resultData AND no cached projection; aborting")
                restoreBubbleAndSpecialUseForeground()
                return
            }
            Log.d(TAG, "Reusing cached MediaProjection for this scan")
        }

        // 3) Hide the bubble so it doesn't appear in the captured frame.
        hideBubbleForCapture()

        serviceScope.launch {
            try {
                // 4) Reliability delay — let the overlay activity's exit animation AND
                //    the bubble's removal from WindowManager complete before we capture.
                delay(CAPTURE_SETTLE_DELAY_MS)

                // 5) Capture the region into an in-memory Bitmap. Never written to disk.
                val bitmap = ScreenCaptureManager.captureRegion(this@FloatingLauncherService, region)

                if (bitmap == null) {
                    Log.e(TAG, "ScreenCaptureManager.captureRegion returned null; OCR aborted")
                    val details = ScreenCaptureManager.lastError ?: "Unable to take screenshot"
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@FloatingLauncherService,
                            "Capture failed: $details",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        restoreBubbleAndSpecialUseForeground()
                        reopenVaultTab()
                    }
                    return@launch
                }

                // 6) Run OCR via Google ML Kit Text Recognition.
                var errorText: String? = null
                val recognizedText = try {
                    OcrManager.recognize(bitmap)
                } catch (t: Throwable) {
                    Log.e(TAG, "OcrManager.recognize threw", t)
                    errorText = t.localizedMessage ?: t.toString()
                    ""
                } finally {
                    bitmap.recycle()
                }

                Log.d(TAG, "OCR result length=${recognizedText.length}, first 60 chars='${recognizedText.take(60)}'")

                // 7) Save the recognized text into the Vault as an OCR-sourced entry.
                if (recognizedText.isNotBlank()) {
                    val database = OrbitDatabase.getDatabase(this@FloatingLauncherService)
                    database.vaultDao().insert(
                        VaultEntry(
                            content = recognizedText,
                            source = "OCR",
                            folderId = null
                        )
                    )
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@FloatingLauncherService,
                            "Saved to Vault: ${recognizedText.take(30)}...",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.w(TAG, "OCR produced empty text; nothing saved to Vault")
                    withContext(Dispatchers.Main) {
                        val msg = if (errorText != null) {
                            "OCR failed: $errorText"
                        } else {
                            "OCR: No text found in selected region"
                        }
                        android.widget.Toast.makeText(
                            this@FloatingLauncherService,
                            msg,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // 8) Restore the bubble and revert the foreground notification to the
                //    persistent-bubble specialUse form.
                withContext(Dispatchers.Main) {
                    restoreBubbleAndSpecialUseForeground()
                    // 9) Reopen the OverlayActivity directly to the Vault tab so the user
                    //    sees the newly-created entry immediately.
                    reopenVaultTab()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Capture+OCR flow failed", t)
                withContext(Dispatchers.Main) {
                    restoreBubbleAndSpecialUseForeground()
                    reopenVaultTab()
                }
            }
        }
    }

    /**
     * Brings the bubble back to the screen and reverts the foreground notification to
     * the persistent-bubble form (specialUse type only, NOTIFICATION_ID).
     *
     * Safe to call multiple times. Called at the end of every capture flow regardless
     * of success/failure so the user is never left without their bubble.
     */
    private fun restoreBubbleAndSpecialUseForeground() {
        try {
            showBubble()
        } catch (t: Throwable) {
            Log.w(TAG, "showBubble() threw during restore", t)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(isBubbleHidden),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(isBubbleHidden))
            }
            // Cancel the capture-specific notification since we're back to bubble mode.
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(CAPTURE_NOTIFICATION_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to restore specialUse foreground notification", t)
        }
    }

    /**
     * Reopens [OverlayActivity] and routes it directly to the Vault tab so the user
     * immediately sees their freshly-OCR'd entry.
     */
    private fun reopenVaultTab() {
        try {
            val launchIntent = Intent(this, OverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("launch_tab", "vault")
            }
            startActivity(launchIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reopen OverlayActivity on Vault tab", t)
        }
    }

    /**
     * Hides the bubble specifically for the capture flow. Distinct from [hideBubble]
     * because we want to forcibly hide even if [isViewAdded] is already false (defensive).
     */
    private fun hideBubbleForCapture() {
        try {
            if (isViewAdded && bubbleView != null) {
                windowManager?.removeView(bubbleView)
                isViewAdded = false
                isBubbleHidden = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hideBubbleForCapture: removeView threw", t)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Pause layout updates, hide bubble from WindowManager and stop all animations immediately
                        bubbleView?.visibility = View.GONE
                        snapAnimator?.cancel()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        // Resume and show the bubble again when the user is active
                        bubbleView?.visibility = View.VISIBLE
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun registerPackageReceiver() {
        if (packageReceiver != null) return
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context?.let { ctx ->
                    // Run refresh in background thread to avoid blockages
                    serviceScope.launch {
                        AppCache.refreshCache(ctx)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }

private class BubbleBackgroundDrawable(
    private var accentColor: Int,
    private var glowIntensity: Float,
    private var borderStrokeDp: Float,
    private val density: Float
) : Drawable() {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    fun updateParams(accentColor: Int, glowIntensity: Float, borderStrokeDp: Float) {
        this.accentColor = accentColor
        this.glowIntensity = glowIntensity
        this.borderStrokeDp = borderStrokeDp
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return

        val minDim = min(w, h)
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        // Glow
        if (glowIntensity > 0f) {
            val alphaInt = (glowIntensity * 255).toInt().coerceIn(0, 255)
            glowPaint.shader = RadialGradient(
                cx, cy, minDim / 2f,
                intArrayOf(
                    (accentColor and 0x00FFFFFF) or (alphaInt shl 24),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, minDim / 2f, glowPaint)
        }

        // Outer border stroke fitting INSIDE bounds
        val strokeWidthPx = borderStrokeDp * density
        if (strokeWidthPx > 0f) {
            borderPaint.color = accentColor
            borderPaint.style = Paint.Style.STROKE
            borderPaint.strokeWidth = strokeWidthPx
            val radius = (minDim / 2f) - (strokeWidthPx / 2f)
            if (radius > 0f) {
                canvas.drawCircle(cx, cy, radius, borderPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

    private fun updateBubbleThemeAndSize() {
        val context = this
        val theme = ThemePreferences.getSelectedTheme(context)
        val color = theme.colorValue.toInt()
        
        val sizeDp = ThemePreferences.getBubbleSize(context)
        val sizePx = dpToPx(sizeDp.toFloat(), context)
        val symbolKey = ThemePreferences.getBubbleSymbol(context)
        val drawableRes = ThemePreferences.getBubbleIconDrawableRes(symbolKey)
        val glowIntensity = ThemePreferences.getGlowIntensity(context)
        val opacity = ThemePreferences.getBubbleOpacity(context)
        val borderStrokeDp = ThemePreferences.getBorderStrokeDp(context).toFloat()
        val density = resources.displayMetrics.density

        bubbleView?.let { view ->
            view.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            val bgDrawable = view.background as? BubbleBackgroundDrawable
            if (bgDrawable != null) {
                bgDrawable.updateParams(color, glowIntensity, borderStrokeDp)
            } else {
                view.background = BubbleBackgroundDrawable(color, glowIntensity, borderStrokeDp, density)
            }
            view.alpha = opacity.coerceIn(0.1f, 1.0f)
            view.invalidate()
            
            // Find ImageView child and update layout params and image resource
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is ImageView) {
                    child.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    val customBitmap = if (symbolKey == "custom") ThemePreferences.loadCustomBubbleBitmap(context) else null
                    if (customBitmap != null) {
                        child.setImageBitmap(customBitmap)
                    } else {
                        child.setImageResource(drawableRes)
                    }
                    val innerTint = ThemePreferences.getInnerTintColor(context)
                    if (innerTint != 0L && customBitmap == null) {
                        child.setColorFilter(innerTint.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    } else {
                        child.clearColorFilter()
                    }
                    child.alpha = (0.3f + (0.7f * glowIntensity)).coerceIn(0.3f, 1.0f)
                }
            }
            
            bubbleParams?.let { params ->
                params.width = sizePx
                params.height = sizePx
                try {
                    windowManager?.updateViewLayout(view, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (isBubbleHidden && bubbleView != null) {
            try {
                val sizeDp = ThemePreferences.getBubbleSize(this)
                val sizePx = dpToPx(sizeDp.toFloat(), this)
                bubbleParams?.let { params ->
                    params.width = sizePx
                    params.height = sizePx
                    windowManager?.addView(bubbleView, params)
                }
                isViewAdded = true
                isBubbleHidden = false
                updateNotification(false)
            } catch (e: Exception) {
                e.printStackTrace()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this@FloatingLauncherService,
                        "Unable to display overlay. Please check 'Display over other apps' permission.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else if (bubbleView == null) {
            // Bubble was never created — build it fresh.
            addFloatingBubble()
        }
    }

    private fun hideBubble() {
        if (isViewAdded && bubbleView != null) {
            try {
                windowManager?.removeView(bubbleView)
                isViewAdded = false
                isBubbleHidden = true
                updateNotification(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createDismissZoneView() {
        if (dismissZoneView != null) return
        val context = this
        val size = dpToPx(80f, context)
        
        val view = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FF0000.toInt()) // subtle reddish glow
                setStroke(dpToPx(2f, context), 0xFFFF3366.toInt()) // neon red/pink border
            }
            
            val xText = TextView(context).apply {
                text = "✕"
                textSize = 28f
                setTextColor(0xFFFF3366.toInt())
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(xText)
        }
        dismissZoneView = view
    }

    private fun showDismissZone() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (isDismissZoneAdded) return
        createDismissZoneView()
        dismissZoneView?.let { view ->
            val size = dpToPx(80f, this)
            val params = WindowManager.LayoutParams(
                size,
                size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dpToPx(50f, this@FloatingLauncherService)
            }
            try {
                windowManager?.addView(view, params)
                isDismissZoneAdded = true
                view.scaleX = 0f
                view.scaleY = 0f
                view.animate().scaleX(1.2f).scaleY(1.2f).setDuration(180).withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideDismissZone() {
        if (!isDismissZoneAdded) return
        dismissZoneView?.let { view ->
            try {
                view.animate().scaleX(0f).scaleY(0f).setDuration(150).withEndAction {
                    try {
                        windowManager?.removeView(view)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            } catch (e: Exception) {
                try {
                    windowManager?.removeView(view)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
            isDismissZoneAdded = false
        }
    }

    private fun isOverlappingDismissZone(bubbleX: Int, bubbleY: Int): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        val sizeDp = ThemePreferences.getBubbleSize(this)
        val sizePx = dpToPx(sizeDp.toFloat(), this)
        val bubbleCenterX = bubbleX + sizePx / 2
        val bubbleCenterY = bubbleY + sizePx / 2
        
        val zoneSize = dpToPx(80f, this)
        val zoneYMargin = dpToPx(50f, this)
        
        val zoneCenterX = screenWidth / 2
        val zoneCenterY = screenHeight - zoneYMargin - (zoneSize / 2)
        
        val distance = hypot((bubbleCenterX - zoneCenterX).toFloat(), (bubbleCenterY - zoneCenterY).toFloat())
        return distance < dpToPx(100f, this)
    }

    private fun addFloatingBubble() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (bubbleView != null) return

        val context = this
        val sizeDp = ThemePreferences.getBubbleSize(context)
        val size = dpToPx(sizeDp.toFloat(), context)
        val currentTheme = ThemePreferences.getSelectedTheme(context)
        val accentColorInt = currentTheme.colorValue.toInt()
        val symbolKey = ThemePreferences.getBubbleSymbol(context)
        val drawableRes = ThemePreferences.getBubbleIconDrawableRes(symbolKey)
        val glowIntensity = ThemePreferences.getGlowIntensity(context)
        val opacity = ThemePreferences.getBubbleOpacity(context)
        val borderStrokeDp = ThemePreferences.getBorderStrokeDp(context).toFloat()
        val density = resources.displayMetrics.density

        val bgDrawable = BubbleBackgroundDrawable(accentColorInt, glowIntensity, borderStrokeDp, density)

        val view = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = bgDrawable
            alpha = opacity.coerceIn(0.1f, 1.0f)
        }

        val innerTint = ThemePreferences.getInnerTintColor(context)

        val imageView = ImageView(context).apply {
            val customBitmap = if (symbolKey == "custom") ThemePreferences.loadCustomBubbleBitmap(context) else null
            if (customBitmap != null) {
                setImageBitmap(customBitmap)
            } else {
                setImageResource(drawableRes)
            }
            if (innerTint != 0L && customBitmap == null) {
                setColorFilter(innerTint.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
            } else {
                clearColorFilter()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = (0.3f + (0.7f * glowIntensity)).coerceIn(0.3f, 1.0f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        bubbleImageView = imageView
        view.addView(imageView)
        bubbleView = view

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 350
        }
        bubbleParams = params

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0f
        var initialY = 0f
        var initialParamsX = 0
        var initialParamsY = 0
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    initialParamsX = params.x
                    initialParamsY = params.y
                    isDragging = false
                    springX?.cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialX
                    val dy = event.rawY - initialY
                    if (!isDragging && hypot(dx, dy) > touchSlop) {
                        isDragging = true
                        showDismissZone()
                    }
                    if (isDragging) {
                        params.x = (initialParamsX + dx).toInt()
                        params.y = (initialParamsY + dy).toInt()
                        
                        val displayMetrics = context.resources.displayMetrics
                        val screenHeight = displayMetrics.heightPixels
                        if (params.y < 0) params.y = 0
                        if (params.y > screenHeight - size) params.y = screenHeight - size

                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            // ignore
                        }

                        // Check overlapping dismiss zone
                        val isOverlapping = isOverlappingDismissZone(params.x, params.y)
                        dismissZoneView?.let { zone ->
                            val targetScale = if (isOverlapping) 1.25f else 1.0f
                            if (zone.scaleX != targetScale) {
                                zone.animate().scaleX(targetScale).scaleY(targetScale).setDuration(120).start()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        hideDismissZone()
                        
                        val isOverlapping = isOverlappingDismissZone(params.x, params.y)
                        if (isOverlapping) {
                            hideBubble()
                        } else {
                            // Snap horizontally with Spring Force!
                            val displayMetrics = context.resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val centerX = params.x + size / 2
                            val targetX = if (centerX < screenWidth / 2) {
                                0
                            } else {
                                screenWidth - size
                            }

                            springX?.cancel()
                            val xHolder = FloatValueHolder(params.x.toFloat())
                            springX = SpringAnimation(xHolder).apply {
                                spring = SpringForce().apply {
                                    dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                                    stiffness = SpringForce.STIFFNESS_LOW
                                    finalPosition = targetX.toFloat()
                                }
                                addUpdateListener { _, value, _ ->
                                    params.x = value.toInt()
                                    try {
                                        windowManager?.updateViewLayout(view, params)
                                    } catch (e: Exception) {
                                        // ignore
                                    }
                                }
                            }
                            springX?.start()
                        }
                    } else {
                        // Tapped! Open launchpad
                        val launchIntent = Intent(context, OverlayActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(launchIntent)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(view, params)
            isViewAdded = true
        } catch (e: Exception) {
            e.printStackTrace()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    this@FloatingLauncherService,
                    "Unable to display overlay. Please check 'Display over other apps' permission.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel 1: persistent bubble service
            val bubbleChannel = NotificationChannel(
                CHANNEL_ID,
                "Orbit Service Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating quick launch bubble active over other apps."
            }
            manager.createNotificationChannel(bubbleChannel)

            // Channel 2: screen capture in progress (slightly higher importance so the
            // user is reliably informed whenever OCR is reading their screen).
            val captureChannel = NotificationChannel(
                CAPTURE_CHANNEL_ID,
                "Orbit Screen Capture",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shown briefly while Orbit is reading text from your screen."
            }
            manager.createNotificationChannel(captureChannel)
        }
    }

    private fun buildNotification(isBackgroundMode: Boolean = false): Notification {
        val showIntent = Intent(this, FloatingLauncherService::class.java).apply {
            action = ACTION_SHOW_BUBBLE
        }
        val pendingIntent = PendingIntent.getService(
            this,
            1,
            showIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val deletePendingIntent = com.example.shortcut.NotificationDismissReceiver.createLauncherDeleteIntent(this)
        val runShortsPendingIntent = com.example.shortcut.NotificationDismissReceiver.createRunShortsIntent(this)

        val title = getString(R.string.app_name)
        val text = if (isBackgroundMode) {
            getString(R.string.orbit_bg_running)
        } else {
            getString(R.string.orbit_launcher_active)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_dialer)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .addAction(
                android.R.drawable.ic_media_play,
                getString(R.string.action_run_shorts),
                runShortsPendingIntent
            )
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Foreground notification shown WHILE a screen capture + OCR pass is in progress.
     * Uses [CAPTURE_CHANNEL_ID] and is cancelled once the capture flow completes and the
     * service reverts to the persistent-bubble specialUse notification.
     */
    private fun buildCaptureNotification(): Notification {
        return NotificationCompat.Builder(this, CAPTURE_CHANNEL_ID)
            .setContentTitle(getString(R.string.orbit_reading_screen))
            .setContentText(getString(R.string.orbit_ocr_desc))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(isBackgroundMode: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isBackgroundMode))
    }


    private fun dpToPx(dp: Float, context: Context): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning.value = false
        instance = null

        // Stop any active MediaProjection so we don't leak a VirtualDisplay.
        try {
            ScreenCaptureManager.stopProjection()
        } catch (t: Throwable) {
            Log.w(TAG, "ScreenCaptureManager.stopProjection threw on service destroy", t)
        }

        // Unregister screen state listener dynamically to prevent memory leaks and battery drainage
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            screenReceiver = null
        }

        // Unregister package alterations listener dynamically to prevent memory leaks and battery drainage
        packageReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            packageReceiver = null
        }

        // Cancel the background preloader scope immediately
        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Safely cancel any running UI/snap transitions
        snapAnimator?.cancel()
        snapAnimator = null

        // Remove floating view safely from WindowManager and garbage collect all references
        bubbleView?.let { view ->
            try {
                if (isViewAdded) {
                    windowManager?.removeViewImmediate(view)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bubbleView = null
        bubbleImageView = null
        windowManager = null
        isViewAdded = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Settings.canDrawOverlays(this)) {
            try {
                val restartServiceIntent = Intent(applicationContext, FloatingLauncherService::class.java).also {
                    it.setPackage(packageName)
                }
                val restartServicePendingIntent = PendingIntent.getService(
                    this,
                    1,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmService = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                alarmService?.set(
                    android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 1000,
                    restartServicePendingIntent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling service restart in onTaskRemoved", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
