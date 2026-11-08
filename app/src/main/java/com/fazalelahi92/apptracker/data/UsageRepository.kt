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
        val dayEnd = dayStart + 24L * 60L * 60L * 1000L
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(dayStart - 12L * 60L * 60L * 1000L, dayEnd)
        val event = UsageEvents.Event()
        val launcher = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName

        var state = "Screen off"
        var pkg = ""
        var label = state
        var cursor = dayStart - 12L * 60L * 60L * 1000L
        val result = mutableListOf<UsageInterval>()

        fun appLabel(packageName: String): String = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) { packageName }

        fun transition(at: Long, newState: String, newPkg: String = "") {
            if (at > cursor && cursor >= dayStart && cursor < dayEnd) {
                result.add(UsageInterval(dayStart = dayStart, startTime = cursor,
                    endTime = at.coerceAtMost(dayEnd), packageName = pkg, appLabel = label, state = state))
            }
            if (at >= dayStart) cursor = at.coerceAtMost(dayEnd)
            state = newState
            pkg = newPkg
            label = newState
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val t = event.timeStamp
            if (t > dayEnd) break
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> transition(t, "Home screen")
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> transition(t, "Screen off")
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val p = event.packageName ?: ""
                    if (p == launcher) transition(t, "Home screen", p)
                    else {
                        transition(t, "App", p)
                        label = appLabel(p)
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (state == "App" && event.packageName == pkg)
                        transition(t, "Home screen", launcher ?: "")
                }
            }
        }
        if (cursor < dayEnd) {
            result.add(UsageInterval(dayStart = dayStart, startTime = cursor.coerceAtLeast(dayStart),
                endTime = dayEnd, packageName = pkg, appLabel = label, state = state))
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
