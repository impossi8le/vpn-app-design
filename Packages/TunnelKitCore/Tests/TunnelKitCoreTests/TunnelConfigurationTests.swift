// Tests/TunnelKitCoreTests/TunnelConfigurationTests.swift
import XCTest
@testable import TunnelKitCore

final class TunnelConfigurationTests: XCTestCase {

    private func profile(_ extra: String = "") throws -> OpenVPNProfile {
        try OpenVPNProfile(parsing: realShapeProfile + "\n" + extra)
    }

    // MARK: Well-formed profile validates OK

    func testWellFormedProfileConfiguresAndValidates() throws {
        let config = try TunnelConfigurationBuilder.build(from: try profile())
        XCTAssertNoThrow(try TunnelConfigValidator.validate(config))

        XCTAssertEqual(config.ipv4Route, .allThroughTunnel)
        XCTAssertEqual(config.ipv6Policy, .closed)
        XCTAssertEqual(config.dns, .insideTunnel)
        XCTAssertTrue(config.killSwitch.includeAllNetworks)
        XCTAssertEqual(config.endpoints.first?.host, "203.0.113.10")
    }

    // MARK: IPv6 bypass is an error

    func testIPv6BypassProducesValidationError() throws {
        // `redirect-gateway ipv6` routes IPv6 outside the tunnel → deanonimisation risk.
        let config = try TunnelConfigurationBuilder.build(from: try profile("redirect-gateway ipv6 def1"))
        XCTAssertEqual(config.ipv6Policy, .routedOutsideTunnel)
        XCTAssertThrowsError(try TunnelConfigValidator.validate(config)) { error in
            XCTAssertEqual(error as? TunnelConfigError, .ipv6BypassesTunnel)
        }
    }

    // MARK: DNS outside tunnel is an error

    func testDNSOutsideTunnelProducesValidationError() {
        let config = TunnelConfiguration(
            endpoints: [RemoteEndpoint(host: "203.0.113.10", port: 443, proto: "udp")],
            ipv4Route: .allThroughTunnel,
            ipv6Policy: .closed,
            dns: .outsideTunnel,
            killSwitch: KillSwitch(includeAllNetworks: true)
        )
        XCTAssertThrowsError(try TunnelConfigValidator.validate(config)) { error in
            XCTAssertEqual(error as? TunnelConfigError, .dnsOutsideTunnel)
        }
    }

    // MARK: Kill switch must be present

    func testMissingKillSwitchProducesValidationError() {
        let config = TunnelConfiguration(
            endpoints: [RemoteEndpoint(host: "203.0.113.10", port: 443, proto: "udp")],
            ipv4Route: .allThroughTunnel,
            ipv6Policy: .closed,
            dns: .insideTunnel,
            killSwitch: KillSwitch(includeAllNetworks: false)
        )
        XCTAssertThrowsError(try TunnelConfigValidator.validate(config)) { error in
            XCTAssertEqual(error as? TunnelConfigError, .killSwitchNotEnabled)
        }
    }

    // MARK: IPv4 not routed through tunnel is suspicious

    func testIPv4NotRoutedThroughTunnelIsError() {
        let config = TunnelConfiguration(
            endpoints: [RemoteEndpoint(host: "203.0.113.10", port: 443, proto: "udp")],
            ipv4Route: .split,
            ipv6Policy: .closed,
            dns: .insideTunnel,
            killSwitch: KillSwitch(includeAllNetworks: true)
        )
        XCTAssertThrowsError(try TunnelConfigValidator.validate(config)) { error in
            XCTAssertEqual(error as? TunnelConfigError, .ipv4NotRoutedThroughTunnel)
        }
    }

    // MARK: No remote → cannot build

    func testProfileWithoutRemoteCannotBuild() {
        let text = "client\ndev tun\nproto udp\n"
        XCTAssertThrowsError(try TunnelConfigurationBuilder.build(from: try OpenVPNProfile(parsing: text))) { error in
            XCTAssertEqual(error as? TunnelConfigError, .noRemoteEndpoint)
        }
    }

    // MARK: Validator accumulates / ordering — ipv6 reported first for the bypass case

    func testValidationErrorOrderingIsDeterministic() {
        let config = TunnelConfiguration(
            endpoints: [RemoteEndpoint(host: "203.0.113.10", port: 443, proto: "udp")],
            ipv4Route: .split,
            ipv6Policy: .routedOutsideTunnel,
            dns: .outsideTunnel,
            killSwitch: KillSwitch(includeAllNetworks: false)
        )
        var thrown: TunnelConfigError?
        do { try TunnelConfigValidator.validate(config) } catch { thrown = error as? TunnelConfigError }
        XCTAssertEqual(thrown, .ipv6BypassesTunnel)
    }
}
