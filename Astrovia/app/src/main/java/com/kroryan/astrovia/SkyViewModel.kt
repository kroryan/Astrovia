@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kroryan.astrovia.data.hips.HiPSProperties
import com.kroryan.astrovia.data.hips.HiPSPropertiesParser
import com.kroryan.astrovia.data.hips.HiPSSurvey
import com.kroryan.astrovia.data.hips.SurveyRegistry
import com.kroryan.astrovia.data.hips.TileRepository
import com.kroryan.astrovia.data.simbad.SimbadObject
import com.kroryan.astrovia.data.simbad.SimbadService
import com.kroryan.astrovia.data.vizier.VizieRService
import com.kroryan.astrovia.model.CatalogRow
import com.kroryan.astrovia.data.archive.ArchiveHit
import com.kroryan.astrovia.data.archive.ArchiveService
import com.kroryan.astrovia.data.archive.ArchiveSource
import com.kroryan.astrovia.data.archive.Publication
import com.kroryan.astrovia.data.archive.SolarSystemBody
import com.kroryan.astrovia.model.Target
import com.kroryan.astrovia.model.FitsDocument
import com.kroryan.astrovia.model.FitsOverlay
import com.kroryan.astrovia.data.fits.ColorMap
import com.kroryan.astrovia.data.fits.FitsRender
import com.kroryan.astrovia.data.fits.Stretch
import com.kroryan.astrovia.data.files.FileOpener
import com.kroryan.astrovia.data.files.OpenedFile
import com.kroryan.astrovia.model.Marker
import com.kroryan.astrovia.model.Measurement
import com.kroryan.astrovia.capture.CaptureFormat
import com.kroryan.astrovia.capture.CaptureOptions
import com.kroryan.astrovia.capture.CaptureState
import com.kroryan.astrovia.capture.RenderedCapture
import com.kroryan.astrovia.capture.SkyCapture
import com.kroryan.astrovia.model.SkyPosition
import com.kroryan.astrovia.sky.SkyCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SkyUiState(
    val camera: SkyCamera = SkyCamera(),
    /** False until the first survey has positioned the camera; later surveys keep the user's view. */
    val cameraPlaced: Boolean = false,
    val capture: CaptureState = CaptureState.Idle,
    val survey: HiPSSurvey = SurveyRegistry.builtIn.first(),
    val availableSurveys: List<HiPSSurvey> = SurveyRegistry.builtIn,
    val surveysLoading: Boolean = false,
    val props: HiPSProperties? = null,
    val propsLoading: Boolean = true,
    val catalogRows: List<CatalogRow> = emptyList(),
    val catalogLoading: Boolean = false,
    val markers: List<Marker> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val selected: SimbadObject? = null,
    val identifying: Boolean = false,
    val searching: Boolean = false,
    val message: String? = null,
    val tileVersion: Int = 0,
    val measureMode: Boolean = false,
    val pendingMeasureFrom: SkyPosition? = null,
    val settings: AppSettings = AppSettings(),
    val downloadingTiles: Int = 0,
    val catalogVisible: Boolean = true,
    val activeCatalogId: String? = null,
    // Multi-archive search (observations, spectra, catalogues, alerts, solar system, exoplanets...)
    val archiveHits: List<ArchiveHit> = emptyList(),
    val archiveLabel: String? = null,
    val archiveLoading: Boolean = false,
    val archiveVisible: Boolean = true,
    val selectedHit: ArchiveHit? = null,
    val solarBodies: List<SolarSystemBody> = emptyList(),
    val publications: List<Publication>? = null,
    val publicationsFor: String? = null,
    val targets: List<Target> = emptyList(),
    val lists: Map<String, List<Target>> = emptyMap(),
    val builtInLists: Map<String, List<Target>> = emptyMap(),
    val currentList: String = "My targets",
    val objectInfo: com.kroryan.astrovia.data.archive.ObjectInfo? = null,
    val objectLoading: Boolean = false,
    // Files opened by the user
    val fitsDoc: FitsDocument? = null,
    val fitsOverlay: FitsOverlay? = null,
    val fileLoading: Boolean = false,
    val lightCurve: com.kroryan.astrovia.data.fits.LightCurve? = null,
    val archiveSources: List<ArchiveSource> = emptyList(),
    val plan: PlanState? = null,
    val fieldObjects: List<com.kroryan.astrovia.data.archive.FieldObject>? = null,
    val customTables: List<String>? = null,
    val customTap: String = "",
    val viewerImage: com.kroryan.astrovia.data.gallery.OutreachImage? = null,
    val quadOverlay: com.kroryan.astrovia.model.QuadOverlay? = null,
    val gallery: List<com.kroryan.astrovia.data.gallery.OutreachImage>? = null,
    val galleryMission: String = "webb",
    val hitFilter: HitFilter? = null,
    val fieldCounts: Map<String, Int> = emptyMap(),
    val scanProgress: Pair<Int, Int>? = null,
) {
    /** Archive results after the chart filter (tap a bar or a year in the charts). */
    val visibleHits: List<ArchiveHit> get() = hitFilter?.let { f -> archiveHits.filter(f.test) } ?: archiveHits
}

/** A filter chosen by tapping a chart: [label] is shown to the user, [test] decides which results stay. */
class HitFilter(val label: String, val test: (ArchiveHit) -> Boolean)

/** An instrument footprint placed on the sky to plan an observation. */
data class PlanState(val key: String, val raDeg: Double, val decDeg: Double, val paDeg: Double)

class SkyViewModel(app: Application) : AndroidViewModel(app) {
    private val simbad = SimbadService()
    private val vizier = VizieRService()
    // Persistent settings (cache, quality, concurrency). Loaded before
    // building the TileRepository so that the memory cache and concurrency
    // start with the saved values.
    private var settings = AppSettings.load(app)
    val tileRepo = TileRepository(app, settings.memCacheMb, settings.concurrency)
    val downloader = com.kroryan.astrovia.data.hips.HiPSDownloader(app)
    val catalogDownloader = com.kroryan.astrovia.data.vizier.CatalogDownloader(app)
    val archives: ArchiveService = ArchiveService(app)

    // Releases the tile bitmaps when the app is hidden or the system is short of memory.
    private val memoryCallbacks = object : android.content.ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) { tileRepo.trimMemory(level) }
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        @Deprecated("Superseded by onTrimMemory")
        override fun onLowMemory() { tileRepo.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
    }

    private val _state = MutableStateFlow(SkyUiState())
    val state: StateFlow<SkyUiState> = _state.asStateFlow()

    init {
        // The tile repository notifies (coalesced) when new bitmaps arrive
        // -> we increment tileVersion to force a Canvas redraw. The same
        // notification triggers when tiles are QUEUED (see TileRepository.request), so
        // we take advantage of it to read activeDownloads and feed the loading
        // indicator: a separate flow is not needed, it already coalesces and synchronizes itself.
        tileRepo.onTilesChanged = {
            _state.update { it.copy(tileVersion = it.tileVersion + 1, downloadingTiles = tileRepo.activeDownloads) }
        }
        app.registerComponentCallbacks(memoryCallbacks)
        _state.update { it.copy(settings = settings, archiveSources = archives.allSources()) }
        loadLists()
        loadSurveyProperties(_state.value.survey)
        fetchSurveys()
    }

    private fun localDirs(): List<java.io.File> =
        listOf(java.io.File(app.getExternalFilesDir(null), "HiPS_Downloads")) + settings.customHiPSDirs.map { java.io.File(it) }

    /** Rescans the downloaded HiPS (no network): call it after a download so it shows up as "(offline)". */
    fun refreshOfflineSurveys() {
        viewModelScope.launch {
            val offline = withContext(Dispatchers.IO) { com.kroryan.astrovia.data.hips.HiPSListService.scanLocal(localDirs()) }
            _state.update { st ->
                val rest = st.availableSurveys.filterNot { com.kroryan.astrovia.data.hips.HiPSListService.isOffline(it) }
                if (offline.map { it.id } == st.availableSurveys.filter { com.kroryan.astrovia.data.hips.HiPSListService.isOffline(it) }.map { it.id }) st
                else st.copy(availableSurveys = offline + rest)
            }
        }
    }

    fun fetchSurveys() {
        _state.update { it.copy(surveysLoading = true) }
        viewModelScope.launch {
            val localDirs = mutableListOf<java.io.File>()
            localDirs.add(java.io.File(app.getExternalFilesDir(null), "HiPS_Downloads"))
            settings.customHiPSDirs.forEach { localDirs.add(java.io.File(it)) }
            
            val list = com.kroryan.astrovia.data.hips.HiPSListService.fetchAvailableSurveys(localDirs, java.io.File(app.filesDir, "surveys-cache.json"))
            
            _state.update { 
                // If offline and properties failed, auto-select local
                var surveyToSet = it.survey
                var loading = it.propsLoading
                var msg = it.message
                if (it.props == null && !it.propsLoading) {
                    val firstLocal = list.firstOrNull { s -> s.id.endsWith("_local") }
                    if (firstLocal != null && it.survey.id != firstLocal.id) {
                        surveyToSet = firstLocal
                        loading = true
                        msg = "Offline: Using local dataset"
                    }
                }
                it.copy(availableSurveys = list, surveysLoading = false, survey = surveyToSet, propsLoading = loading, message = msg)
            }
            
            if (_state.value.propsLoading && _state.value.props == null) {
                // We auto-switched to a local survey, so load it
                loadSurveyProperties(_state.value.survey)
            }
        }
    }

    // --- Settings ---
    /** Applies new settings: persists, reconfigures the repo and recalculates visible
     *  tiles (the quality `targetScale` changes tile density). */
    fun setSettings(s: AppSettings) {
        settings = s
        AppSettings.save(app, s)
        tileRepo.setMemCacheMb(s.memCacheMb)
        tileRepo.setConcurrency(s.concurrency)
        // Trigger recalculation: settings changed -> the collect re-emits and
        // computeVisibleTiles uses the new targetScale. We force an emission
        // by touching tileVersion as well in case the camera did not change.
        _state.update { it.copy(settings = s, tileVersion = it.tileVersion + 1) }
    }

    /** Clears memory and disk cache. */
    fun clearCache() {
        tileRepo.clearAll()
        _state.update { it.copy(tileVersion = it.tileVersion + 1) }
    }

    fun addCustomHiPSDir(path: String) {
        val newDirs = settings.customHiPSDirs + path
        setSettings(settings.copy(customHiPSDirs = newDirs))
        fetchSurveys()
    }

    fun setSurvey(survey: HiPSSurvey) {
        // DO NOT clear props or survey here. If we did, the screen would
        // be left without tiles during the `properties` fetch (~3s on the CDS
        // server) —or forever if the fetch fails—. Instead, we mark loading
        // and leave the old survey visible until the new `properties` arrive.
        // Then we swap survey+props ATOMICALLY (see loadSurveyProperties):
        // this way the fetch of new tiles never uses the new survey with old props
        // (mixing baseUrls/orders would result in wrong tiles).
        if (survey.id == _state.value.survey.id) return
        _state.update { it.copy(propsLoading = true, message = null) }
        loadSurveyProperties(survey)
    }

    // Only the latest survey request may apply its result: without this, a slow
    // response for a previously chosen survey could overwrite a newer selection.
    private var propsJob: kotlinx.coroutines.Job? = null

    private fun loadSurveyProperties(survey: HiPSSurvey) {
        propsJob?.cancel()
        propsJob = viewModelScope.launch {
            val props = fetchProperties(survey)
            _state.update {
                if (props != null) {
                    // Atomic swap of survey+props. The old survey remains visible until exactly
                    // here; from here on the draw and fetch use the new survey (and its new
                    // cache key, see TileRepository).
                    //
                    // The camera is only placed for the FIRST survey. Changing survey used to
                    // jump to that survey's initial position, losing whatever the user was
                    // looking at (impossible to compare DSS and 2MASS on the same object); the
                    // catalogue overlay was cleared too although it does not depend on the survey.
                    // Planets are shown as a disc (ZEA); sky surveys as the all-sky oval (Hammer-Aitoff).
                    val kind = if (props.skyFrame == com.kroryan.astrovia.sky.SkyFrame.BODY) {
                        com.kroryan.astrovia.sky.ProjectionKind.ZEA
                    } else {
                        com.kroryan.astrovia.sky.ProjectionKind.HAMMER
                    }
                    val cam = if (it.cameraPlaced) {
                        it.camera.copy(kind = kind).clampedTo(viewW, viewH)
                    } else {
                        val ic = props.initialIcrs
                        val fov = props.initialFov.coerceIn(SkyCamera.MIN_FOV, MAX_INITIAL_FOV)
                        SkyCamera.looking(SkyPosition(ic[0], ic[1]), fov, kind).clampedTo(viewW, viewH)
                    }
                    it.copy(
                        survey = survey,
                        props = props,
                        propsLoading = false,
                        camera = cam,
                        cameraPlaced = true,
                    )
                } else {
                    // Failed fetch
                    val firstLocal = it.availableSurveys.firstOrNull { s -> s.id.endsWith("_local") }
                    if (firstLocal != null && survey.id != firstLocal.id) {
                        // Switch to local dataset
                        it.copy(propsLoading = false, message = "Offline: Switching to local dataset")
                    } else {
                        it.copy(propsLoading = false, message = "Could not load survey")
                    }
                }
            }
            
            if (!_state.value.propsLoading && _state.value.props == null && _state.value.message == "Offline: Switching to local dataset") {
                val local = _state.value.availableSurveys.firstOrNull { s -> s.id.endsWith("_local") }
                if (local != null) setSurvey(local)
            }
        }
    }

    private suspend fun fetchProperties(survey: HiPSSurvey): HiPSProperties? =
        withContext(Dispatchers.IO) {
            val url = survey.propertiesUrl()
            try {
                val text = if (url.startsWith("file://")) {
                    val file = java.io.File(java.net.URI(url))
                    if (file.exists()) {
                        file.readText()
                    } else {
                        android.util.Log.w("SkyViewModel", "properties file missing: ${file.absolutePath}")
                        return@withContext null
                    }
                } else {
                    val r = com.kroryan.astrovia.data.net.HttpClient.client
                        .newCall(okhttp3.Request.Builder().url(url).build()).execute()
                    r.use { resp ->
                        if (!resp.isSuccessful) {
                            android.util.Log.w("SkyViewModel", "properties HTTP ${resp.code} for $url")
                            return@withContext null
                        }
                        resp.body?.string() ?: return@withContext null
                    }
                }
                HiPSPropertiesParser.parse(survey.baseUrl, text)
            } catch (e: Exception) {
                android.util.Log.w("SkyViewModel", "properties fetch failed for $url", e)
                null
            }
        }

    // --- Camera ---
    private var viewW = 0f
    private var viewH = 0f
    private var flingJob: Job? = null
    private var northJob: Job? = null

    /** Viewport size changed (first layout or rotation): keep the FoV inside what the screen can show. */
    fun setViewport(w: Float, h: Float) {
        viewW = w
        viewH = h
        _state.update { it.copy(camera = it.camera.clampedTo(w, h)) }
    }

    /**
     * One gesture step: the previous centroid (px, py) moved to (nx, ny) while the fingers changed
     * their distance by [zoom] and their angle by [twistDeg] (clockwise). The sky point under the
     * previous centroid follows the fingers exactly.
     */
    fun transform(px: Float, py: Float, nx: Float, ny: Float, zoom: Float, twistDeg: Double, w: Float, h: Float) {
        stopMotion()
        _state.update { s ->
            var cam = s.camera
            if (zoom != 1f) cam = cam.zoom(zoom, w, h, px, py)
            if (twistDeg != 0.0) cam = cam.twist(twistDeg, w, h, px, py)
            cam = cam.drag(px, py, nx, ny, w, h)
            s.copy(camera = cam)
        }
    }

    /** Pure zoom (double tap): [factor] > 1 zooms in around (fx, fy). */
    fun zoomBy(factor: Float, w: Float, h: Float, fx: Float, fy: Float) {
        stopMotion()
        _state.update { it.copy(camera = it.camera.zoom(factor, w, h, fx, fy)) }
    }

    fun recenter(pos: SkyPosition) {
        stopMotion()
        _state.update { it.copy(camera = it.camera.recenterTo(pos)) }
    }

    /** Inertia after a drag. (vx, vy) is the finger velocity in px/s; it decays on its own. */
    fun fling(vx: Float, vy: Float, w: Float, h: Float) {
        stopMotion()
        if (hypot(vx, vy) < 250f) return
        flingJob = viewModelScope.launch {
            var x = vx
            var y = vy
            val dt = 16L
            while (hypot(x, y) > 40f) {
                val dx = x * dt / 1000f
                val dy = y * dt / 1000f
                _state.update { it.copy(camera = it.camera.drag(w / 2f, h / 2f, w / 2f + dx, h / 2f + dy, w, h)) }
                x *= 0.94f
                y *= 0.94f
                delay(dt)
            }
        }
    }

    /** Compass button: smoothly rotate the picture so celestial north points up. */
    fun alignNorth() {
        stopMotion()
        northJob = viewModelScope.launch {
            repeat(14) {
                val a = _state.value.camera.northAngleDeg
                if (abs(a) < 0.05) return@repeat
                _state.update { it.copy(camera = it.camera.rolledBy(-a * 0.3)) }
                delay(16)
            }
            _state.update { it.copy(camera = it.camera.alignedNorthUp()) }
        }
    }

    private fun stopMotion() {
        flingJob?.cancel()
        northJob?.cancel()
    }

    // --- Simbad Search ---
    fun searchObject(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(searching = true, message = null) }
        viewModelScope.launch {
            var first: SimbadObject? = null
            var msg: String
            try {
                first = simbad.search(query).firstOrNull()
                msg = if (first != null) "Found ${first.mainId}" else app.getString(R.string.no_results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // Not the same as "no results": the service could not be reached.
                msg = "Could not reach SIMBAD (${e.message ?: "no connection"})"
            } catch (e: Exception) {
                msg = "Search failed: ${e.message}"
            }
            val found = first
            stopMotion()
            _state.update {
                it.copy(
                    searching = false,
                    selected = found,
                    message = msg,
                    camera = if (found != null) it.camera.recenterTo(found.position) else it.camera,
                )
            }
            clearMessageLater(msg)
        }
    }

    private fun clearMessageLater(msg: String) {
        viewModelScope.launch {
            delay(3500)
            _state.update { if (it.message == msg) it.copy(message = null) else it }
        }
    }

    fun identifyAt(raDeg: Double, decDeg: Double, radiusArcmin: Double = 0.5) {
        _state.update { it.copy(identifying = true, message = null) }
        viewModelScope.launch {
            var obj: SimbadObject? = null
            var msg: String? = null
            try {
                obj = simbad.coneSearch(raDeg, decDeg, radiusArcmin = radiusArcmin)
                if (obj == null) msg = "No SIMBAD object near that point"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = "Could not reach SIMBAD (${e.message ?: "no connection"})"
            }
            _state.update { it.copy(identifying = false, selected = obj, message = msg ?: it.message) }
            msg?.let { clearMessageLater(it) }
        }
    }

    fun clearSelected() = _state.update { it.copy(selected = null) }

    // --- VizieR Catalogs ---
    /**
     * Long press: picks the nearest catalogue source within [radiusDeg] (the caller converts a
     * finger-sized radius in pixels to degrees, so it works at any zoom; it used to be a fixed
     * 0.5 deg: at high zoom everything matched, at low zoom nothing could). With no catalogue
     * loaded, it asks SIMBAD what is there.
     */
    fun longPressAt(pos: SkyPosition, radiusDeg: Double) {
        hitAt(pos, radiusDeg)?.let { hit -> _state.update { it.copy(selectedHit = hit) }; return }
        val rows = _state.value.catalogRows
        if (rows.isEmpty() || !_state.value.catalogVisible) {
            identifyAt(pos.raDeg, pos.decDeg, radiusArcmin = (radiusDeg * 60.0).coerceIn(0.02, 30.0))
            return
        }
        val closest = rows.minByOrNull { SkyPosition.separation(pos, SkyPosition(it.raDeg, it.decDeg)) } ?: return
        val dist = SkyPosition.separation(pos, SkyPosition(closest.raDeg, closest.decDeg))
        if (dist <= radiusDeg) {
            val obj = SimbadObject(
                mainId = closest.id,
                otype = closest.columns["otype"] ?: "Catalog Source",
                position = SkyPosition(closest.raDeg, closest.decDeg),
                magV = closest.mag,
                raw = closest.columns,
            )
            _state.update { it.copy(selected = obj) }
        }
    }

    fun loadCatalog(catalogId: String, maxRows: Int = 500) {
        val s = _state.value
        if (s.props?.skyFrame == com.kroryan.astrovia.sky.SkyFrame.BODY) {
            // Catalogues are lists of SKY objects: on a planetary map they will not line up with anything.
            val note = "Note: catalogues describe the sky, not this planetary map"
            _state.update { it.copy(message = note) }
            clearMessageLater(note)
        }
        val radius = (s.camera.fovDeg / 2.0).coerceIn(0.05, 60.0) * 60.0 // arcmin
        _state.update { it.copy(catalogLoading = true, message = null, activeCatalogId = catalogId) }
        viewModelScope.launch {
            try {
                val rows = if (catalogId == "SIMBAD") {
                    simbad.searchRegion(s.camera.center.raDeg, s.camera.center.decDeg, radius, maxRows).map { simbad.toCatalogRow(it) }
                } else {
                    vizier.coneSearch(catalogId, s.camera.center.raDeg, s.camera.center.decDeg, radius, maxRows)
                }
                val msg = when {
                    rows.isEmpty() -> "No objects found in this area"
                    maxRows > 0 && rows.size >= maxRows ->
                        "Showing the first ${rows.size} objects (the limit): zoom in or raise the limit to see more"
                    else -> "Loaded ${rows.size} objects"
                }
                _state.update { it.copy(catalogLoading = false, catalogRows = rows, message = msg) }
                clearMessageLater(msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = if (e is java.io.IOException) "Could not reach the catalogue service (${e.message ?: "no connection"})" else "Failed: ${e.message}"
                _state.update { it.copy(catalogLoading = false, message = msg) }
                clearMessageLater(msg)
            }
        }
    }

    /** Shows a catalogue that was downloaded earlier (a VOTable file) as the overlay. */
    fun showCatalogFile(path: String) {
        _state.update { it.copy(catalogLoading = true, message = null) }
        viewModelScope.launch {
            var msg: String
            try {
                val rows = withContext(Dispatchers.IO) { vizier.rowsFromVotable(java.io.File(path).readText()) }
                _state.update { it.copy(catalogLoading = false, catalogRows = rows, catalogVisible = true, activeCatalogId = null) }
                msg = if (rows.isEmpty()) "That file has no positions to show" else "Showing ${rows.size} objects from ${java.io.File(path).name}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(catalogLoading = false) }
                msg = "Could not read that catalogue: ${e.message}"
            }
            _state.update { it.copy(message = msg) }
            clearMessageLater(msg)
        }
    }

    fun clearCatalog() = _state.update { it.copy(catalogRows = emptyList(), catalogVisible = true, activeCatalogId = null) }

    fun toggleCatalogVisibility() = _state.update { it.copy(catalogVisible = !it.catalogVisible) }

    // --- Sky capture (screenshot of the HiPS only) ---
    private var captureJob: Job? = null
    private var pendingCapture: RenderedCapture? = null
    private var pendingFormat: CaptureFormat = CaptureFormat.PNG

    /** Sizes (scale -> pixels) the current screen can export on this device. */
    fun captureScales(): List<Int> = SkyCapture.availableScales(viewW.toInt(), viewH.toInt())

    fun captureSize(scale: Int): Pair<Int, Int> = SkyCapture.outputSize(viewW.toInt(), viewH.toInt(), scale)

    /**
     * Renders the sky picture currently on screen (and nothing else) at up to 3x the screen size,
     * with the finest tiles available, and saves it. See [SkyCapture].
     */
    fun captureSky(options: CaptureOptions) {
        if (captureJob?.isActive == true) return
        val snapshot = _state.value
        if (snapshot.props == null || viewW <= 0f || viewH <= 0f) {
            _state.update { it.copy(capture = CaptureState.Failed("Nothing to capture yet: wait for the sky to load.")) }
            return
        }
        captureJob = viewModelScope.launch {
            try {
                val r = SkyCapture.render(tileRepo, snapshot, viewW.toInt(), viewH.toInt(), options) { p ->
                    _state.update { it.copy(capture = p) }
                }
                _state.update { it.copy(capture = CaptureState.Running(com.kroryan.astrovia.capture.CapturePhase.ENCODING, 0, 0)) }
                val w = r.bitmap.width
                val h = r.bitmap.height
                // 1) the folder the user chose in Settings (works on every Android version)
                val tree = settings.captureDirUri?.let { Uri.parse(it) }
                var saved: Triple<Uri, Long, String>? = null
                var treeProblem: String? = null
                if (tree != null) {
                    try {
                        val (uri, bytes) = SkyCapture.saveToTree(app, r, options.format, tree)
                        saved = Triple(uri, bytes, SkyCapture.folderLabel(app, tree))
                    } catch (e: SecurityException) {
                        treeProblem = "The chosen capture folder is no longer accessible (choose it again in Settings)."
                    } catch (e: java.io.IOException) {
                        treeProblem = "Could not save in the chosen folder: ${e.message}"
                    }
                }
                // 2) default: Pictures/Astrovia (Android 10+, no permission needed)
                if (saved == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val (uri, bytes) = SkyCapture.saveToGallery(app, r, options.format)
                    saved = Triple(uri, bytes, "Pictures/Astrovia" + (treeProblem?.let { " ($it)" } ?: ""))
                }
                if (saved != null) {
                    r.bitmap.recycle()
                    val (uri, bytes, where) = saved
                    _state.update { it.copy(capture = CaptureState.Saved(uri, w, h, bytes, r.missingTiles, where)) }
                } else {
                    // 3) Android 7-9 with no folder set: ask where to save (no storage permission needed that way).
                    pendingCapture = r
                    pendingFormat = options.format
                    _state.update { it.copy(capture = CaptureState.AwaitingLocation(r.name, w, h, options.format)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(capture = CaptureState.Failed("Not enough memory for that size. Choose a smaller scale.")) }
            } catch (e: Exception) {
                _state.update { it.copy(capture = CaptureState.Failed(e.message ?: "The capture failed")) }
            }
        }
    }

    /**
     * Chooses (or clears, with null) the folder where captures are saved. The permission is made
     * persistent so it survives restarts.
     */
    fun setCaptureDir(uri: Uri?) {
        if (uri != null) {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                _state.update { it.copy(message = "Could not keep access to that folder") }
                return
            }
        }
        settings.captureDirUri?.let { old ->
            if (uri == null || old != uri.toString()) {
                try {
                    app.contentResolver.releasePersistableUriPermission(
                        Uri.parse(old), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: Exception) { /* it may already be gone */ }
            }
        }
        setSettings(settings.copy(captureDirUri = uri?.toString()))
    }

    /** Label of where captures go, for the UI. */
    fun captureLocationLabel(): String =
        settings.captureDirUri?.let { SkyCapture.folderLabel(app, Uri.parse(it)) }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Pictures/Astrovia" else "Ask every time"

    /** Android 7-9: the user picked a destination (null = cancelled). */
    fun saveCaptureTo(uri: Uri?) {
        val r = pendingCapture
        if (r == null) return
        if (uri == null) {
            dismissCapture()
            return
        }
        viewModelScope.launch {
            try {
                _state.update { it.copy(capture = CaptureState.Running(com.kroryan.astrovia.capture.CapturePhase.ENCODING, 0, 0)) }
                val bytes = SkyCapture.saveToUri(app, r, pendingFormat, uri)
                val w = r.bitmap.width
                val h = r.bitmap.height
                r.bitmap.recycle()
                pendingCapture = null
                _state.update { it.copy(capture = CaptureState.Saved(uri, w, h, bytes, r.missingTiles, "the location you chose")) }
            } catch (e: Exception) {
                _state.update { it.copy(capture = CaptureState.Failed(e.message ?: "Could not save the image")) }
            }
        }
    }

    /** Cancels a running capture or closes its result. */
    fun dismissCapture() {
        captureJob?.cancel()
        pendingCapture?.bitmap?.recycle()
        pendingCapture = null
        _state.update { it.copy(capture = CaptureState.Idle) }
    }

    // --- Markers and Measurements ---
    fun addMarker(pos: SkyPosition, label: String = "M${state.value.markers.size + 1}") {
        _state.update {
            it.copy(markers = it.markers + Marker(position = pos, label = label))
        }
    }

    fun clearMarkers() = _state.update { it.copy(markers = emptyList()) }

    fun toggleMeasureMode() = _state.update {
        it.copy(measureMode = !it.measureMode, pendingMeasureFrom = null)
    }

    /** Taps the sky in measure mode: first tap sets the origin, second sets the destination. */
    fun measureTap(pos: SkyPosition) {
        val s = _state.value
        val from = s.pendingMeasureFrom
        if (from == null) {
            _state.update { it.copy(pendingMeasureFrom = pos) }
        } else {
            _state.update {
                it.copy(
                    pendingMeasureFrom = null,
                    measurements = it.measurements + Measurement(from = from, to = pos),
                )
            }
        }
    }

    fun clearMeasurements() = _state.update { it.copy(measurements = emptyList(), pendingMeasureFrom = null) }



    /** Opens any HiPS given its root URL (online) with its title read from the `properties` file. */
    fun openHipsUrl(url: String) {
        val base = com.kroryan.astrovia.data.hips.HiPSListService.upgradeToHttps(url).trimEnd('/').removeSuffix("/properties")
        viewModelScope.launch {
            val props = withContext(Dispatchers.IO) {
                runCatching {
                    com.kroryan.astrovia.data.net.HttpClient.client.newCall(okhttp3.Request.Builder().url("$base/properties").build()).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.string()
                    }
                }.getOrNull()
            }
            if (props == null) { toast("No HiPS found at that address (no properties file)"); return@launch }
            val map = props.lines().mapNotNull { l -> val i = l.indexOf('='); if (i <= 0 || l.trimStart().startsWith("#")) null else l.substring(0, i).trim() to l.substring(i + 1).trim() }.toMap()
            val title = map["obs_title"] ?: base.substringAfterLast('/')
            val sv = HiPSSurvey(id = "url:$base", title = title, baseUrl = base, isColor = (map["dataproduct_subtype"] ?: "").contains("color", true),
                description = map["obs_description"].orEmpty(), frame = map["hips_frame"] ?: "equatorial", formats = map["hips_tile_format"].orEmpty(),
                category = "Opened by URL", regime = map["obs_regime"].orEmpty())
            _state.update { it.copy(availableSurveys = listOf(sv) + it.availableSurveys.filter { x -> x.id != sv.id }) }
            setSurvey(sv)
        }
    }



    // --- Outreach pictures (Webb, Hubble, Euclid) ---
    fun loadGallery(mission: String) {
        _state.update { it.copy(galleryMission = mission, gallery = null, archiveLoading = true) }
        viewModelScope.launch {
            val g = try { archives.gallery(mission) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast("Gallery: ${e.message ?: "not reachable"}"); null }
            _state.update { if (it.galleryMission == mission) it.copy(gallery = g, archiveLoading = false) else it }
        }
    }

    fun openImage(img: com.kroryan.astrovia.data.gallery.OutreachImage) = _state.update { it.copy(viewerImage = img) }

    fun closeImage() = _state.update { it.copy(viewerImage = null) }

    fun goToImage(img: com.kroryan.astrovia.data.gallery.OutreachImage) {
        stopMotion()
        val fov = ((img.fovDeg ?: 0.2) * 1.4).coerceIn(0.002, 180.0)
        _state.update { it.copy(viewerImage = null, camera = SkyCamera.looking(SkyPosition(img.raDeg, img.decDeg), fov), cameraPlaced = true) }
    }

    /** Places the picture over the sky between its four corners (a coarse copy of it, up to about 1800 px). */
    fun showImageOnSky(img: com.kroryan.astrovia.data.gallery.OutreachImage) {
        if (img.corners.size < 4) { toast("This picture has no sky corners"); return }
        _state.update { it.copy(fileLoading = true) }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val p = com.kroryan.astrovia.data.gallery.TilePyramids.open(img.tilesUrl) { u -> com.kroryan.astrovia.data.gallery.TileLoader.fetchText(u) }
                    if (p == null) null else com.kroryan.astrovia.data.gallery.TileLoader.stitch(p)
                }.getOrNull()
            }
            if (bmp == null) { _state.update { it.copy(fileLoading = false) }; toast("Could not load the picture"); return@launch }
            val ra = DoubleArray(4) { img.corners[it].first }; val dec = DoubleArray(4) { img.corners[it].second }
            val fov = ((img.fovDeg ?: 0.2) * 1.4).coerceIn(0.002, 180.0)
            stopMotion()
            _state.update { it.copy(fileLoading = false, viewerImage = null, quadOverlay = com.kroryan.astrovia.model.QuadOverlay(bmp, ra, dec), camera = SkyCamera.looking(SkyPosition(img.raDeg, img.decDeg), fov), cameraPlaced = true) }
            toast("${img.title} placed on the sky")
        }
    }

    fun clearQuadOverlay() = _state.update { it.copy(quadOverlay = null) }

    // --- Light curves (TESS, Kepler, K2 and any FITS table with a time and a flux column) ---
    fun closeLightCurve() = _state.update { it.copy(lightCurve = null) }

    /** Downloads a light-curve FITS product (e.g. a MAST *_lc.fits) and plots it. */
    fun plotLightCurve(url: String, title: String) {
        _state.update { it.copy(fileLoading = true, message = null) }
        viewModelScope.launch {
            val res: Result<com.kroryan.astrovia.data.fits.LightCurve> = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(app.cacheDir, "lightcurves").apply { mkdirs() }
                    val f = java.io.File(dir, "lc_" + url.hashCode().toUInt().toString(16) + ".fits")
                    if (!f.exists() || f.length() == 0L) {
                        com.kroryan.astrovia.data.net.HttpClient.client.newCall(okhttp3.Request.Builder().url(url.replace("http://", "https://")).build()).execute().use { r ->
                            if (!r.isSuccessful) throw java.io.IOException("The archive answered HTTP ${r.code}")
                            r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        }
                    }
                    com.kroryan.astrovia.data.fits.FitsFile.open(f).use { fits ->
                        val (hdu, cols) = com.kroryan.astrovia.data.fits.LightCurves.find(fits) ?: throw java.io.IOException("This file has no light curve (time + flux columns)")
                        com.kroryan.astrovia.data.fits.LightCurves.read(fits, hdu, cols, title)
                    }
                }
            }
            res.onSuccess { lc -> _state.update { it.copy(fileLoading = false, lightCurve = lc) } }
                .onFailure { e -> _state.update { it.copy(fileLoading = false) }; toast("Light curve: ${e.message ?: "failed"}") }
        }
    }

    /** Plots the light curve of an open FITS table (chosen in the FITS viewer). */
    fun plotFitsLightCurve(tableIndex: Int) {
        val d = _state.value.fitsDoc ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val hdu = d.fits.hdus[tableIndex]
                    val cols = com.kroryan.astrovia.data.fits.LightCurves.detect(com.kroryan.astrovia.data.fits.LightCurves.columnsOf(d.fits, hdu)) ?: throw java.io.IOException("No time and flux columns in this table")
                    com.kroryan.astrovia.data.fits.LightCurves.read(d.fits, hdu, cols, d.name)
                }
            }
            r.onSuccess { lc -> _state.update { it.copy(lightCurve = lc) } }.onFailure { toast("Light curve: ${it.message}") }
        }
    }

    // --- Open files: FITS images and tables, VOTable, CSV/TSV ---
    private var openFits: com.kroryan.astrovia.data.fits.FitsFile? = null

    fun openFile(uri: Uri) {
        _state.update { it.copy(fileLoading = true, message = null) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { FileOpener.open(app, uri) }
            when (res) {
                is OpenedFile.Failed -> toast("${res.name}: ${res.reason}")
                is OpenedFile.Table -> {
                    if (res.rows.isEmpty()) toast("${res.name}: ${res.totalRows} rows but no RA/Dec columns to place them on the sky")
                    else {
                        _state.update { it.copy(catalogRows = res.rows, catalogVisible = true, activeCatalogId = null, camera = it.camera.recenterTo(SkyPosition(res.rows[0].raDeg, res.rows[0].decDeg))) }
                        toast("${res.name}: ${res.rows.size} sources shown on the sky")
                    }
                }
                is OpenedFile.Fits -> showFits(res.name, res.fits)
            }
            _state.update { it.copy(fileLoading = false) }
        }
    }

    private fun toast(msg: String) {
        _state.update { it.copy(message = msg) }
        clearMessageLater(msg)
    }

    private suspend fun showFits(name: String, fits: com.kroryan.astrovia.data.fits.FitsFile) {
        openFits?.close()
        openFits = fits
        val hdu = fits.imageHdus.firstOrNull()
        if (hdu == null) {
            val lcHdu = com.kroryan.astrovia.data.fits.LightCurves.find(fits)
            if (lcHdu != null) {
                val lc = withContext(Dispatchers.IO) { runCatching { com.kroryan.astrovia.data.fits.LightCurves.read(fits, lcHdu.first, lcHdu.second, name) }.getOrNull() }
                if (lc != null) { _state.update { it.copy(lightCurve = lc) }; return }
            }
            val t = fits.tableHdus.firstOrNull()
            if (t == null) { toast("$name: no image or table in this FITS file"); return }
            loadFitsTable(name, fits, t.index)
            return
        }
        val doc = withContext(Dispatchers.Default) { buildFitsDoc(name, fits, hdu.index, Stretch.ASINH, ColorMap.GRAY, null, null) }
        _state.update { it.copy(fitsDoc = doc) }
    }

    private fun buildFitsDoc(name: String, fits: com.kroryan.astrovia.data.fits.FitsFile, hduIndex: Int, st: Stretch, cm: ColorMap, lo0: Float?, hi0: Float?): FitsDocument {
        val hdu = fits.hdus[hduIndex]
        return try {
            val img = fits.readImage(hdu)
            val (lo, hi) = if (lo0 != null && hi0 != null) lo0 to hi0 else FitsRender.cuts(img.values)
            val argb = FitsRender.toArgb(img, lo, hi, st, cm)
            val bmp = android.graphics.Bitmap.createBitmap(argb, img.width, img.height, android.graphics.Bitmap.Config.ARGB_8888)
            FitsDocument(name, fits, hdu, img, bmp, st, cm, lo, hi)
        } catch (e: Exception) {
            FitsDocument(name, fits, hdu, null, null, note = e.message ?: "Could not read this HDU")
        }
    }

    fun setFitsHdu(index: Int) {
        val d = _state.value.fitsDoc ?: return
        viewModelScope.launch {
            val nd = withContext(Dispatchers.Default) { buildFitsDoc(d.name, d.fits, index, d.stretch, d.colorMap, null, null) }
            _state.update { it.copy(fitsDoc = nd) }
        }
    }

    fun setFitsStyle(st: Stretch, cm: ColorMap, lo: Float, hi: Float) {
        val d = _state.value.fitsDoc ?: return
        val hdu = d.hdu ?: return
        viewModelScope.launch {
            val nd = withContext(Dispatchers.Default) { buildFitsDoc(d.name, d.fits, hdu.index, st, cm, lo, hi) }
            _state.update { s ->
                // keep an overlay in sync with the style
                val ov = s.fitsOverlay
                s.copy(fitsDoc = nd, fitsOverlay = if (ov != null && nd.bitmap != null && nd.image?.wcs != null) FitsOverlay(nd.bitmap, nd.image.wcs, nd.image.origWidth, nd.image.origHeight, nd.image.step, ov.alpha) else ov)
            }
        }
    }

    /** Puts the FITS image on the sky at its true position (needs a celestial WCS). */
    fun showFitsOnSky() {
        val d = _state.value.fitsDoc ?: return
        val img = d.image
        val wcs = img?.wcs
        val bmp = d.bitmap
        if (img == null || wcs == null || bmp == null) { toast("This image has no usable sky coordinates (WCS)"); return }
        val c = wcs.pixelToSky(img.origWidth / 2.0 + 0.5, img.origHeight / 2.0 + 0.5)
        if (c == null) { toast("The image centre cannot be projected"); return }
        val fov = (maxOf(img.origWidth, img.origHeight) * com.kroryan.astrovia.data.fits.Wcs.scaleDeg(wcs) * 1.3).coerceIn(0.001, 180.0)
        stopMotion()
        _state.update { it.copy(fitsOverlay = FitsOverlay(bmp, wcs, img.origWidth, img.origHeight, img.step), fitsDoc = null, camera = SkyCamera.looking(SkyPosition(c[0], c[1]), fov), cameraPlaced = true) }
        toast("${d.name} placed on the sky")
    }

    fun setOverlayAlpha(a: Float) = _state.update { s -> s.fitsOverlay?.let { s.copy(fitsOverlay = FitsOverlay(it.bitmap, it.wcs, it.origWidth, it.origHeight, it.step, a)) } ?: s }

    fun clearFitsOverlay() = _state.update { it.copy(fitsOverlay = null) }

    fun closeFits() = _state.update { it.copy(fitsDoc = null) }

    /** Shows a FITS table on the sky as a catalogue (RA/Dec columns are detected by name). */
    fun loadFitsTable(name: String, fits: com.kroryan.astrovia.data.fits.FitsFile, hduIndex: Int) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { runCatching { vizier.rowsFromMaps(fits.readTable(fits.hdus[hduIndex]).rows) }.getOrDefault(emptyList()) }
            if (rows.isEmpty()) { toast("$name: this table has no RA/Dec columns"); return@launch }
            _state.update { it.copy(catalogRows = rows, catalogVisible = true, activeCatalogId = null, fitsDoc = null, camera = it.camera.recenterTo(SkyPosition(rows[0].raDeg, rows[0].decDeg))) }
            toast("$name: ${rows.size} sources shown on the sky")
        }
    }

    fun fitsTables(): List<Pair<Int, String>> = _state.value.fitsDoc?.fits?.tableHdus?.map { it.index to it.name }.orEmpty()


    // --- External data centres: user-added TAP services ---
    fun loadCustomTables(tapUrl: String) {
        _state.update { it.copy(archiveLoading = true, customTables = null, customTap = tapUrl) }
        viewModelScope.launch {
            val t = try { archives.listTables(tapUrl) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast("TAP service: ${e.message ?: "failed"}"); null }
            _state.update { it.copy(archiveLoading = false, customTables = t) }
        }
    }

    fun addCustomSource(tapUrl: String, table: String, label: String) {
        _state.update { it.copy(archiveLoading = true) }
        viewModelScope.launch {
            try {
                val src = archives.detectSource(tapUrl, table, label)
                archives.addCustom(src)
                _state.update { it.copy(archiveLoading = false, archiveSources = archives.allSources()) }
                toast("Added: ${src.label}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(archiveLoading = false) }
                toast("Could not add the table: ${e.message ?: "failed"}")
            }
        }
    }

    fun removeCustomSource(id: String) {
        archives.removeCustom(id)
        _state.update { it.copy(archiveSources = archives.allSources()) }
    }

    // --- Papers of the objects in the field ---
    fun loadFieldObjects() {
        val c = _state.value.camera.center
        val r = visibleRadiusDeg()
        _state.update { it.copy(archiveLoading = true, fieldObjects = null) }
        viewModelScope.launch {
            val l = try { archives.fieldObjects(c.raDeg, c.decDeg, r) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast("SIMBAD: ${e.message ?: "not reachable"}"); null }
            _state.update { it.copy(archiveLoading = false, fieldObjects = l) }
        }
    }

    // --- Observation planning: instrument footprints on the sky ---
    fun setPlan(key: String?, paDeg: Double? = null) {
        val c = _state.value.camera.center
        _state.update { s ->
            if (key == null && s.plan == null) s
            else if (key == null) s.copy(plan = null)
            else s.copy(plan = PlanState(key, s.plan?.raDeg ?: c.raDeg, s.plan?.decDeg ?: c.decDeg, paDeg ?: s.plan?.paDeg ?: 0.0))
        }
    }

    fun placePlanHere() {
        val c = _state.value.camera.center
        _state.update { s -> s.plan?.let { s.copy(plan = it.copy(raDeg = c.raDeg, decDeg = c.decDeg)) } ?: s }
    }

    fun setPlanPa(pa: Double) = _state.update { s -> s.plan?.let { s.copy(plan = it.copy(paDeg = pa)) } ?: s }


    // --- Field overview: how much each archive has in the field (counts, tap to open) ---
    private var scanJob: Job? = null

    fun scanField(all: Boolean = false) {
        scanJob?.cancel()
        val c = _state.value.camera.center
        val r = visibleRadiusDeg()
        val list = _state.value.archiveSources.filter { it.kind in listOf(com.kroryan.astrovia.data.archive.ArchiveKind.OBSERVATIONS, com.kroryan.astrovia.data.archive.ArchiveKind.SPECTRA, com.kroryan.astrovia.data.archive.ArchiveKind.CATALOGUES, com.kroryan.astrovia.data.archive.ArchiveKind.LIGHT_CURVES, com.kroryan.astrovia.data.archive.ArchiveKind.IMAGES, com.kroryan.astrovia.data.archive.ArchiveKind.ALERTS) }
            .let { l -> if (all) l else l.filter { it.centre == "ESA" || it.centre == "MAST" } }
        _state.update { it.copy(fieldCounts = emptyMap(), scanProgress = 0 to list.size) }
        scanJob = viewModelScope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(5)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val jobs = list.map { src ->
                launch {
                    gate.acquire()
                    try {
                        val n = try { archives.countInField(src, c.raDeg, c.decDeg, r) } catch (e: CancellationException) { throw e } catch (e: Exception) { -1 }
                        _state.update { st -> st.copy(fieldCounts = st.fieldCounts + (src.id to n), scanProgress = done.incrementAndGet() to list.size) }
                    } finally { gate.release() }
                }
            }
            jobs.forEach { it.join() }
            _state.update { it.copy(scanProgress = null) }
        }
    }

    // --- Multi-archive search (own implementation over IVOA TAP) ---

    /** Radius (degrees) of the circle that covers the visible field, capped so queries stay fast. */
    private fun visibleRadiusDeg(): Double = (_state.value.camera.fovDeg / 2.0 * 1.25).coerceIn(0.005, 20.0)

    fun searchArchive(src: ArchiveSource, maxRows: Int = 300) {
        val c = _state.value.camera.center
        val radius = visibleRadiusDeg()
        _state.update { it.copy(archiveLoading = true, message = null, selectedHit = null) }
        viewModelScope.launch {
            var msg: String
            var hits: List<ArchiveHit> = emptyList()
            try {
                hits = archives.coneSearch(src, c.raDeg, c.decDeg, radius, maxRows)
                msg = if (hits.isEmpty()) "${src.label}: nothing in this field" else
                    "${src.label}: ${hits.size} result${if (hits.size == maxRows) "s (limit reached, zoom in)" else "s"}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                msg = "${src.label}: ${e.message ?: "no connection"}"
            } catch (e: Exception) {
                msg = "${src.label}: failed (${e.message})"
            }
            _state.update { it.copy(archiveLoading = false, archiveHits = hits, archiveLabel = src.label, archiveVisible = true, hitFilter = null, message = msg) }
            clearMessageLater(msg)
        }
    }

    fun clearArchive() = _state.update { it.copy(archiveHits = emptyList(), archiveLabel = null, selectedHit = null, solarBodies = emptyList(), hitFilter = null) }

    fun setHitFilter(f: HitFilter?) = _state.update { s -> s.copy(hitFilter = if (f == null || s.hitFilter?.label == f.label) null else f, selectedHit = null) }

    fun toggleArchiveVisibility() = _state.update { it.copy(archiveVisible = !it.archiveVisible) }

    fun selectHit(hit: ArchiveHit?) {
        stopMotion()
        _state.update { st ->
            st.copy(selectedHit = hit, camera = if (hit != null) st.camera.recenterTo(SkyPosition(hit.raDeg, hit.decDeg)) else st.camera)
        }
    }

    /** Hit under a sky position: inside a footprint, or the closest centre within [radiusDeg]. */
    fun hitAt(pos: SkyPosition, radiusDeg: Double): ArchiveHit? {
        val s = _state.value
        val vis = s.visibleHits
        if (!s.archiveVisible || vis.isEmpty()) return null
        val inside = vis.filter { h -> h.footprints.any { it.contains(pos.raDeg, pos.decDeg) } }
        if (inside.isNotEmpty()) return inside.minByOrNull { SkyPosition.separation(pos, SkyPosition(it.raDeg, it.decDeg)) }
        val best = vis.minByOrNull { SkyPosition.separation(pos, SkyPosition(it.raDeg, it.decDeg)) } ?: return null
        return best.takeIf { SkyPosition.separation(pos, SkyPosition(best.raDeg, best.decDeg)) <= radiusDeg }
    }

    /** Solar-system bodies in the field right now (SkyBoT). They are shown as archive-style hits. */
    fun searchSolarSystem() {
        val c = _state.value.camera.center
        val radius = visibleRadiusDeg()
        _state.update { it.copy(archiveLoading = true, message = null) }
        viewModelScope.launch {
            var msg: String
            var bodies: List<SolarSystemBody> = emptyList()
            try {
                val jd = com.kroryan.astrovia.sky.Visibility.julianDate(System.currentTimeMillis())
                bodies = archives.solarSystem(c.raDeg, c.decDeg, radius, jd).filter { !it.raDeg.isNaN() && !it.decDeg.isNaN() }
                msg = "Solar system: ${bodies.size} object${if (bodies.size == 1) "" else "s"} in the field now"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = "Solar system: ${if (e is java.io.IOException) e.message ?: "no connection" else "failed (${e.message})"}"
            }
            val src = ArchiveSource("skybot", "Solar-system objects (now)", "IMCCE", "Various",
                com.kroryan.astrovia.data.archive.ArchiveKind.SOLAR_SYSTEM, "skybot", "ra", "dec", "name", "name", null, null, "")
            val hits = bodies.map { b ->
                ArchiveHit(src, if (b.number > 0) "(${b.number}) ${b.name}" else b.name, b.name, b.raDeg, b.decDeg, emptyList(), b.card?.takeIf { it.startsWith("http") },
                    buildMap { put("Class", b.cls); b.vmag?.let { put("V mag", "%.1f".format(it)) }; if (b.number > 0) put("Number", b.number.toString()) })
            }
            _state.update { it.copy(archiveLoading = false, archiveHits = hits, archiveLabel = src.label, archiveVisible = true, solarBodies = bodies, message = msg) }
            clearMessageLater(msg)
        }
    }

    fun loadPublications(objectName: String) {
        val name = objectName.trim()
        if (name.isEmpty()) return
        _state.update { it.copy(publications = null, publicationsFor = name, archiveLoading = true) }
        viewModelScope.launch {
            val list = try { archives.publications(name) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val msg = if (list == null) "Could not read the publications of $name" else null
            _state.update { it.copy(archiveLoading = false, publications = list ?: emptyList(), message = msg ?: it.message) }
            msg?.let { clearMessageLater(it) }
        }
    }

    // --- Target lists: several named lists, built-in lists (Messier), import / export ---
    private val listStore by lazy { com.kroryan.astrovia.model.TargetLists(app) }

    private fun loadLists() {
        val u = listStore.user()
        val cur = if ("My targets" in u) "My targets" else u.keys.first()
        _state.update { it.copy(lists = u, builtInLists = listStore.builtIn(), currentList = cur, targets = u[cur].orEmpty()) }
    }

    private fun updateLists(lists: Map<String, List<Target>>, current: String = _state.value.currentList) {
        listStore.save(lists)
        _state.update { it.copy(lists = lists, currentList = current, targets = lists[current].orEmpty()) }
    }

    fun selectList(name: String) = _state.update { it.copy(currentList = name, targets = it.lists[name] ?: it.builtInLists[name].orEmpty()) }

    fun createList(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n in _state.value.lists) return
        updateLists(_state.value.lists + (n to emptyList()), n)
    }

    fun deleteList(name: String) {
        val l = _state.value.lists - name
        if (l.isEmpty()) updateLists(mapOf("My targets" to emptyList()), "My targets") else updateLists(l, l.keys.first())
    }

    /** Copies a built-in list (Messier...) into the user's lists so it can be edited. */
    fun copyBuiltIn(name: String) {
        val src = _state.value.builtInLists[name] ?: return
        updateLists(_state.value.lists + (name to src), name)
    }

    fun addToCurrentList(t: Target) {
        val cur = _state.value.currentList
        if (cur !in _state.value.lists) return
        updateLists(_state.value.lists + (cur to ((_state.value.lists[cur].orEmpty() + t).distinctBy { it.name })))
        toast("Added to $cur")
    }

    /** Resolves one name per line with SIMBAD and adds the ones found to the current list. */
    fun addTargets(namesText: String) {
        val (known, names) = com.kroryan.astrovia.model.TargetLists.parse(namesText)
        val list0 = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(300)
        _state.update { it.copy(archiveLoading = true, message = null) }
        viewModelScope.launch {
            val found = ArrayList<Target>(known)
            var failed = 0
            for (n in list0) {
                try {
                    val o = simbad.search(n).firstOrNull()
                    if (o != null) found.add(Target(o.mainId, o.position.raDeg, o.position.decDeg)) else failed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            val cur = _state.value.currentList
            val l = _state.value.lists
            if (cur in l) updateLists(l + (cur to (l[cur].orEmpty() + found).distinctBy { it.name }))
            val msg = "Targets: ${found.size} added" + if (failed > 0) ", $failed not found" else ""
            _state.update { it.copy(archiveLoading = false, message = msg) }
            clearMessageLater(msg)
        }
    }

    /** Puts every picture of the gallery of a mission in a new list. */
    fun listFromGallery(label: String, images: List<com.kroryan.astrovia.data.gallery.OutreachImage>) {
        val n = "$label pictures"
        updateLists(_state.value.lists + (n to images.map { Target(it.title, it.raDeg, it.decDeg) }.distinctBy { it.name }), n)
    }

    fun exportCurrentList(csv: Boolean): String = com.kroryan.astrovia.model.TargetLists.let { if (csv) it.toCsv(_state.value.targets) else Target.listToJson(_state.value.targets) }

    fun removeTarget(t: Target) {
        val cur = _state.value.currentList
        val l = _state.value.lists
        if (cur in l) updateLists(l + (cur to (l[cur].orEmpty() - t)))
    }

    fun goToTarget(t: Target) {
        stopMotion()
        _state.update { it.copy(camera = it.camera.recenterTo(SkyPosition(t.raDeg, t.decDeg))) }
    }

    // --- Object explorer ---
    fun loadObjectInfo(name: String) {
        _state.update { it.copy(objectLoading = true, objectInfo = null) }
        viewModelScope.launch {
            val info = try { archives.objectDetails(name) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            _state.update { it.copy(objectLoading = false, objectInfo = info) }
        }
    }

    // --- Sessions and view links ---
    /** A shareable link to the current view: astrovia://view?ra=..&dec=..&fov=..&survey=.. */
    fun viewLink(): String {
        val s = _state.value
        val c = s.camera.center
        return String.format(java.util.Locale.US, "astrovia://view?ra=%.6f&dec=%.6f&fov=%.5f&survey=%s", c.raDeg, c.decDeg, s.camera.fovDeg,
            java.net.URLEncoder.encode(s.survey.id, "UTF-8"))
    }

    /** Opens an astrovia://view link (from a share or an intent). Returns false if it is not one. */
    fun openViewLink(uri: Uri): Boolean {
        if (uri.scheme != "astrovia" || uri.host != "view") return false
        val ra = uri.getQueryParameter("ra")?.toDoubleOrNull() ?: return false
        val dec = uri.getQueryParameter("dec")?.toDoubleOrNull() ?: return false
        val fov = uri.getQueryParameter("fov")?.toDoubleOrNull()?.coerceIn(0.0005, 180.0) ?: _state.value.camera.fovDeg
        uri.getQueryParameter("survey")?.let { id ->
            _state.value.availableSurveys.firstOrNull { it.id == id }?.let { setSurvey(it) }
        }
        stopMotion()
        _state.update { it.copy(camera = SkyCamera.looking(SkyPosition(ra, dec), fov), cameraPlaced = true) }
        return true
    }

    /** Whole session (view, survey, markers, targets) as JSON text. */
    fun exportSession(): String {
        val s = _state.value
        val c = s.camera.center
        val o = org.json.JSONObject()
        o.put("app", "astrovia").put("version", 1).put("ra", c.raDeg).put("dec", c.decDeg).put("fov", s.camera.fovDeg).put("survey", s.survey.id)
        o.put("markers", org.json.JSONArray().also { a -> s.markers.forEach { m -> a.put(org.json.JSONObject().put("ra", m.position.raDeg).put("dec", m.position.decDeg).put("label", m.label).put("color", m.color)) } })
        o.put("targets", org.json.JSONArray(Target.listToJson(s.targets)))
        return o.toString(2)
    }

    fun importSession(text: String): Boolean {
        val o = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return false
        if (o.optString("app") != "astrovia") return false
        val markers = o.optJSONArray("markers")?.let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { m -> Marker(position = SkyPosition(m.getDouble("ra"), m.getDouble("dec")), label = m.optString("label"), color = m.optLong("color", 0xFFEF4444)) } }
        }.orEmpty()
        val targets = runCatching { Target.listFromJson(o.optJSONArray("targets")?.toString() ?: "[]") }.getOrDefault(emptyList())
        o.optString("survey").takeIf { it.isNotEmpty() }?.let { id -> _state.value.availableSurveys.firstOrNull { it.id == id }?.let { setSurvey(it) } }
        stopMotion()
        updateLists(_state.value.lists + (_state.value.currentList to targets))
        _state.update {
            it.copy(
                camera = SkyCamera.looking(SkyPosition(o.getDouble("ra"), o.getDouble("dec")), o.getDouble("fov").coerceIn(0.0005, 180.0)),
                cameraPlaced = true, markers = markers, targets = targets,
            )
        }
        return true
    }

    private val app get() = getApplication<Application>()

    override fun onCleared() {
        // Cancels ongoing fetches and frees cached bitmaps.
        app.unregisterComponentCallbacks(memoryCallbacks)
        tileRepo.shutdown()
        super.onCleared()
    }

    companion object {
        // Initial FoV limit to avoid loading the whole sky at once on startup.
        private const val MAX_INITIAL_FOV = 60.0
    }
}
