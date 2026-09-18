package com.example

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.shortcut.ShortcutNotificationManager
import com.example.shortcut.ShortcutNotificationPreferences
import com.example.shortcut.ShortcutNotificationScreen
import com.example.tutorial.LiveTourGuideOverlay
import com.example.tutorial.TourStep
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import kotlin.math.min
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.provider.MediaStore
import android.app.ActivityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.FileOutputStream
import com.example.ui.theme.*
import com.example.data.ArtworkEntry
import com.example.data.OrbitDatabase
import com.example.data.OrbitRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val isOverlayGranted = MutableStateFlow(false)
    private val isUsageGranted = MutableStateFlow(false)
    private val isNotificationGranted = MutableStateFlow(false)

    // Shared theme state to trigger instant recreate or refresh
    private val activeThemeState = mutableStateOf<NeonTheme?>(null)

    private fun setLocale(context: Context, langCode: String) {
        val locale = java.util.Locale(langCode)
        java.util.Locale.setDefault(locale)
        try {
            val appLocales = LocaleListCompat.forLanguageTags(langCode)
            AppCompatDelegate.setApplicationLocales(appLocales)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val resources = context.resources
        val config = resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
            config.setLayoutDirection(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = android.os.LocaleList(locale)
            config.setLocales(localeList)
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        
        val appContext = context.applicationContext
        val appResources = appContext.resources
        val appConfig = appResources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            appConfig.setLocale(locale)
            appConfig.setLayoutDirection(locale)
        } else {
            @Suppress("DEPRECATION")
            appConfig.locale = locale
        }
        @Suppress("DEPRECATION")
        appResources.updateConfiguration(appConfig, appResources.displayMetrics)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLocale(this, ThemePreferences.getLanguage(this))
        
        // Initialize permission flows with real-time values on startup
        isOverlayGranted.value = Settings.canDrawOverlays(this)
        isUsageGranted.value = hasUsageStatsPermission(this)
        isNotificationGranted.value = hasNotificationsPermission(this)

        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var activeTheme by remember { mutableStateOf(ThemePreferences.getSelectedTheme(context)) }
            var currentLanguage by remember { mutableStateOf(ThemePreferences.getLanguage(context)) }
            var showLangSelect by remember { mutableStateOf(!ThemePreferences.isLangSelected(context)) }
            var showUsernameInput by remember { mutableStateOf(!ThemePreferences.hasUsername(context)) }
            var showIntro by remember { mutableStateOf(!ThemePreferences.isIntroSeen(context)) }

            val layoutDirection = if (currentLanguage == "ar" || currentLanguage == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                MyApplicationTheme(accentColor = activeTheme.getColor()) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = DeepDark
                    ) { innerPadding ->
                        if (showLangSelect) {
                            LanguageSelectionScreen(
                                currentLanguage = currentLanguage,
                                onLanguageSelected = { lang ->
                                    if (currentLanguage != lang) {
                                        ThemePreferences.setLanguage(context, lang)
                                        setLocale(context, lang)
                                        currentLanguage = lang
                                        (context as? Activity)?.recreate()
                                    }
                                },
                                onContinue = {
                                    ThemePreferences.setLangSelected(context, true)
                                    showLangSelect = false
                                },
                                accentColor = activeTheme.getColor()
                            )
                        } else if (showUsernameInput) {
                            UsernameOnboardingScreen(
                                accentColor = activeTheme.getColor(),
                                onFinished = {
                                    showUsernameInput = false
                                }
                            )
                        } else if (showIntro) {
                            IntroSequenceScreen(
                                accentColor = activeTheme.getColor(),
                                onFinished = {
                                    ThemePreferences.setIntroSeen(context, true)
                                    showIntro = false
                                }
                            )
                        } else {
                            MainScreen(
                                paddingValues = innerPadding,
                                activeTheme = activeTheme,
                                onThemeChanged = { newTheme -> activeTheme = newTheme },
                                currentLanguage = currentLanguage,
                                onLanguageChanged = { lang ->
                                    if (currentLanguage != lang) {
                                        ThemePreferences.setLanguage(context, lang)
                                        setLocale(context, lang)
                                        currentLanguage = lang
                                        (context as? Activity)?.recreate()
                                    }
                                },
                                isOverlayGrantedFlow = isOverlayGranted,
                                isUsageGrantedFlow = isUsageGranted,
                                isNotificationGrantedFlow = isNotificationGranted,
                                onRequestOverlay = { requestOverlayPermission() },
                                onRequestUsage = { requestUsageStatsPermission() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Query permissions in real-time when returning from system settings screens
        val overlay = Settings.canDrawOverlays(this)
        isOverlayGranted.value = overlay
        isUsageGranted.value = hasUsageStatsPermission(this)
        isNotificationGranted.value = hasNotificationsPermission(this)

        if (overlay && !FloatingLauncherService.isServiceRunning.value) {
            val intent = Intent(this, FloatingLauncherService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // Notify shortcut screens that the app resumed in case actions were triggered in notification shade
        com.example.shortcut.ShortcutNotificationPreferences.shortcutsUpdateEvent.tryEmit("ALL")
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // General settings fallback
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                data = Uri.parse("package:$packageName")
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationsPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

@Composable
fun MainScreen(
    paddingValues: PaddingValues,
    activeTheme: NeonTheme,
    onThemeChanged: (NeonTheme) -> Unit,
    currentLanguage: String,
    onLanguageChanged: (String) -> Unit,
    isOverlayGrantedFlow: MutableStateFlow<Boolean>,
    isUsageGrantedFlow: MutableStateFlow<Boolean>,
    isNotificationGrantedFlow: MutableStateFlow<Boolean>,
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(MainTab.ORBIT) }

    val accentColor = activeTheme.getColor()

    // Live interactive tour state
    var isLiveTourActive by remember { mutableStateOf(!ThemePreferences.isLiveTourCompleted(context)) }
    var currentTourStep by remember { mutableStateOf(TourStep.ACTIVATE_ORBIT) }

    fun handleTourStepChange(newStep: TourStep) {
        currentTourStep = newStep
        when (newStep) {
            TourStep.ACTIVATE_ORBIT,
            TourStep.ORBIT_RING_FEATURES -> {
                selectedTab = MainTab.ORBIT
            }
            TourStep.FIND_SHORTCUT_SECTION,
            TourStep.PIN_SYSTEM_SHORTCUTS -> {
                selectedTab = MainTab.STUDIO
            }
        }
    }

    fun dismissLiveTour() {
        isLiveTourActive = false
        ThemePreferences.setLiveTourCompleted(context, true)
    }

    // Collect permissions state dynamically
    val isOverlayGranted by isOverlayGrantedFlow.collectAsState()
    val isUsageGranted by isUsageGrantedFlow.collectAsState()
    val isNotificationGranted by isNotificationGrantedFlow.collectAsState()

    var currentUsername by remember { mutableStateOf(ThemePreferences.getUsername(context).ifBlank { "User" }) }

    var isUsageSkipped by remember { mutableStateOf(ThemePreferences.isUsagePermissionSkipped(context)) }

    val activity = context as? Activity
    var openShortcutDirectly by remember {
        mutableStateOf(activity?.intent?.getBooleanExtra(ShortcutNotificationManager.EXTRA_OPEN_SHORTCUT_CONFIG, false) == true)
    }
    LaunchedEffect(Unit) {
        val appNotFoundPkg = activity?.intent?.getStringExtra(ShortcutNotificationManager.EXTRA_SHORTCUT_APP_NOT_FOUND)
        if (!appNotFoundPkg.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.app_unavailable_toast), Toast.LENGTH_LONG).show()
            selectedTab = MainTab.STUDIO
            openShortcutDirectly = true
        } else if (openShortcutDirectly) {
            selectedTab = MainTab.STUDIO
        }
    }

    LaunchedEffect(isUsageGranted) {
        if (isUsageGranted && isUsageSkipped) {
            ThemePreferences.setUsagePermissionSkipped(context, false)
            isUsageSkipped = false
        }
    }

    // Collect service state
    val isServiceRunning by FloatingLauncherService.isServiceRunning.collectAsState()

    val isUsageAllowed = isUsageGranted || isUsageSkipped
    val isServiceRunnable = isOverlayGranted && isNotificationGranted && isUsageAllowed

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            isNotificationGrantedFlow.value = isGranted
            if (!isGranted) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        containerColor = DeepDark,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF0D0E15))
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MainTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        val signalOrange = Color(0xFFFF6B35)
                        val inkDim = Color(0xFF5A6178)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) signalOrange.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable { selectedTab = tab }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(id = tab.titleResId),
                                    tint = if (isSelected) signalOrange else inkDim,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(id = tab.titleResId),
                                    color = if (isSelected) signalOrange else inkDim,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                MainTab.ORBIT -> OrbitTabContent(
                    context = context,
                    accentColor = accentColor,
                    username = currentUsername,
                    isServiceRunning = isServiceRunning,
                    isServiceRunnable = isServiceRunnable,
                    onRequestOverlay = onRequestOverlay,
                    onToggleService = {
                        if (!Settings.canDrawOverlays(context)) {
                            onRequestOverlay()
                        } else {
                            if (isServiceRunning) {
                                val intent = Intent(context, FloatingLauncherService::class.java)
                                context.stopService(intent)
                            } else {
                                val intent = Intent(context, FloatingLauncherService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                        }
                    }
                )
                MainTab.STUDIO -> StudioTabContent(
                    context = context,
                    accentColor = accentColor,
                    activeTheme = activeTheme,
                    onThemeChanged = onThemeChanged,
                    isServiceRunning = isServiceRunning,
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            isNotificationGrantedFlow.value = true
                        }
                    },
                    isNotificationGranted = isNotificationGranted,
                    initialStudioMode = if (openShortcutDirectly || currentTourStep == TourStep.FIND_SHORTCUT_SECTION || currentTourStep == TourStep.PIN_SYSTEM_SHORTCUTS) 3 else 0
                )
                MainTab.TOOLS -> ToolsTabContent(
                    context = context,
                    accentColor = accentColor
                )
                MainTab.SETTINGS -> SettingsTabContent(
                    context = context,
                    accentColor = accentColor,
                    currentUsername = currentUsername,
                    onUsernameChanged = { newUsername -> currentUsername = newUsername },
                    currentLanguage = currentLanguage,
                    onLanguageChanged = onLanguageChanged,
                    isOverlayGranted = isOverlayGranted,
                    isUsageGranted = isUsageGranted,
                    isNotificationGranted = isNotificationGranted,
                    isUsageSkipped = isUsageSkipped,
                    onRequestOverlay = onRequestOverlay,
                    onRequestUsage = onRequestUsage,
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            isNotificationGrantedFlow.value = true
                        }
                    },
                    onSkipUsage = { skipped ->
                        ThemePreferences.setUsagePermissionSkipped(context, skipped)
                        isUsageSkipped = skipped
                    },
                    onRestartLiveTour = {
                        isLiveTourActive = true
                        handleTourStepChange(TourStep.ACTIVATE_ORBIT)
                    }
                )
            }

            if (isLiveTourActive) {
                LiveTourGuideOverlay(
                    currentStep = currentTourStep,
                    isServiceRunning = isServiceRunning,
                    onStepChange = { nextStep -> handleTourStepChange(nextStep) },
                    onDismissTour = { dismissLiveTour() }
                )
            }
        }
    }
}

enum class MainTab(val titleResId: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ORBIT(R.string.tab_orbit, Icons.Default.Public),
    STUDIO(R.string.tab_studio, Icons.Default.Palette),
    TOOLS(R.string.tab_tools, Icons.Default.Code),
    SETTINGS(R.string.tab_setup, Icons.Default.Settings)
}

@Composable
fun OrbitTabContent(
    context: Context,
    accentColor: Color,
    username: String = ThemePreferences.getUsername(context).ifBlank { "User" },
    isServiceRunning: Boolean,
    isServiceRunnable: Boolean,
    onRequestOverlay: () -> Unit,
    onToggleService: () -> Unit
) {
    var bubbleSize by remember { mutableStateOf(ThemePreferences.getBubbleSize(context)) }

    val signalOrange = Color(0xFFFF6B35)
    val trackBlue = Color(0xFF3D9BFF)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF5A6178)

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Top Header: Hello, [Username]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.launch_control_header),
                    color = signalOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(id = R.string.hello_username, username),
                    color = inkLight,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isServiceRunning) Color(0x2200E676) else Color(0x22FF2A85))
                    .border(1.dp, if (isServiceRunning) Color(0xFF00E676) else Color(0xFFFF2A85), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isServiceRunning) stringResource(id = R.string.service_active) else stringResource(id = R.string.service_inactive),
                    color = if (isServiceRunning) Color(0xFF00E676) else Color(0xFFFF2A85),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // System Performance & Real-Time RAM Monitor Card
        SystemPerformanceRamCard(
            context = context,
            accentColor = accentColor,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Quick Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Floating Service Card
            val hasOverlayPermission = Settings.canDrawOverlays(context)
            val isFloatingActive = isServiceRunning && hasOverlayPermission
            Card(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                    .clickable {
                        if (!hasOverlayPermission) {
                            onRequestOverlay()
                        } else {
                            onToggleService()
                        }
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = if (isFloatingActive) Color(0xFF10B981) else trackBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Switch(
                            checked = isFloatingActive,
                            onCheckedChange = { _ ->
                                if (!hasOverlayPermission) {
                                    onRequestOverlay()
                                } else {
                                    onToggleService()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF10B981),
                                uncheckedThumbColor = Color(0xFF9E9E9E),
                                uncheckedTrackColor = Color(0xFF2A2E3D),
                                uncheckedBorderColor = Color.Transparent
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(id = R.string.floating_service),
                        color = Color(0xFF8E94A8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        softWrap = true
                    )
                }
            }

            // Bubble Size Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RadioButtonChecked,
                            contentDescription = null,
                            tint = trackBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "$bubbleSize",
                            color = signalOrange,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(id = R.string.bubble_size_dp),
                        color = Color(0xFF8E94A8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        softWrap = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private data class SystemMemoryMetrics(
    val availMemBytes: Long = 0L,
    val totalMemBytes: Long = 1L,
    val appHeapBytes: Long = 0L,
    val isLowMemory: Boolean = false
)

private fun readSystemMemoryMetrics(context: Context): SystemMemoryMetrics {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memoryInfo)

    val runtime = Runtime.getRuntime()
    val javaHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)

    val total = if (memoryInfo.totalMem > 0L) memoryInfo.totalMem else (4L * 1024L * 1024L * 1024L)
    val avail = memoryInfo.availMem.coerceIn(0L, total)

    return SystemMemoryMetrics(
        availMemBytes = avail,
        totalMemBytes = total,
        appHeapBytes = javaHeap,
        isLowMemory = memoryInfo.lowMemory || (avail.toDouble() / total <= 0.10)
    )
}

private fun performOrbitRamClean(context: Context): Float {
    val runtime = Runtime.getRuntime()
    val beforeHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)

    // 1. Purge loaded app launcher cache & icon ImageBitmaps
    AppCache.clearMemoryCache()

    // 2. Clear Coil image memory cache if active
    try {
        val imageLoader = coil.Coil.imageLoader(context)
        imageLoader.memoryCache?.clear()
    } catch (_: Throwable) {}

    // 3. Clear browser retained webview caches & trim memory
    try {
        BrowserStateManager.getRetainedWebView()?.apply {
            clearCache(true)
            clearFormData()
            clearHistory()
        }
    } catch (_: Throwable) {}

    // 4. Force JVM garbage collection & finalization passes
    System.gc()
    runtime.runFinalization()
    System.gc()

    val afterHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
    val freedBytes = (beforeHeap - afterHeap).coerceAtLeast(0L)
    return (freedBytes.toDouble() / (1024.0 * 1024.0)).toFloat()
}

@Composable
fun SystemPerformanceRamCard(
    context: Context,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    var metrics by remember { mutableStateOf(readSystemMemoryMetrics(context)) }
    var selectedMonitorMode by remember { mutableStateOf(WifiMonitorPreferences.getSelectedMode(context)) }
    var showWifiSettingsDialog by remember { mutableStateOf(false) }
    var wifiConfigVersion by remember { mutableIntStateOf(0) }

    if (showWifiSettingsDialog) {
        WifiMonitorSettingsDialog(
            context = context,
            onDismiss = { showWifiSettingsDialog = false },
            onConfigChanged = { wifiConfigVersion++ }
        )
    }

    LaunchedEffect(Unit) {
        while (coroutineContext.isActive) {
            metrics = readSystemMemoryMetrics(context)
            delay(2000)
        }
    }

    val signalOrange = Color(0xFFFF6B35)
    val neonCyan = Color(0xFF00F0FF)
    val trackBlue = Color(0xFF3D9BFF)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF8E95AA)
    val emeraldGreen = Color(0xFF00E676)
    val alertPink = Color(0xFFFF2A85)

    val totalMem = metrics.totalMemBytes.coerceAtLeast(1L)
    val availMem = metrics.availMemBytes.coerceIn(0L, totalMem)
    val usedMem = (totalMem - availMem).coerceAtLeast(0L)
    val usedPercent = (usedMem.toDouble() / totalMem).toFloat().coerceIn(0.01f, 1f)
    val usedPercentInt = (usedPercent * 100).toInt()

    val availGb = (availMem.toDouble() / (1024.0 * 1024.0 * 1024.0)).toFloat()
    val totalGb = (totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)).toFloat()
    val heapMb = (metrics.appHeapBytes.toDouble() / (1024.0 * 1024.0)).toFloat()

    val isOptimal = !metrics.isLowMemory && ((availMem.toDouble() / totalMem) > 0.12)
    val statusText = if (isOptimal) {
        stringResource(id = R.string.status_optimal_performance)
    } else {
        stringResource(id = R.string.status_high_usage_warning)
    }
    val statusColor = if (isOptimal) emeraldGreen else alertPink

    var isCleaning by remember { mutableStateOf(false) }
    var cleanResultMsg by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val performClean: () -> Unit = {
        if (!isCleaning) {
            coroutineScope.launch {
                isCleaning = true
                val freed = performOrbitRamClean(context)
                delay(600)
                metrics = readSystemMemoryMetrics(context)
                val afterMb = (metrics.appHeapBytes.toDouble() / (1024.0 * 1024.0)).toFloat()
                isCleaning = false

                val msg = if (freed > 0.5f) {
                    context.getString(R.string.msg_ram_cleaned, freed)
                } else {
                    context.getString(R.string.msg_ram_already_optimal, afterMb)
                }
                cleanResultMsg = msg
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                delay(3500)
                if (cleanResultMsg == msg) {
                    cleanResultMsg = null
                }
            }
        }
    }

    // Smooth gauge & progress animations
    val animatedPercent by animateFloatAsState(
        targetValue = usedPercent,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "RamPercentAnim"
    )

    // Pulsing live HUD radar & telemetry beacon
    val infiniteTransition = rememberInfiniteTransition(label = "RamTelemetryPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val waveScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveScale"
    )
    val waveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveAlpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        trackBlue.copy(alpha = 0.45f),
                        signalOrange.copy(alpha = 0.35f),
                        trackBlue.copy(alpha = 0.2f)
                    )
                ),
                RoundedCornerShape(22.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C101C)),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0F1526),
                            Color(0xFF0A0E18),
                            Color(0xFF0D1220)
                        )
                    )
                )
        ) {
            // Futuristic Top Accent Laser Line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                trackBlue.copy(alpha = 0.2f),
                                neonCyan,
                                signalOrange,
                                statusColor,
                                trackBlue.copy(alpha = 0.2f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header Row: Cockpit Telemetry Badge + Mode Switcher Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) signalOrange.copy(alpha = 0.15f) else neonCyan.copy(alpha = 0.15f))
                                .border(1.dp, if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) signalOrange.copy(alpha = 0.45f) else neonCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) Icons.Default.Speed else Icons.Default.Wifi,
                                contentDescription = null,
                                tint = if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) signalOrange else neonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(neonCyan.copy(alpha = pulseAlpha))
                                )
                                Text(
                                    text = if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) "LIVE TELEMETRY // HARDWARE" else "LIVE TELEMETRY // NETWORK",
                                    color = neonCyan,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.8.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) stringResource(id = R.string.ram_monitor_title) else stringResource(id = R.string.wifi_monitor_title),
                                color = inkLight,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.sp,
                                maxLines = 2,
                                softWrap = true,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Mode Switcher Pill (RAM / Wi-Fi)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // RAM Option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) signalOrange else Color.Transparent)
                                .clickable {
                                    selectedMonitorMode = WifiMonitorPreferences.MODE_RAM
                                    WifiMonitorPreferences.setSelectedMode(context, WifiMonitorPreferences.MODE_RAM)
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) Color.White else inkDim,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = stringResource(id = R.string.mode_ram),
                                    color = if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) Color.White else inkDim,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        // Wi-Fi Option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selectedMonitorMode == WifiMonitorPreferences.MODE_WIFI) neonCyan else Color.Transparent)
                                .clickable {
                                    selectedMonitorMode = WifiMonitorPreferences.MODE_WIFI
                                    WifiMonitorPreferences.setSelectedMode(context, WifiMonitorPreferences.MODE_WIFI)
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = if (selectedMonitorMode == WifiMonitorPreferences.MODE_WIFI) Color(0xFF0C101C) else inkDim,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = stringResource(id = R.string.mode_wifi),
                                    color = if (selectedMonitorMode == WifiMonitorPreferences.MODE_WIFI) Color(0xFF0C101C) else inkDim,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedMonitorMode == WifiMonitorPreferences.MODE_RAM) {
                    // RAM Live Status Pill Sub-row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SYSTEM MEMORY USAGE",
                            color = inkDim,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )

                        // Live Status Pill with glowing animated beacon
                        val shortStatusText = if (isOptimal) {
                            stringResource(id = R.string.status_optimal)
                        } else {
                            stringResource(id = R.string.status_high_usage)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(statusColor.copy(alpha = 0.12f))
                                .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 3.5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(statusColor.copy(alpha = pulseAlpha))
                                )
                                Text(
                                    text = shortStatusText,
                                    color = statusColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                // Hero Cockpit Layout: Circular Arc Speedometer + VU Load Meter
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
                    // Circular HUD Arc Gauge
                    Box(
                        modifier = Modifier.size(96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 8.dp.toPx()
                            val padding = strokeWidth / 2f + 4.dp.toPx()
                            val arcRadius = (size.minDimension - padding * 2f) / 2f
                            val arcCenter = Offset(size.width / 2f, size.height / 2f)

                            // 1. Draw perimeter graduation tick marks (135° to 405°)
                            val totalTicks = 18
                            val startAngleDeg = 135f
                            val sweepAngleDeg = 270f
                            val activeSweepAngle = sweepAngleDeg * animatedPercent

                            for (i in 0..totalTicks) {
                                val tickAngleDeg = startAngleDeg + (sweepAngleDeg * (i.toFloat() / totalTicks))
                                val tickAngleRad = Math.toRadians(tickAngleDeg.toDouble())
                                val isTickActive = (tickAngleDeg - startAngleDeg) <= activeSweepAngle

                                val outerR = arcRadius + 5.dp.toPx()
                                val innerR = arcRadius + 2.dp.toPx()

                                val startX = arcCenter.x + innerR * Math.cos(tickAngleRad).toFloat()
                                val startY = arcCenter.y + innerR * Math.sin(tickAngleRad).toFloat()
                                val endX = arcCenter.x + outerR * Math.cos(tickAngleRad).toFloat()
                                val endY = arcCenter.y + outerR * Math.sin(tickAngleRad).toFloat()

                                drawLine(
                                    color = if (isTickActive) signalOrange.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
                                    start = Offset(startX, startY),
                                    end = Offset(endX, endY),
                                    strokeWidth = if (i % 3 == 0) 2.dp.toPx() else 1.2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }

                            // 2. Background Track Arc
                            drawArc(
                                color = Color.White.copy(alpha = 0.08f),
                                startAngle = startAngleDeg,
                                sweepAngle = sweepAngleDeg,
                                useCenter = false,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // 3. Active Foreground Gradient Arc
                            val arcGradient = Brush.sweepGradient(
                                0.0f to neonCyan,
                                0.45f to signalOrange,
                                0.85f to alertPink,
                                1.0f to neonCyan
                            )
                            drawArc(
                                brush = arcGradient,
                                startAngle = startAngleDeg,
                                sweepAngle = activeSweepAngle,
                                useCenter = false,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // 4. Glowing Leading Orb at Arc Tip
                            if (activeSweepAngle > 5f) {
                                val tipAngleRad = Math.toRadians((startAngleDeg + activeSweepAngle).toDouble())
                                val tipX = arcCenter.x + arcRadius * Math.cos(tipAngleRad).toFloat()
                                val tipY = arcCenter.y + arcRadius * Math.sin(tipAngleRad).toFloat()
                                drawCircle(
                                    color = Color.White,
                                    radius = 3.dp.toPx(),
                                    center = Offset(tipX, tipY)
                                )
                                drawCircle(
                                    color = signalOrange.copy(alpha = 0.6f),
                                    radius = 6.dp.toPx(),
                                    center = Offset(tipX, tipY)
                                )
                            }
                        }

                        // Center Percentage Readout
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "${(animatedPercent * 100).toInt()}%",
                                color = inkLight,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = "RAM LOAD",
                                color = inkDim,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Right Telemetry & Multi-Segment Meter
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(id = R.string.ram_usage_label),
                                color = inkDim,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${"%.1f".format(availGb)} GB ${stringResource(id = R.string.unit_free)}",
                                color = emeraldGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Used / Total primary readout
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val usedGb = (usedMem.toDouble() / (1024.0 * 1024.0 * 1024.0)).toFloat()
                            Text(
                                text = "%.1f".format(usedGb),
                                color = signalOrange,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "GB USED",
                                color = signalOrange.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                            Text(
                                text = "/ ${"%.1f".format(totalGb)} GB",
                                color = inkLight.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Multi-Segment Glowing VU Bar (14 segments)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val segmentCount = 14
                            for (idx in 0 until segmentCount) {
                                val segFrac = (idx + 1).toFloat() / segmentCount
                                val isLit = segFrac <= (animatedPercent + 0.04f)
                                val segColor = when {
                                    segFrac > 0.8f -> alertPink
                                    segFrac > 0.5f -> signalOrange
                                    else -> neonCyan
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (isLit) segColor else Color.White.copy(alpha = 0.08f)
                                        )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Bottom dynamic chip
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = trackBlue,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "ACTIVE ENGINE BUFFERING",
                                color = trackBlue.copy(alpha = 0.85f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3 Avionics Metric Pods (Available, Total, Orbit Heap)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pod 1: Available RAM
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, emeraldGreen.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp)
                                    .background(emeraldGreen)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 7.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = emeraldGreen,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = stringResource(id = R.string.ram_available_title),
                                        color = inkDim,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    text = stringResource(id = R.string.ram_available_fmt, availGb),
                                    color = emeraldGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                // Decorative Activity Spark
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    val heights = listOf(4.dp, 8.dp, 11.dp, 6.dp)
                                    heights.forEach { h ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(h)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(emeraldGreen.copy(alpha = 0.45f))
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Pod 2: Total RAM
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, trackBlue.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp)
                                    .background(trackBlue)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 7.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = trackBlue,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = stringResource(id = R.string.ram_total_title),
                                        color = inkDim,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    text = stringResource(id = R.string.ram_total_fmt, totalGb),
                                    color = trackBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                // Decorative Activity Spark
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    val heights = listOf(9.dp, 6.dp, 11.dp, 7.dp)
                                    heights.forEach { h ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(h)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(trackBlue.copy(alpha = 0.45f))
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Pod 3: Orbit App Heap
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, signalOrange.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                            .clickable { performClean() },
                        colors = CardDefaults.cardColors(containerColor = Color(0x10FFFFFF)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp)
                                    .background(signalOrange)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 7.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = signalOrange,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = stringResource(id = R.string.app_heap_title),
                                        color = inkDim,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    text = stringResource(id = R.string.app_heap_fmt, heapMb),
                                    color = signalOrange,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                // Decorative Activity Spark
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    val heights = listOf(6.dp, 10.dp, 5.dp, 8.dp)
                                    heights.forEach { h ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(h)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(signalOrange.copy(alpha = 0.45f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Clean Orbit RAM Cockpit Action Bar
                Button(
                    onClick = { performClean() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("clean_ram_button"),
                    enabled = !isCleaning,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = signalOrange.copy(alpha = 0.16f),
                        contentColor = signalOrange,
                        disabledContainerColor = signalOrange.copy(alpha = 0.08f),
                        disabledContentColor = signalOrange.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isCleaning) neonCyan.copy(alpha = 0.7f) else signalOrange.copy(alpha = 0.45f)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCleaning) {
                            val rotation by rememberInfiniteTransition(label = "cleanSpin").animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = LinearEasing)
                                ),
                                label = "cleanSpinAngle"
                            )
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .rotate(rotation),
                                tint = neonCyan
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.btn_cleaning),
                                color = neonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = stringResource(id = R.string.btn_clean_ram),
                                modifier = Modifier.size(16.dp),
                                tint = signalOrange
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.btn_clean_ram),
                                color = signalOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(signalOrange.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ORBIT HEAP",
                                    color = signalOrange,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }

                // Clean Result Feedback Banner
                AnimatedVisibility(
                    visible = cleanResultMsg != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    cleanResultMsg?.let { msg ->
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(emeraldGreen.copy(alpha = 0.12f))
                                    .border(1.dp, emeraldGreen.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = emeraldGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = msg,
                                    color = emeraldGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Floating Service Memory Impact Banner with Radar Ping
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x0EFFFFFF))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Radar Wave Beacon
                        Box(
                            modifier = Modifier.size(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp * waveScale)
                                    .clip(CircleShape)
                                    .border(1.dp, statusColor.copy(alpha = waveAlpha), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                        }

                        Text(
                            text = stringResource(id = R.string.overlay_impact_title),
                            color = inkDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            } else {
                // WiFi Cockpit Content
                WifiMonitorCockpitContent(
                    context = context,
                    accentColor = accentColor,
                    onOpenSettings = { showWifiSettingsDialog = true },
                    configVersion = wifiConfigVersion
                )
            }
        }
    }
}
}

@Composable
fun RealTimeStatsCard(context: Context, accentColor: Color) {
    var topApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        val apps = AppCache.getApps(context)
        topApps = apps.sortedByDescending { it.usageTimeMs }.take(5)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.recent_usage_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Info,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(id = R.string.recent_usage_subtitle),
                fontSize = 11.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (topApps.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.no_recent_activity),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            } else {
                topApps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = app.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accentColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            val timeMinutes = app.usageTimeMs / (1000 * 60)
                            Text(
                                text = if (timeMinutes > 0) "${timeMinutes}m used" else "Recently active",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CircularCropDialog(
    imageUri: Uri,
    context: Context,
    accentColor: Color,
    onDismiss: () -> Unit,
    onCropped: (Bitmap) -> Unit
) {
    val sourceBitmap = remember(imageUri) {
        try {
            val loaded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, imageUri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
            }
            if (loaded != null && loaded.config == Bitmap.Config.HARDWARE) {
                loaded.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                loaded
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val signalOrange = Color(0xFFFF6B35)
    val neonBlue = Color(0xFF3D9BFF)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF050508))
        ) {
            if (sourceBitmap != null) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val containerWidth = constraints.maxWidth.toFloat()
                    val containerHeight = constraints.maxHeight.toFloat()
                    val cropCircleRadius = Math.min(containerWidth, containerHeight) * 0.38f
                    val cropDiameter = cropCircleRadius * 2f

                    val srcW = sourceBitmap.width.toFloat()
                    val srcH = sourceBitmap.height.toFloat()
                    val fitScale = cropDiameter / Math.min(srcW, srcH)

                    // Transformable Canvas Image Display with Gesture Detection
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(sourceBitmap) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                                    val curW = srcW * fitScale * newScale
                                    val curH = srcH * fitScale * newScale
                                    val maxPanX = ((curW - cropDiameter) / 2f).coerceAtLeast(0f)
                                    val maxPanY = ((curH - cropDiameter) / 2f).coerceAtLeast(0f)
                                    scale = newScale
                                    offset = Offset(
                                        x = (offset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                        y = (offset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                    )
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cX = size.width / 2f
                            val cY = size.height / 2f

                            val displayScale = fitScale * scale
                            val drawW = srcW * displayScale
                            val drawH = srcH * displayScale

                            val left = (cX + offset.x) - (drawW / 2f)
                            val top = (cY + offset.y) - (drawH / 2f)

                            // 1. Draw source bitmap transformed by scale and offset
                            drawImage(
                                image = sourceBitmap.asImageBitmap(),
                                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
                                filterQuality = FilterQuality.High
                            )

                            // 2. Overlay dark mask outside circular viewport (PathFillType.EvenOdd ensures the circle is transparent)
                            val overlayPath = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(0f, 0f, size.width, size.height))
                                addOval(Rect(cX - cropCircleRadius, cY - cropCircleRadius, cX + cropCircleRadius, cY + cropCircleRadius))
                            }
                            drawPath(overlayPath, color = Color(0xEE050508))

                            // 3. Outer Neon Blue Glow Ring
                            drawCircle(
                                color = neonBlue.copy(alpha = 0.4f),
                                radius = cropCircleRadius + 4.dp.toPx(),
                                center = Offset(cX, cY),
                                style = Stroke(width = 6.dp.toPx())
                            )

                            // 4. Inner Signal Orange Ring
                            drawCircle(
                                color = signalOrange,
                                radius = cropCircleRadius,
                                center = Offset(cX, cY),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }

                    // Top Bar Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                        Text(
                            text = stringResource(id = R.string.crop_floating_bubble),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                val cropped = performCircularCrop(
                                    source = sourceBitmap,
                                    userScale = scale,
                                    offset = offset,
                                    cropRadiusOnScreen = cropCircleRadius
                                )
                                onCropped(cropped)
                            }
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "Confirm", tint = signalOrange, modifier = Modifier.size(28.dp))
                        }
                    }

                    // Bottom Controls Guide
                    Text(
                        text = stringResource(id = R.string.crop_guide_hint),
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 36.dp)
                            .background(Color(0xFF0D0E15).copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                            .border(1.dp, signalOrange.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = signalOrange)
                }
            }
        }
    }
}

private fun performCircularCrop(
    source: Bitmap,
    userScale: Float,
    offset: Offset,
    cropRadiusOnScreen: Float
): Bitmap {
    val safeSource = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }

    val outputSize = 256
    val outputRadius = outputSize / 2f
    val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)

    // Save offscreen layer to isolate PorterDuff Xfermode compositing
    val saveCount = canvas.saveLayer(0f, 0f, outputSize.toFloat(), outputSize.toFloat(), null)

    // Solid circular mask
    val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
    }
    canvas.drawCircle(outputRadius, outputRadius, outputRadius, maskPaint)

    // Bitmaps drawing using PorterDuff.Mode.SRC_IN to clip bitmap inside circle
    val bitmapPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }

    val srcW = safeSource.width.toFloat()
    val srcH = safeSource.height.toFloat()

    val cropDiameterOnScreen = cropRadiusOnScreen * 2f
    val baseFitScale = cropDiameterOnScreen / Math.min(srcW, srcH)
    val totalDisplayScale = baseFitScale * userScale

    val screenToOutputRatio = outputSize / cropDiameterOnScreen

    val drawW = srcW * totalDisplayScale * screenToOutputRatio
    val drawH = srcH * totalDisplayScale * screenToOutputRatio

    val drawX = outputRadius + (offset.x * screenToOutputRatio) - (drawW / 2f)
    val drawY = outputRadius + (offset.y * screenToOutputRatio) - (drawH / 2f)

    val matrix = Matrix()
    matrix.postScale(totalDisplayScale * screenToOutputRatio, totalDisplayScale * screenToOutputRatio)
    matrix.postTranslate(drawX, drawY)

    canvas.drawBitmap(safeSource, matrix, bitmapPaint)

    canvas.restoreToCount(saveCount)

    return output
}

data class DrawnPath(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false
)

@Composable
fun CreativeDrawingCanvasSection(
    context: Context,
    repository: OrbitRepository,
    accentColor: Color,
    isServiceRunning: Boolean,
    onDrawingSaved: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var paths by remember { mutableStateOf(listOf<DrawnPath>()) }
    var currentPoints by remember { mutableStateOf(listOf<Offset>()) }
    var currentColor by remember { mutableStateOf(Color(0xFFFF6B35)) }
    var brushSize by remember { mutableStateOf(10f) }
    var isEraserMode by remember { mutableStateOf(false) }

    var canvasSizePx by remember { mutableStateOf(0f) }

    val presetColors = listOf(
        Color(0xFFFF6B35), // Signal Orange
        Color(0xFF00E5FF), // Neon Cyan
        Color(0xFFFF2A85), // Neon Pink
        Color(0xFF00E676), // Neon Green
        Color(0xFFFFD600), // Neon Yellow
        Color(0xFFFFFFFF), // White
        Color(0xFFD500F9), // Neon Purple
        Color(0xFF050508)  // Space Black
    )

    fun renderDrawnBitmap(): Bitmap {
        val outputSize = 256
        val boxPx = if (canvasSizePx > 0f) canvasSizePx else 600f
        val centerX = boxPx / 2f
        val centerY = boxPx / 2f
        val radiusPx = boxPx / 2f
        val cropLeft = centerX - radiusPx
        val cropTop = centerY - radiusPx
        val cropDiameter = radiusPx * 2f
        val scale = outputSize / cropDiameter

        val tempDrawing = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val tempCanvas = android.graphics.Canvas(tempDrawing)
        val linePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }

        paths.forEach { pathItem ->
            linePaint.strokeWidth = pathItem.strokeWidth * scale
            if (pathItem.isEraser) {
                linePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                linePaint.color = android.graphics.Color.TRANSPARENT
            } else {
                linePaint.xfermode = null
                linePaint.color = pathItem.color.toArgb()
            }

            if (pathItem.points.size > 1) {
                for (i in 0 until pathItem.points.size - 1) {
                    val p1 = pathItem.points[i]
                    val p2 = pathItem.points[i + 1]
                    val x1 = (p1.x - cropLeft) * scale
                    val y1 = (p1.y - cropTop) * scale
                    val x2 = (p2.x - cropLeft) * scale
                    val y2 = (p2.y - cropTop) * scale

                    tempCanvas.drawLine(x1, y1, x2, y2, linePaint)
                }
            }
        }

        val finalBitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val finalCanvas = android.graphics.Canvas(finalBitmap)
        val maskPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
        }
        finalCanvas.drawCircle(outputSize / 2f, outputSize / 2f, outputSize / 2f, maskPaint)

        val cropPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        finalCanvas.drawBitmap(tempDrawing, 0f, 0f, cropPaint)
        return finalBitmap
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0C14)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Brush, contentDescription = null, tint = Color(0xFFFF6B35), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CANVAS PAINTER",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = { if (paths.isNotEmpty()) paths = paths.dropLast(1) },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.White.copy(alpha = 0.06f), CircleShape)
                    ) {
                        Icon(imageVector = Icons.Default.Undo, contentDescription = "Undo", tint = Color.White, modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = { paths = emptyList() },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.White.copy(alpha = 0.06f), CircleShape)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear", tint = Color(0xFFFF453A), modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Interactive Circular Drawing Canvas Box
            BoxWithConstraints(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF050508))
                    .border(2.dp, Color(0xFFFF6B35).copy(alpha = 0.6f), CircleShape)
                    .pointerInput(isEraserMode, currentColor, brushSize) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                currentPoints = listOf(offset)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                currentPoints = currentPoints + change.position
                            },
                            onDragEnd = {
                                if (currentPoints.isNotEmpty()) {
                                    paths = paths + DrawnPath(
                                        points = currentPoints,
                                        color = if (isEraserMode) Color(0xFF050508) else currentColor,
                                        strokeWidth = brushSize,
                                        isEraser = isEraserMode
                                    )
                                    currentPoints = emptyList()
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                val wPx = with(density) { constraints.maxWidth.toFloat() }
                SideEffect {
                    canvasSizePx = wPx
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Draw saved paths
                    paths.forEach { pathItem ->
                        if (pathItem.points.size > 1) {
                            for (i in 0 until pathItem.points.size - 1) {
                                drawLine(
                                    color = pathItem.color,
                                    start = pathItem.points[i],
                                    end = pathItem.points[i + 1],
                                    strokeWidth = pathItem.strokeWidth,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }

                    // Draw active path
                    if (currentPoints.size > 1) {
                        for (i in 0 until currentPoints.size - 1) {
                            drawLine(
                                color = if (isEraserMode) Color(0xFF050508) else currentColor,
                                start = currentPoints[i],
                                end = currentPoints[i + 1],
                                strokeWidth = brushSize,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Brush Size & Eraser Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isEraserMode = !isEraserMode },
                    modifier = Modifier
                        .size(38.dp)
                        .background(if (isEraserMode) Color(0xFFFF6B35) else Color.White.copy(alpha = 0.08f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = "Eraser Mode",
                        tint = if (isEraserMode) Color.Black else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = if (isEraserMode) "Eraser Size" else "Brush Thickness", fontSize = 11.sp, color = Color(0xFF8E94A8))
                        Text(text = "${brushSize.toInt()} px", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B35))
                    }
                    Slider(
                        value = brushSize,
                        onValueChange = { brushSize = it },
                        valueRange = 2f..36f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF6B35),
                            activeTrackColor = Color(0xFFFF6B35)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Neon Palette Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                presetColors.forEach { color ->
                    val isSelected = !isEraserMode && currentColor == color
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f),
                                shape = CircleShape
                            )
                            .clickable {
                                currentColor = color
                                isEraserMode = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(if (color == Color.White) Color.Black else Color.White, CircleShape)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions: Save to Collection & Apply Drawing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Save to Collection
                OutlinedButton(
                    onClick = {
                        try {
                            val finalBitmap = renderDrawnBitmap()
                            val artworksDir = File(context.filesDir, "artworks").apply { if (!exists()) mkdirs() }
                            val timestamp = System.currentTimeMillis()
                            val artworkFile = File(artworksDir, "canvas_$timestamp.png")
                            FileOutputStream(artworkFile).use { out ->
                                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            coroutineScope.launch(Dispatchers.IO) {
                                repository.insertArtwork(
                                    ArtworkEntry(
                                        title = "Canvas Art #${timestamp % 10000}",
                                        filePath = artworkFile.absolutePath,
                                        type = "CANVAS"
                                    )
                                )
                            }
                            Toast.makeText(context, context.getString(R.string.saved_to_collection), Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(context, context.getString(R.string.failed_to_render_drawing), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6B35).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.Collections, contentDescription = null, tint = Color(0xFFFF6B35), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(id = R.string.save_to_collection), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B35))
                }

                // Apply Drawing to Bubble
                Button(
                    onClick = {
                        try {
                            val finalBitmap = renderDrawnBitmap()

                            val file = File(context.filesDir, "custom_bubble_icon.png")
                            FileOutputStream(file).use { out ->
                                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }

                            val artworksDir = File(context.filesDir, "artworks").apply { if (!exists()) mkdirs() }
                            val timestamp = System.currentTimeMillis()
                            val artworkFile = File(artworksDir, "canvas_$timestamp.png")
                            FileOutputStream(artworkFile).use { out ->
                                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            coroutineScope.launch(Dispatchers.IO) {
                                repository.insertArtwork(
                                    ArtworkEntry(
                                        title = "Canvas Art #${timestamp % 10000}",
                                        filePath = artworkFile.absolutePath,
                                        type = "CANVAS"
                                    )
                                )
                            }

                            ThemePreferences.setBubbleSymbol(context, "custom")
                            Toast.makeText(context, context.getString(R.string.drawing_saved_to_bubble), Toast.LENGTH_SHORT).show()
                            onDrawingSaved()

                            if (isServiceRunning) {
                                val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                                    action = FloatingLauncherService.ACTION_UPDATE_THEME
                                }
                                context.startService(serviceIntent)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(context, context.getString(R.string.failed_to_render_drawing), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(id = R.string.apply_drawing), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StudioTabContent(
    context: Context,
    accentColor: Color,
    activeTheme: NeonTheme,
    onThemeChanged: (NeonTheme) -> Unit,
    isServiceRunning: Boolean,
    onRequestNotification: () -> Unit = {},
    isNotificationGranted: Boolean = true,
    initialStudioMode: Int = 0
) {
    val coroutineScope = rememberCoroutineScope()
    val database = remember { OrbitDatabase.getDatabase(context) }
    val repository = remember { OrbitRepository(database) }

    val scrollState = rememberScrollState()
    var selectedSymbol by remember { mutableStateOf(ThemePreferences.getBubbleSymbol(context)) }
    var bubbleSize by remember { mutableStateOf(ThemePreferences.getBubbleSize(context).toFloat()) }
    var glowIntensity by remember { mutableStateOf(ThemePreferences.getGlowIntensity(context)) }
    var bubbleOpacity by remember { mutableStateOf(ThemePreferences.getBubbleOpacity(context)) }
    var borderStroke by remember { mutableStateOf(ThemePreferences.getBorderStrokeDp(context).toFloat()) }
    var innerTintColor by remember { mutableStateOf(ThemePreferences.getInnerTintColor(context)) }
    var studioMode by remember { mutableStateOf(initialStudioMode) } // 0 = Presets & Upload, 1 = Canvas Painter, 2 = My Collection, 3 = Shortcut Notification
    var showSavedMessage by remember { mutableStateOf(false) }

    LaunchedEffect(initialStudioMode) {
        if (initialStudioMode != 0) {
            studioMode = initialStudioMode
        }
    }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var customImageVersion by remember { mutableStateOf(0) }

    val signalOrange = Color(0xFFFF6B35)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF5A6178)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedImageUri = it }
    }

    val customBitmap = remember(selectedSymbol, customImageVersion) {
        if (selectedSymbol == "custom") ThemePreferences.loadCustomBubbleBitmap(context) else null
    }

    val innerTintPresetColors = listOf(
        0L to "Default",
        0xFFFF6B35 to "Signal Orange",
        0xFF00E5FF to "Neon Cyan",
        0xFFFFD600 to "Neon Yellow",
        0xFFFF2A85 to "Neon Pink",
        0xFF00E676 to "Neon Green",
        0xFFFFFFFF to "White",
        0xFFD500F9 to "Neon Purple"
    )

    if (selectedImageUri != null) {
        CircularCropDialog(
            imageUri = selectedImageUri!!,
            context = context,
            accentColor = accentColor,
            onDismiss = { selectedImageUri = null },
            onCropped = { croppedBitmap ->
                try {
                    val file = File(context.filesDir, "custom_bubble_icon.png")
                    FileOutputStream(file).use { out ->
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }

                    // Also save to My Collection
                    val artworksDir = File(context.filesDir, "artworks").apply { if (!exists()) mkdirs() }
                    val timestamp = System.currentTimeMillis()
                    val artworkFile = File(artworksDir, "photo_$timestamp.png")
                    FileOutputStream(artworkFile).use { out ->
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    coroutineScope.launch(Dispatchers.IO) {
                        repository.insertArtwork(
                            ArtworkEntry(
                                title = "Photo Crop #${timestamp % 10000}",
                                filePath = artworkFile.absolutePath,
                                type = "PHOTO_CROP"
                            )
                        )
                    }

                    selectedSymbol = "custom"
                    ThemePreferences.setBubbleSymbol(context, "custom")
                    customImageVersion++
                    Toast.makeText(context, context.getString(R.string.bubble_saved_toast), Toast.LENGTH_SHORT).show()

                    if (isServiceRunning) {
                        val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                            action = FloatingLauncherService.ACTION_UPDATE_THEME
                        }
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, context.getString(R.string.bubble_crop_failed_toast), Toast.LENGTH_SHORT).show()
                }
                selectedImageUri = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .then(if (studioMode != 2 && studioMode != 3) Modifier.verticalScroll(scrollState) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(id = R.string.floating_bubble_header),
                    color = signalOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(id = R.string.tab_studio),
                    color = inkLight,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4-Tab Mode Switcher Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0D0E15))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val tabs = listOf(
                0 to stringResource(id = R.string.presets),
                1 to stringResource(id = R.string.canvas),
                2 to stringResource(id = R.string.tab_collection),
                3 to stringResource(id = R.string.tab_shortcut_notif)
            )
            tabs.forEach { (modeIdx, title) ->
                val isSelected = studioMode == modeIdx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) signalOrange.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { studioMode = modeIdx }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) signalOrange else inkDim,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (studioMode == 2) {
            // Dedicated "My Collection" visual gallery area
            StudioCollectionSection(
                context = context,
                repository = repository,
                accentColor = signalOrange,
                isServiceRunning = isServiceRunning,
                onNavigateToCanvas = { studioMode = 1 },
                onNavigateToUpload = { imagePickerLauncher.launch("image/*") }
            )
        } else if (studioMode == 3) {
            // Dedicated "Shortcut Notification" visual configuration section
            ShortcutNotificationScreen(
                onDismiss = null,
                onRequestNotificationPermission = onRequestNotification,
                isNotificationPermissionGranted = isNotificationGranted,
                isEmbedded = true
            )
        } else {

        Spacer(modifier = Modifier.height(16.dp))

        // Live Glassmorphic Bubble Stage Preview
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, signalOrange.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.live_bubble_preview),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = signalOrange,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${bubbleSize.toInt()}dp • ${(bubbleOpacity * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = inkDim
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Box(
                    modifier = Modifier
                        .size(bubbleSize.dp.coerceIn(40.dp, 100.dp))
                        .alpha(bubbleOpacity.coerceIn(0.1f, 1.0f)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val minDim = min(size.width, size.height)
                        val strokeWidthPx = borderStroke.dp.toPx()

                        // Radial gradient glow inside
                        if (glowIntensity > 0f) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(accentColor.copy(alpha = glowIntensity), Color.Transparent),
                                    center = center,
                                    radius = minDim / 2f
                                ),
                                radius = minDim / 2f,
                                center = center
                            )
                        }

                        // Outer border stroke fitting INSIDE bounds
                        if (strokeWidthPx > 0f) {
                            val radius = (minDim / 2f) - (strokeWidthPx / 2f)
                            if (radius > 0f) {
                                drawCircle(
                                    color = accentColor,
                                    radius = radius,
                                    center = center,
                                    style = Stroke(width = strokeWidthPx)
                                )
                            }
                        }
                    }
                    if (selectedSymbol == "custom" && customBitmap != null) {
                        Image(
                            bitmap = customBitmap.asImageBitmap(),
                            contentDescription = "Custom Preview Icon",
                            modifier = Modifier
                                .fillMaxSize(0.72f)
                                .clip(CircleShape)
                                .graphicsLayer(alpha = (0.3f + (0.7f * glowIntensity)).coerceIn(0.3f, 1.0f))
                        )
                    } else {
                        val innerFilter = if (innerTintColor != 0L) ColorFilter.tint(Color(innerTintColor)) else null
                        Image(
                            painter = safePainterResource(id = ThemePreferences.getBubbleIconDrawableRes(selectedSymbol)),
                            contentDescription = "Preview Icon",
                            colorFilter = innerFilter,
                            modifier = Modifier
                                .fillMaxSize(0.65f)
                                .graphicsLayer(alpha = (0.3f + (0.7f * glowIntensity)).coerceIn(0.3f, 1.0f))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (studioMode == 1) {
            // Interactive Drawing Canvas Mode
            CreativeDrawingCanvasSection(
                context = context,
                repository = repository,
                accentColor = accentColor,
                isServiceRunning = isServiceRunning,
                onDrawingSaved = {
                    selectedSymbol = "custom"
                    customImageVersion++
                }
            )
        } else {
            // Presets & Upload Mode
            // Upload Custom Image Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, signalOrange.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                    .clickable { imagePickerLauncher.launch("image/*") },
                colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(signalOrange.copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, signalOrange, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddAPhoto,
                            contentDescription = stringResource(id = R.string.upload_custom_image),
                            tint = signalOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.upload_custom_image),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(id = R.string.upload_custom_image_desc),
                            fontSize = 11.sp,
                            color = inkDim
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = inkDim
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Icon & Vector Symbol Selector
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(id = R.string.bubble_vector_symbol),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = signalOrange,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ThemePreferences.bubbleIcons.take(4).forEach { iconItem ->
                            val isSelected = selectedSymbol == iconItem.id
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) signalOrange.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (isSelected) signalOrange else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .clickable { selectedSymbol = iconItem.id },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = safePainterResource(id = iconItem.drawableRes),
                                    contentDescription = iconItem.name,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        if (ThemePreferences.isCustomBubbleImageAvailable(context)) {
                            val isSelected = selectedSymbol == "custom"
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) signalOrange.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (isSelected) signalOrange else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .clickable { selectedSymbol = "custom" },
                                contentAlignment = Alignment.Center
                            ) {
                                val thumbBitmap = remember(customImageVersion) { ThemePreferences.loadCustomBubbleBitmap(context) }
                                if (thumbBitmap != null) {
                                    Image(
                                        bitmap = thumbBitmap.asImageBitmap(),
                                        contentDescription = "Custom Image",
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = "Custom Image",
                                        tint = signalOrange,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ThemePreferences.bubbleIcons.drop(4).take(4).forEach { iconItem ->
                            val isSelected = selectedSymbol == iconItem.id
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) signalOrange.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (isSelected) signalOrange else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .clickable { selectedSymbol = iconItem.id },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = safePainterResource(id = iconItem.drawableRes),
                                    contentDescription = iconItem.name,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Inner Content Tint Customization
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.inner_element_tint),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = signalOrange,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (innerTintColor == 0L) stringResource(id = R.string.default_label) else stringResource(id = R.string.custom_tint),
                            fontSize = 10.sp,
                            color = inkDim
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        innerTintPresetColors.forEach { (colorVal, name) ->
                            val isSelected = innerTintColor == colorVal
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (colorVal == 0L) Color.White.copy(alpha = 0.15f) else Color(colorVal))
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) signalOrange else Color.White.copy(alpha = 0.15f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        innerTintColor = colorVal
                                        ThemePreferences.setInnerTintColor(context, colorVal)
                                        if (isServiceRunning) {
                                            val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                                                action = FloatingLauncherService.ACTION_UPDATE_THEME
                                            }
                                            context.startService(serviceIntent)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (colorVal == 0L) {
                                    Text(text = "D", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                } else if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(if (colorVal == 0xFFFFFFFFL) Color.Black else Color.White, CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Compact Grid Control Sliders (2 Columns)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Size Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(id = R.string.bubble_size), fontSize = 11.sp, color = inkDim)
                            Text(text = "${bubbleSize.toInt()}dp", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = signalOrange)
                        }
                        Slider(
                            value = bubbleSize,
                            onValueChange = { bubbleSize = it },
                            valueRange = 40f..96f,
                            colors = SliderDefaults.colors(thumbColor = signalOrange, activeTrackColor = signalOrange)
                        )
                    }
                }

                // Opacity Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(id = R.string.opacity), fontSize = 11.sp, color = inkDim)
                            Text(text = "${(bubbleOpacity * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = signalOrange)
                        }
                        Slider(
                            value = bubbleOpacity,
                            onValueChange = { bubbleOpacity = it },
                            valueRange = 0.1f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = signalOrange, activeTrackColor = signalOrange)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Glow Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(id = R.string.glow_intensity), fontSize = 11.sp, color = inkDim)
                            Text(text = "${(glowIntensity * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = signalOrange)
                        }
                        Slider(
                            value = glowIntensity,
                            onValueChange = { glowIntensity = it },
                            valueRange = 0.1f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = signalOrange, activeTrackColor = signalOrange)
                        )
                    }
                }

                // Stroke Card
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(id = R.string.border_stroke), fontSize = 11.sp, color = inkDim)
                            Text(text = "${borderStroke.toInt()}dp", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = signalOrange)
                        }
                        Slider(
                            value = borderStroke,
                            onValueChange = { newValue ->
                                borderStroke = newValue
                                ThemePreferences.setBorderStrokeDp(context, newValue.toInt())
                                if (isServiceRunning) {
                                    val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                                        action = FloatingLauncherService.ACTION_UPDATE_THEME
                                    }
                                    context.startService(serviceIntent)
                                }
                            },
                            valueRange = 0f..8f,
                            colors = SliderDefaults.colors(thumbColor = signalOrange, activeTrackColor = signalOrange)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Outer Theme Accent Palette Selector
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0x0CFFFFFF)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(id = R.string.theme_glow_palette),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = signalOrange,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ThemePreferences.themes.forEach { theme ->
                            val isSelected = theme.id == activeTheme.id
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color(theme.colorValue), CircleShape)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        ThemePreferences.setSelectedTheme(context, theme.id)
                                        onThemeChanged(theme)
                                        if (isServiceRunning) {
                                            val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                                                action = FloatingLauncherService.ACTION_UPDATE_THEME
                                            }
                                            context.startService(serviceIntent)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Save Configurations Action Button
            Button(
                onClick = {
                    ThemePreferences.setBubbleSymbol(context, selectedSymbol)
                    ThemePreferences.setBubbleSize(context, bubbleSize.toInt())
                    ThemePreferences.setGlowIntensity(context, glowIntensity)
                    ThemePreferences.setBubbleOpacity(context, bubbleOpacity)
                    ThemePreferences.setBorderStrokeDp(context, borderStroke.toInt())
                    ThemePreferences.setInnerTintColor(context, innerTintColor)
                    showSavedMessage = true
                    Toast.makeText(context, context.getString(R.string.studio_config_saved), Toast.LENGTH_SHORT).show()
                    if (isServiceRunning) {
                        val serviceIntent = Intent(context, FloatingLauncherService::class.java).apply {
                            action = FloatingLauncherService.ACTION_UPDATE_THEME
                        }
                        context.startService(serviceIntent)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = signalOrange,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.save_studio_config),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
fun ToolsTabContent(
    context: Context,
    accentColor: Color
) {
    val scrollState = rememberScrollState()
    val signalOrange = Color(0xFFFF6B35)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF5A6178)

    var refreshCounter by remember { mutableStateOf(0) }

    val tools = listOf(
        Triple(stringResource(id = R.string.tool_launcher_name), stringResource(id = R.string.app_desc), ToolsPreferences.KEY_LAUNCHPAD) to Icons.Default.Apps,
        Triple(stringResource(id = R.string.tool_vault_name), stringResource(id = R.string.vault_desc), ToolsPreferences.KEY_VAULT) to Icons.Default.Lock,
        Triple(stringResource(id = R.string.tool_calc_name), stringResource(id = R.string.calc_desc), ToolsPreferences.KEY_CALCULATOR) to Icons.Default.Calculate,
        Triple(stringResource(id = R.string.tool_ocr_name), stringResource(id = R.string.ocr_desc), ToolsPreferences.KEY_OCR) to Icons.Default.CameraAlt,
        Triple(stringResource(id = R.string.tool_dial_name), stringResource(id = R.string.dial_desc), ToolsPreferences.KEY_SPEED_DIAL) to Icons.Default.Link,
        Triple(stringResource(id = R.string.tool_browser_name), stringResource(id = R.string.browser_desc), ToolsPreferences.KEY_BROWSER) to Icons.Default.Language
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(id = R.string.tools_track_header),
                    color = signalOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(id = R.string.tab_tools),
                    color = inkLight,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Glassmorphic List Container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            @Suppress("UNUSED_VARIABLE")
            val trigger = refreshCounter

            tools.forEachIndexed { index, (info, icon) ->
                val (title, subtitle, key) = info
                val isPermanent = (key == ToolsPreferences.KEY_LAUNCHPAD || key == ToolsPreferences.KEY_OCR)
                val isEnabled = if (isPermanent) true else ToolsPreferences.isToolEnabled(context, key)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isEnabled) signalOrange.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = if (isEnabled) signalOrange else inkDim,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(13.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            color = inkDim,
                            fontSize = 11.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (isPermanent) {
                        // The option to disable OCR and App Launcher is deleted.
                        // Instead, display locked "Always Active" badge.
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(signalOrange.copy(alpha = 0.14f))
                                .border(1.dp, signalOrange.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = signalOrange,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = stringResource(id = R.string.tool_always_active),
                                    color = signalOrange,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    } else {
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                ToolsPreferences.setToolEnabled(context, key, checked)
                                refreshCounter++
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

                if (index < tools.size - 1) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
fun SettingsTabContent(
    context: Context,
    accentColor: Color,
    currentUsername: String = ThemePreferences.getUsername(context).ifBlank { "User" },
    onUsernameChanged: (String) -> Unit = {},
    currentLanguage: String,
    onLanguageChanged: (String) -> Unit,
    isOverlayGranted: Boolean,
    isUsageGranted: Boolean,
    isNotificationGranted: Boolean,
    isUsageSkipped: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit,
    onRequestNotification: () -> Unit,
    onSkipUsage: (Boolean) -> Unit,
    onRestartLiveTour: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    val signalOrange = Color(0xFFFF6B35)
    val inkLight = Color(0xFFEEF0F6)
    val inkDim = Color(0xFF5A6178)
    val isServiceRunning by FloatingLauncherService.isServiceRunning.collectAsState()

    var showChangeUsernameDialog by remember { mutableStateOf(false) }
    var selectedEngine by remember { mutableStateOf(SearchEnginePreferences.getSelectedEngine(context)) }
    var showSearchEngineDialog by remember { mutableStateOf(false) }

    if (showChangeUsernameDialog) {
        ChangeUsernameDialog(
            currentUsername = currentUsername,
            onDismiss = { showChangeUsernameDialog = false },
            onSave = { newName ->
                ThemePreferences.setUsername(context, newName)
                onUsernameChanged(newName)
                showChangeUsernameDialog = false
                Toast.makeText(context, context.getString(R.string.username_updated_toast), Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showSearchEngineDialog) {
        SearchEngineDialog(
            selectedEngineId = selectedEngine.id,
            onDismiss = { showSearchEngineDialog = false },
            onSelectEngine = { newEngine ->
                SearchEnginePreferences.setSelectedEngine(context, newEngine.id)
                selectedEngine = newEngine
                showSearchEngineDialog = false
                Toast.makeText(context, context.getString(R.string.search_engine_selected_toast, newEngine.name), Toast.LENGTH_SHORT).show()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TRACK 03 · OUTER",
                    color = signalOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Setup",
                    color = inkLight,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Options List (Clean list items with dividers)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            // 0. Change Username Card
            SetupOptionRow(
                title = stringResource(id = R.string.change_username),
                subtitle = stringResource(id = R.string.current_username_prefix, currentUsername),
                icon = Icons.Default.Edit,
                iconColor = signalOrange,
                iconBgColor = signalOrange.copy(alpha = 0.15f),
                onClick = { showChangeUsernameDialog = true },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = inkDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            if (onRestartLiveTour != null) {
                // Interactive App Tour Replay Option
                SetupOptionRow(
                    title = stringResource(id = R.string.live_tour_replay_setting),
                    subtitle = stringResource(id = R.string.live_tour_replay_setting_desc),
                    icon = Icons.Default.Explore,
                    iconColor = signalOrange,
                    iconBgColor = signalOrange.copy(alpha = 0.15f),
                    onClick = onRestartLiveTour,
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = inkDim,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
            }

            // Display over other apps permission
            SetupOptionRow(
                title = stringResource(id = R.string.display_over_apps),
                subtitle = if (isOverlayGranted) stringResource(id = R.string.display_over_apps_desc) else stringResource(id = R.string.display_over_apps_desc),
                icon = Icons.Default.Layers,
                iconColor = if (isOverlayGranted) signalOrange else inkDim,
                iconBgColor = if (isOverlayGranted) signalOrange.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                onClick = { if (!isOverlayGranted) onRequestOverlay() },
                trailingContent = {
                    Switch(
                        checked = isOverlayGranted,
                        onCheckedChange = { onRequestOverlay() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = signalOrange,
                            uncheckedThumbColor = inkDim,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Usage stats permission
            SetupOptionRow(
                title = stringResource(id = R.string.usage_stats),
                subtitle = if (isUsageGranted) stringResource(id = R.string.usage_stats_desc) else if (isUsageSkipped) stringResource(id = R.string.permission_skipped) else stringResource(id = R.string.usage_stats_desc),
                icon = Icons.Default.Analytics,
                iconColor = if (isUsageGranted) signalOrange else inkDim,
                iconBgColor = if (isUsageGranted) signalOrange.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                onClick = { if (!isUsageGranted) onRequestUsage() },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!isUsageGranted) {
                            TextButton(
                                onClick = {
                                    onSkipUsage(!isUsageSkipped)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isUsageSkipped) stringResource(id = R.string.permission_skipped) else stringResource(id = R.string.permission_skip),
                                    color = if (isUsageSkipped) Color(0xFF8E94A8) else signalOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Switch(
                            checked = isUsageGranted || isUsageSkipped,
                            onCheckedChange = { checked ->
                                if (!checked) {
                                    onSkipUsage(false)
                                } else {
                                    onRequestUsage()
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
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Foreground notifications permission
            SetupOptionRow(
                title = stringResource(id = R.string.foreground_notifications),
                subtitle = if (isNotificationGranted) stringResource(id = R.string.foreground_notifications_desc) else stringResource(id = R.string.foreground_notifications_desc),
                icon = Icons.Default.Notifications,
                iconColor = if (isNotificationGranted) signalOrange else inkDim,
                iconBgColor = if (isNotificationGranted) signalOrange.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                onClick = { if (!isNotificationGranted) onRequestNotification() },
                trailingContent = {
                    Switch(
                        checked = isNotificationGranted,
                        onCheckedChange = { onRequestNotification() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = signalOrange,
                            uncheckedThumbColor = inkDim,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Language Selector Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = inkDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.select_language),
                        color = Color.White,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(id = R.string.select_language_desc),
                        color = Color(0xFF5A6178),
                        fontSize = 11.5.sp
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("en" to "EN", "ar" to "AR", "fa" to "FA").forEach { (code, label) ->
                        val isSelected = currentLanguage == code
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) signalOrange.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.03f))
                                .border(
                                    1.dp,
                                    if (isSelected) signalOrange else Color.White.copy(alpha = 0.08f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onLanguageChanged(code) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) signalOrange else Color(0xFF8E94A8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Search Engine Selector Row
            SetupOptionRow(
                title = stringResource(id = R.string.search_engine_title),
                subtitle = selectedEngine.name,
                icon = Icons.Default.Search,
                iconColor = signalOrange,
                iconBgColor = signalOrange.copy(alpha = 0.15f),
                onClick = { showSearchEngineDialog = true },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = selectedEngine.name,
                            color = signalOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = inkDim,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Bubble Size adjustment
            var currentBubbleSize by remember { mutableStateOf(ThemePreferences.getBubbleSize(context).toFloat()) }

            SetupOptionRow(
                title = stringResource(id = R.string.bubble_size_title),
                subtitle = stringResource(id = R.string.bubble_size_dp, currentBubbleSize.toInt()),
                icon = Icons.Default.RadioButtonUnchecked,
                iconColor = signalOrange,
                iconBgColor = signalOrange.copy(alpha = 0.15f),
                trailingContent = {
                    Box(modifier = Modifier.width(110.dp)) {
                        Slider(
                            value = currentBubbleSize,
                            onValueChange = { newSize ->
                                currentBubbleSize = newSize
                                ThemePreferences.setBubbleSize(context, newSize.toInt())
                                val intent = Intent(context, FloatingLauncherService::class.java).apply {
                                    action = FloatingLauncherService.ACTION_UPDATE_THEME
                                }
                                context.startService(intent)
                            },
                            valueRange = 40f..90f,
                            colors = SliderDefaults.colors(
                                thumbColor = signalOrange,
                                activeTrackColor = signalOrange,
                                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Cache & Storage Management
            var cacheSizeFormatted by remember { mutableStateOf(CacheManager.getFormattedCacheSize(context)) }
            var isClearingCache by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val size = CacheManager.getFormattedCacheSize(context)
                    withContext(Dispatchers.Main) {
                        cacheSizeFormatted = size
                    }
                }
            }

            SetupOptionRow(
                title = stringResource(id = R.string.cache_management_title),
                subtitle = stringResource(id = R.string.cache_management_subtitle, cacheSizeFormatted),
                icon = Icons.Default.CleaningServices,
                iconColor = signalOrange,
                iconBgColor = signalOrange.copy(alpha = 0.15f),
                trailingContent = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(signalOrange.copy(alpha = if (isClearingCache) 0.1f else 0.2f))
                            .border(
                                1.dp,
                                signalOrange.copy(alpha = if (isClearingCache) 0.3f else 0.8f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !isClearingCache) {
                                isClearingCache = true
                                CacheManager.clearCacheNow(context) { freedMb ->
                                    isClearingCache = false
                                    cacheSizeFormatted = CacheManager.getFormattedCacheSize(context)
                                    val freedStr = if (freedMb < 1.0) {
                                        String.format(Locale.US, "%.0f KB", freedMb * 1024.0)
                                    } else {
                                        String.format(Locale.US, "%.1f MB", freedMb)
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.cache_cleared_toast, freedStr),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isClearingCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = signalOrange,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = stringResource(id = R.string.clear_cache_button),
                                color = signalOrange,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Wi-Fi & Network Monitor Configuration
            var showSettingsWifiDialog by remember { mutableStateOf(false) }

            if (showSettingsWifiDialog) {
                WifiMonitorSettingsDialog(
                    context = context,
                    onDismiss = { showSettingsWifiDialog = false }
                )
            }

            val currentLimitMb = remember(showSettingsWifiDialog) { WifiMonitorPreferences.getDailyLimitMb(context) }
            val currentDisplayMode = remember(showSettingsWifiDialog) { WifiMonitorPreferences.getDisplayMode(context) }
            val displayModeLabel = when (currentDisplayMode) {
                WifiMonitorPreferences.DISPLAY_SPEED_ONLY -> stringResource(id = R.string.display_mode_speed)
                WifiMonitorPreferences.DISPLAY_USAGE_ONLY -> stringResource(id = R.string.display_mode_usage)
                else -> stringResource(id = R.string.display_mode_all)
            }

            SetupOptionRow(
                title = stringResource(id = R.string.wifi_monitor_title),
                subtitle = if (currentLimitMb > 0f) "${currentLimitMb.toInt()} MB Limit · $displayModeLabel" else "${stringResource(id = R.string.daily_limit_none)} · $displayModeLabel",
                icon = Icons.Default.Wifi,
                iconColor = Color(0xFF00F0FF),
                iconBgColor = Color(0xFF00F0FF).copy(alpha = 0.15f),
                onClick = { showSettingsWifiDialog = true },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = inkDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
fun SetupOptionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color = Color(0xFF8E94A8),
    iconBgColor: Color = Color.White.copy(alpha = 0.05f),
    titleColor: Color = Color.White,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFF5A6178),
                fontSize = 11.5.sp
            )
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}

@Composable
fun OnboardingScreen(
    onDismiss: () -> Unit,
    currentLanguage: String,
    onLanguageChanged: (String) -> Unit,
    accentColor: Color
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepDark)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Top Language Selector row
        Text(
            text = stringResource(id = R.string.select_language),
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Triple("en", "English 🇺🇸", "ltr"),
                Triple("ar", "العربية 🇸🇦", "rtl"),
                Triple("fa", "فارسی 🇮🇷", "rtl")
            ).forEach { (code, name, _) ->
                val isSelected = currentLanguage == code
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) accentColor else CardDark)
                        .border(1.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .clickable {
                            onLanguageChanged(code)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Animated circular Logo
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.4f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
                .border(2.dp, accentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = safePainterResource(id = R.drawable.ic_orbit_neon),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Onboarding Title
        Text(
            text = stringResource(id = R.string.onboarding_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Funny Tagline
        Text(
            text = stringResource(id = R.string.onboarding_funny_tagline),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Description
        Text(
            text = stringResource(id = R.string.onboarding_simple_desc),
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Safety/Privacy Note Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x3300E676), RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0x0C00E676)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Safe",
                    tint = GreenSuccess,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(id = R.string.onboarding_privacy_note),
                    color = Color(0xFFD0FFD0),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Buttons: Get Started
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Get Started Button
            Button(
                onClick = {
                    ThemePreferences.setFirstRun(context, false)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.onboarding_get_started),
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun UsernameOnboardingScreen(
    accentColor: Color,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    var usernameText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val signalOrange = Color(0xFFFF6B35)
    val inputDarkBg = Color(0xFF0D0E15)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepDark)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Dark glassmorphic card container with Signal Orange accent outline
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(inputDarkBg)
                .border(1.5.dp, signalOrange, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "User Profile",
                tint = signalOrange,
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(id = R.string.welcome_to_orbit),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = signalOrange,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(id = R.string.choose_username),
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(id = R.string.username_hint),
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = usernameText,
            onValueChange = {
                usernameText = it
                if (errorMessage != null && it.trim().isNotEmpty()) {
                    errorMessage = null
                }
            },
            label = { Text(stringResource(id = R.string.username_label), color = TextSecondary) },
            placeholder = { Text("e.g. 𝑽𝑬𝑵𝑶𝑴, ♡, Alex", color = TextSecondary.copy(alpha = 0.5f)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = errorMessage != null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = inputDarkBg,
                unfocusedContainerColor = inputDarkBg,
                focusedBorderColor = signalOrange,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = signalOrange,
                focusedLabelColor = signalOrange
            ),
            shape = RoundedCornerShape(14.dp)
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = Color(0xFFFF5252),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        val invalidUsernameError = stringResource(id = R.string.enter_valid_username)
        Button(
            onClick = {
                val trimmed = usernameText.trim()
                if (trimmed.isEmpty()) {
                    errorMessage = invalidUsernameError
                } else {
                    ThemePreferences.setUsername(context, trimmed)
                    onFinished()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = signalOrange),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = stringResource(id = R.string.continue_btn),
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }
    }
}

fun isResourceResolvable(context: android.content.Context, @androidx.annotation.DrawableRes id: Int): Boolean {
    val isRobolectric = android.os.Build.FINGERPRINT == "robolectric" ||
                        android.os.Build.FINGERPRINT.startsWith("robolectric") ||
                        android.os.Build.DEVICE == "robolectric" ||
                        android.os.Build.HARDWARE == "robolectric"
    if (isRobolectric) {
        return false
    }
    return try {
        androidx.core.content.ContextCompat.getDrawable(context, id) != null
    } catch (e: Throwable) {
        false
    }
}

@Composable
fun safePainterResource(@androidx.annotation.DrawableRes id: Int): androidx.compose.ui.graphics.painter.Painter {
    val context = LocalContext.current
    val resolvable = remember(id) { isResourceResolvable(context, id) }
    return if (resolvable) {
        painterResource(id)
    } else {
        remember {
            object : androidx.compose.ui.graphics.painter.Painter() {
                override val intrinsicSize: androidx.compose.ui.geometry.Size = androidx.compose.ui.geometry.Size.Unspecified
                override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
                    drawCircle(color = Color.Gray, radius = size.minDimension / 2)
                }
            }
        }
    }
}

@Composable
fun ChangeUsernameDialog(
    currentUsername: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var usernameText by remember { mutableStateOf(currentUsername) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val signalOrange = Color(0xFFFF6B35)
    val inputDarkBg = Color(0xFF0D0E15)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF0D0E15))
                .border(1.dp, signalOrange.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                .padding(22.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(signalOrange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Username",
                            tint = signalOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(id = R.string.change_username),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(id = R.string.utf8_support_hint),
                            color = Color(0xFF8E94A8),
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = usernameText,
                    onValueChange = {
                        usernameText = it
                        if (errorMessage != null && it.trim().isNotEmpty()) {
                            errorMessage = null
                        }
                    },
                    label = { Text(stringResource(id = R.string.username_label), color = Color(0xFF8E94A8)) },
                    placeholder = { Text("e.g. 𝑽𝑬𝑵𝑶𝑴, ♡, Alex", color = Color(0xFF5A6178)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = inputDarkBg,
                        unfocusedContainerColor = inputDarkBg,
                        focusedBorderColor = signalOrange,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = signalOrange,
                        focusedLabelColor = signalOrange
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.cancel),
                            color = Color(0xFF8E94A8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val invalidUsernameError = stringResource(id = R.string.enter_valid_username)
                    Button(
                        onClick = {
                            val trimmed = usernameText.trim()
                            if (trimmed.isEmpty()) {
                                errorMessage = invalidUsernameError
                            } else {
                                onSave(trimmed)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = signalOrange,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.save_changes),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchEngineDialog(
    selectedEngineId: String,
    onDismiss: () -> Unit,
    onSelectEngine: (SearchEngine) -> Unit
) {
    val signalOrange = Color(0xFFFF6B35)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = R.string.search_engine_title),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.search_engine_desc),
                    color = Color(0xFF8E94A8),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                SearchEnginePreferences.ENGINES.forEach { engine ->
                    val isSelected = engine.id == selectedEngineId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) signalOrange.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
                            .border(
                                1.dp,
                                if (isSelected) signalOrange.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                onSelectEngine(engine)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = engine.name,
                                color = if (isSelected) signalOrange else Color.White,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp
                            )
                            Text(
                                text = engine.homeUrl.removePrefix("https://").removePrefix("www."),
                                color = Color(0xFF5A6178),
                                fontSize = 11.sp
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = signalOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.cancel), color = signalOrange, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF161722),
        shape = RoundedCornerShape(18.dp)
    )
}

