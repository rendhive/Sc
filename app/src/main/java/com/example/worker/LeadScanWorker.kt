package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.db.LeadDatabase
import com.example.data.network.NetworkClient
import com.example.data.repository.LeadRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class LeadScanWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_CATEGORY = "key_category"
        const val KEY_REGION = "key_region"
        const val KEY_RADIUS = "key_radius"
        const val KEY_API_KEY = "key_api_key"
        const val KEY_MIN_RATING = "key_min_rating"

        const val PROGRESS_POINT_CURRENT = "progress_point_current"
        const val PROGRESS_POINT_TOTAL = "progress_point_total"
        const val PROGRESS_LEADS_FOUND = "progress_leads_found"
        const val PROGRESS_STATUS = "progress_status"

        private val activeWorkerIds = mutableSetOf<java.util.UUID>()

        fun isAnyScanning(): Boolean = activeWorkerIds.isNotEmpty()
    }

    override suspend fun doWork(): Result {
        val category = inputData.getString(KEY_CATEGORY) ?: "Restoran"
        val region = inputData.getString(KEY_REGION) ?: "Jakarta"
        val radiusMeters = inputData.getDouble(KEY_RADIUS, 1000.0)
        val apiKey = inputData.getString(KEY_API_KEY) ?: ""
        val minRating = inputData.getFloat(KEY_MIN_RATING, 4.0f)

        activeWorkerIds.add(id)

        // Initialize components manually inside Worker
        val db = androidx.room.Room.databaseBuilder(
            appContext,
            LeadDatabase::class.java,
            "b2b_leads_db"
        ).build()

        val repository = LeadRepository(
            leadDao = db.leadDao,
            osmService = NetworkClient.osmService,
            googlePlacesService = NetworkClient.googlePlacesService
        )

        Log.d("LeadScanWorker", "Starting background lead scan. Category: $category, Region: $region")
        setProgress(workDataOf(PROGRESS_STATUS to "Mencari batas koordinat wilayah (OSM)..."))

        // 1. Resolve free boundary using OSM
        val bounds = repository.resolveRegionBoundary(region)
        if (bounds == null) {
            setProgress(workDataOf(PROGRESS_STATUS to "Gagal mendapatkan batas koordinat dari OSM."))
            Log.e("LeadScanWorker", "OSM Boundary Lookup failed.")
            activeWorkerIds.remove(id)
            return Result.failure()
        }

        Log.d("LeadScanWorker", "Resolved BBox: minLat=${bounds.minLat}, maxLat=${bounds.maxLat}")
        setProgress(workDataOf(PROGRESS_STATUS to "Menghitung grid pemindaian..."))

        // 2. Generate Grid
        val deltaLat = radiusMeters / 111000.0
        val latRad = Math.toRadians(bounds.centerLat)
        val deltaLon = radiusMeters / (111000.0 * Math.cos(latRad))

        val gridPoints = mutableListOf<Pair<Double, Double>>()
        var currentLat = bounds.minLat + (deltaLat / 2.0)
        while (currentLat < bounds.maxLat) {
            var currentLon = bounds.minLon + (deltaLon / 2.0)
            while (currentLon < bounds.maxLon) {
                gridPoints.add(Pair(currentLat, currentLon))
                currentLon += deltaLon
            }
            currentLat += deltaLat
        }

        if (gridPoints.isEmpty()) {
            gridPoints.add(Pair(bounds.centerLat, bounds.centerLon))
        }

        val totalPoints = gridPoints.size
        Log.d("LeadScanWorker", "Generated $totalPoints grid points for scanning.")

        var currentPointIndex = 0
        var totalLoadedLeads = 0

        try {
            for (point in gridPoints) {
                // Instantly check if developer has cancelled background task
                if (isStopped) {
                    Log.d("LeadScanWorker", "Scan task stopped.")
                    break
                }

                currentPointIndex++
                setProgress(
                    workDataOf(
                        PROGRESS_STATUS to "Memindai koordinat ke-$currentPointIndex dari $totalPoints...",
                        PROGRESS_POINT_CURRENT to currentPointIndex,
                        PROGRESS_POINT_TOTAL to totalPoints,
                        PROGRESS_LEADS_FOUND to totalLoadedLeads
                    )
                )

                // 3. Scan Google Places
                val newLeads = repository.scanGridPoint(
                    apiKey = apiKey,
                    category = category,
                    lat = point.first,
                    lng = point.second,
                    radiusMeters = radiusMeters,
                    regionName = region
                )

                // 4. Check database counts
                val dbCount = db.leadDao.getPremiumLeads(minRating).size
                totalLoadedLeads = dbCount

                Log.d("LeadScanWorker", "Matches premium currently: $dbCount")

                // Protect API constraints: Hentikan seluruh task secara instan ketika mencapai limit tepat 600 premium data
                if (dbCount >= 600) {
                    Log.d("LeadScanWorker", "Halt condition reached! Hit $dbCount premium contacts.")
                    break
                }

                // Small delay to behave responsively and protect network boundaries
                kotlinx.coroutines.delay(1000)
            }

            // 5. Instantly generate XLS spreadsheet as requested
            setProgress(workDataOf(
                PROGRESS_STATUS to "Pencarian dihentikan. Mengekspor $totalLoadedLeads prospek premium ke Excel...",
                PROGRESS_POINT_CURRENT to currentPointIndex,
                PROGRESS_POINT_TOTAL to totalPoints,
                PROGRESS_LEADS_FOUND to totalLoadedLeads
            ))

            val fileUri = repository.exportFilteredLeads(appContext, category, region, minRating)
            if (fileUri != null) {
                Log.d("LeadScanWorker", "Excel exported successfully: $fileUri")
                setProgress(workDataOf(
                    PROGRESS_STATUS to "Selesai! File disimpan di $fileUri",
                    PROGRESS_POINT_CURRENT to currentPointIndex,
                    PROGRESS_POINT_TOTAL to totalPoints,
                    PROGRESS_LEADS_FOUND to totalLoadedLeads
                ))
            } else {
                setProgress(workDataOf(
                    PROGRESS_STATUS to "Ekstraksi xlsx gagal (tidak ada data atau masalah penyimpanan).",
                    PROGRESS_POINT_CURRENT to currentPointIndex,
                    PROGRESS_POINT_TOTAL to totalPoints,
                    PROGRESS_LEADS_FOUND to totalLoadedLeads
                ))
            }

        } catch (e: CancellationException) {
            Log.d("LeadScanWorker", "Worker cancelled.")
        } catch (e: Exception) {
            Log.e("LeadScanWorker", "Error in scanning background worker", e)
            setProgress(workDataOf(PROGRESS_STATUS to "Terjadi kesalahan: ${e.message}"))
            activeWorkerIds.remove(id)
            return Result.failure()
        } finally {
            activeWorkerIds.remove(id)
            db.close()
        }

        return Result.success()
    }
}
