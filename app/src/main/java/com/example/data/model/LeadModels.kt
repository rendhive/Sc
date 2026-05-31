package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Persisted lead retrieved from Places XML or JSON search.
 * It stores raw leads sequentially based on sequential coordinates scan.
 */
@Entity(tableName = "raw_leads")
data class RawLead(
    @PrimaryKey
    @ColumnInfo(name = "place_id")
    val placeId: String,
    val name: String,
    val phone: String?,
    val address: String?,
    val rating: Float?,
    @ColumnInfo(name = "has_photos")
    val hasPhotos: Boolean,
    val category: String,
    val region: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long = System.currentTimeMillis()
)

// --- OSM Nominatim Geo-Coding Models ---
@JsonClass(generateAdapter = true)
data class OsmGeocodeResult(
    val display_name: String,
    val lat: String,
    val lon: String,
    val boundingbox: List<String>? // [south_lat, north_lat, west_lon, east_lon]
)

// --- Google Places API (New) Models ---
@JsonClass(generateAdapter = true)
data class PlacesSearchRequest(
    val textQuery: String,
    val locationBias: LocationBias? = null
)

@JsonClass(generateAdapter = true)
data class LocationBias(
    val circle: CircleBias
)

@JsonClass(generateAdapter = true)
data class CircleBias(
    val center: LatLngPoint,
    val radius: Double // radius in meters
)

@JsonClass(generateAdapter = true)
data class LatLngPoint(
    val latitude: Double,
    val longitude: Double
)

@JsonClass(generateAdapter = true)
data class PlacesSearchResponse(
    val places: List<PlaceItem>? = null
)

@JsonClass(generateAdapter = true)
data class PlaceItem(
    val id: String,
    val displayName: DisplayNameItem? = null,
    val nationalPhoneNumber: String? = null,
    val formattedAddress: String? = null,
    val rating: Float? = null,
    val photos: List<PhotoItem>? = null,
    val location: LatLngPoint? = null
)

@JsonClass(generateAdapter = true)
data class DisplayNameItem(
    val text: String,
    val languageCode: String? = null
)

@JsonClass(generateAdapter = true)
data class PhotoItem(
    val name: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null
)
