package com.homekids.child

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import coil.compose.AsyncImage
import com.homekids.child.api.ApiClient
import com.homekids.child.model.ProfileResponse
import com.homekids.child.model.ActivityRequest
import com.homekids.child.service.deviceregistration.DeviceRegistration
import com.homekids.child.service.deviceregistration.ActivityUploader
import com.homekids.child.service.LocalStatsManager
import com.homekids.child.service.DailyResetWorker
import com.homekids.child.ui.theme.HomeKidsChildTheme
import com.homekids.child.vpn.HomeKidsVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

// Premium Color Palette
val BrandPrimary = Color(0xFF6366F1) 
val BrandBg = Color(0xFFF9FAFB)
val CardWhite = Color(0xFFFFFFFF)
val TextDark = Color(0xFF111827)
val TextMuted = Color(0xFF6B7280)
val StatusGreen = Color(0xFF10B981)
val StatusRed = Color(0xFFEF4444)
val StatusOrange = Color(0xFFF59E0B)

class HomeKidsViewModel : ViewModel() {
    private val _profile = MutableStateFlow<ProfileResponse?>(null)
    val profile: StateFlow<ProfileResponse?> = _profile

    private val _screenTimeMinutes = MutableStateFlow(0)
    val screenTimeMinutes: StateFlow<Int> = _screenTimeMinutes

    private val _activityLogs = MutableStateFlow<List<ActivityRequest>>(emptyList())
    val activityLogs: StateFlow<List<ActivityRequest>> = _activityLogs
    
    private val _localStats = MutableStateFlow<HomeKidsViewModel.LocalStats?>(null)
    val localStats: StateFlow<HomeKidsViewModel.LocalStats?> = _localStats

    data class LocalStats(
        val blockedCount: Int,
        val topApps: List<LocalStatsManager.AppStat>,
        val categoryBreakdown: Map<String, Int>,
        val lastSync: Long
    )

    fun loadData(context: android.content.Context) {
        val childName = DeviceRegistration.getChildName(context) ?: return
        val statsManager = LocalStatsManager(context)
        
        viewModelScope.launch {
            try {
                // Ensure queued local activities are flushed to the backend server
                ActivityUploader.flushQueue(context)

                // Fetch profiles for limits and internet pause status
                val response = ApiClient.api.getProfiles()
                if (response.isSuccessful) {
                    _profile.value = response.body()?.find { it.name.trim().equals(childName.trim(), ignoreCase = true) }
                }

                // Fetch logs for screen time calculation and activity log display
                var remoteLogs: List<ActivityRequest> = emptyList()
                try {
                    val activityResponse = ApiClient.api.getActivity(childName, 500)
                    if (activityResponse.isSuccessful && !activityResponse.body().isNullOrEmpty()) {
                        remoteLogs = activityResponse.body()!!
                    }
                } catch (_: Exception) {}

                val localLogs = statsManager.getRecentActivity(100)
                val allLogs = (localLogs + remoteLogs).distinctBy { "${it.domain}_${it.timestamp}" }.sortedByDescending { it.timestamp }

                _activityLogs.value = allLogs
                _screenTimeMinutes.value = calculateMinutes(allLogs)
                
                // Load detailed local stats from SQLite
                _localStats.value = LocalStats(
                    blockedCount = statsManager.getBlockedCount(),
                    topApps = statsManager.getTopApps(),
                    categoryBreakdown = statsManager.getCategoryBreakdown(),
                    lastSync = statsManager.getLastSync()
                )
            } catch (_: Exception) { }
        }
    }

    private fun calculateMinutes(logs: List<ActivityRequest>): Int {
        if (logs.isEmpty()) return 0
        val sorted = logs.sortedBy { it.timestamp }
        var total = 0
        for (i in 0 until sorted.size - 1) {
            val current = parseTs(sorted[i].timestamp)
            val next = parseTs(sorted[i+1].timestamp)
            val diff = (next.time - current.time) / 60000
            if (diff in 1..5) total += diff.toInt()
        }
        return total
    }

    private fun parseTs(ts: String): Date {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(ts) ?: Date()
        } catch (_: Exception) { Date() }
    }
}

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
        else Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleDailyReset()
        enableEdgeToEdge()
        setContent {
            HomeKidsChildTheme {
                Surface(color = BrandBg, modifier = Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }

    @Composable
    fun MainScreen(viewModel: HomeKidsViewModel = viewModel()) {
        val context = LocalContext.current
        var isRegistered by remember { mutableStateOf(DeviceRegistration.isRegistered(context)) }
        var inputName by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }

        val profile by viewModel.profile.collectAsState()
        val screenTime by viewModel.screenTimeMinutes.collectAsState()
        val activityLogs by viewModel.activityLogs.collectAsState()
        val localStats by viewModel.localStats.collectAsState()

        LaunchedEffect(isRegistered) {
            if (isRegistered) {
                if (!HomeKidsVpnService.isRunning) {
                    prepareAndStartVpn()
                }
                while(true) {
                    viewModel.loadData(context)
                    kotlinx.coroutines.delay(10000.milliseconds)
                }
            }
        }

        var inputServerIp by remember { mutableStateOf(DeviceRegistration.getServerIp(context)) }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!isRegistered) {
                RegistrationScreen(
                    inputName = inputName,
                    onNameChange = { inputName = it },
                    serverIp = inputServerIp,
                    onServerIpChange = { inputServerIp = it },
                    isLoading = loading,
                    onRegister = {
                        if (inputName.isNotBlank()) {
                            loading = true
                            lifecycleScope.launch {
                                val result = DeviceRegistration.register(context, inputName, inputServerIp)
                                loading = false
                                if (result.isSuccess) {
                                    isRegistered = true
                                    Toast.makeText(context, "Registered Successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    val error = result.exceptionOrNull()?.message ?: "Unknown Error"
                                    Toast.makeText(context, "Registration Failed: $error", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                )
            } else {
                DashboardScreen(
                    profile = profile,
                    screenTime = screenTime,
                    activityLogs = activityLogs,
                    localStats = localStats,
                    onStart = { prepareAndStartVpn() },
                    onStop = { stopVpnService() }
                )
            }
        }
    }

    @Composable
    fun RegistrationScreen(
        inputName: String,
        onNameChange: (String) -> Unit,
        serverIp: String,
        onServerIpChange: (String) -> Unit,
        isLoading: Boolean,
        onRegister: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(100.dp).background(BrandPrimary.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) { Text("🛡️", fontSize = 50.sp) }
            Spacer(modifier = Modifier.height(24.dp))
            Text("HomeKids", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = BrandPrimary)
            Text("Professional Parental Protection", style = MaterialTheme.typography.bodyLarge, color = TextMuted, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(36.dp))
            
            OutlinedTextField(value = inputName, onValueChange = onNameChange, label = { Text("Child's Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = serverIp, onValueChange = onServerIpChange, label = { Text("Server IP Address") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRegister, enabled = !isLoading, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)) {
                if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                else Text("Register Device", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun DashboardScreen(
        profile: ProfileResponse?,
        screenTime: Int,
        activityLogs: List<ActivityRequest>,
        localStats: HomeKidsViewModel.LocalStats?,
        onStart: () -> Unit,
        onStop: () -> Unit
    ) {
        val context = LocalContext.current
        var showIpDialog by remember { mutableStateOf(false) }
        var currentIp by remember { mutableStateOf(DeviceRegistration.getServerIp(context)) }

        if (showIpDialog) {
            AlertDialog(
                onDismissRequest = { showIpDialog = false },
                title = { Text("Server Connection IP") },
                text = {
                    OutlinedTextField(
                        value = currentIp,
                        onValueChange = { currentIp = it },
                        label = { Text("Server IP Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        DeviceRegistration.setServerIp(context, currentIp)
                        showIpDialog = false
                        Toast.makeText(context, "Server IP updated!", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showIpDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(60.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Welcome back,", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    Text(DeviceRegistration.getChildName(context) ?: "HomeKid", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = TextDark)
                }
                Surface(modifier = Modifier.size(56.dp), shape = CircleShape, color = BrandPrimary.copy(0.1f)) {
                    Box(contentAlignment = Alignment.Center) { Text(profile?.avatar ?: "🧒", fontSize = 32.sp) }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = CardWhite), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Today's Usage", fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(20.dp))
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressBar(screenTime, profile?.dailyLimit ?: 120)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${screenTime}m", fontSize = 52.sp, fontWeight = FontWeight.Black, color = TextDark)
                            Text("Limit: ${profile?.dailyLimit ?: 120}m", fontSize = 14.sp, color = TextMuted)
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CategoryItem("Social", "${localStats?.categoryBreakdown?.get("Social") ?: 0}m", "👥", StatusGreen)
                        CategoryItem("Gaming", "${localStats?.categoryBreakdown?.get("Gaming") ?: 0}m", "🎮", StatusOrange)
                        CategoryItem("Study", "${localStats?.categoryBreakdown?.get("Education") ?: 0}m", "📚", BrandPrimary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoTile("Blocked", "${localStats?.blockedCount ?: 0}", "Attempts", StatusRed, Modifier.weight(1f))
                InfoTile("Sync Status", formatTimeAgo(localStats?.lastSync ?: 0), "Real-time", BrandPrimary, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Server Connection", fontWeight = FontWeight.Bold, color = TextDark)
                            Text("IP: ${DeviceRegistration.getServerIp(context)}", fontSize = 12.sp, color = TextMuted)
                        }
                        TextButton(onClick = { showIpDialog = true }) {
                            Text("Change IP", color = BrandPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Most Used Apps", fontWeight = FontWeight.Bold, color = TextDark)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (localStats?.topApps?.isEmpty() == true) {
                        Text("No activity recorded yet", color = TextMuted, fontSize = 13.sp)
                    } else {
                        localStats?.topApps?.forEach { app ->
                            AppUsageRow(app)
                            if (app != localStats.topApps.last()) HorizontalDivider(color = BrandBg, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Activity Log", fontWeight = FontWeight.Bold, color = TextDark)
                        Text("${activityLogs.size} logs", fontSize = 12.sp, color = TextMuted)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    if (activityLogs.isEmpty()) {
                        Text("No activity recorded yet", color = TextMuted, fontSize = 13.sp)
                    } else {
                        val displayLogs = activityLogs.take(10)
                        displayLogs.forEach { log ->
                            ActivityLogRow(log)
                            if (log != displayLogs.last()) {
                                HorizontalDivider(color = BrandBg, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))
                            }
                        }
                    }
                }
            }
            
            if (profile?.internetPaused == true) {
                Spacer(modifier = Modifier.height(32.dp))
                NoticeBox("Internet Paused", "A parent has temporarily paused your internet access.", StatusRed)
            }

            Spacer(modifier = Modifier.height(32.dp))
            ProtectionStatusBanner(HomeKidsVpnService.isRunning)
            Spacer(modifier = Modifier.height(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = if (HomeKidsVpnService.isRunning) StatusGreen else BrandPrimary), elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)) {
                    Text(if (HomeKidsVpnService.isRunning) "🛡️ Protection Active" else "🛡️ Start Protection", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                if (HomeKidsVpnService.isRunning) {
                    TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Pause Protection", color = StatusRed, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    @Composable
    fun NoticeBox(title: String, body: String, color: Color) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = color.copy(0.1f), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(0.2f))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(title, fontWeight = FontWeight.Bold, color = color, fontSize = 14.sp)
                Text(body, color = color.copy(0.8f), fontSize = 13.sp)
            }
        }
    }

    @Composable
    fun CategoryItem(label: String, value: String, emoji: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = color.copy(0.1f)) {
                Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDark)
            Text(label, fontSize = 11.sp, color = TextMuted)
        }
    }

    @Composable
    fun InfoTile(label: String, value: String, sub: String, color: Color, modifier: Modifier) {
        Card(modifier = modifier, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(label, fontSize = 12.sp, color = TextMuted, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = color)
                Text(sub, fontSize = 11.sp, color = TextMuted)
            }
        }
    }

    @Composable
    fun AppUsageRow(app: LocalStatsManager.AppStat) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = "https://www.google.com/s2/favicons?domain=${app.domain}&sz=64", contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(BrandBg))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Bold, color = TextDark)
                Text(app.domain, fontSize = 12.sp, color = TextMuted)
            }
            Text("${app.count} hits", fontWeight = FontWeight.SemiBold, color = BrandPrimary, fontSize = 13.sp)
        }
    }

    @Composable
    fun ActivityLogRow(log: ActivityRequest) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = "https://www.google.com/s2/favicons?domain=${log.domain}&sz=64",
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(BrandBg)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(log.domain, fontWeight = FontWeight.SemiBold, color = TextDark, fontSize = 14.sp)
                Text("${log.app} • ${log.timestamp}", fontSize = 11.sp, color = TextMuted)
            }
            if (log.status == "blocked") {
                Surface(
                    color = StatusRed.copy(0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Blocked",
                        color = StatusRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun ProtectionStatusBanner(isActive: Boolean) {
        val color = if (isActive) StatusGreen else TextMuted
        val infiniteTransition = rememberInfiniteTransition()
        val alpha by if (isActive) { infiniteTransition.animateFloat(initialValue = 0.5f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse)) } else { remember { mutableFloatStateOf(1f) } }
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = color.copy(0.05f), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(0.2f))) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).background(color.copy(alpha), CircleShape))
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = if (isActive) "Device is currently protected" else "Protection is paused", color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }

    @Composable
    fun CircularProgressBar(current: Int, limit: Int) {
        val progress = if (limit > 0) (current.toFloat() / limit).coerceIn(0f, 1.1f) else 0f
        val animatedValue by animateFloatAsState(targetValue = progress, animationSpec = tween(1500, easing = FastOutSlowInEasing))
        val color by animateColorAsState(targetValue = when { animatedValue < 0.5f -> StatusGreen; animatedValue < 0.85f -> StatusOrange; else -> StatusRed })
        Canvas(modifier = Modifier.size(240.dp).padding(10.dp)) {
            drawArc(color = BrandBg, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round))
            drawArc(brush = Brush.sweepGradient(listOf(color.copy(0.6f), color)), startAngle = 135f, sweepAngle = animatedValue * 270f, useCenter = false, style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round))
        }
    }

    private fun formatTimeAgo(time: Long): String {
        if (time == 0L) return "Never"
        val diff = (System.currentTimeMillis() - time) / 1000
        return when {
            diff < 60 -> "Just now"
            diff < 3600 -> "${diff / 60}m ago"
            else -> "${diff / 3600}h ago"
        }
    }

    private fun scheduleDailyReset() {
        val resetRequest = PeriodicWorkRequestBuilder<DailyResetWorker>(24, java.util.concurrent.TimeUnit.HOURS).setInitialDelay(calculateDelayToMidnight(), java.util.concurrent.TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily_reset", ExistingPeriodicWorkPolicy.KEEP, resetRequest)
    }

    private fun calculateDelayToMidnight(): Long {
        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 23); calendar.set(Calendar.MINUTE, 59); calendar.set(Calendar.SECOND, 59)
        val diff = calendar.timeInMillis - now
        return if (diff > 0) diff else 0
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else startVpnService()
    }

    private fun startVpnService() {
        val intent = Intent(this, HomeKidsVpnService::class.java).apply { action = HomeKidsVpnService.ACTION_START }
        startForegroundService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, HomeKidsVpnService::class.java).apply { action = HomeKidsVpnService.ACTION_STOP }
        startService(intent)
    }
}
