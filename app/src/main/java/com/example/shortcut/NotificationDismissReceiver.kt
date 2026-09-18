package com.example.shortcut

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.example.FloatingLauncherService
import com.example.MainActivity
import com.example.R

/**
 * BroadcastReceiver triggered immediately when any Orbit notification is swiped or dismissed
 * by the user from the Android notification shade.
 *
 * Using a direct BroadcastReceiver ensures sub-millisecond, synchronous re-spawning
 * without being delayed by Android's background service execution limits.
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifDismissReceiver"

        const val ACTION_LAUNCHER_DISMISSED = "com.example.ACTION_LAUNCHER_DISMISSED"
        const val ACTION_SHORTCUT_DISMISSED = "com.example.shortcut.ACTION_SHORTCUT_DISMISSED"
        const val ACTION_REMOVE_SHORTCUT = "com.example.shortcut.ACTION_REMOVE_SHORTCUT"
        const val ACTION_RUN_ALL_SHORTCUTS = "com.example.shortcut.ACTION_RUN_ALL_SHORTCUTS"

        const val EXTRA_SHORTCUT_ID = "extra_shortcut_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        /**
         * Creates an action PendingIntent for the "Run Shorts" button in Orbit's main notification.
         * Restores and re-displays all configured shortcuts into the notification shade.
         */
        fun createRunShortsIntent(context: Context): PendingIntent {
            val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_RUN_ALL_SHORTCUTS
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 7002, intent, flags)
        }

        /**
         * Creates an instant delete PendingIntent for the persistent Floating Launcher notification.
         */
        fun createLauncherDeleteIntent(context: Context): PendingIntent {
            val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_LAUNCHER_DISMISSED
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 7001, intent, flags)
        }

        /**
         * Creates an instant delete PendingIntent for an individual shortcut notification.
         */
        fun createShortcutDeleteIntent(context: Context, item: ShortcutItem): PendingIntent {
            val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_SHORTCUT_DISMISSED
                putExtra(EXTRA_SHORTCUT_ID, item.id)
                putExtra(EXTRA_NOTIFICATION_ID, item.notificationId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // Unique requestCode per shortcut notification so they don't overwrite each other
            return PendingIntent.getBroadcast(context, item.notificationId + 80000, intent, flags)
        }

        /**
         * Creates an action PendingIntent to remove or mute an active shortcut notification.
         */
        fun createRemoveShortcutIntent(context: Context, item: ShortcutItem): PendingIntent {
            val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_REMOVE_SHORTCUT
                putExtra(EXTRA_SHORTCUT_ID, item.id)
                putExtra(EXTRA_NOTIFICATION_ID, item.notificationId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, item.notificationId + 90000, intent, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Notification dismissed: action=$action")

        when (action) {
            ACTION_LAUNCHER_DISMISSED -> {
                // Instantly respawn the floating launcher notification
                try {
                    FloatingLauncherService.repostNotificationImmediately(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to immediately respawn launcher notification", e)
                }
            }
            ACTION_SHORTCUT_DISMISSED -> {
                val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID) ?: return
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                // Only respawn if the shortcut is configured as ongoing / pinned
                val shortcut = ShortcutNotificationPreferences.getShortcutById(context, shortcutId)
                if (shortcut != null && shortcut.isOngoing) {
                    try {
                        ShortcutNotificationManager.respawnShortcutImmediately(context, shortcutId, notificationId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to immediately respawn shortcut notification $shortcutId", e)
                    }
                }
            }
            ACTION_REMOVE_SHORTCUT -> {
                val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID) ?: return
                Log.d(TAG, "User requested removal of shortcut $shortcutId via notification action")
                try {
                    val shortcut = ShortcutNotificationPreferences.getShortcutById(context, shortcutId)
                    if (shortcut != null) {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        nm?.cancel(shortcut.notificationId)
                    }
                    ShortcutNotificationPreferences.setShortcutEnabled(context, shortcutId, false)
                    ShortcutNotificationManager.syncServiceState(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove shortcut $shortcutId", e)
                }
            }
            ACTION_RUN_ALL_SHORTCUTS -> {
                Log.d(TAG, "User requested to run/restore all shortcuts from Orbit notification")
                try {
                    val count = ShortcutNotificationPreferences.enableAllShortcuts(context)
                    if (count > 0) {
                        ShortcutNotificationManager.syncServiceState(context)
                        Toast.makeText(context, context.getString(R.string.shortcuts_restored_all), Toast.LENGTH_SHORT).show()
                    } else {
                        val openIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(ShortcutNotificationManager.EXTRA_OPEN_SHORTCUT_CONFIG, true)
                        }
                        context.startActivity(openIntent)
                        Toast.makeText(context, context.getString(R.string.shortcut_notif_empty_title), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to run/restore shortcuts", e)
                }
            }
        }
    }
}
