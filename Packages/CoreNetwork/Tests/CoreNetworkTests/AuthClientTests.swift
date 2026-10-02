// Tests/CoreNetworkTests/AuthClientTests.swift
import XCTest
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import CoreDomain
import TestSupport
@testable import CoreNetwork

final class AuthClientTests: XCTestCase {

    private func makeClient(token: String? = nil) -> HTTPAuthClient {
        HTTPAuthClient(baseURL: TestTransport.baseURL,
                       session: TestTransport.session(),
                       platform: "ios",
                       appVersion: "1.0.0",
                       deviceName: "iPhone 15")
    }

    // MARK: /auth/link

    func testRequestLink_decodesAuthLink_andSendsContractBody() async throws {
        StubURLProtocol.reset { req in
            XCTAssertEqual(req.httpMethod, "POST")
            XCTAssertEqual(req.url?.path, "/api/v1/auth/link")
            return TestTransport.json(Fixtures.authLink, status: 201)
        }
        let client = makeClient()

        let (link, operation) = try await client.requestLink()

        // Реальное декодирование в модель, не сравнение строк.
        XCTAssertEqual(link.publicCode, "a7f3c9d2e1b4")
        XCTAssertEqual(link.deepLink.absoluteString, "https://t.me/example_bot?start=login_a7f3c9d2e1b4")
        XCTAssertEqual(link.expiresAt, ISO8601DateFormatter().date(from: "2026-10-02T10:15:00Z"))

        // Операция несёт public_code, сгенерированный приложением (он же ушёл в теле),
        // и свежий секрет, который никогда не покидает устройство открытым.
        XCTAssertEqual(operation.publicCode.count, 12)
        XCTAssertFalse(operation.secret.isEmpty)

        // Тело запроса — ровно ключи контракта §1.
        // public_code генерирует приложение: он должен совпасть с тем, что попало в операцию,
        // а не с захардкоженным кодом фикстуры.
        let req = try XCTUnwrap(StubURLProtocol.lastRequest)
        let body = try XCTUnwrap(req.bodyJSON)
        let sentCode = try XCTUnwrap(body["public_code"] as? String)
        XCTAssertEqual(sentCode, operation.publicCode)
        XCTAssertEqual(body["platform"] as? String, "ios")
        XCTAssertEqual(body["app_version"] as? String, "1.0.0")
        XCTAssertEqual(body["device_name"] as? String, "iPhone 15")
        // Секрет уходит только в виде хэша, «secret» никогда не отправляется на /auth/link.
        let secretHash = try XCTUnwrap(body["secret_hash"] as? String)
        XCTAssertTrue(secretHash.hasPrefix("sha256:"))
        XCTAssertNil(body["secret"])
        XCTAssertFalse(secretHash.contains(operation.secret))
        XCTAssertEqual(req.headers["content-type"], "application/json")
    }

    // MARK: /auth/poll

    private func operation() -> AuthOperation {
        AuthOperation(publicCode: "a7f3c9d2e1b4", secret: "s3cr3t-xyz",
                      createdAt: Date(timeIntervalSince1970: 0))
    }

    func testPoll_confirmed_decodesSession_andSendsSecretAndNonce() async throws {
        StubURLProtocol.reset { req in
            XCTAssertEqual(req.httpMethod, "POST")
            XCTAssertEqual(req.url?.path, "/api/v1/auth/poll")
            return TestTransport.ok(Fixtures.pollConfirmed)
        }
        let client = makeClient()

        // pollSession (protocol-метод) отдаёт исход опроса (W0-v2).
        let outcome = try await client.pollSession(operation: operation(), deviceNonce: "4821")

        guard case .confirmed(let session) = outcome else {
            return XCTFail("ожидали .confirmed, получили \(outcome)")
        }
        XCTAssertEqual(session.token, "token-abc")
        XCTAssertEqual(session.chatID, 123456789)
        XCTAssertEqual(session.expiresAt, ISO8601DateFormatter().date(from: "2026-11-02T10:00:00Z"))
        // Подтверждённая сессия становится активным токеном клиента.
        XCTAssertEqual(client.sessionToken, "token-abc")

        // device_nonce обязан уйти в теле (§2.1) — без него вход не привязан к устройству.
        let body = try XCTUnwrap(StubURLProtocol.lastRequest?.bodyJSON)
        XCTAssertEqual(body["device_nonce"] as? String, "4821")
        XCTAssertEqual(body["secret"] as? String, "s3cr3t-xyz")
        XCTAssertEqual(body["public_code"] as? String, "a7f3c9d2e1b4")
    }

    func testPoll_pending_returnsPendingWithRetryHint() async throws {
        StubURLProtocol.reset { _ in TestTransport.ok(Fixtures.pollPending) }
        let client = makeClient()

        let outcome = try await client.pollSession(operation: operation(), deviceNonce: "4821")

        XCTAssertEqual(outcome, .pending(retryAfterMilliseconds: 2000))
    }

    func testPoll_expired_returnsExpiredOutcome() async throws {
        StubURLProtocol.reset { _ in TestTransport.ok(#"{ "status": "expired" }"#) }
        let client = makeClient()
        let outcome = try await client.pollSession(operation: operation(), deviceNonce: "4821")
        XCTAssertEqual(outcome, .expired)
    }

    func testPoll_denied_returnsDeniedOutcome() async throws {
        StubURLProtocol.reset { _ in TestTransport.ok(#"{ "status": "denied" }"#) }
        let client = makeClient()
        let outcome = try await client.pollSession(operation: operation(), deviceNonce: "4821")
        XCTAssertEqual(outcome, .denied)
    }

    func testPoll_attemptLimitExceeded_returnsTypedOutcome() async throws {
        StubURLProtocol.reset { _ in TestTransport.ok(#"{ "status": "attempt_limit_exceeded" }"#) }
        let client = makeClient()
        let outcome = try await client.pollSession(operation: operation(), deviceNonce: "4821")
        XCTAssertEqual(outcome, .attemptLimitExceeded)
    }

    /// W0-v2: `pending` — не ошибка. Раньше тест требовал исключения, из-за чего
    /// цикл входа не отличал «ещё не подтверждено» от сбоя.
    func testPollSession_pending_returnsPendingOutcome() async throws {
        StubURLProtocol.reset { _ in TestTransport.ok(Fixtures.pollPending) }
        let client = makeClient()
        let outcome = try await client.pollSession(operation: operation(), deviceNonce: "4821")
        XCTAssertEqual(outcome, .pending(retryAfterMilliseconds: 2000))
    }

    // MARK: /auth/poll — typed errors

    func testPoll_nonceMismatch_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("nonce_mismatch", status: 403,
                                                          message: "Неверный код", retryable: true) }
        let client = makeClient()
        await expectAPIError("nonce_mismatch") {
            _ = try await client.pollSession(operation: self.operation(), deviceNonce: "0000")
        }
    }

    func testPoll_invalidSecret_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("invalid_secret", status: 401) }
        let client = makeClient()
        await expectAPIError("invalid_secret") {
            _ = try await client.pollSession(operation: self.operation(), deviceNonce: "4821")
        }
    }

    func testPoll_operationNotFound_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("operation_not_found", status: 404) }
        let client = makeClient()
        await expectAPIError("operation_not_found") {
            _ = try await client.pollSession(operation: self.operation(), deviceNonce: "4821")
        }
    }

    func testPoll_alreadyConsumed_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("already_consumed", status: 409) }
        let client = makeClient()
        await expectAPIError("already_consumed") {
            _ = try await client.pollSession(operation: self.operation(), deviceNonce: "4821")
        }
    }

    func testPoll_rateLimited_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("rate_limited", status: 429, retryable: true) }
        let client = makeClient()
        await expectAPIError("rate_limited") {
            _ = try await client.pollSession(operation: self.operation(), deviceNonce: "4821")
        }
    }

    func testRequestLink_rateLimited_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("rate_limited", status: 429, retryable: true) }
        let client = makeClient()
        await expectAPIError("rate_limited") {
            _ = try await client.requestLink()
        }
    }

    func testRequestLink_publicCodeConflict_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("public_code_conflict", status: 409) }
        let client = makeClient()
        await expectAPIError("public_code_conflict") {
            _ = try await client.requestLink()
        }
    }

    // MARK: logout

    func testLogout_clearsStoredSessionToken() async throws {
        StubURLProtocol.reset { _ in TestTransport.ok(Fixtures.pollConfirmed) }
        let client = makeClient()
        _ = try await client.pollSession(operation: operation(), deviceNonce: "4821")
        XCTAssertNotNil(client.sessionToken)

        try await client.logout()
        XCTAssertNil(client.sessionToken, "выход обязан очистить сессию (контракт §5)")
    }
}
