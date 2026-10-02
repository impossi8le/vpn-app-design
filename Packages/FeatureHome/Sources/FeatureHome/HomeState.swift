import Foundation
import CoreUI

/// Ситуация главного экрана. Одна ситуация = один экран/полу-состояние из макета.
/// Набор покрывает 21 состояние спеки §8.5 (некоторые состояния различаются
/// только текстом предупреждения, но их общая «ситуация» совпадает).
public enum HomeScreenSituation: String, CaseIterable, Equatable, Sendable {
    case notConnected       // 2. Не подключено (белая «Подключить»)
    case connecting         // 3. Подключение
    case error              // 3б. Ошибка подключения
    case verifying          // 4. Проверка защиты
    case connected          // 5. Подключено и защищено
    case switching          // 6. Смена подключения — окно без защиты
    case noPermission       // 8. Отказ в разрешении
    case allExpired         // 10. Всё истекло
    case noSubscriptions    // 13. Подписок нет
    case accessRevoked      // 14. Доступ отозван
    case protectionFailed   // 14б. Проверка не пройдена
    case escalating         // 15. Эскалация ожидания

    /// Семантика цвета (спека §9). Инвариант: `.positive` — ровно для `.connected`.
    public var semanticColor: SemanticColor {
        switch self {
        case .connected:
            return .positive
        case .switching:
            // Единственное место, где красный оправдан: окно без защиты (спека §9, п.2).
            return .danger
        case .connecting, .verifying, .escalating, .protectionFailed:
            // Идёт процесс / предупреждение. Зелёное заблокировано до замера.
            return .progress
        case .notConnected, .error, .noPermission, .allExpired,
             .noSubscriptions, .accessRevoked:
            // «Выключено» и «нет данных» — не опасность. Красный им не выдаётся.
            return .neutral
        }
    }

    /// Подпись блока защиты. До замера — честное «не проверено».
    public var protectionLabel: String {
        switch self {
        case .connected:        return "проверено"
        case .verifying:        return "не проверено"
        case .protectionFailed: return "не пройдено"
        default:                return "не проверено"
        }
    }

    /// Реальная опасность — окно без защиты во время смены подключения.
    public var isDangerous: Bool {
        switch self {
        case .switching: return true
        default:         return false
        }
    }
}

// MARK: - Кнопка

/// Заливка кнопки. Белая — движение вперёд; ghost — нейтральное/отмена/отключение.
public enum HomeButtonStyle: Equatable, Sendable { case primary, ghost }

public enum HomeButtonAction: Equatable, Sendable {
    case connect, cancel, disconnect, verifyAgain, retry
    case openSettings, refreshAccess, refresh, reauthenticate
}

public struct HomeButton: Equatable, Sendable {
    public let label: String
    public let style: HomeButtonStyle
    public let action: HomeButtonAction
    public let isEnabled: Bool
    public init(label: String, style: HomeButtonStyle, action: HomeButtonAction, isEnabled: Bool = true) {
        self.label = label; self.style = style; self.action = action; self.isEnabled = isEnabled
    }
}

// MARK: - Матрица кнопки (спека §8.5)

/// Матрица «состояние → текст и заливка главной кнопки», буквально из спеки §8.5.
public enum HomeButtonMatrix {
    public static func button(for situation: HomeScreenSituation) -> HomeButton {
        switch situation {
        case .notConnected:
            return HomeButton(label: "Подключить", style: .primary, action: .connect)
        case .connecting:
            return HomeButton(label: "Отменить", style: .ghost, action: .cancel)
        case .verifying:
            return HomeButton(label: "Отключить", style: .ghost, action: .disconnect)
        case .protectionFailed:
            return HomeButton(label: "Отключить", style: .ghost, action: .disconnect)
        case .connected:
            return HomeButton(label: "Отключить", style: .ghost, action: .disconnect)
        case .error, .escalating:
            return HomeButton(label: "Повторить", style: .primary, action: .retry)
        case .noPermission:
            return HomeButton(label: "Открыть Настройки", style: .primary, action: .openSettings)
        case .allExpired:
            return HomeButton(label: "Обновить доступ", style: .primary, action: .refreshAccess)
        case .noSubscriptions:
            return HomeButton(label: "Обновить", style: .primary, action: .refresh)
        case .accessRevoked:
            return HomeButton(label: "Обновить доступ", style: .primary, action: .refreshAccess)
        case .switching:
            return HomeButton(label: "Отменить", style: .ghost, action: .cancel)
        }
    }

    /// Вторая кнопка там, где спека её задаёт. Продукт без призывов к покупке.
    public static func secondary(for situation: HomeScreenSituation) -> HomeButton? {
        switch situation {
        case .protectionFailed:
            return HomeButton(label: "Проверить снова", style: .primary, action: .verifyAgain)
        case .noSubscriptions, .accessRevoked:
            return HomeButton(label: "Войти другим Telegram", style: .ghost, action: .reauthenticate)
        case .error, .escalating:
            return HomeButton(label: "Выбрать другое", style: .ghost, action: .connect)
        default:
            return nil
        }
    }
}

// MARK: - Эскалация ожидания (спека §8.5: пороги 10/20/30)

/// Пороги эскалации ожидания. Дольше 30 сек — честная ошибка, не «идёт подключение».
public enum HomeEscalation: Int, Equatable, Sendable, Comparable {
    case none = 0
    case ten = 10
    case twenty = 20
    case thirty = 30

    public static func < (a: HomeEscalation, b: HomeEscalation) -> Bool { a.rawValue < b.rawValue }

    /// Порог по прошедшим секундам. null→10s→20s→30s, дальше не растёт.
    public static func threshold(forSeconds s: Int) -> HomeEscalation {
        switch s {
        case ..<10:  return .none
        case 10..<20: return .ten
        case 20..<30: return .twenty
        default:      return .thirty
        }
    }

    /// Текст шага. Пустая строка — эскалации нет.
    public var copy: String {
        switch self {
        case .none:   return ""
        case .ten:    return "Подключение занимает больше времени, чем обычно…"
        case .twenty: return "Сервер отвечает медленно — возможно, перегружен."
        case .thirty: return "Не удалось подключиться. Попробуйте другое подключение."
        }
    }
}
