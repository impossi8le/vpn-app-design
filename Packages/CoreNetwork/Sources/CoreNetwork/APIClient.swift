// Sources/CoreNetwork/APIClient.swift
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Общая механика запросов: склейка пути, JSON-кодирование, разбор ошибок.
/// Не бросает на не-2xx — конвертирует в `APIError` по таблицам контракта.
struct APIClient {
    let baseURL: URL
    let transport: HTTPTransport

    init(baseURL: URL, transport: HTTPTransport) {
        self.baseURL = baseURL
        self.transport = transport
    }

    func makeRequest(path: String, method: String,
                     body: Data? = nil, token: String? = nil) -> URLRequest {
        let url = baseURL.appendingPathComponent(path)
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    /// Отправляет запрос, возвращает (data, response) на 2xx, иначе бросает `APIError`.
    @discardableResult
    func send(_ request: URLRequest) async throws -> (data: Data, response: HTTPURLResponse) {
        let (data, response) = try await transport.send(request)
        guard (200..<300).contains(response.statusCode) else {
            throw APIErrorMapper.decodeError(response: response, body: data)
        }
        return (data, response)
    }

    static let encoder = JSONEncoder()
    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()
}
