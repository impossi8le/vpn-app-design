// Tests/CoreDomainTests/ModelDecodingTests.swift
import XCTest
@testable import CoreDomain

final class ModelDecodingTests: XCTestCase {

    func testConnectionDecodesFromMeJSON() throws {
        let json = """
        {
          "id": "nl-ams-1", "name": "Нидерланды · Амстердам",
          "location": { "country_code": "NL", "city": "Амстердам" },
          "start_date": "2026-09-01T00:00:00Z",
          "end_date": "2026-12-01T00:00:00Z",
          "status": "active"
        }
        """.data(using: .utf8)!
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let connection = try decoder.decode(Connection.self, from: json)
        XCTAssertEqual(connection.id, "nl-ams-1")
        XCTAssertEqual(connection.status, .active)
        XCTAssertEqual(connection.countryCode, "NL")
    }

    func testConnectionStatusDecodesEveryServerValue() throws {
        for (raw, expected) in [("active", Connection.SubscriptionStatus.active),
                                ("expired", .expired),
                                ("revoked", .revoked),
                                ("pending", .pending)] {
            let json = "\"\(raw)\"".data(using: .utf8)!
            XCTAssertEqual(try JSONDecoder().decode(Connection.SubscriptionStatus.self, from: json),
                           expected)
        }
    }
}
