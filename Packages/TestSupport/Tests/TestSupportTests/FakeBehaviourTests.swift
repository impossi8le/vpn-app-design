// Tests/TestSupportTests/FakeBehaviourTests.swift
import XCTest
import CoreDomain
@testable import TestSupport

final class FakeBehaviourTests: XCTestCase {

    func testTunnelEmitsToSubscriberStartingFromCurrent() async {
        let tunnel = FakeTunnelControlling()
        let stream = tunnel.statusStream()          // подписка СИНХРОННА
        let collected = Task { () -> [ConnectionStatus] in
            var out: [ConnectionStatus] = []
            for await s in stream {
                out.append(s)
                if out.count == 3 { break }
            }
            return out
        }
        tunnel.emit(.connecting)                     // после подписки — не теряется
        tunnel.emit(.verifyingProtection)
        let result = await collected.value
        // Первым идёт снимок current (.disconnected), затем оба emit по порядку.
        XCTAssertEqual(result, [.disconnected, .connecting, .verifyingProtection])
    }

    func testTunnelCurrentReflectsLastEmit() {
        let tunnel = FakeTunnelControlling()
        tunnel.emit(.connecting)
        XCTAssertEqual(tunnel.current, .connecting)
    }

    func testProbeDefaultsToNotConfirmed() async throws {
        // Запрет зелёного по умолчанию: фейк не подтверждает защиту без явной настройки.
        let probe = FakeProtectionProbe()
        let verdict = try await probe.verify()
        XCTAssertFalse(verdict.isConfirmed)
    }

    func testProbeReturnsConfiguredVerdict() async throws {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = .failed(.ipv6Leak)
        let verdict = try await probe.verify()
        XCTAssertEqual(verdict, .failed(.ipv6Leak))
    }
}
