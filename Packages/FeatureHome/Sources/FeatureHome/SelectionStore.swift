import Foundation

/// Персистентность выбранного подключения между запусками (спека §8.5).
/// Foundation-only здесь; в приложении на macOS слой заменит на UserDefaults.
public protocol SelectionStoring {
    func load() -> String?
    func save(_ id: String?)
}

public final class InMemorySelectionStore: SelectionStoring {
    private var value: String?
    public init(initial: String? = nil) { self.value = initial }
    public func load() -> String? { value }
    public func save(_ id: String?) { value = id }
}
