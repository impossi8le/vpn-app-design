# Android-клиент — каркас проекта

Каркас многомодульного Gradle-проекта. Соответствует
`docs/architecture/2026-10-03-android-architecture.md`.

**Это каркас, а не работающее приложение.** Ни один модуль пока не содержит
реализации — только структуру и зафиксированные зависимости.

## Состояние

| Что | Состояние |
|---|---|
| Дерево модулей и зависимости | готово |
| Version catalog, settings, корневой build | готово |
| Манифесты (включая `specialUse` foreground-сервис) | готово |
| CI `.github/workflows/android.yml` | готово |
| Gradle wrapper | **нет** — генерируется первым шагом |
| Код модулей | **нет** |
| Вендоринг ics-openvpn | **нет** — submodule не подключён |

## Что проверить сборкой

Версии в `gradle/libs.versions.toml` зафиксированы, но **не проверены прогоном**:
на рабочей машине нет JDK и Android SDK, и локальный тулчейн ставить не планируется
(§10.1 архитектуры). Первый прогон CI обязан подтвердить совместимость
AGP / Kotlin / Compose / Gradle.

## Сборка — только через GitHub Actions

Локально не собирается и не тестируется: JDK, Gradle и Android SDK на машине нет.
**Ни один код не считается проверенным, пока не прошёл CI.**

Workflow `Android` на `ubuntu-latest` сам ставит JDK 17, Android SDK и Gradle 8.11.1.
Запускается на push в `main` и на PR, если затронут `android/` или сам workflow.

```bash
gh run watch
```

## Что сделать первым, когда CI заработает

1. **Сгенерировать Gradle wrapper** — в CI командой `gradle wrapper --gradle-version 8.11.1`,
   закоммитить, перейти на `./gradlew`.
2. **Прогнать `gradle test`** и убедиться, что тесты JVM-модулей действительно
   запускаются. Задача `test` выбрана именно поэтому: `testDebugUnitTest` их не видит.
3. **Добавить эмулятор в CI** (`reactivecircus/android-emulator-runner`), когда
   дойдёт до потока WA6 — без него инвариант защиты §6 непроверяем.

## Границы модулей

Стрелка = «зависит от». Нарушение направления — ошибка проектирования.

```
:app
 ├─ :feature:auth  :feature:home  :feature:configs  :feature:account
 │    └─ :core:domain, :core:ui
 ├─ :core:network  :core:config  :core:security  :core:tunnel  :core:protection
 │    └─ :core:domain
 └─ :vpnservice ─── :core:config + vendor:ics-openvpn (JNI)
```

`core:domain`, `core:config`, `core:network` — **чистый Kotlin/JVM**: тесты идут
без эмулятора. Остальные `core:*` — Android-библиотеки.

## Порядок дальнейшей работы

Потоки `WA0…WA9` описаны в §5 архитектурного документа.
`WA0` (интерфейсы + фейки) обязан смержиться первым — он разблокирует остальные.

Первый шаг перед любым кодом: **сгенерировать Gradle wrapper и прогнать сборку**,
чтобы убедиться, что каркас вообще компилируется.

## Лицензия

Проект под **GPLv2** — следствие использования ics-openvpn. Подробности в §0
архитектурного документа. Файл `LICENSE` в корне репозитория должен это отражать.
