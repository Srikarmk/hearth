#!/usr/bin/env bash
# Send a folder of your own pictures to the TV for the top bar's Wallpapers button.
#
# Usage: TV_HOST=192.168.1.50 tools/wallpapers.sh ~/Pictures/tv
#        TV_HOST=192.168.1.50 tools/wallpapers.sh --clear      # remove them all
#
# JPG, PNG and WebP up to 10 MB each, at most 100. Names are cleaned to letters, digits, - _ and . so the
# launcher accepts them ("Spider-Man Noir.JPG" becomes spider-man_noir.jpg) and shown as titles in the picker.
# A theme push (tools/theme.sh) replaces the collection with the theme's own pictures: run this again after one.
# The pictures stay on your computer and the TV; nothing here puts them in the repo.
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0 <folder>}"
SRC="${1:?usage: wallpapers.sh <folder> | --clear}"
[ "$SRC" = --clear ] || SRC="$(cd "$SRC" && pwd)"
cd "$(dirname "$0")"
PY=venv/bin/python
[ -x "$PY" ] || { echo "run ./install.sh first (it sets up tools/venv)" >&2; exit 1; }
tv() { "$PY" -I tv.py "$@"; }
URI=content://io.github.srikarmk.hearth.theme

if [ "$SRC" = --clear ]; then
  tv "content delete --uri $URI/wallpapers" >/dev/null
  echo "Removed the wallpapers. Restarting Home."
  "$PY" -I theme_restart.py
  exit 0
fi

sent=0
while IFS= read -r -d '' f; do
  base="$(basename "$f")"
  ext="$(printf '%s' "${base##*.}" | tr '[:upper:]' '[:lower:]')"
  case "$ext" in jpg|jpeg|png|webp) ;; *) continue ;; esac
  size=$(stat -f %z "$f" 2>/dev/null || stat -c %s "$f")
  if [ "$size" -gt 10485760 ]; then echo "skipping $base: over 10 MB"; continue; fi
  # Wallpaper sites name files like "wp9200837-1920x1080-manga-wallpapers": drop the site id, the resolution,
  # bare numbers and the word wallpaper, so the picker shows "Manga". Your own files keep their names.
  name="$(printf '%s' "${base%.*}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '\n' \
    | grep -vE '^(u?wp[0-9]+|[0-9]+x[0-9]+|[0-9]+|wallpapers?|hd|4k|uhd)$' | paste -sd_ - || true)"
  [ -n "$name" ] || name="wallpaper_$((sent + 1))"
  echo "== $base -> $name.$ext"
  "$PY" -I b64push.py "$f" /data/local/tmp/hearth-wallpaper >/dev/null
  tv "content write --uri $URI/wallpapers/$name.$ext < /data/local/tmp/hearth-wallpaper; rm -f /data/local/tmp/hearth-wallpaper"
  sent=$((sent + 1))
  [ "$sent" -lt 100 ] || break
done < <(find "$SRC" -maxdepth 1 -type f -print0 | sort -z)

echo "Sent $sent picture(s). Restarting Home; then use the Wallpapers button in the top bar."
"$PY" -I theme_restart.py
