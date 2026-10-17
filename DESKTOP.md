# Astrovia: Sky Atlas - desktop (Linux / Windows)

Java/Swing desktop edition of Astrovia, based on the Aladin Sky Atlas (CDS, GPL v3).
Independent community project, not affiliated with the CDS.

## Build

```
./package-desktop.sh            # everything -> release/desktop/
./build-desktop.sh              # only build/desktop/Astrovia.jar
```

Requirements: JDK 11+, dpkg-deb, fakeroot, apt-ftparchive, zip, ImageMagick, python3, curl.
The Windows part downloads (and caches in `build/cache`) Launch4j and the Temurin 21 Windows JRE.

Outputs in `release/desktop/`:

| File | Use |
|---|---|
| `Astrovia.jar` | `java -jar Astrovia.jar` (any OS, needs Java 8+) |
| `astrovia_<ver>_all.deb` | Debian/Ubuntu package (`/usr/bin/astrovia`, menu entry, icons) |
| `apt-repo/` | static apt repository (publish as-is) |
| `astrovia-<ver>-linux.tar.gz` | portable folder: `astrovia.sh`, `astrovia.desktop`, jar, icon |
| `Astrovia-<ver>-windows-x64.zip` | portable Windows bundle: `Astrovia.exe` (icon embedded) + bundled Java |
| `Astrovia-<ver>-windows-setup.exe` | Windows installer (NSIS: Start menu, desktop shortcut, uninstaller) |
| `Astrovia-<ver>-x64.msix` | unsigned MSIX for the Microsoft Store |

## Microsoft Store

The package identity is private and is not stored in the repository. Create
`credenciales/msix-identity.env` (git-ignored) with the values from Partner Center
(*Product management > Product identity*):

```
MSIX_IDENTITY_NAME=<Package/Identity/Name>
MSIX_PUBLISHER=<Package/Identity/Publisher>
MSIX_PUBLISHER_DISPLAY=<Publisher display name>
```

then run `./package-desktop.sh` (the same variables can also be passed in the environment).
Upload the `.msix` (the Store signs it).

## apt repository

Recommended: GitHub Pages (static, free, same repo). Sign with GPG for production:

```
GPG_KEY=<fingerprint> ./package-desktop.sh
# publish release/desktop/apt-repo/ to the gh-pages branch
```

Users:

```
curl -fsSL https://kroryandev.com/wp-content/uploads/astrovia-apt/astrovia.gpg | sudo tee /etc/apt/keyrings/astrovia.gpg >/dev/null
echo "deb [signed-by=/etc/apt/keyrings/astrovia.gpg] https://kroryandev.com/wp-content/uploads/astrovia-apt stable main" | sudo tee /etc/apt/sources.list.d/astrovia.list
sudo apt update && sudo apt install astrovia
```

Data lives in `~/.astrovia` (cache, configuration, first-run acceptance in `welcome.accepted`).

## Version

The version is written once, in `VERSION` (and `VERSION_CODE` for Android). Change it everywhere with
`tools/set-version.sh <version> [android-version-code]`; `build-desktop.sh` stops if the constant in `Aladin.java` disagrees.
The build also stops on any compile error.

## Code shared with the Android app

`shared/java/` is plain Java 8 used by both apps (image processing in `cds.astrovia.imgproc`; visibility, instrument
footprints, wavelength bands, STC-S and ADQL helpers, VOTable / CSV readers and tile pyramids in `cds.astrovia.sky`).
`shared/data/` holds the archive registry (`archives.json`) and the object lists. Edit them there: the desktop build copies
them into the jar and Gradle packs them as Android assets.

## Archive registry tools

```
python3 tools/archives/build_registry.py          # regenerate shared/data/archives.json from the ESA Sky TAP schema
python3 tools/archives/check_registry.py --fast   # ask every source for one row (EMPTY is fine; ERROR / SLOW deserve a look)
```

`labels.json` says how each table is named and typed; `extras.json` holds the sources written by hand (MAST, ESO, IRSA, ...).

## Files, caches and data

Data lives in `~/.astrovia`. *Clear cache and temporary files...* (the `+` menu of the Available data panel) frees the cache
and keeps offline collections, lists and settings. Gzip-compressed FITS (`.fits.gz`) opens like any other; tile-compressed
`.fz` files are not supported yet.

## Not done yet

AppImage and macOS packages, a signed Windows installer and a Flatpak build (only the metadata exists).
