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

/// Результат одного опроса. Спецификация W0-v1 ошибочно сводила это к `Session`:
/// тогда «ещё не подтверждено» (нормальное состояние, пока пользователь в боте)
/// было неотличимо от терминальной ошибки. Поправка W0-v2.
public enum PollOutcome: Equatable {
    case pending(retryAfterMilliseconds: Int?)
    case confirmed(Session)
    case expired
    case denied
    case attemptLimitExceeded
}

public protocol AuthService {
    func requestLink() async throws -> (link: AuthLink, operation: AuthOperation)
    /// СЕКРЕТ + deviceNonce. Без nonce подтверждение не привязано к устройству (login-CSRF).
    /// Возвращает исход опроса: pending — не ошибка, надо продолжать опрашивать.
    func pollSession(operation: AuthOperation, deviceNonce: String) async throws -> PollOutcome
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
