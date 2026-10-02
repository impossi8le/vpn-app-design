// Tests/CoreNetworkTests/ConfigClientTests.swift
import XCTest
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import CoreDomain
import TestSupport
@testable import CoreNetwork

final class ConfigClientTests: XCTestCase {

    private let ovpnBody = """
    client
    dev tun
    proto udp
    remote vpn.example.com 1194
    <ca>
    -----BEGIN CERTIFICATE-----
    MIIB...
    -----END CERTIFICATE-----
    </ca>
    """

    private func makeClient(token: String? = "session-token") -> HTTPConfigClient {
        HTTPConfigClient(baseURL: TestTransport.baseURL,
                         session: TestTransport.session(),
                         tokenProvider: { token })
    }

    // MARK: GET /me

    func testFetchConnections_decodesRealConnections() async throws {
        StubURLProtocol.reset { req in
            XCTAssertEqual(req.httpMethod, "GET")
            XCTAssertEqual(req.url?.path, "/api/v1/me")
            XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer session-token")
            return TestTransport.ok(Fixtures.me)
        }
        let client = makeClient()

        let connections = try await client.fetchConnections()

        XCTAssertEqual(connections.count, 2)
        let first = connections[0]
        XCTAssertEqual(first.id, "nl-ams-1")
        XCTAssertEqual(first.name, "Нидерланды · Амстердам")
        XCTAssertEqual(first.countryCode, "NL")          // location развёрнут из вложенного объекта
        XCTAssertEqual(first.city, "Амстердам")
        XCTAssertEqual(first.status, .active)
        XCTAssertEqual(first.startDate, ISO8601DateFormatter().date(from: "2026-09-01T00:00:00Z"))
        XCTAssertEqual(first.endDate, ISO8601DateFormatter().date(from: "2026-12-01T00:00:00Z"))

        let second = connections[1]
        XCTAssertEqual(second.id, "nl-rtm-1")
        XCTAssertEqual(second.status, .expired)
        XCTAssertEqual(second.city, "Роттердам")
    }

    func testFetchConnections_emptyConfigs_isValidEmptyNotError() async throws {
        StubURLProtocol.reset { _ in TestTransport.ok(Fixtures.meEmpty) }
        let client = makeClient()

        let connections = try await client.fetchConnections()
        XCTAssertEqual(connections, [], "пустой configs — валидный ответ, не ошибка (контракт §3)")
    }

    func testFetchConnections_unauthorized_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("unauthorized", status: 401) }
        let client = makeClient()
        await expectAPIError("unauthorized") { _ = try await client.fetchConnections() }
    }

    func testFetchConnections_accountBlocked_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("account_blocked", status: 403) }
        let client = makeClient()
        await expectAPIError("account_blocked") { _ = try await client.fetchConnections() }
    }

    // MARK: GET /config/{id}

    func testFetchProfile_returnsRawBytes_andExposesVersionAndHashHeaders() async throws {
        StubURLProtocol.reset { req in
            XCTAssertEqual(req.httpMethod, "GET")
            XCTAssertEqual(req.url?.path, "/api/v1/config/nl-ams-1")
            XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer session-token")
            let headers = [
                "Content-Type": "application/x-openvpn-profile",
                "X-Config-Version": "2026-10-02T09:00:00Z",
                "X-Config-Hash": "sha256:ab12cd34"
            ]
            return TestTransport.json(self.ovpnBody, status: 200, headers: headers)
        }
        let client = makeClient()

        let profile = try await client.fetchProfile(id: "nl-ams-1")

        // Тело — сырой .ovpn, не JSON: байты должны совпасть ровно.
        XCTAssertEqual(profile.raw, Data(ovpnBody.utf8))
        XCTAssertTrue(String(data: profile.raw, encoding: .utf8)!.hasPrefix("client\n"))
        XCTAssertEqual(profile.version, "2026-10-02T09:00:00Z")
        XCTAssertEqual(profile.hash, "sha256:ab12cd34")
    }

    func testFetchProfile_unauthorized_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("unauthorized", status: 401) }
        let client = makeClient()
        await expectAPIError("unauthorized") { _ = try await client.fetchProfile(id: "x") }
    }

    func testFetchProfile_configRevoked_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("config_revoked", status: 403) }
        let client = makeClient()
        await expectAPIError("config_revoked") { _ = try await client.fetchProfile(id: "x") }
    }

    func testFetchProfile_subscriptionExpired_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("subscription_expired", status: 402) }
        let client = makeClient()
        await expectAPIError("subscription_expired") { _ = try await client.fetchProfile(id: "x") }
    }

    func testFetchProfile_configNotFound_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("config_not_found", status: 404) }
        let client = makeClient()
        await expectAPIError("config_not_found") { _ = try await client.fetchProfile(id: "x") }
    }

    func testFetchProfile_configRetired_mapsToTypedError() async {
        StubURLProtocol.reset { _ in TestTransport.error("config_retired", status: 410) }
        let client = makeClient()
        await expectAPIError("config_retired") { _ = try await client.fetchProfile(id: "x") }
    }

    func testFetchProfile_missingAuthHeader_throwsUnauthorizedWithoutNetwork() async {
        StubURLProtocol.reset { _ in
            XCTFail("без токена запрос уходить не должен")
            return TestTransport.ok(self.ovpnBody)
        }
        let client = makeClient(token: nil)
        await expectAPIError("unauthorized") { _ = try await client.fetchProfile(id: "x") }
    }
}
