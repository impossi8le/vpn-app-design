import Foundation

// ---------------------------------------------------------------------------
// ADAPTER-FACING TYPE — what is DEFERRED to macOS
// ---------------------------------------------------------------------------
// `TunnelConfiguration` is the stable, platform-independent output of this package:
// it encodes the §8.3 product requirements (DNS inside the tunnel, IPv6 closed,
// IPv4 route-all, kill switch expressible) as *explicit fields*, so they can be
// asserted in tests here and re-asserted by the macOS adapter later.
//
// DEFERRED to the macOS-only adapter (NOT built or tested on Windows):
//   * Translating this object into a real TunnelKit `OpenVPN.Configuration`.
//   * Linking TunnelKit and NetworkExtension into the NE (packet-tunnel) target,
//     i.e. the archive/link risk that is the core of W4's acceptance criterion.
//   * Supplying `NEPacketTunnelNetworkSettings` (routed IPv4/IPv6, DNS servers).
//   * Setting `<profile>.includeAllNetworks` on the NEVPNProtocol to enforce the
//     kill switch. `KillSwitch.includeAllNetworks` below is the intent flag only;
//     the adapter maps it onto the system property.
//
// Why deferred: TunnelKit, NetworkExtension, Security and UIKit do not exist on the
// Windows toolchain used for development. Anything importing them cannot build and
// its tests cannot run here. Keeping the core pure lets the parsing + validation
// contract be fully verified now; the thin wiring is the only macOS-only piece.
// ---------------------------------------------------------------------------

/// IPv4 routing intent derived from the profile (`redirect-gateway def1`).
public enum IPv4RoutePolicy: Equatable {
    case allThroughTunnel
    case split
}

/// Whether IPv6 is closed or can leak outside the tunnel.
public enum IPv6Policy: Equatable {
    case closed
    case routedOutsideTunnel
}

/// Where DNS resolution happens.
public enum DNSPolicy: Equatable {
    case insideTunnel
    case outsideTunnel
}

/// Kill-switch intent, mapped by the macOS adapter to `includeAllNetworks`.
public struct KillSwitch: Equatable {
    public let includeAllNetworks: Bool
    public init(includeAllNetworks: Bool) { self.includeAllNetworks = includeAllNetworks }
}

/// Validated, platform-independent tunnel configuration (see the block comment above).
public struct TunnelConfiguration: Equatable {
    public let endpoints: [RemoteEndpoint]
    public let ipv4Route: IPv4RoutePolicy
    public let ipv6Policy: IPv6Policy
    public let dns: DNSPolicy
    public let killSwitch: KillSwitch

    public init(endpoints: [RemoteEndpoint],
                ipv4Route: IPv4RoutePolicy,
                ipv6Policy: IPv6Policy,
                dns: DNSPolicy,
                killSwitch: KillSwitch) {
        self.endpoints = endpoints
        self.ipv4Route = ipv4Route
        self.ipv6Policy = ipv6Policy
        self.dns = dns
        self.killSwitch = killSwitch
    }
}

/// Typed configuration violations, ordered so validation is deterministic.
public enum TunnelConfigError: Error, Equatable {
    case noRemoteEndpoint
    case ipv6BypassesTunnel
    case dnsOutsideTunnel
    case ipv4NotRoutedThroughTunnel
    case killSwitchNotEnabled
}

/// Builds a `TunnelConfiguration` from a parsed profile.
///
/// The builder's defaults encode the safe posture: IPv6 closed, DNS inside, IPv4
/// route-all (from `redirect-gateway`), kill switch on. A profile can *declare*
/// something unsafe (e.g. `redirect-gateway ipv6`); the builder records that
/// faithfully and the validator rejects it.
public enum TunnelConfigurationBuilder {

    public static func build(from profile: OpenVPNProfile) throws -> TunnelConfiguration {
        let endpoints = profile.remotes
        guard !endpoints.isEmpty else { throw TunnelConfigError.noRemoteEndpoint }

        return TunnelConfiguration(
            endpoints: endpoints,
            ipv4Route: profile.contains("redirect-gateway") ? .allThroughTunnel : .split,
            ipv6Policy: routesIPv6OutsideTunnel(profile) ? .routedOutsideTunnel : .closed,
            dns: .insideTunnel,
            killSwitch: KillSwitch(includeAllNetworks: true)
        )
    }

    /// `redirect-gateway` variants that route IPv6 through the real interface
    /// instead of the tunnel. Bare `redirect-gateway` / `def1` / `bypass-dhcp`
    /// only affect IPv4; the `ipv6` flag is the leak.
    private static func routesIPv6OutsideTunnel(_ profile: OpenVPNProfile) -> Bool {
        profile.allValues(for: "redirect-gateway").contains { directive in
            directive.args.contains("ipv6")
        }
    }
}

/// Enforces the §8.3 requirements. First violation wins, in a fixed order.
public enum TunnelConfigValidator {

    public static func validate(_ config: TunnelConfiguration) throws {
        guard config.ipv6Policy != .routedOutsideTunnel else { throw TunnelConfigError.ipv6BypassesTunnel }
        guard config.dns == .insideTunnel else { throw TunnelConfigError.dnsOutsideTunnel }
        guard config.ipv4Route == .allThroughTunnel else { throw TunnelConfigError.ipv4NotRoutedThroughTunnel }
        guard config.killSwitch.includeAllNetworks else { throw TunnelConfigError.killSwitchNotEnabled }
    }
}
