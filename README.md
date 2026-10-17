<p align="center">
  <img src="Astrovia/store/feature-graphic-PC-1024x500.png" alt="Astrovia: Sky Atlas - Open-source sky atlas" width="100%">
</p>

<h1 align="center">
  <img src="Astrovia/store/icon-512.png" alt="" width="64" align="absmiddle"> Astrovia: Sky Atlas
</h1>

<p align="center"><b>Open-source sky atlas for Windows, Linux and Android.</b></p>

<p align="center">
  <a href="https://apps.microsoft.com/detail/9pk3fbqlh2x3?cid=DevShareMCLPCS&hl=en-US&gl=ES"><img alt="Get it from Microsoft Store" src="https://img.shields.io/badge/Microsoft%20Store-Get%20it%20for%20Windows-0078D4?style=for-the-badge&logo=windows"></a>
  <a href="https://github.com/kroryan/Astrovia/releases"><img alt="Releases" src="https://img.shields.io/badge/Download-GitHub%20Releases-181717?style=for-the-badge&logo=github"></a>
  <img alt="License GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-green?style=for-the-badge">
</p>

**Astrovia: Sky Atlas** (Astrovia for short) lets you explore the sky with HiPS surveys, overlay Simbad and VizieR catalogues, and work with
FITS images and MOC coverage, in a modern dark interface. Free, no ads, no tracking.

> **Astrovia is an independent community project based on the [Aladin Sky Atlas](https://aladin.cds.unistra.fr/)**
> developed by the Centre de Données astronomiques de Strasbourg (CDS). It is **not** affiliated with,
> endorsed by or sponsored by the CDS or the Aladin project. "Aladin", "CDS", "Simbad" and "VizieR" belong
> to their respective owners and are only mentioned to credit the original work and data.
> This adaptation was created with the help of artificial intelligence tools and may contain mistakes.

## Editions

| Edition | Where | Notes |
|---|---|---|
| **Desktop** (Linux, Windows) | this repository (`src/`) | Java/Swing, based on Aladin Desktop. See [DESKTOP.md](DESKTOP.md) |
| **Android** | [`Astrovia/`](Astrovia/README.md) | Native Kotlin / Jetpack Compose app |

## Highlights (0.6.3)

- **Archives: multi-mission search (new, Android and desktop).** One window/sheet to search around the view you are looking at, over about 100 archives and catalogues:
  - *Observations and spectra:* JWST, HST, XMM-Newton, Chandra, Herschel, ALMA, Spitzer, AKARI, ISO, IUE, Suzaku, CHEOPS, CoRoT, Euclid mosaics, LAMOST and more.
  - *Catalogues:* Gaia DR3, Hipparcos, Tycho-2, 2MASS, AllWISE, eROSITA, ROSAT, Fermi, Planck, Chandra, XMM, Herschel, INTEGRAL, Hubble Source Catalog...
  - *Alerts* (gravitational waves, IceCube), *outreach images* (Webb, Hubble, Euclid), *exoplanets* (NASA Exoplanet Archive) and *solar-system objects* in the field right now (IMCCE SkyBoT).
  - Android draws the observation footprints on the sky; tap one to see its details and open or download the data.
- **Science tools:** visibility windows for JWST / Euclid (Sun-angle limits), target lists resolved with SIMBAD, publications of an object (SIMBAD to ADS), and shareable view links / sessions (`astrovia://view?ra=..&dec=..&fov=..`).
- All the offline download features of 0.6.2 are unchanged.

The Archives feature is an original implementation on top of public IVOA protocols (TAP/ADQL, VOTable) and the services above; see [NOTICE.md](NOTICE.md) for the data acknowledgements.

## Highlights (0.6.2)

- **All-sky view like Aladin Desktop** (Android): zoom all the way out and a sky survey becomes the whole-sky oval, a planet becomes a disc you can rotate. You can zoom and navigate inside both. Tiles are drawn as one continuous mesh, so there are no gaps between them at any zoom.
- **Offline collections**:
  - *Desktop:* add HiPS folders (or folders that contain HiPS) and catalogue files, and they appear under "HiPS offline" / "Catalogs offline" in Available data. A *Source* selector switches between all, online-only and offline-only collections, and downloads can be added automatically with a checkbox.
  - *Android:* every downloaded HiPS shows up at the top of the collections menu as "<name> (offline)": type "offline" in the search box to find them.
- **Downloads that survive the background** (Android): a progress notification with a Stop button. HiPS downloads can be limited to the area in view, with a size estimate before you start.
- **Capture the sky**: a camera button saves *only* the survey picture of the current view (no panels, toolbars or crosshair) at up to 3-4x the screen size, using the finest tiles available. PNG (lossless) or JPEG. You choose the folder.
- **Correct sky orientation** (north up, east left, like a sky chart) and a camera with no singularity at the poles (Android).
- **Galactic and planetary surveys** (Mellinger, IRIS, Mars, the Moon, Venus, Titan...) and FITS-only surveys.
- **Name search** (SIMBAD) and catalogues from VizieR/SIMBAD that can be downloaded and shown on the sky.

## Install (desktop)

### Debian / Ubuntu / Linux Mint (apt repository)

Add the Astrovia repository once, then install and update it with `apt`:

```
sudo install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://kroryandev.com/wp-content/uploads/astrovia-apt/astrovia.gpg | sudo tee /etc/apt/keyrings/astrovia.gpg >/dev/null
echo "deb [signed-by=/etc/apt/keyrings/astrovia.gpg] https://kroryandev.com/wp-content/uploads/astrovia-apt stable main" | sudo tee /etc/apt/sources.list.d/astrovia.list
sudo apt update
sudo apt install astrovia
```

Astrovia then updates with the rest of your system (`sudo apt upgrade`). Requires Java 8 or newer
(`default-jre` is pulled in automatically). To remove it:

```
sudo apt remove astrovia
sudo rm /etc/apt/sources.list.d/astrovia.list /etc/apt/keyrings/astrovia.gpg
```

You can also install the `.deb` directly from the Releases page: `sudo apt install ./astrovia_<version>_all.deb`

### Other options

- **Windows (recommended):** get it from the [Microsoft Store](https://apps.microsoft.com/detail/9pk3fbqlh2x3?cid=DevShareMCLPCS&hl=en-US&gl=ES), or run `Astrovia-<version>-windows-setup.exe` (includes its own Java runtime), or use the portable zip
- **Any OS:** `java -jar Astrovia.jar` (Java 8+)
- **Linux portable:** unpack `astrovia-<version>-linux.tar.gz` and run `./astrovia.sh`

Binaries are published on the [Releases](https://github.com/kroryan/Astrovia/releases) page.
User data lives in `~/.astrovia`.

## Build

```
./package-desktop.sh      # Linux .deb, Windows installer/zip, MSIX, jar  -> release/desktop/
./build-desktop.sh        # only Astrovia.jar
```

Details, Microsoft Store and apt repository instructions: [DESKTOP.md](DESKTOP.md).
Installer exit codes: [docs/installer-return-codes.md](docs/installer-return-codes.md).
The Android build is described in [Astrovia/README.md](Astrovia/README.md).

## Licence and attribution

Astrovia is distributed under the **GNU General Public License v3** ([COPYING](COPYING)), the same licence as
the original software. Copyright of the original Aladin code: © Université de Strasbourg/CNRS (CDS).
Astrovia modifications: kroryan. The program comes with ABSOLUTELY NO WARRANTY.
See [NOTICE.md](NOTICE.md) for modifications and third-party components.

## Data and privacy

Astrovia connects to public astronomical services (HiPS servers, Simbad and VizieR at CDS); coordinates and
names you query are sent to those servers. Astrovia itself collects nothing. See
[Astrovia/PRIVACY.md](Astrovia/PRIVACY.md).

## Contributing

Bug reports and pull requests are welcome on [GitHub](https://github.com/kroryan/Astrovia/issues).
For problems with the original Aladin, contact the CDS directly.
