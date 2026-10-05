# Сборка и публикация: где лежат ключи и артефакты

Дата: 2026-10-06. Статус: **работает** — релиз `android-v92` собран, подписан и
скачивается анонимно.

Дополняет §10.3 архитектуры: там описан замысел, здесь — что реально сделано и
где лежат ключи, которые легко потерять.

## Ключ подписи Android

Релизный keystore создан 2026-10-06 и **не хранится в репозитории** (каталог
`.tools/` в `.gitignore`). Если он потеряется, приложение нельзя будет обновить
поверх установленного: Android отвергнет APK с другим ключом, и пользователю
придётся удалять и ставить приложение заново.

| Что | Где | Значение |
|---|---|---|
| Keystore | `.tools/release.jks` (вне git) | PKCS12, RSA 2048, validity 10000 дней |
| Alias | GitHub Secret `ANDROID_KEY_ALIAS` | `vpnapp` |
| Пароль хранилища | GitHub Secret `ANDROID_KEYSTORE_PASSWORD` | 32 случайных символа |
| Пароль ключа | GitHub Secret `ANDROID_KEY_PASSWORD` | **тот же**, что у хранилища |
| Keystore в base64 | GitHub Secret `ANDROID_KEYSTORE_BASE64` | `base64 -w0 .tools/release.jks` |
| Отпечаток сертификата | — | SHA-256 `3C:23:F6:0A:A7:54:EF:59:89:C7:0F:76:A2:25:1E:CF:7C:0F:AA:D5:C1:2F:13:19:B5:4F:88:11:97:36:AA:90` |

**Пароли ключа и хранилища совпадают.** Это не упущение: `keytool` с PKCS12
игнорирует отдельный `-keypass` (`Warning: Different store and key passwords not
supported for PKCS12 KeyStores`) и пишет об этом. Значение лежит в
`.tools/.kstore_pw` (тоже вне git).

### Где взять копию, если `.tools/` потеряется

Секреты в GitHub восстановить нельзя — их можно только перезаписать. Поэтому
достать keystore обратно можно лишь из секрета:

```bash
gh secret list                       # убедиться, что секреты на месте
```

Если секрет цел, а локального файла нет — base64-значение доступно только
перезаписью (GitHub не отдаёт секреты наружу). **Практический вывод: сделайте
резервную копию `.tools/release.jks` и `.tools/.kstore_pw` отдельно от этой
машины.** Это единственная вещь в проекте, потеря которой невосстановима.

## Публикация релиза (что читает приложение)

Джоба `release` в `.github/workflows/android.yml` после подписи выполняет шаг
`Publish release`: создаёт GitHub Release с тегом `android-v<run_number>` и
прикладывает APK. Тег — машинно-читаемая привязка к сборке: `run_number` и есть
`ANDROID_VERSION_CODE`, который AGP записывает в `BuildConfig.VERSION_CODE`.

Проверено на релизе `android-v92`:

* тег `android-v92`, `versionCode` APK — `92`, `versionName` — `1.0.92`;
* ассет `app-release.apk`, 106 МБ, скачивается анонимно (HTTP 200);
* подписан ключом `CN=VPN App, O=impossi8le` (отпечаток выше);
* `GET /releases/latest` отдаёт этот тег и `browser_download_url` — ровно то,
  что разбирает `UpdateApi`.

## Проверить локально

```bash
cd /d/VPN_app/android
JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon
```

Тулчейн лежит в репозитории (`.tools/jdk17`, `.tools/sdk`) — см. `local-build.md`.
Юнит-тесты: `./gradlew test`, релизную сборку локально не поднять без
`ANDROID_KEYSTORE_PATH` и паролей.
