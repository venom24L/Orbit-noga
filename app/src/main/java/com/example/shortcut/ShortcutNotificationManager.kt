package com.example.shortcut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Controller for building, posting, and synchronizing system-compliant Orbit shortcut notifications.
 */
object ShortcutNotificationManager {
    private const val TAG = "ShortcutNotifMgr"
    const val CHANNEL_ID = "orbit_shortcut_notification_channel"
    const val NOTIFICATION_ID = 2002
    const val SUMMARY_NOTIFICATION_ID = 2000
    const val GROUP_KEY_SHORTCUTS = "com.example.orbit.SHORTCUTS"

    // Default Orbit brand accent color (Vibrant Coral / Neon-Orange)
    const val DEFAULT_ORBIT_COLOR = 0xFFFF6B35.toInt()

    const val ACTION_START_OR_UPDATE = "com.example.shortcut.ACTION_START_OR_UPDATE"
    const val ACTION_STOP = "com.example.shortcut.ACTION_STOP"
    const val EXTRA_OPEN_SHORTCUT_CONFIG = "extra_open_shortcut_config"
    const val EXTRA_SHORTCUT_APP_NOT_FOUND = "extra_shortcut_app_not_found"

    // In-memory color cache keyed by target package to avoid redundant palette extraction
    private val colorCache = ConcurrentHashMap<String, Int>()

    fun init(context: Context) {
        createNotificationChannel(context)
        if (ShortcutNotificationPreferences.isEnabled(context)) {
            syncServiceState(context)
        }
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.shortcut_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.shortcut_notif_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Asynchronously extracts the vibrant or dominant accent color from a bitmap using androidx.palette.
     * Guaranteed to execute off the main thread on Dispatchers.Default.
     */
    suspend fun extractAccentColor(
        bitmap: Bitmap?,
        cacheKey: String? = null,
        defaultColor: Int = DEFAULT_ORBIT_COLOR
    ): Int = withContext(Dispatchers.Default) {
        if (cacheKey != null && colorCache.containsKey(cacheKey)) {
            return@withContext colorCache[cacheKey]!!
        }
        if (bitmap == null || bitmap.isRecycled) return@withContext defaultColor
        val color = try {
            val palette = Palette.from(bitmap).generate()
            palette.vibrantSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.getDominantColor(defaultColor)
        } catch (e: Exception) {
            Log.w(TAG, "Palette generation failed for $cacheKey", e)
            defaultColor
        }
        if (cacheKey != null && cacheKey.isNotBlank()) {
            colorCache[cacheKey] = color
        }
        color
    }

    /**
     * Synchronous fallback for color extraction (checks cache or runs local palette generation).
     */
    fun extractAccentColorSync(
        bitmap: Bitmap?,
        cacheKey: String? = null,
        defaultColor: Int = DEFAULT_ORBIT_COLOR
    ): Int {
        if (cacheKey != null && colorCache.containsKey(cacheKey)) {
            return colorCache[cacheKey]!!
        }
        if (bitmap == null || bitmap.isRecycled) return defaultColor
        val color = try {
            val palette = Palette.from(bitmap).generate()
            palette.vibrantSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.getDominantColor(defaultColor)
        } catch (e: Exception) {
            defaultColor
        }
        if (cacheKey != null && cacheKey.isNotBlank()) {
            colorCache[cacheKey] = color
        }
        return color
    }

    /**
     * Resolves the large icon bitmap based on the user's icon selection.
     */
    fun resolveLargeIcon(context: Context, iconType: String, targetPackage: String): Bitmap? {
        return try {
            when (iconType) {
                ShortcutNotificationPreferences.ICON_TYPE_APP -> {
                    if (targetPackage.isNotBlank()) {
                        val drawable = context.packageManager.getApplicationIcon(targetPackage)
                        drawableToBitmap(drawable, 128)
                    } else {
                        getOrbitIconBitmap(context)
                    }
                }
                ShortcutNotificationPreferences.ICON_TYPE_ORBIT -> {
                    getOrbitIconBitmap(context)
                }
                ShortcutNotificationPreferences.ICON_TYPE_MINIMAL -> {
                    getMinimalGlyphBitmap(context)
                }
                else -> getOrbitIconBitmap(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve large icon for $iconType", e)
            getOrbitIconBitmap(context)
        }
    }

    /**
     * Builds a standard, system-compliant notification for an individual ShortcutItem.
     *
     * 1. Large Icon: Target app's own launcher icon.
     * 2. Accent Color: Extracted dominant/vibrant color from the target app icon via Palette (setColor + setColorized).
     * 3. Small Icon: Monochrome transparent Orbit silhouette (R.drawable.ic_orbit_small_monochrome).
     * 4. Content: "Open [App Name]" and "Tap to launch" dynamically generated per shortcut.
     * 5. Action Buttons: "Open" (launches app) and "Remove" (removes/mutes shortcut).
     * 6. Configurable isOngoing: Allows persistent pinned or swipeable dismissable mode.
     * 7. Grouping: Groups notifications with GROUP_KEY_SHORTCUTS.
     */
    fun buildNotification(
        context: Context,
        item: ShortcutItem,
        overrideAccentColor: Int? = null
    ): Notification {
        createNotificationChannel(context)

        val targetPackage = item.packageName
        val title = item.displayTitle(context)
        val body = item.displayBody(context)
        val iconType = item.iconType
        val isOngoing = item.isOngoing

        // Launch Intent specific to this shortcut
        val pm = context.packageManager
        val launchIntent = if (targetPackage.isNotBlank()) {
            try {
                pm.getLaunchIntentForPackage(targetPackage)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting launch intent for $targetPackage", e)
                null
            }
        } else null

        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                item.notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            // Fallback: Opens Orbit with a helpful warning if app is disabled or uninstalled
            val fallbackIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SHORTCUT_APP_NOT_FOUND, targetPackage)
            }
            PendingIntent.getActivity(
                context,
                item.notificationId,
                fallbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val largeIconBitmap = resolveLargeIcon(context, iconType, targetPackage)

        // Palette accent color extraction
        val accentColor = overrideAccentColor
            ?: extractAccentColorSync(largeIconBitmap, cacheKey = targetPackage)

        val deletePendingIntent = NotificationDismissReceiver.createShortcutDeleteIntent(context, item)
        val removePendingIntent = NotificationDismissReceiver.createRemoveShortcutIntent(context, item)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_orbit_small_monochrome)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .setColor(accentColor)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_KEY_SHORTCUTS)
            .setShowWhen(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

        if (largeIconBitmap != null) {
            builder.setLargeIcon(largeIconBitmap)
        }

        // Action 1: Open app
        builder.addAction(0, context.getString(R.string.shortcut_action_open), pendingIntent)

        // Action 2: Remove shortcut
        builder.addAction(0, context.getString(R.string.shortcut_action_remove), removePendingIntent)

        val notification = builder.build()
        if (isOngoing) {
            notification.flags = notification.flags or
                NotificationCompat.FLAG_NO_CLEAR or
                NotificationCompat.FLAG_ONGOING_EVENT
        }

        return notification
    }

    /**
     * Builds the primary shortcut notification (compatibility wrapper).
     */
    fun buildNotification(context: Context): Notification {
        val primary = ShortcutNotificationPreferences.getAllShortcuts(context).firstOrNull()
            ?: ShortcutItem(id = ShortcutNotificationPreferences.DEFAULT_SHORTCUT_ID, notificationId = NOTIFICATION_ID)
        return buildNotification(context, primary)
    }

    /**
     * Builds the group summary notification when multiple shortcuts are active.
     */
    fun buildSummaryNotification(context: Context, activeCount: Int): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SHORTCUT_CONFIG, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orbit_small_monochrome)
            .setContentTitle(context.getString(R.string.shortcut_notif_title_screen))
            .setContentText(context.getString(R.string.shortcut_summary_active_count, activeCount))
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_SHORTCUTS)
            .setGroupSummary(true)
            .setColor(DEFAULT_ORBIT_COLOR)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun getOrbitIconBitmap(context: Context): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?: ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
            drawable?.let { drawableToBitmap(it, 128) }
        } catch (e: Exception) {
            null
        }
    }

    private fun getMinimalGlyphBitmap(context: Context): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_bubble_atom_core)
            drawable?.let { drawableToBitmap(it, 128, tintColor = DEFAULT_ORBIT_COLOR) }
        } catch (e: Exception) {
            null
        }
    }

    fun drawableToBitmap(drawable: Drawable, size: Int = 128, tintColor: Int? = null): Bitmap {
        if (drawable is BitmapDrawable && tintColor == null) {
            val original = drawable.bitmap
            if (original != null && !original.isRecycled) {
                return Bitmap.createScaledBitmap(original, size, size, true)
            }
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else size
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else size

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        if (tintColor != null) {
            drawable.setTint(tintColor)
        }
        drawable.draw(canvas)

        return if (width != size || height != size) {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        } else {
            bitmap
        }
    }

    /**
     * Synchronizes all active shortcut notifications with the system notification shade.
     * Palette color extraction and notification building execute asynchronously off the main thread.
     */
    fun syncServiceState(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            syncServiceStateInternal(context)
        }
    }

    private suspend fun syncServiceStateInternal(context: Context) {
        val allShortcuts = ShortcutNotificationPreferences.getAllShortcuts(context)
        val enabledShortcuts = allShortcuts.filter { it.isEnabled && it.packageName.isNotBlank() }
        val serviceIntent = Intent(context, ShortcutNotificationService::class.java)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        if (enabledShortcuts.isNotEmpty()) {
            serviceIntent.action = ACTION_START_OR_UPDATE
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ShortcutNotificationService", e)
            }

            // Immediately post or update each enabled notification
            for (shortcut in enabledShortcuts) {
                try {
                    val largeIcon = resolveLargeIcon(context, shortcut.iconType, shortcut.packageName)
                    val accentColor = extractAccentColor(largeIcon, cacheKey = shortcut.packageName)
                    val notif = buildNotification(context, shortcut, overrideAccentColor = accentColor)
                    notificationManager?.notify(shortcut.notificationId, notif)
                } catch (e: Exception) {
                    Log.w(TAG, "Direct notify failed for ${shortcut.id}: ${e.message}")
                }
            }

            // If multiple shortcuts are enabled, manage the group summary notification
            if (enabledShortcuts.size > 1) {
                try {
                    val summaryNotif = buildSummaryNotification(context, enabledShortcuts.size)
                    notificationManager?.notify(SUMMARY_NOTIFICATION_ID, summaryNotif)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to post summary notification: ${e.message}")
                }
            } else {
                try {
                    notificationManager?.cancel(SUMMARY_NOTIFICATION_ID)
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Cancel any muted or incomplete shortcuts
            for (shortcut in allShortcuts.filter { !it.isEnabled || it.packageName.isBlank() }) {
                try {
                    notificationManager?.cancel(shortcut.notificationId)
                } catch (e: Exception) {
                    Log.w(TAG, "Cancel notification failed for ${shortcut.id}: ${e.message}")
                }
            }
        } else {
            serviceIntent.action = ACTION_STOP
            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping ShortcutNotificationService", e)
            }

            // Cancel summary and all individual notifications
            try {
                notificationManager?.cancel(SUMMARY_NOTIFICATION_ID)
            } catch (e: Exception) {
                // Ignore
            }

            for (shortcut in allShortcuts) {
                try {
                    notificationManager?.cancel(shortcut.notificationId)
                } catch (e: Exception) {
                    Log.w(TAG, "Cancel notification failed: ${e.message}")
                }
            }
            try {
                notificationManager?.cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Instantly respawns a shortcut notification when swiped away by the user from the status bar
     * if the shortcut is configured as persistent/ongoing.
     */
    fun respawnShortcutImmediately(context: Context, shortcutId: String, notificationId: Int) {
        val shortcut = ShortcutNotificationPreferences.getShortcutById(context, shortcutId)
        if (shortcut != null && shortcut.isEnabled && shortcut.isOngoing && shortcut.packageName.isNotBlank()) {
            try {
                val notif = buildNotification(context, shortcut)
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.notify(shortcut.notificationId, notif)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to immediately respawn shortcut $shortcutId", e)
            }
        }
    }
}
