import Foundation

/// Нейтральный дубль NEVPNStatus: CoreDomain не импортирует NetworkExtension.
public enum SystemTunnelState: Equatable {
    case invalid
    case disconnected
    case connecting
    case connected
    case reasserting
    case disconnecting
}

public enum ConnectionStatus: Equatable {
    case disconnected
    case connecting
    case verifyingProtection
    case protected(ProtectionEvidence)
    case protectionFailed(ProtectionVerdict)
    case failed(TunnelError)

    /// ИНВАРИАНТ: ни одно системное состояние не даёт `.protected`.
    /// `.connected` — это только «туннель поднят», не «защита подтверждена».
    public static func map(system: SystemTunnelState) -> ConnectionStatus {
        switch system {
        case .connected:                  return .verifyingProtection
        case .connecting, .reasserting:   return .connecting
        case .invalid, .disconnected,
             .disconnecting:              return .disconnected
        }
    }
}
