package com.example.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.AppCache
import com.example.AppInfo
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PreviewDisplayMode {
    COLLAPSED,
    EXPANDED
}

/**
 * Screen for managing multiple custom shortcut notifications and configuring individual shortcuts.
 * Features a standard, system-compliant notification architecture:
 * 1. Target app's own launcher icon as setLargeIcon().
 * 2. Accent color extraction via Palette (setColor + setColorized).
 * 3. Orbit monochrome small icon.
 * 4. Dynamic title/body content.
 * 5. Action buttons (Open, Remove).
 * 6. Configurable ongoing persistence.
 * 7. System notification shade grouping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutNotificationScreen(
    onDismiss: (() -> Unit)? = null,
    onRequestNotificationPermission: () -> Unit,
    isNotificationPermissionGranted: Boolean,
    isEmbedded: Boolean = false
) {
    val context = LocalContext.current
    val containerBg = Color(0xFF0D1220)

    // Load all saved shortcuts from persistence
    var allShortcuts by remember { mutableStateOf(ShortcutNotificationPreferences.getAllShortcuts(context)) }

    // Currently editing shortcut: null means we are viewing the list of all shortcuts
    var editingShortcut by remember { mutableStateOf<ShortcutItem?>(null) }

    // Dialog state for deleting a shortcut
    var shortcutToDelete by remember { mutableStateOf<ShortcutItem?>(null) }

    // Cache of installed apps
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val apps = AppCache.getApps(context)
            withContext(Dispatchers.Main) {
                installedApps = apps
            }
        }
    }

    fun refreshShortcuts() {
        allShortcuts = ShortcutNotificationPreferences.getAllShortcuts(context)
    }

    LaunchedEffect(Unit) {
        ShortcutNotificationPreferences.shortcutsUpdateEvent.collect {
            refreshShortcuts()
        }
    }

    Box(
        modifier = if (isEmbedded) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxSize()
                .background(containerBg)
                .statusBarsPadding()
                .navigationBarsPadding()
        }
    ) {
        if (editingShortcut == null) {
            ShortcutsListContent(
                shortcuts = allShortcuts,
                installedApps = installedApps,
                isNotificationPermissionGranted = isNotificationPermissionGranted,
                onRequestNotificationPermission = onRequestNotificationPermission,
                isEmbedded = isEmbedded,
                onDismiss = onDismiss,
                onAddNewShortcut = {
                    val newShortcut = ShortcutNotificationPreferences.createNewShortcut(context)
                    editingShortcut = newShortcut
                },
                onEditShortcut = { shortcut ->
                    editingShortcut = shortcut
                },
                onToggleShortcut = { shortcut, enabled ->
                    if (enabled) {
                        if (!isNotificationPermissionGranted) {
                            onRequestNotificationPermission()
                            Toast.makeText(
                                context,
                                context.getString(R.string.permission_notification_required),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@ShortcutsListContent
                        }
                    }
                    ShortcutNotificationPreferences.setShortcutEnabled(context, shortcut.id, enabled)
                    ShortcutNotificationManager.syncServiceState(context)
                    refreshShortcuts()
                    val message = if (enabled) {
                        context.getString(R.string.shortcut_notif_active_status)
                    } else {
                        context.getString(R.string.shortcut_notif_muted_status)
                    }
                    Toast.makeText(context, "${shortcut.displayTitle(context)}: $message", Toast.LENGTH_SHORT).show()
                },
                onDeleteShortcut = { shortcut ->
                    shortcutToDelete = shortcut
                }
            )
        } else {
            ShortcutEditorContent(
                initialItem = editingShortcut!!,
                installedApps = installedApps,
                isNotificationPermissionGranted = isNotificationPermissionGranted,
                onRequestNotificationPermission = onRequestNotificationPermission,
                isEmbedded = isEmbedded,
                onBackToList = {
                    editingShortcut = null
                    refreshShortcuts()
                },
                onSave = { savedItem ->
                    ShortcutNotificationPreferences.saveShortcut(context, savedItem)
                    ShortcutNotificationManager.syncServiceState(context)
                    refreshShortcuts()
                    Toast.makeText(
                        context,
                        context.getString(R.string.shortcut_notif_saved_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                    editingShortcut = null
                },
                onDelete = { itemToDelete ->
                    ShortcutNotificationPreferences.deleteShortcut(context, itemToDelete.id)
                    ShortcutNotificationManager.syncServiceState(context)
                    refreshShortcuts()
                    Toast.makeText(context, context.getString(R.string.shortcut_notif_deleted_toast), Toast.LENGTH_SHORT).show()
                    editingShortcut = null
                }
            )
        }

        // Confirmation dialog for deleting a shortcut
        if (shortcutToDelete != null) {
            val item = shortcutToDelete!!
            AlertDialog(
                onDismissRequest = { shortcutToDelete = null },
                title = {
                    Text(
                        text = stringResource(R.string.shortcut_notif_delete_confirm_title),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.shortcut_notif_delete_confirm_desc),
                        color = Color(0xFFEEF0F6)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            ShortcutNotificationPreferences.deleteShortcut(context, item.id)
                            ShortcutNotificationManager.syncServiceState(context)
                            refreshShortcuts()
                            shortcutToDelete = null
                            Toast.makeText(context, context.getString(R.string.shortcut_notif_deleted_toast), Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    ) {
                        Text(stringResource(R.string.shortcut_notif_delete), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { shortcutToDelete = null }) {
                        Text(stringResource(R.string.cancel), color = Color(0xFF5A6178))
                    }
                },
                containerColor = Color(0xFF151D33),
                shape = RoundedCornerShape(18.dp)
            )
        }
    }
}

/**
 * List overview of all created shortcut notifications.
 */
@Composable
private fun ShortcutsListContent(
    shortcuts: List<ShortcutItem>,
    installedApps: List<AppInfo>,
    isNotificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    isEmbedded: Boolean,
    onDismiss: (() -> Unit)?,
    onAddNewShortcut: () -> Unit,
    onEditShortcut: (ShortcutItem) -> Unit,
    onToggleShortcut: (ShortcutItem, Boolean) -> Unit,
    onDeleteShortcut: (ShortcutItem) -> Unit
) {
    val scrollState = rememberScrollState()
    val signalOrange = Color(0xFFFF6B35)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF5A6178)
    val cardBg = Color(0xFF151D33)

    val validShortcuts = shortcuts.filter { it.packageName.isNotBlank() }
    val activeCount = validShortcuts.count { it.isEnabled }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = if (isEmbedded) 0.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!isEmbedded) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.shortcut_notif_track),
                        color = signalOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.shortcut_notif_manage_title),
                        color = inkLight,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (onDismiss != null) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear),
                            tint = inkLight
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // Notification Permission Warning Card
        if (!isNotificationPermissionGranted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRequestNotificationPermission() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1B12)),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(signalOrange.copy(alpha = 0.6f), signalOrange.copy(alpha = 0.2f))))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(signalOrange.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = signalOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.shortcut_notif_permission_required_title),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.shortcut_notif_permission_required_desc),
                            color = Color(0xFFD4BBA5),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = signalOrange
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // Overview Summary & Add Button Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.shortcut_notif_section_header),
                    color = inkDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (activeCount > 0) signalOrange.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.shortcut_notif_count_summary, activeCount, validShortcuts.size),
                        color = if (activeCount > 0) signalOrange else inkDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = onAddNewShortcut,
                colors = ButtonDefaults.buttonColors(containerColor = signalOrange),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.shortcut_notif_add_btn),
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (validShortcuts.isEmpty()) {
            // Empty State
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(signalOrange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = null,
                            tint = signalOrange,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.shortcut_notif_empty_title),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.shortcut_notif_empty_desc),
                        color = inkDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = onAddNewShortcut,
                        colors = ButtonDefaults.buttonColors(containerColor = signalOrange),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.shortcut_notif_btn_create_first),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // List of created shortcut cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                validShortcuts.forEach { shortcut ->
                    val appInfo = remember(shortcut.packageName, installedApps) {
                        installedApps.find { it.packageName == shortcut.packageName }
                    }

                    ShortcutItemCard(
                        shortcut = shortcut,
                        appInfo = appInfo,
                        accentColor = signalOrange,
                        isNotificationPermissionGranted = isNotificationPermissionGranted,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onToggle = { enabled -> onToggleShortcut(shortcut, enabled) },
                        onEdit = { onEditShortcut(shortcut) },
                        onDelete = { onDeleteShortcut(shortcut) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))
    }
}

/**
 * Individual card representation in the Shortcuts list.
 */
@Composable
private fun ShortcutItemCard(
    shortcut: ShortcutItem,
    appInfo: AppInfo?,
    accentColor: Color,
    isNotificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val inkDim = Color(0xFF5A6178)
    val cardBg = Color(0xFF151D33)
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onEdit() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                if (shortcut.isEnabled) listOf(accentColor.copy(alpha = 0.4f), Color.White.copy(alpha = 0.08f))
                else listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))
            )
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Icon Slot
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (shortcut.isEnabled) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (shortcut.isEnabled) accentColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (appInfo?.iconBitmap != null) {
                        Image(
                            bitmap = appInfo.iconBitmap,
                            contentDescription = shortcut.appName,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = if (shortcut.isEnabled) accentColor else inkDim,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Titles & Subtitles
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = shortcut.displayTitle(context),
                            color = if (shortcut.isEnabled) Color.White else inkDim,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Notification ID tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "#${shortcut.notificationId}",
                                color = inkDim,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = shortcut.displayBody(context),
                        color = inkDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Switch
                Switch(
                    checked = shortcut.isEnabled && isNotificationPermissionGranted,
                    enabled = isNotificationPermissionGranted,
                    onCheckedChange = { checked ->
                        if (!isNotificationPermissionGranted) {
                            onRequestNotificationPermission()
                            Toast.makeText(
                                context,
                                context.getString(R.string.permission_notification_required),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            onToggle(checked)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accentColor,
                        uncheckedThumbColor = inkDim,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                    )
                )
            }

            // Bottom action strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.02f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.shortcut_notif_palette_accent),
                            color = inkDim,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.shortcut_notif_edit),
                            color = accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.shortcut_notif_delete),
                            tint = Color(0xFFFF5252).copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Detailed Configurator & Live Preview view for a single shortcut item.
 */
@Composable
private fun ShortcutEditorContent(
    initialItem: ShortcutItem,
    installedApps: List<AppInfo>,
    isNotificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    isEmbedded: Boolean,
    onBackToList: () -> Unit,
    onSave: (ShortcutItem) -> Unit,
    onDelete: (ShortcutItem) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val signalOrange = Color(0xFFFF6B35)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF5A6178)
    val cardBg = Color(0xFF151D33)

    var isEnabled by remember { mutableStateOf(initialItem.isEnabled) }
    val isOngoing = true
    var targetPackage by remember { mutableStateOf(initialItem.packageName) }
    var targetAppName by remember { mutableStateOf(initialItem.appName) }
    var customTitle by remember { mutableStateOf(initialItem.title) }
    var customBody by remember { mutableStateOf(initialItem.body) }
    var selectedIconType by remember { mutableStateOf(initialItem.iconType) }

    // Synchronize enabled state if notification permission is revoked
    LaunchedEffect(isNotificationPermissionGranted) {
        if (!isNotificationPermissionGranted && isEnabled) {
            isEnabled = false
        }
    }

    // Listen for external shortcut updates (e.g. removed via notification shade action)
    LaunchedEffect(initialItem.id) {
        ShortcutNotificationPreferences.shortcutsUpdateEvent.collect { changedId ->
            if (changedId == initialItem.id || changedId == "ALL") {
                val updated = ShortcutNotificationPreferences.getShortcutById(context, initialItem.id)
                if (updated != null) {
                    isEnabled = updated.isEnabled
                }
            }
        }
    }

    var previewDisplayMode by remember { mutableStateOf(PreviewDisplayMode.EXPANDED) }
    var showAppPicker by remember { mutableStateOf(false) }

    val currentAppInfo = remember(targetPackage, installedApps) {
        installedApps.find { it.packageName == targetPackage }
    }

    fun getCurrentSnapshot(): ShortcutItem {
        val effectiveEnabled = isEnabled && isNotificationPermissionGranted
        return initialItem.copy(
            isEnabled = effectiveEnabled,
            isOngoing = isOngoing,
            packageName = targetPackage,
            appName = targetAppName,
            title = customTitle,
            body = customBody,
            iconType = selectedIconType
        )
    }

    val attemptSave = {
        if (targetPackage.isBlank()) {
            Toast.makeText(context, context.getString(R.string.shortcut_notif_toast_select_first), Toast.LENGTH_SHORT).show()
            showAppPicker = true
        } else {
            onSave(getCurrentSnapshot())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = if (isEmbedded) 0.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Navigation & Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackToList,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.shortcut_notif_back_to_list),
                    tint = inkLight
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shortcut_notif_config_header, initialItem.notificationId),
                    color = signalOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = if (targetAppName.isNotBlank()) targetAppName else stringResource(R.string.shortcut_notif_title_screen),
                    color = inkLight,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Quick Top Save Button
            Button(
                onClick = attemptSave,
                colors = ButtonDefaults.buttonColors(containerColor = signalOrange),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(stringResource(R.string.save), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 1: Activation Controls Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Enable Notification Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isNotificationPermissionGranted) {
                            onRequestNotificationPermission()
                            Toast.makeText(
                                context,
                                context.getString(R.string.permission_notification_required),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isEnabled && isNotificationPermissionGranted) signalOrange.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = if (isEnabled && isNotificationPermissionGranted) signalOrange else inkDim,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.shortcut_notif_enable_toggle),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.shortcut_notif_enable_desc),
                            color = inkDim,
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = isEnabled && isNotificationPermissionGranted,
                        enabled = isNotificationPermissionGranted,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (!isNotificationPermissionGranted) {
                                    onRequestNotificationPermission()
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.permission_notification_required),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    isEnabled = false
                                } else {
                                    isEnabled = true
                                }
                            } else {
                                isEnabled = false
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = signalOrange,
                            uncheckedThumbColor = inkDim,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 2: Live Notification Preview Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.shortcut_notif_live_preview_header),
                    color = signalOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.shortcut_notif_palette_colorized),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Mode Selector: Collapsed vs Expanded
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (previewDisplayMode == PreviewDisplayMode.COLLAPSED) signalOrange else Color.Transparent)
                        .clickable { previewDisplayMode = PreviewDisplayMode.COLLAPSED }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.shortcut_notif_mode_collapsed),
                        color = if (previewDisplayMode == PreviewDisplayMode.COLLAPSED) Color.White else inkDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (previewDisplayMode == PreviewDisplayMode.EXPANDED) signalOrange else Color.Transparent)
                        .clickable { previewDisplayMode = PreviewDisplayMode.EXPANDED }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.shortcut_notif_mode_expanded),
                        color = if (previewDisplayMode == PreviewDisplayMode.EXPANDED) Color.White else inkDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Standard System Notification Preview Card
        NotificationLivePreviewCard(
            targetAppName = targetAppName,
            title = if (customTitle.isNotBlank()) customTitle else (if (targetAppName.isNotBlank()) stringResource(R.string.shortcut_open_app_format, targetAppName) else stringResource(R.string.shortcut_open_app_default)),
            body = if (customBody.isNotBlank()) customBody else stringResource(R.string.shortcut_tap_to_launch),
            iconType = selectedIconType,
            isOngoing = isOngoing,
            currentAppInfo = currentAppInfo,
            accentColor = signalOrange,
            displayMode = previewDisplayMode,
            onRemoveClick = {
                isEnabled = false
                ShortcutNotificationPreferences.setShortcutEnabled(context, initialItem.id, false)
                ShortcutNotificationManager.syncServiceState(context)
                Toast.makeText(
                    context,
                    context.getString(R.string.shortcut_notif_muted_status),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Section 3: Target Application Selector
        Text(
            text = stringResource(R.string.shortcut_notif_target_app_header),
            color = signalOrange,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAppPicker = true },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(signalOrange.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentAppInfo?.iconBitmap != null) {
                        Image(
                            bitmap = currentAppInfo.iconBitmap,
                            contentDescription = currentAppInfo.label,
                            modifier = Modifier.size(34.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = signalOrange,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (targetAppName.isNotBlank()) targetAppName else stringResource(R.string.shortcut_notif_no_app_selected),
                        color = Color.White,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (targetPackage.isNotBlank()) targetPackage else stringResource(R.string.shortcut_notif_tap_to_choose_app),
                        color = inkDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = { showAppPicker = true },
                    colors = ButtonDefaults.buttonColors(containerColor = signalOrange),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.shortcut_notif_choose_app_btn),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Section 4: Text Customization
        Text(
            text = stringResource(R.string.shortcut_notif_text_header),
            color = signalOrange,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Notification Title
                Text(
                    text = stringResource(R.string.shortcut_notif_title_field),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it },
                    placeholder = {
                        Text(
                            text = if (targetAppName.isNotBlank()) stringResource(R.string.shortcut_open_app_format, targetAppName) else stringResource(R.string.shortcut_open_app_default),
                            color = inkDim
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (customTitle.isNotBlank()) {
                            IconButton(onClick = { customTitle = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear),
                                    tint = inkDim,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = signalOrange,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = signalOrange
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Notification Body
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.shortcut_notif_body_field),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.optional),
                        color = inkDim,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customBody,
                    onValueChange = { customBody = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.shortcut_tap_to_launch),
                            color = inkDim
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (customBody.isNotBlank()) {
                            IconButton(onClick = { customBody = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear),
                                    tint = inkDim,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = signalOrange,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = signalOrange
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        stringResource(R.string.shortcut_preset_tap_to_launch),
                        stringResource(R.string.shortcut_preset_quick_launch),
                        stringResource(R.string.shortcut_preset_open_now)
                    ).forEach { preset ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (customBody == preset) signalOrange.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f))
                                .border(
                                    1.dp,
                                    if (customBody == preset) signalOrange else Color.White.copy(alpha = 0.08f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { customBody = preset }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = preset,
                                color = if (customBody == preset) signalOrange else inkDim,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Section 5: Icon Selection
        Text(
            text = stringResource(R.string.shortcut_notif_icon_header),
            color = signalOrange,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Option 1: Native App Icon
            IconOptionCard(
                title = stringResource(R.string.shortcut_notif_icon_app),
                subtitle = stringResource(R.string.shortcut_notif_icon_app_desc),
                isSelected = selectedIconType == ShortcutNotificationPreferences.ICON_TYPE_APP,
                accentColor = signalOrange,
                modifier = Modifier.weight(1f),
                onClick = { selectedIconType = ShortcutNotificationPreferences.ICON_TYPE_APP }
            ) {
                if (currentAppInfo?.iconBitmap != null) {
                    Image(
                        bitmap = currentAppInfo.iconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = signalOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Option 2: Orbit Icon
            IconOptionCard(
                title = stringResource(R.string.shortcut_notif_icon_orbit),
                subtitle = stringResource(R.string.shortcut_notif_icon_orbit_desc),
                isSelected = selectedIconType == ShortcutNotificationPreferences.ICON_TYPE_ORBIT,
                accentColor = signalOrange,
                modifier = Modifier.weight(1f),
                onClick = { selectedIconType = ShortcutNotificationPreferences.ICON_TYPE_ORBIT }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_bubble_atom_core),
                    contentDescription = null,
                    tint = signalOrange,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Option 3: Minimal Glyph
            IconOptionCard(
                title = stringResource(R.string.shortcut_notif_icon_minimal),
                subtitle = stringResource(R.string.shortcut_notif_icon_minimal_desc),
                isSelected = selectedIconType == ShortcutNotificationPreferences.ICON_TYPE_MINIMAL,
                accentColor = signalOrange,
                modifier = Modifier.weight(1f),
                onClick = { selectedIconType = ShortcutNotificationPreferences.ICON_TYPE_MINIMAL }
            ) {
                Icon(
                    imageVector = Icons.Default.Launch,
                    contentDescription = null,
                    tint = signalOrange,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Section 6: Primary Save Button
        Button(
            onClick = attemptSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = signalOrange),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Save,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.shortcut_notif_save_btn),
                color = Color.White,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Delete Shortcut button
        OutlinedButton(
            onClick = { onDelete(initialItem) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.35f))
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.shortcut_notif_delete),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(40.dp))
    }

    // App Picker Dialog
    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            selectedPackage = targetPackage,
            onDismiss = { showAppPicker = false },
            onSelectApp = { app ->
                targetPackage = app.packageName
                targetAppName = app.label
                if (customTitle.isBlank() || customTitle.startsWith("Open ") || customTitle.startsWith("فتح ") || customTitle.startsWith("باز کردن ")) {
                    customTitle = context.getString(R.string.shortcut_open_app_format, app.label)
                }
                showAppPicker = false
                Toast.makeText(context, context.getString(R.string.search_engine_selected_toast, app.label), Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/**
 * High-fidelity representation of the Android System Notification Shade.
 * - Standard Android notification styling with Large Icon on right.
 * - Dynamic Palette accent color extracted from the target app icon.
 * - Monochrome Orbit small icon in the notification header.
 * - Action buttons ("OPEN" and "REMOVE").
 */
@Composable
fun NotificationLivePreviewCard(
    targetAppName: String,
    title: String,
    body: String,
    iconType: String,
    isOngoing: Boolean,
    currentAppInfo: AppInfo?,
    accentColor: Color,
    displayMode: PreviewDisplayMode = PreviewDisplayMode.EXPANDED,
    onRemoveClick: (() -> Unit)? = null
) {
    val cardBg = Color(0xFF1B202E)
    val textColor = Color(0xFFF1F3F9)
    val textDim = Color(0xFF949CB2)
    val displayBody = if (body.isNotBlank()) body else stringResource(R.string.shortcut_tap_to_launch)

    // Asynchronously extract dominant/vibrant accent color from app icon using Palette
    val dynamicPaletteColor by produceState<Color>(initialValue = accentColor, key1 = currentAppInfo?.packageName) {
        if (currentAppInfo?.iconBitmap != null) {
            val extractedRgb = withContext(Dispatchers.Default) {
                ShortcutNotificationManager.extractAccentColorSync(
                    currentAppInfo.iconBitmap.asAndroidBitmap(),
                    cacheKey = currentAppInfo.packageName,
                    defaultColor = ShortcutNotificationManager.DEFAULT_ORBIT_COLOR
                )
            }
            value = Color(extractedRgb)
        } else {
            value = accentColor
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    dynamicPaletteColor.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.06f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // 1. Android System Notification Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Monochrome small icon container
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(dynamicPaletteColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_orbit_small_monochrome),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }

                Spacer(modifier = Modifier.width(7.dp))

                Text(
                    text = "Orbit",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.shortcut_notif_now),
                    color = textDim,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                if (isOngoing) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(R.string.shortcut_notif_persistent),
                        tint = dynamicPaletteColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Icon(
                    imageVector = if (displayMode == PreviewDisplayMode.EXPANDED) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = textDim,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Content Row: Title, Text on Left | Target App Large Icon on Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = textColor,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = displayBody,
                        color = textDim,
                        fontSize = 13.sp,
                        maxLines = if (displayMode == PreviewDisplayMode.EXPANDED) 3 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Target App Large Icon
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(dynamicPaletteColor.copy(alpha = 0.15f))
                        .border(1.dp, dynamicPaletteColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    when (iconType) {
                        ShortcutNotificationPreferences.ICON_TYPE_APP -> {
                            if (currentAppInfo?.iconBitmap != null) {
                                Image(
                                    bitmap = currentAppInfo.iconBitmap,
                                    contentDescription = currentAppInfo.label,
                                    modifier = Modifier.size(34.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Apps,
                                    contentDescription = null,
                                    tint = dynamicPaletteColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        ShortcutNotificationPreferences.ICON_TYPE_ORBIT -> {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_bubble_atom_core),
                                contentDescription = null,
                                tint = dynamicPaletteColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        ShortcutNotificationPreferences.ICON_TYPE_MINIMAL -> {
                            Icon(
                                imageVector = Icons.Default.Launch,
                                contentDescription = null,
                                tint = dynamicPaletteColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = null,
                                tint = dynamicPaletteColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // 3. Expanded View Action Buttons
            if (displayMode == PreviewDisplayMode.EXPANDED) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Action 1: Open
                    Button(
                        onClick = { },
                        colors = ButtonDefaults.buttonColors(containerColor = dynamicPaletteColor.copy(alpha = 0.25f)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Launch,
                            contentDescription = null,
                            tint = dynamicPaletteColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.shortcut_action_open),
                            color = dynamicPaletteColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Action 2: Remove
                    OutlinedButton(
                        onClick = { onRemoveClick?.invoke() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textDim),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = textDim,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.shortcut_action_remove),
                            color = textDim,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun IconOptionCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) accentColor.copy(alpha = 0.15f) else Color(0xFF151D33))
            .border(
                1.dp,
                if (isSelected) accentColor else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) accentColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                iconContent()
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                color = if (isSelected) Color.White else Color(0xFF8E94A8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFF5A6178),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Search-enabled modal dialog for selecting any installed application.
 */
@Composable
fun AppPickerDialog(
    apps: List<AppInfo>,
    selectedPackage: String,
    onDismiss: () -> Unit,
    onSelectApp: (AppInfo) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(searchQuery, apps) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val signalOrange = Color(0xFFFF6B35)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF5A6178)
    val cardBg = Color(0xFF151D33)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))))
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                    // Dialog Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.shortcut_notif_select_app_title),
                                color = inkLight,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.shortcut_notif_apps_count, apps.size),
                                color = inkDim,
                                fontSize = 12.sp
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.clear),
                                tint = inkLight
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Search Field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(text = stringResource(R.string.shortcut_notif_search_apps_hint), color = inkDim) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = signalOrange
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.clear),
                                        tint = inkDim
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = signalOrange,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = signalOrange
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // App List
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isSelected = app.packageName == selectedPackage
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) signalOrange.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.02f))
                                    .border(
                                        1.dp,
                                        if (isSelected) signalOrange else Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSelectApp(app) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (app.iconBitmap != null) {
                                    Image(
                                        bitmap = app.iconBitmap,
                                        contentDescription = app.label,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(signalOrange.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Apps,
                                            contentDescription = null,
                                            tint = signalOrange,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        color = Color.White,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = app.packageName,
                                        color = inkDim,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = signalOrange,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
