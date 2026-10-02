import Foundation
import CoreDomain

/// Concrete `SessionStore` that persists a `Session` through an injected
/// `KeychainBackend` (Session -> Data -> backend). The backend is injected so
/// the logic is testable without Apple's Security framework, which does not
/// exist on this build host. The real backend is `SystemKeychainBackend`,
/// compiled only where `Security` is importable.
public final class KeychainSessionStore: SessionStore {
    private let backend: KeychainBackend
    private let account: String

    public init(backend: KeychainBackend, account: String = "com.vpnapp.session") {
        self.backend = backend
        self.account = account
    }

    public func save(_ session: Session) throws {
        try backend.set(RecordCoder.encode(session), account: account)
    }

    public func load() throws -> Session? {
        guard let data = try backend.get(account: account) else { return nil }
        return try RecordCoder.decodeSession(data)
    }

    public func clear() throws {
        try backend.delete(account: account)
    }
}
