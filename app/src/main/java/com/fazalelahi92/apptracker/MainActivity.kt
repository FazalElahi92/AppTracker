package com.fazalelahi92.apptracker

import android.os.Bundle
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fazalelahi92.apptracker.data.UsageSyncWorker
import java.util.concurrent.TimeUnit
import com.fazalelahi92.apptracker.data.UsageInterval
import com.fazalelahi92.apptracker.data.UsageRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var repository: UsageRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = UsageRepository(applicationContext)
        val sync = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "apptracker-usage-sync", ExistingPeriodicWorkPolicy.KEEP, sync
        )
        setContent {
            AppTrackerScreen(
                hasPermission = repository.hasUsagePermission(),
                onOpenSettings = { startActivity(repository.permissionSettingsIntent()) },
                onRefresh = { day, callback ->
                    lifecycleScope.launch { callback(repository.loadDay(day)) }
                },
                onCheckPermission = { repository.hasUsagePermission() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTrackerScreen(
    hasPermission: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: (Long, (List<UsageInterval>) -> Unit) -> Unit,
    onCheckPermission: () -> Boolean
) {
    var selectedDay by remember { mutableLongStateOf(UsageRepository.startOfDay(System.currentTimeMillis())) }
    var intervals by remember { mutableStateOf(emptyList<UsageInterval>()) }
    var tab by remember { mutableIntStateOf(0) }
    var permission by remember { mutableStateOf(hasPermission) }
    var lookupHour by remember { mutableIntStateOf(12) }
    var lookupMinute by remember { mutableIntStateOf(0) }
    val dateFormat = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permission = onCheckPermission() }

    LaunchedEffect(selectedDay, permission) {
        if (permission) onRefresh(selectedDay) { intervals = it }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF365EAA), secondary = Color(0xFF5277B8),
        surface = Color(0xFFF8F9FC), background = Color(0xFFF8F9FC)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (tab == 0) {
                            TextButton(onClick = {
                                val c = Calendar.getInstance().apply { timeInMillis = selectedDay }
                                DatePickerDialog(context, { _, year, month, day ->
                                    selectedDay = Calendar.getInstance().apply {
                                        set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                            }) {
                                Text(dateFormat.format(Date(selectedDay)), style = MaterialTheme.typography.titleMedium)
                            }
                        } else Text("Settings")
                    },
                    actions = {
                        if (tab == 0) IconButton(onClick = { tab = 1 }) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.CalendarMonth, null) }, label = { Text("Timeline") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            if (tab == 1) {
                SettingsScreen(permission, padding, onOpenSettings) {
                    permission = onCheckPermission()
                }
            } else {
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selectedDay -= 86_400_000L }) { Text("‹ Previous day") }
                        TextButton(onClick = { selectedDay = UsageRepository.startOfDay(System.currentTimeMillis()) }) { Text("Today") }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!permission) {
                        Card {
                            Column(Modifier.padding(18.dp)) {
                                Text("Usage access is needed", style = MaterialTheme.typography.titleMedium)
                                Text("Allow AppTracker to read app-usage events to build your 24-hour history.")
                                Button(onClick = onOpenSettings) { Text("Grant usage access") }
                            }
                        }
                    } else {
                        val displayIntervals = remember(intervals) {
                            val ordered = intervals.sortedBy { it.startTime }
                            val result = mutableListOf<UsageInterval>()
                            var pendingShortHomeStart: Long? = null
                            for (item in ordered) {
                                val isHome = item.state == "Home screen" ||
                                    (item.state == "App" && (
                                        item.appLabel.contains("launcher", ignoreCase = true) ||
                                        item.packageName.contains("launcher", ignoreCase = true)
                                    ))
                                val isShortHome = isHome && item.endTime - item.startTime < 5L * 60_000L
                                if (isShortHome) {
                                    if (result.isNotEmpty()) {
                                        val previous = result.removeAt(result.lastIndex)
                                        result.add(previous.copy(endTime = item.endTime))
                                    } else {
                                        pendingShortHomeStart = pendingShortHomeStart ?: item.startTime
                                    }
                                } else {
                                    val adjusted = if (pendingShortHomeStart != null) {
                                        item.copy(startTime = minOf(item.startTime, pendingShortHomeStart!!))
                                    } else item
                                    result.add(adjusted)
                                    pendingShortHomeStart = null
                                }
                            }
                            if (pendingShortHomeStart != null && result.isNotEmpty()) {
                                val last = result.removeAt(result.lastIndex)
                                result.add(last.copy(endTime = maxOf(last.endTime, ordered.last().endTime)))
                            }
                            result
                        }
                        val totals = displayIntervals.groupBy { if (it.state == "App") friendlyAppName(it.appLabel, it.packageName) else it.state }
                            .mapValues { e -> e.value.sumOf { (it.endTime-it.startTime).coerceAtLeast(0L) } }
                        val totalDuration = totals.values.sum().coerceAtLeast(1L)
                        val separateEntries = totals.entries
                            .filter { it.value.toDouble() / totalDuration > 0.05 && it.key != "Others" }
                            .sortedByDescending { it.value }
                        val otherEntries = totals.entries
                            .filter { entry -> separateEntries.none { it.key == entry.key } }
                            .sortedByDescending { it.value }
                        val othersDuration = otherEntries.sumOf { it.value }
                        val chartTotals = linkedMapOf<String, Long>().apply {
                            separateEntries.forEach { put(it.key, it.value) }
                            if (othersDuration > 0L) put("Others", othersDuration)
                        }
                        var othersExpanded by remember(selectedDay) { mutableStateOf(false) }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("24-hour overview", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    UsageDonut(chartTotals, Modifier.size(132.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Daily activity", style = MaterialTheme.typography.titleSmall)
                                        chartTotals.forEach { (name, value) ->
                                            if (name == "Others") {
                                                TextButton(
                                                    onClick = { othersExpanded = !othersExpanded },
                                                    contentPadding = PaddingValues(0.dp)
                                                ) {
                                                    Text("${if (othersExpanded) "▾" else "▸"} Others · ${duration(value)} · ${(value * 100L / totalDuration)}%")
                                                }
                                                if (othersExpanded) {
                                                    otherEntries.forEach { item ->
                                                        Text(
                                                            "   ${item.key} · ${duration(item.value)} · ${(item.value * 100L / totalDuration)}%",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            } else {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(Modifier.size(9.dp).background(colorFor(name), CircleShape))
                                                    Spacer(Modifier.width(7.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                        Text("${duration(value)} · ${(value * 100L / totalDuration)}%",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val lookupInstant = Calendar.getInstance().apply {
                            timeInMillis = selectedDay
                            set(Calendar.HOUR_OF_DAY, lookupHour)
                            set(Calendar.MINUTE, lookupMinute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        val match = intervals.firstOrNull { lookupInstant >= it.startTime && lookupInstant < it.endTime }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("Check a specific time", style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = {
                                    TimePickerDialog(context, { _, hour, minute ->
                                        lookupHour = hour
                                        lookupMinute = minute
                                    }, lookupHour, lookupMinute, false).show()
                                }) {
                                    Text("Choose time: ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(lookupInstant))}")
                                }
                                Text(
                                    when {
                                        match == null -> "No recorded activity at this time"
                                        match.state == "App" -> "Foreground app: ${friendlyAppName(match.appLabel, match.packageName)}"
                                        else -> "Phone state: ${match.state}"
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Activity timeline", style = MaterialTheme.typography.titleLarge)
                        val timelineState = androidx.compose.foundation.lazy.rememberLazyListState()
                        val scope = rememberCoroutineScope()
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LazyColumn(
                                state = timelineState,
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(displayIntervals) { item ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(10.dp).background(colorFor(item.appLabel), CircleShape))
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(if (item.state == "App") friendlyAppName(item.appLabel, item.packageName) else item.state,
                                                    style = MaterialTheme.typography.titleSmall)
                                                Text("${clock(item.startTime)} – ${clock(item.endTime)}",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text(duration(item.endTime - item.startTime), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                            // A fixed 24-hour navigator; selecting an hour jumps to its first recorded interval.
                            LazyColumn(
                                modifier = Modifier.width(42.dp).fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items((0..23).toList()) { hour ->
                                    TextButton(
                                        onClick = {
                                            val dayStart = selectedDay
                                            val target = dayStart + hour * 3_600_000L
                                            val index = displayIntervals.indexOfFirst {
                                                target >= it.startTime && target < it.endTime
                                            }.let { found ->
                                                if (found >= 0) found else displayIntervals.indexOfFirst { it.startTime >= target }
                                            }
                                            if (index >= 0) scope.launch { timelineState.animateScrollToItem(index) }
                                        },
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(hour.toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SettingsScreen(permission: Boolean, padding: PaddingValues, onOpenSettings: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                Text("Usage access: ${if (permission) "Granted" else "Not granted"}")
                Text("Android requires you to enable this permission in system settings.")
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(if (permission) "Manage usage access" else "Grant usage access")
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh permission status") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("About tracking", style = MaterialTheme.typography.titleMedium)
                Text("AppTracker records app names and timestamps locally on this phone. It does not read app content.")
            }
        }
    }
}

@Composable
private fun UsageDonut(totals: Map<String, Long>, modifier: Modifier = Modifier) {
    val entries = totals.entries.sortedByDescending { it.value }
    val sum = entries.sumOf { it.value }.coerceAtLeast(1L)
    Canvas(modifier) {
        var start = -90f
        entries.forEach { entry ->
            val sweep = entry.value.toFloat() / sum * 360f
            drawArc(colorFor(entry.key), start, sweep, false,
                topLeft = Offset(8.dp.toPx(), 8.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 16.dp.toPx(), size.height - 16.dp.toPx()),
                style = Stroke(width = 18.dp.toPx()))
            start += sweep
        }
    }
}

private fun colorFor(value: String): Color {
    val palette = listOf(Color(0xFF4267B2), Color(0xFFDB8B36), Color(0xFF3C9478),
        Color(0xFF9B6BB5), Color(0xFFCF6470), Color(0xFF6B91A8), Color(0xFF8B9560))
    return palette[(value.hashCode().ushr(1)) % palette.size]
}
private fun friendlyAppName(label: String, packageName: String): String {
    val candidate = label.trim()
    if (candidate.isNotBlank() && !candidate.contains('.') && candidate != packageName) return candidate
    val shortName = packageName.substringAfterLast('.').ifBlank { candidate }
        .replace('_', ' ').replace('-', ' ')
    return shortName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
private fun clock(time: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))
private fun duration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
