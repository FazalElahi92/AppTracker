package com.fazalelahi92.apptracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_intervals")
data class UsageInterval(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayStart: Long,
    val startTime: Long,
    val endTime: Long,
    val packageName: String,
    val appLabel: String,
    val state: String
)
