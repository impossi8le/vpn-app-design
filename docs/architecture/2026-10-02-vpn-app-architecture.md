# Архитектура: iOS VPN-клиент (SwiftUI, test-first)

Дата: 2026-10-02
Статус: на ревью
Область: iOS-клиент целиком. Бэкенд — вне области, но контракт API зафиксирован в `2026-10-02-api-contract.md`.
Базовый документ: `docs/superpowers/specs/2026-09-30-vpn-app-design.md`
Макет: `docs/design/mockup.html`

## 1. Принципы

1. **Тестируемость через границы, а не через моки фреймворков.** Каждая зависимость от системы (Keychain, NetworkExtension, сеть, файлы) скрыта за протоколом в `CoreDomain`. Тест подставляет fake — реальное устройство не нужно.
2. **Граф зависимостей направлен вниз.** Модуль импортирует только то, что ниже него. `CoreDomain` не импортирует ничего, кроме Foundation.
3. **Никакой логики туннеля в UI-процессе.** Расширение — единственное место, где живёт VPN.
4. **Никаких обещаний защиты без замера.** Зелёный статус выводится только из подтверждения трафика через туннель, не из `NEVPNStatus`. Это инвариант, а не деталь UI (см. §6).
5. **Атомарность записи конфига.** Битый `.ovpn` в общем контейнере ломает подключение до ручного вмешательства.

## 2. Целевая конфигурация

| Параметр | Значение |
|---|---|
| Язык / UI | Swift 5.9+, SwiftUI |
| Минимальная iOS | 15.0 |
| Сборка | Swift Package Manager, локальные пакеты |
| VPN-движок | TunnelKit на зафиксированном коммите |
| Расширение | `NEPacketTunnelProvider` |
| Обмен с расширением | App Group + файловый протокол согласованной записи |
| Хранилище секретов | Keychain (сессия), App Group (конфиг) |
| Тесты | XCTest + XCUITest |

## 3. Граф модулей

```
┌─────────────────────────────┐
│  VpnApp (app target)        │
│  корень, сборка DI          │
└───────────┬─────────────────┘
            │
   ┌────────┼────────┬──────────────┐
   ▼        ▼        ▼              ▼
┌────────┐┌────────┐┌────────────┐┌────────────┐
│Feature ││Feature ││ Feature    ││ Feature    │
│  Home  ││  Auth  ││  Configs   ││  Account   │
└───┬────┘└───┬────┘└─────┬──────┘└─────┬──────┘
    └─────────┴───────────┴─────────────┘
              │
      ┌───────┴────────┐
      ▼                ▼
┌──────────────┐ ┌──────────────────┐
│   CoreUI     │ │   CoreDomain     │ ← 0 зависимостей
│ токены, база │ │ модели + протоколы│
└──────────────┘ └────────┬─────────┘
                           │ (всё зависит вниз)
   ┌──────────┬────────────┼────────────┬─────────────────┐
   ▼          ▼            ▼            ▼                 ▼
┌─────────┐┌──────────┐┌────────────┐┌──────────────┐┌──────────────┐
│ Core    ││ Core     ││ Core       ││ TunnelKit    ││ Core         │
│ Network ││ Config   ││ Security   ││ Adapter      ││ Observability│
│ API-кли.││ Store    ││ Keychain   ││ обёртка над  ││ логи (без    │
│         ││ .ovpn    ││ сессия     ││ TunnelKit    ││ секретов)    │
└─────────┘└──────────┘└────────────┘└──────────────┘└──────────────┘
                                                    ▲
                              ┌─────────────────────┴─────────────┐
                              │ VpnTunnelExtension (app extension) │
                              │ NEPacketTunnelProvider             │
                              └───────────────────────────────────┘

TestSupport (fixtures + fakes) ── импортируется только тест-таргетами
```

**Правило:** стрелка = «импортирует». Ни один модуль не импортирует то, что выше него. `CoreDomain` не импортирует ничего, кроме Foundation — именно поэтому весь домен тестируется без симулятора устройства и без сети.

## 4. Модули и их ответственность

Каждый модуль отвечает на три вопроса: что делает, как используется, от чего зависит.

### 4.1 CoreDomain (без зависимостей)

**Что:** модели предметной области и протоколы границ. Ни байта логики ввода-вывода.
**Как:** импортируется всеми.
**Модели:** `AuthLink`, `AuthOperation`, `Session`, `Connection`, `ConnectionStatus`, `Profile` (`.ovpn`), `ProtectionVerdict`.
**Протоколы:** см. §5.

`ConnectionStatus` — это **наша** модель, не `NEVPNStatus`. Системный статус отображается в неё через явный маппинг в адаптере, а не протекает в домен.

### 4.2 CoreNetwork

**Что:** клиент API по контракту из `2026-10-02-api-contract.md`. Собирает URLRequest, разбирает ответ, маппит ошибки в типизированные.
**Как:** `CoreNetwork` реализует `AuthService` и `ConfigService` из `CoreDomain`.
**Зависит:** `CoreDomain`.
**Тестируется:** без сети — через `URLProtocol`-заглушку; контрактные фикстуры JSON лежат в `TestSupport`.

### 4.3 CoreConfig

**Что:** хранение `.ovpn` в App Group по протоколу согласованной записи (§7).
**Как:** реализует `ProfileStore`.
**Зависит:** `CoreDomain`.
**Тестируется:** на временной директории — temp+rename, откат, обрыв на середине записи.

### 4.4 CoreSecurity

**Что:** Keychain-хранилище сессии, генерация секрета операции входа, очистка при выходе.
**Как:** реализует `SessionStore`.
**Зависит:** `CoreDomain`.
**Тестируется:** с fake-обёрткой над Keychain (протокол `KeychainBackend`).

### 4.5 TunnelKitAdapter и TunnelManager (разделены)

Раньше это был один модуль; ревью показало, что так нельзя: расширение не создаёт `NETunnelProviderManager`, а среда NetworkExtension недоступна на симуляторе.

- **`TunnelKitCore`** — чистое ядро: парсит `.ovpn`, строит конфигурацию туннеля, предъявляет пакеты. Линкуется **и в приложение, и в расширение**. Не импортирует `NetworkExtension` вообще. Тестируется без устройства (байты → конфигурация).
- **`TunnelManager`** — только app-side: `NETunnelProviderManager`, `saveToPreferences`, `startVPNTunnel`, наблюдение `NEVPNStatusDidChange`. Линкуется **только в приложение**. Реализует `TunnelControlling`.
- **`VpnTunnelExtension`** — `NEPacketTunnelProvider`, использует `TunnelKitCore` напрямую, менеджер не создаёт.

Отображение `NEVPNStatus → SystemTunnelState → ConnectionStatus.map(system:)` живёт в `TunnelManager`, а не в ядре.

**Тестируется:**
- Парсинг реальных продакшен-`.ovpn` — без устройства.
- Конфигурация (DNS, IPv6, маршруты, kill switch) — юнит-тестами на построенном объекте.
- Build/link проверка: архивный TunnelKit собирается и линкуется в NE-таргет (см. W4) — это и есть реальный риск архивации.
- Реальное поднятие туннеля — только на устройстве; симулятор туннели не поднимает (см. W9).

### 4.6 CoreObservability

**Что:** структурированные логи без приватных ключей, токенов и адресов. Явный allow-list полей.
**Зависит:** `CoreDomain`.

### 4.7 CoreUI

**Что:** токены дизайна, базовые компоненты, типографика. Только представление, без домена.
**Зависит:** ничего (кроме SwiftUI).

### 4.8 Feature-модули

`FeatureHome`, `FeatureAuth`, `FeatureConfigs`, `FeatureAccount` — по одному на группу экранов из макета. Каждый содержит `ObservableObject`-модель представления и SwiftUI-представления. Модель представления зависит **только** от протоколов `CoreDomain`; это делает состояния экранов тестируемыми без UI.

### 4.9 VpnApp

Корень приложения: собирает зависимости (composition root), владеет `ScenePhase`-наблюдением, конфигурирует `NETunnelProviderManager`.

### 4.10 VpnTunnelExtension

`NEPacketTunnelProvider`. Читает профиль из App Group, поднимает туннель через `TunnelKitCore`. Логики парсинга не имеет — она в ядре, которое линкуется и в расширение. `NETunnelProviderManager` не создаёт: это делает только `TunnelManager` в приложении.

**App Group и энтайтлменты (определить на W6):** один идентификатор App Group, включённый в оба таргета (`com.apple.security.application-groups`); Network Extension (Packet Tunnel) включён в оба; bundle ID расширения — с префиксом ID приложения.

## 5. Протоколы границ (контракт между агентами)

Это ключевой артефакт: интерфейсы пишутся **до** реализаций, чтобы потоки разработки не блокировали друг друга.

```swift
// CoreDomain/Auth — версия W0-v2
public enum PollOutcome: Equatable {
    case pending(retryAfterMilliseconds: Int?)   // не ошибка: пользователь ещё в боте
    case confirmed(Session)
    case expired
    case denied
    case attemptLimitExceeded
}

public protocol AuthService {
    func requestLink() async throws -> (link: AuthLink, operation: AuthOperation)
    /// Предъявляет СЕКРЕТ (сгенерирован приложением) И deviceNonce (ввёл пользователь,
    /// прочитав в боте). Без nonce подтверждение не привязано к этому устройству — login-CSRF.
    func pollSession(operation: AuthOperation, deviceNonce: String) async throws -> PollOutcome
    func logout() async throws
}
// operation несёт secret. secret НИКОГДА не проходит через Telegram.
// deviceNonce приходит в чат бота и НЕ возвращается в /auth/link — см. api-contract §2.1.
//
// ПОПРАВКА W0-v2 (2026-10-03): прежде `pollSession` возвращал `Session`, из-за чего
// «ещё не подтверждено» было неотличимо от терминальной ошибки, и цикл входа не мог
// ждать подтверждения. Теперь возвращается исход опроса. Поднято интеграцией W1↔W3.
```

public protocol SessionStore {
    func save(_ session: Session) throws
    func load() throws -> Session?
    func clear() throws
}

public protocol KeychainBackend {                        // для тестов
    func set(_ data: Data, account: String) throws
    func get(account: String) throws -> Data?
    func delete(account: String) throws
}
```

```swift
// CoreDomain/Config
public protocol ConfigService {
    func fetchConnections() async throws -> [Connection]  // /me
    func fetchProfile(id: Connection.ID) async throws -> Data   // /config/{id}, сырой .ovpn
}

public protocol ProfileStore {
    func load() throws -> Profile?
    func stage(_ raw: Data) throws -> StagedProfile        // во временный файл + валидация
    func commit(_ staged: StagedProfile) throws            // атомарная замена, старая -> backup
    func rollback() throws                                 // вернуть последнюю рабочую
}
```

```swift
// CoreDomain/Tunnel
public protocol TunnelControlling {
    func connect(profile: Profile) async throws
    func disconnect() async throws
    /// Текущее состояние — для поздно подписавшихся и для возврата из фона.
    /// Без него подписчик, пришедший после .connecting, теряет контекст.
    var current: ConnectionStatus { get }
    /// Мультиподписчичная лента. Новый подписчик получает current первым событием.
    func statusStream() -> AsyncStream<ConnectionStatus>
}

// ВАЖНО: TunnelControlling НЕ эмитит .protected. Он эмитит .connecting /
// .verifyingProtection / .disconnected / .failed. Зелёное добавляет ProtectionGate.
```

```swift
// CoreDomain/Protection
public protocol ProtectionProbe {
    func verify() async throws -> ProtectionVerdict
}

/// Доказательство защиты. `init` внутренний — конструируется только внутри CoreDomain.
/// Внешние модули не могут собрать evidence руками.
public struct ProtectionEvidence: Equatable {
    public let ipv4Bypassed: Bool
    public let ipv6Closed: Bool
    public let dnsInside: Bool
    init(ipv4Bypassed: Bool, ipv6Closed: Bool, dnsInside: Bool) {
        self.ipv4Bypassed = ipv4Bypassed
        self.ipv6Closed = ipv6Closed
        self.dnsInside = dnsInside
    }
}

public enum ProtectionVerdict: Equatable {
    case confirmed(ProtectionEvidence)
    case failed(ProtectionFailure)

    /// ЕДИНСТВЕННЫЙ конструктор зелёного. Все три условия обязаны быть истинны,
    /// иначе возвращается .failed(.inconclusive). Проверка на входе, а не на выводе.
    public static func evaluate(ipv4Bypassed: Bool, ipv6Closed: Bool, dnsInside: Bool) -> ProtectionVerdict {
        guard ipv4Bypassed, ipv6Closed, dnsInside else { return .failed(.inconclusive) }
        return .confirmed(ProtectionEvidence(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true))
    }

    public var isConfirmed: Bool {
        if case .confirmed = self { return true }
        return false
    }
}

/// Единственный владелец перехода в .protected. Никакой другой код не конструирует зелёное.
public struct ProtectionGate {
    private let probe: ProtectionProbe
    public init(probe: ProtectionProbe) { self.probe = probe }

    public func evaluate() async -> ConnectionStatus {
        do {
            let verdict = try await probe.verify()
            if case .confirmed(let evidence) = verdict {
                return .protected(evidence)
            }
            return .protectionFailed(verdict)
        } catch {
            return .protectionFailed(.failed(.probeUnavailable))
        }
    }
}

// ВАЖНО: case .confirmed принимает ProtectionEvidence с внутренним init, а не bool-ы.
// Поэтому .confirmed невозможно собрать из чужого модуля — только через .evaluate(...).
```

**Инвариант домена (честная формулировка).** Из `NEVPNStatus.connected` зелёное получить нельзя: `map(system:)` возвращает `.verifyingProtection`. Зелёное конструируется только через `ProtectionGate.evaluate()` (владелец перехода) и только при `ProtectionEvidence`, все три поля которого истинны. `ProtectionEvidence` имеет внутренний `init`, поэтому собрать его из чужого модуля нельзя.

Что это НЕ гарантирует: честность самой пробы. Если `ProtectionProbe` реализован с ошибкой и всегда возвращает подтверждение, тип этого не поймает. Это проверяется тестом пробы на реальном устройстве (W9), а не типом. Раньше формулировка «тип не даёт сконструировать» была неверной — исправлено по итогам ревью.

## 6. Критический инвариант: защита подтверждается замером

Из `feedback_vpn_false_security`: ложная уверенность в защите хуже отсутствия защиты.

Обязательное поведение:

1. `NEVPNStatus.connected` маппится в `.verifyingProtection`, **никогда** в `.protected`.
2. `.protected` выставляется только после `ProtectionProbe.verify()`.
3. До замера UI показывает «не проверено», а не «защищено».
4. Провал замера — это отдельный третий исход (`protectionFailed`), а не вечное «проверяем» и не ложный зелёный.
5. Замер **не** ходит на внешний сервис за реальным IP: внешний сервис сам видит реальный адрес. Проверка строится на локально наблюдаемых признаках (какой интерфейс держит маршрут по умолчанию, закрыт ли IPv6, уходит ли DNS в туннель).

Это правило — не пожелание. Оно закреплено типами настолько, насколько это возможно: (а) `map(system:)` физически не возвращает `.protected`; (б) единственный вход в зелёное — `ProtectionGate.evaluate()`; (в) `ProtectionEvidence` нельзя собрать вне CoreDomain; (г) `ProtectionVerdict.evaluate(ipv4Bypassed:ipv6Closed:dnsInside:)` при любом `false` возвращает `.failed(.inconclusive)` — зелёный при сломанном замере невозможен.

Тесты: `StatusMappingTests` (нет `.protected` ни из одного системного состояния), `ProtectionGateTests` (все три `false` → не зелёное; успешная проба → `.protected`).

**Кто владеет переходом:** `ProtectionGate` вызывается один раз в потоке подключения — после того как туннель поднят (`.verifyingProtection`), до показа статуса пользователю. `TunnelControlling` не эмитит `.protected` сам; он эмитит `.connecting`/`.verifyingProtection`/`.disconnected`, а результат пробы добавляет вызывающая сторона (адаптер подключения в `VpnApp`).

## 7. Протокол согласованной записи конфига

Один битый `.ovpn` в общем App Group ломает подключение до ручного вмешательства. Схема:

```
1. stage(raw):     запись в profile.ovpn.tmp → валидация парсером → StagedProfile
2. commit(staged): profile.ovpn → profile.ovpn.bak (rename)
                   profile.ovpn.tmp → profile.ovpn (rename, атомарно)
3. rollback():     profile.ovpn.bak → profile.ovpn
```

Требования:
- Валидация до применения. Невалидный конфиг не доходит до `commit`.
- rename в пределах одной файловой системы — атомарен.
- Предыдущая рабочая версия сохраняется для откатa.
- Запись из UI-процесса и чтение из расширения разведены по времени: расширение читает только `profile.ovpn`, никогда `.tmp`.
- При выходе из аккаунта конфиг и `.bak` удаляются.

## 8. Поток данных: подключение

```
Пользователь: «Подключить»
  → FeatureHome (ViewModel) → TunnelControlling.connect(profile)
     → TunnelKitAdapter: NETunnelProviderManager.loadFromPreferences
        → saveToPreferences → startVPNTunnel
  → statusStream: .connecting
  → [расширение поднимает туннель через TunnelKit]
  → statusStream: .verifyingProtection        ← НЕ .protected
  → ProtectionProbe.verify()
       ├─ confirmed → statusStream: .protected(verdict)
       └─ failed    → statusStream: .protectionFailed(verdict)
  → FeatureHome рендерит ЗЕЛЁНЫЙ только на .protected
```

## 9. Поток данных: вход

```
FeatureAuth → AuthService.requestLink()
  → { publicCode, deepLink: t.me/<bot>?start=login_<publicCode> }
  → приложение: openURL(deepLink)
  → [бот показывает пользователю device_nonce, пользователь жмёт «Подтвердить»]
  → пользователь вводит device_nonce в приложение   ← ЗАМЫКАЕТ вход на это устройство
  → AuthService.pollSession(operation, deviceNonce)  // СЕКРЕТ + nonce, не publicCode
      → при возврате из фона (scenePhase) опрос возобновляется
  → Session → SessionStore.save() → Keychain
```

Два независимых разделения:

1. **Код против секрета.** `publicCode` виден в ссылке и чате; секрет генерируется приложением, через Telegram не проходит.
2. **nonce против ссылки.** `device_nonce` бот показывает только в чате инициировавшей стороны, в приложение из `/auth/link` он не возвращается. Без него злоумышленник, подсунувший жертве свой deep link, получил бы сессию её аккаунта (login-CSRF). Детали — `2026-10-02-api-contract.md` §2.1.

Опрос не идёт непрерывно: iOS усыпляет приложение в фоне. Незавершённая операция персистится, проверка возобновляется при возврате в foreground.

## 10. Стратегия тестов

Тесты — основа, код — следствие. Каждый поток начинается с падающего теста.

| Слой | Что проверяет | Инструмент | Устройство |
|---|---|---|---|
| Юнит | Домен, ViewModel, парсер `.ovpn`, store, маппинг ошибок | XCTest + fakes | нет |
| Интеграция | Вход, истечение, отзыв — через mock-бэкенд | XCTest + URLProtocol | нет |
| Контракт | Реальные `.ovpn`, конфигурация туннеля (DNS/IPv6/kill switch) | XCTest | симулятор |
| UI | 21 состояние из макета | XCUITest + фикстуры | симулятор |
| Устройство | Реальное поднятие туннеля, смена сети, сон, перезагрузка | Ручной чеклист + XCUITest | да |

**Обязательные тест-кейсы по рискам** (из спеки §8, §11):

- Поворот `NEVPNStatus.connected` **не** даёт `.protected`.
- Обрыв туннеля переводит в `.disconnected`, а не оставляет кнопку «Отключить» на том, что не подключено.
- IPv6-маршрут закрыт: тест на построенной конфигурации.
- Битая запись `.ovpn`: `stage` отклоняет, `rollback` возвращает рабочую версию.
- Выход из аккаунта: конфиг и сессия удалены, следующая сессия не подхватывает профиль предыдущего.
- Обрыв на середине `commit`: файл `.ovpn` остаётся валидным (старый или новый), никогда не полузаписанным.
- Возврат из фона во время входа: операция возобновляется.
- Отказ в системном разрешении VPN: не тупик, есть инструкция (системный диалог показывается один раз).
- Отсутствие Telegram на устройстве.
- Один конфиг истёк — остальные работают, экран не блокируется.

## 11. Что сознательно не делается

- Нет абстракции «профиль подключения» поверх `.ovpn` — преждевременно.
- Нет своего OpenVPN-движка.
- Нет микросервисов, нет эксплуатационного контура (мониторинг, RPO/RTO) — для ~1000 пользователей переразмер.
- Нет живой пробы реального IP через внешний сервис (см. §6, п.5).
- Нет поддержки iOS < 15.

## 12. Открытые вопросы (перенесены из дизайн-спеки)

1. Серверная сверка с БД при коннекте — влияет только на отзыв, клиента не блокирует.
2. Лимит устройств — один профиль на пользователя или на устройство. Влияет на контракт `/me`.
3. Поведение при истечении подписки: что видит пользователь, когда профиль перестаёт обновляться. Контракт должен покрыть `status: expired`.
