package com.fazalelahi92.apptracker.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UsageInterval::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
}
