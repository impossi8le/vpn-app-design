// Tests/TestSupportTests/FixtureDecodingTests.swift
import XCTest
import CoreDomain
@testable import TestSupport

final class FixtureDecodingTests: XCTestCase {

    private func decoder() -> JSONDecoder {
        let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d
    }

    func testMeFixtureDecodesIntoConnectionsWithOwnStatuses() throws {
        struct Me: Decodable { let chatID: Int64; let configs: [Connection]
            enum CodingKeys: String, CodingKey { case chatID = "chat_id"; case configs } }
        let me = try decoder().decode(Me.self, from: Fixtures.me.data(using: .utf8)!)
        XCTAssertEqual(me.configs.count, 2)
        XCTAssertEqual(me.configs[0].status, .active)
        XCTAssertEqual(me.configs[1].status, .expired)   // статус у каждого свой
    }

    func testEmptyMeIsValidNotError() throws {
        struct Me: Decodable { let configs: [Connection] }
        let me = try decoder().decode(Me.self, from: Fixtures.meEmpty.data(using: .utf8)!)
        XCTAssertTrue(me.configs.isEmpty)
    }

    func testPollConfirmedFixtureDecodes() throws {
        struct Poll: Decodable { let status: String; let sessionToken: String
            enum CodingKeys: String, CodingKey { case status; case sessionToken = "session_token" } }
        let poll = try decoder().decode(Poll.self, from: Fixtures.pollConfirmed.data(using: .utf8)!)
        XCTAssertEqual(poll.status, "confirmed")
        XCTAssertEqual(poll.sessionToken, "token-abc")
    }
}
