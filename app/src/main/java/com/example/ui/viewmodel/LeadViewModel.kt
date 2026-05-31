package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.db.LeadDatabase
import com.example.data.db.LeadDao
import com.example.data.model.RawLead
import com.example.data.network.NetworkClient
import com.example.data.repository.LeadRepository
import com.example.worker.LeadScanWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class LeadViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "LeadViewModel"
    private val context = application.applicationContext

    // Room Database and Repository initialized thread-safely
    private val database: LeadDatabase by lazy {
        androidx.room.Room.databaseBuilder(
            context,
            LeadDatabase::class.java,
            "b2b_leads_db"
        ).fallbackToDestructiveMigration().build()
    }

    val leadRepository: LeadRepository by lazy {
        LeadRepository(
            leadDao = database.leadDao,
            osmService = NetworkClient.osmService,
            googlePlacesService = NetworkClient.googlePlacesService
        )
    }

    // --- USER SETTIBLE PARAMS ---
    val category = MutableStateFlow("Restoran")
    val regionName = MutableStateFlow("Bandung")
    val radiusMeters = MutableStateFlow("1000") // standard grid scan size
    val minimalRating = MutableStateFlow("4.0") // dual criteria minimal rating
    val userApiKey = MutableStateFlow("") // custom override for direct client tests

    // --- SCAN LIVE STATUS ---
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _progressStatus = MutableStateFlow("Masukkan parameter dan ketuk Mulai Pemindaian.")
    val progressStatus: StateFlow<String> = _progressStatus.asStateFlow()

    private val _scannedPoints = MutableStateFlow(0)
    val scannedPoints: StateFlow<Int> = _scannedPoints.asStateFlow()

    private val _totalPoints = MutableStateFlow(0)
    val totalPoints: StateFlow<Int> = _totalPoints.asStateFlow()

    private val _resolvedBbox = MutableStateFlow<String?>(null)
    val resolvedBbox: StateFlow<String?> = _resolvedBbox.asStateFlow()

    private val _lastExportedFile = MutableStateFlow<String?>(null)
    val lastExportedFile: StateFlow<String?> = _lastExportedFile.asStateFlow()

    private val _scanMode = MutableStateFlow("Live Coroutine") // "Live Coroutine" or "WorkManager"
    val scanMode: StateFlow<String> = _scanMode.asStateFlow()

    // --- REUSE COUNTERS FROM DATABASE FLOW ---
    val rawLeads: StateFlow<List<RawLead>> = leadRepository.allRawLeads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rawLeadsCount: StateFlow<Int> = leadRepository.rawLeadsCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Reactive counter for leads matching priority criteria
    private val _premiumRatingThreshold = MutableStateFlow(4.0f)
    val premiumLeadsCount: StateFlow<Int> = MutableStateFlow(0).apply {
        viewModelScope.launch {
            _premiumRatingThreshold.collect { minR ->
                leadRepository.getPremiumLeadsCountFlow(minR).collect { count ->
                    (this@apply as MutableStateFlow).value = count
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var scanningJob: Job? = null
    private var workManagerObserverJob: Job? = null
    private val workManagerState: WorkManager? by lazy {
        try {
            WorkManager.getInstance(context)
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize or get WorkManager", e)
            null
        }
    }
    private var activeWorkerId: UUID? = null

    init {
        // Observe rating parameters to refresh counters
        viewModelScope.launch {
            minimalRating.collect { rStr ->
                val rating = rStr.toFloatOrNull() ?: 4.0f
                _premiumRatingThreshold.value = rating
            }
        }
    }

    /**
     * Start scan using Coroutines directly in App lifecycle.
     * This provides interactive updates.
     */
    fun startLiveScan() {
        if (_isScanning.value) return

        _isScanning.value = true
        _lastExportedFile.value = null
        _scanMode.value = "Live Coroutine"
        _scannedPoints.value = 0
        _totalPoints.value = 0
        _resolvedBbox.value = null
        _progressStatus.value = "Menghubungi OpenStreetMap..."

        val queryCat = category.value.trim()
        val queryRegion = regionName.value.trim()
        val rMeters = radiusMeters.value.toDoubleOrNull() ?: 1000.0
        val minR = minimalRating.value.toFloatOrNull() ?: 4.0f
        val customKey = userApiKey.value.trim()

        scanningJob = viewModelScope.launch {
            try {
                // 1. OSM Boundary Lookup
                val bounds = leadRepository.resolveRegionBoundary(queryRegion)
                if (bounds == null) {
                    _progressStatus.value = "Gagal memetakan batas wilayah OSM. Coba nama wilayah lain."
                    _isScanning.value = false
                    return@launch
                }

                _resolvedBbox.value = "BBox: (${String.format("%.4f", bounds.minLat)}, ${String.format("%.4f", bounds.minLon)}) s/d (${String.format("%.4f", bounds.maxLat)}, ${String.format("%.4f", bounds.maxLon)})"
                _progressStatus.value = "Wilayah diidentifikasi. Menghitung rangkaian grid..."

                // 2. Map coordinates grid
                // Delta lat (111km)
                val deltaLat = rMeters / 111000.0
                val latRad = Math.toRadians(bounds.centerLat)
                // Delta lon at latitude
                val deltaLon = rMeters / (111000.0 * Math.cos(latRad))

                val gridPoints = mutableListOf<Pair<Double, Double>>()
                var lat = bounds.minLat + (deltaLat / 2.0)
                while (lat < bounds.maxLat) {
                    var lon = bounds.minLon + (deltaLon / 2.0)
                    while (lon < bounds.maxLon) {
                        gridPoints.add(Pair(lat, lon))
                        lon += deltaLon
                    }
                    lat += deltaLat
                }

                if (gridPoints.isEmpty()) {
                    gridPoints.add(Pair(bounds.centerLat, bounds.centerLon))
                }

                val totalCount = gridPoints.size
                _totalPoints.value = totalCount
                _progressStatus.value = "Ditemukan $totalCount grid pemindaian. Memulai pencarian Places..."

                // 3. Scan sequential grid
                for ((index, point) in gridPoints.withIndex()) {
                    if (!_isScanning.value) {
                        _progressStatus.value = "Pemindaian dihentikan oleh pengguna."
                        break
                    }

                    _scannedPoints.value = index + 1
                    _progressStatus.value = "Memindai koordinat grid ${index + 1} dari $totalCount..."

                    leadRepository.scanGridPoint(
                        apiKey = customKey,
                        category = queryCat,
                        lat = point.first,
                        lng = point.second,
                        radiusMeters = rMeters,
                        regionName = queryRegion
                    )

                    // Check if 600 premium contacts retrieved
                    val currentPremiumCount = premiumLeadsCount.value
                    if (currentPremiumCount >= 600) {
                        _progressStatus.value = "Kuota 600 kontak premium berhasil terpenuhi secara tepat!"
                        break
                    }

                    // A brief polite delay between API requests
                    delay(1200)
                }

                // 4. Finished Scan - Export Workbook
                exportLeads()

            } catch (e: Exception) {
                Log.e(tag, "Scan error", e)
                _progressStatus.value = "Terjadi kesalahan: ${e.localizedMessage}"
                _isScanning.value = false
            }
        }
    }

    /**
     * Alternatif: Start background scan using WorkManager
     */
    fun startWorkManagerScan() {
        if (_isScanning.value) return

        val wm = workManagerState
        if (wm == null) {
            _progressStatus.value = "Background Scan tidak didukung di sistem ini."
            return
        }

        _isScanning.value = true
        _lastExportedFile.value = null
        _scanMode.value = "WorkManager (Background)"
        _scannedPoints.value = 0
        _totalPoints.value = 0
        _resolvedBbox.value = null
        _progressStatus.value = "Menjadwalkan WorkManager..."

        val inputData = Data.Builder()
            .putString(LeadScanWorker.KEY_CATEGORY, category.value.trim())
            .putString(LeadScanWorker.KEY_REGION, regionName.value.trim())
            .putDouble(LeadScanWorker.KEY_RADIUS, radiusMeters.value.toDoubleOrNull() ?: 1000.0)
            .putString(LeadScanWorker.KEY_API_KEY, userApiKey.value.trim())
            .putFloat(LeadScanWorker.KEY_MIN_RATING, minimalRating.value.toFloatOrNull() ?: 4.0f)
            .build()

        val scanRequest = OneTimeWorkRequestBuilder<LeadScanWorker>()
            .setInputData(inputData)
            .build()

        activeWorkerId = scanRequest.id

        wm.enqueueUniqueWork(
            "b2b_lead_scan_work",
            ExistingWorkPolicy.REPLACE,
            scanRequest
        )

        // Observe WorkManager progress states
        workManagerObserverJob?.cancel()
        workManagerObserverJob = viewModelScope.launch {
            wm.getWorkInfoByIdFlow(scanRequest.id).collect { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val msg = workInfo.progress.getString(LeadScanWorker.PROGRESS_STATUS) ?: "Memindai di background..."
                            _progressStatus.value = msg

                            val current = workInfo.progress.getInt(LeadScanWorker.PROGRESS_POINT_CURRENT, 0)
                            val total = workInfo.progress.getInt(LeadScanWorker.PROGRESS_POINT_TOTAL, 0)
                            _scannedPoints.value = current
                            _totalPoints.value = total
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _progressStatus.value = "Pemindaian background sukses dan Excel berhasil ditulis."
                            _isScanning.value = false
                            _scannedPoints.value = _totalPoints.value
                            // Automatically find generated spreadsheet file
                            _lastExportedFile.value = "Download/B2B_Leads/"
                        }
                        WorkInfo.State.FAILED -> {
                            _progressStatus.value = "Pemindaian background gagal."
                            _isScanning.value = false
                        }
                        WorkInfo.State.CANCELLED -> {
                            _progressStatus.value = "Pemindaian dibatalkan secara instan."
                            _isScanning.value = false
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    /**
     * Stop scanning instantly. Cancels either live Coroutine job or background WorkManager.
     */
    fun stopScanning() {
        _isScanning.value = false

        // Cancel live Coroutine
        scanningJob?.cancel()
        scanningJob = null

        // Cancel WorkManager background task instantly
        val wm = workManagerState
        if (wm != null) {
            activeWorkerId?.let { id ->
                wm.cancelWorkById(id)
                activeWorkerId = null
            }
            wm.cancelUniqueWork("b2b_lead_scan_work")
        }

        workManagerObserverJob?.cancel()
        workManagerObserverJob = null

        _progressStatus.value = "Pemindaian dihentikan secara instan untuk melindungi kuota API Google."
    }

    /**
     * Export contacts currently stored to stylized XLS
     */
    fun exportLeads() {
        viewModelScope.launch {
            _progressStatus.value = "Mengekspor prospek ke Excel..."
            val path = leadRepository.exportFilteredLeads(
                context = context,
                category = category.value,
                region = regionName.value,
                minRating = minimalRating.value.toFloatOrNull() ?: 4.0f
            )

            if (path != null) {
                _progressStatus.value = "Selesai! File berhasil disimpan di: $path"
                _lastExportedFile.value = path
            } else {
                _progressStatus.value = "Pengeksporan gagal. Pastikan target data premium tidak kosong."
            }
            _isScanning.value = false
        }
    }

    /**
     * Triggers database clear row logic
     */
    fun clearDb() {
        viewModelScope.launch {
            leadRepository.clearLeads()
            _lastExportedFile.value = null
            _progressStatus.value = "Database lokal berhasil dibersihkan!"
            _scannedPoints.value = 0
            _totalPoints.value = 0
            _resolvedBbox.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanningJob?.cancel()
        workManagerObserverJob?.cancel()
    }
}
