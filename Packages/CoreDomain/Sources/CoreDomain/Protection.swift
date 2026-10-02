import Foundation

public enum ProtectionFailure: Equatable {
    case ipv4Leak
    case ipv6Leak
    case dnsOutsideTunnel
    case probeUnavailable
    case inconclusive
}

/// Доказательство защиты. `init` внутренний — вне CoreDomain собрать нельзя.
public struct ProtectionEvidence: Equatable {
    public let ipv4Bypassed: Bool
    public let ipv6Closed: Bool
    public let dnsInside: Bool
    init(ipv4Bypassed: Bool, ipv6Closed: Bool, dnsInside: Bool) {
        self.ipv4Bypassed = ipv4Bypassed
        self.ipv6Closed = ipv6Closed
        self.dnsInside = dnsInside
    }
}

public enum ProtectionVerdict: Equatable {
    case confirmed(ProtectionEvidence)
    case failed(ProtectionFailure)

    /// ЕДИНСТВЕННЫЙ вход в зелёное. Любое false → .failed(.inconclusive).
    public static func evaluate(ipv4Bypassed: Bool, ipv6Closed: Bool, dnsInside: Bool) -> ProtectionVerdict {
        guard ipv4Bypassed, ipv6Closed, dnsInside else { return .failed(.inconclusive) }
        return .confirmed(ProtectionEvidence(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true))
    }

    public var isConfirmed: Bool {
        if case .confirmed = self { return true }
        return false
    }
}

public protocol ProtectionProbe {
    func verify() async throws -> ProtectionVerdict
}

/// Единственный владелец перехода в зелёное.
public struct ProtectionGate {
    private let probe: ProtectionProbe
    public init(probe: ProtectionProbe) { self.probe = probe }

    public func evaluate() async -> ConnectionStatus {
        do {
            switch try await probe.verify() {
            case .confirmed(let evidence): return .protected(evidence)
            case .failed:                  return .protectionFailed(.failed(.inconclusive))
            }
        } catch {
            return .protectionFailed(.failed(.probeUnavailable))
        }
    }
}
