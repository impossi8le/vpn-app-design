#!/usr/bin/env bash
# Обёртка вокруг Gradle с блокировкой на время прогона.
#
# Зачем: несколько агентов пишут тесты параллельно и запускают Gradle из одного
# чекаута. Два `connectedAndroidTest` одновременно дерутся за один adb-девайс и
# за `.gradle`-кэш: интерфейс теста устанавливается по очереди, и прогон падает
# с «device offline» или «INSTALL_FAILED_ALREADY_EXISTS» — то есть ошибкой
# инфраструктуры, а не теста. Такой отказ маскирует настоящую причину, и
# разбирать его дороже, чем подождать очередь.
#
# Использование:
#   scripts/gradle-locked.sh :feature:account:connectedDebugAndroidTest
#   scripts/gradle-locked.sh test --no-daemon
#
# Блокировка — каталог: `flock` в Git Bash недоступен, а `mkdir` атомарен и в
# Windows, и в POSIX. Ждём до LOCK_WAIT секунд (по умолчанию 1800), затем сдаёмся.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/.gradle-lock"
LOCK_WAIT="${LOCK_WAIT:-1800}"
LOCK_STALE="${LOCK_STALE:-2400}"

export JAVA_HOME="${JAVA_HOME:-D:/VPN_app/.tools/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-D:/VPN_app/.tools/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

waited=0
while ! mkdir "$LOCK" 2>/dev/null; do
  # Протухший замок: держатель умер или его убили таймаутом. Возраст каталога
  # больше LOCK_STALE секунд считаем брошенным, иначе очередь встанет навсегда.
  if [ -f "$LOCK/pid" ]; then
    age=$(( $(date +%s) - $(stat -c %Y "$LOCK/pid" 2>/dev/null || echo 0) ))
    if [ "$age" -gt "$LOCK_STALE" ]; then
      echo "[locked] замок протух (${age}s), снимаю" >&2
      rm -rf "$LOCK"
      continue
    fi
  fi
  if [ "$waited" -ge "$LOCK_WAIT" ]; then
    echo "[locked] не дождался замка за ${LOCK_WAIT}s — сдаюсь" >&2
    exit 75
  fi
  sleep 5
  waited=$((waited + 5))
  [ $((waited % 30)) -eq 0 ] && echo "[locked] жду очередь на Gradle: ${waited}s" >&2
done

echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT INT TERM

# Инструментальные тесты запускают activity на устройстве, а спящий или
# заблокированный экран этого не даёт: прогон падает пачкой
# «No compose hierarchies found» — то есть отказом инфраструктуры, который
# выглядит как дефект тестов. Проверено на живом прогоне: 80 из 88 тестов
# «упали» именно из-за погасшего экрана, а не из-за кода.
#
# Будим и держим экран включённым. Если аргументов про устройство нет — просто
# не мешаем: команда безвредна и на JVM-прогонах.
ADB="$ROOT/.tools/platform-tools/adb.exe"
if [ "${SKIP_DEVICE_PREP:-0}" != "1" ] && [ -x "$ADB" ]; then
  if [ -n "$("$ADB" devices | awk 'NR>1 && $2=="device"')" ]; then
    "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "$ADB" shell svc power stayon true >/dev/null 2>&1 || true

    # Установленное приложение, оставшееся на переднем плане, ломает
    # инструментальные тесты: хост-activity теста не поднимается, и весь класс
    # падает «No compose hierarchies found» — отказом, который выглядит как
    # дефект тестов. Проверено: 19 из 20 тестов «падали», пока на экране висел
    # MainActivity установленного приложения; после am force-stop — 20/20 зелёные.
    "$ADB" shell am force-stop com.impossi8le.vpnapp >/dev/null 2>&1 || true
  fi
fi

cd "$ROOT/android"
./gradlew "$@"
