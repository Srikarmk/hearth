#!/usr/bin/env bash
# Set up a Fire TV in one run: back up, debloat, build and install the launcher, and point Home at it.
#
# Usage: TV_HOST=192.168.1.50 ./install.sh
#   LIST=lists/<file>.txt   debloat list (default: lists/fire-tv-<codename>.txt for this TV, if there is one)
#   DNS=1                   also set ad-blocking Private DNS (dns.adguard-dns.com)
#   THEME=themes/<name>     push a theme once the launcher is in
#   SKIP_DEBLOAT=1          launcher only
#   SKIP_FIREPLACE=1        don't download and install the fireplace clip (19 MB)
#
# Undo everything with ./uninstall.sh.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST

PY=tools/venv/bin/python
if [ ! -x "$PY" ]; then
  echo "== Setting up tools/venv"
  python3 -m venv tools/venv
  tools/venv/bin/pip install -q -r tools/requirements.txt
fi
tv() { "$PY" -I tools/tv.py "$@"; }
FIRE_URL=https://upload.wikimedia.org/wikipedia/commons/b/bb/Fantastic-fireplace-fire-chimney-hearth-_background_-_texture_-_motion_graphics_-_free_video_library.webm
prop() { tv "getprop $1" | tr -d '\r\n'; }

echo "== Connecting to $TV_HOST (accept \"Allow USB debugging?\" on the TV if it asks)"
MAKER=$(prop ro.product.manufacturer)
DEVICE=$(prop ro.product.device)
SDK=$(prop ro.build.version.sdk)
echo "   $MAKER $(prop ro.product.model) ($DEVICE), $(prop ro.build.version.name), SDK $SDK"
if [ "$MAKER" != Amazon ]; then
  echo "This is not a Fire TV. Hearth's Home redirect is built for Fire OS; on Google TV use upstream cleanyourtv." >&2
  exit 1
fi
if [ "$SDK" -lt 26 ]; then
  echo "SDK $SDK is below the launcher's minSdk 26 (Fire OS 7 and newer). Nothing was changed." >&2
  exit 1
fi

BACKUP=backup/$(date +%Y%m%d-%H%M%S)
echo "== Backing up to $BACKUP"
mkdir -p "$BACKUP"
tv 'pm list packages -f' > "$BACKUP/packages.txt"
tv 'pm list packages -d' > "$BACKUP/disabled.txt"
tv getprop > "$BACKUP/getprop.txt"
for ns in system secure global; do tv "settings list $ns" > "$BACKUP/settings-$ns.txt"; done

if [ -z "${SKIP_DEBLOAT:-}" ]; then
  LIST=${LIST:-lists/fire-tv-$DEVICE.txt}
  if [ -f "$LIST" ]; then
    echo "== Debloating with $LIST"
    tools/disable.sh "$LIST"
  else
    echo "== No list for '$DEVICE' yet (looked for $LIST): skipping the debloat. Pass LIST=... to pick one."
  fi
fi

if [ -n "${DNS:-}" ]; then
  echo "== Private DNS: dns.adguard-dns.com"
  tv "settings get global private_dns_mode" > "$BACKUP/private-dns-mode.txt"
  tv 'settings put global private_dns_specifier dns.adguard-dns.com'
  tv 'settings put global private_dns_mode hostname'
fi

echo "== Building the launcher"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17; fi
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in ~/Library/Android/sdk ~/Android/Sdk /opt/homebrew/share/android-commandlinetools; do
    [ -d "$d" ] && export ANDROID_HOME=$d && break
  done
fi
[ -f launcher/local.properties ] || echo "sdk.dir=${ANDROID_HOME:?install the Android SDK or set ANDROID_HOME}" > launcher/local.properties
(cd launcher && ./gradlew assembleRelease -q --console=plain)
APK=launcher/app/build/outputs/apk/release/app-release.apk

echo "== Installing"
"$PY" -I tools/b64push.py "$APK" /data/local/tmp/hearth.apk
tv 'pm install -r /data/local/tmp/hearth.apk'
tv 'rm -f /data/local/tmp/hearth.apk'

if [ -z "${SKIP_FIREPLACE:-}" ]; then
  echo "== Fireplace clip (Wikimedia Commons, CC BY 3.0)"
  mkdir -p media
  [ -s media/fireplace.webm ] || curl -fL -A 'hearth-install' -o media/fireplace.webm "$FIRE_URL"
  "$PY" -I tools/b64push.py media/fireplace.webm /data/local/tmp/fireplace.webm
  tv 'content write --uri content://com.gathin.tvhome.theme/fireplace.webm < /data/local/tmp/fireplace.webm; rm -f /data/local/tmp/fireplace.webm'
fi

echo "== Pointing Home at the launcher"
SERVICE=com.gathin.tvhome/.FireHomeService
CURRENT=$(tv 'settings get secure enabled_accessibility_services' | tr -d '\r\n')
case "$CURRENT" in
  null|'') NEW=$SERVICE ;;
  *"$SERVICE"*) NEW=$CURRENT ;;
  *) NEW="$CURRENT:$SERVICE" ;;
esac
tv "settings put secure enabled_accessibility_services $NEW"
tv 'settings put secure accessibility_enabled 1'
tv 'am start -n com.gathin.tvhome/.MainActivity' > /dev/null

if [ -n "${THEME:-}" ]; then
  echo "== Theme $THEME"
  tools/theme.sh "$THEME"
fi

echo
echo "Done. Press Home on the remote: it opens the new home screen (twice quickly for Amazon's)."
echo "Backup: $BACKUP. Undo: TV_HOST=$TV_HOST ./uninstall.sh"
echo "When you're finished, switch ADB debugging off: Settings > My Fire TV > Developer options."
