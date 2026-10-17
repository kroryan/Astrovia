#!/usr/bin/env bash
# Builds Astrovia.jar (executable, self-contained) from src/ and lib/. Requires JDK 11+ (javac, jar).
set -euo pipefail
cd "$(dirname "$0")"
OUT=${OUT:-build/desktop}
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/jar"
CP=$(ls lib/*.jar | tr '\n' ':')
(find src -name "*.java"; find shared/imgproc -name "*.java") > "$OUT/sources.txt"
javac -nowarn -encoding Cp1252 --release 8 -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v '^Note:' || true
# resources next to the classes (same selection as build.xml)
( cd src && cp --parents *.gif *.jpg *.png Ala*.* Constellation.txt viewConfiguration.properties StandardForms.dic "../$OUT/classes/" 2>/dev/null || true )
# merge dependency jars
for j in lib/*.jar; do ( cd "$OUT/jar" && unzip -qo "../../../$j" -x 'META-INF/*' ); done
cp -r "$OUT/classes/." "$OUT/jar/"
printf 'Main-Class: cds.astrovia.Astrovia\nImplementation-Title: Astrovia\nImplementation-Version: %s\n' "${ASTROVIA_VERSION:-0.6.3}" > "$OUT/MANIFEST.MF"
jar --create --file "$OUT/Astrovia.jar" --manifest "$OUT/MANIFEST.MF" -C "$OUT/jar" .
ls -la "$OUT/Astrovia.jar"
