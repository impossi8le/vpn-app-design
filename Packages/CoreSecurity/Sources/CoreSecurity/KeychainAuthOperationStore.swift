import Foundation
import CoreDomain

/// Keychain-backed `AuthOperationStore`. Keeps the in-flight login operation
/// (publicCode + secret + createdAt) available for resume after the app is
/// backgrounded, without ever writing the secret to a plaintext file.
public final class KeychainAuthOperationStore: AuthOperationStore {
    private let backend: KeychainBackend
    private let account: String

    public init(backend: KeychainBackend, account: String = "com.vpnapp.auth.operation") {
        self.backend = backend
        self.account = account
    }

    public func save(_ operation: AuthOperation) throws {
        try backend.set(RecordCoder.encode(operation), account: account)
    }

    public func load() throws -> AuthOperation? {
        guard let data = try backend.get(account: account) else { return nil }
        return try RecordCoder.decodeOperation(data)
    }

    public func clear() throws {
        try backend.delete(account: account)
    }
}
