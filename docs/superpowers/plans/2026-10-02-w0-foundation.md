# W0 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Зафиксировать протоколы (версия `W0-v1`) и тестовые дублёры, которые разблокируют параллельную разработку всех остальных потоков.

**Architecture:** Пакет `CoreDomain` без зависимостей (кроме Foundation) содержит модели и протоколы границ. Пакет `TestSupport` зависит от него и даёт фейки. Пустой `TestSupportMockBackend` объявлен заранее, чтобы W7 не правил пакет W0.

**Tech Stack:** Swift 5.9, Swift Package Manager, XCTest.

## Global Constraints

- Минимальная iOS: **15.0**
- `CoreDomain` не импортирует ничего, кроме **Foundation**. Ни `NetworkExtension`, ни `Security`, ни `UIKit`.
- Зелёное (`.protected`) конструируется **только** через `ProtectionGate.evaluate()`. `ProtectionEvidence` имеет **внутренний** `init` — собрать его вне CoreDomain нельзя.
- `ProtectionVerdict.evaluate(ipv4Bypassed:ipv6Closed:dnsInside:)` при любом `false` возвращает `.failed(.inconclusive)` — ложный зелёный невозможен на входе.
- Фейки: `FakeTunnelControlling` **не** создаёт поток на каждое обращение (гонка) — поток создаётся один раз в `init`.
- `FakeProtectionProbe` по умолчанию возвращает **не** подтверждение (запрет зелёного по умолчанию).
- Версия протоколов: **`W0-v1`**. Ломающие изменения — через письменную поправку к `architecture §5` и бамп версии, не правкой на месте.

---

## Структура файлов

```
Packages/
  CoreDomain/
    Package.swift
    Sources/CoreDomain/
      StatusMapping.swift   — SystemTunnelState, ConnectionStatus, ConnectionStatus.map(system:)
      Protection.swift      — ProtectionEvidence, ProtectionFailure, ProtectionVerdict, ProtectionProbe, ProtectionGate
      Tunnel.swift          — TunnelControlling, TunnelError
      Auth.swift            — AuthLink, AuthOperation, Session, AuthService, SessionStore, KeychainBackend
      Config.swift          — Connection, Profile, StagedProfile, ConfigService, ProfileStore
    Tests/CoreDomainTests/
      StatusMappingTests.swift
      ProtectionGateTests.swift
      ModelDecodingTests.swift
  TestSupport/
    Package.swift
    Sources/TestSupport/
      Fakes.swift
      Fixtures.swift
    Tests/TestSupportTests/
      FakeBehaviourTests.swift
      FixtureDecodingTests.swift
  TestSupportMockBackend/
    Package.swift                              — объявлен заранее, наполняет W7
    Sources/TestSupportMockBackend/Placeholder.swift  — заглушка: пустой таргет не собирается
```

---

### Task 1: Каркас пакетов

**Files:**
- Create: `Packages/CoreDomain/Package.swift`
- Create: `Packages/TestSupportMockBackend/Package.swift`
- Create: `Packages/TestSupportMockBackend/Sources/TestSupportMockBackend/.gitkeep`

**Interfaces:**
- Consumes: ничего
- Produces: пакет `CoreDomain` (модуль `CoreDomain`) — импортируют все потоки; пустой пакет `TestSupportMockBackend` — наполняет W7 без правки W0.

- [ ] **Step 1: Создать `Packages/CoreDomain/Package.swift`**

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

- [ ] **Step 2: Создать `Packages/TestSupportMockBackend/Package.swift`**

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TestSupportMockBackend",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "TestSupportMockBackend", targets: ["TestSupportMockBackend"])
    ],
    dependencies: [
        .package(path: "../CoreDomain")
    ],
    targets: [
        .target(name: "TestSupportMockBackend", dependencies: ["CoreDomain"])
    ]
)
```

- [ ] **Step 3: Проверить сборку**

Run: `cd Packages/CoreDomain && swift build && cd ../TestSupportMockBackend && swift build`
Expected: `Build complete!` дважды

- [ ] **Step 4: Commit**

```bash
git add Packages/CoreDomain/Package.swift Packages/TestSupportMockBackend
git commit -m "chore(w0): scaffold CoreDomain and TestSupportMockBackend packages"
```

---

### Task 2: Статус и инвариант защиты (запечатанный зелёный)

**Files:**
- Create: `Packages/CoreDomain/Sources/CoreDomain/StatusMapping.swift`
- Create: `Packages/CoreDomain/Sources/CoreDomain/Protection.swift`
- Create: `Packages/CoreDomain/Sources/CoreDomain/Tunnel.swift`
- Create: `Packages/CoreDomain/Tests/CoreDomainTests/StatusMappingTests.swift`
- Create: `Packages/CoreDomain/Tests/CoreDomainTests/ProtectionGateTests.swift`

**Interfaces:**
- Consumes: ничего
- Produces:
  - `enum SystemTunnelState` — нейтральный дубль `NEVPNStatus`
  - `enum ConnectionStatus` с `.protected(ProtectionEvidence)`
  - `static func ConnectionStatus.map(system:) -> ConnectionStatus`
  - `struct ProtectionEvidence` (внутренний `init`), `enum ProtectionVerdict`, `static ProtectionVerdict.evaluate(...)`
  - `struct ProtectionGate` с `func evaluate() async -> ConnectionStatus`
  - `protocol ProtectionProbe`, `enum TunnelError`, `protocol TunnelControlling`

- [ ] **Step 1: Написать падающие тесты**

```swift
// Tests/CoreDomainTests/StatusMappingTests.swift
import XCTest
@testable import CoreDomain

final class StatusMappingTests: XCTestCase {

    func testNoSystemStateYieldsProtected() {
        // Инвариант: зелёное не выводится ни из одного системного состояния.
        // Проверяем ВСЕ состояния, а не только .connected.
        let all: [SystemTunnelState] = [.invalid, .disconnected, .connecting,
                                        .connected, .reasserting, .disconnecting]
        for state in all {
            let mapped = ConnectionStatus.map(system: state)
            if case .protected = mapped {
                XCTFail("состояние \(state) дало .protected — ложная защита")
            }
        }
    }

    func testConnectedMapsToVerifyingNotProtected() {
        XCTAssertEqual(ConnectionStatus.map(system: .connected), .verifyingProtection)
    }

    func testDisconnectedStates() {
        for state in [SystemTunnelState.invalid, .disconnected, .disconnecting] {
            XCTAssertEqual(ConnectionStatus.map(system: state), .disconnected)
        }
    }

    func testTransientStates() {
        for state in [SystemTunnelState.connecting, .reasserting] {
            XCTAssertEqual(ConnectionStatus.map(system: state), .connecting)
        }
    }
}
```

```swift
// Tests/CoreDomainTests/ProtectionGateTests.swift
import XCTest
@testable import CoreDomain

private struct StubProbe: ProtectionProbe {
    let verdict: ProtectionVerdict
    func verify() async throws -> ProtectionVerdict { verdict }
}

final class ProtectionGateTests: XCTestCase {

    func testAnyFalseConditionNeverYieldsGreen() {
        // Проверяем, что НИ ОДНА комбинация с false не даёт зелёное.
        for ipv4 in [true, false] {
            for ipv6 in [true, false] {
                for dns in [true, false] {
                    let verdict = ProtectionVerdict.evaluate(
                        ipv4Bypassed: ipv4, ipv6Closed: ipv6, dnsInside: dns)
                    if !(ipv4 && ipv6 && dns) {
                        XCTAssertFalse(verdict.isConfirmed,
                            "комбинация \(ipv4),\(ipv6),\(dns) дала зелёное")
                    }
                }
            }
        }
    }

    func testAllTrueYieldsGreen() {
        let verdict = ProtectionVerdict.evaluate(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true)
        XCTAssertTrue(verdict.isConfirmed)
    }

    func testGateYieldsProtectedOnlyOnConfirmedProbe() async {
        let ok = ProtectionGate(probe: StubProbe(
            verdict: .evaluate(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true)))
        let okStatus = await ok.evaluate()
        if case .protected = okStatus {} else { XCTFail("ожидали .protected") }

        let bad = ProtectionGate(probe: StubProbe(verdict: .failed(.ipv6Leak)))
        let badStatus = await bad.evaluate()
        if case .protectionFailed = badStatus {} else { XCTFail("ожидали .protectionFailed") }
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что падают**

Run: `cd Packages/CoreDomain && swift test`
Expected: FAIL — `cannot find 'ConnectionStatus' in scope`

- [ ] **Step 3: Реализация**

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
    case protected(ProtectionEvidence)
    case protectionFailed(ProtectionVerdict)
    case failed(TunnelError)

    /// ИНВАРИАНТ: ни одно системное состояние не даёт `.protected`.
    /// `.connected` — это только «туннель поднят», не «защита подтверждена».
    public static func map(system: SystemTunnelState) -> ConnectionStatus {
        switch system {
        case .connected:                  return .verifyingProtection
        case .connecting, .reasserting:   return .connecting
        case .invalid, .disconnected,
             .disconnecting:              return .disconnected
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

/// Доказательство защиты. `init` внутренний — вне CoreDomain собрать нельзя.
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

    /// ЕДИНСТВЕННЫЙ вход в зелёное. Любое false → .failed(.inconclusive).
    public static func evaluate(ipv4Bypassed: Bool, ipv6Closed: Bool, dnsInside: Bool) -> ProtectionVerdict {
        guard ipv4Bypassed, ipv6Closed, dnsInside else { return .failed(.inconclusive) }
        return .confirmed(ProtectionEvidence(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true))
    }

    public var isConfirmed: Bool {
        if case .confirmed = self { return true }
        return false
    }
}

public protocol ProtectionProbe {
    func verify() async throws -> ProtectionVerdict
}

/// Единственный владелец перехода в зелёное.
public struct ProtectionGate {
    private let probe: ProtectionProbe
    public init(probe: ProtectionProbe) { self.probe = probe }

    public func evaluate() async -> ConnectionStatus {
        do {
            switch try await probe.verify() {
            case .confirmed(let evidence): return .protected(evidence)
            case .failed:                  return .protectionFailed(.failed(.inconclusive))
            }
        } catch {
            return .protectionFailed(.failed(.probeUnavailable))
        }
    }
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
    /// Для поздно подписавшихся и для возврата из фона.
    var current: ConnectionStatus { get }
    /// Мультиподписчичная лента. Новый подписчик первым получает current.
    func statusStream() -> AsyncStream<ConnectionStatus>
}
// TunnelControlling НЕ эмитит .protected — это делает ProtectionGate.
```

- [ ] **Step 4: Запустить — убедиться, что проходит**

Run: `cd Packages/CoreDomain && swift test`
Expected: PASS (7 тестов)

- [ ] **Step 5: Commit**

```bash
git add Packages/CoreDomain
git commit -m "feat(w0): sealed protection verdict and connection status invariant"
```

---

### Task 3: Модели авторизации и конфигурации

**Files:**
- Create: `Packages/CoreDomain/Sources/CoreDomain/Auth.swift`
- Create: `Packages/CoreDomain/Sources/CoreDomain/Config.swift`
- Create: `Packages/CoreDomain/Tests/CoreDomainTests/ModelDecodingTests.swift`

**Interfaces:**
- Consumes: `ConnectionStatus` (Task 2)
- Produces: `AuthLink`, `AuthOperation`, `Session`, `Connection`, `Profile`, `StagedProfile`, и протоколы `AuthService` (с `deviceNonce`), `SessionStore`, `KeychainBackend`, `ConfigService`, `ProfileStore`

- [ ] **Step 1: Написать падающий тест (реальное декодирование, не сравнение литералов)**

```swift
// Tests/CoreDomainTests/ModelDecodingTests.swift
import XCTest
@testable import CoreDomain

final class ModelDecodingTests: XCTestCase {

    func testConnectionDecodesFromMeJSON() throws {
        let json = """
        {
          "id": "nl-ams-1", "name": "Нидерланды · Амстердам",
          "location": { "country_code": "NL", "city": "Амстердам" },
          "start_date": "2026-09-01T00:00:00Z",
          "end_date": "2026-12-01T00:00:00Z",
          "status": "active"
        }
        """.data(using: .utf8)!
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let connection = try decoder.decode(Connection.self, from: json)
        XCTAssertEqual(connection.id, "nl-ams-1")
        XCTAssertEqual(connection.status, .active)
        XCTAssertEqual(connection.countryCode, "NL")
    }

    func testConnectionStatusDecodesEveryServerValue() throws {
        for (raw, expected) in [("active", Connection.SubscriptionStatus.active),
                                ("expired", .expired),
                                ("revoked", .revoked),
                                ("pending", .pending)] {
            let json = "\"\(raw)\"".data(using: .utf8)!
            XCTAssertEqual(try JSONDecoder().decode(Connection.SubscriptionStatus.self, from: json),
                           expected)
        }
    }
}
```

Этот тест проверяет **декодирование в модель**, а не равенство строк самим себе. Он упал бы при неверном `CodingKeys`.

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `cd Packages/CoreDomain && swift test --filter ModelDecodingTests`
Expected: FAIL — `cannot find 'Connection' in scope`

- [ ] **Step 3: Реализация**

```swift
// Sources/CoreDomain/Auth.swift
import Foundation

public struct AuthLink: Equatable {
    public let publicCode: String
    public let deepLink: URL
    public let expiresAt: Date
    public init(publicCode: String, deepLink: URL, expiresAt: Date) {
        self.publicCode = publicCode; self.deepLink = deepLink; self.expiresAt = expiresAt
    }
}

public struct AuthOperation: Equatable {
    public let publicCode: String
    public let secret: String      // НИКОГДА не уходит в Telegram
    public let createdAt: Date
    public init(publicCode: String, secret: String, createdAt: Date) {
        self.publicCode = publicCode; self.secret = secret; self.createdAt = createdAt
    }
}

public struct Session: Equatable {
    public let token: String
    public let expiresAt: Date
    public let chatID: Int64
    public init(token: String, expiresAt: Date, chatID: Int64) {
        self.token = token; self.expiresAt = expiresAt; self.chatID = chatID
    }
}

public protocol AuthService {
    func requestLink() async throws -> (link: AuthLink, operation: AuthOperation)
    /// СЕКРЕТ + deviceNonce. Без nonce подтверждение не привязано к устройству (login-CSRF).
    func pollSession(operation: AuthOperation, deviceNonce: String) async throws -> Session
    func logout() async throws
}

public protocol SessionStore {
    func save(_ session: Session) throws
    func load() throws -> Session?
    func clear() throws
}

public protocol KeychainBackend {
    func set(_ data: Data, account: String) throws
    func get(account: String) throws -> Data?
    func delete(account: String) throws
}
```

```swift
// Sources/CoreDomain/Config.swift
import Foundation

public struct Connection: Equatable, Identifiable, Decodable {
    public enum SubscriptionStatus: String, Equatable, Decodable {
        case active, expired, revoked, pending
    }
    public let id: String
    public let name: String
    public let countryCode: String
    public let city: String
    public let startDate: Date
    public let endDate: Date
    public let status: SubscriptionStatus

    enum CodingKeys: String, CodingKey {
        case id, name, status
        case countryCode = "country_code"
        case city
        case startDate = "start_date"
        case endDate = "end_date"
    }

    // Сервер отдаёт location как вложенный объект; разворачиваем через отдельный init.
    private struct Location: Decodable { let countryCode: String; let city: String
        enum CodingKeys: String, CodingKey { case countryCode = "country_code"; case city } }
    private enum TopKeys: String, CodingKey { case id, name, location, startDate = "start_date",
                                              endDate = "end_date", status }

    public init(from decoder: Decoder) throws {
        let top = try decoder.container(keyedBy: TopKeys.self)
        id = try top.decode(String.self, forKey: .id)
        name = try top.decode(String.self, forKey: .name)
        let loc = try top.decode(Location.self, forKey: .location)
        countryCode = loc.countryCode
        city = loc.city
        startDate = try top.decode(Date.self, forKey: .startDate)
        endDate = try top.decode(Date.self, forKey: .endDate)
        status = try top.decode(SubscriptionStatus.self, forKey: .status)
    }

    public init(id: String, name: String, countryCode: String, city: String,
                startDate: Date, endDate: Date, status: SubscriptionStatus) {
        self.id = id; self.name = name; self.countryCode = countryCode; self.city = city
        self.startDate = startDate; self.endDate = endDate; self.status = status
    }
}

public struct Profile: Equatable {
    public let raw: Data
    public let version: String?
    public let hash: String?
    public init(raw: Data, version: String? = nil, hash: String? = nil) {
        self.raw = raw; self.version = version; self.hash = hash
    }
}

public struct StagedProfile {
    public let profile: Profile
    public let temporaryURL: URL
    public init(profile: Profile, temporaryURL: URL) {
        self.profile = profile; self.temporaryURL = temporaryURL
    }
}

public protocol ConfigService {
    func fetchConnections() async throws -> [Connection]
    func fetchProfile(id: Connection.ID) async throws -> Profile
}

public protocol ProfileStore {
    func load() throws -> Profile?
    func stage(_ raw: Data) throws -> StagedProfile
    func commit(_ staged: StagedProfile) throws
    func rollback() throws
}
```

- [ ] **Step 4: Запустить — убедиться, что проходит**

Run: `cd Packages/CoreDomain && swift test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add Packages/CoreDomain
git commit -m "feat(w0): auth and config models with real decoding"
```

---

### Task 4: TestSupport с неразрывными фейками

**Files:**
- Create: `Packages/TestSupport/Package.swift`
- Create: `Packages/TestSupport/Sources/TestSupport/Fakes.swift`
- Create: `Packages/TestSupport/Tests/TestSupportTests/FakeBehaviourTests.swift`

**Interfaces:**
- Consumes: протоколы `CoreDomain`
- Produces: `FakeTunnelControlling` (неразрывный, с `current`), `FakeProtectionProbe` (по умолчанию **не** подтверждение), `FakeSessionStore`, `FakeProfileStore`, `FakeConfigService`, `FakeAuthService`

- [ ] **Step 1: Создать `Package.swift`**

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TestSupport",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [ .library(name: "TestSupport", targets: ["TestSupport"]) ],
    dependencies: [ .package(path: "../CoreDomain") ],
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

    func testTunnelEmitsToSubscriberStartingFromCurrent() async {
        let tunnel = FakeTunnelControlling()
        let stream = tunnel.statusStream()          // подписка СИНХРОННА
        let collected = Task { () -> [ConnectionStatus] in
            var out: [ConnectionStatus] = []
            for await s in stream {
                out.append(s)
                if out.count == 3 { break }
            }
            return out
        }
        tunnel.emit(.connecting)                     // после подписки — не теряется
        tunnel.emit(.verifyingProtection)
        let result = await collected.value
        // Первым идёт снимок current (.disconnected), затем оба emit по порядку.
        XCTAssertEqual(result, [.disconnected, .connecting, .verifyingProtection])
    }

    func testTunnelCurrentReflectsLastEmit() {
        let tunnel = FakeTunnelControlling()
        tunnel.emit(.connecting)
        XCTAssertEqual(tunnel.current, .connecting)
    }

    func testProbeDefaultsToNotConfirmed() async throws {
        // Запрет зелёного по умолчанию: фейк не подтверждает защиту без явной настройки.
        let probe = FakeProtectionProbe()
        let verdict = try await probe.verify()
        XCTAssertFalse(verdict.isConfirmed)
    }

    func testProbeReturnsConfiguredVerdict() async throws {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = .failed(.ipv6Leak)
        // XCTAssertEqual использует autoclosure без поддержки concurrency —
        // await обязан стоять отдельной строкой (иначе ошибка компиляции).
        let verdict = try await probe.verify()
        XCTAssertEqual(verdict, .failed(.ipv6Leak))
    }
}
```

- [ ] **Step 3: Запустить — убедиться, что падает**

Run: `cd Packages/TestSupport && swift test`
Expected: FAIL — `cannot find 'FakeTunnelControlling' in scope`

- [ ] **Step 4: Реализация**

```swift
// Sources/TestSupport/Fakes.swift
import Foundation
import CoreDomain

/// Один брокер, много подписчиков. Подписка синхронна (замыкание AsyncStream
/// исполняется сразу), поэтому emit после подписки не теряет событие. Новый
/// подписчик первым получает current. Прошлый вариант с ретрансляцией через
/// Task терял события — исправлено прогоном на реальном компиляторе.
public final class FakeTunnelControlling: TunnelControlling, @unchecked Sendable {
    private let lock = NSLock()
    private var _current: ConnectionStatus = .disconnected
    private var subscribers: [UUID: AsyncStream<ConnectionStatus>.Continuation] = [:]
    public private(set) var connectedProfiles: [Profile] = []
    public var connectError: TunnelError?

    public init() {}

    public var current: ConnectionStatus {
        lock.lock(); defer { lock.unlock() }; return _current
    }

    public func statusStream() -> AsyncStream<ConnectionStatus> {
        let id = UUID()
        return AsyncStream { cont in
            lock.lock()
            subscribers[id] = cont
            let snapshot = _current
            lock.unlock()
            cont.yield(snapshot)                 // новый подписчик сразу видит текущее
            cont.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock(); self.subscribers[id] = nil; self.lock.unlock()
            }
        }
    }

    public func emit(_ status: ConnectionStatus) {
        lock.lock()
        _current = status
        let live = Array(subscribers.values)
        lock.unlock()
        for c in live { c.yield(status) }
    }

    public func connect(profile: Profile) async throws {
        if let error = connectError { throw error }
        connectedProfiles.append(profile)
    }

    public func disconnect() async throws {}
}

public final class FakeProtectionProbe: ProtectionProbe, @unchecked Sendable {
    /// По умолчанию НЕ подтверждено — зелёное только по явной настройке теста.
    public var nextVerdict: ProtectionVerdict = .failed(.inconclusive)
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
        backup = current; current = staged.profile
    }

    public func rollback() throws { if let b = backup { current = b } }
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
    public var link = AuthLink(publicCode: "code",
                               deepLink: URL(string: "https://t.me/bot")!,
                               expiresAt: Date(timeIntervalSince1970: 0))
    public private(set) var lastNonce: String?
    public var session: Session?
    public var pollError: Error?
    public private(set) var logoutCalled = false
    public init() {}

    public func requestLink() async throws -> (link: AuthLink, operation: AuthOperation) {
        (link, AuthOperation(publicCode: link.publicCode, secret: "secret",
                             createdAt: Date(timeIntervalSince1970: 0)))
    }

    public func pollSession(operation: AuthOperation, deviceNonce: String) async throws -> Session {
        lastNonce = deviceNonce
        if let error = pollError { throw error }
        guard let session else { throw TunnelError.notConfigured }
        return session
    }

    public func logout() async throws { logoutCalled = true }
}
```

- [ ] **Step 5: Запустить — убедиться, что проходит**

Run: `cd Packages/TestSupport && swift test`
Expected: PASS (4 теста)

- [ ] **Step 6: Commit**

```bash
git add Packages/TestSupport
git commit -m "feat(w0): race-free fakes with safe-by-default protection probe"
```

---

### Task 5: Фикстуры API-контракта

**Files:**
- Create: `Packages/TestSupport/Sources/TestSupport/Fixtures.swift`
- Create: `Packages/TestSupport/Tests/TestSupportTests/FixtureDecodingTests.swift`

**Interfaces:**
- Consumes: `Connection`, `Connection.SubscriptionStatus`
- Produces: `enum Fixtures` с JSON-строками, совпадающими с `2026-10-02-api-contract.md`

- [ ] **Step 1: Написать падающий тест (декодирование в модели)**

```swift
// Tests/TestSupportTests/FixtureDecodingTests.swift
import XCTest
import CoreDomain
@testable import TestSupport

final class FixtureDecodingTests: XCTestCase {

    private func decoder() -> JSONDecoder {
        let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d
    }

    func testMeFixtureDecodesIntoConnectionsWithOwnStatuses() throws {
        struct Me: Decodable { let chatID: Int64; let configs: [Connection]
            enum CodingKeys: String, CodingKey { case chatID = "chat_id"; case configs } }
        let me = try decoder().decode(Me.self, from: Fixtures.me.data(using: .utf8)!)
        XCTAssertEqual(me.configs.count, 2)
        XCTAssertEqual(me.configs[0].status, .active)
        XCTAssertEqual(me.configs[1].status, .expired)   // статус у каждого свой
    }

    func testEmptyMeIsValidNotError() throws {
        struct Me: Decodable { let configs: [Connection] }
        let me = try decoder().decode(Me.self, from: Fixtures.meEmpty.data(using: .utf8)!)
        XCTAssertTrue(me.configs.isEmpty)
    }

    func testPollConfirmedFixtureDecodes() throws {
        struct Poll: Decodable { let status: String; let sessionToken: String
            enum CodingKeys: String, CodingKey { case status; case sessionToken = "session_token" } }
        let poll = try decoder().decode(Poll.self, from: Fixtures.pollConfirmed.data(using: .utf8)!)
        XCTAssertEqual(poll.status, "confirmed")
        XCTAssertEqual(poll.sessionToken, "token-abc")
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `cd Packages/TestSupport && swift test --filter FixtureDecodingTests`
Expected: FAIL — `cannot find 'Fixtures' in scope`

- [ ] **Step 3: Реализация**

```swift
// Sources/TestSupport/Fixtures.swift
import Foundation

public enum Fixtures {
    public static let me = """
    { "chat_id": 123456789,
      "configs": [
        { "id": "nl-ams-1", "name": "Нидерланды · Амстердам",
          "location": { "country_code": "NL", "city": "Амстердам" },
          "start_date": "2026-09-01T00:00:00Z", "end_date": "2026-12-01T00:00:00Z",
          "status": "active" },
        { "id": "nl-rtm-1", "name": "Нидерланды · Роттердам",
          "location": { "country_code": "NL", "city": "Роттердам" },
          "start_date": "2026-05-01T00:00:00Z", "end_date": "2026-09-01T00:00:00Z",
          "status": "expired" }
      ] }
    """

    public static let meEmpty = """
    { "chat_id": 123456789, "configs": [] }
    """

    public static let authLink = """
    { "public_code": "a7f3c9d2e1b4",
      "deep_link": "https://t.me/example_bot?start=login_a7f3c9d2e1b4",
      "expires_at": "2026-10-02T10:15:00Z" }
    """

    public static let pollPending = """
    { "status": "pending", "retry_after_ms": 2000 }
    """

    public static let pollConfirmed = """
    { "status": "confirmed", "session_token": "token-abc",
      "expires_at": "2026-11-02T10:00:00Z", "chat_id": 123456789 }
    """

    public static let errorNonceMismatch = """
    { "error": { "code": "nonce_mismatch", "message": "Неверный код", "retryable": true } }
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
Expected: PASS (7 тестов)

- [ ] **Step 5: Commit**

```bash
git add Packages/TestSupport
git commit -m "test(w0): API contract fixtures validated by real decoding"
```

---

## Self-Review

**Spec coverage:**
- Протоколы границ (архитектура §5) → Task 3, 4. ✔
- Инвариант защиты, **запечатанный тип** → Task 2: `evaluate()` + внутренний `init` + `ProtectionGate`; тесты проверяют все комбинации false и все системные состояния. ✔
- `deviceNonce` (login-CSRF) → Task 3 (`pollSession(operation:deviceNonce:)`), фикстура `errorNonceMismatch`. ✔
- `TunnelControlling.current` + мультиподписчик → Task 2, Task 4. ✔
- Неразрывный фейк (ревью: гонка) → Task 4, тест `testTunnelEmitsToSubscriber`. ✔
- `FakeProtectionProbe` не зелёный по умолчанию → Task 4, тест `testProbeDefaultsToNotConfirmed`. ✔
- Независимый пакет под мок-бэкенд → Task 1 (`TestSupportMockBackend`). ✔
- Канонические пути `Packages/` → Структура файлов. ✔

**Placeholder scan:** заполнителей нет; весь код приведён целиком.

**Type consistency:** `ConnectionStatus.map(system:)`, `ProtectionVerdict.evaluate(ipv4Bypassed:ipv6Closed:dnsInside:)`, `ProtectionEvidence`, `ProtectionGate.evaluate()`, `pollSession(operation:deviceNonce:)`, `TunnelControlling.current/statusStream()` — совпадают во всех задачах и в архитектуре §5.

**Версия:** протоколы `W0-v1`. Ломающие изменения — письменная поправка + бамп, не правка на месте.

---

## Дальнейшие планы

Покрыт **W0** — единственный поток, обязанный завершиться первым. Остальные планы пишутся в момент старта потока по шаблону `2026-10-02-agent-workstreams.md` §4:

| Поток | План |
|---|---|
| W1 Auth flow | `docs/superpowers/plans/<date>-w1-auth-flow.md` |
| W2 ConfigStore | `docs/superpowers/plans/<date>-w2-configstore.md` |
| W3 Networking | `docs/superpowers/plans/<date>-w3-networking.md` |
| W4 TunnelKit | `docs/superpowers/plans/<date>-w4-tunnelkit.md` |
| W5 UI states | `docs/superpowers/plans/<date>-w5-ui-states.md` |
| W6 Extension | `docs/superpowers/plans/<date>-w6-extension.md` |
| W7 Mock backend | `docs/superpowers/plans/<date>-w7-mock-backend.md` |
| W8 CI | `docs/superpowers/plans/<date>-w8-ci.md` |
| W9 Integration | `docs/superpowers/plans/<date>-w9-integration.md` |
