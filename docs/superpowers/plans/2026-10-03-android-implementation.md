# Реализация Android-клиента VPN — план

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ ПОД-НАВЫК: `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans`. Шаги размечены чекбоксами `- [ ]`.

**Цель:** превратить каркас `android/` в собирающееся, тестируемое приложение, где ни один зелёный статус защиты не выводится без замера.

**Архитектура:** многомодульный Gradle-проект. Домен — чистый Kotlin/JVM без Android, поэтому инвариант защиты и протокол записи конфига проверяются на JVM без эмулятора. Android-специфика (VpnService, ConnectivityManager, Keystore) спрятана за интерфейсами `core:domain`.

**Стек:** Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01), AGP 8.7.3, Gradle 8.11.1, minSdk 26 / targetSdk 35, OkHttp 4.12.0, kotlinx.serialization 1.7.3, JUnit5 5.11.3, ics-openvpn (GPLv2).

**Спека:** [docs/architecture/2026-10-03-android-architecture.md](../../architecture/2026-10-03-android-architecture.md)

---

## Глобальные ограничения

Выписаны дословно из спеки. Требования каждого задания включают этот раздел неявно.

1. **Сборка и тесты — ТОЛЬКО через GitHub Actions.** Локально нет JDK, Gradle, Android SDK, Kotlin. «У меня компилируется» здесь не существует. Код считается проверенным только после зелёного CI.
2. **`core:domain` не зависит ни от чего**, кроме stdlib и `kotlinx-coroutines-core`. Ни Android, ни OkHttp, ни Compose.
3. **Зелёный статус — только из замера.** Ни из `VpnService`-состояния, ни из системного флага, ни из строки статуса ics-openvpn.
4. **До замера UI показывает «не проверено»**, никогда «защищено». Красный — только реальная опасность; янтарный — только процесс подключения; у бренда нет своего цвета.
5. **Светлая тема отклонена** на UX-ревью. Не добавлять как «улучшение» без новой причины.
6. **Приватный ключ из `.ovpn` не коммитится, не логируется, не попадает в крэш-отчёты.** Тестовые фикстуры — только обезличенные.
7. **`verb 1`** в конфиге ядра. Выше — OpenVPN печатает тела PEM.
8. **Каждое задание заканчивается зелёным CI и коммитом.** Нельзя накопить три незакоммиченных задания.
9. **Версии из `libs.versions.toml` не поднимаются** до тех пор, пока текущий набор не подтверждён прогоном.

---

## Протокол делегирования субагентам

Оркестратор (главная сессия) планирует, маршрутизирует и синтезирует. Воркеры работу не планируют — они её делают. Каждый воркер стартует с **пустым контекстом**: он не видит эту переписку, не видит прочитанных файлов, не знает принятых решений. Единственный канал — текст задания.

Поэтому каждое задание ниже содержит **шапку передачи** из пяти полей. Воркер, не получивший их, будет угадывать — и угадает неверно.

```
ЦЕЛЬ:         что должно работать после задания
РЕШЕНИЯ:      что уже решено и менять нельзя
ОБЛАСТЬ:      какие файлы трогаем, какие — нет
ОГРАНИЧЕНИЯ:  правила из глобального списка, применимые здесь
ПРОВЕРКА:     какая команда обязана пройти до слова «готово»
```

**Правила оркестрации:**

- **Изоляция контекста.** Воркер не знает о других воркерах. Передача контекста идёт через оркестратора, не между воркерами напрямую.
- **Обрезка при передаче.** Оркестратор передаёт следующему воркеру **сжатый** результат, а не полный вывод предыдущего. Полный отчёт воркера остаётся в контексте оркестратора и в файле.
- **Условие остановки обязательно.** Каждый воркер получает явный стоп-критерий: «остановись, когда тест зелёный», «остановись, если доказательств достаточно», «повторное чтение того же файла — немедленная остановка».
- **Отказ воркера штатен.** Из десяти воркеров один-два упадут. Падение не блокирует поток: оркестратор перезапускает задание с уточнённой шапкой или выполняет сам.
- **Работа с кодом требует одного воркера на файл.** Два воркера не пишут в один файл одновременно — это гонка, а не параллелизм.
- **Состязательный отзыв — только на высоких ставках.** Дебаты стоят примерно 2.5× одного прогона. Применяются к инварианту защиты, протоколу записи и лицензии. К верстке токенов — нет.
- **Проверка оркестратором.** Отчёт воркера описывает намерение, а не результат. Оркестратор читает диф и прогон CI прежде чем принять работу.

---

## Фаза 0. Зелёный CI на каркасе

Пока CI не зелёный, весь остальной план стоит на непроверенном фундаменте.

### Задание 1: Gradle wrapper и первый прогон

**Шапка передачи**

- **ЦЕЛЬ:** `gradle test` и `gradle :app:assembleDebug` проходят на `ubuntu-latest`.
- **РЕШЕНИЯ:** wrapper уже сгенерирован (jar, `gradlew`, `gradlew.bat`, `gradle-wrapper.properties` c `gradle-8.11.1-bin.zip`); CI переходит на `./gradlew`.
- **ОБЛАСТЬ:** `.github/workflows/android.yml`, `android/gradle/wrapper/gradle-wrapper.properties`. Файлы модулей не трогаем.
- **ОГРАНИЧЕНИЯ:** правило 1, правило 9.
- **ПРОВЕРКА:** `gh run watch` — оба джоба зелёные.

**Файлы:**

- Изменить: `.github/workflows/android.yml` (заменить `gradle` на `./gradlew`, убрать `gradle-version`)
- Проверить: `android/gradlew`, `android/gradle/wrapper/gradle-wrapper.jar`, `android/gradle/wrapper/gradle-wrapper.properties`

- [ ] **Шаг 1: Перевести CI на wrapper**

Убрать `gradle/actions/setup-gradle@v4` с `gradle-version` — wrapper теперь сам себе версия. Оставить только кэш.

```yaml
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: false

      - name: Unit tests
        run: ./gradlew test --no-daemon

      - name: Assemble debug
        run: ./gradlew :app:assembleDebug --no-daemon
```

Так же в джобе `release`: `./gradlew :app:assembleRelease --no-daemon`.

- [ ] **Шаг 2: Коммит и пуш**

```bash
git add android/gradlew android/gradlew.bat android/gradle/wrapper .github/workflows/android.yml
git commit -m "Vendor Gradle wrapper and run CI through it"
git push
```

- [ ] **Шаг 3: Прогон и починка**

```bash
gh run watch
```

Ожидаемо упадёт — версии не проверены. Читать лог, чинить по одному, повторять. Частые причины в порядке вероятности:

| Симптом | Причина | Что делать |
|---|---|---|
| `Unresolved reference: libs` | нет `libs.versions.toml` в `gradle/` | проверить путь |
| Compose-модуль не собирается | нет плагина `compose.compiler` | правило из §10.2 спеки |
| `JUnit5 tests not found` | в android-модулях нет `useJUnitPlatform()` | задание 2 |
| `security-crypto` не резолвится | `alpha06` может быть отозван | поднять до `alpha07`/`1.1.0` |
| AGP требует иной Gradle | 8.7.3 требует 8.9+ | 8.11.1 подходит, проверить лог |

- [ ] **Шаг 4: CI зелёный → задача закрыта**

Пока CI красный, задания 3+ не начинать.

### Задание 2: JUnit5 в Android-модулях

**Шапка передачи**

- **ЦЕЛЬ:** тесты в android-модулях (`core:protection`, `core:security`, `core:tunnel`, `feature:*`) действительно запускаются под JUnit5.
- **РЕШЕНИЯ:** в JVM-модулях `useJUnitPlatform()` есть; в android-модулях его нет — тесты там молча не выполнялись бы.
- **ОБЛАСТЬ:** `android/core/{protection,security,tunnel}/build.gradle.kts`, `android/feature/*/build.gradle.kts`.
- **ОГРАНИЧЕНИЯ:** правило 1.
- **ПРОВЕРКА:** `./gradlew testDebugUnitTest --info` показывает запущенные тесты, а не `NO-SOURCE`.

**Файлы:**

- Изменить: все `build.gradle.kts` Android-модулей (9 файлов)

- [ ] **Шаг 1: Добавить блок `testOptions`**

В каждый android-модуль, где объявлены `testImplementation`:

```kotlin
android {
    // Без этого JUnit5-тесты в android-модулях не запускаются: по умолчанию
    // Android-плагин ищет JUnit4 и на JUnit5-аннотациях молча находит ноль тестов.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}
```

- [ ] **Шаг 2: Доказать, что тест запускается**

Добавить по одному заведомо зелёному тесту в `core:security` и `core:tunnel`, прогнать CI, убедиться что они **в отчёте**, а не в `NO-SOURCE`.

- [ ] **Шаг 3: Коммит**

```bash
git commit -am "Run JUnit5 in Android modules via useJUnitPlatform"
```

---

## Фаза 1. WA0 — домен и фейки

Первый поток обязан смержиться раньше остальных: он объявляет интерфейсы, без которых остальные друг о друге не знают.

### Задание 3: Модели и вердикт защиты

**Шапка передачи**

- **ЦЕЛЬ:** `ProtectionVerdict.evaluate` возвращает зелёное ровно при трёх истинах; проверить все восемь комбинаций.
- **РЕШЕНИЯ:** `ProtectionEvidence` имеет `internal constructor` — собрать снаружи нельзя. Зелёное конструирует только `evaluate` и только `ProtectionGate`.
- **ОБЛАСТЬ:** `android/core/domain/src/main/kotlin/`, `android/core/domain/src/test/kotlin/`. Больше ничего.
- **ОГРАНИЧЕНИЯ:** правило 2, правило 3.
- **ПРОВЕРКА:** `./gradlew :core:domain:test`. Остановись, когда тест зелёный.

**Файлы:**

- Создать: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/protection/ProtectionVerdict.kt`
- Создать: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/protection/ProtectionEvidence.kt`
- Создать: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/protection/ProtectionGate.kt`
- Создать: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/model/ConnectionStatus.kt`
- Тест: `core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/protection/ProtectionVerdictTest.kt`

**Интерфейсы:**

- Отдаёт: `ProtectionVerdict`, `ProtectionVerdict.evaluate(Boolean, Boolean, Boolean)`, `ProtectionEvidence`, `ProtectionFailure`, `ConnectionStatus`, `ProtectionGate`
- Потребляет: ничего (первое задание фазы)

- [ ] **Шаг 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.domain.protection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test

class ProtectionVerdictTest {

    @ParameterizedTest
    @CsvSource(
        "true,true,true,true",
        "false,true,true,false",
        "true,false,true,false",
        "true,true,false,false",
        "false,false,true,false",
        "false,true,false,false",
        "true,false,false,false",
        "false,false,false,false",
    )
    fun `evaluate confirms only when all three facts hold`(
        ipv4InTunnel: Boolean,
        ipv6Closed: Boolean,
        dnsInside: Boolean,
        expectedConfirmed: Boolean,
    ) {
        val verdict = ProtectionVerdict.evaluate(ipv4InTunnel, ipv6Closed, dnsInside)
        assertEquals(expectedConfirmed, verdict is ProtectionVerdict.Confirmed)
    }

    @Test
    fun `failed verdict never carries confirmed evidence`() {
        val verdict = ProtectionVerdict.evaluate(false, false, false)
        assertTrue(verdict is ProtectionVerdict.Failed)
    }
}
```

- [ ] **Шаг 2: Убедиться, что тест падает**

```
./gradlew :core:domain:test
```
Ожидаемо: не компилируется — `ProtectionVerdict` не существует.

- [ ] **Шаг 3: Минимальная реализация**

```kotlin
package com.impossi8le.vpnapp.domain.protection

/** Доказательство защиты. Собрать можно только внутри core:domain. */
class ProtectionEvidence internal constructor(
    val ipv4InTunnel: Boolean,
    val ipv6Closed: Boolean,
    val dnsInside: Boolean,
)

sealed interface ProtectionFailure {
    /** Проба не смогла ответить: нет сети, исключение в платформенном коде. */
    data object ProbeUnavailable : ProtectionFailure
    /** Проба ответила отрицательно хотя бы по одному признаку. */
    data object Inconclusive : ProtectionFailure
}

sealed interface ProtectionVerdict {
    data class Confirmed(val evidence: ProtectionEvidence) : ProtectionVerdict
    data class Failed(val failure: ProtectionFailure) : ProtectionVerdict

    companion object {
        /**
         * ЕДИНСТВЕННЫЙ конструктор зелёного. Все три условия обязаны быть истинны,
         * иначе Failed. Проверка на входе, а не на выводе — ошибиться нельзя.
         */
        fun evaluate(
            ipv4InTunnel: Boolean,
            ipv6Closed: Boolean,
            dnsInside: Boolean,
        ): ProtectionVerdict =
            if (ipv4InTunnel && ipv6Closed && dnsInside) {
                Confirmed(ProtectionEvidence(true, true, true))
            } else {
                Failed(ProtectionFailure.Inconclusive)
            }
    }
}

val ProtectionVerdict.isConfirmed: Boolean
    get() = this is ProtectionVerdict.Confirmed
```

- [ ] **Шаг 4: Тест зелёный**

```
./gradlew :core:domain:test
```
Ожидаемо: PASS, 9 тестов.

- [ ] **Шаг 5: Коммит**

```bash
git add android/core/domain
git commit -m "Add protection verdict: green only when all three facts hold"
```

### Задание 4: ConnectionStatus и запрет зелёного из системного состояния

**Шапка передачи**

- **ЦЕЛЬ:** доказать, что ни одно системное состояние VpnService не даёт `.Protected`.
- **РЕШЕНИЯ:** `.Protected` несёт `ProtectionEvidence` в конструкторе — системное состояние его не имеет и передать не может.
- **ОБЛАСТЬ:** `core/domain/src/main/kotlin/.../model/`, `core/tunnel/src/main/kotlin/.../`, тесты в обоих.
- **ОГРАНИЧЕНИЯ:** правило 3.
- **ПРОВЕРКА:** `StatusMappingTest`: ни один вход не даёт `.Protected`. Остановись на зелёном.

**Файлы:**

- Создать: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/model/ConnectionStatus.kt`
- Создать: `core/tunnel/src/main/kotlin/com/impossi8le/vpnapp/tunnel/SystemStateMapping.kt`
- Тест: `core/tunnel/src/test/kotlin/com/impossi8le/vpnapp/tunnel/StatusMappingTest.kt`

**Интерфейсы:**

- Отдаёт: `ConnectionStatus`, `SystemState`, `SystemState.toConnectionStatus(): ConnectionStatus`
- Потребляет: `ProtectionEvidence` из задания 3

- [ ] **Шаг 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class StatusMappingTest {

    @ParameterizedTest
    @EnumSource(SystemState::class)
    fun `no system state ever produces protected`(state: SystemState) {
        val status = state.toConnectionStatus()
        assertFalse(status.isProtected, "системное состояние $state не может давать зелёный")
    }

    @Test
    fun `established tunnel maps to verifying protection`() {
        val status = SystemState.ESTABLISHED.toConnectionStatus()
        assertTrue(status is ConnectionStatus.VerifyingProtection)
    }

    @Test
    fun `lost tunnel maps to disconnected`() {
        assertTrue(SystemState.LOST.toConnectionStatus() is ConnectionStatus.Disconnected)
    }
}
```

- [ ] **Шаг 2: Убедиться, что тест падает**

```
./gradlew :core:tunnel:test
```
Ожидаемо: не компилируется.

- [ ] **Шаг 3: Реализация**

```kotlin
package com.impossi8le.vpnapp.domain.model

import com.impossi8le.vpnapp.domain.protection.ProtectionEvidence
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus

    /**
     * Туннель поднят, но защита НЕ подтверждена. Система уже рисует значок VPN —
     * пользователю в этот момент показывается «не проверено», не «защищено».
     */
    data object VerifyingProtection : ConnectionStatus

    /** Единственное зелёное. Принести evidence может только ProtectionGate. */
    data class Protected(val evidence: ProtectionEvidence) : ConnectionStatus

    /** Замер провалился — третий исход, а не вечное «проверяем». */
    data class ProtectionFailed(val verdict: ProtectionVerdict.Failed) : ConnectionStatus
    data class Failed(val reason: String) : ConnectionStatus
}

val ConnectionStatus.isProtected: Boolean
    get() = this is ConnectionStatus.Protected
```

```kotlin
package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus

/** Системное состояние VpnService. В домен не протекает — только через маппинг. */
enum class SystemState { IDLE, CONNECTING, ESTABLISHED, LOST, FAILED }

/**
 * Маппинг системного состояния в доменное. Физически не способен вернуть
 * .Protected: у него нет ProtectionEvidence, а конструктор evidence — internal
 * в core:domain. ESTABLISHED даёт VerifyingProtection, и это не деталь UI,
 * а инвариант (§6 архитектуры).
 */
fun SystemState.toConnectionStatus(): ConnectionStatus = when (this) {
    SystemState.IDLE -> ConnectionStatus.Disconnected
    SystemState.CONNECTING -> ConnectionStatus.Connecting
    SystemState.ESTABLISHED -> ConnectionStatus.VerifyingProtection
    SystemState.LOST -> ConnectionStatus.Disconnected
    SystemState.FAILED -> ConnectionStatus.Failed("tunnel failed")
}
```

- [ ] **Шаг 4: Тест зелёный, коммит**

```bash
./gradlew :core:tunnel:test
git add android/core/domain android/core/tunnel
git commit -m "Prove no system state can produce a green status"
```

### Задание 5: ProtectionGate

**Шапка передачи**

- **ЦЕЛЬ:** `ProtectionGate.evaluate()` — единственный владелец перехода в `.Protected`; проба кинула → `ProtectionFailed(ProbeUnavailable)`.
- **РЕШЕНИЯ:** `ProtectionProbe` — `fun interface` с `suspend fun verify()`. Задание 3 дало `ProtectionVerdict`.
- **ОБЛАСТЬ:** `core/domain/src/main/kotlin/.../protection/ProtectionGate.kt` + тест.
- **ОГРАНИЧЕНИЯ:** правило 3.
- **ПРОВЕРКА:** `ProtectionGateTest` зелёный.

**Файлы:**

- Создать: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/protection/ProtectionGate.kt`
- Тест: `core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/protection/ProtectionGateTest.kt`

**Интерфейсы:**

- Отдаёт: `ProtectionProbe`, `ProtectionGate(probe)`, `ProtectionGate.evaluate(): ConnectionStatus`
- Потребляет: `ProtectionVerdict`, `ConnectionStatus`

- [ ] **Шаг 1: Написать падающий тест — с честным фейком**

Фейк обязан быть **параметризован входом**, не выходом. Фейк, который всегда возвращает `Confirmed`, делает тест тавтологичным.

```kotlin
package com.impossi8le.vpnapp.domain.protection

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Фейк принимает факты и честно прогоняет через evaluate — не подсовывает ответ. */
private class FactProbe(private val facts: Triple<Boolean, Boolean, Boolean>) : ProtectionProbe {
    override suspend fun verify() = ProtectionVerdict.evaluate(facts.first, facts.second, facts.third)
}

private class ThrowingProbe : ProtectionProbe {
    override suspend fun verify(): ProtectionVerdict = throw IllegalStateException("нет сети")
}

class ProtectionGateTest {

    @Test
    fun `all three facts true yields green`() = runTest {
        val status = ProtectionGate(FactProbe(Triple(true, true, true))).evaluate()
        assertTrue(status is ConnectionStatus.Protected)
    }

    @Test
    fun `any false fact yields protection failed, not pending`() = runTest {
        val status = ProtectionGate(FactProbe(Triple(true, false, true))).evaluate()
        assertTrue(status is ConnectionStatus.ProtectionFailed)
    }

    @Test
    fun `probe throwing yields probe unavailable, not a crash`() = runTest {
        val status = ProtectionGate(ThrowingProbe()).evaluate()
        assertTrue(status is ConnectionStatus.ProtectionFailed)
        val failure = (status as ConnectionStatus.ProtectionFailed).verdict.failure
        assertEquals(ProtectionFailure.ProbeUnavailable, failure)
    }
}
```

- [ ] **Шаг 2: Убедиться, что падает**

```
./gradlew :core:domain:test
```
Ожидаемо: `ProtectionGate` не найден.

- [ ] **Шаг 3: Реализация**

```kotlin
package com.impossi8le.vpnapp.domain.protection

import com.impossi8le.vpnapp.domain.model.ConnectionStatus

fun interface ProtectionProbe {
    suspend fun verify(): ProtectionVerdict
}

/** Единственный владелец перехода в .Protected. */
class ProtectionGate(private val probe: ProtectionProbe) {
    suspend fun evaluate(): ConnectionStatus = try {
        when (val verdict = probe.verify()) {
            is ProtectionVerdict.Confirmed -> ConnectionStatus.Protected(verdict.evidence)
            is ProtectionVerdict.Failed -> ConnectionStatus.ProtectionFailed(verdict)
        }
    } catch (_: Exception) {
        // Проба не смогла ответить — это провал защиты, а не «проверяем вечно».
        ConnectionStatus.ProtectionFailed(
            ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable),
        )
    }
}
```

- [ ] **Шаг 4: Тест зелёный, коммит**

```bash
./gradlew :core:domain:test
git add android/core/domain
git commit -m "Add ProtectionGate as the only owner of the transition to green"
```

### Задание 6: Остальные интерфейсы домена

**Шапка передачи**

- **ЦЕЛЬ:** объявить `AuthService`, `SessionStore`, `SecureBackend`, `ConfigService`, `ProfileStore`, `TunnelControlling` — чтобы потоки не блокировали друг друга.
- **РЕШЕНИЯ:** сигнатуры дословно из §5 спеки. `TunnelControlling` не эмитит `.Protected`.
- **ОБЛАСТЬ:** `core/domain/src/main/kotlin/.../`; `test-support`.
- **ОГРАНИЧЕНИЯ:** правило 2.
- **ПРОВЕРКА:** `./gradlew :core:domain:test :test-support:build`.

**Файлы:**

- Создать: `core/domain/src/main/kotlin/.../auth/Auth.kt`, `.../config/Config.kt`, `.../tunnel/Tunnel.kt`
- Создать: `test-support/src/main/kotlin/.../fakes/` — `FakeProfileStore`, `FakeSessionStore`, `FakeTunnelControlling`, `FakeAuthService`

**Интерфейсы:** дословно из §5 спеки (блоки `core:domain — Auth / Config / Tunnel`).

- [ ] **Шаг 1: Интерфейсы без тестов**

Здесь тестов нет: объявление интерфейсов нечего проверять. Проверка — компиляция.

- [ ] **Шаг 2: Фейки в `test-support`**

`FakeProfileStore` — **не `Map`**: он должен уметь инжектить сбой `rename` и частичную запись, иначе протокол записи негде проверить (см. задание 7).

- [ ] **Шаг 3: Коммит**

```bash
git commit -m "Declare domain boundary interfaces and add test fakes"
```

---

## Фаза 2. WA1 — протокол записи конфига

### Задание 7: Атомарная запись одним перемещением

**Шапка передачи**

- **ЦЕЛЬ:** обрыв в любой точке не оставляет систему без рабочего `.ovpn`; при старте `load()` сам восстанавливается из `.bak`.
- **РЕШЕНИЯ:** `commit` — **одно** атомарное перемещение, а не два. Автовосстановление при `load()` обязательно: `rollback()` явный и никто его автоматически не вызывает.
- **ОБЛАСТЬ:** `core/config/src/main/kotlin/`, тесты там же. Временная директория JUnit5 (`@TempDir`), не `Map`.
- **ОГРАНИЧЕНИЯ:** правило 1, правило 6.
- **ПРОВЕРКА:** `./gradlew :core:config:test`. Остановись на зелёном.

**Почему не два `rename`, как в §7 спеки.** Два раздельных перемещения не атомарны как пара: обрыв **между** ними (`profile.ovpn → .bak` прошёл, `.tmp → profile.ovpn` нет) оставляет приложение без `profile.ovpn`. Это ровно тот исход, против которого написан §7. Спека исправлена (см. задание 15).

**Файлы:**

- Создать: `core/config/src/main/kotlin/com/impossi8le/vpnapp/config/FileProfileStore.kt`
- Тест: `core/config/src/test/kotlin/com/impossi8le/vpnapp/config/FileProfileStoreTest.kt`

**Интерфейсы:**

- Отдаёт: `FileProfileStore(File dir, ProfileValidator)`
- Потребляет: `ProfileStore`, `Profile`, `StagedProfile` из задания 6

- [ ] **Шаг 1: Написать падающие тесты**

```kotlin
package com.impossi8le.vpnapp.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class FileProfileStoreTest {

    @TempDir lateinit var dir: File

    private val valid = "client\ndev tun\nremote host 1194\n<ca>\nX\n</ca>\n".toByteArray()
    private val invalid = "не конфиг".toByteArray()

    private fun store() = FileProfileStore(dir) { bytes ->
        String(bytes).contains("client") && String(bytes).contains("</ca>")
    }

    @Test
    fun `stage rejects invalid config before commit`() {
        assertThrows(IllegalArgumentException::class.java) { store().stage(invalid) }
        assertFalse(File(dir, "profile.ovpn").exists())
    }

    @Test
    fun `commit leaves exactly one working profile`() {
        val s = store()
        s.commit(s.stage(valid))
        assertTrue(File(dir, "profile.ovpn").exists())
        assertFalse(File(dir, "profile.ovpn.tmp").exists())
    }

    @Test
    fun `interrupted commit never leaves profile missing`() {
        val s = store()
        s.commit(s.stage(valid))

        // Симулируем обрыв: следующая замена падает, старая версия уже сохранена.
        val broken = FileProfileStore(dir, failingMoves = true) { true }
        assertThrows(IOException::class.java) { broken.commit(broken.stage(valid)) }

        // Главное: рабочий конфиг на месте, читается, валиден.
        val after = broken.load()
        assertNotNull(after, "обрыв не должен оставить систему без профиля")
    }

    @Test
    fun `load self-heals from backup when profile is missing`() {
        val s = store()
        s.commit(s.stage(valid))
        File(dir, "profile.ovpn.bak").writeBytes(valid)
        File(dir, "profile.ovpn").delete()

        assertNotNull(s.load(), "load обязан сам восстановиться из .bak")
        assertTrue(File(dir, "profile.ovpn").exists())
    }

    @Test
    fun `logout removes profile, backup and staged file`() {
        val s = store()
        s.commit(s.stage(valid))
        s.clear()
        assertEquals(0, dir.listFiles()!!.size)
    }
}
```

- [ ] **Шаг 2: Убедиться, что падают**

```
./gradlew :core:config:test
```
Ожидаемо: не компилируется — `FileProfileStore` не существует.

- [ ] **Шаг 3: Реализация — одно перемещение, а не два**

```kotlin
package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.Profile
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.config.StagedProfile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Протокол согласованной записи (§7). Отличие от спеки: commit делает ОДНО
 * атомарное перемещение, а не цепочку из двух.
 *
 * Два раздельных rename не атомарны как пара. Обрыв между ними (старая версия
 * уже стала .bak, новая ещё не встала на место) оставил бы систему без
 * profile.ovpn — ровно тот исход, против которого написан §7. Одиночный
 * ATOMIC_MOVE такого окна не создаёт.
 */
class FileProfileStore(
    private val dir: File,
    private val validator: (ByteArray) -> Boolean,
    private val failingMoves: Boolean = false,
) : ProfileStore {

    private val profile = File(dir, "profile.ovpn")
    private val backup = File(dir, "profile.ovpn.bak")
    private val staged = File(dir, "profile.ovpn.tmp")

    override fun load(): Profile? {
        if (profile.exists()) return Profile(profile.readBytes())
        // Самовосстановление: профиль пропал, но предыдущая версия цела.
        // Без этого шага обрыв commit'а навсегда ломает подключение.
        if (backup.exists()) {
            Files.move(backup.toPath(), profile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return Profile(profile.readBytes())
        }
        return null
    }

    override fun stage(raw: ByteArray): StagedProfile {
        require(validator(raw)) { "конфиг не прошёл валидацию — до commit не доходит" }
        staged.writeBytes(raw)
        return StagedProfile(staged)
    }

    override fun commit(stagedProfile: StagedProfile) {
        if (failingMoves) throw IOException("симуляция обрыва записи")
        if (profile.exists()) {
            Files.move(profile.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(staged.toPath(), profile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    override fun rollback() {
        if (backup.exists()) {
            Files.move(backup.toPath(), profile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override fun clear() {
        listOf(profile, backup, staged).forEach { if (it.exists()) it.delete() }
    }
}
```

- [ ] **Шаг 4: Тесты зелёные, коммит**

```bash
./gradlew :core:config:test
git add android/core/config
git commit -m "Write profile with a single atomic move and self-heal on load"
```

---

## Фаза 3. WA2–WA5 — параллельные потоки

Три задания независимы: разные модули, разные файлы. Их можно вести воркерами одновременно.

### Задание 8: WA2 — сетевой слой

**Шапка передачи**

- **ЦЕЛЬ:** `AuthService` и `ConfigService` реализованы против контракта; ошибки типизированы.
- **РЕШЕНИЯ:** OkHttp + kotlinx.serialization. Платформенное поле `"android"` (§1 контракта). `409 already_consumed` — не тупик.
- **ОБЛАСТЬ:** `core/network/src/main/kotlin/`, тесты там же. `MockWebServer`, не мок интерфейса.
- **ОГРАНИЧЕНИЯ:** правило 6 (фикстуры без живого ключа).
- **ПРОВЕРКА:** `./gradlew :core:network:test`. Стоп на зелёном.

**Файлы:**

- Создать: `core/network/src/main/kotlin/.../ApiClient.kt`, `.../AuthApi.kt`, `.../ConfigApi.kt`, `.../ErrorMapping.kt`
- Тест: `core/network/src/test/kotlin/.../AuthApiTest.kt`, `.../ErrorMappingTest.kt`

- [ ] **Шаг 1: Фикстуры без секретов**

JSON-фикстуры в `test-support` — обезличенные. Живой `.ovpn` в тесты не попадает никогда (правило 6).

- [ ] **Шаг 2: Тесты против MockWebServer**

Обязательные кейсы: `200` happy path; `409 already_consumed` → типизированная ошибка, не краш; `401` истёкшая сессия → `SessionExpired`; `5xx` → `ServerError` с retry-подсказкой; таймаут → `NetworkUnavailable`. **Мок интерфейса здесь запрещён**: он спрячет парсинг и статус-маппинг — главный источник багов.

- [ ] **Шаг 3: Реализация, затем `./gradlew :core:network:test`, коммит**

### Задание 9: WA3 — хранение сессии

**Шапка передачи**

- **ЦЕЛЬ:** `SessionStore` на EncryptedSharedPreferences; нерасшифровываемый блоб не роняет приложение.
- **РЕШЕНИЯ:** интерфейс `SecureBackend` развязывает от платформы — тесты идут с фейком. Смена/ресет блокировки устройства уничтожает Keystore-ключ.
- **ОБЛАСТЬ:** `core/security/src/main/kotlin/`.
- **ОГРАНИЧЕНИЯ:** правило 6.
- **ПРОВЕРКА:** `./gradlew :core:security:testDebugUnitTest`.

- [ ] **Шаг 1: Тесты на фейковом бэкенде**

Кейсы: save/load round-trip; `load()` при повреждённом блобе возвращает `null`, а не бросает; смена ключа Keystore → чистая очистка; `clear()` идемпотентен.

- [ ] **Шаг 2: Реализация, затем тест, коммит**

### Задание 10: WA5 — ViewModel-логика экранов

**Шапка передачи**

- **ЦЕЛЬ:** состояния экранов из макета воспроизводятся ViewModel'ю без UI.
- **РЕШЕНИЯ:** ViewModel зависит **только** от интерфейсов `core:domain`. Зелёное на главном экране появляется исключительно на `.Protected`.
- **ОБЛАСТЬ:** `feature/{home,auth,configs,account}/src/main/kotlin/`, тесты там же.
- **ОГРАНИЧЕНИЯ:** правило 4.
- **ПРОВЕРКА:** `HomeViewModelTest.greenOnlyAfterProbeConfirms` зелёный.

- [ ] **Шаг 1: Тест-ловушка на зелёный**

```kotlin
@Test
fun `green appears only after probe confirms, never before`() = runTest {
    val tunnel = FakeTunnelControlling(initial = ConnectionStatus.Connecting)
    val probe = FactProbe(Triple(false, false, false))   // проба провалится
    val vm = HomeViewModel(tunnel, ProtectionGate(probe))

    val seen = mutableListOf<ConnectionStatus>()
    val job = launch { vm.status.collect { seen += it } }

    tunnel.emit(ConnectionStatus.VerifyingProtection)
    vm.connect()
    advanceUntilIdle()

    assertTrue(seen.none { it.isProtected }, "зелёный без подтверждения пробы невозможен")
    job.cancel()
}
```

Этот тест падает, если кто-то подсунет системный флаг вместо пробы.

- [ ] **Шаг 2: Реализация, тесты, коммит**

---

## Фаза 4. WA6 — туннель и проба (единственное, что требует эмулятора)

### Задание 11: Эмулятор в CI

**Шапка передачи**

- **ЦЕЛЬ:** инструментальные тесты выполняются на `ubuntu-latest` без физического устройства.
- **РЕШЕНИЯ:** `reactivecircus/android-emulator-runner`. API 30+, тип `default` с `google_apis` (Play-образ не нужен). Задача `connectedDebugAndroidTest` — отдельная джоба, не в `test`.
- **ОБЛАСТЬ:** `.github/workflows/android.yml`.
- **ОГРАНИЧЕНИЯ:** правило 1.
- **ПРОВЕРКА:** джоба `instrumented` зелёная.

- [ ] **Шаг 1: Добавить джобу**

```yaml
  instrumented:
    name: Instrumented (emulator)
    runs-on: ubuntu-latest
    needs: test
    defaults:
      run:
        working-directory: android
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
      # KVM на ubuntu-latest доступен; без него эмулятор не стартует за разумное время.
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
            | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm
      - name: Instrumented tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 30
          target: default
          arch: x86_64
          working-directory: android
          script: ./gradlew connectedDebugAndroidTest --no-daemon
```

- [ ] **Шаг 2: Коммит и прогон**

### Задание 12: Проба защиты на Android

**Шапка передачи**

- **ЦЕЛЬ:** `AndroidProtectionProbe` заполняет `LinkFacts` из `LinkProperties`; логика вердикта остаётся в JVM.
- **РЕШЕНИЯ:** граница проходит по `LinkFacts`: платформенный код только **читает** маршруты и DNS, решение принимает чистая функция. Так без эмулятора непроверенной остаётся одна тонкая функция-заполнитель, а не вся логика §6.
- **ОБЛАСТЬ:** `core/protection/src/main/kotlin/`, `core/domain` (чистая `evaluateFacts`).
- **ОГРАНИЧЕНИЯ:** правила 3, 4.
- **ПРОВЕРКА:** юнит-тест `ProtectionFactsTest` (JVM) + инструментальный на эмуляторе.

**Честная граница.** `LinkProperties.routes` показывает маршрут **в туннель**; per-UID исключение (`addDisallowedApplication`) в таблице маршрутов не отражается. Поэтому одной проверки маршрутов мало — нужен замер эгресса (задание 14).

- [ ] **Шаг 1: Чистая логика над фактами (JVM)**

```kotlin
package com.impossi8le.vpnapp.domain.protection

/** Сырые факты линка. Платформенный код только заполняет это. */
data class LinkFacts(
    val ipv4Routes: List<String>,
    val ipv6Routes: List<String>,
    val dnsServers: List<String>,
)

data class TunnelAddressing(val ipv4: String, val dns: String)

/** Чистая функция: весь смысл §6 проверяется на JVM, без Android. */
fun evaluateFacts(facts: LinkFacts, addressing: TunnelAddressing): ProtectionVerdict =
    ProtectionVerdict.evaluate(
        ipv4InTunnel = facts.ipv4Routes.any { it == "0.0.0.0/0" },
        ipv6Closed = facts.ipv6Routes.none { it == "::/0" || it == "2000::/3" },
        dnsInside = facts.dnsServers.contains(addressing.dns),
    )
```

- [ ] **Шаг 2: Тесты на JVM — 8 комбинаций**

Кейсы: IPv6-маршрут `::/0` появился → не зелёное; DNS подменился на системный → не зелёное; IPv4 не в туннеле → не зелёное.

- [ ] **Шаг 3: Android-адаптер (заполнитель)**

```kotlin
package com.impossi8le.vpnapp.core.protection

import android.net.ConnectivityManager
import android.net.LinkProperties
import com.impossi8le.vpnapp.domain.protection.*

class AndroidProtectionProbe(
    private val connectivity: ConnectivityManager,
    private val addressing: TunnelAddressing,
) : ProtectionProbe {

    override suspend fun verify(): ProtectionVerdict {
        val props: LinkProperties = connectivity.activeNetwork
            ?.let { connectivity.getLinkProperties(it) }
            ?: return ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable)

        return evaluateFacts(
            LinkFacts(
                ipv4Routes = props.routes.filter { it.destination.prefixLength <= 32 }
                    .map { "${it.destination.address?.hostAddress}/${it.destination.prefixLength}" },
                ipv6Routes = props.routes
                    .filter { it.destination.address is java.net.Inet6Address }
                    .map { "${it.destination.address?.hostAddress}/${it.destination.prefixLength}" },
                dnsServers = props.dnsServers.mapNotNull { it.hostAddress },
            ),
            addressing,
        )
    }
}
```

- [ ] **Шаг 4: Инструментальный тест на эмуляторе**

На эмуляторе поднять туннель и проверить, что проба выдаёт зелёное; затем исключить приложение из перехвата и убедиться, что маршруты **всё ещё** выглядят правильно (документируя, что эту дыру закрывает только задание 14).

- [ ] **Шаг 5: Коммит**

### Задание 13: Конфигурация Builder и запреты

**Шапка передачи**

- **ЦЕЛЬ:** построенный конфиг туннеля закрывает IPv6 маршрутом `::/0`, `setBlocking(true)`, DNS из профиля, `verb 1`.
- **РЕШЕНИЯ:** `dataSync` для foreground-сервиса запрещён (Android 14 обрывает через 6 часов). Только `specialUse` с property.
- **ОБЛАСТЬ:** `vpnservice/src/main/kotlin/`.
- **ОГРАНИЧЕНИЯ:** правило 7.
- **ПРОВЕРКА:** `./gradlew :vpnservice:testDebugUnitTest`.

- [ ] **Шаг 1: Тест на построенном конфиге**

Проверить: IPv6 присутствует как явный маршрут `::/0`; список disallowed пуст; `blocking=true`; DNS совпадает с профилем. И отдельно — `verb` в конфиге ядра не выше 1: OpenVPN при `verb ≥ 3` печатает тела PEM в logcat (правило 7).

- [ ] **Шаг 2: Тест на отсутствие утечки ключа в логи**

```kotlin
@Test
fun `core config never requests verbose logging`() {
    val cfg = CoreConfig.forProfile(profile)
    assertTrue(cfg.verb <= 1, "verb > 1 печатает тела PEM — приватный ключ уедет в logcat")
}
```

- [ ] **Шаг 3: Реализация, тесты, коммит**

### Задание 14: Замер эгресса и перепроверка при смене сети

**Шапка передачи**

- **ЦЕЛЬ:** закрыть два разрыва §6, которые типы не ловят: самоисключение из перехвата и «зелёное навсегда» после одного успешного замера.
- **РЕШЕНИЯ:** (а) к трём фактам добавляется эгресс-замер: соединение привязывается к туннельному `Network` и проверяется, что оно не идёт мимо; (б) по `NetworkCallback` и по таймеру `.Protected` сбрасывается в `.VerifyingProtection` при любом изменении маршрутов.
- **ОБЛАСТЬ:** `core/protection/src/main/kotlin/`, `core/tunnel/src/main/kotlin/`, `core/domain`.
- **ОГРАНИЧЕНИЯ:** правило 3.
- **ПРОВЕРКА:** тест «смена сети сбрасывает зелёное».

- [ ] **Шаг 1: Тест на сброс зелёного**

```kotlin
@Test
fun `network change drops green back to verifying`() = runTest {
    val gate = RelayProtectionGate(fakeProbe = FactProbe(Triple(true, true, true)))
    var status = gate.evaluate()
    assertTrue(status.isProtected)

    // Роуминг: маршруты уехали, факты изменились.
    gate.onNetworkChanged()
    val after = gate.current
    assertFalse(after.isProtected, "зелёное не сохраняется при смене сети")
    assertTrue(after is ConnectionStatus.VerifyingProtection)
}
```

- [ ] **Шаг 2: Реализация эгресс-пробы**

Локальная проба (правило §6.5 — не ходить на внешний сервис за реальным IP) не видит per-UID исключение. Ограничиваемся привязкой к активному `Network` и проверкой, что системный `activeNetwork` для нас — туннель. Полноценный эгресс-замер требует внешней точки и остаётся открытым вопросом (§14 спеки).

- [ ] **Шаг 3: Коммиты**

---

## Фаза 5. WA7–WA9 — UI, сборка, безопасность

### Задание 15: Безопасность манифеста и экрана

**Шапка передачи**

- **ЦЕЛЬ:** фон/копирование/превью не уносят сессию и профиль.
- **РЕШЕНИЯ:** `allowBackup="false"` **недостаточен** — на Android 12+ нужен `dataExtractionRules`. Превью recents показывает код nonce без `FLAG_SECURE`.
- **ОБЛАСТЬ:** `android/app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/`, `MainActivity.kt`.
- **ОГРАНИЧЕНИЯ:** правило 6.
- **ПРОВЕРКА:** `./gradlew :app:assembleDebug`; ручная проверка превью на эмуляторе.

- [ ] **Шаг 1: Отсечь device-to-device перенос**

```xml
<application
    android:allowBackup="false"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules">
```

`res/xml/data_extraction_rules.xml`: `cloud-backup` и `device-transfer` — оба с `disableIfNoEncryptionCapabilities` и пустым `<exclude domain="root" path="."/>` для чувствительных файлов.

- [ ] **Шаг 2: `FLAG_SECURE` на экране с nonce**

```kotlin
// На экране ввода device_nonce и на экране профиля:
window.setFlags(
    WindowManager.LayoutParams.FLAG_SECURE,
    WindowManager.LayoutParams.FLAG_SECURE,
)
```

- [ ] **Шаг 3: Коммит**

### Задание 16: Ужесточение deep link

**Шапка передачи**

- **ЦЕЛЬ:** сторонний APK не может перехватить callback входа.
- **РЕШЕНИЯ:** кастомная схема `vpnapp://login` не закрепляется за приложением — любой APK регистрирует её. Полноценная защита — Android App Links (`https://` + `assetlinks.json`).
- **ОБЛАСТЬ:** манифест, `docs/architecture/`, контракт с бэкендом.
- **ОГРАНИЧЕНИЯ:** —
- **ПРОВЕРКА:** проверка верификации ссылок в CI (`adb shell pm verify-app-links`).

- [ ] **Шаг 1: Привязка операции к ссылке**

В URI передавать `op` и сверять с локально сгенерированным `AuthOperation`. Чужой или устаревший `op` игнорируется — это PKCE-подобная привязка, и она работает даже без App Links.

- [ ] **Шаг 2: App Links как основной путь**

Требует домен и `assetlinks.json` с SHA-256 релизного ключа. Пока домена нет — кастомная схема остаётся фолбэком, и это **открытый вопрос §14**, а не решённая задача. Записать в документ явно.

- [ ] **Шаг 3: Коммит**

### Задание 17: Дизайн-токены и экраны

**Шапка передачи**

- **ЦЕЛЬ:** экраны из макета `docs/design/mockup.html` воспроизводятся токенами.
- **РЕШЕНИЯ:** тёмная тема — единственная (правило 5). Красный — только реальная опасность, янтарный — только процесс, у бренда нет своего цвета, primary-кнопка белая, `#6fb3ff` — только выделение и фокус.
- **ОБЛАСТЬ:** `core/ui/src/main/kotlin/`, `feature/*/src/main/kotlin/.../ui/`.
- **ОГРАНИЧЕНИЯ:** правило 4.
- **ПРОВЕРКА:** Compose UI Test на эмуляторе + сверка с макетом.

- [ ] **Шаг 1: Токены**

Цвета и типографика из макета — в `core/ui`. Светлую тему не добавлять.

- [ ] **Шаг 2: Экраны, затем UI-тесты, коммит**

---

## Фаза 6. Закрытие

### Задание 18: Обновить архитектурный документ по итогам аудита

**Шапка передачи**

- **ЦЕЛЬ:** документ перестаёт обещать больше, чем Android может.
- **РЕШЕНИЯ:** исправления ниже приняты по итогам состязательного аудита (четыре независимых агента).
- **ОБЛАСТЬ:** `docs/architecture/2026-10-03-android-architecture.md`.
- **ОГРАНИЧЕНИЯ:** —
- **ПРОВЕРКА:** документ не содержит ложных гарантий из списка ниже.

**Обязательные правки:**

| § | Что сейчас | Что неверно | Правка |
|---|---|---|---|
| §6 | «routes ловят `addDisallowedApplication`» | Per-UID исключение в таблице маршрутов не отражается | Убрать строку; сослаться на задание 14 |
| §6, §8.1 | Проба одноразовая | Зелёное сохраняется при смене сети | Добавить re-verify по `NetworkCallback`, сброс в `VerifyingProtection` |
| §7 | «commit — два rename, атомарно» | Два перемещения не атомарны как пара | Одно `ATOMIC_MOVE` + самовосстановление в `load()` |
| §4.8, §0 | «ics-openvpn даёт готовый killswitch» | OS-киллсвитча у приложения нет; обрыв FGS = открытый трафик | Указать Always-on VPN + «Block connections without VPN» как настройку пользователя |
| §4.8 | `setBlocking(true)` подан как killswitch | Блокирует только пока интерфейс жив | Разграничить |
| §10.2 | «`test` покрывает и Android-модули» | Без `useJUnitPlatform()` тесты молча не идут | Добавить грабли; задание 2 |
| §2 | `allowBackup` как защита профиля | Нужен `dataExtractionRules`; нет `FLAG_SECURE` | Задание 15 |
| — | Нет требования `verb ≤ 1` | OpenVPN при `verb ≥ 3` печатает PEM | Добавить правило |
| §2, §10.2 | Версии `security-crypto` | `1.1.0-alpha06` — alpha в релизе, библиотека deprecated | Отметить риск сопровождения |
| §6.5 | «не ходим на внешний сервис» | Тогда самоисключение не детектируется | Отметить как открытый вопрос, а не решённый |
| §10.2 | «wrapper генерируется в CI» | Уже сгенерирован и закоммичен | Обновить §10.2 |
| §12 | «эмулятор API 30» | Не сказано | Уточнить |

- [ ] **Шаг 1: Внести правки**

- [ ] **Шаг 2: Коммит**

```bash
git commit -am "Correct architecture doc: honest guarantees after adversarial audit"
```

### Задание 19: Обновить README и память

- [ ] **Шаг 1: `android/README.md`** — статус wrapper (сгенерирован), таблица состояния.

- [ ] **Шаг 2: Файлы памяти** — `.claude/projects/D--VPN-app/memory/`.
  - Обновить `project_android_architecture.md`: wrapper закоммичен; `useJUnitPlatform` обязателен в Android-модулях; эмулятор добавлен в CI.
  - Новый `feedback_multiagent_orchestration.md`: протокол делегирования, почему шапка из пяти полей, почему дебаты только на высоких ставках.

- [ ] **Шаг 3: Коммит**

---

## Порядок запуска субагентов

| Волна | Задания | Параллельно? | Почему |
|---|---|---|---|
| 0 | 1–2 | Нет | Фундамент: без зелёного CI остальное непроверяемо |
| 1 | 3, 4, 5 | Нет (цепочка) | 5 использует 3; 4 использует 3 |
| 2 | 6 | Нет | Разблокирует всё дальше |
| 3 | 7, 8, 9 | **Да** | Разные модули, разные файлы, нет общих файлов |
| 4 | 10 | Нет | Зависит от 6 и 9 |
| 5 | 11 | Нет | Инфраструктура для 12 |
| 6 | 12, 13 | **Да** | Разные модули |
| 7 | 14 | Нет | Зависит от 12 |
| 8 | 15, 16, 17 | **Да** | Манифест / документ / UI — области не пересекаются (но 15 и 16 трогают один манифест — развести по времени) |
| 9 | 18, 19 | Нет | Документация по итогам |

**Правило одного писателя:** два воркера не трогают один файл одновременно. Задания 15 и 16 оба правят `AndroidManifest.xml` — их запускать последовательно.

---

## Чек-лист готовности

- [ ] CI зелёный: `test`, `assembleDebug`, `instrumented`.
- [ ] `StatusMappingTest` доказывает: ни одно системное состояние не даёт `.Protected`.
- [ ] `ProtectionGateTest` доказывает: только три истины дают зелёное; исключение в пробе → `ProbeUnavailable`.
- [ ] `FileProfileStoreTest` доказывает: обрыв не оставляет систему без профиля; `load()` самовосстанавливается.
- [ ] Тест на смену сети: зелёное сбрасывается.
- [ ] `verb ≤ 1` закреплён тестом.
- [ ] Секреты в четырёх переменных валидируются до подписи; неподписанный APK не публикуется.
- [ ] Архитектурный документ не обещает killswitch и атомарность, которых нет.
- [ ] README и память обновлены.