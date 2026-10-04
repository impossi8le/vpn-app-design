#!/usr/bin/env bash
# Поднять эмулятор Android, поставить наше приложение и проверить, что оно живо.
#
# Зачем скрипт: путь к SDK, имя AVD и время загрузки эмулятора — три вещи,
# которые каждый раз вспоминаются заново. Здесь они собраны в одном месте, плюс
# проверяются предусловия с внятным сообщением, а не падением в середине.
#
# Использование:
#   scripts/run-emulator.sh            # поднять эмулятор и поставить APK
#   scripts/run-emulator.sh --no-apk   # только поднять

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$ROOT/.tools/sdk}"
ADB="$ROOT/.tools/platform-tools/adb.exe"
EMULATOR="$SDK/emulator/emulator.exe"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager.bat"
AVD_NAME="vpnapp-api35"
IMAGE="system-images;android-35;google_apis;x86_64"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

[ -x "$ADB" ] || ADB="adb"
command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || die "adb не найден"
[ -f "$EMULATOR" ] || die "эмулятор не найден: $EMULATOR (поставьте 'emulator' через sdkmanager)"
[ -d "$SDK/system-images/android-35/google_apis/x86_64" ] || \
  die "нет системного образа $IMAGE — поставьте его через sdkmanager"

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
# sdkmanager и avdmanager — .bat, им нужен JAVA_HOME. JDK есть в Android Studio.
export JAVA_HOME="${JAVA_HOME:-D:/AndroidStudio/jbr}"

# --- AVD --------------------------------------------------------------------
if ! "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
  say "Создаю AVD $AVD_NAME…"
  echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE" --force >/dev/null
fi

# --- запуск -----------------------------------------------------------------
if "$ADB" devices | tail -n +2 | grep -q "emulator.*device$"; then
  say "Эмулятор уже запущен"
else
  say "Запускаю эмулятор (первый старт может занять несколько минут)…"
  # -no-snapshot: предсказуемый старт, без зависимости от прошлого состояния.
  # -gpu swiftshader_indirect: на headless/без GPU это надёжнее хостового GL.
  #
  # -dns-server ОБЯЗАТЕЛЕН, и вот почему. Без него slirp прописывает гостю свой
  # прокси 10.0.2.3, который не отвечает: `dumpsys dnsresolver` показывал 128
  # таймаутов из 128 UDP-запросов, и `ping github.com` давал «unknown host» при
  # том, что ICMP до 8.8.8.8 ходил. Проверить приложение без резолвинга имён
  # невозможно, а `setprop net.dns1` на API 35 не работает, `ndc resolver`
  # убран — обычные советы из интернета тут бессильны. Подробности и пруфы:
  # scripts/emulator-network-notes.md
  "$EMULATOR" -avd "$AVD_NAME" -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect -dns-server 8.8.8.8,8.8.4.4 >/dev/null 2>&1 &
fi

say "Жду загрузки Android…"
"$ADB" wait-for-device
# Ждём именно завершения загрузки: wait-for-device возвращается раньше, и
# установка на ещё не поднятую систему падает.
for i in $(seq 1 90); do
  booted=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)
  [ "$booted" = "1" ] && break
  sleep 5
done
[ "${booted:-}" = "1" ] || die "эмулятор не загрузился за отведённое время"
say "Эмулятор готов"
"$ADB" devices -l | tail -n +2

[ "${1:-}" = "--no-apk" ] && exit 0

say "Ставлю приложение…"
"$ROOT/scripts/install-apk.sh"
