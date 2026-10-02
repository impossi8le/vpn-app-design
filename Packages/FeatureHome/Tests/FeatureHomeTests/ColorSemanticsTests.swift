import XCTest
@testable import FeatureHome
import CoreDomain

/// Правила семантики цвета (спека §9). Нарушение — дефект.
final class ColorSemanticsTests: XCTestCase {

    func testGreenPermittedOnlyOnProtected() {
        XCTAssertEqual(HomeScreenSituation.connected.semanticColor, .positive)
        let greens = HomeScreenSituation.allCases.filter { $0.semanticColor == .positive }
        XCTAssertEqual(greens, [.connected], "зелёный — ровно одно состояние")
    }

    func testNoOtherSituationIsGreen() {
        for s in HomeScreenSituation.allCases where s != .connected {
            XCTAssertNotEqual(s.semanticColor, .positive,
                              "зелёный запрещён для \(s)")
        }
    }

    func testVerifyingIsNotGreenAndSaysNotVerified() {
        XCTAssertNotEqual(HomeScreenSituation.verifying.semanticColor, .positive)
        XCTAssertEqual(HomeScreenSituation.verifying.semanticColor, .progress)
        XCTAssertEqual(HomeScreenSituation.verifying.protectionLabel, "не проверено")
    }

    func testNotConnectedIsNeutralNotDanger() {
        XCTAssertEqual(HomeScreenSituation.notConnected.semanticColor, .neutral)
    }

    func testSwitchingWindowIsDanger() {
        XCTAssertEqual(HomeScreenSituation.switching.semanticColor, .danger)
        XCTAssertEqual(HomeScreenSituation.switching.isDangerous, true)
    }

    func testProtectionFailedIsAmberNotGreen() {
        // Третий исход — предупреждение, но зелёное заблокировано.
        XCTAssertEqual(HomeScreenSituation.protectionFailed.semanticColor, .progress)
        XCTAssertNotEqual(HomeScreenSituation.protectionFailed.semanticColor, .positive)
    }

    func testProgressIsAmberOnlyForInFlight() {
        let amber: Set<HomeScreenSituation> = [.connecting, .verifying, .escalating, .protectionFailed]
        for s in HomeScreenSituation.allCases {
            if amber.contains(s) {
                XCTAssertEqual(s.semanticColor, .progress, "\(s) обязан быть янтарным")
            } else {
                XCTAssertNotEqual(s.semanticColor, .progress, "\(s) не должен быть янтарным")
            }
        }
    }

    func testDangerReservedForRealDanger() {
        // Красный ровно там, где реальная опасность: окно без защиты при смене.
        XCTAssertEqual(HomeScreenSituation.switching.semanticColor, .danger)
        XCTAssertNotEqual(HomeScreenSituation.notConnected.semanticColor, .danger)
        XCTAssertNotEqual(HomeScreenSituation.connected.semanticColor, .danger)
        XCTAssertNotEqual(HomeScreenSituation.allExpired.semanticColor, .danger)
    }
}
