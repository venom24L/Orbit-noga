package com.example

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class PingQuality {
    ULTRA_LOW,
    GOOD,
    HIGH,
    OFFLINE
}

data class DayWifiUsage(
    val dayOfMonth: Int,
    val dayOfWeek: String,
    val dateLabel: String,
    val rxBytes: Long,
    val txBytes: Long,
    val totalBytes: Long,
    val totalFormatted: String,
    val rxFormatted: String,
    val txFormatted: String,
    val isToday: Boolean,
    val isFuture: Boolean,
    val isExceededLimit: Boolean = false,
    val limitPercent: Float = 0f
)

data class MonthWifiSummary(
    val year: Int,
    val month: Int,
    val monthLabel: String,
    val totalBytes: Long,
    val totalFormatted: String,
    val rxBytes: Long,
    val rxFormatted: String,
    val txBytes: Long,
    val txFormatted: String,
    val dailyAverageBytes: Long,
    val dailyAverageFormatted: String,
    val peakDay: DayWifiUsage?,
    val days: List<DayWifiUsage>,
    val hasUsagePermission: Boolean,
    val dailyLimitMb: Float
)

data class WifiTelemetryMetrics(
    val isConnected: Boolean = false,
    val isWifi: Boolean = false,
    val ssid: String = "",
    val frequencyBand: String = "",
    val linkSpeedMbps: Int = 0,
    val signalLevel: Int = 3,
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val downloadValueStr: String = "0",
    val downloadUnitStr: String = "KB/s",
    val uploadValueStr: String = "0",
    val uploadUnitStr: String = "KB/s",
    val speedUnitIsBits: Boolean = false,
    val todayBytes: Long = 0L,
    val todayUsageFormatted: String = "0 MB",
    val todayUsageMb: Float = 0f,
    val hasUsagePermission: Boolean = false,
    val pingMs: Int = -1,
    val pingQuality: PingQuality = PingQuality.OFFLINE,
    val dailyLimitMb: Float = 0f,
    val dailyLimitPercent: Float = 0f,
    val isNearLimit: Boolean = false,
    val isLimitExceeded: Boolean = false,
    val isHighTraffic: Boolean = false,
    val adaptiveIntervalMs: Long = 1000L
)

object WifiMonitorManager {

    private var lastSpeedSampleTime = 0L
    private var lastTotalRx = -1L
    private var lastTotalTx = -1L
    private var lastMobileRx = -1L
    private var lastMobileTx = -1L
    private var lastDownloadBps = 0L
    private var lastUploadBps = 0L

    private var lastPingSampleTime = 0L
    private var cachedPingMs = -1

    /**
     * Checks whether the user has granted the special PACKAGE_USAGE_STATS permission
     * to read system-wide historical network telemetry.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun sampleWifiMetrics(context: Context): WifiTelemetryMetrics = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)

        val isConnected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        // 1. Wi-Fi Band & Signal Level
        var linkSpeed = 0
        var signalLevel = 3
        var frequencyBand = ""
        if (isWifi) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wm?.connectionInfo
                val freq = info?.frequency ?: 0
                frequencyBand = when {
                    freq in 2400..2500 -> "2.4 GHz"
                    freq in 4900..5900 -> "5 GHz"
                    freq in 5925..7125 -> "6 GHz"
                    else -> ""
                }
                val rawLink = info?.linkSpeed ?: -1
                linkSpeed = if (rawLink > 0) {
                    rawLink
                } else {
                    caps.linkDownstreamBandwidthKbps / 1000
                }
                val rssi = info?.rssi ?: -70
                signalLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps != null) {
                    caps.signalStrength.coerceIn(0, 4)
                } else {
                    WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
                }
            } catch (_: Throwable) {}
        }

        // 2. SSID & Network Name
        val ssid = when {
            !isConnected -> context.getString(R.string.wifi_disconnected)
            isWifi -> getWifiSsid(context, frequencyBand)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> context.getString(R.string.network_cellular)
            else -> context.getString(R.string.network_connected)
        }

        // 3. Real-Time Speeds (Isolate Wi-Fi when on Wi-Fi, otherwise total device)
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()

        var downloadBps = 0L
        var uploadBps = 0L

        if (lastSpeedSampleTime > 0L && lastTotalRx >= 0L && totalRx >= lastTotalRx) {
            val elapsedMs = nowElapsed - lastSpeedSampleTime
            val elapsedSec = elapsedMs / 1000.0
            if (elapsedSec in 0.15..10.0) {
                val deltaTotalRx = totalRx - lastTotalRx
                val deltaTotalTx = if (lastTotalTx >= 0L && totalTx >= lastTotalTx) totalTx - lastTotalTx else 0L

                val deltaMobileRx = if (mobileRx >= 0L && lastMobileRx >= 0L && mobileRx >= lastMobileRx) {
                    mobileRx - lastMobileRx
                } else {
                    0L
                }
                val deltaMobileTx = if (mobileTx >= 0L && lastMobileTx >= 0L && mobileTx >= lastMobileTx) {
                    mobileTx - lastMobileTx
                } else {
                    0L
                }

                val deltaWifiRx = if (isWifi) (deltaTotalRx - deltaMobileRx).coerceAtLeast(0L) else deltaTotalRx
                val deltaWifiTx = if (isWifi) (deltaTotalTx - deltaMobileTx).coerceAtLeast(0L) else deltaTotalTx

                val rawDl = (deltaWifiRx / elapsedSec).toLong()
                val rawUl = (deltaWifiTx / elapsedSec).toLong()

                downloadBps = if (lastDownloadBps <= 0L || rawDl > lastDownloadBps) {
                    rawDl
                } else {
                    ((rawDl * 0.75) + (lastDownloadBps * 0.25)).toLong()
                }
                uploadBps = if (lastUploadBps <= 0L || rawUl > lastUploadBps) {
                    rawUl
                } else {
                    ((rawUl * 0.75) + (lastUploadBps * 0.25)).toLong()
                }
            }
        }

        lastSpeedSampleTime = nowElapsed
        lastTotalRx = totalRx
        lastTotalTx = totalTx
        lastMobileRx = mobileRx
        lastMobileTx = mobileTx
        lastDownloadBps = downloadBps
        lastUploadBps = uploadBps

        val useBits = WifiMonitorPreferences.isSpeedUnitBits(context)
        val (dlVal, dlUnit) = formatSpeedComponents(downloadBps, useBits)
        val (ulVal, ulUnit) = formatSpeedComponents(uploadBps, useBits)

        // 4. Daily Wi-Fi Data Usage (Continuous Cumulative Boot Delta + OS System Sync)
        val hasUsagePerm = hasUsageStatsPermission(context)
        val todayBytes = readTodayWifiBytes(context, hasUsagePerm)
        val todayMb = (todayBytes.toDouble() / (1024.0 * 1024.0)).toFloat()
        val todayFormatted = formatDataSize(todayBytes)

        // 5. Daily Limit & Alerts
        val dailyLimitMb = WifiMonitorPreferences.getDailyLimitMb(context)
        val dailyLimitPercent = if (dailyLimitMb > 0f) {
            (todayMb / dailyLimitMb).coerceAtLeast(0f)
        } else {
            0f
        }
        val isNearLimit = dailyLimitMb > 0f && dailyLimitPercent >= 0.8f && dailyLimitPercent < 1.0f
        val isLimitExceeded = dailyLimitMb > 0f && dailyLimitPercent >= 1.0f

        // 6. Ping / Latency
        val isPingEnabled = WifiMonitorPreferences.isPingEnabled(context)
        val pingMs = if (isPingEnabled && isConnected) {
            if (nowElapsed - lastPingSampleTime > 3000L || cachedPingMs < 0) {
                val measured = measurePing()
                cachedPingMs = measured
                lastPingSampleTime = nowElapsed
                measured
            } else {
                cachedPingMs
            }
        } else {
            -1
        }

        val pingQuality = when {
            pingMs < 0 -> PingQuality.OFFLINE
            pingMs <= 45 -> PingQuality.ULTRA_LOW
            pingMs <= 95 -> PingQuality.GOOD
            else -> PingQuality.HIGH
        }

        // 7. Responsive Refresh Rate Calculation
        val isAdaptive = WifiMonitorPreferences.isAdaptiveRefreshEnabled(context)
        val totalSpeed = downloadBps + uploadBps
        val isHighTraffic = totalSpeed > 30 * 1024 // > 30 KB/s

        val adaptiveIntervalMs = if (!isAdaptive) {
            1000L
        } else when {
            totalSpeed > 60 * 1024 -> 750L   // High traffic: fast updates
            totalSpeed > 10 * 1024 -> 1000L  // Moderate traffic
            else -> 1200L                    // Low traffic: snappy and battery-conscious
        }

        WifiTelemetryMetrics(
            isConnected = isConnected,
            isWifi = isWifi,
            ssid = ssid,
            frequencyBand = frequencyBand,
            linkSpeedMbps = linkSpeed,
            signalLevel = signalLevel,
            downloadSpeedBps = downloadBps,
            uploadSpeedBps = uploadBps,
            downloadValueStr = dlVal,
            downloadUnitStr = dlUnit,
            uploadValueStr = ulVal,
            uploadUnitStr = ulUnit,
            speedUnitIsBits = useBits,
            todayBytes = todayBytes,
            todayUsageFormatted = todayFormatted,
            todayUsageMb = todayMb,
            hasUsagePermission = hasUsagePerm,
            pingMs = pingMs,
            pingQuality = pingQuality,
            dailyLimitMb = dailyLimitMb,
            dailyLimitPercent = dailyLimitPercent,
            isNearLimit = isNearLimit,
            isLimitExceeded = isLimitExceeded,
            isHighTraffic = isHighTraffic,
            adaptiveIntervalMs = adaptiveIntervalMs
        )
    }

    private fun getWifiSsid(context: Context, frequencyBand: String): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val raw = info?.ssid?.replace("\"", "") ?: ""
            if (raw.isNotBlank() && raw != "<unknown ssid>" && raw != "0x") {
                raw
            } else if (frequencyBand.isNotBlank()) {
                "Wi-Fi ($frequencyBand)"
            } else {
                context.getString(R.string.wifi_connected_generic)
            }
        } catch (_: Throwable) {
            if (frequencyBand.isNotBlank()) "Wi-Fi ($frequencyBand)" else context.getString(R.string.wifi_connected_generic)
        }
    }

    private fun readTodayWifiBytes(context: Context, hasUsagePermission: Boolean): Long {
        // Step 1: Capture any kernel boot traffic delta that occurred since last sample
        val localAccumulated = WifiMonitorPreferences.updateTrafficDelta(context)

        if (!hasUsagePermission) {
            return localAccumulated
        }

        // Step 2: Query system NetworkStatsManager for verified Android OS Wi-Fi usage today
        var systemStatsBytes = 0L
        try {
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            // Form 1: querySummaryForDevice with empty string subscriberId
            try {
                val bucket = nsm?.querySummaryForDevice(
                    ConnectivityManager.TYPE_WIFI,
                    "",
                    startTime,
                    endTime
                )
                if (bucket != null) {
                    systemStatsBytes = bucket.rxBytes + bucket.txBytes
                }
            } catch (_: Throwable) {}

            // Form 2: querySummaryForDevice with null subscriberId
            if (systemStatsBytes == 0L) {
                try {
                    val bucket = nsm?.querySummaryForDevice(
                        ConnectivityManager.TYPE_WIFI,
                        null,
                        startTime,
                        endTime
                    )
                    if (bucket != null) {
                        systemStatsBytes = bucket.rxBytes + bucket.txBytes
                    }
                } catch (_: Throwable) {}
            }

            // Form 3: querySummary iterator (aggregates across all apps)
            if (systemStatsBytes == 0L) {
                try {
                    val stats = nsm?.querySummary(ConnectivityManager.TYPE_WIFI, "", startTime, endTime)
                    if (stats != null) {
                        val b = NetworkStats.Bucket()
                        var sum = 0L
                        while (stats.hasNextBucket()) {
                            stats.getNextBucket(b)
                            sum += b.rxBytes + b.txBytes
                        }
                        stats.close()
                        if (sum > 0L) {
                            systemStatsBytes = sum
                        }
                    }
                } catch (_: Throwable) {}
            }

            if (systemStatsBytes > 0L) {
                WifiMonitorPreferences.setAccumulatedTodayBytes(context, systemStatsBytes)
            }
        } catch (_: Throwable) {}

        return if (systemStatsBytes > 0L) systemStatsBytes else localAccumulated
    }

    private fun measurePing(): Int {
        // 1. Primary: Fast HTTP probe to Android's captive portal check endpoint (returns 204 No Content)
        try {
            val start = System.currentTimeMillis()
            val url = URL("https://www.google.com/generate_204")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connect()
            val responseCode = conn.responseCode
            val latency = (System.currentTimeMillis() - start).toInt()
            conn.disconnect()
            if (responseCode in 200..399 && latency in 1..2500) {
                return latency
            }
        } catch (_: Throwable) {}

        // 2. Secondary: Fast TCP socket handshake to primary DNS servers (Cloudflare / Google)
        val targets = listOf(
            Pair("1.1.1.1", 443),
            Pair("8.8.8.8", 53),
            Pair("1.0.0.1", 80)
        )
        for ((host, port) in targets) {
            try {
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 900)
                val latency = (System.currentTimeMillis() - start).toInt()
                socket.close()
                if (latency in 1..2500) return latency
            } catch (_: Throwable) {}
        }

        return -1
    }

    fun formatSpeedComponents(bytesPerSec: Long, useBits: Boolean = false): Pair<String, String> {
        if (useBits) {
            val bits = (bytesPerSec.coerceAtLeast(0L) * 8).toDouble()
            return when {
                bits >= 1_000_000_000.0 -> {
                    Pair(String.format(Locale.US, "%.2f", bits / 1_000_000_000.0), "Gbps")
                }
                bits >= 1_000_000.0 -> {
                    Pair(String.format(Locale.US, "%.2f", bits / 1_000_000.0), "Mbps")
                }
                bits >= 1_000.0 -> {
                    Pair(String.format(Locale.US, "%.1f", bits / 1_000.0), "Kbps")
                }
                else -> {
                    Pair(String.format(Locale.US, "%.0f", bits), "bps")
                }
            }
        } else {
            val b = bytesPerSec.coerceAtLeast(0L).toDouble()
            return when {
                b >= 1024.0 * 1024.0 * 1024.0 -> {
                    val gb = b / (1024.0 * 1024.0 * 1024.0)
                    Pair(String.format(Locale.US, "%.2f", gb), "GB/s")
                }
                b >= 1024.0 * 1024.0 -> {
                    val mb = b / (1024.0 * 1024.0)
                    Pair(String.format(Locale.US, "%.2f", mb), "MB/s")
                }
                b >= 1024.0 -> {
                    val kb = b / 1024.0
                    Pair(String.format(Locale.US, if (kb < 10) "%.1f" else "%.0f", kb), "KB/s")
                }
                else -> {
                    Pair(String.format(Locale.US, "%.0f", b), "B/s")
                }
            }
        }
    }

    fun formatDataSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L).toDouble()
        return when {
            b >= 1024.0 * 1024.0 * 1024.0 -> {
                val gb = b / (1024.0 * 1024.0 * 1024.0)
                String.format(Locale.US, "%.2f GB", gb)
            }
            b >= 1024.0 * 1024.0 -> {
                val mb = b / (1024.0 * 1024.0)
                String.format(Locale.US, "%.1f MB", mb)
            }
            b >= 1024.0 -> {
                val kb = b / 1024.0
                String.format(Locale.US, "%.0f KB", kb)
            }
            else -> {
                "$bytes B"
            }
        }
    }

    suspend fun getMonthDailyWifiUsage(context: Context, year: Int, month: Int): MonthWifiSummary = withContext(Dispatchers.IO) {
        val hasUsagePermission = hasUsageStatsPermission(context)
        val dailyLimitMb = WifiMonitorPreferences.getDailyLimitMb(context)
        val dailyLimitBytes = (dailyLimitMb * 1024L * 1024L).toLong()

        val cal = Calendar.getInstance()
        val todayYear = cal.get(Calendar.YEAR)
        val todayMonth = cal.get(Calendar.MONTH)
        val todayDay = cal.get(Calendar.DAY_OF_MONTH)
        val nowMillis = System.currentTimeMillis()

        val monthCal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val maxDaysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val monthLabel = monthFormat.format(monthCal.time)

        val nsm = if (hasUsagePermission) {
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
        } else null

        val dayFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val dayOfWeekFormat = SimpleDateFormat("EEE", Locale.getDefault())

        val dayList = ArrayList<DayWifiUsage>(maxDaysInMonth)
        var monthRx = 0L
        var monthTx = 0L

        for (day in 1..maxDaysInMonth) {
            val startCal = Calendar.getInstance().apply {
                clear()
                set(year, month, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startTime = startCal.timeInMillis

            val endCal = Calendar.getInstance().apply {
                clear()
                set(year, month, day, 23, 59, 59)
                set(Calendar.MILLISECOND, 999)
            }
            val endTime = endCal.timeInMillis

            val isToday = (year == todayYear && month == todayMonth && day == todayDay)
            val isFuture = startTime > nowMillis

            var dayRx = 0L
            var dayTx = 0L

            if (!isFuture) {
                if (isToday) {
                    val liveToday = WifiMonitorPreferences.getAccumulatedTodayBytes(context)
                    if (nsm != null) {
                        val (sysRx, sysTx) = queryDeviceWifiStats(nsm, startTime, nowMillis)
                        if (sysRx + sysTx >= liveToday) {
                            dayRx = sysRx
                            dayTx = sysTx
                        } else {
                            dayRx = (liveToday * 0.8).toLong()
                            dayTx = (liveToday * 0.2).toLong()
                        }
                    } else {
                        dayRx = (liveToday * 0.8).toLong()
                        dayTx = (liveToday * 0.2).toLong()
                    }
                } else {
                    if (nsm != null) {
                        val (sysRx, sysTx) = queryDeviceWifiStats(nsm, startTime, endTime)
                        dayRx = sysRx
                        dayTx = sysTx
                    } else {
                        val cached = WifiMonitorPreferences.getHistoricalDayBytes(context, year, month, day)
                        dayRx = (cached * 0.8).toLong()
                        dayTx = (cached * 0.2).toLong()
                    }
                }
            }

            val totalDay = dayRx + dayTx
            monthRx += dayRx
            monthTx += dayTx

            val isExceeded = dailyLimitBytes > 0 && totalDay > dailyLimitBytes
            val limitPercent = if (dailyLimitBytes > 0) (totalDay.toFloat() / dailyLimitBytes.toFloat()).coerceAtLeast(0f) else 0f

            dayList.add(
                DayWifiUsage(
                    dayOfMonth = day,
                    dayOfWeek = dayOfWeekFormat.format(startCal.time),
                    dateLabel = dayFormat.format(startCal.time),
                    rxBytes = dayRx,
                    txBytes = dayTx,
                    totalBytes = totalDay,
                    totalFormatted = formatDataSize(totalDay),
                    rxFormatted = formatDataSize(dayRx),
                    txFormatted = formatDataSize(dayTx),
                    isToday = isToday,
                    isFuture = isFuture,
                    isExceededLimit = isExceeded,
                    limitPercent = limitPercent
                )
            )
        }

        val totalMonth = monthRx + monthTx
        val effectiveDays = if (year == todayYear && month == todayMonth) todayDay.coerceAtLeast(1) else maxDaysInMonth
        val dailyAvg = if (effectiveDays > 0) totalMonth / effectiveDays else 0L
        val peakDay = dayList.filter { !it.isFuture }.maxByOrNull { it.totalBytes }?.takeIf { it.totalBytes > 0 }

        MonthWifiSummary(
            year = year,
            month = month,
            monthLabel = monthLabel,
            totalBytes = totalMonth,
            totalFormatted = formatDataSize(totalMonth),
            rxBytes = monthRx,
            rxFormatted = formatDataSize(monthRx),
            txBytes = monthTx,
            txFormatted = formatDataSize(monthTx),
            dailyAverageBytes = dailyAvg,
            dailyAverageFormatted = formatDataSize(dailyAvg),
            peakDay = peakDay,
            days = dayList,
            hasUsagePermission = hasUsagePermission,
            dailyLimitMb = dailyLimitMb
        )
    }

    private fun queryDeviceWifiStats(nsm: NetworkStatsManager, startTime: Long, endTime: Long): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        try {
            val bucket = nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, "", startTime, endTime)
            if (bucket != null) {
                rx = bucket.rxBytes
                tx = bucket.txBytes
            }
        } catch (_: Throwable) {}

        if (rx == 0L && tx == 0L) {
            try {
                val bucket = nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, startTime, endTime)
                if (bucket != null) {
                    rx = bucket.rxBytes
                    tx = bucket.txBytes
                }
            } catch (_: Throwable) {}
        }

        if (rx == 0L && tx == 0L) {
            try {
                val stats = nsm.querySummary(ConnectivityManager.TYPE_WIFI, "", startTime, endTime)
                if (stats != null) {
                    val b = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(b)
                        rx += b.rxBytes
                        tx += b.txBytes
                    }
                    stats.close()
                }
            } catch (_: Throwable) {}
        }

        return Pair(rx, tx)
    }
}
