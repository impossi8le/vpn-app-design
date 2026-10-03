#!/usr/bin/env bash
# Установка и первичная проверка APK на подключённом телефоне.
#
# Зачем скрипт, а не команды по памяти: путь к adb, номер последнего артефакта
# и имя APK меняются каждый прогон, а последовательность шагов — нет. Скрипт
# заодно проверяет предусловия и говорит, чего не хватает, вместо того чтобы
# падать на середине.
#
# Использование:
#   scripts/install-apk.sh                 # скачать последний debug-APK и поставить
#   scripts/install-apk.sh путь/к.apk      # поставить конкретный файл

set -euo pipefail

REPO="impossi8le/vpn-app-design"
WORKFLOW="android.yml"
PKG="com.impossi8le.vpnapp"
ADB="$(dirname "$0")/../.tools/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="adb"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# --- предусловия ---
command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || die "adb не найден. Ожидается в .tools/platform-tools/ или в PATH."

DEVICES=$("$ADB" devices | tail -n +2 | grep -c "device$" || true)
if [ "$DEVICES" -eq 0 ]; then
  die "Телефон не подключён. Включите отладку по USB и подтвердите запрос на экране телефона."
fi
say "Устройств подключено: $DEVICES"

APK="${1:-}"

if [ -z "$APK" ]; then
  command -v curl >/dev/null || die "нужен curl, чтобы скачать артефакт"
  TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null \
    | grep '^password=' | cut -d= -f2-)
  [ -n "$TOKEN" ] || die "не удалось достать токен GitHub из хранилища учётных данных"

  say "Ищу последний успешный прогон $WORKFLOW…"
  RUN=$(curl -s --max-time 25 -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/runs?per_page=1&status=success" \
    | python -c "import json,sys;print(json.load(sys.stdin)['workflow_runs'][0]['id'])")
  [ -n "$RUN" ] || die "нет успешных прогонов"

  URL=$(curl -s --max-time 25 -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$REPO/actions/runs/$RUN/artifacts" \
    | python -c "
import json,sys
arts=[a for a in json.load(sys.stdin)['artifacts'] if a['name'].startswith('vpn-app-debug')]
if not arts: raise SystemExit('артефакт debug-APK не найден')
print(arts[0]['archive_download_url'])
")
  [ -n "$URL" ] || die "артефакт debug-APK не найден в прогоне $RUN"

  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  say "Скачиваю артефакт прогона $RUN…"
  curl -sL --max-time 180 -H "Authorization: Bearer $TOKEN" "$URL" -o "$TMP/a.zip"
  unzip -oq "$TMP/a.zip" -d "$TMP"
  APK=$(find "$TMP" -name '*.apk' | head -1)
  [ -n "$APK" ] || die "в архиве нет .apk"
fi

[ -f "$APK" ] || die "файл не найден: $APK"
say "Ставлю $(basename "$APK") ($(wc -c < "$APK") байт)…"
"$ADB" install -r -d "$APK"

say "Запускаю приложение…"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null

sleep 3
say "Проверяю, что процесс жив (не упал при старте)…"
if "$ADB" shell pidof "$PKG" >/dev/null 2>&1; then
  say "OK: приложение запущено."
else
  die "Процесс не найден — смотрите логи: $ADB logcat -d | tail -50"
fi

say "Скриншот: $ADB exec-out screencap -p > screen.png"
say "Логи:      $ADB logcat -d | tail -50"
