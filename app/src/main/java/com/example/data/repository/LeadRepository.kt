package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.db.LeadDao
import com.example.data.model.CircleBias
import com.example.data.model.LatLngPoint
import com.example.data.model.LocationBias
import com.example.data.model.PlacesSearchRequest
import com.example.data.model.RawLead
import com.example.data.network.GooglePlacesApiService
import com.example.data.network.OsmApiService
import com.example.utils.ExcelExportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class GeoBoundary(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val centerLat: Double,
    val centerLon: Double,
    val displayName: String
)

class LeadRepository(
    private val leadDao: LeadDao,
    private val osmService: OsmApiService,
    private val googlePlacesService: GooglePlacesApiService
) {
    private val tag = "LeadRepository"

    val allRawLeads: Flow<List<RawLead>> = leadDao.getAllRawLeadsFlow()
    val rawLeadsCount: Flow<Int> = leadDao.getRawLeadsCountFlow()

    fun getPremiumLeadsCountFlow(minRating: Float): Flow<Int> =
        leadDao.getPremiumLeadsCountFlow(minRating)

    suspend fun clearLeads() {
        withContext(Dispatchers.IO) {
            leadDao.clearAllLeads()
        }
    }

    /**
     * Integrasi OpenStreetMap Nominatim.
     * Mengambil bounding box suatu daerah (level_wilayah) secara gratis.
     */
    suspend fun resolveRegionBoundary(regionName: String): GeoBoundary? {
        return withContext(Dispatchers.IO) {
            try {
                val results = osmService.searchGeocode(regionName)
                if (results.isNotEmpty()) {
                    val first = results[0]
                    val bbox = first.boundingbox
                    val centerLat = first.lat.toDoubleOrNull() ?: 0.0
                    val centerLon = first.lon.toDoubleOrNull() ?: 0.0

                    if (bbox != null && bbox.size == 4) {
                        // Nominatim returns: [southLatitude, northLatitude, westLongitude, eastLongitude]
                        val minLat = bbox[0].toDoubleOrNull() ?: (centerLat - 0.1)
                        val maxLat = bbox[1].toDoubleOrNull() ?: (centerLat + 0.1)
                        val minLon = bbox[2].toDoubleOrNull() ?: (centerLon - 0.1)
                        val maxLon = bbox[3].toDoubleOrNull() ?: (centerLon + 0.1)

                        GeoBoundary(
                            minLat = minLat,
                            maxLat = maxLat,
                            minLon = minLon,
                            maxLon = maxLon,
                            centerLat = centerLat,
                            centerLon = centerLon,
                            displayName = first.display_name
                        )
                    } else {
                        // Simple 10km bounds fallback around center
                        GeoBoundary(
                            minLat = centerLat - 0.05,
                            maxLat = centerLat + 0.05,
                            minLon = centerLon - 0.05,
                            maxLon = centerLon + 0.05,
                            centerLat = centerLat,
                            centerLon = centerLon,
                            displayName = first.display_name
                        )
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to resolve OSM boundary for $regionName", e)
                null
            }
        }
    }

    /**
     * Optimized call:
     * Request Google Places API (New) - Text Search with required Headers & coordinates bias.
     */
    suspend fun scanGridPoint(
        apiKey: String,
        category: String,
        lat: Double,
        lng: Double,
        radiusMeters: Double,
        regionName: String
    ): Int {
        return withContext(Dispatchers.IO) {
            val keyToUse = apiKey.ifEmpty { BuildConfig.PLACES_API_KEY }.trim()
            if (keyToUse.isEmpty() || keyToUse == "MY_PLACES_API_KEY_DEFAULT_VALUE") {
                Log.w(tag, "No Places API Key provided!")
                return@withContext 0
            }

            try {
                val request = PlacesSearchRequest(
                    textQuery = category,
                    locationBias = LocationBias(
                        circle = CircleBias(
                            center = LatLngPoint(lat, lng),
                            radius = radiusMeters
                        )
                    )
                )

                // Standard field mask limiting costs to text search advanced ($0.040/req)
                val fieldMask = "places.id,places.displayName,places.nationalPhoneNumber,places.formattedAddress,places.rating,places.photos,places.location"

                val response = googlePlacesService.searchPlaces(
                    apiKey = keyToUse,
                    fieldMask = fieldMask,
                    request = request
                )

                val places = response.places
                if (!places.isNullOrEmpty()) {
                    val rawLeads = places.map { item ->
                        val hasPhotos = !item.photos.isNullOrEmpty()
                        RawLead(
                            placeId = item.id,
                            name = item.displayName?.text ?: "Unknown Business",
                            phone = item.nationalPhoneNumber,
                            address = item.formattedAddress,
                            rating = item.rating,
                            hasPhotos = hasPhotos,
                            category = category,
                            region = regionName,
                            lat = item.location?.latitude ?: lat,
                            lng = item.location?.longitude ?: lng
                        )
                    }

                    // Save raw leads into Room Database with OnConflictStrategy.IGNORE
                    val insertResults = leadDao.insertLeads(rawLeads)
                    val mainInsertedCount = insertResults.count { it != -1L }
                    Log.d(tag, "Scanned coordinate ($lat, $lng) - Found ${places.size} centers, inserted $mainInsertedCount new distinct entries.")
                    mainInsertedCount
                } else {
                    0
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to scan grid point ($lat, $lng)", e)
                0
            }
        }
    }

    /**
     * Fetches up to 600 premium filtered B2B contacts and exports them to Excel.
     */
    suspend fun exportFilteredLeads(
        context: Context,
        category: String,
        region: String,
        minRating: Float
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Get filtered leads limited to 600
                val filteredLeads = leadDao.getPremiumLeads(minRating)
                if (filteredLeads.isEmpty()) {
                    Log.w(tag, "No leads matched the filtering criteria for Excel export!")
                    return@withContext null
                }

                ExcelExportHelper.exportLeadsToDownloads(
                    context = context,
                    category = category,
                    region = region,
                    premiumLeads = filteredLeads
                )
            } catch (e: Exception) {
                Log.e(tag, "Error exporting leads to Excel file", e)
                null
            }
        }
    }
}
