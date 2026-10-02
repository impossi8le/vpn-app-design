// Sources/CoreNetwork/HTTPAuthClient.swift
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import CoreDomain

/// HTTP-реализация `AuthService` по контракту §1–§2.
public final class HTTPAuthClient: AuthService {
    private let api: APIClient
    private let platform: String
    private let appVersion: String
    private let deviceName: String

    /// Сессия хранится только в памяти этого процесса; наружу не логируется.
    public private(set) var sessionToken: String?

    public init(baseURL: URL,
                transport: HTTPTransport,
                platform: String = "ios",
                appVersion: String,
                deviceName: String) {
        self.api = APIClient(baseURL: baseURL, transport: transport)
        self.platform = platform
        self.appVersion = appVersion
        self.deviceName = deviceName
    }

    /// Продакшен-удобство: транспорт поверх `URLSession`.
    public convenience init(baseURL: URL,
                            session: URLSession,
                            platform: String = "ios",
                            appVersion: String,
                            deviceName: String) {
        self.init(baseURL: baseURL,
                  transport: URLSessionTransport(session: session),
                  platform: platform,
                  appVersion: appVersion,
                  deviceName: deviceName)
    }

    // MARK: POST /auth/link

    public func requestLink() async throws -> (link: AuthLink, operation: AuthOperation) {
        // Секрет генерируется локально и НИКОГДА не уходит в открытом виде: только sha256.
        let secret = Self.generateSecret()
        let publicCode = Self.generatePublicCode()
        let payload = LinkRequest(publicCode: publicCode,
                                  secretHash: "sha256:" + SHA256.hash(secret),
                                  deviceName: deviceName,
                                  platform: platform,
                                  appVersion: appVersion)
        let request = api.makeRequest(path: "/auth/link", method: "POST",
                                      body: try APIClient.encoder.encode(payload))
        let (data, _) = try await api.send(request)
        let response = try await decode(LinkResponse.self, from: data)

        let link = AuthLink(publicCode: response.publicCode,
                            deepLink: response.deepLink,
                            expiresAt: response.expiresAt)
        // Операция хранит код, который приложение сгенерировало и отправило;
        // сервер по контракту §1 возвращает тот же код.
        let operation = AuthOperation(publicCode: publicCode,
                                      secret: secret,
                                      createdAt: Date())
        return (link, operation)
    }

    // MARK: POST /auth/poll

    /// Возвращает исход опроса напрямую (W0-v2): `pending` — не ошибка, а нормальное
    /// состояние, пока пользователь подтверждает вход в боте.
    public func pollSession(operation: AuthOperation, deviceNonce: String) async throws -> PollOutcome {
        let payload = PollRequest(publicCode: operation.publicCode,
                                  secret: operation.secret,
                                  deviceNonce: deviceNonce)
        let request = api.makeRequest(path: "/auth/poll", method: "POST",
                                      body: try APIClient.encoder.encode(payload))
        let (data, _) = try await api.send(request)
        let response = try await decode(PollResponse.self, from: data)
        let outcome = response.outcome()
        if case .confirmed(let session) = outcome {
            sessionToken = session.token
        }
        return outcome
    }

    // MARK: POST /auth/logout

    public func logout() async throws {
        // Токен не логируем; достаточно очистить локальную сессию (контракт §5).
        sessionToken = nil
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) async throws -> T {
        try APIClient.decoder.decode(T.self, from: data)
    }

    static func generateSecret() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        for i in 0..<bytes.count { bytes[i] = UInt8.random(in: 0...255) }
        return "s3cr3t-" + SHA256.hex(bytes)
    }

    static func generatePublicCode() -> String {
        var bytes = [UInt8](repeating: 0, count: 6)
        for i in 0..<bytes.count { bytes[i] = UInt8.random(in: 0...255) }
        return SHA256.hex(bytes)
    }
}

// MARK: - Wire types (private)

private struct LinkRequest: Encodable {
    let publicCode: String
    let secretHash: String
    let deviceName: String
    let platform: String
    let appVersion: String
    enum CodingKeys: String, CodingKey {
        case publicCode = "public_code"
        case secretHash = "secret_hash"
        case deviceName = "device_name"
        case platform
        case appVersion = "app_version"
    }
}

private struct LinkResponse: Decodable {
    let publicCode: String
    let deepLink: URL
    let expiresAt: Date
    enum CodingKeys: String, CodingKey {
        case publicCode = "public_code"
        case deepLink = "deep_link"
        case expiresAt = "expires_at"
    }
}

private struct PollRequest: Encodable {
    let publicCode: String
    let secret: String
    let deviceNonce: String
    enum CodingKeys: String, CodingKey {
        case publicCode = "public_code"
        case secret
        case deviceNonce = "device_nonce"
    }
}

private struct PollResponse: Decodable {
    let status: String
    let retryAfterMilliseconds: Int?
    let sessionToken: String?
    let expiresAt: Date?
    let chatID: Int64?

    enum CodingKeys: String, CodingKey {
        case status
        case retryAfterMilliseconds = "retry_after_ms"
        case sessionToken = "session_token"
        case expiresAt = "expires_at"
        case chatID = "chat_id"
    }

    func outcome() -> CoreDomain.PollOutcome {
        switch status {
        case "confirmed":
            return .confirmed(Session(token: sessionToken ?? "",
                                      expiresAt: expiresAt ?? Date.distantPast,
                                      chatID: chatID ?? 0))
        case "pending":   return .pending(retryAfterMilliseconds: retryAfterMilliseconds)
        case "expired":   return .expired
        case "denied":    return .denied
        case "attempt_limit_exceeded": return .attemptLimitExceeded
        default:          return .expired
        }
    }
}

