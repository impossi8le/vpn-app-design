// Tests/CoreNetworkTests/ErrorMappingTests.swift
import XCTest
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import CoreNetwork

/// Проверяет, что таблица кодов контракта §2/§4 покрыта типизированным перечислением
/// и что неизвестный код не «протекает» как строка и не роняет клиент.
final class ErrorMappingTests: XCTestCase {

    private static let contractCodes = [
        // §2
        "invalid_secret", "nonce_mismatch", "operation_not_found", "rate_limited",
        "already_consumed", "public_code_conflict",
        // §4
        "unauthorized", "config_revoked", "subscription_expired", "config_not_found",
        "config_retired", "account_blocked"
    ]

    func testEveryContractCodeHasTypedCase() {
        for raw in Self.contractCodes {
            XCTAssertNotEqual(APIErrorCode(rawValue: raw), .unknown, "код контракта не покрыт: \(raw)")
        }
    }

    func testUnknownCodeMapsToUnknownButStaysTyped() {
        XCTAssertEqual(APIErrorCode(rawValue: "brand_new_code"), .unknown)
    }

    func testAPIErrorExposesRetryableAndMessage() {
        let (resp, data) = TestTransport.error("rate_limited", status: 429,
                                               message: "slow down", retryable: true)
        let e = APIErrorMapper.decodeError(response: resp, body: data)
        XCTAssertEqual(e.code, .rateLimited)
        XCTAssertTrue(e.retryable)
        XCTAssertEqual(e.message, "slow down")
        XCTAssertEqual(e.httpStatus, 429)
    }

    func testNonJSONErrorBody_mapsToUnknownNotCrash() {
        let (resp, data) = TestTransport.json("<html>502 Bad Gateway</html>", status: 502)
        let e = APIErrorMapper.decodeError(response: resp, body: data)
        XCTAssertEqual(e.code, .unknown)
        XCTAssertEqual(e.httpStatus, 502)
    }
}
