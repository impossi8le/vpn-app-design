import XCTest
import Foundation
@testable import CoreConfig
import CoreDomain

/// W2 — ConfigStore: атомарная запись .ovpn.
/// Тесты написаны ПЕРЕД реализацией (strict TDD).
final class FileProfileStoreTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        dir = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("coreconfig-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
    }

    private var store: FileProfileStore { FileProfileStore(baseDirectory: dir) }
    private var profileURL: URL { dir.appendingPathComponent("profile.ovpn") }
    private var backupURL: URL { dir.appendingPathComponent("profile.ovpn.bak") }
    private var tempURL: URL { dir.appendingPathComponent("profile.ovpn.tmp") }

    /// Минимальный валидный .ovpn: содержит директиву `client`.
    private func validOVPN(remote: String = "vpn.example.com") -> Data {
        Data("""
        client
        dev tun
        proto udp
        remote \(remote) 1194
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIB
        -----END CERTIFICATE-----
        </ca>
        """.utf8)
    }

    // MARK: - load

    func testLoadReturnsNilWhenNothingStored() throws {
        XCTAssertNil(try store.load())
    }

    func testLoadReturnsStoredProfileBytes() throws {
        let raw = validOVPN()
        try raw.write(to: profileURL)
        let loaded = try store.load()
        XCTAssertEqual(loaded?.raw, raw)
    }

    func testLoadIgnoresTempFileExtensionReadsOnlyProfile() throws {
        // Расширение читает только profile.ovpn, никогда .tmp.
        try validOVPN().write(to: tempURL)
        XCTAssertNil(try store.load(), "load() must read profile.ovpn only, never .tmp")
    }

    // MARK: - stage: validation before commit

    func testStageRejectsBrokenProfileAndLeavesOldUntouched() throws {
        let good = validOVPN(remote: "old.example.com")
        try good.write(to: profileURL)

        let broken = Data("this is not an openvpn config".utf8)
        XCTAssertThrowsError(try store.stage(broken)) { error in
            XCTAssertEqual(error as? ProfileStoreError, .invalidProfile)
        }

        // Старый профиль не тронут, временный файл не оставлен.
        XCTAssertEqual(try Data(contentsOf: profileURL), good)
        XCTAssertFalse(FileManager.default.fileExists(atPath: tempURL.path),
                       "invalid stage must not leave a temp file")
    }

    func testStageRejectsEmptyProfile() throws {
        XCTAssertThrowsError(try store.stage(Data())) { error in
            XCTAssertEqual(error as? ProfileStoreError, .invalidProfile)
        }
    }

    func testStageRejectsWhitespaceOnlyProfile() throws {
        XCTAssertThrowsError(try store.stage(Data("   \n\t\n ".utf8))) { error in
            XCTAssertEqual(error as? ProfileStoreError, .invalidProfile)
        }
    }

    func testStageAcceptsValidProfileAndWritesTempNotTarget() throws {
        let raw = validOVPN()
        let staged = try store.stage(raw)

        XCTAssertEqual(staged.profile.raw, raw)
        XCTAssertEqual(staged.temporaryURL, tempURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: tempURL.path))
        // stage не должен трогать profile.ovpn.
        XCTAssertFalse(FileManager.default.fileExists(atPath: profileURL.path))
    }

    func testStageDetectsClientDirectiveCaseInsensitivelyWithLeadingWhitespace() throws {
        let raw = Data("\n   CLIENT\nremote x 1194\n".utf8)
        XCTAssertNoThrow(try store.stage(raw))
    }

    // MARK: - commit: atomic replace, old -> backup

    func testCommitReplacesProfileAndPreservesBackup() throws {
        let old = validOVPN(remote: "old.example.com")
        let new = validOVPN(remote: "new.example.com")
        try old.write(to: profileURL)

        let staged = try store.stage(new)
        try store.commit(staged)

        // Новое — целиком, не усечено.
        XCTAssertEqual(try Data(contentsOf: profileURL), new)
        // Предыдущая рабочая версия сохранена.
        XCTAssertEqual(try Data(contentsOf: backupURL), old)
        // Временный файл израсходован.
        XCTAssertFalse(FileManager.default.fileExists(atPath: tempURL.path))
    }

    func testCommitIsAtomicNeverTruncated() throws {
        // После успешного commit файл байт-в-байт равен новому профилю,
        // а не префиксу/полузаписи.
        let new = validOVPN(remote: "atomic.example.com")
        let staged = try store.stage(new)
        try store.commit(staged)
        XCTAssertEqual(try Data(contentsOf: profileURL), new)
    }

    func testCommitFailsWhenStagedTempMissingAndLeavesOldIntact() throws {
        // Эмуляция обрыва: временный файл исчез до commit.
        // profile.ovpn обязан остаться прежним (старым), не усечённым.
        let old = validOVPN(remote: "old.example.com")
        try old.write(to: profileURL)

        let staged = try store.stage(validOVPN(remote: "new.example.com"))
        try FileManager.default.removeItem(at: staged.temporaryURL)

        XCTAssertThrowsError(try store.commit(staged))
        XCTAssertEqual(try Data(contentsOf: profileURL), old)
    }

    func testCommitWithoutExistingProfileWorks() throws {
        let new = validOVPN()
        let staged = try store.stage(new)
        try store.commit(staged)
        XCTAssertEqual(try Data(contentsOf: profileURL), new)
        XCTAssertFalse(FileManager.default.fileExists(atPath: backupURL.path))
    }

    // MARK: - rollback

    func testRollbackRestoresBackup() throws {
        let old = validOVPN(remote: "old.example.com")
        let new = validOVPN(remote: "new.example.com")
        try old.write(to: profileURL)
        try store.commit(try store.stage(new))

        try store.rollback()

        XCTAssertEqual(try Data(contentsOf: profileURL), old)
        XCTAssertEqual(try store.load()?.raw, old)
    }

    func testRollbackWithoutBackupThrows() throws {
        XCTAssertThrowsError(try store.rollback()) { error in
            XCTAssertEqual(error as? ProfileStoreError, .noBackup)
        }
    }

    // MARK: - clear (logout)

    func testClearRemovesProfileAndBackup() throws {
        try store.commit(try store.stage(validOVPN(remote: "old.example.com")))
        try store.commit(try store.stage(validOVPN(remote: "new.example.com")))
        XCTAssertTrue(FileManager.default.fileExists(atPath: profileURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: backupURL.path))

        try store.clear()

        XCTAssertFalse(FileManager.default.fileExists(atPath: profileURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: backupURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: tempURL.path))
        XCTAssertNil(try store.load())
    }
}
