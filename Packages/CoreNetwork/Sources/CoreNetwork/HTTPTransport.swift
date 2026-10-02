// Sources/CoreNetwork/HTTPTransport.swift
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Изолирует HTTP за протоколом, чтобы клиенты не зависели от `URLSession`
/// напрямую и тесты подставляли любую реализацию (в т.ч. `URLProtocol`-стаб).
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (data: Data, response: HTTPURLResponse)
}

/// Продакшен-транспорт поверх `URLSession`.
public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) { self.session = session }

    public func send(_ request: URLRequest) async throws -> (data: Data, response: HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError(code: .unknown, message: "non-HTTP response", retryable: true, httpStatus: -1)
        }
        return (data, http)
    }
}
