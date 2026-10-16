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
)

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
        _state.update { it.copy(settings = settings) }
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

    private val app get() = getApplication<Application>()

    override fun onCleared() {
        // Cancels ongoing fetches and frees cached bitmaps.
        tileRepo.shutdown()
        super.onCleared()
    }

    companion object {
        // Initial FoV limit to avoid loading the whole sky at once on startup.
        private const val MAX_INITIAL_FOV = 60.0
    }
}
