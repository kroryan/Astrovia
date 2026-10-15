#!/usr/bin/env bash
# Builds every Astrovia desktop deliverable into release/desktop/:
#   Astrovia.jar                         universal executable jar (java -jar Astrovia.jar)
#   astrovia_<ver>_all.deb               Debian/Ubuntu package (+ apt repository in apt-repo/)
#   astrovia-<ver>-linux.tar.gz          portable folder with astrovia.sh + astrovia.desktop
#   Astrovia-<ver>-windows-x64.zip       portable Windows bundle (Astrovia.exe + bundled Java runtime)
#   Astrovia-<ver>-windows-setup.exe     Windows installer (NSIS)
#   Astrovia-<ver>-x64.msix              unsigned MSIX for Microsoft Store submission
# Needs: JDK 11+, dpkg-deb, apt-ftparchive, zip, ImageMagick (convert), python3, curl, unzip, tar.
set -euo pipefail
cd "$(dirname "$0")"
VER=${ASTROVIA_VERSION:-0.5.0}
WINVER="$VER.0"
export ASTROVIA_VERSION=$VER
REL=release/desktop
CACHE=build/cache
LAUNCH4J_URL=https://downloads.sourceforge.net/project/launch4j/launch4j-3/3.50/launch4j-3.50-linux-x64.tgz
JRE_URL="https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse"
mkdir -p "$REL" "$CACHE"
rm -rf "$REL"/*

echo "== jar"
./build-desktop.sh >/dev/null
cp build/desktop/Astrovia.jar "$REL/Astrovia.jar"

echo "== linux portable"
P=build/pkg/astrovia-$VER
rm -rf build/pkg; mkdir -p "$P"
cp "$REL/Astrovia.jar" desktop/astrovia.sh desktop/astrovia.desktop COPYING "$P/"
cp desktop/icons/astrovia-256.png "$P/astrovia.png"
tar -C build/pkg -czf "$REL/astrovia-$VER-linux.tar.gz" "astrovia-$VER"

echo "== deb"
D=build/deb
rm -rf $D; mkdir -p $D/DEBIAN $D/usr/bin $D/usr/share/astrovia $D/usr/share/applications $D/usr/share/doc/astrovia $D/usr/share/pixmaps
install -m755 desktop/astrovia.sh $D/usr/bin/astrovia
install -m644 "$REL/Astrovia.jar" $D/usr/share/astrovia/Astrovia.jar
install -m644 desktop/astrovia.desktop $D/usr/share/applications/astrovia.desktop
for s in 16 24 32 48 64 128 256 512; do
  install -Dm644 desktop/icons/astrovia-$s.png $D/usr/share/icons/hicolor/${s}x${s}/apps/astrovia.png
done
install -m644 desktop/icons/astrovia-48.png $D/usr/share/pixmaps/astrovia.png
gzip -9n -c COPYING > $D/usr/share/doc/astrovia/COPYING.gz
cat > $D/usr/share/doc/astrovia/copyright <<'CR'
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: Astrovia
Source: https://github.com/kroryan/Astrovia

Files: *
Copyright: Universite de Strasbourg/CNRS (CDS) - original Aladin Sky Atlas
           kroryan - Astrovia adaptation
License: GPL-3+
 On Debian systems the full text of the GNU General Public License version 3
 is in /usr/share/common-licenses/GPL-3 (also /usr/share/doc/astrovia/COPYING.gz).
CR
SIZE=$(du -sk $D/usr | cut -f1)
cat > $D/DEBIAN/control <<CT
Package: astrovia
Version: $VER
Section: science
Priority: optional
Architecture: all
Depends: default-jre | java8-runtime
Installed-Size: $SIZE
Maintainer: kroryan <kroryan@users.noreply.github.com>
Homepage: https://github.com/kroryan/Astrovia
Description: Open-source sky atlas (HiPS, FITS, MOC, Simbad, VizieR)
 Astrovia is an independent open-source project based on the Aladin Sky
 Atlas developed by the CDS (Strasbourg). It is not affiliated with the CDS.
 It lets you visualise astronomical images and HiPS surveys, overlay
 catalogues (Simbad, VizieR), and work with FITS, MOC and SAMP.
CT
cat > $D/DEBIAN/postinst <<'PI'
#!/bin/sh
set -e
if [ "$1" = configure ] && command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database -q || true; fi
if [ "$1" = configure ] && command -v gtk-update-icon-cache >/dev/null 2>&1; then gtk-update-icon-cache -q -t /usr/share/icons/hicolor || true; fi
exit 0
PI
chmod 755 $D/DEBIAN/postinst
fakeroot dpkg-deb --build -Zxz $D "$REL/astrovia_${VER}_all.deb" >/dev/null

echo "== apt repository"
A=$REL/apt-repo
mkdir -p $A/pool/main $A/dists/stable/main/binary-all
cp "$REL/astrovia_${VER}_all.deb" $A/pool/main/
( cd $A && apt-ftparchive packages pool > dists/stable/main/binary-all/Packages \
  && gzip -9nk dists/stable/main/binary-all/Packages \
  && apt-ftparchive -o APT::FTPArchive::Release::Origin=Astrovia -o APT::FTPArchive::Release::Label=Astrovia \
       -o APT::FTPArchive::Release::Suite=stable -o APT::FTPArchive::Release::Codename=stable \
       -o APT::FTPArchive::Release::Architectures=all -o APT::FTPArchive::Release::Components=main \
       release dists/stable > dists/stable/Release )
if [ -n "${GPG_KEY:-}" ]; then
  gpg --batch --yes -u "$GPG_KEY" --armor --detach-sign -o $A/dists/stable/Release.gpg $A/dists/stable/Release
  gpg --batch --yes -u "$GPG_KEY" --clearsign -o $A/dists/stable/InRelease $A/dists/stable/Release
  gpg --export "$GPG_KEY" | gpg --dearmor 2>/dev/null > $A/astrovia.gpg || gpg --export "$GPG_KEY" > $A/astrovia.gpg
else
  echo "   (GPG_KEY not set: repository is unsigned, use [trusted=yes] or sign with GPG_KEY=<id>)"
fi

echo "== windows"
if [ ! -x "$CACHE/launch4j/launch4j" ]; then
  curl -sSL -m 300 -o "$CACHE/l4j.tgz" "$LAUNCH4J_URL"
  mkdir -p "$CACHE/launch4j-x" && tar xzf "$CACHE/l4j.tgz" -C "$CACHE/launch4j-x"
  rm -rf "$CACHE/launch4j" && mv "$CACHE/launch4j-x/launch4j" "$CACHE/launch4j"
fi
if [ ! -d "$CACHE/jre-win" ]; then
  curl -sSL -m 600 -o "$CACHE/jre-win.zip" "$JRE_URL"
  mkdir -p "$CACHE/jre-win-x" && unzip -q "$CACHE/jre-win.zip" -d "$CACHE/jre-win-x"
  mv "$CACHE/jre-win-x"/jdk-* "$CACHE/jre-win"
fi
W=build/win/Astrovia
rm -rf build/win; mkdir -p "$W"
ABS=$(pwd)
sed -e "s|@OUT@|$ABS/build/win/Astrovia/Astrovia.exe|" -e "s|@JAR@|$ABS/$REL/Astrovia.jar|" \
    -e "s|@ICON@|$ABS/desktop/icons/astrovia.ico|" -e "s|@WINVER@|$WINVER|g" -e "s|@VER@|$VER|g" \
    desktop/launch4j.xml.in > build/win/launch4j.xml
java -jar "$CACHE/launch4j/launch4j.jar" build/win/launch4j.xml >/dev/null
cp -r "$CACHE/jre-win" "$W/jre"
cp COPYING "$W/COPYING.txt"
cp desktop/icons/astrovia.ico "$W/"
( cd build/win && zip -qr "$ABS/$REL/Astrovia-$VER-windows-x64.zip" Astrovia )
echo "== windows installer (NSIS)"
if [ ! -x "$CACHE/nsis/usr/bin/makensis" ]; then
  mkdir -p "$CACHE/nsis-deb" "$CACHE/nsis"
  ( cd "$CACHE/nsis-deb" && apt-get download nsis nsis-common >/dev/null 2>&1 )
  for d in "$CACHE"/nsis-deb/*.deb; do dpkg -x "$d" "$CACHE/nsis"; done
fi
NSISDIR="$ABS/$CACHE/nsis/usr/share/nsis" "$CACHE/nsis/usr/bin/makensis" -V1 \
  -DVER="$VER" -DOUT="$ABS/$REL/Astrovia-$VER-windows-setup.exe" -DSRC="$ABS/$W" \
  -DICON="$ABS/desktop/icons/astrovia.ico" -DLICENSE="$ABS/COPYING" desktop/installer.nsi
python3 -I desktop/make_msix.py "$W" desktop/icons "$REL/Astrovia-$VER-x64.msix" "$WINVER"

echo "== checksums"
( cd "$REL" && find . -maxdepth 1 -type f -printf '%f\n' | sort | xargs sha256sum > SHA256SUMS.txt )
ls -la "$REL"
