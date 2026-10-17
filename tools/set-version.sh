#!/usr/bin/env bash
# One command to change the version everywhere: tools/set-version.sh 0.6.5 11
# (VERSION and VERSION_CODE feed Gradle and the desktop scripts; the constant in Aladin.java is updated here.)
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -ge 1 ] || { echo "usage: $0 <version> [android-version-code]" >&2; exit 2; }
printf '%s\n' "$1" > VERSION
[ $# -ge 2 ] && printf '%s\n' "$2" > VERSION_CODE
# Aladin.java is CRLF / Cp1252: edit in place without touching line endings
sed -i "s/\(ASTROVIA_VERSION *= *\"\)[^\"]*\"/\1$1\"/" src/cds/aladin/Aladin.java
echo "version $(cat VERSION), Android versionCode $(cat VERSION_CODE)"
