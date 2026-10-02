import Foundation

/// Цвет как чистые компоненты 0…1. SwiftUI здесь нет — на macOS обёртка
/// сделает `Color` из этих значений.
public struct ColorToken: Equatable, Hashable, Sendable {
    public let red: Double
    public let green: Double
    public let blue: Double

    /// Принимает `#rgb`/`#rrggbb` (решётка необязательна). Некорректный ввод
    /// даёт чёрный — токен всё равно задаётся литералом в коде, не из сети.
    public init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        if s.count == 3 {
            s = s.map { "\($0)\($0)" }.joined()
        }
        guard s.count == 6, let v = UInt32(s, radix: 16) else {
            self.red = 0; self.green = 0; self.blue = 0
            return
        }
        self.red = Double((v >> 16) & 0xff) / 255.0
        self.green = Double((v >> 8) & 0xff) / 255.0
        self.blue = Double(v & 0xff) / 255.0
    }

    /// Каноничная запись `#rrggbb` в нижнем регистре.
    public var hex: String {
        let r = Int((red * 255).rounded())
        let g = Int((green * 255).rounded())
        let b = Int((blue * 255).rounded())
        return String(format: "#%02x%02x%02x", r, g, b)
    }
}

/// Семантические роли цвета — по спеке §9, п.2–4.
/// Красный — только danger; янтарный — только progress; зелёный — только positive.
public enum SemanticColor: Equatable, Hashable, Sendable {
    /// Подтверждённая защита. ЕДИНСТВЕННЫЙ владелец зелёного.
    case positive
    /// Идёт процесс подключения/проверки.
    case progress
    /// Реальная опасность: окно без защиты, обрыв.
    case danger
    /// Выключено, нейтральное, вторичное.
    case neutral
}

/// Токены дизайна из спеки §9. Только значения, без вёрстки.
public struct DesignTokens: Equatable, Sendable {
    public static let standard = DesignTokens()

    // Фон: #0a0c10 → #12151b → #1a1e26
    public let background0: ColorToken
    public let background1: ColorToken
    public let background2: ColorToken

    // Границы: карточки #262b34, интерактив #5a6472
    public let borderCard: ColorToken
    public let borderInteractive: ColorToken

    // Текст: основной #e8ecf2, вторичный #aab4c2, приглушённый #8b95a3
    public let textPrimary: ColorToken
    public let textSecondary: ColorToken
    public let textMuted: ColorToken

    // Акцент — только выделение и фокус
    public let accent: ColorToken

    // Состояния: зелёный #3ddc97, янтарный #ffb454, красный #ff5f6d
    public let statePositive: ColorToken
    public let stateProgress: ColorToken
    public let stateDanger: ColorToken

    public init(
        background0: ColorToken = ColorToken(hex: "#0a0c10"),
        background1: ColorToken = ColorToken(hex: "#12151b"),
        background2: ColorToken = ColorToken(hex: "#1a1e26"),
        borderCard: ColorToken = ColorToken(hex: "#262b34"),
        borderInteractive: ColorToken = ColorToken(hex: "#5a6472"),
        textPrimary: ColorToken = ColorToken(hex: "#e8ecf2"),
        textSecondary: ColorToken = ColorToken(hex: "#aab4c2"),
        textMuted: ColorToken = ColorToken(hex: "#8b95a3"),
        accent: ColorToken = ColorToken(hex: "#6fb3ff"),
        statePositive: ColorToken = ColorToken(hex: "#3ddc97"),
        stateProgress: ColorToken = ColorToken(hex: "#ffb454"),
        stateDanger: ColorToken = ColorToken(hex: "#ff5f6d")
    ) {
        self.background0 = background0
        self.background1 = background1
        self.background2 = background2
        self.borderCard = borderCard
        self.borderInteractive = borderInteractive
        self.textPrimary = textPrimary
        self.textSecondary = textSecondary
        self.textMuted = textMuted
        self.accent = accent
        self.statePositive = statePositive
        self.stateProgress = stateProgress
        self.stateDanger = stateDanger
    }

    /// Маппинг семантической роли в конкретный токен.
    public func color(for semantic: SemanticColor) -> ColorToken {
        switch semantic {
        case .positive: return statePositive
        case .progress: return stateProgress
        case .danger:   return stateDanger
        case .neutral:  return textSecondary
        }
    }
}
