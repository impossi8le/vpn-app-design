// Sources/CoreNetwork/APIError.swift
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Каждый код ошибки из таблиц контракта §2 и §4. Неизвестный код → `.unknown`,
/// чтобы сырая строка никогда не «протекала» в вызывающий код.
public enum APIErrorCode: String, Equatable, Sendable {
    // §1 /auth/link
    case publicCodeConflict = "public_code_conflict"
    case rateLimited = "rate_limited"
    // §2 /auth/poll
    case invalidSecret = "invalid_secret"
    case nonceMismatch = "nonce_mismatch"
    case operationNotFound = "operation_not_found"
    case alreadyConsumed = "already_consumed"
    // §3 /me
    case unauthorized = "unauthorized"
    case accountBlocked = "account_blocked"
    // §4 /config/{id}
    case configRevoked = "config_revoked"
    case subscriptionExpired = "subscription_expired"
    case configNotFound = "config_not_found"
    case configRetired = "config_retired"
    // Fallback
    case unknown = "unknown"

    /// Коды контракта → типизированный случай. Неизвестное значение — не ошибка парсинга,
    /// а честный `.unknown` (тело могло прийти не от нашего бэкенда).
    public init(rawValue: String) {
        self = APIErrorCode(exactly: rawValue) ?? .unknown
    }

    private init?(exactly raw: String) {
        switch raw {
        case "public_code_conflict": self = .publicCodeConflict
        case "rate_limited":         self = .rateLimited
        case "invalid_secret":       self = .invalidSecret
        case "nonce_mismatch":       self = .nonceMismatch
        case "operation_not_found":  self = .operationNotFound
        case "already_consumed":     self = .alreadyConsumed
        case "unauthorized":         self = .unauthorized
        case "account_blocked":      self = .accountBlocked
        case "config_revoked":       self = .configRevoked
        case "subscription_expired": self = .subscriptionExpired
        case "config_not_found":     self = .configNotFound
        case "config_retired":       self = .configRetired
        default:                     return nil
        }
    }
}

/// Типизированная ошибка API. `message` — для лога (контракт §0), не для UI.
public struct APIError: Error, Equatable, Sendable {
    public let code: APIErrorCode
    public let message: String
    public let retryable: Bool
    public let httpStatus: Int

    public init(code: APIErrorCode, message: String, retryable: Bool, httpStatus: Int) {
        self.code = code
        self.message = message
        self.retryable = retryable
        self.httpStatus = httpStatus
    }
}

/// Разбор тела `{ "error": {code, message, retryable} }`.
/// Никогда не бросает: нераспознанное тело → `.unknown` с сохранением HTTP-статуса.
public enum APIErrorMapper {
    private struct Envelope: Decodable {
        struct Payload: Decodable {
            let code: String
            let message: String
            let retryable: Bool
        }
        let error: Payload
    }

    public static func decodeError(response: HTTPURLResponse, body: Data) -> APIError {
        let status = response.statusCode
        if let envelope = try? JSONDecoder().decode(Envelope.self, from: body) {
            return APIError(code: APIErrorCode(rawValue: envelope.error.code),
                            message: envelope.error.message,
                            retryable: envelope.error.retryable,
                            httpStatus: status)
        }
        // Тело без конверта — маппим по HTTP-статусу, если он однозначен.
        return APIError(code: fallbackCode(forStatus: status),
                        message: "unrecognized error body",
                        retryable: status >= 500 || status == 429,
                        httpStatus: status)
    }

    private static func fallbackCode(forStatus status: Int) -> APIErrorCode {
        switch status {
        case 401: return .unauthorized
        case 402: return .subscriptionExpired
        case 403: return .configRevoked
        case 404: return .configNotFound
        case 410: return .configRetired
        case 429: return .rateLimited
        default:  return .unknown
        }
    }
}
