package com.example.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import com.example.data.model.RawLead
import kotlinx.coroutines.flow.Flow

@Dao
interface LeadDao {
    @Query("SELECT * FROM raw_leads ORDER BY timestamp DESC")
    fun getAllRawLeadsFlow(): Flow<List<RawLead>>

    @Query("SELECT * FROM raw_leads ORDER BY timestamp DESC")
    suspend fun getAllRawLeads(): List<RawLead>

    @Query("SELECT COUNT(*) FROM raw_leads")
    fun getRawLeadsCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_leads")
    suspend fun getRawLeadsCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLead(lead: RawLead): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLeads(leads: List<RawLead>): List<Long>

    @Query("DELETE FROM raw_leads")
    suspend fun clearAllLeads()

    /**
     * Dual-Criteria Query: Retrieves exactly up to 600 premium leads.
     * - Main Criteria: Must have contact phone number.
     * - Eligibility Criteria: Rating >= minRating OR (Rating IS NULL AND has_photos = 1).
     * - Sorting: Highest rating to lowest, prioritizing leads with photos, then timestamp.
     * - Limit: Exactly 600.
     */
    @Query("""
        SELECT * FROM raw_leads 
        WHERE phone IS NOT NULL AND phone != '' 
          AND (rating >= :minRating OR (rating IS NULL AND has_photos = 1))
        ORDER BY rating DESC, has_photos DESC, timestamp DESC
        LIMIT 600
    """)
    suspend fun getPremiumLeads(minRating: Float): List<RawLead>

    @Query("""
        SELECT COUNT(*) FROM raw_leads 
        WHERE phone IS NOT NULL AND phone != '' 
          AND (rating >= :minRating OR (rating IS NULL AND has_photos = 1))
    """)
    fun getPremiumLeadsCountFlow(minRating: Float): Flow<Int>
}

@Database(entities = [RawLead::class], version = 1, exportSchema = false)
abstract class LeadDatabase : RoomDatabase() {
    abstract val leadDao: LeadDao
}
