#!/usr/bin/env bash
# Renderiza un SVG a PNG con fondo transparente usando Chromium headless.
# Uso: render.sh in.svg out.png ancho alto
set -e
IN="$(readlink -f "$1")"; OUT="$(readlink -f "$(dirname "$2")")/$(basename "$2")"; W=$3; H=$4
TMP=$(mktemp --suffix=.html)
cat > "$TMP" <<HTML
<html><body style="margin:0;background:transparent"><img src="file://$IN" width="$W" height="$H" style="display:block"></body></html>
HTML
chromium --headless=new --no-sandbox --disable-gpu --hide-scrollbars --default-background-color=00000000 \
  --window-size=$W,$H --screenshot="$OUT" "file://$TMP" >/dev/null 2>&1
rm -f "$TMP"
