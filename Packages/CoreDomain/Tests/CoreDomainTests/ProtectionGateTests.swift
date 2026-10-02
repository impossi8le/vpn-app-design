// Tests/CoreDomainTests/ProtectionGateTests.swift
import XCTest
@testable import CoreDomain

private struct StubProbe: ProtectionProbe {
    let verdict: ProtectionVerdict
    func verify() async throws -> ProtectionVerdict { verdict }
}

final class ProtectionGateTests: XCTestCase {

    func testAnyFalseConditionNeverYieldsGreen() {
        // Проверяем, что НИ ОДНА комбинация с false не даёт зелёное.
        for ipv4 in [true, false] {
            for ipv6 in [true, false] {
                for dns in [true, false] {
                    let verdict = ProtectionVerdict.evaluate(
                        ipv4Bypassed: ipv4, ipv6Closed: ipv6, dnsInside: dns)
                    if !(ipv4 && ipv6 && dns) {
                        XCTAssertFalse(verdict.isConfirmed,
                            "комбинация \(ipv4),\(ipv6),\(dns) дала зелёное")
                    }
                }
            }
        }
    }

    func testAllTrueYieldsGreen() {
        let verdict = ProtectionVerdict.evaluate(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true)
        XCTAssertTrue(verdict.isConfirmed)
    }

    func testGateYieldsProtectedOnlyOnConfirmedProbe() async {
        let ok = ProtectionGate(probe: StubProbe(
            verdict: .evaluate(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true)))
        let okStatus = await ok.evaluate()
        if case .protected = okStatus {} else { XCTFail("ожидали .protected") }

        let bad = ProtectionGate(probe: StubProbe(verdict: .failed(.ipv6Leak)))
        let badStatus = await bad.evaluate()
        if case .protectionFailed = badStatus {} else { XCTFail("ожидали .protectionFailed") }
    }
}
