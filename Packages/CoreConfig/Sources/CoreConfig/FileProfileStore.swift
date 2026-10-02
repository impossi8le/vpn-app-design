import Foundation
import CoreDomain

/// Ошибки хранилища профиля.
public enum ProfileStoreError: Error, Equatable {
    /// Профиль не прошёл валидацию — commit не должен доходить.
    case invalidProfile
    /// Нет .bak для отката.
    case noBackup
}

/// Валидатор `.ovpn` — минимальный и явный.
///
/// Определение «валидного .ovpn» (см. §7 архитектуры: валидация ДО применения):
///   1. тело непустое после отбрасывания пробельных символов;
///   2. присутствует хотя бы одна непустая строка, чья первая лексема —
///      директива из allow-list обязательных (регистр не важен, ведущие
///      пробелы/табы допускаются, комментарии `#` и `;` игнорируются):
///      `client`, `remote`, `dev`, `proto`, `pull`, `tls-client`.
///
/// Почему так, а не полноценный парсер: полный парсер — зона W4
/// (`TunnelKitCore`). Здесь важно лишь не пропустить в общий App Group
/// заведомо битый/пустой файл: одна такая запись ломает подключение всем
/// до ручного вмешательства. Проверка строго на входе, до `commit`.
public enum OpenVPNProfileValidator {
    /// Директивы, любой одной из которых достаточно, чтобы файл считался
    /// профилем OpenVPN. Покрывают как клиентские, так и сохраняемые сервером
    /// конфиги, где `client` может отсутствовать.
    public static let requiredDirectives: Set<String> = [
        "client", "remote", "dev", "proto", "pull", "tls-client"
    ]

    public static func isValid(_ raw: Data) -> Bool {
        guard !raw.isEmpty, let text = String(data: raw, encoding: .utf8) else { return false }
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            guard let first = line.first, first != "#", first != ";" else { continue }
            let directive = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
                .first.map(String.init)?.lowercased()
            if let directive, requiredDirectives.contains(directive) { return true }
        }
        return false
    }
}

/// Хранилище `.ovpn` в каталоге (App Group контейнер в проде, temp-каталог в тестах).
///
/// Реализует протокол согласованной записи §7:
///   stage(raw):    profile.ovpn.tmp ← raw, валидация → StagedProfile
///   commit(staged): profile.ovpn → profile.ovpn.bak, затем .tmp → profile.ovpn
///   rollback():    profile.ovpn.bak → profile.ovpn
///
/// Все замены — `replaceItemAt`/move в пределах одной файловой системы, то есть
/// атомарны: `profile.ovpn` никогда не наблюдаем полузаписанным — только
/// целиком старый или целиком новый.
public final class FileProfileStore: ProfileStore {

    public struct FileNames {
        public static let profile = "profile.ovpn"
        public static let backup = "profile.ovpn.bak"
        public static let temporary = "profile.ovpn.tmp"
    }

    private let baseDirectory: URL
    private let fileManager: FileManager

    public var profileURL: URL { baseDirectory.appendingPathComponent(FileNames.profile) }
    public var backupURL: URL { baseDirectory.appendingPathComponent(FileNames.backup) }
    public var temporaryURL: URL { baseDirectory.appendingPathComponent(FileNames.temporary) }

    public init(baseDirectory: URL, fileManager: FileManager = .default) {
        self.baseDirectory = baseDirectory
        self.fileManager = fileManager
    }

    // MARK: - ProfileStore

    public func load() throws -> Profile? {
        guard fileManager.fileExists(atPath: profileURL.path) else { return nil }
        // Читаем только profile.ovpn, никогда .tmp.
        let raw = try Data(contentsOf: profileURL)
        return Profile(raw: raw)
    }

    public func stage(_ raw: Data) throws -> StagedProfile {
        // Валидация ДО записи: невалидный конфиг не доходит до commit
        // и не оставляет temp-файла.
        guard OpenVPNProfileValidator.isValid(raw) else {
            throw ProfileStoreError.invalidProfile
        }
        try fileManager.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        // Перезаписываем temp атомарно: .atomic пишет в отдельный файл и делает
        // rename на место назначения, поэтому .tmp нельзя застать полузаписанным.
        try raw.write(to: temporaryURL, options: .atomic)
        return StagedProfile(profile: Profile(raw: raw), temporaryURL: temporaryURL)
    }

    public func commit(_ staged: StagedProfile) throws {
        // Обрыв: temp исчез до commit — прежний profile.ovpn обязан уцелеть.
        guard fileManager.fileExists(atPath: staged.temporaryURL.path) else {
            throw CocoaError(.fileNoSuchFile)
        }
        try fileManager.createDirectory(at: baseDirectory, withIntermediateDirectories: true)

        // Шаг 1: текущий profile.ovpn → profile.ovpn.bak (атомарный rename).
        if fileManager.fileExists(atPath: profileURL.path) {
            if fileManager.fileExists(atPath: backupURL.path) {
                try fileManager.removeItem(at: backupURL)
            }
            try fileManager.moveItem(at: profileURL, to: backupURL)
        }

        // Шаг 2: profile.ovpn.tmp → profile.ovpn (атомарный rename в пределах ФС).
        // Profile в StagedProfile может быть записан в другом temp-месте — тогда
        // сначала кладём его в наш .tmp, затем делаем rename.
        if staged.temporaryURL != temporaryURL {
            try? fileManager.removeItem(at: temporaryURL)
            try staged.profile.raw.write(to: temporaryURL, options: .atomic)
        }
        do {
            try fileManager.moveItem(at: temporaryURL, to: profileURL)
        } catch {
            // Откат шага 1, чтобы не остаться без рабочего профиля.
            if !fileManager.fileExists(atPath: profileURL.path),
               fileManager.fileExists(atPath: backupURL.path) {
                try? fileManager.moveItem(at: backupURL, to: profileURL)
            }
            throw error
        }
    }

    public func rollback() throws {
        guard fileManager.fileExists(atPath: backupURL.path) else {
            throw ProfileStoreError.noBackup
        }
        // Атомарная замена: бэкап возвращается на место профиля.
        if fileManager.fileExists(atPath: profileURL.path) {
            try fileManager.removeItem(at: profileURL)
        }
        try fileManager.moveItem(at: backupURL, to: profileURL)
    }

    // MARK: - clear (logout)

    /// Удаляет profile.ovpn И profile.ovpn.bak (и подчищает .tmp).
    /// Требование §7: при выходе из аккаунта конфиг и .bak удаляются.
    public func clear() throws {
        for url in [profileURL, backupURL, temporaryURL] {
            if fileManager.fileExists(atPath: url.path) {
                try fileManager.removeItem(at: url)
            }
        }
    }
}
