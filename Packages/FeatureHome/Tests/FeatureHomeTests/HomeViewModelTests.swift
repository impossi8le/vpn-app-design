import XCTest
@testable import FeatureHome
import CoreDomain
import TestSupport

final class HomeViewModelTests: XCTestCase {

    // MARK: - Helpers

    private func conn(_ id: String, _ name: String, _ status: Connection.SubscriptionStatus) -> Connection {
        Connection(id: id, name: name, countryCode: "NL", city: name,
                   startDate: Date(timeIntervalSince1970: 0),
                   endDate: Date(timeIntervalSince1970: 1_000_000),
                   status: status)
    }

    private func makeVM(
        connections: [Connection] = [],
        probe: FakeProtectionProbe = FakeProtectionProbe(),
        selection: InMemorySelectionStore = InMemorySelectionStore()
    ) -> (HomeViewModel, FakeTunnelControlling, FakeConfigService, InMemorySelectionStore) {
        let tunnel = FakeTunnelControlling()
        let config = FakeConfigService()
        config.connections = connections
        let vm = HomeViewModel(
            tunnel: tunnel,
            gate: ProtectionGate(probe: probe),
            config: config,
            selection: selection
        )
        return (vm, tunnel, config, selection)
    }

    private func green() -> ProtectionVerdict {
        ProtectionVerdict.evaluate(ipv4Bypassed: true, ipv6Closed: true, dnsInside: true)
    }
    private func red() -> ProtectionVerdict {
        ProtectionVerdict.evaluate(ipv4Bypassed: false, ipv6Closed: true, dnsInside: true)
    }

    // MARK: - Loading / empty / expired

    func testEmptyListIsNoSubscriptionsState() async {
        let (vm, _, _, _) = makeVM(connections: [])
        await vm.load()
        XCTAssertEqual(vm.state.situation, .noSubscriptions)
        XCTAssertEqual(vm.state.button.label, "Обновить")
    }

    func testOneExpiredDoesNotBlockTheOthers() async {
        let (vm, _, _, _) = makeVM(connections: [
            conn("a", "Амстердам", .active),
            conn("b", "Роттердам", .expired)
        ])
        await vm.load()
        XCTAssertEqual(vm.state.connections.count, 2)
        XCTAssertNotEqual(vm.state.situation, .allExpired)
        XCTAssertEqual(vm.state.situation, .notConnected)
        XCTAssertEqual(vm.state.selectedID, "a", "выбрано активное, не истёкшее")
    }

    func testAllExpiredIsAllExpiredState() async {
        let (vm, _, _, _) = makeVM(connections: [
            conn("a", "Амстердам", .expired),
            conn("b", "Роттердам", .expired)
        ])
        await vm.load()
        XCTAssertEqual(vm.state.situation, .allExpired)
        XCTAssertEqual(vm.state.button.label, "Обновить доступ")
    }

    func testRevokedWithNoActiveIsAccessRevoked() async {
        let (vm, _, _, _) = makeVM(connections: [
            conn("a", "Амстердам", .revoked)
        ])
        await vm.load()
        XCTAssertEqual(vm.state.situation, .accessRevoked)
        XCTAssertEqual(vm.state.button.label, "Обновить доступ")
    }

    // MARK: - Selected connection persists

    func testSelectedConnectionPersistsAcrossViewModelRecreation() async {
        let store = InMemorySelectionStore()
        let (vm1, _, _, _) = makeVM(connections: [
            conn("a", "Амстердам", .active),
            conn("b", "Роттердам", .active)
        ], selection: store)
        await vm1.load()
        await vm1.select(id: "b")
        XCTAssertEqual(vm1.state.selectedID, "b")

        let (vm2, _, _, _) = makeVM(connections: [
            conn("a", "Амстердам", .active),
            conn("b", "Роттердам", .active)
        ], selection: store)
        await vm2.load()
        XCTAssertEqual(vm2.state.selectedID, "b", "выбор пережил пересоздание модели")
    }

    // MARK: - Green only on .protected

    func testVerifyingShowsNotVerifiedAndIsNotGreen() async {
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)])
        await vm.load()
        vm.apply(.verifyingProtection)
        XCTAssertEqual(vm.state.situation, .verifying)
        XCTAssertEqual(vm.state.protectionLabel, "не проверено")
        XCTAssertNotEqual(vm.state.situation.semanticColor, .positive)
    }

    func testGreenOnlyAfterConfirmedProbe() async {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = green()
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)], probe: probe)
        await vm.load()
        vm.apply(.verifyingProtection)
        XCTAssertEqual(vm.state.situation, .verifying, "сначала «не проверено»")
        await vm.verifyProtection()               // гейт вызывает пробу
        XCTAssertEqual(vm.state.situation, .connected)
        XCTAssertEqual(vm.state.situation.semanticColor, .positive)
    }

    // MARK: - Protection failure: third outcome, not eternal "verifying"

    func testProtectionFailureShowsThirdOutcome() async {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = red()
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)], probe: probe)
        await vm.load()
        vm.apply(.verifyingProtection)
        await vm.verifyProtection()
        XCTAssertEqual(vm.state.situation, .protectionFailed)
        XCTAssertNotEqual(vm.state.situation, .verifying, "не вечное «проверяем»")
        XCTAssertNotEqual(vm.state.situation.semanticColor, .positive)
        XCTAssertEqual(vm.state.button.label, "Отключить")
        XCTAssertEqual(vm.state.secondaryButton?.label, "Проверить снова")
    }

    func testProbeUnavailableIsAlsoThirdOutcome() async {
        let probe = FakeProtectionProbe()
        probe.verifyError = TunnelError.providerFailed("boom")
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)], probe: probe)
        await vm.load()
        vm.apply(.verifyingProtection)
        await vm.verifyProtection()
        XCTAssertEqual(vm.state.situation, .protectionFailed)
    }

    // MARK: - Round-1 regression: tunnel drop returns button to "Подключить"

    func testTunnelDropReturnsToNotConnectedAndConnectButton() async {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = green()
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)], probe: probe)
        await vm.load()
        vm.apply(.verifyingProtection)
        await vm.verifyProtection()
        XCTAssertEqual(vm.state.button.label, "Отключить")

        vm.apply(.disconnected)            // обрыв туннеля
        XCTAssertEqual(vm.state.situation, .notConnected)
        XCTAssertEqual(vm.state.button.label, "Подключить",
                       "регрессия раунда 1: застревание с «Отключить» на неподключённом")
        XCTAssertEqual(vm.state.button.action, .connect)
    }

    func testObserveStreamDrivesDropRegressionEndToEnd() async {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = green()
        let (vm, tunnel, _, _) = makeVM(connections: [conn("a", "NL", .active)], probe: probe)
        await vm.load()

        // FakeTunnelControlling отдаёт текущее состояние новому подписчику первым
        // событием, поэтому emit до observe() не теряется — рукопожатие детерминировано.
        tunnel.emit(.verifyingProtection)
        let task = Task { await vm.observe() }
        await waitUntil { vm.state.situation == .verifying }
        XCTAssertEqual(vm.state.situation, .verifying, "подписка встала и получила current")

        await vm.verifyProtection()
        XCTAssertEqual(vm.state.situation, .connected)

        tunnel.emit(.disconnected)                                // обрыв туннеля
        await waitUntil { vm.state.situation == .notConnected }
        XCTAssertEqual(vm.state.situation, .notConnected)
        XCTAssertEqual(vm.state.button.label, "Подключить")
        task.cancel()
    }

    /// Ждёт условие, диспетчеризуя сопрограммы. Без реального сна в проде.
    private func waitUntil(_ predicate: () -> Bool, timeout: Int = 500) async {
        for _ in 0..<timeout {
            if predicate() { return }
            await Task.yield()
        }
    }

    // MARK: - Connect flow

    func testConnectFetchesProfileAndStartsTunnel() async {
        let (vm, tunnel, _, _) = makeVM(connections: [conn("a", "NL", .active)])
        await vm.load()
        await vm.onPrimary()
        XCTAssertEqual(tunnel.connectedProfiles.count, 1)
        XCTAssertEqual(vm.state.situation, .connecting)
        XCTAssertEqual(vm.state.button.label, "Отменить")
    }

    func testConnectErrorBecomesErrorStateWithRetry() async {
        let (vm, tunnel, _, _) = makeVM(connections: [conn("a", "NL", .active)])
        tunnel.connectError = .providerFailed("no route")
        await vm.load()
        await vm.onPrimary()
        XCTAssertEqual(vm.state.situation, .error)
        XCTAssertEqual(vm.state.button.label, "Повторить")
    }

    // MARK: - Permission denied is not a dead end

    func testPermissionDeniedIsNotADeadEnd() async {
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)])
        await vm.load()
        vm.apply(.failed(.permissionDenied))
        XCTAssertEqual(vm.state.situation, .noPermission)
        XCTAssertEqual(vm.state.button.label, "Открыть Настройки")
        XCTAssertEqual(vm.state.button.action, .openSettings)

        // Устройство не заперто: возврат туннеля в disconnected снова даёт «Подключить».
        vm.apply(.disconnected)
        XCTAssertEqual(vm.state.situation, .notConnected)
        XCTAssertEqual(vm.state.button.label, "Подключить")
    }

    // MARK: - Unsecured window on switching = danger

    func testSwitchingShowsUnsecuredWindowAsDanger() async {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = green()
        let (vm, _, _, _) = makeVM(connections: [
            conn("a", "Амстердам", .active),
            conn("b", "Роттердам", .active)
        ], probe: probe)
        await vm.load()
        vm.apply(.verifyingProtection)
        await vm.verifyProtection()              // подключены и защищены
        XCTAssertEqual(vm.state.situation, .connected)

        await vm.select(id: "b")                  // смена подключения = переподключение
        XCTAssertEqual(vm.state.situation, .switching)
        XCTAssertEqual(vm.state.situation.semanticColor, .danger)
        XCTAssertTrue(vm.state.situation.isDangerous)
        XCTAssertNotNil(vm.state.warning, "окно без защиты сопровождается предупреждением")
        XCTAssertEqual(vm.state.button.label, "Отменить")
    }

    // MARK: - Escalation 10 / 20 / 30

    func testEscalationPicksRightCopyAt10And20() async {
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)])
        await vm.load()
        await vm.onPrimary()
        XCTAssertEqual(vm.state.escalation, .none)

        vm.tick(seconds: 11)
        XCTAssertEqual(vm.state.situation, .escalating)
        XCTAssertEqual(vm.state.escalation, .ten)
        XCTAssertEqual(vm.state.escalation.copy, HomeEscalation.ten.copy)
        XCTAssertEqual(vm.state.button.label, "Повторить")

        vm.tick(seconds: 21)
        XCTAssertEqual(vm.state.escalation, .twenty)
        XCTAssertEqual(vm.state.escalation.copy, HomeEscalation.twenty.copy)
        XCTAssertNotEqual(HomeEscalation.ten.copy, HomeEscalation.twenty.copy)
    }

    func testEscalationBeyondThirtyIsHonestError() async {
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)])
        await vm.load()
        await vm.onPrimary()
        vm.tick(seconds: 31)
        XCTAssertEqual(vm.state.escalation, .thirty)
        XCTAssertEqual(vm.state.situation, .error, "дольше 30 — честная ошибка с выходом")
        XCTAssertEqual(vm.state.button.label, "Повторить")
    }

    // MARK: - Demo mode (round-2 Apple-reviewer blocker)

    func testDemoModeIsReachableAndNeverClaimsProtection() async {
        let tunnel = FakeTunnelControlling()
        let vm = HomeViewModel(
            tunnel: tunnel,
            gate: ProtectionGate(probe: FakeProtectionProbe()),
            config: FakeConfigService(),
            selection: InMemorySelectionStore(),
            mode: .demo
        )
        await vm.load()
        XCTAssertTrue(vm.state.isDemo)
        XCTAssertFalse(vm.state.connections.isEmpty, "демо-данные доступны ревьюеру без подписки")
        XCTAssertEqual(vm.state.situation, .notConnected)
        XCTAssertEqual(vm.state.button.label, "Подключить")
        XCTAssertFalse(vm.state.button.isEnabled, "в демо туннель не поднимается")

        await vm.onPrimary()
        XCTAssertTrue(tunnel.connectedProfiles.isEmpty, "демо не поднимает туннель")
        XCTAssertNotEqual(vm.state.situation.semanticColor, .positive,
                          "демо никогда не показывает «защищено» как факт")
    }

    // MARK: - Disconnect from connected

    func testDisconnectFromConnectedReturnsToNotConnected() async {
        let probe = FakeProtectionProbe()
        probe.nextVerdict = green()
        let (vm, _, _, _) = makeVM(connections: [conn("a", "NL", .active)], probe: probe)
        await vm.load()
        vm.apply(.verifyingProtection)
        await vm.verifyProtection()
        await vm.onPrimary()                      // кнопка «Отключить»
        vm.apply(.disconnected)
        XCTAssertEqual(vm.state.situation, .notConnected)
        XCTAssertEqual(vm.state.button.label, "Подключить")
    }
}
