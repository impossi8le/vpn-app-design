// Sources/TestSupport/Fakes.swift
import Foundation
import CoreDomain

/// Один брокер, много подписчиков. Подписка синхронна (замыкание AsyncStream
/// исполняется сразу), поэтому emit после подписки не теряет событие. Новый
/// подписчик первым получает current. Прошлый вариант с ретрансляцией потока
/// через Task терял события — исправлено по итогам прогона на реальном компиляторе.
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
