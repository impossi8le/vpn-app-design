import Foundation
import CoreDomain

/// Заглушка пакета. Наполняется в W7 (мок-бэкенд по api-contract).
/// Существует, чтобы пакет собирался и W7 не правил пакеты W0.
public enum MockBackendVersion {
    public static let contract = "2026-10-02"
}
