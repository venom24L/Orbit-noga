package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun WifiMonitorSettingsDialog(
    context: Context,
    onDismiss: () -> Unit,
    onConfigChanged: () -> Unit = {}
) {
    val signalOrange = Color(0xFFFF6B35)
    val neonCyan = Color(0xFF00F0FF)
    val emeraldGreen = Color(0xFF00E676)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF8E95AA)

    var currentLimitMb by remember { mutableStateOf(WifiMonitorPreferences.getDailyLimitMb(context)) }
    var currentDisplayMode by remember { mutableStateOf(WifiMonitorPreferences.getDisplayMode(context)) }
    var isPingEnabled by remember { mutableStateOf(WifiMonitorPreferences.isPingEnabled(context)) }
    var isAdaptiveEnabled by remember { mutableStateOf(WifiMonitorPreferences.isAdaptiveRefreshEnabled(context)) }
    var isSpeedUnitBits by remember { mutableStateOf(WifiMonitorPreferences.isSpeedUnitBits(context)) }
    val hasUsagePerm = remember { WifiMonitorManager.hasUsageStatsPermission(context) }

    var customInputText by remember {
        mutableStateOf(if (currentLimitMb > 0f) currentLimitMb.toInt().toString() else "")
    }

    val limitPresets = listOf(
        0f to stringResource(id = R.string.limit_unlimited),
        500f to "500 MB",
        1024f to "1 GB",
        2048f to "2 GB",
        5120f to "5 GB",
        10240f to "10 GB"
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101626)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(neonCyan.copy(alpha = 0.15f))
                                .border(1.dp, neonCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = neonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(id = R.string.wifi_monitor_title),
                                color = inkLight,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "COCKPIT CONFIGURATION",
                                color = neonCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = inkDim,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // SECTION 1: Daily WiFi Data Limit
                Text(
                    text = stringResource(id = R.string.daily_limit_title),
                    color = inkLight,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(id = R.string.daily_limit_desc),
                    color = inkDim,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Limit presets grid
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rows = limitPresets.chunked(3)
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { (mb, label) ->
                                val isSelected = currentLimitMb == mb
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) signalOrange.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f))
                                        .border(
                                            1.dp,
                                            if (isSelected) signalOrange else Color.White.copy(alpha = 0.08f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            currentLimitMb = mb
                                            customInputText = if (mb > 0f) mb.toInt().toString() else ""
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) signalOrange else inkLight,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Custom limit text field
                OutlinedTextField(
                    value = customInputText,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }
                        customInputText = filtered
                        val mbVal = filtered.toFloatOrNull() ?: 0f
                        currentLimitMb = mbVal
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(id = R.string.limit_custom), color = inkDim, fontSize = 11.sp) },
                    placeholder = { Text(stringResource(id = R.string.wifi_limit_placeholder), color = inkDim.copy(alpha = 0.5f), fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = inkLight,
                        unfocusedTextColor = inkLight,
                        focusedBorderColor = signalOrange,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        cursorColor = signalOrange
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 2: Display Customization
                Text(
                    text = stringResource(id = R.string.display_customization_title),
                    color = inkLight,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(id = R.string.display_customization_desc),
                    color = inkDim,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                listOf(
                    WifiMonitorPreferences.DISPLAY_ALL to stringResource(id = R.string.display_mode_all),
                    WifiMonitorPreferences.DISPLAY_SPEED_ONLY to stringResource(id = R.string.display_mode_speed),
                    WifiMonitorPreferences.DISPLAY_USAGE_ONLY to stringResource(id = R.string.display_mode_usage)
                ).forEach { (modeKey, modeTitle) ->
                    val isSelected = currentDisplayMode == modeKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) neonCyan.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f))
                            .border(
                                1.dp,
                                if (isSelected) neonCyan.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { currentDisplayMode = modeKey }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = modeTitle,
                            color = if (isSelected) neonCyan else inkLight,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        RadioButton(
                            selected = isSelected,
                            onClick = { currentDisplayMode = modeKey },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = neonCyan,
                                unselectedColor = inkDim
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(modifier = Modifier.height(14.dp))

                // SECTION 3: Speed Display Unit (Bytes vs Bits)
                Text(
                    text = stringResource(id = R.string.speed_unit_title),
                    color = inkLight,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(id = R.string.speed_unit_desc),
                    color = inkDim,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isBytesSelected = !isSpeedUnitBits
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isBytesSelected) neonCyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
                            .border(
                                1.dp,
                                if (isBytesSelected) neonCyan else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { isSpeedUnitBits = false }
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "MB/s",
                                color = if (isBytesSelected) neonCyan else inkLight,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "MegaBytes",
                                color = inkDim,
                                fontSize = 9.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSpeedUnitBits) neonCyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
                            .border(
                                1.dp,
                                if (isSpeedUnitBits) neonCyan else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { isSpeedUnitBits = true }
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Mbps",
                                color = if (isSpeedUnitBits) neonCyan else inkLight,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Megabits",
                                color = inkDim,
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(modifier = Modifier.height(14.dp))

                // SECTION 4: Smart Adaptive Refresh & Ping Switches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.adaptive_refresh_title),
                            color = inkLight,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(id = R.string.adaptive_refresh_desc),
                            color = inkDim,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isAdaptiveEnabled,
                        onCheckedChange = { isAdaptiveEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = neonCyan,
                            uncheckedThumbColor = inkDim,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.ping_label),
                            color = inkLight,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Real-time latency testing in ms",
                            color = inkDim,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isPingEnabled,
                        onCheckedChange = { isPingEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = signalOrange,
                            uncheckedThumbColor = inkDim,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(modifier = Modifier.height(14.dp))

                // SECTION 5: Android OS System Usage Access Sync
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (hasUsagePerm) emeraldGreen.copy(alpha = 0.10f) else neonCyan.copy(alpha = 0.10f))
                        .border(
                            1.dp,
                            if (hasUsagePerm) emeraldGreen.copy(alpha = 0.35f) else neonCyan.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (hasUsagePerm) Icons.Default.CheckCircle else Icons.Default.Sync,
                                contentDescription = null,
                                tint = if (hasUsagePerm) emeraldGreen else neonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(id = R.string.wifi_os_sync_title),
                                color = inkLight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (hasUsagePerm) {
                                "Connected to Android NetworkStatsManager for verified system-level accuracy."
                            } else {
                                stringResource(id = R.string.wifi_os_sync_desc)
                            },
                            color = inkDim,
                            fontSize = 10.sp
                        )
                    }

                    if (!hasUsagePerm) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Throwable) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    } catch (_: Throwable) {}
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = neonCyan, contentColor = Color(0xFF0B101D)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(text = stringResource(id = R.string.wifi_grant_permission), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Save Action Button
                Button(
                    onClick = {
                        WifiMonitorPreferences.setDailyLimitMb(context, currentLimitMb)
                        WifiMonitorPreferences.setDisplayMode(context, currentDisplayMode)
                        WifiMonitorPreferences.setPingEnabled(context, isPingEnabled)
                        WifiMonitorPreferences.setAdaptiveRefreshEnabled(context, isAdaptiveEnabled)
                        WifiMonitorPreferences.setSpeedUnitBits(context, isSpeedUnitBits)
                        onConfigChanged()
                        Toast.makeText(context, "Wi-Fi Monitor settings updated!", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = neonCyan,
                        contentColor = Color(0xFF0B101D)
                    )
                ) {
                    Text(
                        text = "SAVE CONFIGURATION",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
