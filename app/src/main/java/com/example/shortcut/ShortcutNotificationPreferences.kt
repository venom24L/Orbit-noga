package com.example.shortcut

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import java.util.UUID

/**
 * Manages user preferences for multiple custom persistent shortcut notifications.
 */
object ShortcutNotificationPreferences {
    private const val PREFS_NAME = "orbit_shortcut_notification_prefs"
    private const val TAG = "ShortcutNotificationPrefs"

    val shortcutsUpdateEvent = MutableSharedFlow<String>(extraBufferCapacity = 32)

    const val KEY_SHORTCUTS_LIST = "shortcut_notifications_list_json"
    const val KEY_ENABLED = "shortcut_notif_enabled"
    const val KEY_ONGOING = "shortcut_notif_ongoing"
    const val KEY_PACKAGE_NAME = "shortcut_notif_package"
    const val KEY_APP_NAME = "shortcut_notif_app_name"
    const val KEY_TITLE = "shortcut_notif_title"
    const val KEY_BODY = "shortcut_notif_body"
    const val KEY_ICON_TYPE = "shortcut_notif_icon_type"

    const val ICON_TYPE_APP = "app"
    const val ICON_TYPE_ORBIT = "orbit"
    const val ICON_TYPE_MINIMAL = "minimal"

    const val DEFAULT_SHORTCUT_ID = "default_shortcut"
    const val BASE_NOTIFICATION_ID = 2002

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves all saved custom shortcut configurations.
     * Automatically migrates legacy single-shortcut preference if present.
     */
    fun getAllShortcuts(context: Context): List<ShortcutItem> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_SHORTCUTS_LIST, null)

        if (jsonString != null && jsonString.isNotBlank()) {
            try {
                val array = JSONArray(jsonString)
                val list = mutableListOf<ShortcutItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(ShortcutItem.fromJson(obj))
                }
                // Purge any phantom/ghost items that have no package configured
                val validShortcuts = list.filter { it.packageName.isNotBlank() }
                if (validShortcuts.size != list.size) {
                    saveAllShortcutsList(context, validShortcuts)
                }
                return validShortcuts
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse shortcuts list json", e)
            }
        }

        // Migration from legacy single-shortcut prefs only if valid legacy shortcut was configured
        val legacyPackage = prefs.getString(KEY_PACKAGE_NAME, "") ?: ""
        val legacyAppName = prefs.getString(KEY_APP_NAME, "") ?: ""
        val legacyTitle = prefs.getString(KEY_TITLE, "") ?: ""
        val legacyBody = prefs.getString(KEY_BODY, "") ?: ""
        val legacyIconType = prefs.getString(KEY_ICON_TYPE, ICON_TYPE_APP) ?: ICON_TYPE_APP
        val legacyEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val legacyOngoing = prefs.getBoolean(KEY_ONGOING, true)

        if (legacyPackage.isNotBlank()) {
            val defaultItem = ShortcutItem(
                id = DEFAULT_SHORTCUT_ID,
                notificationId = BASE_NOTIFICATION_ID,
                isEnabled = legacyEnabled,
                isOngoing = legacyOngoing,
                packageName = legacyPackage,
                appName = legacyAppName,
                title = legacyTitle,
                body = legacyBody,
                iconType = legacyIconType
            )

            val initialList = listOf(defaultItem)
            saveAllShortcutsList(context, initialList)
            return initialList
        }

        // Clean slate: return empty list without persisting phantom defaults
        return emptyList()
    }

    /**
     * Saves the entire list of shortcut items to SharedPreferences.
     */
    private fun saveAllShortcutsList(context: Context, items: List<ShortcutItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(item.toJson())
        }
        getPrefs(context).edit().putString(KEY_SHORTCUTS_LIST, array.toString()).apply()

        // Also sync primary/first shortcut to legacy keys for backward compatibility
        val primary = items.firstOrNull()
        if (primary != null) {
            getPrefs(context).edit()
                .putBoolean(KEY_ENABLED, primary.isEnabled)
                .putBoolean(KEY_ONGOING, primary.isOngoing)
                .putString(KEY_PACKAGE_NAME, primary.packageName)
                .putString(KEY_APP_NAME, primary.appName)
                .putString(KEY_TITLE, primary.title)
                .putString(KEY_BODY, primary.body)
                .putString(KEY_ICON_TYPE, primary.iconType)
                .apply()
        }
    }

    fun getShortcutById(context: Context, id: String): ShortcutItem? {
        return getAllShortcuts(context).find { it.id == id }
    }

    fun saveShortcut(context: Context, item: ShortcutItem) {
        // Never save an empty ghost shortcut without a target app
        if (item.packageName.isBlank()) {
            Log.w(TAG, "Refusing to save empty shortcut without target app")
            return
        }
        val current = getAllShortcuts(context).toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
        } else {
            current.add(item)
        }
        saveAllShortcutsList(context, current)
        shortcutsUpdateEvent.tryEmit(item.id)
    }

    fun setShortcutEnabled(context: Context, id: String, enabled: Boolean) {
        val current = getAllShortcuts(context).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(isEnabled = enabled)
            saveAllShortcutsList(context, current)
            shortcutsUpdateEvent.tryEmit(id)
        }
    }

    fun deleteShortcut(context: Context, id: String): Boolean {
        val current = getAllShortcuts(context).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current.removeAt(index)
            saveAllShortcutsList(context, current)
            shortcutsUpdateEvent.tryEmit(id)
            return true
        }
        return false
    }

    fun getNextNotificationId(context: Context): Int {
        val shortcuts = getAllShortcuts(context)
        val maxId = shortcuts.maxOfOrNull { it.notificationId } ?: (BASE_NOTIFICATION_ID - 1)
        return maxOf(BASE_NOTIFICATION_ID, maxId + 1)
    }

    fun createNewShortcut(context: Context, pkg: String = "", appName: String = ""): ShortcutItem {
        val nextId = getNextNotificationId(context)
        return ShortcutItem(
            id = UUID.randomUUID().toString(),
            notificationId = nextId,
            isEnabled = true,
            isOngoing = true,
            packageName = pkg,
            appName = appName,
            title = if (appName.isNotBlank()) context.getString(com.example.R.string.shortcut_open_app_format, appName) else "",
            body = context.getString(com.example.R.string.shortcut_tap_to_launch),
            iconType = ICON_TYPE_APP,
            createdAt = System.currentTimeMillis()
        )
    }

    // ==========================================
    // Legacy Bridge Methods (Maintains 100% compatibility)
    // ==========================================

    fun isEnabled(context: Context): Boolean {
        return getAllShortcuts(context).any { it.isEnabled }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val shortcuts = getAllShortcuts(context).toMutableList()
        if (shortcuts.isNotEmpty()) {
            shortcuts[0] = shortcuts[0].copy(isEnabled = enabled)
            saveAllShortcutsList(context, shortcuts)
        }
    }

    fun isOngoing(context: Context): Boolean {
        return getAllShortcuts(context).firstOrNull()?.isOngoing ?: true
    }

    fun getTargetPackage(context: Context): String {
        return getAllShortcuts(context).firstOrNull()?.packageName ?: ""
    }

    fun getTargetAppName(context: Context): String {
        return getAllShortcuts(context).firstOrNull()?.appName ?: ""
    }

    fun setTargetApp(context: Context, packageName: String, appName: String) {
        val shortcuts = getAllShortcuts(context).toMutableList()
        if (shortcuts.isNotEmpty()) {
            shortcuts[0] = shortcuts[0].copy(packageName = packageName, appName = appName)
            saveAllShortcutsList(context, shortcuts)
        }
    }

    fun setTitle(context: Context, title: String) {
        val shortcuts = getAllShortcuts(context).toMutableList()
        if (shortcuts.isNotEmpty()) {
            shortcuts[0] = shortcuts[0].copy(title = title)
            saveAllShortcutsList(context, shortcuts)
        }
    }

    fun setBody(context: Context, body: String) {
        val shortcuts = getAllShortcuts(context).toMutableList()
        if (shortcuts.isNotEmpty()) {
            shortcuts[0] = shortcuts[0].copy(body = body)
            saveAllShortcutsList(context, shortcuts)
        }
    }

    fun getTitle(context: Context): String {
        val primary = getAllShortcuts(context).firstOrNull()
        if (primary != null) {
            return primary.displayTitle(context)
        }
        return context.getString(com.example.R.string.shortcut_open_app_default)
    }

    fun getRawTitle(context: Context): String {
        return getAllShortcuts(context).firstOrNull()?.title ?: ""
    }

    fun getBody(context: Context): String {
        val primary = getAllShortcuts(context).firstOrNull()
        if (primary != null) {
            return primary.displayBody(context)
        }
        return context.getString(com.example.R.string.shortcut_tap_to_launch)
    }

    fun getRawBody(context: Context): String {
        return getAllShortcuts(context).firstOrNull()?.body ?: ""
    }

    fun getIconType(context: Context): String {
        return getAllShortcuts(context).firstOrNull()?.iconType ?: ICON_TYPE_APP
    }

    fun saveAll(
        context: Context,
        enabled: Boolean,
        ongoing: Boolean,
        pkg: String,
        appName: String,
        title: String,
        body: String,
        iconType: String
    ) {
        val shortcuts = getAllShortcuts(context).toMutableList()
        if (shortcuts.isNotEmpty()) {
            val primary = shortcuts[0]
            shortcuts[0] = primary.copy(
                isEnabled = enabled,
                isOngoing = ongoing,
                packageName = pkg,
                appName = appName,
                title = title,
                body = body,
                iconType = iconType
            )
            saveAllShortcutsList(context, shortcuts)
        } else {
            val newShortcut = ShortcutItem(
                id = DEFAULT_SHORTCUT_ID,
                notificationId = BASE_NOTIFICATION_ID,
                isEnabled = enabled,
                isOngoing = ongoing,
                packageName = pkg,
                appName = appName,
                title = title,
                body = body,
                iconType = iconType
            )
            saveAllShortcutsList(context, listOf(newShortcut))
        }
    }
}
