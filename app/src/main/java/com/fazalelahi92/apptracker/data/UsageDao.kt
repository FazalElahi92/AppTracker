package com.fazalelahi92.apptracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_intervals WHERE dayStart = :dayStart ORDER BY startTime")
    suspend fun forDay(dayStart: Long): List<UsageInterval>

    @Query("DELETE FROM usage_intervals WHERE dayStart = :dayStart")
    suspend fun deleteDay(dayStart: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(intervals: List<UsageInterval>)
}
