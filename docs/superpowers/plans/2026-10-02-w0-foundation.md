# W0 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Зафиксировать протоколы и тестовые дублёры, которые разблокируют параллельную разработку всех остальных потоков.

**Architecture:** Один Swift Package `CoreDomain` без зависимостей (кроме Foundation) содержит модели и протоколы границ. Пакет `TestSupport` зависит от него и даёт фейки. Все остальные потоки импортируют только `CoreDomain`/`TestSupport`, поэтому W0 обязан быть стабилен до их старта.

**Tech Stack:** Swift 5.9, Swift Package Manager, XCTest.

## Global Constraints

- Минимальная iOS: **15.0**
- `CoreDomain` не импортирует ничего, кроме **Foundation**. Ни одного импорта `NetworkExtension`, `Security`, `UIKit`.
- `ConnectionStatus.protected` конструируется **только** через `ProtectionProbe`. Мэппинг из системного состояния — **никогда** не даёт `.protected`.
- Тесты обязательны на каждый протокол и на инвариант статуса.
- Протоколы замораживаются после мержа W0: изменение — только через блокер, а не тихую правку.

---

## Структура файлов

```
Packages/
  CoreDomain/
    Package.swift
    Sources/CoreDomain/
      Auth.swift            — AuthLink, AuthOperation, Session, AuthService, SessionStore, KeychainBackend
      Config.swift          — Connection, ConnectionStatus, Profile, ConfigService, ProfileStore
      Tunnel.swift          — TunnelControlling, SystemTunnelState, TunnelError
      Protection.swift      — ProtectionVerdict, ProtectionProbe, ProtectionFailure
      StatusMapping.swift   — ConnectionStatus.map(system:)
    Tests/CoreDomainTests/
      StatusMappingTests.swift
      ProtocolConformanceTests.swift
  TestSupport/
    Package.swift
    Sources/TestSupport/
      Fakes.swift           — FakeAuthService, FakeConfigService, FakeProfileStore,
                              FakeSessionStore, FakeTunnelControlling, FakeProtectionProbe
      Fixtures.swift        — JSON-фикстуры ответов из api-contract
    Tests/TestSupportTests/
      FakeBehaviourTests.swift
```

---

### Task 1: Каркас пакета CoreDomain

**Files:**
- Create: `Packages/CoreDomain/Package.swift`
- Create: `Packages/CoreDomain/Sources/CoreDomain/.gitkeep`

**Interfaces:**
- Consumes: ничего
- Produces: пакет `CoreDomain`, модуль `CoreDomain` — его импортируют все потоки.

- [ ] **Step 1: Создать `Package.swift`**

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CoreDomain",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "CoreDomain", targets: ["CoreDomain"])
    ],
    targets: [
        .target(name: "CoreDomain"),
        .testTarget(name: "CoreDomainTests", dependencies: ["CoreDomain"])
    ]
)
```

- [ ] **Step 2: Проверить, что пакет собирается**

Run: `cd Packages/CoreDomain && swift build`
Expected: `Build complete!`

- [ ] **Step 3: Commit**

```bash
git add Packages/CoreDomain/Package.swift
git commit -m "chore(w0): scaffold CoreDomain package"
```

---

### Task 2: Модель статуса и инвариант защиты

**Files:**
- Create: `Packages/CoreDomain/Sources/CoreDomain/StatusMapping.swift`
- Create: `Packages/CoreDomain/Tests/CoreDomainTests/StatusMappingTests.swift`

**Interfaces:**
- Consumes: ничего
- Produces:
  - `enum SystemTunnelState` — нейтральный дубль `NEVPNStatus`, чтобы CoreDomain не тянул NetworkExtension.
  - `enum ConnectionStatus`, `enum ProtectionVerdict`, `enum ProtectionFailure`
  - `static func ConnectionStatus.map(system: SystemTunnelState) -> ConnectionStatus`

- [ ] **Step 1: Написать падающий тест**

```swift
// Tests/CoreDomainTests/StatusMappingTests.swift
import XCTest
@testable import CoreDomain

final class StatusMappingTests: XCTestCase {

    func testConnectedSystemStateNeverYieldsProtected() {
        // Системный флаг "connected" НЕ является доказательством защиты.
        let mapped = ConnectionStatus.map(system: .connected)
        XCTAssertEqual(mapped, .verifyingProtection)
        if case .protected = mapped {
            XCTFail("системный статус не может давать .protected")
        }
    }

    func testDisconnectedStatesMapToDisconnected() {
        for state in [SystemTunnelState.invalid, .disconnected, .disconnecting] {
            XCTAssertEqual(ConnectionStatus.map(system: state), .disconnected)
        }
    }

    func testTransientStatesMapToConnecting() {
        for state in [SystemTunnelState.connecting, .reasserting] {
            XCTAssertEqual(ConnectionStatus.map(system: state), .connecting)
        }
    }

    func testProtectedRequiresVerdict() {
        // Единственный конструктор .protected принимает вердикт пробы.
        let verdict = ProtectionVerdict.confirmed(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true)
        let status = ConnectionStatus.protected(verdict)
        if case .protected(let v) = status {
            XCTAssertEqual(v, verdict)
        } else {
            XCTFail("ожидали .protected")
        }
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться, что падает**

Run: `cd Packages/CoreDomain && swift test --filter StatusMappingTests`
Expected: FAIL — `cannot find 'ConnectionStatus' in scope`

- [ ] **Step 3: Минимальная реализация**

```swift
// Sources/CoreDomain/StatusMapping.swift
import Foundation

/// Нейтральный дубль NEVPNStatus: CoreDomain не импортирует NetworkExtension.
public enum SystemTunnelState: Equatable {
    case invalid
    case disconnected
    case connecting
    case connected
    case reasserting
    case disconnecting
}

public enum ConnectionStatus: Equatable {
    case disconnected
    case connecting
    case verifyingProtection
    case protected(ProtectionVerdict)
    case protectionFailed(ProtectionVerdict)
    case failed(TunnelError)

    /// Мэппинг системного состояния в домен.
    /// ИНВАРИАНТ: `.connected` даёт `.verifyingProtection`, НИКОГДА `.protected`.
    public static func map(system: SystemTunnelState) -> ConnectionStatus {
        switch system {
        case .connected:
            return .verifyingProtection
        case .connecting, .reasserting:
            return .connecting
        case .invalid, .disconnected, .disconnecting:
            return .disconnected
        }
    }
}
```

```swift
// Sources/CoreDomain/Protection.swift
import Foundation

public enum ProtectionFailure: Equatable {
    case ipv4Leak
    case ipv6Leak
    case dnsOutsideTunnel
    case probeUnavailable
    case inconclusive
}

public enum ProtectionVerdict: Equatable {
    case confirmed(ipv4Bypassed: Bool, ipv6Closed: Bool, dnsInside: Bool)
    case failed(ProtectionFailure)

    public var isConfirmed: Bool {
        if case .confirmed = self { return true }
        return false
    }
}

public protocol ProtectionProbe {
    func verify() async throws -> ProtectionVerdict
}
```

```swift
// Sources/CoreDomain/Tunnel.swift
import Foundation

public enum TunnelError: Equatable, Error {
    case permissionDenied
    case configurationInvalid(String)
    case providerFailed(String)
    case notConfigured
}

public protocol TunnelControlling {
    func connect(profile: Profile) async throws
    func disconnect() async throws
    var statusStream: AsyncStream<ConnectionStatus> { get }
}
```

- [ ] **Step 4: Запустить тест — убедиться, что проходит**

Run: `cd Packages/CoreDomain && swift test --filter StatusMappingTests`
Expected: PASS (4 теста)

- [ ] **Step 5: Commit**

```bash
git add Packages/CoreDomain
git commit -m "feat(w0): connection status model with protection invariant"
```

---

### Task 3: Модели авторизации и конфигурации

**Files:**
- Create: `Packages/CoreDomain/Sources/CoreDomain/Auth.swift`
- Create: `Packages/CoreDomain/Sources/CoreDomain/Config.swift`
- Create: `Packages/CoreDomain/Tests/CoreDomainTests/ProtocolConformanceTests.swift`

**Interfaces:**
- Consumes: `Session`, `ConnectionStatus` (Task 2)
- Produces:
  - `AuthLink { publicCode: String; deepLink: URL; expiresAt: Date }`
  - `AuthOperation { publicCode: String; secret: String; createdAt: Date }`
  - `Session { token: String; expiresAt: Date; chatID: Int64 }`
  - `Connection { id: String; name: String; countryCode: String; city: String; startDate: Date; endDate: Date; status: Connection.SubscriptionStatus }`
  - `Profile { raw: Data; version: String?; hash: String? }`
  - протоколы: `AuthService`, `SessionStore`, `KeychainBackend`, `ConfigService`, `ProfileStore`

- [ ] **Step 1: Написать падающий тест**

```swift
// Tests/CoreDomainTests/ProtocolConformanceTests.swift
import XCTest
@testable import CoreDomain

final class ProtocolConformanceTests: XCTestCase {

    func testSessionRoundTripsThroughEquatable() {
        let a = Session(token: "t", expiresAt: Date(timeIntervalSince1970: 0), chatID: 1)
        let b = Session(token: "t", expiresAt: Date(timeIntervalSince1970: 0), chatID: 1)
        XCTAssertEqual(a, b)
    }

    func testConnectionCarriesOwnStatus() {
        // Статус у каждого подключения свой.
        let active = Connection(id: "nl-ams-1", name: "Нидерланды · Амстердам",
                                countryCode: "NL", city: "Амстердам",
                                startDate: Date(timeIntervalSince1970: 0),
                                endDate: Date(timeIntervalSince1970: 100),
                                status: .active)
        let expired = Connection(id: "nl-rtm-1", name: "Нидерланды · Роттердам",
                                 countryCode: "NL", city: "Роттердам",
                                 startDate: Date(timeIntervalSince1970: 0),
                                 endDate: Date(timeIntervalSince1970: 50),
                                 status: .expired)
        XCTAssertNotEqual(active.status, expired.status)
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться, что падает**

Run: `cd Packages/CoreDomain && swift test --filter ProtocolConformanceTests`
Expected: FAIL — `cannot find 'Session' in scope`

- [ ] **Step 3: Минимальная реализация**

```swift
// Sources/CoreDomain/Auth.swift
import Foundation

public struct AuthLink: Equatable {
    public let publicCode: String
    public let deepLink: URL
    public let expiresAt: Date
    public init(publicCode: String, deepLink: URL, expiresAt: Date) {
        self.publicCode = publicCode
        self.deepLink = deepLink
        self.expiresAt = expiresAt
    }
}

public struct AuthOperation: Equatable {
    public let publicCode: String
    public let secret: String      // НИКОГДА не уходит в Telegram
    public let createdAt: Date
    public init(publicCode: String, secret: String, createdAt: Date) {
        self.publicCode = publicCode
        self.secret = secret
        self.createdAt = createdAt
    }
}

public struct Session: Equatable {
    public let token: String
    public let expiresAt: Date
    public let chatID: Int64
    public init(token: String, expiresAt: Date, chatID: Int64) {
        self.token = token
        self.expiresAt = expiresAt
        self.chatID = chatID
    }
}

public protocol AuthService {
    func requestLink() async throws -> (link: AuthLink, operation: AuthOperation)
    func pollSession(operation: AuthOperation) async throws -> Session
    func logout() async throws
}

public protocol SessionStore {
    func save(_ session: Session) throws
    func load() throws -> Session?
    func clear() throws
}

/// Тонкая граница над Keychain — чтобы CoreSecurity тестировался без устройства.
public protocol KeychainBackend {
    func set(_ data: Data, account: String) throws
    func get(account: String) throws -> Data?
    func delete(account: String) throws
}
```

```swift
// Sources/CoreDomain/Config.swift
import Foundation

public struct Connection: Equatable, Identifiable {
    public enum SubscriptionStatus: String, Equatable {
        case active, expired, revoked, pending
    }
    public let id: String
    public let name: String
    public let countryCode: String
    public let city: String
    public let startDate: Date
    public let endDate: Date
    public let status: SubscriptionStatus

    public init(id: String, name: String, countryCode: String, city: String,
                startDate: Date, endDate: Date, status: SubscriptionStatus) {
        self.id = id
        self.name = name
        self.countryCode = countryCode
        self.city = city
        self.startDate = startDate
        self.endDate = endDate
        self.status = status
    }
}

public struct Profile: Equatable {
    public let raw: Data
    public let version: String?
    public let hash: String?
    public init(raw: Data, version: String? = nil, hash: String? = nil) {
        self.raw = raw
        self.version = version
        self.hash = hash
    }
}

public struct StagedProfile {
    public let profile: Profile
    public let temporaryURL: URL
    public init(profile: Profile, temporaryURL: URL) {
        self.profile = profile
        self.temporaryURL = temporaryURL
    }
}

public protocol ConfigService {
    func fetchConnections() async throws -> [Connection]
    func fetchProfile(id: Connection.ID) async throws -> Profile
}

public protocol ProfileStore {
    func load() throws -> Profile?
    /// Пишет во временный файл и валидирует. Невалидный профиль НЕ применяется.
    func stage(_ raw: Data) throws -> StagedProfile
    /// Атомарная замена: старая версия уходит в .bak.
    func commit(_ staged: StagedProfile) throws
    /// Возврат последней рабочей версии.
    func rollback() throws
}
```

- [ ] **Step 4: Запустить тест — убедиться, что проходит**

Run: `cd Packages/CoreDomain && swift test`
Expected: PASS (все тесты, включая Task 2)

- [ ] **Step 5: Commit**

```bash
git add Packages/CoreDomain
git commit -m "feat(w0): auth and config domain models and protocols"
```

---

### Task 4: Пакет TestSupport с фейками

**Files:**
- Create: `Packages/TestSupport/Package.swift`
- Create: `Packages/TestSupport/Sources/TestSupport/Fakes.swift`
- Create: `Packages/TestSupport/Tests/TestSupportTests/FakeBehaviourTests.swift`

**Interfaces:**
- Consumes: все протоколы `CoreDomain` (Task 2–3)
- Produces: фейки, которыми пользуются тесты W1–W9:
  - `FakeTunnelControlling` — с управляемым `emit(_:)`
  - `FakeProtectionProbe` — с задаваемым вердиктом
  - `FakeSessionStore`, `FakeProfileStore`, `FakeConfigService`, `FakeAuthService`

- [ ] **Step 1: Создать `Package.swift`**

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TestSupport",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "TestSupport", targets: ["TestSupport"])
    ],
    dependencies: [
        .package(path: "../CoreDomain")
    ],
    targets: [
        .target(name: "TestSupport", dependencies: ["CoreDomain"]),
        .testTarget(name: "TestSupportTests", dependencies: ["TestSupport", "CoreDomain"])
    ]
)
```

- [ ] **Step 2: Написать падающий тест**

```swift
// Tests/TestSupportTests/FakeBehaviourTests.swift
import XCTest
import CoreDomain
@testable import TestSupport

final class FakeBehaviourTests: XCTestCase {

    func testFakeTunnelEmitsScriptedStatuses() async {
        let tunnel = FakeTunnelControlling()
        let expectation = expectation(description: "получаем два статуса")
        var received: [ConnectionStatus] = []

        let task = Task {
            for await status in tunnel.statusStream {
                received.append(status)
                if received.count == 2 { expectation.fulfill(); break }
            }
        }

        tunnel.emit(.connecting)
        tunnel.emit(.verifyingProtection)
        await fulfillment(of: [expectation], timeout: 1)
        task.cancel()

        XCTAssertEqual(received, [.connecting, .verifyingProtection])
    }

    func testFakeProbeReturnsConfiguredVerdict() async throws {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = .failed(.ipv6Leak)
        let verdict = try await probe.verify()
        XCTAssertEqual(verdict, .failed(.ipv6Leak))
    }
}
```

- [ ] **Step 3: Запустить тест — убедиться, что падает**

Run: `cd Packages/TestSupport && swift test`
Expected: FAIL — `cannot find 'FakeTunnelControlling' in scope`

- [ ] **Step 4: Минимальная реализация**

```swift
// Sources/TestSupport/Fakes.swift
import Foundation
import CoreDomain

public final class FakeTunnelControlling: TunnelControlling {
    private var continuation: AsyncStream<ConnectionStatus>.Continuation?
    public private(set) var connectedProfiles: [Profile] = []
    public var connectError: TunnelError?

    public init() {}

    public var statusStream: AsyncStream<ConnectionStatus> {
        AsyncStream { continuation in
            self.continuation = continuation
        }
    }

    public func emit(_ status: ConnectionStatus) {
        continuation?.yield(status)
    }

    public func connect(profile: Profile) async throws {
        if let error = connectError { throw error }
        connectedProfiles.append(profile)
    }

    public func disconnect() async throws {}
}

public final class FakeProtectionProbe: ProtectionProbe {
    public var nextVerdict: ProtectionVerdict = .confirmed(
        ipv4Bypassed: true, ipv6Closed: true, dnsInside: true)
    public var verifyError: Error?
    public private(set) var callCount = 0

    public init() {}

    public func verify() async throws -> ProtectionVerdict {
        callCount += 1
        if let error = verifyError { throw error }
        return nextVerdict
    }
}

public final class FakeSessionStore: SessionStore {
    public private(set) var session: Session?
    public init() {}
    public func save(_ session: Session) throws { self.session = session }
    public func load() throws -> Session? { session }
    public func clear() throws { session = nil }
}

public final class FakeProfileStore: ProfileStore {
    public private(set) var current: Profile?
    public private(set) var backup: Profile?
    public var stageError: Error?
    public var commitError: Error?

    public init(current: Profile? = nil) { self.current = current }

    public func load() throws -> Profile? { current }

    public func stage(_ raw: Data) throws -> StagedProfile {
        if let error = stageError { throw error }
        return StagedProfile(profile: Profile(raw: raw),
                             temporaryURL: URL(fileURLWithPath: "/tmp/fake.ovpn.tmp"))
    }

    public func commit(_ staged: StagedProfile) throws {
        if let error = commitError { throw error }
        backup = current
        current = staged.profile
    }

    public func rollback() throws {
        if let b = backup { current = b }
    }
}

public final class FakeConfigService: ConfigService {
    public var connections: [Connection] = []
    public var profileData: Data = Data()
    public var fetchError: Error?

    public init() {}

    public func fetchConnections() async throws -> [Connection] {
        if let error = fetchError { throw error }
        return connections
    }

    public func fetchProfile(id: Connection.ID) async throws -> Profile {
        if let error = fetchError { throw error }
        return Profile(raw: profileData)
    }
}

public final class FakeAuthService: AuthService {
    public var link = AuthLink(publicCode: "code", deepLink: URL(string: "https://t.me/bot")!,
                               expiresAt: Date(timeIntervalSince1970: 0))
    public var operation: AuthOperation?
    public var session: Session?
    public var pollError: Error?
    public private(set) var logoutCalled = false

    public init() {}

    public func requestLink() async throws -> (link: AuthLink, operation: AuthOperation) {
        let op = AuthOperation(publicCode: link.publicCode, secret: "secret",
                               createdAt: Date(timeIntervalSince1970: 0))
        operation = op
        return (link, op)
    }

    public func pollSession(operation: AuthOperation) async throws -> Session {
        if let error = pollError { throw error }
        guard let session else { throw TunnelError.notConfigured }
        return session
    }

    public func logout() async throws { logoutCalled = true }
}
```

- [ ] **Step 5: Запустить тест — убедиться, что проходит**

Run: `cd Packages/TestSupport && swift test`
Expected: PASS (2 теста)

- [ ] **Step 6: Commit**

```bash
git add Packages/TestSupport
git commit -m "feat(w0): TestSupport package with domain fakes"
```

---

### Task 5: Фикстуры API-контракта

**Files:**
- Create: `Packages/TestSupport/Sources/TestSupport/Fixtures.swift`
- Modify: `Packages/TestSupport/Tests/TestSupportTests/FakeBehaviourTests.swift` (добавить тест)

**Interfaces:**
- Consumes: `Connection.SubscriptionStatus`
- Produces: `enum Fixtures` с JSON-строками ответов `/me`, `/auth/link`, `/auth/poll`, `/config` для тестов W3 и W7. Совпадают с `2026-10-02-api-contract.md`.

- [ ] **Step 1: Написать падающий тест**

```swift
// добавить в FakeBehaviourTests.swift
func testMeFixtureDecodesPerConnectionStatus() throws {
    let data = Fixtures.me.data(using: .utf8)!
    let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
    let configs = json["configs"] as! [[String: Any]]
    XCTAssertEqual(configs.count, 2)
    XCTAssertEqual(configs[0]["status"] as? String, "active")
    XCTAssertEqual(configs[1]["status"] as? String, "expired")
    // поле-призыв к покупке отсутствует намеренно
    XCTAssertNil(configs[0]["purchase_url"])
}
```

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `cd Packages/TestSupport && swift test --filter testMeFixtureDecodesPerConnectionStatus`
Expected: FAIL — `cannot find 'Fixtures' in scope`

- [ ] **Step 3: Реализация**

```swift
// Sources/TestSupport/Fixtures.swift
import Foundation

public enum Fixtures {
    public static let me = """
    {
      "chat_id": 123456789,
      "configs": [
        {
          "id": "nl-ams-1",
          "name": "Нидерланды · Амстердам",
          "location": { "country_code": "NL", "city": "Амстердам" },
          "start_date": "2026-09-01T00:00:00Z",
          "end_date": "2026-12-01T00:00:00Z",
          "status": "active"
        },
        {
          "id": "nl-rtm-1",
          "name": "Нидерланды · Роттердам",
          "location": { "country_code": "NL", "city": "Роттердам" },
          "start_date": "2026-05-01T00:00:00Z",
          "end_date": "2026-09-01T00:00:00Z",
          "status": "expired"
        }
      ]
    }
    """

    public static let meEmpty = """
    { "chat_id": 123456789, "configs": [] }
    """

    public static let authLink = """
    {
      "public_code": "a7f3c9d2e1b4",
      "deep_link": "https://t.me/example_bot?start=login_a7f3c9d2e1b4",
      "expires_at": "2026-10-02T10:15:00Z"
    }
    """

    public static let pollPending = """
    { "status": "pending", "retry_after_ms": 2000 }
    """

    public static let pollConfirmed = """
    {
      "status": "confirmed",
      "session_token": "token-abc",
      "expires_at": "2026-11-02T10:00:00Z",
      "chat_id": 123456789
    }
    """

    public static let errorExpired = """
    { "error": { "code": "subscription_expired", "message": "Подписка истекла", "retryable": false } }
    """

    public static let errorRevoked = """
    { "error": { "code": "config_revoked", "message": "Доступ отозван", "retryable": false } }
    """
}
```

- [ ] **Step 4: Запустить — убедиться, что проходит**

Run: `cd Packages/TestSupport && swift test`
Expected: PASS (все тесты)

- [ ] **Step 5: Commit**

```bash
git add Packages/TestSupport
git commit -m "test(w0): API contract fixtures matching the frozen backend contract"
```

---

## Self-Review

**Spec coverage:**
- Протоколы границ из архитектуры §5 → Task 3, Task 4. ✔
- Инвариант «зелёное только после пробы» → Task 2, покрыт `StatusMappingTests`. ✔
- Модели `Connection`/`Profile`/`StagedProfile` для §7 (атомарная запись) → Task 3. ✔
- Фикстуры контракта для W3/W7 → Task 5. ✔
- Протокол §1–4 API-контракта → Task 5. ✔

**Placeholder scan:** заполнителей нет; весь код приведён целиком.

**Type consistency:** `ProfileStore.stage/commit/rollback`, `StagedProfile(profile:temporaryURL:)`, `ConnectionStatus.map(system:)`, `SystemTunnelState` — одинаковы в Task 2–4. `ProtectionVerdict.confirmed(ipv4Bypassed:ipv6Closed:dnsInside:)` совпадает в Task 2 и фикстурах.

**Известное расхождение с архитектурой:** §5 архитектуры показывает `AuthService.requestLink() -> AuthLink`, а план отдаёт `(link:operation:)` — так приложению нужен `AuthOperation` (с секретом) для последующего `pollSession`. Здесь план точнее архитектурного наброска; при мерже обновить §5 архитектуры под эту сигнатуру.

---

## Дальнейшие планы

Этот план покрывает **W0** — единственный поток, который обязан завершиться первым. Для остальных потоков план пишется по тому же шаблону в момент старта, на основе `docs/architecture/2026-10-02-agent-workstreams.md` §2 и §4:

| Поток | План |
|---|---|
| W1 Auth | `docs/superpowers/plans/<date>-w1-auth.md` |
| W2 ConfigStore | `docs/superpowers/plans/<date>-w2-configstore.md` |
| W3 Networking | `docs/superpowers/plans/<date>-w3-networking.md` |
| W4 TunnelKit | `docs/superpowers/plans/<date>-w4-tunnelkit.md` |
| W5 UI states | `docs/superpowers/plans/<date>-w5-ui-states.md` |
| W6 Extension | `docs/superpowers/plans/<date>-w6-extension.md` |
| W7 Mock backend | `docs/superpowers/plans/<date>-w7-mock-backend.md` |
| W8 CI | `docs/superpowers/plans/<date>-w8-ci.md` |
| W9 Integration | `docs/superpowers/plans/<date>-w9-integration.md` |
