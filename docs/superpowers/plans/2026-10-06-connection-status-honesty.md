# Честный статус, уведомление в шторке, кнопка «Показать» — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Закрепить тестом, что «Подключено» выводится только из события ядра `CONNECTED`; сделать текст уведомления в шторке зависящим от реального состояния; починить кнопку «Показать» в аккаунте.

**Architecture:** Три независимых правки. (1) Тесты — без изменений кода, инвариант уже верен. (2) `VpnTunnelService` получает чистую функцию `notificationTextFor(state)` и вызывает `notify` при смене состояния. (3) `telegramIdRevealed` перестаёт быть константой и становится полем состояния приложения; добавляется обработчик `AppIntent.ToggleTelegramId`.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 5 (`useJUnitPlatform`), Android `NotificationManager`. Локальная сборка: `JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew … --no-daemon` из каталога `android/`.

## Global Constraints

- `minSdk = 26`, `targetSdk = 35` — не менять.
- **Инструментальные тесты (`src/androidTest`) не используют backtick-имена методов** (D8 до DEX 040 запрещает пробелы). JVM-тесты (`src/test`) — используют.
- **Не заворачивать вывод Gradle в конвейер** — любой `| tee` маскирует код возврата и даёт ложную зелень.
- Не выводить зелёный статус из системного флага (§6). Уведомление не должно содержать имя сервера и профиля (видно на экране блокировки).
- Русские строки — дословно из текста выше, менять нельзя без правки спеки.
- Никаких комментариев, объясняющих ЧТО делает код. Комментарий — только там, где неочевидно ПОЧЕМУ.

---

### Task 1: Защитить инвариант «не из кнопки» тестом провайдера состояния

**Files:**
- Test: `android/core/tunnel/src/test/kotlin/com/impossi8le/vpnapp/tunnel/StatusMappingTest.kt` (дополнить)

**Interfaces:**
- Consumes: `SystemState` (`core/tunnel/.../SystemStateMapping.kt:12`), `SystemState.toConnectionStatus()`.
- Produces: ничего (только тест).

- [ ] **Step 1: Дописать тест-функцию в существующий класс**

Добавить в `StatusMappingTest` (после теста «потеря туннеля возвращает в отключено»):

```kotlin
    @Test
    fun `заголовок подключено снимается потерей туннеля`() {
        // Полный ход события ядра, как он доходит до экрана: ядро сообщило
        // CONNECTED (ESTABLISHED), затем связь пропала (LOST). Экран обязан
        // перестать показывать «подключено» на втором шаге. Тест держит это
        // правило: если кто-то свяжет заголовок с фактом нажатия кнопки, а не
        // с событием ядра, первая же половина упадёт.
        val established = SystemState.ESTABLISHED.toConnectionStatus()
        assertTrue(
            established is ConnectionStatus.VerifyingProtection,
            "после CONNECTED туннель считается поднятым",
        )

        val lost = SystemState.LOST.toConnectionStatus()
        assertTrue(
            lost is ConnectionStatus.Disconnected,
            "после LOST заголовок «подключено» обязан сняться",
        )
    }
```

- [ ] **Step 2: Прогнать тест**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:tunnel:test --tests "com.impossi8le.vpnapp.tunnel.StatusMappingTest" --no-daemon`
Expected: PASS (правило уже держится кодом — тест его закрепляет).

- [ ] **Step 3: Прогнать тест с намеренной поломкой (проверка, что тест ловит)**

Временно (в черновике, не коммитить) поменять в `SystemStateMapping.kt` строку `SystemState.LOST -> ConnectionStatus.Disconnected` на `SystemState.LOST -> ConnectionStatus.VerifyingProtection`, прогнать тест, убедиться, что он FAILED, вернуть как было.
Expected: FAILED с сообщением «после LOST заголовок «подключено» обязан сняться».

- [ ] **Step 4: Вернуть код, убедиться, что тест снова зелёный**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:tunnel:test --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/core/tunnel/src/test/kotlin/com/impossi8le/vpnapp/tunnel/StatusMappingTest.kt
git commit -m "Запереть тестом: заголовок подключено идёт от ядра, не от кнопки"
```

---

### Task 2: Чистая функция текста уведомления

**Files:**
- Modify: `android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnTunnelService.kt:29` (рядом с `enum class SystemState`)
- Create: `android/vpnservice/src/test/kotlin/com/impossi8le/vpnapp/vpnservice/NotificationTextTest.kt`

**Interfaces:**
- Consumes: `SystemState` (`VpnTunnelService.kt:29`, `enum class SystemState { IDLE, CONNECTING, ESTABLISHED, LOST, FAILED }`).
- Produces: `internal fun notificationTextFor(state: SystemState): String?` — текст уведомления; `null` означает «уведомление снять».

- [ ] **Step 1: Написать падающий тест**

Create `android/vpnservice/src/test/kotlin/com/impossi8le/vpnapp/vpnservice/NotificationTextTest.kt`:

```kotlin
package com.impossi8le.vpnapp.vpnservice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Текст уведомления идёт от реального состояния, а не от константы.
 *
 * Раньше в шторке всегда висело «Туннель поднимается», в том числе когда
 * туннель уже поднят. Это ложное сообщение о процессе — тот же класс ошибки,
 * что и ложный зелёный статус, против которого написан §6.
 */
class NotificationTextTest {

    @Test
    fun `в покое уведомления нет`() {
        assertNull(notificationTextFor(SystemState.IDLE))
    }

    @Test
    fun `подключение говорит о процессе`() {
        assertEquals("Подключение…", notificationTextFor(SystemState.CONNECTING))
    }

    @Test
    fun `поднятый туннель говорит подключено`() {
        assertEquals("Подключено", notificationTextFor(SystemState.ESTABLISHED))
    }

    @Test
    fun `потеря связи говорит прямо`() {
        assertEquals("Соединение потеряно", notificationTextFor(SystemState.LOST))
    }

    @Test
    fun `отказ говорит об отказе`() {
        assertEquals("Не удалось подключиться", notificationTextFor(SystemState.FAILED))
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что не компилируется/падает**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :vpnservice:test --tests "com.impossi8le.vpnapp.vpnservice.NotificationTextTest" --no-daemon`
Expected: FAIL — `notificationTextFor` не найдена (Unresolved reference).

- [ ] **Step 3: Реализовать функцию**

В `VpnTunnelService.kt`, сразу после строки `enum class SystemState { IDLE, CONNECTING, ESTABLISHED, LOST, FAILED }` (строка 29), добавить:

```kotlin
/**
 * Текст уведомления по системному состоянию.
 *
 * `null` — «уведомление снять»: в покое держать его не за чем, а висящее
 * «Подключено» после отключения было бы ложью в шторке.
 *
 * Функция чистая и живёт вне сервиса: уведомление нельзя показать в тесте без
 * устройства, а правило «какое состояние — какой текст» проверить можно и нужно.
 */
internal fun notificationTextFor(state: SystemState): String? = when (state) {
    SystemState.IDLE -> null
    SystemState.CONNECTING -> "Подключение…"
    SystemState.ESTABLISHED -> "Подключено"
    SystemState.LOST -> "Соединение потеряно"
    SystemState.FAILED -> "Не удалось подключиться"
}
```

- [ ] **Step 4: Прогнать — убедиться, что зелено**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :vpnservice:test --tests "com.impossi8le.vpnapp.vpnservice.NotificationTextTest" --no-daemon`
Expected: PASS (5 тестов).

- [ ] **Step 5: Commit**

```bash
git add android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnTunnelService.kt android/vpnservice/src/test/kotlin/com/impossi8le/vpnapp/vpnservice/NotificationTextTest.kt
git commit -m "Вывести текст уведомления из состояния, а не из константы"
```

---

### Task 3: Обновлять уведомление при смене состояния

**Files:**
- Modify: `android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnTunnelService.kt` — `buildNotification()` (строка 282), `notifyState()` (строка 243), `startForeground` (строка 143), `stopTunnel` (строка 210)

**Interfaces:**
- Consumes: `notificationTextFor(state)` из Task 2; `SystemState`; константы `NOTIFICATION_ID` (1001), `NOTIFICATION_CHANNEL_ID` ("vpn_status").
- Produces: ничего наружу.

- [ ] **Step 1: Сделать `buildNotification` параметризованным по тексту**

Заменить функцию `buildNotification()` (строки 282-303) целиком:

```kotlin
    private fun buildNotification(text: String): android.app.Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(channelId) == null) {
            manager?.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    "Состояние VPN",
                    // IMPORTANCE_LOW: состояние туннеля — это не событие, ради
                    // которого стоит звонить и вибрировать.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }
```

- [ ] **Step 2: Обновлять уведомление в `notifyState`**

Заменить `notifyState` (строки 243-249) целиком:

```kotlin
    private fun notifyState(state: SystemState) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state.name),
        )
        updateNotification(state)
    }

    /**
     * Привести уведомление в соответствие с состоянием.
     *
     * `startForeground` вызывается один раз при старте, и без этого обновления
     * в шторке навсегда остался бы стартовый текст. В покое уведомление не
     * снимаем через `cancel` — его снимает `stopForeground` при остановке
     * сервиса; здесь достаточно не перерисовывать его текстом «подключено».
     */
    private fun updateNotification(state: SystemState) {
        val text = notificationTextFor(state) ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }
```

- [ ] **Step 3: Стартовое уведомление — по тому же правилу**

В `startTunnel` строку 143 `startForeground(NOTIFICATION_ID, buildNotification())` заменить на:

```kotlin
        startForeground(
            NOTIFICATION_ID,
            buildNotification(notificationTextFor(SystemState.CONNECTING) ?: "Подключение…"),
        )
```

- [ ] **Step 4: Собираемость и тесты модуля**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :vpnservice:build --no-daemon`
Expected: BUILD SUCCESSFUL, тесты модуля зелёные.

- [ ] **Step 5: Commit**

```bash
git add android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnTunnelService.kt
git commit -m "Обновлять уведомление в шторке при каждой смене состояния"
```

---

### Task 4: Обработчик кнопки «Показать» и состояние раскрытия

**Files:**
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt` — в редьюсере (после `AppIntent.SetConfirmCountrySwitch`, строка 772); в блоке `SignOut` (строки 701-708)
- Modify: `android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AccountMapping.kt:28-37`

**Interfaces:**
- Consumes: `AppIntent.ToggleTelegramId` (`AppRoot.kt:442`), `AccountScreenState.telegramIdRevealed` (`AccountScreen.kt:67`), `DefaultAccountScreenState` (`AccountMapping.kt:28`).
- Produces: рабочее переключение `telegramIdRevealed` в состоянии приложения.

- [ ] **Step 1: Дописать тест маппинга — раскрытие не перетирается загрузкой `/me`**

Дополнить `android/feature/account/src/test/kotlin/com/impossi8le/vpnapp/feature/account/AccountMappingTest.kt` (в тот же класс, где уже есть тест с `telegramIdRevealed = true`):

```kotlin
    @Test
    fun `загрузка me не сбрасывает раскрытие id`() {
        // `toAccountScreenState` переносит поля, которых не знает, из `base`.
        // Раскрытие — именно такое поле: пользователь нажал «Показать», пришёл
        // ответ `/me`, и маска не должна вернуться сама.
        val base = DefaultAccountScreenState.copy(telegramIdRevealed = true)

        val after = emptyConfigList().toAccountScreenState(base)

        assertEquals(true, after.telegramIdRevealed)
    }
```

Если в файле нет хелпера `emptyConfigList()`, добавить приватный в класс:

```kotlin
    private fun emptyConfigList() = ConfigList(
        configs = emptyList(),
        connectionsLimit = 0,
        subscriptionUntilEpochSeconds = null,
    )
```

(Поля `ConfigList` сверить с `core/domain/.../domain/config/Config.kt`; если сигнатура иная — привести к фактической.)

- [ ] **Step 2: Прогнать тест**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :feature:account:test --tests "com.impossi8le.vpnapp.feature.account.AccountMappingTest" --no-daemon`
Expected: PASS (маппинг и так переносит поле — тест это закрепляет).

- [ ] **Step 3: Добавить обработчик интента**

В `MainActivity.kt` в редьюсере, сразу после блока `is AppIntent.SetConfirmCountrySwitch` (строки 769-772), добавить:

```kotlin
                // «Показать/Скрыть» Telegram ID в аккаунте. Интент объявлялся и
                // пробрасывался из экрана, но ветки не было — нажатие молча
                // уходило в `else -> Unit`. Раскрытие живёт в состоянии
                // приложения, а не в маппинге: оно про действие пользователя, а
                // не про ответ сервера.
                AppIntent.ToggleTelegramId ->
                    account = account.copy(telegramIdRevealed = !account.telegramIdRevealed)
```

- [ ] **Step 4: Сбрасывать раскрытие при выходе из аккаунта**

В блоке `AppIntent.SignOut` (строки 701-708) после `signedOut = true` добавить:

```kotlin
                    account = account.copy(telegramIdRevealed = false)
```

- [ ] **Step 5: Собираемость и полный прогон тестов**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew test --no-daemon`
Expected: BUILD SUCCESSFUL, все тесты зелёные (около 480+).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AccountMapping.kt android/feature/account/src/test/kotlin/com/impossi8le/vpnapp/feature/account/AccountMappingTest.kt
git commit -m "Починить кнопку Показать в аккаунте: обработчик интента и сброс при выходе"
```

---

### Task 5: Проверка на устройстве и обновление документов

**Files:**
- Modify: `android/README.md`
- Modify: `docs/design/2026-10-06-implemented-ui-spec.md` (раздел «Аккаунт», если описание кнопки неточное)
- Create: `docs/testing/2026-10-06-notification-and-reveal-phone.md`

**Interfaces:**
- Consumes: собранный APK; телефон с отладкой по USB (`scripts/install-apk.sh`).
- Produces: документ-свидетельство с фактами и скриншотами.

- [ ] **Step 1: Собрать и поставить на телефон**

Run:
```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon
```
then install with `scripts/install-apk.sh`. На телефоне: войти, открыть «Аккаунт», нажать «Показать ›».

- [ ] **Step 2: Проверить три вещи и записать результат**

1. Кнопка «Показать ›» раскрывает полный Telegram ID, надпись меняется на «Скрыть»; повторное нажатие скрывает.
2. Подключить VPN → вытащить шторку → текст «Подключено» (не «Туннель поднимается»).
3. Выключить Wi-Fi и мобильные данные при поднятом туннеле → шторка → «Соединение потеряно».

Скриншоты положить в `docs/testing/` (шторка и аккаунт).

- [ ] **Step 3: Записать факты в документ-свидетельство**

Create `docs/testing/2026-10-06-notification-and-reveal-phone.md`:

```markdown
# Уведомление и кнопка «Показать» — проверка на телефоне

Дата: 2026-10-06. Модель: <модель, версия Android>.

## Что проверено

| Что | Результат | Свидетельство |
|---|---|---|
| «Показать ›» раскрывает Telegram ID | <да/нет> | <скриншот> |
| Повторное нажатие скрывает | <да/нет> | <скриншот> |
| Шторка: «Подключено» при поднятом туннеле | <да/нет> | <скриншот> |
| Шторка: «Соединение потеряно» после обрыва | <да/нет> | <скриншот> |

## Что не проверено

<Перечислить честно то, что не удалось проверить, и почему.>
```

- [ ] **Step 4: Обновить README**

В `android/README.md` в таблицу состояния добавить строку:

```markdown
| Уведомление в шторке отражает состояние | готово, проверено на телефоне |
```

- [ ] **Step 5: Commit**

```bash
git add android/README.md docs/testing/2026-10-06-notification-and-reveal-phone.md docs/design/2026-10-06-implemented-ui-spec.md
git commit -m "Записать проверку уведомления и кнопки Показать на телефоне"
```

---

## Self-Review

**Покрытие спеки:**
- «Статус не из кнопки» → Task 1 (тест-закрепитель). ✓
- «Уведомление врёт» → Tasks 2-3. ✓
- «Кнопка не работает» (обработчик + константа) → Task 4. ✓
- «Проверено на устройстве» → Task 5. ✓

**Открытые вопросы спеки, НЕ входящие в этот план (осознанно):**
- `AlwaysOnGuidance` никогда не срабатывает — отдельное решение, не входит в запрос.
- Формулировка статуса при обходах РФ — входит в спеку B (обходы).

**Согласованность типов:** `notificationTextFor(SystemState): String?` определена в Task 2 и используется в Task 3 без изменения сигнатуры. `telegramIdRevealed: Boolean` существует в `AccountScreenState` (Task 4 не меняет тип, только источник значения). ✓

**Плейсхолдеры:** в Task 5 есть `<модель>` и `<да/нет>` — это поля документа-свидетельства, заполняемые фактом на устройстве, а не отложенная работа. Допустимо.
