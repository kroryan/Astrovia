#!/bin/sh
# Astrovia launcher (Linux). Works from the unpacked portable folder and when installed by the .deb.
DIR="$(dirname "$(readlink -f "$0")")"
for JAR in "$DIR/Astrovia.jar" "$DIR/../share/astrovia/Astrovia.jar" /usr/share/astrovia/Astrovia.jar; do
  [ -f "$JAR" ] && break
done
if [ -x "$DIR/jre/bin/java" ]; then JAVA="$DIR/jre/bin/java"; else JAVA="${JAVA:-java}"; fi
# Java apps on tiling/Wayland desktops: let the user override toolkit flags via ASTROVIA_JAVA_OPTS
exec "$JAVA" $ASTROVIA_JAVA_OPTS -jar "$JAR" "$@"
