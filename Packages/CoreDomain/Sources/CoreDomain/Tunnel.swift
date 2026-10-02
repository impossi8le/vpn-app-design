import Foundation

public enum TunnelError: Equatable, Error {
    case permissionDenied
    case configurationInvalid(String)
    case providerFailed(String)
    case notConfigured
}

public protocol TunnelControlling {
    func connect(profile: Profile) async throws
    func disconnect() async throws
    /// Для поздно подписавшихся и для возврата из фона.
    var current: ConnectionStatus { get }
    /// Мультиподписчичная лента. Новый подписчик первым получает current.
    func statusStream() -> AsyncStream<ConnectionStatus>
}
// TunnelControlling НЕ эмитит .protected — это делает ProtectionGate.
