#!/bin/sh
# Astrovia launcher inside the Flatpak sandbox.
# HOME is read-only here, so keep Astrovia's user data (the ".astrovia" folder) in the app's data dir.
export _JAVA_OPTIONS=
exec /app/jdk/bin/java \
  -Duser.home="${XDG_DATA_HOME:-$HOME/.local/share}" \
  -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true \
  $ASTROVIA_JAVA_OPTS \
  -jar /app/share/astrovia/Astrovia.jar "$@"
