package com.example.shortcut

import android.content.Context
import com.example.R
import org.json.JSONObject
import java.util.UUID

/**
 * Represents a single custom shortcut notification.
 */
data class ShortcutItem(
    val id: String = UUID.randomUUID().toString(),
    val notificationId: Int = 2002,
    val isEnabled: Boolean = true,
    val isOngoing: Boolean = true,
    val packageName: String = "",
    val appName: String = "",
    val title: String = "",
    val body: String = "",
    val iconType: String = ShortcutNotificationPreferences.ICON_TYPE_APP,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun displayTitle(context: Context? = null): String {
        if (title.isNotBlank()) return title
        if (appName.isNotBlank()) {
            return context?.getString(R.string.shortcut_open_app_format, appName) ?: "Open $appName"
        }
        return context?.getString(R.string.shortcut_open_app_default) ?: "Open App"
    }

    fun displayBody(context: Context? = null): String {
        if (body.isNotBlank()) return body
        return context?.getString(R.string.shortcut_tap_to_launch) ?: "Tap to launch"
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("notificationId", notificationId)
            put("isEnabled", isEnabled)
            put("isOngoing", isOngoing)
            put("packageName", packageName)
            put("appName", appName)
            put("title", title)
            put("body", body)
            put("iconType", iconType)
            put("createdAt", createdAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ShortcutItem {
            return ShortcutItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                notificationId = json.optInt("notificationId", 2002),
                isEnabled = json.optBoolean("isEnabled", true),
                isOngoing = json.optBoolean("isOngoing", true),
                packageName = json.optString("packageName", ""),
                appName = json.optString("appName", ""),
                title = json.optString("title", ""),
                body = json.optString("body", ""),
                iconType = json.optString("iconType", ShortcutNotificationPreferences.ICON_TYPE_APP),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
