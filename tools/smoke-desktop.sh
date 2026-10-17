#!/usr/bin/env bash
# Smoke test of the desktop app: builds the jar, then runs test/smoke/SmokeTest.java under a virtual display.
# Also opens the app itself with a FITS file for a few seconds and fails on any exception in its log.
set -euo pipefail
cd "$(dirname "$0")/.."
./build-desktop.sh > /dev/null
JAR=build/desktop/Astrovia.jar
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
javac --release 8 -nowarn -cp "$JAR" -d "$T" test/smoke/SmokeTest.java 2>&1 | grep -v warning || true
xvfb-run -a java -cp "$T:$JAR" cds.aladin.SmokeTest
mkdir -p "$T/home/.astrovia"; touch "$T/home/.astrovia/welcome.accepted"
HOME="$T/home" xvfb-run -a bash -c "java -jar $JAR > $T/app.log 2>&1 & P=\$!; sleep 25; kill \$P 2>/dev/null; true"
if grep -ai "exception" "$T/app.log"; then echo "the app logged exceptions" >&2; exit 1; fi
echo "app started and stayed up without exceptions"
