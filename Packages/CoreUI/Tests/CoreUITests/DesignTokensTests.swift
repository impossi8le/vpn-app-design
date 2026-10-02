import XCTest
@testable import CoreUI

/// Токены дизайна — из спеки §9. Значения зафиксированы буквально.
final class DesignTokensTests: XCTestCase {

    func testBackgroundsMatchSpec() {
        let t = DesignTokens.standard
        XCTAssertEqual(t.background0.hex, "#0a0c10")
        XCTAssertEqual(t.background1.hex, "#12151b")
        XCTAssertEqual(t.background2.hex, "#1a1e26")
    }

    func testBordersMatchSpec() {
        let t = DesignTokens.standard
        XCTAssertEqual(t.borderCard.hex, "#262b34")
        XCTAssertEqual(t.borderInteractive.hex, "#5a6472")
    }

    func testTextMatchesSpec() {
        let t = DesignTokens.standard
        XCTAssertEqual(t.textPrimary.hex, "#e8ecf2")
        XCTAssertEqual(t.textSecondary.hex, "#aab4c2")
        XCTAssertEqual(t.textMuted.hex, "#8b95a3")
    }

    func testAccentMatchesSpec() {
        XCTAssertEqual(DesignTokens.standard.accent.hex, "#6fb3ff")
    }

    func testStateColorsMatchSpec() {
        let t = DesignTokens.standard
        XCTAssertEqual(t.statePositive.hex, "#3ddc97")
        XCTAssertEqual(t.stateProgress.hex, "#ffb454")
        XCTAssertEqual(t.stateDanger.hex, "#ff5f6d")
    }

    func testHexParsesIntoLinearComponents() {
        let c = ColorToken(hex: "#3ddc97")
        XCTAssertEqual(c.red, Double(0x3d) / 255.0, accuracy: 0.0001)
        XCTAssertEqual(c.green, Double(0xdc) / 255.0, accuracy: 0.0001)
        XCTAssertEqual(c.blue, Double(0x97) / 255.0, accuracy: 0.0001)
    }

    func testHexParsesWithoutLeadingHash() {
        XCTAssertEqual(ColorToken(hex: "ffb454").hex, "#ffb454")
    }

    func testSemanticMapping() {
        let t = DesignTokens.standard
        XCTAssertEqual(t.color(for: .positive), t.statePositive)
        XCTAssertEqual(t.color(for: .progress), t.stateProgress)
        XCTAssertEqual(t.color(for: .danger), t.stateDanger)
        XCTAssertEqual(t.color(for: .neutral), t.textSecondary)
    }

    /// Зелёный токен не переиспользуется ни одной другой семантической ролью.
    func testGreenTokenIsUniqueToPositiveRole() {
        let t = DesignTokens.standard
        XCTAssertNotEqual(t.color(for: .progress), t.statePositive)
        XCTAssertNotEqual(t.color(for: .danger), t.statePositive)
        XCTAssertNotEqual(t.color(for: .neutral), t.statePositive)
        XCTAssertNotEqual(t.background0, t.statePositive)
        XCTAssertNotEqual(t.accent, t.statePositive)
    }
}
