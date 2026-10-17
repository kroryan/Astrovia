#!/usr/bin/env bash
# Builds Astrovia.jar (executable, self-contained) from src/ and lib/. Requires JDK 11+ (javac, jar).
set -euo pipefail
cd "$(dirname "$0")"
OUT=${OUT:-build/desktop}
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/jar"
CP=$(ls lib/*.jar | tr '\n' ':')
(find src -name "*.java"; find shared/java -name "*.java") > "$OUT/sources.txt"
# a compile error must stop the build (it used to be swallowed and a partial jar was packaged)
if ! javac -nowarn -encoding Cp1252 --release 8 -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log"; echo "build-desktop.sh: compilation failed" >&2; exit 1
fi
grep -v '^Note:' "$OUT/javac.log" || true
# the version lives in ./VERSION; the constant in Aladin.java must agree
VERSION_FILE=$(tr -d '[:space:]' < VERSION)
CONST=$(grep -a -o 'ASTROVIA_VERSION *= *"[^"]*"' src/cds/aladin/Aladin.java | head -1 | sed 's/.*"\(.*\)"/\1/')
if [ "$CONST" != "$VERSION_FILE" ]; then echo "VERSION ($VERSION_FILE) and Aladin.ASTROVIA_VERSION ($CONST) differ: run tools/set-version.sh" >&2; exit 1; fi
# resources next to the classes (same selection as build.xml)
( cd src && cp --parents *.gif *.jpg *.png Ala*.* Constellation.txt viewConfiguration.properties StandardForms.dic "../$OUT/classes/" 2>/dev/null || true )
# data shared with the Android app (single source: shared/data)
cp shared/data/archives.json "$OUT/classes/AlaArchives.json"
cp shared/data/lists.json "$OUT/classes/AlaLists.json"
# merge dependency jars
for j in lib/*.jar; do ( cd "$OUT/jar" && unzip -qo "../../../$j" -x 'META-INF/*' ); done
cp -r "$OUT/classes/." "$OUT/jar/"
printf 'Main-Class: cds.astrovia.Astrovia\nImplementation-Title: Astrovia\nImplementation-Version: %s\n' "${ASTROVIA_VERSION:-$VERSION_FILE}" > "$OUT/MANIFEST.MF"
jar --create --file "$OUT/Astrovia.jar" --manifest "$OUT/MANIFEST.MF" -C "$OUT/jar" .
ls -la "$OUT/Astrovia.jar"
