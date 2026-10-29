#!/usr/bin/env bash
# Set up a Fire TV in one run: back up, debloat, install Hearth and point Home at it.
#
# Usage: TV_HOST=192.168.1.50 ./install.sh
#   LIST=lists/<file>.txt   debloat list (default: the one for this TV's model or codename, if lists/ has one)
#   SKIP_DEBLOAT=1          launcher only
#   DNS=1                   also set ad-blocking Private DNS (dns.adguard-dns.com)
#   THEME=themes/<name>     push a theme once Hearth is in
#   SCREENSAVER=1           make Hearth's clock (with the ambience scene, in a theme with "fireplace") the screensaver
#   CLAUDE_API_KEY=sk-...   turn on Live Dr. Malvek in Chess (his lines written by Claude; billed to that key)
#   HEARTH_APK=<file>       install this APK instead of building one or downloading the latest release
#
# Undo everything with ./uninstall.sh.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
PKG=io.github.srikarmk.hearth
OLD_PKG=com.gathin.tvhome
SERVICE=$PKG/.FireHomeService
REPO=Srikarmk/hearth

PY=tools/venv/bin/python
if [ ! -x "$PY" ]; then
  echo "== Setting up tools/venv"
  python3 -m venv tools/venv
  tools/venv/bin/pip install -q -r tools/requirements.txt
fi
tv() { "$PY" -I tools/tv.py "$@"; }
prop() { tv "getprop $1" | tr -d '\r\n'; }

echo "== Connecting to $TV_HOST (accept \"Allow USB debugging?\" on the TV if it asks)"
MAKER=$(prop ro.product.manufacturer)
MODEL=$(prop ro.product.model)
DEVICE=$(prop ro.product.device)
SDK=$(prop ro.build.version.sdk)
echo "   $MAKER $MODEL ($DEVICE), $(prop ro.build.version.name), SDK $SDK"
if [ "$MAKER" != Amazon ]; then
  echo "This is not a Fire TV. Hearth's Home redirect is built for Fire OS; on Google TV use upstream cleanyourtv." >&2
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
  if [ -z "${LIST:-}" ]; then
    lower=$(printf '%s' "$MODEL" | tr '[:upper:]' '[:lower:]')
    if [ -f "lists/fire-tv-$lower.txt" ]; then
      LIST=lists/fire-tv-$lower.txt
    else
      by_code=$(awk -v d="$DEVICE" '$1 == d {print $2}' lists/codenames.txt)
      [ -n "$by_code" ] && LIST=lists/$by_code
    fi
  fi
  if [ -n "${LIST:-}" ] && [ -f "$LIST" ]; then
    echo "== Debloating with $LIST"
    tools/disable.sh "$LIST"
  else
    echo "== No tested list for $MODEL ($DEVICE): skipping the debloat. Pass LIST=lists/<file>.txt to pick one."
  fi
fi

if [ -n "${DNS:-}" ]; then
  echo "== Private DNS: dns.adguard-dns.com"
  tv 'settings get global private_dns_mode' > "$BACKUP/private-dns-mode.txt"
  tv 'settings put global private_dns_specifier dns.adguard-dns.com'
  tv 'settings put global private_dns_mode hostname'
fi

if [ "$SDK" -lt 26 ]; then
  echo "== SDK $SDK is below Hearth's minimum (Android 8, Fire OS 7). The debloat is done; the launcher can't run here."
  exit 0
fi

# A Hearth built before the rename ran as com.gathin.tvhome; upstream cleanyourtv's launcher uses that name too,
# so only remove it when it carries Hearth's FireHomeService.
if tv "dumpsys package $OLD_PKG" | grep -q FireHomeService; then
  echo "== Removing the earlier Hearth build ($OLD_PKG)"
  current=$(tv 'settings get secure enabled_accessibility_services' | tr -d '\r\n')
  rest=$(printf '%s' "$current" | tr ':' '\n' | grep -v "^$OLD_PKG/" | grep -vx null | paste -sd: - || true)
  if [ -n "$rest" ]; then tv "settings put secure enabled_accessibility_services $rest"; else tv 'settings delete secure enabled_accessibility_services'; fi
  tv "pm uninstall $OLD_PKG" || true
fi

APK=${HEARTH_APK:-}
if [ -z "$APK" ]; then
  [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17
  if [ -z "${ANDROID_HOME:-}" ]; then
    for d in ~/Library/Android/sdk ~/Android/Sdk /opt/homebrew/share/android-commandlinetools; do
      [ -d "$d/platforms" ] && export ANDROID_HOME=$d && break
    done
  fi
  if [ -n "${JAVA_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    echo "== Building Hearth"
    [ -f launcher/local.properties ] || echo "sdk.dir=$ANDROID_HOME" > launcher/local.properties
    (cd launcher && ./gradlew assembleRelease -q --console=plain)
    APK=launcher/app/build/outputs/apk/release/app-release.apk
  else
    echo "== No JDK 17 + Android SDK here: downloading the latest release"
    mkdir -p work
    APK=work/hearth-latest.apk
    if command -v gh >/dev/null && gh release download -R "$REPO" -p '*.apk' -O "$APK" --clobber 2>/dev/null; then :; else
      url=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | "$PY" -I -c 'import json,sys; print(next(a["browser_download_url"] for a in json.load(sys.stdin)["assets"] if a["name"].endswith(".apk")))')
      curl -fL -o "$APK" "$url"
    fi
  fi
fi

echo "== Installing $APK"
"$PY" -I tools/b64push.py "$APK" /data/local/tmp/hearth.apk
tv 'pm install -r /data/local/tmp/hearth.apk'
tv 'rm -f /data/local/tmp/hearth.apk'

echo "== Pointing Home at Hearth"
CURRENT=$(tv 'settings get secure enabled_accessibility_services' | tr -d '\r\n')
case "$CURRENT" in
  null|'') NEW=$SERVICE ;;
  *"$SERVICE"*) NEW=$CURRENT ;;
  *) NEW="$CURRENT:$SERVICE" ;;
esac
tv "settings put secure enabled_accessibility_services $NEW"
tv 'settings put secure accessibility_enabled 1'
# Android rebinds the service a few seconds after an install; until then a Home press still lands on Amazon's
# home, where the next key can open an ad. Wait for it before handing the remote back.
for _ in $(seq 1 20); do
  tv 'dumpsys accessibility' | grep -q "$PKG/.*FireHomeService\|label=Fire TV Home" && break
  sleep 1
done
tv "am start -n $PKG/.MainActivity" > /dev/null

if [ -n "${CLAUDE_API_KEY:-}" ]; then
  echo "== Live Dr. Malvek on"
  mkdir -p work
  printf '%s' "$CLAUDE_API_KEY" > work/claude_key.txt
  "$PY" -I tools/b64push.py work/claude_key.txt /data/local/tmp/hearth-key >/dev/null
  rm -f work/claude_key.txt
  tv "content write --uri content://$PKG.theme/claude_key.txt < /data/local/tmp/hearth-key; rm -f /data/local/tmp/hearth-key"
fi

if [ -n "${THEME:-}" ]; then
  echo "== Theme $THEME"
  tools/theme.sh "$THEME"
fi

if [ -n "${SCREENSAVER:-}" ]; then
  echo "== Screensaver: Hearth's clock"
  tv 'settings get secure screensaver_components' > "$BACKUP/screensaver.txt"
  tv "settings put secure screensaver_components $PKG/.ClockDream"
  tv 'settings put secure screensaver_enabled 1'
  echo "   Fire OS may keep its own screensaver anyway; check Settings > Display & Sounds > Screensaver."
fi

echo
echo "Done. Press Home on the remote: it opens Hearth (twice quickly for Amazon's)."
echo "Phone companion: open Wallpapers on the TV for the address and code."
echo "Backup: $BACKUP. Undo: TV_HOST=$TV_HOST ./uninstall.sh"
echo "When you're finished, switch ADB debugging off: Settings > My Fire TV > Developer options."
