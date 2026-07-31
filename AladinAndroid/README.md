# Aladin para Android

Una reimplementation **nativa** (Kotlin + Jetpack Compose) del concepto de
Aladin Sky Atlas (CDS) para Android, con una UI pensada para móvil: **no** es
un port de la UI de Aladin Desktop (Swing), sino una interfaz táctil nueva.

Reutiliza los mismos servicios web del CDS que Aladin Desktop:

- **HiPS** (encuestas de imágenes del cielo) — tiles HEALPix anidados.
- **Simbad** — búsqueda de objetos por nombre o coordenadas.
- **VizieR** — catálogos astronómicos (cone search).

## Funcionalidades (MVP)

- Visor de encuestas HiPS con pan/zoom táctiles (proyección TAN gnomónica).
- Selector de encuestas (DSS2 color, 2MASS, ALLWISE, GALEX, …).
- Búsqueda de objetos por nombre (Simbad) y centrado del mapa.
- Tocar el cielo para identificar el objeto (Simbad cone search).
- Overlays de catálogos VizieR (Gaia DR3, 2MASS, Tycho-2, SDSS…).
- Marcadores manuales y medidas de distancia angular (gran círculo).
- Barra de estado con RA/Dec/FoV del centro.

## Arquitectura

```
app/src/main/java/com/aladin/android/
  AladinApp.kt               Application
  MainActivity.kt            Entry point (Compose)
  SkyViewModel.kt            Estado (StateFlow): cámara, surveys, catálogos, marcadores
  model/                     SkyPosition, Marker, Measurement, CatalogRow
  sky/
    Healpix.kt               HEALPix anidado (forward: centro y esquinas)
    Projection.kt            Proyección TAN (forward/inverse) con wraparound de RA
    SkyCamera.kt             Estado de cámara (pan/zoom/recenter)
    SkyRenderer.kt           Recursión NESTED para tiles visibles
  data/
    net/HttpClient.kt        OkHttpClient compartido
    hips/                    HiPSProperties, HiPSSurvey, SurveyRegistry, TileRepository
    simbad/SimbadService.kt  Búsqueda Simbad (VOTable)
    vizier/VizieRService.kt  Cone search VizieR (VOTable)
    VotableParser.kt         Parser VOTable mínimo (TABLEDATA)
  ui/
    theme/                   Color, Theme, Type (Compose)
    SkyCanvas.kt             Canvas que dibuja tiles + overlays y gestiona gestos
    SkyScreen.kt             Top bar, drawer, bottom sheets, FABs, status bar
```

### Notas de implementación

- **Render HiPS**: recursión del árbol NESTED (los hijos de `(order,npix)` son
  `4*npix+{0,1,2,3}` en `order+1`), así que solo se necesita HEALPix *forward*
  (centro y esquinas). Cada tile se coloca de forma afín (rotación+escala sobre
  el centro proyectado) — aproximación correcta a FoV moderado.
- **Proyección**: TAN (gnomónica), la que usa Aladin por defecto.
- **Tiles**: cache en memoria (LruCache) + cache de disco (OkHttp Cache),
  carga asíncrona con repintado al llegar el bitmap.

## Compilar

Requisitos: Android SDK (platform 35 + build-tools), JDK 17+.

```bash
cp local.properties.example local.properties   # ajusta sdk.dir
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

O abrir el proyecto en Android Studio y pulsar Run.

> Nota: este proyecto se generó sin poder compilarlo en la máquina de origen
> (no había una plataforma Android instalada en el SDK). Revisa antes de firmar.