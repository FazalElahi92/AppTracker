package com.fazalelahi92.apptracker.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Process
import androidx.room.Room
import java.util.Calendar

class UsageRepository(private val context: Context) {
    private val db by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "apptracker.db").build()
    }

    fun hasUsagePermission(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun permissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun loadDay(dayStart: Long): List<UsageInterval> {
        val cached = db.usageDao().forDay(dayStart)
        if (!hasUsagePermission()) return cached
        val dayEnd = minOf(dayStart + 24L * 60L * 60L * 1000L, System.currentTimeMillis())
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(dayStart - 12L * 60L * 60L * 1000L, dayEnd)
        val event = UsageEvents.Event()
        val launcher = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName

        var state = "Unknown"
        var pkg = ""
        var label = state
        var cursor = dayStart
        val result = mutableListOf<UsageInterval>()

        fun appLabel(packageName: String): String {
            val installedLabel = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0)
                ).toString().trim()
            } catch (_: Exception) { "" }
            if (installedLabel.isNotBlank() && installedLabel != packageName &&
                !installedLabel.contains('/')) return installedLabel
            val shortName = packageName.substringAfterLast('.').replace('_', ' ').replace('-', ' ')
            return shortName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        fun appendInterval(start: Long, end: Long) {
            if (end <= start) return
            val previous = result.lastOrNull()
            if (previous != null && previous.endTime == start &&
                previous.state == state && previous.packageName == pkg) {
                result[result.lastIndex] = previous.copy(endTime = end)
            } else {
                result.add(UsageInterval(dayStart = dayStart, startTime = start,
                    endTime = end, packageName = pkg, appLabel = label, state = state))
            }
        }

        fun transition(at: Long, newState: String, newPkg: String = "") {
            val bounded = at.coerceIn(dayStart, dayEnd)
            if (newState == state && newPkg == pkg) return
            if (at > cursor && cursor >= dayStart && cursor < dayEnd) {
                appendInterval(cursor, bounded)
            }
            if (at >= dayStart) cursor = bounded
            state = newState
            pkg = newPkg
            label = newState
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val t = event.timeStamp
            if (t > dayEnd) break
            when (event.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> transition(t, "Screen off")
                UsageEvents.Event.KEYGUARD_SHOWN -> transition(t, "Locked")
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val p = event.packageName ?: ""
                    if (p == launcher) transition(t, "Home screen")
                    else {
                        transition(t, "App", p)
                        label = appLabel(p)
                    }
                }
                // A paused activity is not proof that the user returned to Home.
                // The launcher is recorded only when its own ACTIVITY_RESUMED event arrives.
            }
        }
        if (cursor < dayEnd) {
            appendInterval(cursor.coerceAtLeast(dayStart), dayEnd)
        }
        db.usageDao().deleteDay(dayStart)
        if (result.isNotEmpty()) db.usageDao().insertAll(result)
        return result
    }

    companion object {
        fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
