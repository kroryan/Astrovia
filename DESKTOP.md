# Astrovia desktop (Linux / Windows)

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

Reserve the app name in Partner Center, copy *Product identity* values and rebuild the MSIX:

```
MSIX_IDENTITY_NAME='12345kroryan.Astrovia' MSIX_PUBLISHER='CN=XXXXXXXX-....' \
MSIX_PUBLISHER_DISPLAY='kroryan' ./package-desktop.sh
```

Upload the `.msix` (the Store signs it). For sideload testing sign it with your own certificate (`signtool`).

## apt repository

Recommended: GitHub Pages (static, free, same repo). Sign with GPG for production:

```
GPG_KEY=<fingerprint> ./package-desktop.sh
# publish release/desktop/apt-repo/ to the gh-pages branch
```

Users:

```
curl -fsSL https://kroryan.github.io/Astrovia/astrovia.gpg | sudo tee /etc/apt/keyrings/astrovia.gpg >/dev/null
echo "deb [signed-by=/etc/apt/keyrings/astrovia.gpg] https://kroryan.github.io/Astrovia stable main" | sudo tee /etc/apt/sources.list.d/astrovia.list
sudo apt update && sudo apt install astrovia
```

Data lives in `~/.astrovia` (cache, configuration, first-run acceptance in `welcome.accepted`).
