# Astrovia: Sky Atlas (Android)

**Astrovia: Sky Atlas** is a free, ad-free, open-source sky atlas for Android (Kotlin + Jetpack Compose),
based on the concept of the [Aladin Sky Atlas](https://aladin.cds.unistra.fr/) developed by the
Centre de Données astronomiques de Strasbourg (CDS). It has a UI designed for mobile: it is **not**
a port of the Aladin Desktop (Swing) UI, but a new touch interface.

> **Astrovia is an independent project. It is NOT affiliated with, endorsed by or sponsored by the
> CDS or the Aladin project.** This Android port / adaptation was created with the help of AI tools.
> Licensed under the GNU GPL v3 (see [`../COPYING`](../COPYING) and [`NOTICE.md`](NOTICE.md)).

It reuses the same CDS web services as Aladin Desktop:

- **HiPS** (sky image surveys) — nested HEALPix tiles.
- **Simbad** — object search by name or coordinates.
- **VizieR** — astronomical catalogs (cone search).

## Features (MVP)

- HiPS survey viewer with touch pan/zoom (TAN gnomonic projection).
- Survey selector (DSS2 color, 2MASS, ALLWISE, GALEX, …).
- Object search by name (Simbad) and map centering.
- Tap the sky to identify the object (Simbad cone search).
- SIMBAD and VizieR catalogues (Gaia DR3, 2MASS, Tycho-2, SDSS…) inside the Archives panel, with field counts, charts and footprints like any other archive.
- Manual markers and angular distance measurements (great circle).
- Status bar with center RA/Dec/FoV.

## Architecture

```
app/src/main/java/com/kroryan/astrovia/
  AstroviaApp.kt             Application
  MainActivity.kt            Entry point (Compose)
  SkyViewModel.kt            State (StateFlow): camera, surveys, catalogs, markers
  model/                     SkyPosition, Marker, Measurement, CatalogRow
  sky/
    Healpix.kt               Nested HEALPix (forward: center and corners)
    Projection.kt            TAN projection (forward/inverse) with RA wraparound
    SkyCamera.kt             Camera state (pan/zoom/recenter)
    SkyRenderer.kt           NESTED recursion for visible tiles
  data/
    net/HttpClient.kt        Shared OkHttpClient
    hips/                    HiPSProperties, HiPSSurvey, SurveyRegistry, TileRepository
    simbad/SimbadService.kt  Simbad search (VOTable)
    vizier/VizieRService.kt  VizieR cone search (VOTable)
    VotableParser.kt         Minimal VOTable parser (TABLEDATA)
  ui/
    theme/                   Color, Theme, Type (Compose)
    SkyCanvas.kt             Canvas that draws tiles + overlays and manages gestures
    SkyScreen.kt             Top bar, drawer, bottom sheets, FABs, status bar
```

### Implementation Notes

- **HiPS Rendering**: recursion of the NESTED tree (the children of `(order,npix)` are
  `4*npix+{0,1,2,3}` in `order+1`), so only HEALPix *forward* is needed
  (center and corners). Each tile is placed affinely (rotation+scale around
  the projected center) — correct approximation at moderate FoV.
- **Projection**: TAN (gnomonic), the one used by Aladin by default.
- **Tiles**: memory cache (LruCache) + disk cache (OkHttp Cache),
  asynchronous loading with repaint when the bitmap arrives.

## Build

Requirements: Android SDK (platform 36 + build-tools), JDK 17+.

```bash
cp local.properties.example local.properties   # adjust sdk.dir
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and press Run.

### Signed release (Google Play)

Release builds are signed with the keystore described in `../credenciales/keystore.properties`
(that folder is git-ignored and must never be committed):

```bash
./gradlew :app:bundleRelease   # app/build/outputs/bundle/release/app-release.aab
./gradlew :app:assembleRelease # app/build/outputs/apk/release/app-release.apk
```

Design sources of the icon live in [`design/`](design/) and the Play Store graphics in [`store/`](store/).
See also the [privacy policy](PRIVACY.md).
