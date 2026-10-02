import Foundation
import CoreDomain

/// Режим экрана. В демо туннель не поднимается, зелёное недостижимо
/// (блокер раунда 2: ревьюер Apple без подписки должен увидеть интерфейс).
public enum HomeMode: Equatable, Sendable { case live, demo }

/// Что экран показывает. Чистые значения, без SwiftUI.
public struct HomeViewState: Equatable, Sendable {
    public var situation: HomeScreenSituation
    public var button: HomeButton
    public var secondaryButton: HomeButton?
    public var protectionLabel: String
    public var escalation: HomeEscalation
    public var warning: String?
    public var connections: [Connection]
    public var selectedID: String?
    public var isDemo: Bool

    public init() {
        self.situation = .notConnected
        self.button = HomeButtonMatrix.button(for: .notConnected)
        self.secondaryButton = HomeButtonMatrix.secondary(for: .notConnected)
        self.protectionLabel = HomeScreenSituation.notConnected.protectionLabel
        self.escalation = .none
        self.warning = nil
        self.connections = []
        self.selectedID = nil
        self.isDemo = false
    }
}

/// Модель представления главного экрана. Зависит только от протоколов CoreDomain.
/// НЕ ObservableObject — на macOS обёртка добавит реактивность. Здесь только логика.
public final class HomeViewModel {
    private let tunnel: TunnelControlling
    private let gate: ProtectionGate
    private let config: ConfigService
    private let selection: SelectionStoring
    private let mode: HomeMode

    private var elapsedSeconds = 0

    public private(set) var state = HomeViewState()

    public init(tunnel: TunnelControlling,
                gate: ProtectionGate,
                config: ConfigService,
                selection: SelectionStoring,
                mode: HomeMode = .live) {
        self.tunnel = tunnel
        self.gate = gate
        self.config = config
        self.selection = selection
        self.mode = mode
    }

    // MARK: - Load

    /// Загружает список подключений и выбирает активное (или сохранённое).
    public func load() async {
        let connections: [Connection]
        if mode == .demo {
            connections = Self.demoConnections
        } else {
            connections = (try? await config.fetchConnections()) ?? []
        }
        state.connections = connections
        state.isDemo = (mode == .demo)

        // Восстановление выбранного: сохранённое, если ещё существует; иначе первое активное.
        if let saved = selection.load(), connections.contains(where: { $0.id == saved }) {
            state.selectedID = saved
        } else {
            let active = connections.first(where: { $0.status == .active })
                ?? connections.first
            state.selectedID = active?.id
            selection.save(state.selectedID)
        }

        state.situation = baseSituation(for: connections)
        // Живое состояние туннеля важнее «пустого» экрана, если он уже поднят.
        if mode == .live, state.situation == .notConnected {
            apply(tunnel.current)
            return
        }
        rebuild()
    }

    /// Ситуация по составу списка, когда туннель опущен.
    private func baseSituation(for connections: [Connection]) -> HomeScreenSituation {
        if connections.isEmpty { return .noSubscriptions }
        if connections.contains(where: { $0.status == .active }) { return .notConnected }
        if connections.contains(where: { $0.status == .revoked }) { return .accessRevoked }
        if connections.allSatisfy({ $0.status == .expired || $0.status == .revoked }) {
            return connections.contains(where: { $0.status == .revoked }) ? .accessRevoked : .allExpired
        }
        return .notConnected
    }

    // MARK: - Selection

    /// Смена подключения. Если туннель поднят — это переподключение через окно без защиты.
    public func select(id: String) async {
        guard state.connections.contains(where: { $0.id == id }) else { return }
        state.selectedID = id
        selection.save(id)

        switch state.situation {
        case .connected, .verifying, .protectionFailed:
            // Туннель опускается — окно без защиты. Единственное место, где красный оправдан.
            state.situation = .switching
            state.warning = "Трафик временно не защищён. Идёт переключение между подключениями."
        default:
            break
        }
        rebuild()
    }

    // MARK: - Primary button

    public func onPrimary() async {
        let action = state.button.action
        await perform(action)
    }

    private func perform(_ action: HomeButtonAction) async {
        switch action {
        case .connect, .retry:
            await connect()
        case .cancel, .disconnect:
            try? await tunnel.disconnect()
            apply(.disconnected)
        case .verifyAgain:
            await verifyProtection()
        case .refresh, .refreshAccess:
            await load()
        case .openSettings, .reauthenticate:
            // Обрабатывает хост-приложение (открыть Настройки / выйти и войти заново).
            break
        }
    }

    private func connect() async {
        guard mode == .live else { return }             // демо туннель не поднимает
        guard let id = state.selectedID else { return }
        elapsedSeconds = 0
        state.escalation = .none
        do {
            let profile = try await config.fetchProfile(id: id)
            try await tunnel.connect(profile: profile)
            state.situation = .connecting
            state.warning = nil
        } catch let error as TunnelError {
            apply(.failed(error))
            return
        } catch {
            state.situation = .error
        }
        rebuild()
    }

    // MARK: - Protection verification

    /// Единственный вход в зелёное. Вызывается после того, как туннель поднят.
    public func verifyProtection() async {
        guard mode == .live else { apply(.disconnected); return }
        let status = await gate.evaluate()
        apply(status)
    }

    // MARK: - Status reducer

    /// Применяет состояние туннеля. Единственное место, где рождается `.connected`.
    public func apply(_ status: ConnectionStatus) {
        if mode == .demo {
            // Демо никогда не показывает «защищено» как факт.
            state.situation = .notConnected
            rebuild()
            return
        }
        switch status {
        case .disconnected:
            state.situation = .notConnected
            state.escalation = .none
            state.warning = nil
        case .connecting:
            state.situation = .connecting
        case .verifyingProtection:
            state.situation = .verifying          // «не проверено», не зелёное
        case .protected:
            state.situation = .connected          // ЗЕЛЁНОЕ — только здесь
            state.escalation = .none
            state.warning = nil
        case .protectionFailed:
            state.situation = .protectionFailed   // третий исход, не вечное «проверяем»
        case .failed(.permissionDenied):
            state.situation = .noPermission
        case .failed:
            state.situation = .error
        }
        rebuild()
    }

    // MARK: - Escalation (10/20/30)

    /// Вызывается тикером, пока идёт подключение. Пороги 10/20/30 сек.
    public func tick(seconds: Int) {
        guard state.situation == .connecting || state.situation == .escalating else { return }
        elapsedSeconds = seconds
        let step = HomeEscalation.threshold(forSeconds: seconds)
        state.escalation = step
        switch step {
        case .none:
            break
        case .ten, .twenty:
            state.situation = .escalating
        case .thirty:
            state.situation = .error              // дольше 30 — честная ошибка с выходом
        }
        rebuild()
    }

    // MARK: - Stream observation

    /// Подписка на ленту туннеля. Новый подписчик первым получает current.
    public func observe() async {
        for await status in tunnel.statusStream() {
            apply(status)
        }
    }

    // MARK: - Reconstruction

    private func rebuild() {
        state.button = HomeButtonMatrix.button(for: state.situation)
        state.secondaryButton = HomeButtonMatrix.secondary(for: state.situation)
        state.protectionLabel = state.situation.protectionLabel
        if state.isDemo { state.button = HomeButton(label: state.button.label,
                                                    style: state.button.style,
                                                    action: state.button.action,
                                                    isEnabled: false) }
    }

    // MARK: - Demo data

    /// Вымышленные данные для ревьюера без подписки.
    static let demoConnections: [Connection] = [
        Connection(id: "demo-nl", name: "Нидерланды", countryCode: "NL", city: "Амстердам",
                   startDate: Date(timeIntervalSince1970: 0),
                   endDate: Date(timeIntervalSince1970: 1_000_000), status: .active),
        Connection(id: "demo-tr", name: "Турция", countryCode: "TR", city: "Стамбул",
                   startDate: Date(timeIntervalSince1970: 0),
                   endDate: Date(timeIntervalSince1970: 2_000_000), status: .active)
    ]
}
