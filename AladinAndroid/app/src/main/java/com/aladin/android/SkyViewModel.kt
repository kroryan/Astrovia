package com.aladin.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aladin.android.data.hips.HiPSProperties
import com.aladin.android.data.hips.HiPSPropertiesParser
import com.aladin.android.data.hips.HiPSSurvey
import com.aladin.android.data.hips.SurveyRegistry
import com.aladin.android.data.hips.TileRepository
import com.aladin.android.data.simbad.SimbadObject
import com.aladin.android.data.simbad.SimbadService
import com.aladin.android.data.vizier.VizieRService
import com.aladin.android.model.CatalogRow
import com.aladin.android.model.Marker
import com.aladin.android.model.Measurement
import com.aladin.android.model.SkyPosition
import com.aladin.android.sky.SkyCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SkyUiState(
    val camera: SkyCamera = SkyCamera(),
    val survey: HiPSSurvey = SurveyRegistry.builtIn.first(),
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
)

class SkyViewModel(app: Application) : AndroidViewModel(app) {
    private val simbad = SimbadService()
    private val vizier = VizieRService()
    // Ajustes persistentes (caché, calidad, concurrencia). Se cargan antes de
    // construir el TileRepository para que la caché en memoria y la concurrencia
    // arranquen con los valores guardados.
    private var settings = AppSettings.load(app)
    val tileRepo = TileRepository(app, settings.memCacheMb, settings.concurrency)

    private val _state = MutableStateFlow(SkyUiState())
    val state: StateFlow<SkyUiState> = _state.asStateFlow()

    init {
        // El repositorio de tiles avisa (coalescido) cuando llegan bitmaps nuevos
        // -> subimos tileVersion para forzar un redibujado del Canvas. El mismo
        // aviso dispara cuando se ENCOLAN tiles (ver TileRepository.request), así
        // que aprovechamos para leer activeDownloads y alimentar el indicador de
        // carga: no hace falta un flow aparte, ya se coalesce y sincroniza solo.
        tileRepo.onTilesChanged = {
            _state.update { it.copy(tileVersion = it.tileVersion + 1, downloadingTiles = tileRepo.activeDownloads) }
        }
        _state.update { it.copy(settings = settings) }
        loadSurveyProperties(_state.value.survey)
    }

    // --- Ajustes (Settings) ---
    /** Aplica nuevos ajustes: persiste, reconfigura el repo y recalcula los tiles
     *  visibles (el `targetScale` de calidad cambia la densidad de tiles). */
    fun setSettings(s: AppSettings) {
        settings = s
        AppSettings.save(app, s)
        tileRepo.setMemCacheMb(s.memCacheMb)
        tileRepo.setConcurrency(s.concurrency)
        // Disparar recálculo: settings cambió -> el collect re-emite y
        // computeVisibleTiles usa el nuevo targetScale. Forzamos una emisión
        // tocando tileVersion también por si la cámara no cambió.
        _state.update { it.copy(settings = s, tileVersion = it.tileVersion + 1) }
    }

    /** Vacía caché en memoria y disco. */
    fun clearCache() {
        tileRepo.clearAll()
        _state.update { it.copy(tileVersion = it.tileVersion + 1) }
    }

    fun setSurvey(survey: HiPSSurvey) {
        // NO vaciamos props ni survey aquí. Si lo hiciéramos, la pantalla se
        // quedaría sin tiles durante el fetch de `properties` (~3s en el servidor
        // CDS) —o para siempre si el fetch falla—. En su lugar, marcamos loading
        // y dejamos el survey viejo visible hasta que el nuevo `properties` llega.
        // Entonces intercambiamos survey+props ATÓMICAMENTE (ver loadSurveyProperties):
        // así el fetch de tiles nuevos nunca usa el survey nuevo con props viejos
        // (mezclar baseUrls/ordenes daría tiles erróneos).
        if (survey.id == _state.value.survey.id) return
        _state.update { it.copy(propsLoading = true, message = null) }
        loadSurveyProperties(survey)
    }

    private fun loadSurveyProperties(survey: HiPSSurvey) {
        viewModelScope.launch {
            val props = fetchProperties(survey)
            _state.update {
                if (props != null) {
                    // Intercambio atómico survey+props+camera. El survey viejo sigue
                    // visible hasta justo aquí; a partir de aquí el draw y el fetch
                    // usan el nuevo survey (y su cache key nueva, ver TileRepository).
                    it.copy(
                        survey = survey,
                        props = props,
                        propsLoading = false,
                        catalogRows = emptyList(),
                        camera = SkyCamera(
                            center = SkyPosition(props.initialRa, props.initialDec),
                            fovDeg = props.initialFov.coerceIn(SkyCamera.MIN_FOV, MAX_INITIAL_FOV),
                        ),
                    )
                } else {
                    // Fetch fallido: conservamos el survey/props viejos (no negro
                    // permanente) y avisamos.
                    it.copy(propsLoading = false, message = "Could not load survey")
                }
            }
        }
    }

    private suspend fun fetchProperties(survey: HiPSSurvey): HiPSProperties? =
        withContext(Dispatchers.IO) {
            try {
                val r = com.aladin.android.data.net.HttpClient.client
                    .newCall(okhttp3.Request.Builder().url(survey.propertiesUrl()).build()).execute()
                r.use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val text = resp.body?.string() ?: return@withContext null
                    HiPSPropertiesParser.parse(survey.baseUrl, text)
                }
            } catch (e: Exception) { null }
        }

    // --- Cámara ---
    fun panBy(dx: Float, dy: Float, w: Float, h: Float) {
        _state.update { it.copy(camera = it.camera.panBy(dx, dy, w, h)) }
    }

    fun zoomBy(factor: Float, w: Float, h: Float, fx: Float, fy: Float) {
        _state.update { it.copy(camera = it.camera.zoom(factor, w, h, fx, fy)) }
    }

    fun recenter(pos: SkyPosition) {
        _state.update { it.copy(camera = it.camera.recenterTo(pos)) }
    }

    // --- Búsqueda Simbad ---
    fun searchObject(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(searching = true, message = null) }
        viewModelScope.launch {
            val results = simbad.search(query, radiusArcmin = 30.0)
            val first = results.firstOrNull()
            _state.update {
                it.copy(
                    searching = false,
                    selected = first,
                    message = if (first == null) app.getString(R.string.no_results) else null,
                    camera = if (first != null) it.camera.recenterTo(first.position) else it.camera,
                )
            }
        }
    }

    fun identifyAt(raDeg: Double, decDeg: Double) {
        _state.update { it.copy(identifying = true, message = null) }
        viewModelScope.launch {
            val obj = simbad.coneSearch(raDeg, decDeg, radiusArcmin = 0.5)
            _state.update { it.copy(identifying = false, selected = obj) }
        }
    }

    fun clearSelected() = _state.update { it.copy(selected = null) }

    // --- Catálogos VizieR ---
    fun loadCatalog(catalogId: String) {
        val s = _state.value
        val radius = (s.camera.fovDeg / 2.0).coerceIn(0.05, 60.0) * 60.0 // arcmin
        _state.update { it.copy(catalogLoading = true, message = null) }
        viewModelScope.launch {
            val rows = vizier.coneSearch(
                catalogId, s.camera.center.raDeg, s.camera.center.decDeg, radius
            )
            _state.update { it.copy(catalogLoading = false, catalogRows = rows) }
        }
    }

    fun clearCatalog() = _state.update { it.copy(catalogRows = emptyList()) }

    // --- Marcadores y medidas ---
    fun addMarker(pos: SkyPosition, label: String = "M${state.value.markers.size + 1}") {
        _state.update {
            it.copy(markers = it.markers + Marker(position = pos, label = label))
        }
    }

    fun clearMarkers() = _state.update { it.copy(markers = emptyList()) }

    fun toggleMeasureMode() = _state.update {
        it.copy(measureMode = !it.measureMode, pendingMeasureFrom = null)
    }

    /** Toca el cielo en modo medida: primer toque fija el origen, segundo fija el destino. */
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
        // Cancela fetches en curso y libera los bitmaps cacheados.
        tileRepo.shutdown()
        super.onCleared()
    }

    companion object {
        // Tope de FoV inicial para no cargar todo el cielo de golpe al arrancar.
        private const val MAX_INITIAL_FOV = 60.0
    }
}