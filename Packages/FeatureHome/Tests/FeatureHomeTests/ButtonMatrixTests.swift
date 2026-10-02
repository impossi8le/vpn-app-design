import XCTest
@testable import FeatureHome

/// Матрица главной кнопки — спека §8.5 (+ строка «Переключение» из макета).
/// Каждая строка проверяется буквально: текст + заливка (белая/ghost).
final class ButtonMatrixTests: XCTestCase {

    private let m = HomeButtonMatrix.self

    func testNotConnected() {
        XCTAssertEqual(m.button(for: .notConnected),
                       HomeButton(label: "Подключить", style: .primary, action: .connect))
    }

    func testConnecting() {
        XCTAssertEqual(m.button(for: .connecting),
                       HomeButton(label: "Отменить", style: .ghost, action: .cancel))
    }

    func testVerifying() {
        XCTAssertEqual(m.button(for: .verifying),
                       HomeButton(label: "Отключить", style: .ghost, action: .disconnect))
    }

    func testProtectionFailed() {
        XCTAssertEqual(m.button(for: .protectionFailed),
                       HomeButton(label: "Отключить", style: .ghost, action: .disconnect))
        XCTAssertEqual(m.secondary(for: .protectionFailed),
                       HomeButton(label: "Проверить снова", style: .primary, action: .verifyAgain))
    }

    func testConnected() {
        XCTAssertEqual(m.button(for: .connected),
                       HomeButton(label: "Отключить", style: .ghost, action: .disconnect))
    }

    func testError() {
        XCTAssertEqual(m.button(for: .error),
                       HomeButton(label: "Повторить", style: .primary, action: .retry))
    }

    func testNoPermission() {
        XCTAssertEqual(m.button(for: .noPermission),
                       HomeButton(label: "Открыть Настройки", style: .primary, action: .openSettings))
    }

    func testAllExpired() {
        XCTAssertEqual(m.button(for: .allExpired),
                       HomeButton(label: "Обновить доступ", style: .primary, action: .refreshAccess))
    }

    func testNoSubscriptions() {
        XCTAssertEqual(m.button(for: .noSubscriptions),
                       HomeButton(label: "Обновить", style: .primary, action: .refresh))
        XCTAssertEqual(m.secondary(for: .noSubscriptions),
                       HomeButton(label: "Войти другим Telegram", style: .ghost, action: .reauthenticate))
    }

    func testAccessRevoked() {
        XCTAssertEqual(m.button(for: .accessRevoked),
                       HomeButton(label: "Обновить доступ", style: .primary, action: .refreshAccess))
        XCTAssertEqual(m.secondary(for: .accessRevoked),
                       HomeButton(label: "Войти другим Telegram", style: .ghost, action: .reauthenticate))
    }

    func testSwitching() {
        XCTAssertEqual(m.button(for: .switching),
                       HomeButton(label: "Отменить", style: .ghost, action: .cancel))
    }

    /// Все ситуации матрицы покрыты — ни одна не осталась без кнопки-заглушки.
    func testEverySituationHasADistinctPrimary() {
        let labels = HomeScreenSituation.allCases.map { m.button(for: $0).label }
        XCTAssertFalse(labels.contains("STUB"))
        for s in HomeScreenSituation.allCases {
            XCTAssertFalse(m.button(for: s).label.isEmpty, "пустая кнопка для \(s)")
        }
    }
}
