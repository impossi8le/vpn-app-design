# Локальная сборка APK (Windows)

Пока эта штука экономит ~10 минут на каждой итерации: то же самое делает CI,
но в облаке и с нуля.

## Что нужно один раз

- **JDK 17** в `D:\VPN_app\.tools\jdk17` (Temurin 17.0.20.1). JDK 21 от Android
  Studio НЕ подходит: `jvmToolchain(17)` не находит 17-й тулчейн под 21-м.
- **Android SDK** в `D:\VPN_app\.tools\sdk` (platform-tools, platforms;android-35,
  build-tools, ndk;27.3.13750724, cmake;3.22.1).
- `android/local.properties` с `sdk.dir=D:\\VPN_app\\.tools\\sdk` (в .gitignore).
- **Движок разложен** в `android/vpnengine/src/main/jniLibs/<abi>/ovpn3.so`
  (arm64-v8a, x86_64) и `android/vpnengine/src/main/java/**`. Без них `:vpnengine`
  падает на `preBuild` — это намеренно (см. комментарий в build.gradle.kts).

## Команда сборки

```bash
cd /d/VPN_app/android
JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon
```

APK: `D:\VPN_app\android\app\build\outputs\apk\debug\app-debug.apk` (~105 МБ).

Проверить версию Java: `D:/VPN_app/.tools/jdk17/bin/java.exe -version`.

## Тайминги (эта машина)

| Сборка | Время |
|---|---|
| Полная (холодная, без кэша) | ~60 с |
| Повторная (всё up-to-date) | ~11 с |

Движок НЕ пересобирается: `ovpn3.so` — готовый артефакт из CI, Gradle его только
упаковывает. Поэтому локальная сборка быстрая.

## Грабли

1. **JDK 21 вместо 17.** `jvmToolchain(17)` при JDK 21 в `JAVA_HOME` падает с
   «No matching toolchains». Решение: явно `JAVA_HOME=D:/VPN_app/.tools/jdk17`.
2. **Скрипт Gradle `./gradlew` — это sh-скрипт**, в Git Bash работает. Путь
   `JAVA_HOME` — в POSIX-форме (`D:/...`), обратные слэши не годятся.
3. **Движка нет** → `:vpnengine:preBuild` бросает GradleException. Артефакт
   `ovpn3-engine` из CI (джоба `engine` в android.yml) распаковывается в
   `android/vpnengine/src/main/` — внутри `jniLibs/` и `java/` уже в нужной
   раскладке.
4. **Первый запуск** качает Gradle 8.11.1 (wrapper) и зависимости — это разово.
5. `--no-daemon` замедляет повторные сборки (JVM поднимается заново), но даёт
   чистый замер и не висит в фоне. Можно убрать для скорости.

## Обновить движок (если изменился Rust/C++)

Скачать артефакт `ovpn3-engine` последнего успешного прогона джобы `engine`:

```bash
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2-)
# archive_download_url из: /repos/<owner>/<repo>/actions/runs/<run_id>/artifacts
curl -sL -H "Authorization: Bearer $TOKEN" "<archive_download_url>" -o engine.zip
cd /d/VPN_app/android/vpnengine/src/main && unzip -o engine.zip
```

Репозиторий: `impossi8le/vpn-app-design`.
