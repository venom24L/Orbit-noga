package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

@Composable
fun WifiMonitorCockpitContent(
    context: Context,
    accentColor: Color,
    onOpenSettings: () -> Unit,
    configVersion: Int = 0,
    modifier: Modifier = Modifier
) {
    var metrics by remember { mutableStateOf(WifiTelemetryMetrics()) }
    var showMonthlyTracker by remember { mutableStateOf(false) }

    val signalOrange = Color(0xFFFF6B35)
    val neonCyan = Color(0xFF00F0FF)
    val trackBlue = Color(0xFF3D9BFF)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF8E95AA)
    val emeraldGreen = Color(0xFF00E676)
    val alertPink = Color(0xFFFF2A85)

    // Dynamic polling with adaptive refresh rate & instant reaction to settings changes
    LaunchedEffect(configVersion) {
        metrics = WifiMonitorManager.sampleWifiMetrics(context)
        while (isActive) {
            val interval = if (!WifiMonitorPreferences.isAdaptiveRefreshEnabled(context)) {
                1000L
            } else {
                metrics.adaptiveIntervalMs
            }
            delay(interval)
            metrics = WifiMonitorManager.sampleWifiMetrics(context)
        }
    }

    val displayMode = remember(configVersion) { WifiMonitorPreferences.getDisplayMode(context) }
    val isPingEnabledPref = remember(configVersion) { WifiMonitorPreferences.isPingEnabled(context) }

    // Pulsing animations for active indicators and alerts
    val infiniteTransition = rememberInfiniteTransition(label = "WifiPulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (metrics.isHighTraffic) 600 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wifiPulseAlpha"
    )
    val alertPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alertPulse"
    )

    // Gauge fraction based on download speed (logarithmic mapping for responsive visual sweep)
    val speedBps = metrics.downloadSpeedBps.toDouble()
    val speedFraction = when {
        speedBps <= 0.0 -> 0.02f
        speedBps < 100 * 1024 -> ((speedBps / (100.0 * 1024)) * 0.25).toFloat()
        speedBps < 5 * 1024 * 1024 -> (0.25 + (speedBps / (5.0 * 1024 * 1024)) * 0.45).toFloat()
        else -> (0.70 + (log10(speedBps / (5.0 * 1024 * 1024)) / 2.0).coerceIn(0.0, 0.30)).toFloat()
    }.coerceIn(0.02f, 1.0f)

    val animatedSpeedFraction by animateFloatAsState(
        targetValue = speedFraction,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "wifiSpeedFractionAnim"
    )

    Column(modifier = modifier.fillMaxWidth()) {

        // 1. WiFi SSID Banner & Quick Action Gear
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x0FFFFFFF))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Glowing Wi-Fi Antenna Beacon
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (metrics.isConnected) neonCyan.copy(alpha = 0.15f) else alertPink.copy(alpha = 0.15f))
                        .border(1.dp, if (metrics.isConnected) neonCyan.copy(alpha = 0.45f) else alertPink.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (metrics.isWifi) Icons.Default.Wifi else if (metrics.isConnected) Icons.Default.SignalCellularAlt else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = if (metrics.isConnected) neonCyan else alertPink,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (metrics.isConnected) emeraldGreen.copy(alpha = pulseAlpha) else alertPink)
                        )
                        Text(
                            text = stringResource(id = R.string.wifi_ssid_label).uppercase(),
                            color = neonCyan,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = metrics.ssid,
                            color = inkLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (metrics.frequencyBand.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(neonCyan.copy(alpha = 0.15f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = metrics.frequencyBand,
                                    color = neonCyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Latency / Ping Pill & Settings Gear
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isPingEnabledPref && metrics.pingMs >= 0) {
                    val pingColor = when (metrics.pingQuality) {
                        PingQuality.ULTRA_LOW -> emeraldGreen
                        PingQuality.GOOD -> neonCyan
                        PingQuality.HIGH -> signalOrange
                        PingQuality.OFFLINE -> inkDim
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(pingColor.copy(alpha = 0.12f))
                            .border(1.dp, pingColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 7.dp, vertical = 3.5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(pingColor)
                            )
                            Text(
                                text = "${metrics.pingMs} ms",
                                color = pingColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { showMonthlyTracker = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = stringResource(id = R.string.monthly_tracker_button),
                        tint = neonCyan,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Wi-Fi Monitor Settings",
                        tint = inkDim,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Hero Cockpit HUD (Speedometer for SPEED/ALL, or Dedicated Daily Usage Gauge for USAGE_ONLY)
        if (displayMode == WifiMonitorPreferences.DISPLAY_USAGE_ONLY) {
            // Dedicated Hero Daily Usage Cockpit HUD
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x0EFFFFFF))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Circular HUD Arc Gauge (Daily Usage vs Daily Limit)
                val usageFraction = if (metrics.dailyLimitMb > 0f) {
                    (metrics.todayUsageMb / metrics.dailyLimitMb).coerceIn(0.02f, 1.0f)
                } else {
                    ((metrics.todayUsageMb / 5120f).coerceIn(0.04f, 1.0f))
                }
                val animatedUsageFraction by animateFloatAsState(
                    targetValue = usageFraction,
                    animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                    label = "wifiUsageFractionAnim"
                )

                Box(
                    modifier = Modifier.size(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 8.dp.toPx()
                        val padding = strokeWidth / 2f + 4.dp.toPx()
                        val arcRadius = (size.minDimension - padding * 2f) / 2f
                        val arcCenter = Offset(size.width / 2f, size.height / 2f)

                        val totalTicks = 18
                        val startAngleDeg = 135f
                        val sweepAngleDeg = 270f
                        val activeSweepAngle = sweepAngleDeg * animatedUsageFraction

                        for (i in 0..totalTicks) {
                            val tickAngleDeg = startAngleDeg + (sweepAngleDeg * (i.toFloat() / totalTicks))
                            val tickAngleRad = Math.toRadians(tickAngleDeg.toDouble())
                            val isTickActive = (tickAngleDeg - startAngleDeg) <= activeSweepAngle

                            val outerR = arcRadius + 5.dp.toPx()
                            val innerR = arcRadius + 2.dp.toPx()

                            val startX = arcCenter.x + innerR * cos(tickAngleRad).toFloat()
                            val startY = arcCenter.y + innerR * sin(tickAngleRad).toFloat()
                            val endX = arcCenter.x + outerR * cos(tickAngleRad).toFloat()
                            val endY = arcCenter.y + outerR * sin(tickAngleRad).toFloat()

                            val tickColor = if (metrics.isLimitExceeded) alertPink else if (metrics.isNearLimit) signalOrange else neonCyan
                            drawLine(
                                color = if (isTickActive) tickColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
                                start = Offset(startX, startY),
                                end = Offset(endX, endY),
                                strokeWidth = if (i % 3 == 0) 2.dp.toPx() else 1.2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }

                        // Background Track
                        drawArc(
                            color = Color.White.copy(alpha = 0.08f),
                            startAngle = startAngleDeg,
                            sweepAngle = sweepAngleDeg,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Active Foreground Arc
                        val arcGradient = if (metrics.isLimitExceeded) {
                            Brush.sweepGradient(0.0f to alertPink, 1.0f to alertPink)
                        } else if (metrics.isNearLimit) {
                            Brush.sweepGradient(0.0f to signalOrange, 1.0f to alertPink)
                        } else {
                            Brush.sweepGradient(
                                0.0f to trackBlue,
                                0.5f to neonCyan,
                                1.0f to emeraldGreen
                            )
                        }

                        drawArc(
                            brush = arcGradient,
                            startAngle = startAngleDeg,
                            sweepAngle = activeSweepAngle,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        if (activeSweepAngle > 5f) {
                            val tipAngleRad = Math.toRadians((startAngleDeg + activeSweepAngle).toDouble())
                            val tipX = arcCenter.x + arcRadius * cos(tipAngleRad).toFloat()
                            val tipY = arcCenter.y + arcRadius * sin(tipAngleRad).toFloat()
                            drawCircle(
                                color = Color.White,
                                radius = 3.dp.toPx(),
                                center = Offset(tipX, tipY)
                            )
                            drawCircle(
                                color = if (metrics.isLimitExceeded) alertPink else neonCyan,
                                radius = 6.dp.toPx(),
                                center = Offset(tipX, tipY)
                            )
                        }
                    }

                    // Center Daily Usage Readout
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (metrics.dailyLimitMb > 0f) {
                            val percentInt = (metrics.dailyLimitPercent * 100).toInt()
                            Text(
                                text = "$percentInt%",
                                color = if (metrics.isLimitExceeded) alertPink else inkLight,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = "OF LIMIT",
                                color = if (metrics.isLimitExceeded) alertPink else neonCyan,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        } else {
                            Text(
                                text = metrics.todayUsageFormatted.substringBefore(" "),
                                color = inkLight,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = metrics.todayUsageFormatted.substringAfter(" ", "MB"),
                                color = neonCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }

                // Right Telemetry: Detailed Data Consumption Metrics
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    if (metrics.hasUsagePermission) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(emeraldGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(emeraldGreen)
                                )
                                Text(
                                    text = "OS SYNC",
                                    color = emeraldGreen,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.daily_usage_title),
                            color = inkDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(neonCyan.copy(alpha = 0.14f))
                                .border(1.dp, neonCyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .clickable { showMonthlyTracker = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = neonCyan, modifier = Modifier.size(10.dp))
                                Text(stringResource(R.string.wifi_metric_monthly), color = neonCyan, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = metrics.todayUsageFormatted,
                        color = neonCyan,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    if (metrics.dailyLimitMb > 0f) {
                        val remainingMb = (metrics.dailyLimitMb - metrics.todayUsageMb).coerceAtLeast(0f)
                        val remainingStr = if (remainingMb >= 1024f) {
                            "%.2f GB".format(remainingMb / 1024f)
                        } else {
                            "%.0f MB".format(remainingMb)
                        }
                        val limitColor = if (metrics.isLimitExceeded) alertPink else emeraldGreen
                        val limitLabel = if (metrics.isLimitExceeded) stringResource(R.string.wifi_quota_exceeded) else stringResource(R.string.wifi_quota_remaining, remainingStr)
                        Text(
                            text = limitLabel,
                            color = limitColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = stringResource(id = R.string.limit_unlimited),
                            color = emeraldGreen,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        } else {
            // Hero Cockpit HUD Speedometer (Shown for ALL and SPEED_ONLY)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x0EFFFFFF))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Circular HUD Arc Gauge (Download Speed)
                Box(
                    modifier = Modifier.size(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 8.dp.toPx()
                        val padding = strokeWidth / 2f + 4.dp.toPx()
                        val arcRadius = (size.minDimension - padding * 2f) / 2f
                        val arcCenter = Offset(size.width / 2f, size.height / 2f)

                        val totalTicks = 18
                        val startAngleDeg = 135f
                        val sweepAngleDeg = 270f
                        val activeSweepAngle = sweepAngleDeg * animatedSpeedFraction

                        // Tick marks
                        for (i in 0..totalTicks) {
                            val tickAngleDeg = startAngleDeg + (sweepAngleDeg * (i.toFloat() / totalTicks))
                            val tickAngleRad = Math.toRadians(tickAngleDeg.toDouble())
                            val isTickActive = (tickAngleDeg - startAngleDeg) <= activeSweepAngle

                            val outerR = arcRadius + 5.dp.toPx()
                            val innerR = arcRadius + 2.dp.toPx()

                            val startX = arcCenter.x + innerR * cos(tickAngleRad).toFloat()
                            val startY = arcCenter.y + innerR * sin(tickAngleRad).toFloat()
                            val endX = arcCenter.x + outerR * cos(tickAngleRad).toFloat()
                            val endY = arcCenter.y + outerR * sin(tickAngleRad).toFloat()

                            drawLine(
                                color = if (isTickActive) neonCyan.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
                                start = Offset(startX, startY),
                                end = Offset(endX, endY),
                                strokeWidth = if (i % 3 == 0) 2.dp.toPx() else 1.2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }

                        // Background Track
                        drawArc(
                            color = Color.White.copy(alpha = 0.08f),
                            startAngle = startAngleDeg,
                            sweepAngle = sweepAngleDeg,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Active Foreground Arc
                        val arcGradient = Brush.sweepGradient(
                            0.0f to trackBlue,
                            0.45f to neonCyan,
                            0.85f to emeraldGreen,
                            1.0f to trackBlue
                        )
                        drawArc(
                            brush = arcGradient,
                            startAngle = startAngleDeg,
                            sweepAngle = activeSweepAngle,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Glowing Leading Orb
                        if (activeSweepAngle > 5f) {
                            val tipAngleRad = Math.toRadians((startAngleDeg + activeSweepAngle).toDouble())
                            val tipX = arcCenter.x + arcRadius * cos(tipAngleRad).toFloat()
                            val tipY = arcCenter.y + arcRadius * sin(tipAngleRad).toFloat()
                            drawCircle(
                                color = Color.White,
                                radius = 3.dp.toPx(),
                                center = Offset(tipX, tipY)
                            )
                            drawCircle(
                                color = neonCyan.copy(alpha = 0.7f),
                                radius = 6.dp.toPx(),
                                center = Offset(tipX, tipY)
                            )
                        }
                    }

                    // Center Speed Readout
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = metrics.downloadValueStr,
                            color = inkLight,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = metrics.downloadUnitStr,
                            color = neonCyan,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                // Right Telemetry: Live Download / Upload & Multi-Segment Meter
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Download Speed Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = neonCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(id = R.string.download_speed),
                                color = inkDim,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "${metrics.downloadValueStr} ${metrics.downloadUnitStr}",
                            color = neonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Upload Speed Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = null,
                                tint = signalOrange,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(id = R.string.upload_speed),
                                color = inkDim,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "${metrics.uploadValueStr} ${metrics.uploadUnitStr}",
                            color = signalOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 14-Segment VU Activity Meter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val segmentCount = 14
                        for (idx in 0 until segmentCount) {
                            val segFrac = (idx + 1).toFloat() / segmentCount
                            val isLit = segFrac <= (animatedSpeedFraction + 0.05f)
                            val segColor = when {
                                segFrac > 0.8f -> alertPink
                                segFrac > 0.45f -> neonCyan
                                else -> trackBlue
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (isLit) segColor else Color.White.copy(alpha = 0.08f))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Adaptive Refresh Rate Chip
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (metrics.isHighTraffic) Icons.Default.Bolt else Icons.Default.BatterySaver,
                            contentDescription = null,
                            tint = if (metrics.isHighTraffic) neonCyan else emeraldGreen,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = if (metrics.isHighTraffic) {
                                stringResource(id = R.string.adaptive_refresh_live)
                            } else {
                                stringResource(id = R.string.adaptive_refresh_idle)
                            },
                            color = if (metrics.isHighTraffic) neonCyan.copy(alpha = 0.85f) else emeraldGreen.copy(alpha = 0.85f),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // 3. Avionics Metric Pods (Tailored per Display Mode)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val limitBorderColor = when {
                metrics.isLimitExceeded -> alertPink
                metrics.isNearLimit -> signalOrange
                else -> trackBlue
            }
            val pingStatColor = when (metrics.pingQuality) {
                PingQuality.ULTRA_LOW -> emeraldGreen
                PingQuality.GOOD -> neonCyan
                PingQuality.HIGH -> signalOrange
                PingQuality.OFFLINE -> inkDim
            }

            when (displayMode) {
                WifiMonitorPreferences.DISPLAY_SPEED_ONLY -> {
                    // Pod 1: Wi-Fi Link Speed
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, neonCyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(neonCyan))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Icons.Default.Speed, contentDescription = null, tint = neonCyan, modifier = Modifier.size(11.dp))
                                    Text(stringResource(R.string.wifi_metric_link_speed), color = inkDim, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(stringResource(R.string.wifi_speed_mbps_format, metrics.linkSpeedMbps), color = neonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                    listOf(6.dp, 10.dp, 8.dp, 12.dp).forEach { h ->
                                        Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(neonCyan.copy(alpha = 0.45f)))
                                    }
                                }
                            }
                        }
                    }

                    // Pod 2: Wi-Fi Frequency Band
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, trackBlue.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(trackBlue))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = trackBlue, modifier = Modifier.size(11.dp))
                                    Text(stringResource(R.string.wifi_metric_band), color = inkDim, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(if (metrics.frequencyBand.isNotEmpty()) metrics.frequencyBand else "2.4 GHz", color = trackBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                    listOf(7.dp, 9.dp, 11.dp, 8.dp).forEach { h ->
                                        Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(trackBlue.copy(alpha = 0.45f)))
                                    }
                                }
                            }
                        }
                    }

                    // Pod 3: Latency / Ping
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, pingStatColor.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(pingStatColor))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Icons.Default.Sensors, contentDescription = null, tint = pingStatColor, modifier = Modifier.size(11.dp))
                                    Text(stringResource(R.string.wifi_metric_latency), color = inkDim, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(if (metrics.pingMs >= 0) "${metrics.pingMs} ms" else stringResource(id = R.string.ping_offline), color = pingStatColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                    listOf(6.dp, 8.dp, 5.dp, 10.dp).forEach { h ->
                                        Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(pingStatColor.copy(alpha = 0.45f)))
                                    }
                                }
                            }
                        }
                    }
                }

                WifiMonitorPreferences.DISPLAY_USAGE_ONLY -> {
                    // Pod 1: Daily Limit & Alerts
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                1.dp,
                                if (metrics.isLimitExceeded) alertPink.copy(alpha = alertPulseAlpha) else limitBorderColor.copy(alpha = 0.25f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onOpenSettings() },
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(limitBorderColor))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(if (metrics.isLimitExceeded) Icons.Default.Warning else Icons.Default.Speed, contentDescription = null, tint = limitBorderColor, modifier = Modifier.size(11.dp))
                                    Text(stringResource(id = R.string.daily_limit_title), color = inkDim, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    text = if (metrics.dailyLimitMb > 0f) {
                                        val percentInt = (metrics.dailyLimitPercent * 100).toInt()
                                        stringResource(id = R.string.daily_limit_used_fmt, percentInt)
                                    } else {
                                        stringResource(id = R.string.daily_limit_none)
                                    },
                                    color = limitBorderColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (metrics.dailyLimitMb > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color.White.copy(alpha = 0.08f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(fraction = metrics.dailyLimitPercent.coerceIn(0f, 1f))
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(limitBorderColor)
                                        )
                                    }
                                } else {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                        listOf(8.dp, 6.dp, 10.dp, 7.dp).forEach { h ->
                                            Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(trackBlue.copy(alpha = 0.45f)))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Pod 2: Hardware Wi-Fi Link Speed & Band
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, neonCyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(neonCyan))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = neonCyan, modifier = Modifier.size(11.dp))
                                    Text(stringResource(R.string.wifi_metric_link_band), color = inkDim, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                val bandDisplay = if (metrics.frequencyBand.isNotEmpty()) metrics.frequencyBand else "Wi-Fi"
                                Text(stringResource(R.string.wifi_speed_band_format, metrics.linkSpeedMbps, bandDisplay), color = neonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                    listOf(6.dp, 10.dp, 8.dp, 11.dp).forEach { h ->
                                        Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(neonCyan.copy(alpha = 0.45f)))
                                    }
                                }
                            }
                        }
                    }

                    // Pod 3: Latency & Health
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, pingStatColor.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(pingStatColor))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.Sensors, contentDescription = null, tint = pingStatColor, modifier = Modifier.size(11.dp))
                                    Text(stringResource(R.string.wifi_metric_latency), color = inkDim, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(if (metrics.pingMs >= 0) "${metrics.pingMs} ms" else stringResource(id = R.string.ping_offline), color = pingStatColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                    listOf(6.dp, 8.dp, 5.dp, 10.dp).forEach { h ->
                                        Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(pingStatColor.copy(alpha = 0.45f)))
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // DISPLAY_ALL:
                    // Pod 1: Daily WiFi Usage (00:00 - Now)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, neonCyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                            .clickable { showMonthlyTracker = true },
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(neonCyan))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                if (metrics.hasUsagePermission) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(emeraldGreen.copy(alpha = 0.2f))
                                            .padding(horizontal = 4.dp, vertical = 1.5.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(3.5.dp)
                                                    .clip(CircleShape)
                                                    .background(emeraldGreen)
                                            )
                                            Text(stringResource(R.string.wifi_metric_os_sync), color = emeraldGreen, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.DataUsage, contentDescription = null, tint = neonCyan, modifier = Modifier.size(11.dp))
                                    Text(stringResource(id = R.string.daily_usage_title), color = inkDim, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(metrics.todayUsageFormatted, color = neonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                    listOf(5.dp, 9.dp, 12.dp, 7.dp).forEach { h ->
                                        Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(neonCyan.copy(alpha = 0.45f)))
                                    }
                                }
                            }
                        }
                    }

                    // Pod 2: Daily Limit & Alerts
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                1.dp,
                                if (metrics.isLimitExceeded) alertPink.copy(alpha = alertPulseAlpha) else limitBorderColor.copy(alpha = 0.25f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onOpenSettings() },
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(limitBorderColor))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(if (metrics.isLimitExceeded) Icons.Default.Warning else Icons.Default.Speed, contentDescription = null, tint = limitBorderColor, modifier = Modifier.size(11.dp))
                                    Text(stringResource(id = R.string.daily_limit_title), color = inkDim, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    text = if (metrics.dailyLimitMb > 0f) {
                                        val percentInt = (metrics.dailyLimitPercent * 100).toInt()
                                        stringResource(id = R.string.daily_limit_used_fmt, percentInt)
                                    } else {
                                        stringResource(id = R.string.daily_limit_none)
                                    },
                                    color = limitBorderColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (metrics.dailyLimitMb > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color.White.copy(alpha = 0.08f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(fraction = metrics.dailyLimitPercent.coerceIn(0f, 1f))
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(limitBorderColor)
                                        )
                                    }
                                } else {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                        listOf(8.dp, 6.dp, 10.dp, 7.dp).forEach { h ->
                                            Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(trackBlue.copy(alpha = 0.45f)))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Pod 3: Latency & Stability
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, pingStatColor.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(pingStatColor))
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.Sensors, contentDescription = null, tint = pingStatColor, modifier = Modifier.size(11.dp))
                                    Text(stringResource(R.string.wifi_metric_latency), color = inkDim, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(if (metrics.pingMs >= 0) "${metrics.pingMs} ms" else stringResource(id = R.string.ping_offline), color = pingStatColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                    listOf(6.dp, 8.dp, 5.dp, 10.dp).forEach { h ->
                                        Box(modifier = Modifier.weight(1f).height(h).clip(RoundedCornerShape(1.dp)).background(pingStatColor.copy(alpha = 0.45f)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3.5. OS Data Usage Sync Reminder Banner (if not yet granted)
        if (!metrics.hasUsagePermission && displayMode != WifiMonitorPreferences.DISPLAY_SPEED_ONLY) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(neonCyan.copy(alpha = 0.08f))
                    .border(1.dp, neonCyan.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .clickable {
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
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = neonCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.wifi_usage_sync_banner),
                        color = inkLight,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = stringResource(id = R.string.wifi_usage_sync_action),
                    color = neonCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 4. Limit Alert Banners (Animated pulse when approaching or exceeded)
        AnimatedVisibility(
            visible = (metrics.isLimitExceeded || metrics.isNearLimit) && displayMode != WifiMonitorPreferences.DISPLAY_SPEED_ONLY,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val alertBannerColor = if (metrics.isLimitExceeded) alertPink else signalOrange
            val percentInt = (metrics.dailyLimitPercent * 100).toInt()
            val bannerText = if (metrics.isLimitExceeded) {
                stringResource(id = R.string.daily_limit_exceeded, percentInt)
            } else {
                stringResource(id = R.string.daily_limit_warning, percentInt)
            }

            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(alertBannerColor.copy(alpha = 0.12f))
                        .border(
                            1.dp,
                            alertBannerColor.copy(alpha = alertPulseAlpha),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (metrics.isLimitExceeded) Icons.Default.ErrorOutline else Icons.Default.Warning,
                        contentDescription = null,
                        tint = alertBannerColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = bannerText,
                        color = alertBannerColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // 5. Quick Set Limit & Customization Cockpit Action Bar
        Button(
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .testTag("wifi_monitor_config_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = neonCyan.copy(alpha = 0.14f),
                contentColor = neonCyan
            ),
            border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.45f)),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = neonCyan
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(id = R.string.dialog_set_limit_title),
                    color = neonCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(neonCyan.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (metrics.dailyLimitMb > 0f) "${metrics.dailyLimitMb.toInt()} MB" else "LIMIT OFF",
                        color = neonCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        if (showMonthlyTracker) {
            WifiMonthlyTrackerDialog(
                context = context,
                onDismiss = { showMonthlyTracker = false }
            )
        }
    }
}
