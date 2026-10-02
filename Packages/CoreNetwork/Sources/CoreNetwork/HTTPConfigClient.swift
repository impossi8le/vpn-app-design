// Sources/CoreNetwork/HTTPConfigClient.swift
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import CoreDomain

/// HTTP-реализация `ConfigService` по контракту §3–§4.
/// Токен берётся лениво через `tokenProvider`, чтобы клиент не кэшировал устаревшую сессию.
public final class HTTPConfigClient: ConfigService {
    private let api: APIClient
    private let tokenProvider: @Sendable () -> String?

    public init(baseURL: URL,
                transport: HTTPTransport,
                tokenProvider: @escaping @Sendable () -> String?) {
        self.api = APIClient(baseURL: baseURL, transport: transport)
        self.tokenProvider = tokenProvider
    }

    public convenience init(baseURL: URL,
                            session: URLSession,
                            tokenProvider: @escaping @Sendable () -> String?) {
        self.init(baseURL: baseURL,
                  transport: URLSessionTransport(session: session),
                  tokenProvider: tokenProvider)
    }

    // MARK: GET /me

    public func fetchConnections() async throws -> [Connection] {
        let request = try authorizedRequest(path: "/me", method: "GET")
        let (data, _) = try await api.send(request)
        // Пустой `configs` — валидный ответ, не ошибка (контракт §3).
        let response = try APIClient.decoder.decode(MeResponse.self, from: data)
        return response.configs
    }

    // MARK: GET /config/{id}

    public func fetchProfile(id: Connection.ID) async throws -> Profile {
        let request = try authorizedRequest(path: "/config/\(id)", method: "GET")
        let (data, response) = try await api.send(request)
        // Тело — сырой .ovpn, не JSON. Версия/хэш доступны из заголовков (контракт §4).
        return Profile(raw: data,
                       version: response.value(forHTTPHeaderField: "X-Config-Version"),
                       hash: response.value(forHTTPHeaderField: "X-Config-Hash"))
    }

    // MARK: helpers

    /// Без токена запрос не уходит в сеть — сразу `unauthorized` (§5).
    private func authorizedRequest(path: String, method: String) throws -> URLRequest {
        guard let token = tokenProvider(), !token.isEmpty else {
            throw APIError(code: .unauthorized, message: "no session token",
                           retryable: false, httpStatus: 401)
        }
        return api.makeRequest(path: path, method: method, token: token)
    }
}

private struct MeResponse: Decodable {
    let chatID: Int64
    let configs: [Connection]
    enum CodingKeys: String, CodingKey {
        case chatID = "chat_id"
        case configs
    }
}
