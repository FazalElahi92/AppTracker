package com.fazalelahi92.apptracker

import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.fazalelahi92.apptracker.data.UsageInterval
import com.fazalelahi92.apptracker.data.UsageRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var repository: UsageRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = UsageRepository(applicationContext)
        setContent {
            AppTrackerScreen(
                hasPermission = repository.hasUsagePermission(),
                onOpenSettings = { startActivity(repository.permissionSettingsIntent()) },
                onRefresh = { day, callback ->
                    lifecycleScope.launch { callback(repository.loadDay(day)) }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTrackerScreen(
    hasPermission: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: (Long, (List<UsageInterval>) -> Unit) -> Unit
) {
    var selectedDay by remember { mutableLongStateOf(UsageRepository.startOfDay(System.currentTimeMillis())) }
    var intervals by remember { mutableStateOf(emptyList<UsageInterval>()) }
    var tab by remember { mutableIntStateOf(0) }
    var permission by remember { mutableStateOf(hasPermission) }
    val dateFormat = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }

    LaunchedEffect(selectedDay, permission) {
        if (permission) onRefresh(selectedDay) { intervals = it }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF365EAA), secondary = Color(0xFF5277B8),
        surface = Color(0xFFF8F9FC), background = Color(0xFFF8F9FC)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("AppTracker") }, actions = {
                    IconButton(onClick = { tab = 1 }) { Icon(Icons.Default.Settings, "Settings") }
                })
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
                SettingsScreen(permission, onOpenSettings) {
                    permission = repositoryPermissionCheckFallback(permission)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { selectedDay -= 86_400_000L }) { Text("‹ Previous") }
                        Text(dateFormat.format(Date(selectedDay)), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { selectedDay = UsageRepository.startOfDay(System.currentTimeMillis()) }) { Text("Today") }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!permission) {
                        Card {
                            Column(Modifier.padding(18.dp)) {
                                Text("Usage access is needed", style = MaterialTheme.typography.titleMedium)
                                Text("Allow AppTracker to read app-usage events to build your 24-hour history.")
                                Button(onClick = onOpenSettings) { Text("Grant usage access") }
                            }
                        }
                    } else {
                        val totals = intervals.groupBy { if (it.state == "App") it.appLabel else it.state }
                            .mapValues { e -> e.value.sumOf { (it.endTime-it.startTime).coerceAtLeast(0L) } }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("24-hour overview", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    UsageDonut(totals, Modifier.size(132.dp))
                                    Spacer(Modifier.width(18.dp))
                                    Column {
                                        Text("Full-day timeline", style = MaterialTheme.typography.titleSmall)
                                        Text("${totals.size} activity categories", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Includes screen-off time", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Activity timeline", style = MaterialTheme.typography.titleLarge)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(intervals) { item ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(10.dp).background(colorFor(item.appLabel), CircleShape))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(if (item.state == "App") item.appLabel else item.state,
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
                    }
                }
            }
        }
    }
}

private fun repositoryPermissionCheckFallback(current: Boolean) = current

@Composable
private fun SettingsScreen(permission: Boolean, onOpenSettings: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
    val entries = totals.entries.sortedByDescending { it.value }.take(7)
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
private fun clock(time: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))
private fun duration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
