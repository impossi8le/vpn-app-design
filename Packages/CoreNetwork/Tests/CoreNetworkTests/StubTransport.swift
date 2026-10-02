// Tests/CoreNetworkTests/StubTransport.swift
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// URLProtocol-заглушка: тесты подсовывают её в URLSession через `config.protocolClasses`.
/// Записывает метод, путь, заголовки и тело каждого запроса — чтобы утверждать,
/// что клиент реально шлёт `device_nonce`, `Authorization` и корректные пути.
final class StubURLProtocol: URLProtocol {
    struct Recorded {
        let method: String
        let url: URL
        let headers: [String: String]
        let body: Data?
        let bodyJSON: [String: Any]?
    }

    final class Box: @unchecked Sendable {
        var handler: ((URLRequest) -> (HTTPURLResponse, Data))!
        private(set) var recorded: [Recorded] = []
        private let lock = NSLock()
        func append(_ r: Recorded) { lock.lock(); recorded.append(r); lock.unlock() }
        func clear() { lock.lock(); recorded = []; lock.unlock() }
    }

    static let box = Box()

    static func reset(handler: @escaping (URLRequest) -> (HTTPURLResponse, Data)) {
        box.handler = handler
        box.clear()
    }

    static var lastRequest: Recorded? { box.recorded.last }
    static var recordedRequests: [Recorded] { box.recorded }

    private static func capture(_ request: URLRequest) -> Recorded {
        var headers: [String: String] = [:]
        for (k, v) in request.allHTTPHeaderFields ?? [:] { headers[k.lowercased()] = v }
        var body = request.httpBody
        if body == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buf = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                data.append(buf, count: n)
            }
            stream.close()
            if !data.isEmpty { body = data }
        }
        let json = body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        return Recorded(method: request.httpMethod ?? "GET",
                        url: request.url!,
                        headers: headers,
                        body: body,
                        bodyJSON: json)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let requested = StubURLProtocol.capture(request)
        StubURLProtocol.box.append(requested)
        let (response, data) = StubURLProtocol.box.handler(request)
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// Собирает URLSession, замкнутую на стаб, и удобные конструкторы ответов.
enum TestTransport {
    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }

    static let baseURL = URL(string: "https://api.example.test/api/v1")!

    static func json(_ raw: String, status: Int, headers: [String: String] = [:]) -> (HTTPURLResponse, Data) {
        let url = URL(string: "https://api.example.test")!
        let resp = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        return (resp, Data(raw.utf8))
    }

    static func ok(_ raw: String) -> (HTTPURLResponse, Data) { json(raw, status: 200) }

    static func error(_ code: String, status: Int, message: String = "log only", retryable: Bool = false) -> (HTTPURLResponse, Data) {
        let raw = """
        { "error": { "code": "\(code)", "message": "\(message)", "retryable": \(retryable) } }
        """
        return json(raw, status: status)
    }
}
