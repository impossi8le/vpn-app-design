// Tests/CoreDomainTests/StatusMappingTests.swift
import XCTest
@testable import CoreDomain

final class StatusMappingTests: XCTestCase {

    func testNoSystemStateYieldsProtected() {
        // Инвариант: зелёное не выводится ни из одного системного состояния.
        // Проверяем ВСЕ состояния, а не только .connected.
        let all: [SystemTunnelState] = [.invalid, .disconnected, .connecting,
                                        .connected, .reasserting, .disconnecting]
        for state in all {
            let mapped = ConnectionStatus.map(system: state)
            if case .protected = mapped {
                XCTFail("состояние \(state) дало .protected — ложная защита")
            }
        }
    }

    func testConnectedMapsToVerifyingNotProtected() {
        XCTAssertEqual(ConnectionStatus.map(system: .connected), .verifyingProtection)
    }

    func testDisconnectedStates() {
        for state in [SystemTunnelState.invalid, .disconnected, .disconnecting] {
            XCTAssertEqual(ConnectionStatus.map(system: state), .disconnected)
        }
    }

    func testTransientStates() {
        for state in [SystemTunnelState.connecting, .reasserting] {
            XCTAssertEqual(ConnectionStatus.map(system: state), .connecting)
        }
    }
}
