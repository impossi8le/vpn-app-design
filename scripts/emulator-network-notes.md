# Сеть эмулятора Android: DNS не резолвит

## Симптом
- `adb shell ping 8.8.8.8` → 0% потерь, IP-связность есть.
- `adb shell ping github.com` → `ping: unknown host github.com`.
- Разрешение имён не работает, интернет «наполовину».

## Причина
Эмулятор запускался без флага `-dns-server`. При старте slirp сам выбирает
DNS-сервер и прописал гостю `10.0.2.3` (свой DNS-прокси), но запросы к нему
**не отвечают** — таймаут.

Прямые доказательства:
- `dumpsys dnsresolver`: `10.0.2.3:53 (128, 5009ms, [TIMEOUT:128], ...) score{0.0}`
  — 128 из 128 UDP-запросов в таймаут.
- `logcat`: `resolv: res_nsend: used send_dg 0 terrno: 110`
  (`110` = ETIMEDOUT).
- ICMP до `10.0.2.3` ходит (ping ок), до `8.8.8.8` тоже; не работает именно UDP/53.

Вывод: проблема не в манифесте/разрешениях и не в маршрутах
(дефолтный маршрут `0.0.0.0/0 -> 10.0.2.2` есть, см. `dumpsys connectivity`),
а в том, что DNS-прокси эмулятора не обслуживает запросы.

## Что помогло
Перезапуск эмулятора с явным `-dns-server`:

```
"$SDK/emulator/emulator.exe" -avd vpnapp-api35 \
  -no-snapshot -no-boot-anim -gpu swiftshader_indirect \
  -dns-server 8.8.8.8,8.8.4.4
```

Грациозно: `adb emu kill`, дождаться выхода `qemu-system-x86_64.exe`,
поднять заново, ждать `sys.boot_completed = 1`.

После этого `dumpsys dnsresolver` показывает `10.0.2.3/10.0.2.4` с
`NOERROR`, а `ping github.com` резолвит и отвечает.

## Не забыть в следующий раз
1. `scripts/run-emulator.sh` (строка запуска `emulator`) **не содержит**
   `-dns-server` — добавить `-dns-server 8.8.8.8,8.8.4.4`, иначе баг вернётся.
2. `-no-snapshot` сбрасывает сетевые настройки — фиксировать DNS флагом, а не
   `setprop` (на API 35 `net.dns1` не читается, а `ndc resolver` убран).
3. Быстрая диагностика: `dumpsys dnsresolver | grep -E ":53|DNS servers"`
   — если у сервера `TIMEOUT` и `score{0.0}`, это оно.

## Проверка
```
adb shell ping -c 2 github.com
# 64 bytes from lb-140-82-121-4-fra.github.com (140.82.121.4) ... 0% packet loss

adb shell 'printf "GET / HTTP/1.0\r\nHost: github.com\r\n\r\n" | nc -w 8 github.com 80'
# HTTP/1.1 301 Moved Permanently / Location: https://github.com/
```
