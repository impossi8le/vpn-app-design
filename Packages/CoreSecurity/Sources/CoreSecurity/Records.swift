import Foundation
import CoreDomain

/// Wire-format DTO for `Session`. `Session` itself is frozen in CoreDomain and
/// is not `Codable`, so persistence goes through this private record.
struct SessionRecord: Codable {
    let token: String
    let expiresAt: Date
    let chatID: Int64

    // snake_case matches the API contract's field spelling.
    enum CodingKeys: String, CodingKey {
        case token
        case expiresAt = "expires_at"
        case chatID = "chat_id"
    }
}

/// Wire-format DTO for `AuthOperation`. The `secret` lives here — it is the
/// value that must never leave the Keychain in cleartext on disk.
struct AuthOperationRecord: Codable {
    let publicCode: String
    let secret: String
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case publicCode = "public_code"
        case secret
        case createdAt = "created_at"
    }
}

/// Errors surfaced by the codec / stores. Deliberately does not embed any
/// payload (never a secret) so it is safe to log.
public enum CoreSecurityError: Error, Equatable {
    case corruptData
}

enum RecordCoder {
    /// Whole-second epoch keeps `Date` bit-exact across encode/decode so a
    /// round-trip compares equal without fractional-second drift.
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()

    static func encode(_ session: Session) throws -> Data {
        try encoder.encode(SessionRecord(token: session.token,
                                         expiresAt: session.expiresAt,
                                         chatID: session.chatID))
    }

    static func decodeSession(_ data: Data) throws -> Session {
        do {
            let r = try decoder.decode(SessionRecord.self, from: data)
            return Session(token: r.token, expiresAt: r.expiresAt, chatID: r.chatID)
        } catch {
            throw CoreSecurityError.corruptData
        }
    }

    static func encode(_ operation: AuthOperation) throws -> Data {
        try encoder.encode(AuthOperationRecord(publicCode: operation.publicCode,
                                               secret: operation.secret,
                                               createdAt: operation.createdAt))
    }

    static func decodeOperation(_ data: Data) throws -> AuthOperation {
        do {
            let r = try decoder.decode(AuthOperationRecord.self, from: data)
            return AuthOperation(publicCode: r.publicCode,
                                 secret: r.secret,
                                 createdAt: r.createdAt)
        } catch {
            throw CoreSecurityError.corruptData
        }
    }
}
