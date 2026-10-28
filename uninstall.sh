#!/usr/bin/env bash
# Put a Fire TV back the way install.sh found it: Amazon's home, the debloated packages re-enabled,
# Private DNS back to automatic.
#
# Usage: TV_HOST=192.168.1.50 ./uninstall.sh
#   LIST=lists/<file>.txt   the list install.sh used (default: lists/fire-tv-<codename>.txt)
#   KEEP_DEBLOAT=1          only remove the launcher (the debloat and Private DNS stay)
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run ./install.sh first, or: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
tv() { "$PY" -I tools/tv.py "$@"; }

echo "== Turning off the Home redirect"
SERVICE=com.gathin.tvhome/.FireHomeService
CURRENT=$(tv 'settings get secure enabled_accessibility_services' | tr -d '\r\n')
REST=$(printf '%s' "$CURRENT" | tr ':' '\n' | grep -vxF -e "$SERVICE" -e com.gathin.tvhome/com.gathin.tvhome.FireHomeService -e null | paste -sd: - || true)
if [ -n "$REST" ]; then
  tv "settings put secure enabled_accessibility_services $REST"
else
  tv 'settings delete secure enabled_accessibility_services'
  tv 'settings put secure accessibility_enabled 0'
fi

echo "== Removing the launcher"
tv 'pm uninstall com.gathin.tvhome' || true

if [ -z "${KEEP_DEBLOAT:-}" ]; then
  LIST=${LIST:-lists/fire-tv-$(tv 'getprop ro.product.device' | tr -d '\r\n').txt}
  if [ -f "$LIST" ]; then
    echo "== Re-enabling $LIST"
    tools/restore.sh "$LIST" com.amazon.tv.launcher
  fi
fi

if [ -z "${KEEP_DEBLOAT:-}" ] && [ "$(tv 'settings get global private_dns_specifier' | tr -d '\r\n')" = dns.adguard-dns.com ]; then
  echo "== Private DNS back to automatic"
  tv 'settings put global private_dns_mode opportunistic'
fi

tv 'input keyevent HOME'
echo "Done: Amazon's home is back."
